package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import java.io.IOException
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Signals a host port conflict against another active sandbox or host OS listener.
 */
class PortConflictException(
    val conflictingPort: Int?,
    val protocol: ForwardProtocol?,
    val conflictingProfileId: String? = null,
    val conflictingDisplayName: String? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Describes a detected port overlap on the same container or across active containers.
 */
data class PortConflictMatch(
    val port: Int,
    val protocol: ForwardProtocol,
    val candidateListenHost: ListenHost,
    val otherProfileId: String?,
    val otherDisplayName: String,
    val otherListenHost: String,
)

/**
 * Probes host socket availability before freezing or spawning a sandbox.
 */
fun interface HostPortProbe {
    @Throws(IOException::class)
    fun verifyAvailable(listenHost: ListenHost, port: Int, protocol: ForwardProtocol)
}

/**
 * Default no-op probe for isolated JVM tests with synthetic process launchers.
 */
object NoOpHostPortProbe : HostPortProbe {
    override fun verifyAvailable(listenHost: ListenHost, port: Int, protocol: ForwardProtocol) = Unit
}

/**
 * Probes local TCP and UDP socket availability on the Android host before container transitions.
 */
object SocketBindHostPortProbe : HostPortProbe {
    override fun verifyAvailable(listenHost: ListenHost, port: Int, protocol: ForwardProtocol) {
        if (port !in 1..65535) return
        val bindAddress = when (listenHost) {
            ListenHost.LOOPBACK_V4 -> InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            ListenHost.LAN_ALL_V4 -> InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
            ListenHost.LOOPBACK_V6 -> InetAddress.getByName("::1")
            ListenHost.LAN_ALL_V6 -> InetAddress.getByName("::")
        }
        val socketAddr = InetSocketAddress(bindAddress, port)
        try {
            when (protocol) {
                ForwardProtocol.TCP -> {
                    ServerSocket().use { server ->
                        server.reuseAddress = false
                        server.bind(socketAddr)
                    }
                }
                ForwardProtocol.UDP -> {
                    DatagramSocket(null).use { dgram ->
                        dgram.reuseAddress = false
                        dgram.bind(socketAddr)
                    }
                }
                ForwardProtocol.UNIX_STREAM -> Unit
            }
        } catch (e: IOException) {
            if (PortConflictDetector.isAddressInUseError(e)) {
                throw e
            }
        }
    }
}

/**
 * Validates host port rules for self-overlaps, cross-profile conflicts, and EADDRINUSE errors.
 */
object PortConflictDetector {

    private val ADDRESS_IN_USE_PORT_REGEX = Regex("""(?::|\bport\s+)(\d{1,5})\b""", RegexOption.IGNORE_CASE)

    fun isActivePortHoldingState(state: SandboxRunState?): Boolean = when (state) {
        SandboxRunState.STARTING,
        SandboxRunState.RUNNING,
        SandboxRunState.RESTORING -> true
        else -> false
    }

    /**
     * Returns true when two host bind addresses overlap in the same IPv4 or dual-stack IPv6 space.
     */
    fun listenHostsConflict(first: ListenHost, second: ListenHost): Boolean {
        if (first == second) return true
        if (first == ListenHost.LAN_ALL_V6 || second == ListenHost.LAN_ALL_V6) return true
        val firstIsV4 = first == ListenHost.LOOPBACK_V4 || first == ListenHost.LAN_ALL_V4
        val secondIsV4 = second == ListenHost.LOOPBACK_V4 || second == ListenHost.LAN_ALL_V4
        return firstIsV4 && secondIsV4
    }

    fun parseListenHost(wireLiteral: String): ListenHost? = when (wireLiteral.trim()) {
        "127.0.0.1" -> ListenHost.LOOPBACK_V4
        "0.0.0.0", "*" -> ListenHost.LAN_ALL_V4
        "[::1]", "::1" -> ListenHost.LOOPBACK_V6
        "[::]", "::" -> ListenHost.LAN_ALL_V6
        else -> null
    }

    /**
     * Checks whether two rules within the same profile bind overlapping fixed host ports.
     */
    fun detectSelfRuleConflict(
        rules: List<PortForwardRule>,
        containerDisplayName: String,
    ): PortConflictMatch? {
        val active = rules.filter {
            it.enabled && it.protocol != ForwardProtocol.UNIX_STREAM && it.hostPortStart > 0
        }
        for (i in active.indices) {
            val a = active[i]
            for (j in i + 1 until active.size) {
                val b = active[j]
                if (a.protocol != b.protocol) continue
                if (!listenHostsConflict(a.listenHost, b.listenHost)) continue
                val overlapPort = firstOverlappingPort(a.hostPortStart, a.hostPortEnd, b.hostPortStart, b.hostPortEnd)
                if (overlapPort != null) {
                    return PortConflictMatch(
                        port = overlapPort,
                        protocol = a.protocol,
                        candidateListenHost = b.listenHost,
                        otherProfileId = null,
                        otherDisplayName = containerDisplayName,
                        otherListenHost = a.listenHost.wireLiteral,
                    )
                }
            }
        }
        return null
    }

    /**
     * Checks whether any enabled fixed port rule in [candidateRules] collides with another active container.
     */
    fun detectCrossProfileConflict(
        targetProfileId: String,
        candidateRules: List<PortForwardRule>,
        profiles: Iterable<SandboxProfile>,
        runStates: Map<String, SandboxRunState>,
        publishedPortsByProfile: Map<String, List<PublishedPortMapping>> = emptyMap(),
    ): PortConflictMatch? {
        val candidates = candidateRules.filter {
            it.enabled && it.protocol != ForwardProtocol.UNIX_STREAM && it.hostPortStart > 0
        }
        if (candidates.isEmpty()) return null

        for (other in profiles) {
            if (other.id == targetProfileId) continue
            if (!isActivePortHoldingState(runStates[other.id])) continue

            for (candidate in candidates) {
                for (otherRule in other.portForwards) {
                    if (!otherRule.enabled || otherRule.protocol != candidate.protocol) continue
                    if (otherRule.hostPortStart <= 0) continue
                    if (!listenHostsConflict(candidate.listenHost, otherRule.listenHost)) continue
                    val overlapPort = firstOverlappingPort(
                        candidate.hostPortStart,
                        candidate.hostPortEnd,
                        otherRule.hostPortStart,
                        otherRule.hostPortEnd,
                    )
                    if (overlapPort != null) {
                        return PortConflictMatch(
                            port = overlapPort,
                            protocol = candidate.protocol,
                            candidateListenHost = candidate.listenHost,
                            otherProfileId = other.id,
                            otherDisplayName = other.displayName,
                            otherListenHost = otherRule.listenHost.wireLiteral,
                        )
                    }
                }

                val published = publishedPortsByProfile[other.id].orEmpty()
                for (mapping in published) {
                    if (mapping.protocol != candidate.protocol) continue
                    if (mapping.hostPort !in candidate.hostPortStart..candidate.hostPortEnd) continue
                    val otherHost = parseListenHost(mapping.listenHost)
                    if (otherHost != null && !listenHostsConflict(candidate.listenHost, otherHost)) continue
                    return PortConflictMatch(
                        port = mapping.hostPort,
                        protocol = candidate.protocol,
                        candidateListenHost = candidate.listenHost,
                        otherProfileId = other.id,
                        otherDisplayName = other.displayName,
                        otherListenHost = mapping.listenHost,
                    )
                }
            }
        }
        return null
    }

    fun formatConflictMessage(match: PortConflictMatch): String {
        val protoLabel = match.protocol.name.lowercase()
        return if (match.otherProfileId != null) {
            "Port ${match.port} is already bound by running container '${match.otherDisplayName}' " +
                "(${match.otherListenHost}:${match.port}/$protoLabel). Choose another host port or stop that container."
        } else {
            "Port ${match.port} is already configured on container '${match.otherDisplayName}' " +
                "(${match.otherListenHost}:${match.port}/$protoLabel)."
        }
    }

    fun toException(match: PortConflictMatch): PortConflictException = PortConflictException(
        conflictingPort = match.port,
        protocol = match.protocol,
        conflictingProfileId = match.otherProfileId,
        conflictingDisplayName = match.otherDisplayName,
        message = formatConflictMessage(match),
    )

    fun isAddressInUseError(throwable: Throwable?, stderrText: String = ""): Boolean {
        if (containsAddressInUseToken(stderrText)) return true
        var cur = throwable
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur is BindException) return true
            if (containsAddressInUseToken(cur.message.orEmpty())) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    fun extractAddressInUsePort(stderrText: String, attemptedRules: List<PortForwardRule>): Int? {
        val fixedRules = attemptedRules.filter {
            it.enabled && it.protocol != ForwardProtocol.UNIX_STREAM && it.hostPortStart > 0
        }
        for (line in stderrText.split('|', '\n')) {
            if (!containsAddressInUseToken(line)) continue
            val candidates = ADDRESS_IN_USE_PORT_REGEX.findAll(line)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .filter { it in 1..65535 }
                .toList()
            val matchingRulePort = candidates.firstOrNull { port ->
                fixedRules.any { port in it.hostPortStart..it.hostPortEnd }
            }
            if (matchingRulePort != null) return matchingRulePort
            if (candidates.isNotEmpty()) return candidates.first()
        }
        if (fixedRules.size == 1 && fixedRules.single().hostPortStart == fixedRules.single().hostPortEnd) {
            return fixedRules.single().hostPortStart
        }
        return null
    }

    fun formatAddressInUseMessage(
        stderrText: String,
        attemptedRules: List<PortForwardRule>,
    ): String {
        val port = extractAddressInUsePort(stderrText, attemptedRules)
        return if (port != null) {
            "Host port $port is already in use (address already in use). " +
                "Choose another host port or stop the conflicting process."
        } else {
            "Requested host port is already in use (address already in use). " +
                "Choose another host port or stop the conflicting process."
        }
    }

    /**
     * Builds initial deterministic published port entries for fixed TCP and UDP rules.
     */
    fun buildFixedPublishedPortMappings(rules: List<PortForwardRule>): List<PublishedPortMapping> {
        val out = ArrayList<PublishedPortMapping>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.protocol != ForwardProtocol.TCP && rule.protocol != ForwardProtocol.UDP) continue
            if (rule.hostPortStart <= 0) continue
            val count = (rule.hostPortEnd - rule.hostPortStart).coerceAtLeast(0)
            for (offset in 0..count) {
                val hostPort = rule.hostPortStart + offset
                val guestPort = rule.guestPortStart + offset
                if (hostPort in 1..65535 && guestPort in 1..65535) {
                    out.add(
                        PublishedPortMapping(
                            listenHost = rule.listenHost.wireLiteral,
                            hostPort = hostPort,
                            guestPort = guestPort,
                            protocol = rule.protocol,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun firstOverlappingPort(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int? {
        val low = maxOf(aStart, bStart)
        val high = minOf(aEnd, bEnd)
        return if (low <= high) low else null
    }

    private fun containsAddressInUseToken(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return lower.contains("address already in use") || lower.contains("eaddrinuse")
    }
}
