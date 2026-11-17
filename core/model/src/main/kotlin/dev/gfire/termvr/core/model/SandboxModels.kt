package dev.gfire.termvr.core.model

import kotlin.math.min
import kotlinx.serialization.Serializable

/**
 * Defines a persisted Linux sandbox profile and all pre-boot resource, storage, and network rules.
 */
@Serializable
data class SandboxProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val displayName: String,
    val imageRef: String,
    val muxMode: MuxMode = MuxMode.STDIO_MUX,
    val initDaemonArgv: List<String> = emptyList(),
    val defaultShellArgv: List<String> = emptyList(),
    val memoryMiB: Int = UNLIMITED_MEMORY_MIB,
    val cpuCores: Double = UNLIMITED_CPU_CORES,
    val guestPrivileged: Boolean = false,
    val volumes: List<VolumeMountSpec> = emptyList(),
    val tmpfsMounts: List<String> = DEFAULT_TMPFS_MOUNTS,
    val envVars: Map<String, String> = emptyMap(),
    val network: NetworkModeConfig = NetworkModeConfig(),
    val portForwards: List<PortForwardRule> = emptyList(),
    val trustHostCaCerts: Boolean = false,
    val guestClipboardReadPolicy: ClipboardReadPolicy = ClipboardReadPolicy.DISABLED,
    val autoCheckpointOnLowMemory: Boolean = true,
    val makeRootRshared: Boolean = false,
    val persistentRootfs: Boolean = false,
    val gitWorktree: GitWorktreeSpec? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported SandboxProfile schemaVersion: $schemaVersion"
        }
        require(ID_SLUG_REGEX.matches(id)) {
            "SandboxProfile id must match [a-z0-9-]{3,32}, found '$id'"
        }
        require(displayName.isNotBlank()) { "SandboxProfile displayName must not be blank" }
        require(imageRef.isNotBlank() && !imageRef.any { it.isWhitespace() }) {
            "SandboxProfile imageRef must be a non-blank OCI reference without whitespace"
        }
        require(initDaemonArgv.none { it.isEmpty() }) {
            "SandboxProfile initDaemonArgv must not contain empty elements"
        }
        require(defaultShellArgv.none { it.isEmpty() }) {
            "SandboxProfile defaultShellArgv must not contain empty elements"
        }
        require(memoryMiB == UNLIMITED_MEMORY_MIB || memoryMiB in MIN_MEMORY_MIB..MAX_MEMORY_MIB) {
            "SandboxProfile memoryMiB must be $UNLIMITED_MEMORY_MIB (unlimited) or within " +
                "$MIN_MEMORY_MIB..$MAX_MEMORY_MIB, found $memoryMiB"
        }
        require(cpuCores == UNLIMITED_CPU_CORES || (cpuCores.isFinite() && cpuCores > 0.0)) {
            "SandboxProfile cpuCores must be $UNLIMITED_CPU_CORES (unlimited) or finite and positive, found $cpuCores"
        }
        validateExt4VolumeConflicts()
        for (tmpfsPath in tmpfsMounts) {
            require(isCleanAbsoluteGuestPath(tmpfsPath)) {
                "SandboxProfile tmpfsMount must be a clean absolute path, found '$tmpfsPath'"
            }
        }
        for ((envKey, envValue) in envVars) {
            require(ENV_KEY_REGEX.matches(envKey)) {
                "SandboxProfile envVar key '$envKey' is invalid"
            }
            require(!envValue.contains('\u0000')) {
                "SandboxProfile envVar '$envKey' value must not contain NUL bytes"
            }
        }
    }

    private fun validateExt4VolumeConflicts() {
        val writerVolumes = HashSet<String>()
        val readerVolumes = HashSet<String>()
        if (persistentRootfs) {
            writerVolumes.add(rootfsVolumeName)
        }
        for (mount in volumes) {
            when (mount.kind) {
                VolumeKind.WORKSPACE_HOSTFS -> Unit
                VolumeKind.NAMED_EXT4LITE -> {
                    if (mount.readOnly) {
                        require(mount.sourceName !in writerVolumes) {
                            "Volume '${mount.sourceName}' cannot be mounted both read-write and read-only in profile '$id'"
                        }
                        readerVolumes.add(mount.sourceName)
                    } else {
                        require(mount.sourceName !in writerVolumes && mount.sourceName !in readerVolumes) {
                            "Volume '${mount.sourceName}' cannot have conflicting exclusive read-write mounts in profile '$id'"
                        }
                        writerVolumes.add(mount.sourceName)
                    }
                }
                VolumeKind.OVERLAY_EXT4LITE -> {
                    require(mount.sourceName !in writerVolumes) {
                        "Overlay base volume '${mount.sourceName}' cannot also be mounted read-write in profile '$id'"
                    }
                    readerVolumes.add(mount.sourceName)
                    val upper = mount.overlayUpperVolumeName
                    if (upper != null) {
                        require(upper !in writerVolumes && upper !in readerVolumes) {
                            "Overlay upper volume '$upper' conflicts with another mount in profile '$id'"
                        }
                        writerVolumes.add(upper)
                    }
                }
            }
        }
    }

    val hasMemoryLimit: Boolean
        get() = memoryMiB > 0

    val hasCpuLimit: Boolean
        get() = cpuCores > 0.0

    val rootfsVolumeName: String
        get() = "$ROOTFS_VOLUME_PREFIX$id"

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val ROOTFS_VOLUME_PREFIX: String = "rootfs-"
        const val UNLIMITED_MEMORY_MIB: Int = 0
        const val UNLIMITED_CPU_CORES: Double = 0.0
        const val MIN_MEMORY_MIB: Int = 256
        const val MAX_MEMORY_MIB: Int = 12288
        const val DEFAULT_MEMORY_MIB: Int = UNLIMITED_MEMORY_MIB
        const val DEFAULT_CPU_CORES: Double = UNLIMITED_CPU_CORES

        val DEFAULT_TMPFS_MOUNTS: List<String> = listOf("/tmp", "/run")

        private val ID_SLUG_REGEX = Regex("^[a-z0-9-]{3,32}$")
        private val ENV_KEY_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        fun isValidProfileId(candidate: String): Boolean = ID_SLUG_REGEX.matches(candidate)

        fun clampMemoryMiB(rawMiB: Int): Int =
            if (rawMiB <= 0) UNLIMITED_MEMORY_MIB else rawMiB.coerceIn(MIN_MEMORY_MIB, MAX_MEMORY_MIB)

        fun clampCpuCores(
            requestedCores: Double,
            hostAvailableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        ): Double {
            require(requestedCores.isFinite()) {
                "Requested cpuCores must be finite"
            }
            if (requestedCores <= 0.0) {
                return UNLIMITED_CPU_CORES
            }
            val maxHostCores = hostAvailableProcessors.coerceAtLeast(1).toDouble()
            return min(requestedCores, maxHostCores)
        }
    }
}

