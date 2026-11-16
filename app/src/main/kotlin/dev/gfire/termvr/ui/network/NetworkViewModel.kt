package dev.gfire.termvr.ui.network

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.PortConflictDetector
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Represents a staged port-forward update awaiting Checkpoint-and-Restore or Cold Restart.
 */
data class StagedPortApplyRequest(
    val profileId: String,
    val updatedRules: List<PortForwardRule>,
    val previousRules: List<PortForwardRule> = emptyList(),
)

/**
 * UI state for inbound port forwards, LAN confirmation/QR, outbound proxy, and WireGuard mode.
 */
data class NetworkUiState(
    val profiles: List<SandboxProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val publishedPorts: Map<String, List<PublishedPortMapping>> = emptyMap(),
    val lanReconfirmationProfiles: Set<String> = emptySet(),
    val activeWifiIpv4: String? = null,
    val wireGuardConfigs: List<String> = emptyList(),
    val stagedPortApply: StagedPortApplyRequest? = null,
    val transparentTcpRestartRequired: Boolean = false,
    val lastProxyTest: ProxyTestResult? = null,
    val testingProxy: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val selectedProfile: SandboxProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
}

private data class NetworkLocalState(
    val selectedProfileId: String? = null,
    val wireGuardConfigs: List<String> = emptyList(),
    val stagedPortApply: StagedPortApplyRequest? = null,
    val runningTransparentTcpBaseline: Map<String, Boolean> = emptyMap(),
    val lastProxyTest: ProxyTestResult? = null,
    val testingProxy: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Manages inbound port validation, QR publishing, proxy staging, and WireGuard config import.
 */
class NetworkViewModel(
    private val profileRepository: ProfileRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val localState = MutableStateFlow(NetworkLocalState())

    private val runtimePart = combine(
        profileRepository.observeProfiles(),
        runtimeGateway.runStates,
        runtimeGateway.publishedPortsByProfile,
        runtimeGateway.lanReconfirmationRequired,
    ) { profiles, states, ports, reconfirm ->
        NetworkRuntimePart(profiles, states, ports, reconfirm)
    }

    val uiState: StateFlow<NetworkUiState> = combine(runtimePart, localState) { rt, local ->
        val selected = rt.profiles.firstOrNull { it.id == local.selectedProfileId }
            ?: rt.profiles.firstOrNull()
        val isRunning = selected != null && rt.states[selected.id] == SandboxRunState.RUNNING
        val baselineNetRaw = selected?.let { local.runningTransparentTcpBaseline[it.id] }
        val needsNetRawRestart = isRunning &&
            baselineNetRaw != null &&
            selected.network.outboundProxy.transparentTcp != baselineNetRaw

        NetworkUiState(
            profiles = rt.profiles,
            selectedProfileId = selected?.id,
            runStates = rt.states,
            publishedPorts = rt.ports,
            lanReconfirmationProfiles = rt.reconfirm,
            activeWifiIpv4 = runtimeGateway.activeWifiIpv4(),
            wireGuardConfigs = local.wireGuardConfigs,
            stagedPortApply = local.stagedPortApply,
            transparentTcpRestartRequired = needsNetRawRestart,
            lastProxyTest = local.lastProxyTest,
            testingProxy = local.testingProxy,
            busy = local.busy,
            statusMessage = local.statusMessage,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = NetworkUiState(),
    )

    init {
        refreshWireGuardConfigs()
        scope.launch {
            runtimeGateway.runStates.collect { states ->
                val profiles = profileRepository.observeProfiles().first().associateBy { it.id }
                val updatedBaseline = localState.value.runningTransparentTcpBaseline.toMutableMap()
                for ((id, state) in states) {
                    if (state == SandboxRunState.RUNNING) {
                        if (!updatedBaseline.containsKey(id)) {
                            updatedBaseline[id] = profiles[id]?.network?.outboundProxy?.transparentTcp ?: false
                        }
                    } else if (state == SandboxRunState.STOPPED) {
                        updatedBaseline.remove(id)
                    }
                }
                localState.value = localState.value.copy(runningTransparentTcpBaseline = updatedBaseline)
            }
        }
    }

    fun selectProfile(profileId: String) {
        localState.value = localState.value.copy(selectedProfileId = profileId, errorMessage = null, statusMessage = null)
    }

    fun clearBanners() {
        localState.value = localState.value.copy(statusMessage = null, errorMessage = null)
    }

    /**
     * Validates a candidate port forward rule before LAN confirmation or persistence.
     */
    fun validateCandidatePortRule(
        protocol: ForwardProtocol,
        listenHost: ListenHost,
        hostPort: Int,
        guestPort: Int,
    ): Boolean {
        val currentUi = uiState.value
        val profile = currentUi.selectedProfile ?: return false
        if (hostPort !in 1..65535 || guestPort !in 1..65535) {
            localState.value = localState.value.copy(
                errorMessage = "Port numbers must be in 1..65535.",
                statusMessage = null,
            )
            return false
        }
        if (PortForwardRule.isLanDockerPortBlocked(listenHost, hostPort, hostPort, guestPort, guestPort)) {
            localState.value = localState.value.copy(
                errorMessage = "Port 2375 Docker daemon exposure on LAN is blocked for security. Bind to 127.0.0.1 instead.",
                statusMessage = null,
            )
            return false
        }
        val probeRule = PortForwardRule(
            id = "probe",
            protocol = protocol,
            listenHost = listenHost,
            hostPortStart = hostPort,
            hostPortEnd = hostPort,
            guestPortStart = guestPort,
            guestPortEnd = guestPort,
        )
        val candidateRules = profile.portForwards + probeRule
        PortConflictDetector.detectSelfRuleConflict(candidateRules, profile.displayName)?.let { match ->
            localState.value = localState.value.copy(
                errorMessage = PortConflictDetector.formatConflictMessage(match),
                statusMessage = null,
            )
            return false
        }
        PortConflictDetector.detectCrossProfileConflict(
            targetProfileId = profile.id,
            candidateRules = candidateRules,
            profiles = currentUi.profiles,
            runStates = currentUi.runStates,
            publishedPortsByProfile = currentUi.publishedPorts,
        )?.let { match ->
            localState.value = localState.value.copy(
                errorMessage = PortConflictDetector.formatConflictMessage(match),
                statusMessage = null,
            )
            return false
        }
        return true
    }

    /**
     * Validates and adds a port forward rule, blocking port 2375 LAN exposure and staging live restarts.
     */
    fun addPortRule(
        protocol: ForwardProtocol,
        listenHost: ListenHost,
        hostPort: Int,
        guestPort: Int,
    ) {
        val profile = uiState.value.selectedProfile ?: return
        if (!validateCandidatePortRule(protocol, listenHost, hostPort, guestPort)) {
            return
        }
        val newRule = PortForwardRule(
            id = "pf-${UUID.randomUUID().toString().take(8)}",
            protocol = protocol,
            listenHost = listenHost,
            hostPortStart = hostPort,
            hostPortEnd = hostPort,
            guestPortStart = guestPort,
            guestPortEnd = guestPort,
        )
        applyUpdatedPortRules(profile, profile.portForwards + newRule)
    }

    /**
     * Removes a port forward rule from the selected profile and stages a restart when running.
     */
    fun removePortRule(ruleId: String) {
        val profile = uiState.value.selectedProfile ?: return
        val nextRules = profile.portForwards.filterNot { it.id == ruleId }
        applyUpdatedPortRules(profile, nextRules)
    }

    /**
     * Dismisses the staged Checkpoint-and-Restore vs Cold Restart prompt without dropping persisted rules.
     */
    fun dismissStagedPortApply() {
        localState.value = localState.value.copy(stagedPortApply = null)
    }

    /**
     * Commits staged port forward changes to a running sandbox via checkpoint-restore or cold restart.
     */
    fun confirmStagedPortApply(checkpointAndRestore: Boolean) {
        val staged = localState.value.stagedPortApply ?: return
        scope.launch {
            localState.value = localState.value.copy(
                busy = true,
                stagedPortApply = null,
                errorMessage = null,
                statusMessage = null,
            )
            runCatching {
                val currentUi = uiState.value
                PortConflictDetector.detectCrossProfileConflict(
                    targetProfileId = staged.profileId,
                    candidateRules = staged.updatedRules,
                    profiles = currentUi.profiles,
                    runStates = currentUi.runStates,
                    publishedPortsByProfile = currentUi.publishedPorts,
                )?.let { match ->
                    throw PortConflictDetector.toException(match)
                }
                runtimeGateway.applyPortForwards(
                    profileId = staged.profileId,
                    updatedRules = staged.updatedRules,
                    checkpointAndRestore = checkpointAndRestore,
                )
                localState.value = localState.value.copy(
                    statusMessage = if (checkpointAndRestore) {
                        "Applied port rules via Checkpoint & Restore."
                    } else {
                        "Applied port rules via Cold Restart."
                    },
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                runCatching {
                    profileRepository.getProfile(staged.profileId)?.let { existing ->
                        profileRepository.upsertProfile(existing.copy(portForwards = staged.previousRules))
                    }
                }
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to apply port forward changes",
                    statusMessage = null,
                )
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    /**
     * Re-confirms LAN port exposure after Wi-Fi handle change without losing sandbox state.
     */
    fun reconfirmLanExposure(profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                runtimeGateway.startOrResumeSandbox(profile, reconfirmLan = true)
                localState.value = localState.value.copy(statusMessage = "Confirmed LAN binding on active Wi-Fi.")
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "LAN confirmation failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    /**
     * Downgrades all LAN-bound rules on [profileId] back to loopback `127.0.0.1` and restarts.
     */
    fun downgradeLanRulesToLoopback(profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                val safeRules = profile.portForwards.map { it.downgradeLanToLoopback() }
                profileRepository.upsertProfile(profile.copy(portForwards = safeRules))
                runtimeGateway.applyPortForwards(profile.id, safeRules, checkpointAndRestore = false)
                localState.value = localState.value.copy(statusMessage = "Downgraded all LAN rules to 127.0.0.1.")
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Downgrade failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    /**
     * Updates outbound proxy configuration, saves Keystore password if provided, and applies live.
     */
    fun saveOutboundProxy(
        mode: OutboundProxyMode,
        protocol: ProxyProtocol,
        host: String,
        port: Int,
        username: String,
        password: String,
        routeDnsViaProxy: Boolean,
        transparentTcp: Boolean,
        noProxyEntries: List<String>,
    ) {
        val profile = uiState.value.selectedProfile ?: return
        scope.launch {
            localState.value = localState.value.copy(errorMessage = null, statusMessage = null)
            runCatching {
                val endpoint = if (mode == OutboundProxyMode.CUSTOM) {
                    val alias = if (password.isNotBlank()) {
                        val key = "proxy-${profile.id}"
                        val secretChars = password.toCharArray()
                        try {
                            profileRepository.saveProxySecret(key, secretChars)
                        } finally {
                            secretChars.fill('\u0000')
                        }
                        key
                    } else {
                        profile.network.outboundProxy.customEndpoint?.credentialKeyAlias
                    }
                    ProxyEndpointSpec(
                        protocol = protocol,
                        host = host.trim(),
                        port = port,
                        username = username.trim().ifEmpty { null },
                        credentialKeyAlias = alias,
                    )
                } else {
                    null
                }
                val nextProxy = OutboundProxyConfig(
                    mode = mode,
                    customEndpoint = endpoint,
                    routeDnsViaProxy = routeDnsViaProxy,
                    transparentTcp = transparentTcp,
                    noProxyEntries = noProxyEntries.filter { it.isNotBlank() },
                )
                val updated = profile.copy(
                    network = profile.network.copy(outboundProxy = nextProxy),
                )
                profileRepository.upsertProfile(updated)
                runtimeGateway.updateOutboundProxyLive(profile.id, nextProxy)

                val isRunning = uiState.value.runStates[profile.id] == SandboxRunState.RUNNING
                val baseline = localState.value.runningTransparentTcpBaseline[profile.id]
                val notice = if (isRunning && baseline != null && baseline != transparentTcp) {
                    "Saved proxy policy. Restart sandbox to apply --net-raw change."
                } else {
                    "Applied outbound proxy live."
                }
                localState.value = localState.value.copy(statusMessage = notice)
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Invalid proxy settings")
            }
        }
    }

    /**
     * Restarts a running sandbox cleanly so `--net-raw` transparent TCP redirect changes take effect.
     */
    fun restartForNetRawChange() {
        val profile = uiState.value.selectedProfile ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                runtimeGateway.stopSandbox(profile.id)
                runtimeGateway.startOrResumeSandbox(profile)
                localState.value = localState.value.copy(statusMessage = "Restarted sandbox with updated --net-raw mode.")
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Restart failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    /**
     * Tests outbound proxy handshake latency without modifying live sandbox routing.
     */
    fun testProxyConfig(config: OutboundProxyConfig) {
        scope.launch {
            localState.value = localState.value.copy(testingProxy = true, lastProxyTest = null)
            val result = runtimeGateway.testProxy(config)
            localState.value = localState.value.copy(testingProxy = false, lastProxyTest = result)
        }
    }

    fun updateNetworkBackend(backend: NetBackend, isolateLanEgress: Boolean, wireGuardName: String?) {
        val profile = uiState.value.selectedProfile ?: return
        scope.launch {
            runCatching {
                val updated = profile.copy(
                    network = profile.network.copy(
                        backend = backend,
                        isolateLanEgress = isolateLanEgress,
                        wireGuardConfigName = if (backend == NetBackend.WIREGUARD) wireGuardName else null,
                    ),
                )
                profileRepository.upsertProfile(updated)
                localState.value = localState.value.copy(statusMessage = "Updated network backend to ${backend.name}.")
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to update backend")
            }
        }
    }

    fun reportWireGuardImportError(message: String) {
        localState.value = localState.value.copy(
            errorMessage = message.trim().ifEmpty { "WireGuard import failed." },
            statusMessage = null,
        )
    }

    /**
     * Reads a SAF WireGuard document stream up to 64 KiB, sanitizes its filename, and persists it.
     */
    fun importWireGuardConfigFromStream(
        rawDisplayName: String?,
        declaredSizeBytes: Long? = null,
        fallbackName: String = "wg-${System.currentTimeMillis()}",
        openStream: () -> InputStream?,
    ) {
        scope.launch {
            localState.value = localState.value.copy(errorMessage = null, statusMessage = null)
            runCatching {
                if (declaredSizeBytes != null && declaredSizeBytes > MAX_WIREGUARD_CONFIG_BYTES.toLong()) {
                    throw IllegalArgumentException(WIREGUARD_SIZE_LIMIT_ERROR)
                }
                val configText = withContext(ioDispatcher) {
                    val stream = openStream() ?: throw IOException("Unable to read selected WireGuard config file.")
                    stream.use { input ->
                        readBoundedWireGuardConfig(
                            input = input,
                            maxBytes = MAX_WIREGUARD_CONFIG_BYTES,
                            declaredSizeBytes = declaredSizeBytes,
                        )
                    }
                }
                val cleanName = sanitizeWireGuardConfigName(rawDisplayName, fallbackName)
                persistImportedWireGuardConfig(cleanName, configText)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "WireGuard import failed.",
                    statusMessage = null,
                )
            }
        }
    }

    fun importWireGuardConfig(name: String, configText: String) {
        scope.launch {
            localState.value = localState.value.copy(errorMessage = null, statusMessage = null)
            runCatching {
                val utf8Bytes = configText.toByteArray(Charsets.UTF_8).size
                require(utf8Bytes <= MAX_WIREGUARD_CONFIG_BYTES) {
                    WIREGUARD_SIZE_LIMIT_ERROR
                }
                val cleanName = sanitizeWireGuardConfigName(name, "wg-${System.currentTimeMillis()}")
                persistImportedWireGuardConfig(cleanName, configText)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "WireGuard import failed.",
                    statusMessage = null,
                )
            }
        }
    }

    private suspend fun persistImportedWireGuardConfig(cleanName: String, configText: String) {
        profileRepository.saveWireGuardConfig(cleanName, configText)
        val names = runCatching { profileRepository.listWireGuardConfigNames() }.getOrDefault(emptyList())
        val selectedId = localState.value.selectedProfileId
        val latestProfiles = profileRepository.observeProfiles().first()
        val profile = uiState.value.selectedProfile
            ?: latestProfiles.firstOrNull { it.id == selectedId }
            ?: latestProfiles.firstOrNull()
        if (profile != null) {
            val updated = profile.copy(
                network = profile.network.copy(
                    backend = NetBackend.WIREGUARD,
                    wireGuardConfigName = cleanName,
                ),
            )
            profileRepository.upsertProfile(updated)
        }
        localState.value = localState.value.copy(
            wireGuardConfigs = names,
            statusMessage = "Imported WireGuard profile \"$cleanName\".",
            errorMessage = null,
        )
    }

    fun openUrlInCustomTab(url: String) {
        runtimeGateway.openUrl(url)
    }

    private fun applyUpdatedPortRules(profile: SandboxProfile, nextRules: List<PortForwardRule>) {
        val previousRules = localState.value.stagedPortApply
            ?.takeIf { it.profileId == profile.id }
            ?.previousRules
            ?: profile.portForwards
        localState.value = localState.value.copy(errorMessage = null)
        scope.launch {
            runCatching {
                val updated = profile.copy(portForwards = nextRules)
                profileRepository.upsertProfile(updated)
                val isRunning = uiState.value.runStates[profile.id] == SandboxRunState.RUNNING
                if (isRunning) {
                    localState.value = localState.value.copy(
                        stagedPortApply = StagedPortApplyRequest(
                            profileId = profile.id,
                            updatedRules = nextRules,
                            previousRules = previousRules,
                        ),
                        errorMessage = null,
                    )
                } else {
                    localState.value = localState.value.copy(
                        statusMessage = "Updated inbound port rules.",
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Invalid port rule",
                    statusMessage = null,
                )
            }
        }
    }

    private fun refreshWireGuardConfigs() {
        scope.launch {
            val names = runCatching { profileRepository.listWireGuardConfigNames() }.getOrDefault(emptyList())
            localState.value = localState.value.copy(wireGuardConfigs = names)
        }
    }

    fun close() {
        scope.cancel()
    }

    private data class NetworkRuntimePart(
        val profiles: List<SandboxProfile>,
        val states: Map<String, SandboxRunState>,
        val ports: Map<String, List<PublishedPortMapping>>,
        val reconfirm: Set<String>,
    )

    companion object {
        const val MAX_WIREGUARD_CONFIG_BYTES: Int = 64 * 1024
        private const val WIREGUARD_SIZE_LIMIT_ERROR =
            "WireGuard configuration exceeds the 64 KiB size limit."
        private val UNSAFE_WG_NAME_CHARS = Regex("[^a-zA-Z0-9._-]+")
        private val REPEATED_DASHES = Regex("-+")

        /**
         * Sanitizes a SAF display name into a valid WireGuard profile name without `.conf`.
         */
        fun sanitizeWireGuardConfigName(
            rawDisplayName: String?,
            fallbackName: String = "wg-imported",
        ): String {
            if (rawDisplayName.isNullOrBlank()) return fallbackName
            val baseName = rawDisplayName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            val withoutExt = if (baseName.endsWith(".conf", ignoreCase = true)) {
                baseName.dropLast(5)
            } else {
                baseName
            }
            val cleaned = withoutExt
                .replace(UNSAFE_WG_NAME_CHARS, "-")
                .replace(REPEATED_DASHES, "-")
                .trim('.', '-')
                .take(64)
                .trimEnd('.', '-')
            return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                fallbackName
            } else {
                cleaned
            }
        }

        /**
         * Reads UTF-8 WireGuard configuration text while enforcing a strict [maxBytes] cap.
         */
        fun readBoundedWireGuardConfig(
            input: InputStream,
            maxBytes: Int = MAX_WIREGUARD_CONFIG_BYTES,
            declaredSizeBytes: Long? = null,
        ): String {
            require(declaredSizeBytes == null || declaredSizeBytes <= maxBytes.toLong()) {
                WIREGUARD_SIZE_LIMIT_ERROR
            }
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream(minOf(maxBytes, 4096))
            var totalBytes = 0
            while (true) {
                val readCount = input.read(buffer)
                if (readCount < 0) break
                if (readCount == 0) continue
                totalBytes += readCount
                require(totalBytes <= maxBytes) {
                    WIREGUARD_SIZE_LIMIT_ERROR
                }
                out.write(buffer, 0, readCount)
            }
            val decoded = out.toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF")
            require(decoded.isNotBlank()) {
                "Selected WireGuard configuration file is empty."
            }
            return decoded
        }
    }
}

/**
 * Resolves the active host port for [rule], matching [PublishedPortMapping] when [rule] uses `:0`.
 */
fun resolveEffectiveHostPort(
    rule: PortForwardRule,
    publishedPorts: List<PublishedPortMapping>,
): Int? {
    if (rule.hostPortStart > 0) return rule.hostPortStart
    return publishedPorts.firstOrNull {
        it.protocol == rule.protocol && it.guestPort == rule.guestPortStart && it.hostPort > 0
    }?.hostPort
}
