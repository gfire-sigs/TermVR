package dev.gfire.termvr.ui.home

import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.GitProjectSummary
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountMode
import dev.gfire.termvr.core.model.VolumeMountSpec
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Containers dashboard and concurrent multi-container controls.
 */
data class HomeUiState(
    val profiles: List<SandboxProfile> = emptyList(),
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val telemetry: Map<String, SandboxTelemetrySnapshot> = emptyMap(),
    val hostReadiness: HostReadinessReport = HostReadinessReport(hostSdkInt = 31, hostAbi = "arm64-v8a"),
    val availableVolumes: List<String> = emptyList(),
    val gitProjects: List<GitProjectSummary> = emptyList(),
    val busyProfileIds: Set<String> = emptySet(),
    val serviceNotice: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val runningCount: Int
        get() = runStates.values.count { it == SandboxRunState.RUNNING }

    val exceedsRecommendedSandboxCount: Boolean
        get() = runningCount > GlobalAppSettings.DEFAULT_MAX_ACTIVE_SANDBOXES

    val hasReadinessWarning: Boolean
        get() = hostReadiness.phantomKillerDetected ||
            hostReadiness.exceedsRecommendedProcessBudget ||
            exceedsRecommendedSandboxCount

    val readinessWarningMessage: String?
        get() = HomeViewModel.formatHostReadinessWarning(hostReadiness, runningCount)

    val isNoticeError: Boolean
        get() = errorMessage != null || HomeViewModel.isErrorServiceNotice(serviceNotice)

    val activeOrFrozenCount: Int
        get() = runStates.values.count {
            it == SandboxRunState.RUNNING || it == SandboxRunState.FROZEN
        }
}

/**
 * Coordinates Quick Debian launch, concurrent multi-container actions, and volume mounts.
 */