@Serializable
enum class MuxMode {
    STDIO_MUX,
    RAW_PTY,
}

@Serializable
enum class ClipboardReadPolicy {
    DISABLED,
    ASK_EACH_TIME,
    SESSION_ALLOWED,
}

/**
 * Defines a persistent or copy-on-write volume mount mapped into a sandbox guest path.
 */
@Serializable
data class VolumeMountSpec(
    val sourceName: String,
    val guestMountPath: String,
    val kind: VolumeKind,
    val readOnly: Boolean = false,
    val overlayUpperVolumeName: String? = null,
) {
    init {
        require(isValidVolumeName(sourceName)) {
            "VolumeMountSpec sourceName must match [a-z0-9][a-z0-9._-]{0,63}, found '$sourceName'"
        }
        require(isCleanAbsoluteGuestPath(guestMountPath)) {
            "VolumeMountSpec guestMountPath must be an absolute path without ':', ',', or '..', found '$guestMountPath'"
        }
        if (kind == VolumeKind.OVERLAY_EXT4LITE) {
            require(!readOnly) {
                "VolumeMountSpec with OVERLAY_EXT4LITE must have readOnly == false"
            }
            val normalizedGuestPath = guestMountPath.trimEnd('/').ifEmpty { "/" }
            require(normalizedGuestPath !in FORBIDDEN_OVERLAY_GUEST_PATHS) {
                "VolumeMountSpec OVERLAY_EXT4LITE guestMountPath cannot target '$normalizedGuestPath'"
            }
            if (overlayUpperVolumeName != null) {
                require(isValidVolumeName(overlayUpperVolumeName)) {
                    "VolumeMountSpec overlayUpperVolumeName must match [a-z0-9][a-z0-9._-]{0,63}, found '$overlayUpperVolumeName'"
                }
                require(overlayUpperVolumeName != sourceName) {
                    "VolumeMountSpec overlayUpperVolumeName must not equal sourceName '$sourceName'"
                }
            }
        } else {
            require(overlayUpperVolumeName == null) {
                "VolumeMountSpec overlayUpperVolumeName is only permitted when kind is OVERLAY_EXT4LITE"
            }
        }
    }

    val mountMode: VolumeMountMode
        get() = when {
            kind == VolumeKind.OVERLAY_EXT4LITE && overlayUpperVolumeName != null -> VolumeMountMode.COW_PERSISTENT
            kind == VolumeKind.OVERLAY_EXT4LITE -> VolumeMountMode.COW_EPHEMERAL
            readOnly -> VolumeMountMode.SHARED_RO
            else -> VolumeMountMode.EXCLUSIVE_RW
        }

    companion object {
        const val MAX_VOLUME_NAME_LENGTH: Int = 64
        private val VOLUME_SOURCE_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val FORBIDDEN_OVERLAY_GUEST_PATHS = setOf("/", "/proc", "/sys", "/dev")

        fun isValidVolumeName(candidate: String): Boolean =
            VOLUME_SOURCE_REGEX.matches(candidate) && candidate != "." && candidate != ".."

        fun forMode(
            baseVolumeName: String,
            guestMountPath: String,
            mode: VolumeMountMode,
            overlayUpperVolumeName: String? = null,
        ): VolumeMountSpec = when (mode) {
            VolumeMountMode.EXCLUSIVE_RW -> VolumeMountSpec(
                sourceName = baseVolumeName,
                guestMountPath = guestMountPath,
                kind = VolumeKind.NAMED_EXT4LITE,
                readOnly = false,
            )
            VolumeMountMode.SHARED_RO -> VolumeMountSpec(
                sourceName = baseVolumeName,
                guestMountPath = guestMountPath,
                kind = VolumeKind.NAMED_EXT4LITE,
                readOnly = true,
            )
            VolumeMountMode.COW_PERSISTENT -> VolumeMountSpec(
                sourceName = baseVolumeName,
                guestMountPath = guestMountPath,
                kind = VolumeKind.OVERLAY_EXT4LITE,
                readOnly = false,
                overlayUpperVolumeName = requireNotNull(overlayUpperVolumeName?.trim()?.takeIf { it.isNotEmpty() }) {
                    "Persistent CoW branch requires an upper volume name"
                },
            )
            VolumeMountMode.COW_EPHEMERAL -> VolumeMountSpec(
                sourceName = baseVolumeName,
                guestMountPath = guestMountPath,
                kind = VolumeKind.OVERLAY_EXT4LITE,
                readOnly = false,
                overlayUpperVolumeName = null,
            )
        }

        fun suggestOverlayUpperVolumeName(
            baseVolumeName: String,
            profileNameOrId: String,
        ): String {
            val cleanBase = sanitizeVolumeSegment(baseVolumeName).ifEmpty { "vol" }
            val cleanProfile = sanitizeVolumeSegment(profileNameOrId).ifEmpty { "sandbox" }
            val separator = "-wt-"
            val maxBaseChars = (MAX_VOLUME_NAME_LENGTH - separator.length - 8).coerceAtLeast(8)
            val truncatedBase = cleanBase.take(maxBaseChars).trimEnd('-', '.', '_').ifEmpty { "vol" }
            val remainingForProfile = (MAX_VOLUME_NAME_LENGTH - truncatedBase.length - separator.length).coerceAtLeast(1)
            val truncatedProfile = cleanProfile.take(remainingForProfile).trimEnd('-', '.', '_').ifEmpty { "1" }
            val candidate = "$truncatedBase$separator$truncatedProfile"
            return if (candidate == baseVolumeName) {
                "${candidate.take(MAX_VOLUME_NAME_LENGTH - 2)}-2"
            } else {
                candidate
            }
        }

        private fun sanitizeVolumeSegment(raw: String): String =
            raw.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-', '.', '_')
    }
}

