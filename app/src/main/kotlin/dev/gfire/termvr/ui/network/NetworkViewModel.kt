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
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import java.util.UUID
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

/**
 * Represents a staged port-forward update awaiting Checkpoint-and-Restore or Cold Restart.
 */
data class StagedPortApplyRequest(
    val profileId: String,
    val updatedRules: List<PortForwardRule>,
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
            selected != null &&
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
     * Validates and adds a port forward rule, blocking port 2375 LAN exposure and staging live restarts.
     */
    fun addPortRule(
        protocol: ForwardProtocol,
        listenHost: ListenHost,
        hostPort: Int,
        guestPort: Int,
    ) {
        val profile = uiState.value.selectedProfile ?: return
        if (hostPort !in 1..65535 || guestPort !in 1..65535) {
            localState.value = localState.value.copy(errorMessage = "Port numbers must be in 1..65535.")
            return
        }
        if (PortForwardRule.isLanDockerPortBlocked(listenHost, hostPort, hostPort, guestPort, guestPort)) {
            localState.value = localState.value.copy(
                errorMessage = "Port 2375 Docker daemon exposure on LAN is blocked for security. Bind to 127.0.0.1 instead.",
            )
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
            localState.value = localState.value.copy(busy = true, stagedPortApply = null, errorMessage = null)
            runCatching {
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
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to apply port forward changes",
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

    fun importWireGuardConfig(name: String, configText: String) {
        scope.launch {
            runCatching {
                profileRepository.saveWireGuardConfig(name, configText)
                refreshWireGuardConfigs()
                val profile = uiState.value.selectedProfile
                if (profile != null) {
                    val updated = profile.copy(
                        network = profile.network.copy(
                            backend = NetBackend.WIREGUARD,
                            wireGuardConfigName = name,
                        ),
                    )
                    profileRepository.upsertProfile(updated)
                }
                localState.value = localState.value.copy(
                    statusMessage = "Imported WireGuard profile \"$name\".",
                    errorMessage = null,
                )
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "WireGuard import failed")
            }
        }
    }

    fun openUrlInCustomTab(url: String) {
        runtimeGateway.openUrl(url)
    }

    private fun applyUpdatedPortRules(profile: SandboxProfile, nextRules: List<PortForwardRule>) {
        localState.value = localState.value.copy(errorMessage = null)
        scope.launch {
            runCatching {
                val updated = profile.copy(portForwards = nextRules)
                profileRepository.upsertProfile(updated)
                val isRunning = uiState.value.runStates[profile.id] == SandboxRunState.RUNNING
                if (isRunning) {
                    localState.value = localState.value.copy(
                        stagedPortApply = StagedPortApplyRequest(profile.id, nextRules),
                        errorMessage = null,
                    )
                } else {
                    localState.value = localState.value.copy(
                        statusMessage = "Updated inbound port rules.",
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Invalid port rule")
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
}