class HomeViewModel(
    private val profileRepository: ProfileRepository,
    private val catalogRepository: CatalogRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val sessionCoordinator: TerminalSessionCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val busyProfileIds = MutableStateFlow<Set<String>>(emptySet())
    private val availableVolumes = MutableStateFlow<List<String>>(emptyList())
    private val gitProjects = MutableStateFlow<List<GitProjectSummary>>(emptyList())
    private val statusMessage = MutableStateFlow<String?>(null)
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
        busyProfileIds,
        availableVolumes,
        runtimeGateway.activeServiceNotice,
        statusMessage,
        errorMessage,
    ) { busyIds, volumes, notice, status, err ->
        LocalHomeData(busyIds, volumes, notice, status, err)
    }

    val uiState: StateFlow<HomeUiState> = combine(baseFlow, localFlow, gitProjects) { base, local, projects ->
        val profileVolumes = base.profiles.flatMap { profile ->
            profile.volumes.flatMap { vol ->
                when (vol.kind) {
                    VolumeKind.WORKSPACE_HOSTFS -> emptyList()
                    VolumeKind.NAMED_EXT4LITE -> listOf(vol.sourceName)
                    VolumeKind.OVERLAY_EXT4LITE -> listOfNotNull(vol.sourceName, vol.overlayUpperVolumeName)
                }
            }
        }
        val mergedVolumes = (local.volumes + profileVolumes).distinct().sorted()
        HomeUiState(
            profiles = base.profiles,
            runStates = base.states,
            telemetry = base.telemetry,
            hostReadiness = base.readiness,
            availableVolumes = mergedVolumes,
            gitProjects = projects,
            busyProfileIds = local.busyIds,
            serviceNotice = local.notice,
            statusMessage = local.status,
            errorMessage = local.err,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState(hostReadiness = runtimeGateway.hostReadiness.value),
    )

    init {
        refreshGitProjects()
    }

    fun clearNotice() {
        runtimeGateway.clearNotice()
        statusMessage.value = null
        errorMessage.value = null
    }

    fun refreshAvailableVolumes() {
        scope.launch {
            runCatching { runtimeGateway.listVolumes().map { it.name } }
                .onSuccess { names -> availableVolumes.value = names }
        }
    }

    fun refreshGitProjects() {
        scope.launch {
            runCatching { runtimeGateway.gitWorktreeManager().listProjects() }
                .onSuccess { list -> gitProjects.value = list }
        }
    }

    /**
     * Ensures the default Debian container exists, starts it, opens a terminal tab, and navigates.
     */
    fun launchQuickDebian(onOpenTerminal: () -> Unit) {
        scope.launch {
            markBusy(QUICK_DEBIAN_PROFILE_ID)
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val existing = profileRepository.getProfile(QUICK_DEBIAN_PROFILE_ID)
                val profile = existing ?: buildQuickDebianProfile().also {
                    profileRepository.upsertProfile(it)
                }
                startAndAttachProfile(profile, onOpenTerminal)
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to start Debian"
            }
            clearBusy(QUICK_DEBIAN_PROFILE_ID)
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
            markBusy(profile.id)
            errorMessage.value = null
            statusMessage.value = null
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
                errorMessage.value = err.message ?: "Container start failed"
            }
            clearBusy(profile.id)
        }
    }

    /**
     * Opens an additional interactive shell tab for a running container and switches to Terminal.
     */
    fun openNewShellForProfile(
        profile: SandboxProfile,
        onOpenTerminal: () -> Unit,
    ) {
        scope.launch {
            errorMessage.value = null
            runCatching {
                val currentState = runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED
                if (currentState != SandboxRunState.RUNNING) {
                    startAndAttachProfile(profile, onOpenTerminal)
                    return@runCatching
                }
                val settings = profileRepository.observeAppSettings().first()
                val palette = ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
                sessionCoordinator.openNewShellTab(
                    profile = profile,
                    maxScrollbackLines = settings.defaultScrollbackLines,
                    palette = palette,
                )
                onOpenTerminal()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to open shell"
            }
        }
    }

    fun freezeProfile(profileId: String) {
        scope.launch {
            markBusy(profileId)
            errorMessage.value = null
            runCatching {
                runtimeGateway.freezeSandbox(profileId)
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to freeze container"
            }
            clearBusy(profileId)
        }
    }

    fun stopProfile(profileId: String) {
        scope.launch {
            markBusy(profileId)
            errorMessage.value = null
            runCatching {
                runtimeGateway.stopSandbox(profileId)
                sessionCoordinator.closeProfileTabs(profileId)
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to stop container"
            }
            clearBusy(profileId)
        }
    }

    fun freezeAllRunning() {
        scope.launch {
            errorMessage.value = null
            val runningIds = runtimeGateway.runStates.value
                .filterValues { it == SandboxRunState.RUNNING }
                .keys
                .toList()
            if (runningIds.isEmpty()) return@launch
            runningIds.forEach(::markBusy)
            runCatching {
                runningIds.map { id ->
                    async { runtimeGateway.freezeSandbox(id) }
                }.awaitAll()
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to freeze all containers"
            }
            runningIds.forEach(::clearBusy)
        }
    }

    fun stopAllRunning() {
        scope.launch {
            errorMessage.value = null
            val activeIds = runtimeGateway.runStates.value
                .filterValues { it != SandboxRunState.STOPPED }
                .keys
                .toList()
            activeIds.forEach(::markBusy)
            runCatching {
                runtimeGateway.stopAllSandboxes()
                activeIds.forEach { sessionCoordinator.closeProfileTabs(it) }
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to stop all containers"
            }
            activeIds.forEach(::clearBusy)
        }
    }

    fun createProfile(
        displayName: String,
        imageRef: String,
        memoryMiB: Int = SandboxProfile.UNLIMITED_MEMORY_MIB,
        cpuCores: Double = SandboxProfile.UNLIMITED_CPU_CORES,
        muxMode: MuxMode = MuxMode.STDIO_MUX,
        persistentRootfs: Boolean = false,
        gitWorktree: GitWorktreeSpec? = null,
        gitProjectDisplayName: String? = null,
    ) {
        scope.launch {
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val cleanName = displayName.trim().ifEmpty { "Debian" }
                val cleanImage = imageRef.trim().ifEmpty { DEFAULT_DEBIAN_IMAGE_REF }
                val baseSlug = cleanName.lowercase()
                    .replace(Regex("""[^a-z0-9-]+"""), "-")
                    .trim('-')
                    .ifEmpty { "container" }
                    .take(24)
                val existingIds = profileRepository.observeProfiles().first().map { it.id }.toSet()
                var candidateId = baseSlug
                var suffix = 2
                while (candidateId in existingIds || !SandboxProfile.isValidProfileId(candidateId)) {
                    candidateId = "$baseSlug-$suffix"
                    suffix++
                }
                if (gitWorktree != null) {
                    val wtMgr = runtimeGateway.gitWorktreeManager()
                    wtMgr.createOrEnsureProject(
                        projectId = gitWorktree.projectId,
                        name = gitProjectDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: gitWorktree.projectId,
                    )
                    wtMgr.provisionWorktreeForContainer(candidateId, gitWorktree)
                }
                val defaultVolumes = if (gitWorktree != null) {
                    emptyList()
                } else {
                    listOf(
                        VolumeMountSpec(
                            sourceName = candidateId,
                            guestMountPath = "/workspace",
                            kind = VolumeKind.WORKSPACE_HOSTFS,
                            readOnly = false,
                        ),
                    )
                }
                val profile = SandboxProfile(
                    id = candidateId,
                    displayName = cleanName,
                    imageRef = cleanImage,
                    muxMode = muxMode,
                    defaultShellArgv = emptyList(),
                    memoryMiB = SandboxProfile.clampMemoryMiB(memoryMiB),
                    cpuCores = SandboxProfile.clampCpuCores(cpuCores),
                    volumes = defaultVolumes,
                    persistentRootfs = persistentRootfs,
                    gitWorktree = gitWorktree,
                )
                profileRepository.upsertProfile(profile)
                gitProjects.value = runtimeGateway.gitWorktreeManager().listProjects()
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to create container"
            }
        }
    }

    fun updateProfileConfig(
        profileId: String,
        displayName: String,
        memoryMiB: Int,
        cpuCores: Double,
        persistentRootfs: Boolean,
        gitWorktree: GitWorktreeSpec?,
        gitProjectDisplayName: String? = null,
    ) {
        scope.launch {
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val existing = profileRepository.getProfile(profileId)
                    ?: error("Container not found")
                val wtMgr = runtimeGateway.gitWorktreeManager()
                val prevGit = existing.gitWorktree
                if (gitWorktree != null) {
                    wtMgr.createOrEnsureProject(
                        projectId = gitWorktree.projectId,
                        name = gitProjectDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: gitWorktree.projectId,
                    )
                    wtMgr.provisionWorktreeForContainer(profileId, gitWorktree)
                    if (prevGit != null && prevGit.projectId != gitWorktree.projectId) {
                        wtMgr.detachWorktree(prevGit.projectId, profileId, deleteWorktreeFiles = false)
                    }
                } else if (prevGit != null) {
                    wtMgr.detachWorktree(prevGit.projectId, profileId, deleteWorktreeFiles = false)
                }
                val cleanedVolumes = if (gitWorktree != null) {
                    existing.volumes.filterNot {
                        it.guestMountPath.trimEnd('/') == "/workspace" &&
                            it.kind == VolumeKind.WORKSPACE_HOSTFS &&
                            it.sourceName == profileId &&
                            !it.readOnly
                    }
                } else if (existing.volumes.none { it.guestMountPath.trimEnd('/') == "/workspace" }) {
                    existing.volumes + VolumeMountSpec(
                        sourceName = profileId,
                        guestMountPath = "/workspace",
                        kind = VolumeKind.WORKSPACE_HOSTFS,
                        readOnly = false,
                    )
                } else {
                    existing.volumes
                }
                val updated = existing.copy(
                    displayName = displayName.trim().ifEmpty { existing.displayName },
                    memoryMiB = SandboxProfile.clampMemoryMiB(memoryMiB),
                    cpuCores = SandboxProfile.clampCpuCores(cpuCores),
                    persistentRootfs = persistentRootfs,
                    volumes = cleanedVolumes,
                    gitWorktree = gitWorktree,
                )
                profileRepository.upsertProfile(updated)
                gitProjects.value = wtMgr.listProjects()
                val isRunning = runtimeGateway.runStates.value[profileId] == SandboxRunState.RUNNING
                statusMessage.value = if (isRunning) {
                    "Saved ${updated.displayName}. Restart container to apply changes."
                } else {
                    "Saved ${updated.displayName}."
                }
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to update container"
            }
        }
    }

    fun attachVolumeToProfile(
        profileId: String,
        volumeName: String,
        guestMountPath: String = "/data",
        mode: VolumeMountMode = VolumeMountMode.EXCLUSIVE_RW,
        overlayUpperVolumeName: String? = null,
    ) {
        scope.launch {
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val cleanVol = volumeName.trim()
                val cleanPath = guestMountPath.trim().ifEmpty { "/data" }
                val profile = profileRepository.getProfile(profileId)
                    ?: error("Container not found")
                if (cleanVol !in availableVolumes.value) {
                    runCatching { runtimeGateway.createVolume(cleanVol) }
                }
                val resolvedUpper = if (mode == VolumeMountMode.COW_PERSISTENT) {
                    overlayUpperVolumeName?.trim()?.takeIf { it.isNotEmpty() }
                        ?: VolumeMountSpec.suggestOverlayUpperVolumeName(cleanVol, profile.displayName)
                } else {
                    null
                }
                val mount = VolumeMountSpec.forMode(
                    baseVolumeName = cleanVol,
                    guestMountPath = cleanPath,
                    mode = mode,
                    overlayUpperVolumeName = resolvedUpper,
                )
                val updatedVolumes = profile.volumes
                    .filterNot { existing ->
                        existing.guestMountPath == cleanPath ||
                            (mode == VolumeMountMode.EXCLUSIVE_RW &&
                                (existing.sourceName == cleanVol || existing.overlayUpperVolumeName == cleanVol)) ||
                            (existing.kind == VolumeKind.NAMED_EXT4LITE &&
                                !existing.readOnly &&
                                existing.sourceName == cleanVol)
                    } + mount
                profileRepository.upsertProfile(profile.copy(volumes = updatedVolumes))
                val addedNames = listOfNotNull(cleanVol, resolvedUpper)
                availableVolumes.value = (availableVolumes.value + addedNames).distinct().sorted()
                val isRunning = runtimeGateway.runStates.value[profileId] == SandboxRunState.RUNNING
                statusMessage.value = if (isRunning) {
                    "Attached $cleanVol at $cleanPath. Restart container to apply."
                } else {
                    "Attached $cleanVol at $cleanPath."
                }
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to attach volume"
            }
        }
    }

    fun detachVolumeFromProfile(
        profileId: String,
        volumeName: String,
        guestMountPath: String? = null,
    ) {
        scope.launch {
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val profile = profileRepository.getProfile(profileId)
                    ?: error("Container not found")
                val updatedVolumes = profile.volumes.filterNot { vol ->
                    (vol.kind == VolumeKind.NAMED_EXT4LITE || vol.kind == VolumeKind.OVERLAY_EXT4LITE) &&
                        vol.sourceName == volumeName &&
                        (guestMountPath == null || vol.guestMountPath == guestMountPath)
                }
                profileRepository.upsertProfile(profile.copy(volumes = updatedVolumes))
                val isRunning = runtimeGateway.runStates.value[profileId] == SandboxRunState.RUNNING
                statusMessage.value = if (isRunning) {
                    "Detached $volumeName. Restart container to apply."
                } else {
                    "Detached $volumeName."
                }
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to detach volume"
            }
        }
    }

    fun deleteProfile(profileId: String) {
        scope.launch {
            markBusy(profileId)
            runCatching {
                val existingProfile = profileRepository.getProfile(profileId)
                runtimeGateway.stopSandbox(profileId)
                sessionCoordinator.closeProfileTabs(profileId)
                profileRepository.deleteProfile(profileId)
                val prevGit = existingProfile?.gitWorktree
                if (prevGit != null) {
                    runCatching {
                        runtimeGateway.gitWorktreeManager().detachWorktree(
                            projectId = prevGit.projectId,
                            profileId = profileId,
                            deleteWorktreeFiles = true,
                        )
                    }
                    gitProjects.value = runtimeGateway.gitWorktreeManager().listProjects()
                }
                if (existingProfile?.persistentRootfs == true) {
                    val rootVol = existingProfile.rootfsVolumeName
                    runCatching { runtimeGateway.removeVolume(rootVol, force = false) }
                    availableVolumes.value = availableVolumes.value.filterNot { it == rootVol }
                }
            }.onFailure { err ->
                errorMessage.value = err.message ?: "Failed to delete container"
            }
            clearBusy(profileId)
        }
    }

    private suspend fun startAndAttachProfile(
        profile: SandboxProfile,
        onOpenTerminal: () -> Unit,
        reconfirmLan: Boolean = false,
    ) {
        val prevState = runtimeGateway.runStates.value[profile.id]
        val finalState = runtimeGateway.startOrResumeSandbox(profile, reconfirmLan = reconfirmLan)
        runtimeGateway.refreshHostReadiness()
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

    private fun buildQuickDebianProfile(): SandboxProfile {
        val debian = catalogRepository.getCuratedDistributions()
            .firstOrNull { it.id == "debian-trixie" }
            ?: DistributionCatalogEntry.CURATED_ENTRIES.first()
        return debian.toSandboxProfile(
            profileId = QUICK_DEBIAN_PROFILE_ID,
            profileDisplayName = "Debian",
        ).copy(
            memoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
            cpuCores = SandboxProfile.UNLIMITED_CPU_CORES,
        )
    }

    private fun markBusy(profileId: String) {
        busyProfileIds.update { it + profileId }
    }

    private fun clearBusy(profileId: String) {
        busyProfileIds.update { it - profileId }
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
        val busyIds: Set<String>,
        val volumes: List<String>,
        val notice: String?,
        val status: String?,
        val err: String?,
    )

    companion object {
        const val QUICK_DEBIAN_PROFILE_ID = "debian-main"
        const val DEFAULT_DEBIAN_IMAGE_REF = "docker.io/library/debian:trixie-slim"
        private const val ANDROID_14_SDK_INT = 34

        /**
         * Formats an actionable host readiness warning for phantom-process kills and multi-container caps.
         */
        fun formatHostReadinessWarning(
            report: HostReadinessReport,
            runningCount: Int,
            maxRecommendedSandboxes: Int = GlobalAppSettings.DEFAULT_MAX_ACTIVE_SANDBOXES,
        ): String? {
            return when {
                report.phantomKillerDetected -> {
                    if (report.remediationKind == PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE ||
                        report.hostSdkInt >= ANDROID_14_SDK_INT
                    ) {
                        "Android stopped a child process (SIGKILL). " +
                            "Enable 'Disable child process restrictions' in Developer Options, or open System Diagnostics."
                    } else {
                        "Android stopped a child process (32-process or RAM limit). " +
                            "Keep at most $maxRecommendedSandboxes containers running, or open System Diagnostics."
                    }
                }

                report.exceedsRecommendedProcessBudget -> {
                    "Active child processes (${report.liveChildProcessCount}/32) approach Android's 32-process limit. " +
                        "Freeze idle containers or open System Diagnostics."
                }

                runningCount > maxRecommendedSandboxes -> {
                    "Running $runningCount containers approaches Android's 32-process limit " +
                        "(${report.liveChildProcessCount}/32 host processes). Freeze idle containers or open System Diagnostics."
                }

                else -> null
            }
        }

        /**
         * Formats the live running container and Android host child-process summary strip.
         */
        fun formatRunningStatusSummary(
            runningCount: Int,
            liveChildProcessCount: Int,
        ): String {
            val base = if (runningCount == 1) "1 running" else "$runningCount running"
            return if (liveChildProcessCount > 0) {
                "$base · $liveChildProcessCount/32 procs"
            } else {
                base
            }
        }

        fun isErrorServiceNotice(notice: String?): Boolean {
            if (notice.isNullOrBlank()) return false
            return notice.contains("SIGKILL") ||
                notice.contains("exited unexpectedly") ||
                notice.contains("already in use", ignoreCase = true)
        }
    }
}