@Serializable
enum class VolumeKind {
    NAMED_EXT4LITE,
    WORKSPACE_HOSTFS,
    OVERLAY_EXT4LITE,
}

@Serializable
enum class VolumeLockMode {
    NONE,
    SHARED,
    EXCLUSIVE,
    ;

    val isInUse: Boolean
        get() = this != NONE

    val allowsSharedReaders: Boolean
        get() = this != EXCLUSIVE

    companion object {
        fun fromWireOrInUse(wireMode: String?, inUse: Boolean): VolumeLockMode =
            when (wireMode?.trim()?.lowercase()) {
                "exclusive" -> EXCLUSIVE
                "shared" -> SHARED
                "none" -> NONE
                else -> if (inUse) EXCLUSIVE else NONE
            }
    }
}

@Serializable
enum class VolumeMountMode {
    EXCLUSIVE_RW,
    SHARED_RO,
    COW_PERSISTENT,
    COW_EPHEMERAL,
}

/**
 * Summarizes a shared host-side bare Git project repository under `<filesDir>/git-projects/<id>`.
 */
@Serializable
data class GitProjectSummary(
    val id: String,
    val name: String,
    val defaultBranch: String = DEFAULT_BRANCH,
    val createdAtEpochMs: Long,
    val worktreeCount: Int = 0,
    val diskBytes: Long = 0L,
) {
    init {
        require(GitWorktreeSpec.isValidProjectId(id)) {
            "GitProjectSummary id must match [a-zA-Z0-9][a-zA-Z0-9_.-]{0,63} without '..', found '$id'"
        }
        require(name.isNotBlank()) { "GitProjectSummary name must not be blank" }
        require(GitWorktreeSpec.isValidBranchName(defaultBranch)) {
            "GitProjectSummary defaultBranch '${defaultBranch}' violates git-check-ref-format"
        }
        require(createdAtEpochMs >= 0L) { "GitProjectSummary createdAtEpochMs must be non-negative" }
        require(worktreeCount >= 0) { "GitProjectSummary worktreeCount must be non-negative" }
        require(diskBytes >= 0L) { "GitProjectSummary diskBytes must be non-negative" }
    }

    companion object {
        const val DEFAULT_BRANCH: String = "main"
    }
}

