package dev.gfire.termvr.ui.home

import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.DistributionCatalogEntry
import dev.gfire.termvr.core.model.GitProjectSummary
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.LiveKernelLogEntry
import dev.gfire.termvr.core.model.LiveMountInfo
import dev.gfire.termvr.core.model.LiveProcessInfo
import dev.gfire.termvr.core.model.LiveResourceUpdate
import dev.gfire.termvr.core.model.LiveStatsSnapshot
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.SandboxInspectSnapshot
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountMode
import dev.gfire.termvr.core.model.VolumeMountSpec
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import kotlinx.coroutines.CancellationException
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

enum class InspectorTab(val label: String) {
    STATS("Stats"),
    PROCESSES("Processes"),
    MOUNTS("Mounts"),
    KERNEL_LOG("dmesg"),
}

/**
 * Holds live `gvx-ctrl/2` inspection state for one running or paused container.
 */
data class ContainerInspectorState(
    val profileId: String,
    val displayName: String,
    val selectedTab: InspectorTab = InspectorTab.STATS,
    val loading: Boolean = false,
    val inspect: SandboxInspectSnapshot? = null,
    val stats: LiveStatsSnapshot? = null,
    val processes: List<LiveProcessInfo> = emptyList(),
    val mounts: List<LiveMountInfo> = emptyList(),
    val kernelLog: List<LiveKernelLogEntry> = emptyList(),
    val errorMessage: String? = null,
    val actionMessage: String? = null,
)

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
    val inspector: ContainerInspectorState? = null,
    val serviceNotice: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val runningCount: Int
        get() = runStates.values.count { it == SandboxRunState.RUNNING }

    val pausedCount: Int
        get() = runStates.values.count { it == SandboxRunState.PAUSED }

    val liveInMemoryCount: Int
        get() = runStates.values.count { it.isLiveInMemory }

    val exceedsRecommendedSandboxCount: Boolean
        get() = liveInMemoryCount > GlobalAppSettings.DEFAULT_MAX_ACTIVE_SANDBOXES

    val hasReadinessWarning: Boolean
        get() = hostReadiness.phantomKillerDetected ||
            hostReadiness.exceedsRecommendedProcessBudget ||
            exceedsRecommendedSandboxCount

    val readinessWarningMessage: String?
        get() = HomeViewModel.formatHostReadinessWarning(hostReadiness, liveInMemoryCount)

    val isNoticeError: Boolean
        get() = errorMessage != null || HomeViewModel.isErrorServiceNotice(serviceNotice)

    val activeOrFrozenCount: Int
        get() = runStates.values.count {
            it == SandboxRunState.RUNNING || it == SandboxRunState.PAUSED || it == SandboxRunState.FROZEN
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
    private val inspectorState = MutableStateFlow<ContainerInspectorState?>(null)
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

    val uiState: StateFlow<HomeUiState> = combine(
        baseFlow,
        localFlow,
        gitProjects,
        inspectorState,
    ) { base, local, projects, inspector ->
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
            inspector = inspector,
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

    fun pauseProfile(profileId: String) {
        scope.launch {
            markBusy(profileId)
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                runtimeGateway.pauseSandbox(profileId, quiesceNet = true)
                statusMessage.value = "Paused container in memory."
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage.value = err.message ?: "Failed to pause container"
            }
            clearBusy(profileId)
        }
    }

    fun unpauseProfile(profileId: String) {
        scope.launch {
            markBusy(profileId)
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                runtimeGateway.unpauseSandbox(profileId)
                statusMessage.value = "Resumed container."
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage.value = err.message ?: "Failed to resume container"
            }
            clearBusy(profileId)
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
                if (err is CancellationException) throw err
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
                if (inspectorState.value?.profileId == profileId) {
                    inspectorState.value = null
                }
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage.value = err.message ?: "Failed to stop container"
            }
            clearBusy(profileId)
        }
    }

    fun freezeAllRunning() {
        scope.launch {
            errorMessage.value = null
            val runningIds = runtimeGateway.runStates.value
                .filterValues { it.isLiveInMemory }
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
                if (err is CancellationException) throw err
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
                inspectorState.value = null
                runtimeGateway.refreshHostReadiness()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage.value = err.message ?: "Failed to stop all containers"
            }
            activeIds.forEach(::clearBusy)
        }
    }

    fun openInspector(profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        inspectorState.value = ContainerInspectorState(
            profileId = profile.id,
            displayName = profile.displayName,
            loading = true,
        )
        refreshInspector()
    }

    fun closeInspector() {
        inspectorState.value = null
    }

    fun selectInspectorTab(tab: InspectorTab) {
        val cur = inspectorState.value ?: return
        inspectorState.value = cur.copy(selectedTab = tab, actionMessage = null)
    }

    fun refreshInspector() {
        val cur = inspectorState.value ?: return
        val profileId = cur.profileId
        inspectorState.value = cur.copy(loading = true, errorMessage = null)
        scope.launch {
            val inspectDef = async { runCatching { runtimeGateway.inspectSandbox(profileId) } }
            val statsDef = async { runCatching { runtimeGateway.statsSandbox(profileId) } }
            val procsDef = async { runCatching { runtimeGateway.listGuestProcesses(profileId) } }
            val dmesgDef = async { runCatching { runtimeGateway.readKernelLog(profileId, tail = 200) } }

            val inspectRes = inspectDef.await()
            val statsRes = statsDef.await()
            val procsRes = procsDef.await()
            val dmesgRes = dmesgDef.await()

            val firstErr = listOf(inspectRes, statsRes, procsRes, dmesgRes)
                .mapNotNull { it.exceptionOrNull() }
                .firstOrNull()
            if (firstErr is CancellationException) throw firstErr

            val latest = inspectorState.value
            if (latest?.profileId != profileId) return@launch
            val inspectSnap = inspectRes.getOrNull() ?: latest.inspect
            inspectorState.value = latest.copy(
                loading = false,
                inspect = inspectSnap,
                stats = statsRes.getOrNull() ?: latest.stats,
                processes = procsRes.getOrNull() ?: latest.processes,
                mounts = inspectSnap?.mounts ?: latest.mounts,
                kernelLog = dmesgRes.getOrNull() ?: latest.kernelLog,
                errorMessage = if (inspectSnap == null && statsRes.isFailure) {
                    firstErr?.message ?: "Control socket unavailable"
                } else {
                    null
                },
            )
        }
    }

    fun signalInspectorProcess(pid: Int, signal: String) {
        val cur = inspectorState.value ?: return
        val profileId = cur.profileId
        scope.launch {
            inspectorState.value = inspectorState.value?.copy(loading = true, errorMessage = null, actionMessage = null)
            runCatching {
                runtimeGateway.signalGuestProcess(profileId, pid = pid, signal = signal)
            }.onSuccess {
                inspectorState.value = inspectorState.value?.copy(
                    actionMessage = "Sent $signal to PID $pid.",
                )
                refreshInspector()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                inspectorState.value = inspectorState.value?.copy(
                    loading = false,
                    errorMessage = err.message ?: "Failed to signal PID $pid",
                )
            }
        }
    }

    fun remountInspectorMount(targetPath: String, readOnly: Boolean) {
        val cur = inspectorState.value ?: return
        val profileId = cur.profileId
        scope.launch {
            inspectorState.value = inspectorState.value?.copy(loading = true, errorMessage = null, actionMessage = null)
            runCatching {
                val resultingRo = runtimeGateway.remountLive(profileId, targetPath, readOnly)
                profileRepository.getProfile(profileId)?.let { profile ->
                    val updated = profile.volumes.map { vol ->
                        if (vol.guestMountPath == targetPath) vol.copy(readOnly = resultingRo) else vol
                    }
                    if (updated != profile.volumes) {
                        profileRepository.upsertProfile(profile.copy(volumes = updated))
                    }
                }
                resultingRo
            }.onSuccess { ro ->
                inspectorState.value = inspectorState.value?.copy(
                    actionMessage = "Remounted $targetPath ${if (ro) "read-only" else "read-write"}.",
                )
                refreshInspector()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                inspectorState.value = inspectorState.value?.copy(
                    loading = false,
                    errorMessage = err.message ?: "Failed to remount $targetPath",
                )
            }
        }
    }

    fun unmountInspectorMount(targetPath: String, detach: Boolean = false) {
        val cur = inspectorState.value ?: return
        val profileId = cur.profileId
        scope.launch {
            inspectorState.value = inspectorState.value?.copy(loading = true, errorMessage = null, actionMessage = null)
            runCatching {
                val normalized = targetPath.trim().trimEnd('/').ifEmpty { "/" }
                runtimeGateway.detachMountLive(profileId, normalized, detach = detach)
                profileRepository.getProfile(profileId)?.let { profile ->
                    val updatedVolumes = profile.volumes.filterNot { it.guestMountPath.trimEnd('/') == normalized }
                    val updatedTmpfs = profile.tmpfsMounts.filterNot { it.trimEnd('/') == normalized }
                    if (updatedVolumes != profile.volumes || updatedTmpfs != profile.tmpfsMounts) {
                        profileRepository.upsertProfile(
                            profile.copy(volumes = updatedVolumes, tmpfsMounts = updatedTmpfs),
                        )
                    }
                }
            }.onSuccess {
                inspectorState.value = inspectorState.value?.copy(
                    actionMessage = "Unmounted $targetPath.",
                )
                refreshInspector()
            }.onFailure { err ->
                if (err is CancellationException) throw err
                inspectorState.value = inspectorState.value?.copy(
                    loading = false,
                    errorMessage = err.message ?: "Failed to unmount $targetPath",
                )
            }
        }
    }

    fun clearInspectorKernelLog() {
        val cur = inspectorState.value ?: return
        val profileId = cur.profileId
        scope.launch {
            inspectorState.value = inspectorState.value?.copy(loading = true, errorMessage = null, actionMessage = null)
            runCatching {
                runtimeGateway.readKernelLog(profileId, tail = 200, clear = true)
            }.onSuccess {
                inspectorState.value = inspectorState.value?.copy(
                    loading = false,
                    kernelLog = emptyList(),
                    actionMessage = "Cleared kernel log ring.",
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                inspectorState.value = inspectorState.value?.copy(
                    loading = false,
                    errorMessage = err.message ?: "Failed to clear kernel log",
                )
            }
        }
    }

    fun createProfile(
        displayName: String,
        imageRef: String,
        memoryMiB: Int = SandboxProfile.UNLIMITED_MEMORY_MIB,
        cpuCores: Double = SandboxProfile.UNLIMITED_CPU_CORES,
        pidsLimit: Int = SandboxProfile.UNLIMITED_PIDS_LIMIT,
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
                        name = gitProjectDisplayName?.trim()?.takeIf { it.isNotEmpty() },
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
                    pidsLimit = SandboxProfile.clampPidsLimit(pidsLimit),
                    volumes = defaultVolumes,
                    persistentRootfs = persistentRootfs,
                    gitWorktree = gitWorktree,
                )
                profileRepository.upsertProfile(profile)
                gitProjects.value = runtimeGateway.gitWorktreeManager().listProjects()
            }.onFailure { err ->
                if (err is CancellationException) throw err
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
        pidsLimit: Int? = null,
    ) {
        scope.launch {
            errorMessage.value = null
            statusMessage.value = null
            runCatching {
                val existing = profileRepository.getProfile(profileId)
                    ?: error("Container not found")
                val clampedMem = SandboxProfile.clampMemoryMiB(memoryMiB)
                val clampedCpu = SandboxProfile.clampCpuCores(cpuCores)
                val clampedPids = pidsLimit?.let { SandboxProfile.clampPidsLimit(it) } ?: existing.pidsLimit
                val isLive = runtimeGateway.runStates.value[profileId]?.isLiveInMemory == true
                val liveResourceUpdate = LiveResourceUpdate(
                    memoryMiB = if (clampedMem != existing.memoryMiB) clampedMem else null,
                    cpuCores = if (clampedCpu != existing.cpuCores) clampedCpu else null,
                    pidsLimit = if (clampedPids != existing.pidsLimit) clampedPids else null,
                )
                if (isLive && liveResourceUpdate.hasChanges) {
                    runtimeGateway.updateResourcesLive(profileId, liveResourceUpdate)
                }

                val wtMgr = runtimeGateway.gitWorktreeManager()
                val prevGit = existing.gitWorktree
                if (gitWorktree != null) {
                    wtMgr.createOrEnsureProject(
                        projectId = gitWorktree.projectId,
                        name = gitProjectDisplayName?.trim()?.takeIf { it.isNotEmpty() },
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
                    memoryMiB = clampedMem,
                    cpuCores = clampedCpu,
                    pidsLimit = clampedPids,
                    persistentRootfs = persistentRootfs,
                    volumes = cleanedVolumes,
                    gitWorktree = gitWorktree,
                )
                profileRepository.upsertProfile(updated)
                gitProjects.value = wtMgr.listProjects()
                val restartOnlyChanged = persistentRootfs != existing.persistentRootfs || gitWorktree != prevGit
                statusMessage.value = when {
                    isLive && liveResourceUpdate.hasChanges && restartOnlyChanged ->
                        "Applied resource limits live. Restart ${updated.displayName} for storage changes."
                    isLive && liveResourceUpdate.hasChanges ->
                        "Applied resource limits live on ${updated.displayName}."
                    isLive && restartOnlyChanged ->
                        "Saved ${updated.displayName}. Restart container to apply changes."
                    else ->
                        "Saved ${updated.displayName}."
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
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
                val isLive = runtimeGateway.runStates.value[profileId]?.isLiveInMemory == true
                val canHotAttach = isLive && mount.kind == VolumeKind.NAMED_EXT4LITE
                if (canHotAttach) {
                    runtimeGateway.attachVolumeLive(profileId, mount)
                }
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
                statusMessage.value = when {
                    canHotAttach -> "Mounted $cleanVol at $cleanPath live."
                    isLive -> "Attached $cleanVol at $cleanPath. Restart container to apply CoW overlay."
                    else -> "Attached $cleanVol at $cleanPath."
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
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
                val matching = profile.volumes.filter { vol ->
                    (vol.kind == VolumeKind.NAMED_EXT4LITE || vol.kind == VolumeKind.OVERLAY_EXT4LITE) &&
                        vol.sourceName == volumeName &&
                        (guestMountPath == null || vol.guestMountPath == guestMountPath)
                }
                val isLive = runtimeGateway.runStates.value[profileId]?.isLiveInMemory == true
                val canHotDetach = isLive && matching.isNotEmpty() && matching.all { it.kind == VolumeKind.NAMED_EXT4LITE }
                if (canHotDetach) {
                    for (vol in matching) {
                        runtimeGateway.detachMountLive(profileId, vol.guestMountPath, detach = false)
                    }
                }
                val updatedVolumes = profile.volumes.filterNot { vol ->
                    (vol.kind == VolumeKind.NAMED_EXT4LITE || vol.kind == VolumeKind.OVERLAY_EXT4LITE) &&
                        vol.sourceName == volumeName &&
                        (guestMountPath == null || vol.guestMountPath == guestMountPath)
                }
                profileRepository.upsertProfile(profile.copy(volumes = updatedVolumes))
                statusMessage.value = when {
                    canHotDetach -> "Unmounted $volumeName live."
                    isLive -> "Detached $volumeName. Restart container to apply."
                    else -> "Detached $volumeName."
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
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
            pausedCount: Int = 0,
        ): String {
            val parts = mutableListOf<String>()
            if (runningCount > 0 || pausedCount == 0) {
                parts.add("$runningCount running")
            }
            if (pausedCount > 0) {
                parts.add("$pausedCount paused")
            }
            if (liveChildProcessCount > 0) {
                parts.add("$liveChildProcessCount/32 procs")
            }
            return parts.joinToString(" · ")
        }

        fun isErrorServiceNotice(notice: String?): Boolean {
            if (notice.isNullOrBlank()) return false
            return notice.contains("SIGKILL") ||
                notice.contains("exited unexpectedly") ||
                notice.contains("already in use", ignoreCase = true)
        }
    }
}
