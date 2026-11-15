package dev.gfire.termvr.ui.home

import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
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
 * UI state for the Home dashboard, active sandbox cards, and Host Readiness diagnostics.
 */
data class HomeUiState(
    val profiles: List<SandboxProfile> = emptyList(),
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val telemetry: Map<String, SandboxTelemetrySnapshot> = emptyMap(),
    val hostReadiness: HostReadinessReport = HostReadinessReport(hostSdkInt = 31, hostAbi = "arm64-v8a"),
    val readinessExpanded: Boolean = false,
    val busyProfileId: String? = null,
    val serviceNotice: String? = null,
    val errorMessage: String? = null,
    val recommendedAdbCommand: String = ADB_DISABLE_PHANTOM_KILLER_CMD,
) {
    companion object {
        const val ADB_DISABLE_PHANTOM_KILLER_CMD =
            "adb shell device_config set_sync_disabled_for_tests persistent && " +
                "adb shell device_config put activity_manager max_phantom_processes 2147483647 && " +
                "adb shell settings put global settings_enable_monitor_phantom_procs false"
    }
}

/**
 * Coordinates Quick Shell launch, sandbox lifecycle transitions, and Host Readiness diagnostics.
 */
class HomeViewModel(
    private val profileRepository: ProfileRepository,
    private val catalogRepository: CatalogRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val sessionCoordinator: TerminalSessionCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val readinessExpanded = MutableStateFlow(false)
    private val busyProfileId = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val baseFlow = combine(
        profileRepository.observeProfiles(),
        runtimeGateway.runStates,
        runtimeGateway.telemetryByProfile,
        runtimeGateway.hostReadiness,
    ) { profiles, states, telemetry, readiness ->
        BaseHomeData(profiles, states, telemetry, readiness)
    }

    private val localFlow = combine(
        readinessExpanded,
        busyProfileId,
        runtimeGateway.activeServiceNotice,
        errorMessage,
    ) { expanded, busyId, notice, err ->
        LocalHomeData(expanded, busyId, notice, err)
    }

    val uiState: StateFlow<HomeUiState> = combine(baseFlow, localFlow) { base, local ->
        val autoExpand = local.expanded || base.readiness.phantomKillerDetected || base.readiness.exceedsRecommendedProcessBudget
        HomeUiState(
            profiles = base.profiles,
            runStates = base.states,
            telemetry = base.telemetry,
            hostReadiness = base.readiness,
            readinessExpanded = autoExpand,
            busyProfileId = local.busyId,
            serviceNotice = local.notice,
            errorMessage = local.err,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(hostReadiness = runtimeGateway.hostReadiness.value),
    )

    fun toggleReadinessExpanded() {
        readinessExpanded.value = !readinessExpanded.value
    }

    fun refreshHostReadiness() {
        runtimeGateway.refreshHostReadiness()
    }

    fun clearNotice() {
        runtimeGateway.clearNotice()
        errorMessage.value = null
    }

    /**
     * Ensures an Alpine quick shell profile exists, starts it, opens a terminal tab, and navigates.
     */
    fun launchQuickAlpine(onOpenTerminal: () -> Unit) {
        scope.launch {
            busyProfileId.value = QUICK_ALPINE_PROFILE_ID
            errorMessage.value = null
            runCatching {
                val existing = profileRepository.getProfile(QUICK_ALPINE_PROFILE_ID)
                val profile = existing ?: buildQuickAlpineProfile().also {
                    profileRepository.upsertProfile(it)
                }
                startAndAttachProfile(profile, onOpenTerminal)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to start Quick Shell"
            }
            busyProfileId.value = null
        }
    }

    /**
     * Starts or resumes [profile] and opens its terminal tabs when running.
     */
    fun startOrOpenProfile(
        profile: SandboxProfile,
        reconfirmLan: Boolean = false,
        onOpenTerminal: () -> Unit,
    ) {
        scope.launch {
            busyProfileId.value = profile.id
            errorMessage.value = null
            runCatching {
                val currentState = runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED
                if (currentState == SandboxRunState.RUNNING) {
                    val settings = profileRepository.observeAppSettings().first()
                    sessionCoordinator.ensureTabsForProfile(
                        profile = profile,
                        maxScrollbackLines = settings.defaultScrollbackLines,
                        palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK,
                    )
                    onOpenTerminal()
                } else {
                    startAndAttachProfile(profile, onOpenTerminal, reconfirmLan)
                }
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Sandbox start failed"
            }
            busyProfileId.value = null
        }
    }

    fun freezeProfile(profileId: String) {
        scope.launch {
            busyProfileId.value = profileId
            errorMessage.value = null
            runCatching {
                runtimeGateway.freezeSandbox(profileId)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to freeze sandbox"
            }
            busyProfileId.value = null
        }
    }

    fun stopProfile(profileId: String) {
        scope.launch {
            busyProfileId.value = profileId
            errorMessage.value = null
            runCatching {
                runtimeGateway.stopSandbox(profileId)
                sessionCoordinator.closeProfileTabs(profileId)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to stop sandbox"
            }
            busyProfileId.value = null
        }
    }

    fun createProfile(
        displayName: String,
        imageRef: String,
        memoryMiB: Int,
        cpuCores: Double,
        muxMode: MuxMode,
    ) {
        scope.launch {
            errorMessage.value = null
            runCatching {
                val cleanName = displayName.trim().ifEmpty { "Linux Shell" }
                val cleanImage = imageRef.trim().ifEmpty { "alpine:3.21" }
                val baseSlug = cleanName.lowercase()
                    .replace(Regex("""[^a-z0-9._-]+"""), "-")
                    .trim('-')
                    .ifEmpty { "sandbox" }
                    .take(24)
                val existingIds = profileRepository.observeProfiles().first().map { it.id }.toSet()
                var candidateId = baseSlug
                var suffix = 2
                while (candidateId in existingIds || !SandboxProfile.isValidProfileId(candidateId)) {
                    candidateId = "$baseSlug-$suffix"
                    suffix++
                }
                val profile = SandboxProfile(
                    id = candidateId,
                    displayName = cleanName,
                    imageRef = cleanImage,
                    muxMode = muxMode,
                    defaultShellArgv = listOf("/bin/sh"),
                    memoryMiB = SandboxProfile.clampMemoryMiB(memoryMiB),
                    cpuCores = SandboxProfile.clampCpuCores(cpuCores),
                )
                profileRepository.upsertProfile(profile)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to save profile"
            }
        }
    }

    fun deleteProfile(profileId: String) {
        scope.launch {
            runCatching {
                runtimeGateway.stopSandbox(profileId)
                sessionCoordinator.closeProfileTabs(profileId)
                profileRepository.deleteProfile(profileId)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to delete profile"
            }
        }
    }

    private suspend fun startAndAttachProfile(
        profile: SandboxProfile,
        onOpenTerminal: () -> Unit,
        reconfirmLan: Boolean = false,
    ) {
        val prevState = runtimeGateway.runStates.value[profile.id]
        val finalState = runtimeGateway.startOrResumeSandbox(profile, reconfirmLan = reconfirmLan)
        if (finalState == SandboxRunState.RUNNING) {
            val settings = profileRepository.observeAppSettings().first()
            val palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
            if (prevState == SandboxRunState.FROZEN) {
                sessionCoordinator.reattachAfterResume(
                    profile = profile,
                    maxScrollbackLines = settings.defaultScrollbackLines,
                    palette = palette,
                )
            } else {
                sessionCoordinator.ensureTabsForProfile(
                    profile = profile,
                    maxScrollbackLines = settings.defaultScrollbackLines,
                    palette = palette,
                )
            }
            onOpenTerminal()
        }
    }

    private fun buildQuickAlpineProfile(): SandboxProfile {
        val alpine = catalogRepository.getCuratedDistributions()
            .firstOrNull { it.id == "alpine-3.21" }
            ?: DistributionCatalogEntry.CURATED_ENTRIES.first()
        return alpine.toSandboxProfile(
            profileId = QUICK_ALPINE_PROFILE_ID,
            profileDisplayName = "Quick Shell (Alpine)",
        )
    }

    fun close() {
        scope.cancel()
    }

    private data class BaseHomeData(
        val profiles: List<SandboxProfile>,
        val states: Map<String, SandboxRunState>,
        val telemetry: Map<String, SandboxTelemetrySnapshot>,
        val readiness: HostReadinessReport,
    )

    private data class LocalHomeData(
        val expanded: Boolean,
        val busyId: String?,
        val notice: String?,
        val err: String?,
    )

    companion object {
        const val QUICK_ALPINE_PROFILE_ID = "quick-alpine"
    }
}