/**
 * Attaches a sandbox profile to a per-container linked `git worktree` of a shared bare Git project.
 */
@Serializable
data class GitWorktreeSpec(
    val projectId: String,
    val branchName: String,
) {
    init {
        require(isValidProjectId(projectId)) {
            "GitWorktreeSpec projectId must match [a-zA-Z0-9][a-zA-Z0-9_.-]{0,63} without '..', found '$projectId'"
        }
        require(isValidBranchName(branchName)) {
            "GitWorktreeSpec branchName '$branchName' violates git-check-ref-format"
        }
    }

    companion object {
        const val MAX_PROJECT_ID_LENGTH: Int = 64
        const val MAX_BRANCH_NAME_LENGTH: Int = 200
        private val PROJECT_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")
        private val FORBIDDEN_REF_CHARS = setOf(' ', '~', '^', ':', '?', '*', '[', '\\')

        fun isValidProjectId(candidate: String): Boolean {
            if (!PROJECT_ID_REGEX.matches(candidate)) return false
            if (candidate.contains("..")) return false
            return true
        }

        fun isValidBranchName(candidate: String): Boolean {
            if (candidate.isEmpty() || candidate.length > MAX_BRANCH_NAME_LENGTH) return false
            if (candidate == "@" || candidate == "HEAD") return false
            if (candidate.startsWith('-') || candidate.startsWith('/') || candidate.endsWith('/') || candidate.endsWith('.')) {
                return false
            }
            if (candidate.contains("..") || candidate.contains("@{") || candidate.contains("//")) {
                return false
            }
            for (ch in candidate) {
                if (
                    ch == '\u0000' ||
                    ch.code < 0x20 ||
                    ch.code == 0x7F ||
                    Character.isISOControl(ch) ||
                    ch.isWhitespace() ||
                    ch in FORBIDDEN_REF_CHARS
                ) {
                    return false
                }
            }
            val segments = candidate.split('/')
            for (segment in segments) {
                if (
                    segment.isEmpty() ||
                    segment == "." ||
                    segment == ".." ||
                    segment.startsWith('.') ||
                    segment.endsWith(".lock", ignoreCase = true)
                ) {
                    return false
                }
            }
            return true
        }

        fun suggestProjectId(rawName: String): String {
            val cleaned = rawName.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_.-]+"), "-")
                .replace(Regex("\\.{2,}"), ".")
                .replace(Regex("-{2,}"), "-")
                .trim('-', '.', '_')
                .take(MAX_PROJECT_ID_LENGTH)
                .trimEnd('-', '.', '_')
            return if (cleaned.isNotEmpty() && isValidProjectId(cleaned)) cleaned else "project"
        }

        fun defaultBranchForProfile(profileIdOrName: String): String {
            val slug = profileIdOrName.trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-', '_')
                .take(48)
                .trimEnd('-', '_')
                .ifEmpty { "container" }
            val candidate = "wt/$slug"
            return if (isValidBranchName(candidate)) candidate else "wt/container"
        }
    }
}

/**
 * Configures virtual networking backend, LAN isolation, WireGuard tunnel, and outbound proxy rules.
 */
@Serializable
data class NetworkModeConfig(
    val backend: NetBackend = NetBackend.NAT,
    val isolateLanEgress: Boolean = false,
    val wireGuardConfigName: String? = null,
    val outboundProxy: OutboundProxyConfig = OutboundProxyConfig(),
) {
    init {
        if (backend == NetBackend.WIREGUARD) {
            require(!wireGuardConfigName.isNullOrBlank()) {
                "NetworkModeConfig wireGuardConfigName is required when backend is WIREGUARD"
            }
        }
        if (wireGuardConfigName != null) {
            require(
                WG_CONFIG_NAME_REGEX.matches(wireGuardConfigName) &&
                    wireGuardConfigName != "." &&
                    wireGuardConfigName != "..",
            ) {
                "NetworkModeConfig wireGuardConfigName must match [a-zA-Z0-9._-]+"
            }
        }
    }

    companion object {
        private val WG_CONFIG_NAME_REGEX = Regex("^[a-zA-Z0-9._-]{1,64}$")
    }
}

@Serializable
enum class NetBackend {
    NAT,
    WIREGUARD,
    NONE,
}

/**
 * Configures outbound proxy routing, proxy DNS tunneling, transparent TCP redirect, and bypass targets.
 */
@Serializable
data class OutboundProxyConfig(
    val mode: OutboundProxyMode = OutboundProxyMode.DIRECT,
    val customEndpoint: ProxyEndpointSpec? = null,
    val routeDnsViaProxy: Boolean = true,
    val transparentTcp: Boolean = false,
    val noProxyEntries: List<String> = DEFAULT_NO_PROXY,
) {
    init {
        if (mode == OutboundProxyMode.CUSTOM) {
            require(customEndpoint != null) {
                "OutboundProxyConfig customEndpoint is required when mode is CUSTOM"
            }
        }
        require(noProxyEntries.all { it.isNotBlank() && !it.any(Char::isWhitespace) }) {
            "OutboundProxyConfig noProxyEntries must not contain blank or whitespace strings"
        }
    }

    companion object {
        val DEFAULT_NO_PROXY: List<String> = listOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            ".svc",
            ".cluster.local",
        )
    }
}

@Serializable
enum class OutboundProxyMode {
    DIRECT,
    ANDROID_SYSTEM,
    CUSTOM,
}

/**
 * Describes an upstream HTTP, HTTPS, SOCKS5, SOCKS4, or SOCKS4A proxy endpoint without raw secrets.
 */
@Serializable
data class ProxyEndpointSpec(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String? = null,
    val credentialKeyAlias: String? = null,
    val tlsServerName: String? = null,
) {
    init {
        require(
            host.isNotBlank() &&
                !host.contains("://") &&
                !host.contains('@') &&
                !host.any { it.isWhitespace() },
        ) {
            "ProxyEndpointSpec host must be a hostname or IP literal without scheme or credentials"
        }
        require(port in 1..65535) {
            "ProxyEndpointSpec port must be within 1..65535, found $port"
        }
        if (username != null) {
            require(username.isNotEmpty() && !username.contains(':') && !username.contains('\u0000')) {
                "ProxyEndpointSpec username must not be empty or contain ':'"
            }
        }
        if (credentialKeyAlias != null) {
            require(KEY_ALIAS_REGEX.matches(credentialKeyAlias)) {
                "ProxyEndpointSpec credentialKeyAlias must match [a-zA-Z0-9._-]{1,128}"
            }
        }
        if (tlsServerName != null) {
            require(tlsServerName.isNotBlank() && !tlsServerName.any { it.isWhitespace() }) {
                "ProxyEndpointSpec tlsServerName must not be blank or contain whitespace"
            }
        }
    }

    companion object {
        private val KEY_ALIAS_REGEX = Regex("^[a-zA-Z0-9._-]{1,128}$")
    }
}

@Serializable
enum class ProxyProtocol {
    HTTP,
    HTTPS,
    SOCKS5,
    SOCKS4,
    SOCKS4A,
}

/**
 * Defines a host-to-guest TCP, UDP, or Unix domain stream port forwarding rule.
 */
@Serializable
data class PortForwardRule(
    val id: String,
    val protocol: ForwardProtocol,
    val listenHost: ListenHost = ListenHost.LOOPBACK_V4,
    val hostPortStart: Int,
    val hostPortEnd: Int = hostPortStart,
    val guestPortStart: Int,
    val guestPortEnd: Int = guestPortStart,
    val unixSocketName: String? = null,
    val guestUnixPath: String? = null,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "PortForwardRule id must not be blank" }
        when (protocol) {
            ForwardProtocol.TCP, ForwardProtocol.UDP -> {
                require(guestPortStart in 1..65535 && guestPortEnd in guestPortStart..65535) {
                    "PortForwardRule guest port range $guestPortStart..$guestPortEnd must be within 1..65535"
                }
                require(hostPortStart in 0..65535 && hostPortEnd in hostPortStart..65535) {
                    "PortForwardRule host port range $hostPortStart..$hostPortEnd must be within 0..65535"
                }
                if (hostPortStart == 0) {
                    require(hostPortEnd == 0) {
                        "PortForwardRule ephemeral hostPortStart 0 requires hostPortEnd 0"
                    }
                } else {
                    require((hostPortEnd - hostPortStart) == (guestPortEnd - guestPortStart)) {
                        "PortForwardRule host range length must match guest range length"
                    }
                }
                require(
                    !isLanDockerPortBlocked(
                        listenHost = listenHost,
                        hostPortStart = hostPortStart,
                        hostPortEnd = hostPortEnd,
                        guestPortStart = guestPortStart,
                        guestPortEnd = guestPortEnd,
                    ),
                ) {
                    "Unauthenticated Docker port $DOCKER_UNAUTHENTICATED_TCP_PORT cannot bind on LAN (${listenHost.wireLiteral})"
                }
            }
            ForwardProtocol.UNIX_STREAM -> {
                require(
                    !unixSocketName.isNullOrBlank() &&
                        UNIX_SOCKET_NAME_REGEX.matches(unixSocketName) &&
                        unixSocketName != "." &&
                        unixSocketName != "..",
                ) {
                    "PortForwardRule unixSocketName must match [a-z0-9._-]+"
                }
                require(!guestUnixPath.isNullOrBlank() && isCleanAbsoluteGuestPath(guestUnixPath)) {
                    "PortForwardRule guestUnixPath must be a clean absolute path"
                }
            }
        }
    }

    val exposesLan: Boolean
        get() = enabled && protocol != ForwardProtocol.UNIX_STREAM && listenHost.exposesLan

    fun downgradeLanToLoopback(): PortForwardRule = when (listenHost) {
        ListenHost.LAN_ALL_V4 -> copy(listenHost = ListenHost.LOOPBACK_V4)
        ListenHost.LAN_ALL_V6 -> copy(listenHost = ListenHost.LOOPBACK_V6)
        ListenHost.LOOPBACK_V4, ListenHost.LOOPBACK_V6 -> this
    }

    companion object {
        const val DOCKER_UNAUTHENTICATED_TCP_PORT: Int = 2375
        private val UNIX_SOCKET_NAME_REGEX = Regex("^[a-z0-9._-]+$")

        fun isLanDockerPortBlocked(
            listenHost: ListenHost,
            hostPortStart: Int,
            hostPortEnd: Int = hostPortStart,
            guestPortStart: Int,
            guestPortEnd: Int = guestPortStart,
        ): Boolean {
            if (!listenHost.exposesLan) return false
            val guestTouchesDockerPort = DOCKER_UNAUTHENTICATED_TCP_PORT in guestPortStart..guestPortEnd
            val hostTouchesDockerPort =
                hostPortStart > 0 && DOCKER_UNAUTHENTICATED_TCP_PORT in hostPortStart..hostPortEnd
            return guestTouchesDockerPort || hostTouchesDockerPort
        }
    }
}

@Serializable
enum class ForwardProtocol {
    TCP,
    UDP,
    UNIX_STREAM,
}

@Serializable
enum class ListenHost(val wireLiteral: String, val exposesLan: Boolean) {
    LOOPBACK_V4("127.0.0.1", false),
    LOOPBACK_V6("[::1]", false),
    LAN_ALL_V4("0.0.0.0", true),
    LAN_ALL_V6("[::]", true),
}

@Serializable
enum class SandboxRunState {
    STOPPED,
    STARTING,
    RUNNING,
    FREEZING,
    FROZEN,
    RESTORING,
    FAILED,
    ;

    val locksWorkspaceWrites: Boolean
        get() = this == FREEZING || this == FROZEN
}

/**
 * Identifies the host-native Linux architecture supported without cross-ISA CPU emulation.
 */
@Serializable
enum class GuestLinuxArch(val ociArch: String, val androidAbi: String) {
    ARM64(ociArch = "arm64", androidAbi = "arm64-v8a"),
    AMD64(ociArch = "amd64", androidAbi = "x86_64"),
    ;

    companion object {
        fun fromAndroidAbi(abi: String): GuestLinuxArch? =
            entries.firstOrNull { it.androidAbi == abi }

        fun fromOciArch(arch: String): GuestLinuxArch? =
            entries.firstOrNull { it.ociArch == arch }
    }
}

/**
 * Describes a curated host-ISA Linux distribution entry in the catalog gallery.
 */
@Serializable
data class DistributionCatalogEntry(
    val id: String,
    val distroSlug: String,
    val displayName: String,
    val version: String,
    val summary: String,
    val architecture: GuestLinuxArch,
    val imageRef: String,
    val approximateDownloadMiB: Int,
    val defaultMemoryMiB: Int = SandboxProfile.UNLIMITED_MEMORY_MIB,
    val defaultShellArgv: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "DistributionCatalogEntry id must not be blank" }
        require(SandboxProfile.isValidProfileId(distroSlug)) {
            "DistributionCatalogEntry distroSlug must match [a-z0-9-]{3,32}"
        }
        require(displayName.isNotBlank()) { "DistributionCatalogEntry displayName must not be blank" }
        require(version.isNotBlank()) { "DistributionCatalogEntry version must not be blank" }
        require(summary.isNotBlank()) { "DistributionCatalogEntry summary must not be blank" }
        require(imageRef.isNotBlank() && !imageRef.any { it.isWhitespace() }) {
            "DistributionCatalogEntry imageRef must be valid"
        }
        require(approximateDownloadMiB > 0) {
            "DistributionCatalogEntry approximateDownloadMiB must be positive"
        }
        require(
            defaultMemoryMiB == SandboxProfile.UNLIMITED_MEMORY_MIB ||
                defaultMemoryMiB >= SandboxProfile.MIN_MEMORY_MIB,
        ) {
            "DistributionCatalogEntry defaultMemoryMiB must be ${SandboxProfile.UNLIMITED_MEMORY_MIB} " +
                "or at least ${SandboxProfile.MIN_MEMORY_MIB}, found $defaultMemoryMiB"
        }
    }

    val recommendedMemoryMiB: Int
        get() = defaultMemoryMiB

    fun toSandboxProfile(
        profileId: String = distroSlug,
        profileDisplayName: String = displayName,
        persistentRootfs: Boolean = false,
    ): SandboxProfile = SandboxProfile(
        id = profileId,
        displayName = profileDisplayName,
        imageRef = imageRef,
        muxMode = MuxMode.STDIO_MUX,
        defaultShellArgv = defaultShellArgv,
        memoryMiB = SandboxProfile.clampMemoryMiB(defaultMemoryMiB),
        volumes = listOf(
            VolumeMountSpec(
                sourceName = profileId,
                guestMountPath = "/workspace",
                kind = VolumeKind.WORKSPACE_HOSTFS,
                readOnly = false,
            ),
        ),
        persistentRootfs = persistentRootfs,
    )

    companion object {
        val CURATED_ENTRIES: List<DistributionCatalogEntry> = GuestLinuxArch.entries.flatMap { arch ->
            listOf(
                DistributionCatalogEntry(
                    id = "debian-trixie",
                    distroSlug = "debian",
                    displayName = "Debian 13",
                    version = "13",
                    summary = "General-purpose Linux environment with apt package management and broad tool support.",
                    architecture = arch,
                    imageRef = "docker.io/library/debian:trixie-slim",
                    approximateDownloadMiB = 30,
                    defaultMemoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
                    defaultShellArgv = listOf("/bin/bash"),
                ),
                DistributionCatalogEntry(
                    id = "ubuntu-noble",
                    distroSlug = "ubuntu",
                    displayName = "Ubuntu 24.04",
                    version = "24.04",
                    summary = "Long-term support Linux environment with apt packages for development tools and servers.",
                    architecture = arch,
                    imageRef = "docker.io/library/ubuntu:24.04",
                    approximateDownloadMiB = 32,
                    defaultMemoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
                    defaultShellArgv = listOf("/bin/bash"),
                ),
                DistributionCatalogEntry(
                    id = "fedora-42",
                    distroSlug = "fedora",
                    displayName = "Fedora 42",
                    version = "42",
                    summary = "Up-to-date Linux environment with dnf packages and modern developer toolchains.",
                    architecture = arch,
                    imageRef = "docker.io/library/fedora:42",
                    approximateDownloadMiB = 72,
                    defaultMemoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
                    defaultShellArgv = listOf("/bin/bash"),
                ),
                DistributionCatalogEntry(
                    id = "alpine-3-21",
                    distroSlug = "alpine",
                    displayName = "Alpine 3.21",
                    version = "3.21",
                    summary = "Lightweight Linux base with apk packages and fast shell startup.",
                    architecture = arch,
                    imageRef = "docker.io/library/alpine:3.21",
                    approximateDownloadMiB = 15,
                    defaultMemoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
                    defaultShellArgv = listOf("/bin/ash"),
                ),
            )
        }

        fun forArchitecture(arch: GuestLinuxArch): List<DistributionCatalogEntry> =
            CURATED_ENTRIES.filter { it.architecture == arch }

        fun forAndroidAbi(abi: String): List<DistributionCatalogEntry> {
            val arch = GuestLinuxArch.fromAndroidAbi(abi) ?: return emptyList()
            return forArchitecture(arch)
        }
    }
}

/**
 * Captures live CPU, memory, process, port, and badge telemetry for one running sandbox.
 */
@Serializable
data class SandboxTelemetrySnapshot(
    val profileId: String,
    val state: SandboxRunState = SandboxRunState.RUNNING,
    val cpuPercent: Double = 0.0,
    val cpuNanos: Long = 0L,
    val pssBytes: Long = 0L,
    val guestRssBytes: Long = 0L,
    val hostChildProcessCount: Int = 0,
    val guestProcessCount: Int = 0,
    val guestListenPorts: List<Int> = emptyList(),
    val exposesLan: Boolean = false,
    val activeProxyLabel: String? = null,
    val sampledAtEpochMillis: Long = 0L,
) {
    init {
        require(SandboxProfile.isValidProfileId(profileId)) {
            "SandboxTelemetrySnapshot profileId must match [a-z0-9-]{3,32}"
        }
        require(cpuPercent.isFinite() && cpuPercent >= 0.0) {
            "SandboxTelemetrySnapshot cpuPercent must be non-negative"
        }
        require(cpuNanos >= 0L) { "SandboxTelemetrySnapshot cpuNanos must be non-negative" }
        require(pssBytes >= 0L) { "SandboxTelemetrySnapshot pssBytes must be non-negative" }
        require(guestRssBytes >= 0L) { "SandboxTelemetrySnapshot guestRssBytes must be non-negative" }
        require(hostChildProcessCount >= 0) {
            "SandboxTelemetrySnapshot hostChildProcessCount must be non-negative"
        }
        require(guestProcessCount >= 0) {
            "SandboxTelemetrySnapshot guestProcessCount must be non-negative"
        }
        require(guestListenPorts.all { it in 1..65535 }) {
            "SandboxTelemetrySnapshot guestListenPorts must be within 1..65535"
        }
    }

    val pssMiB: Long
        get() = pssBytes / BYTES_PER_MIB

    companion object {
        private const val BYTES_PER_MIB: Long = 1024L * 1024L
    }
}

@Serializable
enum class PhantomProcessRemediation {
    NONE,
    ANDROID_14_DEV_TOGGLE,
    ANDROID_12_13_ADB_COMMAND,
}

@Serializable
data class StorageUsageBreakdown(
    val layersBytes: Long = 0L,
    val volumesBytes: Long = 0L,
    val workspacesBytes: Long = 0L,
    val checkpointsBytes: Long = 0L,
) {
    init {
        require(layersBytes >= 0L && volumesBytes >= 0L && workspacesBytes >= 0L && checkpointsBytes >= 0L) {
            "StorageUsageBreakdown byte counts must be non-negative"
        }
    }

    val totalBytes: Long
        get() = layersBytes + volumesBytes + workspacesBytes + checkpointsBytes
}

/**
 * Summarizes Android host kernel page size, child-process budget, phantom-killer exits, and disk usage.
 */
@Serializable
data class HostReadinessReport(
    val hostSdkInt: Int,
    val hostAbi: String,
    val guestArch: GuestLinuxArch? = GuestLinuxArch.fromAndroidAbi(hostAbi),
    val kernelPageSizeBytes: Int = 4096,
    val totalHostRamMiB: Int = 0,
    val freeDiskMiB: Int = 0,
    val liveChildProcessCount: Int = 0,
    val phantomKillerDetected: Boolean = false,
    val remediationKind: PhantomProcessRemediation = PhantomProcessRemediation.NONE,
    val lastKilledExitReasonCode: Int? = null,
    val diskUsage: StorageUsageBreakdown = StorageUsageBreakdown(),
) {
    init {
        require(hostSdkInt >= MIN_SUPPORTED_SDK_INT) {
            "HostReadinessReport hostSdkInt must be at least $MIN_SUPPORTED_SDK_INT, found $hostSdkInt"
        }
        require(hostAbi.isNotBlank()) { "HostReadinessReport hostAbi must not be blank" }
        require(kernelPageSizeBytes >= 4096 && kernelPageSizeBytes.countOneBits() == 1) {
            "HostReadinessReport kernelPageSizeBytes must be a power of two >= 4096, found $kernelPageSizeBytes"
        }
        require(totalHostRamMiB >= 0) { "HostReadinessReport totalHostRamMiB must be non-negative" }
        require(freeDiskMiB >= 0) { "HostReadinessReport freeDiskMiB must be non-negative" }
        require(liveChildProcessCount >= 0) {
            "HostReadinessReport liveChildProcessCount must be non-negative"
        }
    }

    val is16KbPageKernel: Boolean
        get() = kernelPageSizeBytes == PAGE_SIZE_16KB

    val exceedsRecommendedProcessBudget: Boolean
        get() = liveChildProcessCount > MAX_RECOMMENDED_CHILD_PROCESSES

    companion object {
        const val MIN_SUPPORTED_SDK_INT: Int = 31
        const val PAGE_SIZE_16KB: Int = 16384
        const val MAX_RECOMMENDED_CHILD_PROCESSES: Int = 12
    }
}

/**
 * Stores global user preferences for keyboard behavior, color palette, scrollback, and wake locks.
 */
@Serializable
data class GlobalAppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val pinKeyBarWithHardwareKeyboard: Boolean = false,
    val passAllShortcutsToTerminal: Boolean = false,
    val selectedPaletteId: String = ColorPalette.DEFAULT_DARK.id,
    val defaultScrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    val defaultWakeLockMinutes: Int = DEFAULT_WAKE_LOCK_MINUTES,
    val idleFreezeTimeoutMinutes: Int = DEFAULT_IDLE_FREEZE_TIMEOUT_MINUTES,
    val maxRecommendedActiveSandboxes: Int = DEFAULT_MAX_ACTIVE_SANDBOXES,
    val confirmUnsafeMultiLinePaste: Boolean = true,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported GlobalAppSettings schemaVersion: $schemaVersion"
        }
        require(selectedPaletteId.isNotBlank()) {
            "GlobalAppSettings selectedPaletteId must not be blank"
        }
        require(defaultScrollbackLines in MIN_SCROLLBACK_LINES..MAX_SCROLLBACK_LINES) {
            "GlobalAppSettings defaultScrollbackLines must be within $MIN_SCROLLBACK_LINES..$MAX_SCROLLBACK_LINES"
        }
        require(defaultWakeLockMinutes >= 0) {
            "GlobalAppSettings defaultWakeLockMinutes must be non-negative"
        }
        require(idleFreezeTimeoutMinutes >= 1) {
            "GlobalAppSettings idleFreezeTimeoutMinutes must be at least 1"
        }
        require(maxRecommendedActiveSandboxes in 1..8) {
            "GlobalAppSettings maxRecommendedActiveSandboxes must be within 1..8"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_SCROLLBACK_LINES: Int = 10_000
        const val MIN_SCROLLBACK_LINES: Int = 1_000
        const val MAX_SCROLLBACK_LINES: Int = 100_000
        const val DEFAULT_WAKE_LOCK_MINUTES: Int = 30
        const val DEFAULT_IDLE_FREEZE_TIMEOUT_MINUTES: Int = 5
        const val DEFAULT_MAX_ACTIVE_SANDBOXES: Int = 3
    }
}

internal fun isCleanAbsoluteGuestPath(path: String): Boolean {
    if (!path.startsWith("/")) return false
    if (path.contains('\u0000') || path.contains(':') || path.contains(',')) return false
    val segments = path.split('/')
    return segments.none { it == "." || it == ".." }
}
