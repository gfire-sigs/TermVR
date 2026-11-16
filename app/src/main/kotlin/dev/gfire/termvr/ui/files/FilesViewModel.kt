package dev.gfire.termvr.ui.files

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountSpec
import dev.gfire.termvr.core.runtime.VolumeDirEntry
import dev.gfire.termvr.provider.WorkspaceDocumentsEngine
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.util.SafUriUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
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

enum class FileRootTab {
    WORKSPACE,
    VOLUME,
}

enum class VolumePauseKind {
    ACTIVE_CONTAINER_SINGLE_WRITER,
    FROZEN_CHECKPOINT_LOCK,
}

data class VolumeAccessPauseState(
    val volumeName: String,
    val kind: VolumePauseKind,
    val message: String,
    val attachedProfileIds: List<String> = emptyList(),
    val attachedProfileNames: List<String> = emptyList(),
)

data class HostFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

data class VolumeMountAttachment(
    val profileId: String,
    val profileDisplayName: String,
    val guestMountPath: String,
    val isRunning: Boolean,
    val runState: SandboxRunState = if (isRunning) SandboxRunState.RUNNING else SandboxRunState.STOPPED,
)

data class ManagedVolumeItem(
    val name: String,
    val sizeBytes: Long = 0L,
    val mounts: List<VolumeMountAttachment> = emptyList(),
) {
    val hasRunningMount: Boolean
        get() = mounts.any { it.isRunning }

    val hasFrozenMount: Boolean
        get() = mounts.any { it.runState.locksWorkspaceWrites }

    val activeProfileNames: List<String>
        get() = mounts.filter { it.isRunning }.map { it.profileDisplayName }.distinct()
}

data class FilesUiState(
    val profiles: List<SandboxProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedRootTab: FileRootTab = FileRootTab.WORKSPACE,
    val runState: SandboxRunState = SandboxRunState.STOPPED,
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val writeLocked: Boolean = false,
    val workspacePath: String = "",
    val workspaceEntries: List<HostFileItem> = emptyList(),
    val volumes: List<ManagedVolumeItem> = emptyList(),
    val selectedVolumeName: String? = null,
    val selectedVolumePause: VolumeAccessPauseState? = null,
    val volumePath: String = "/",
    val volumeEntries: List<VolumeDirEntry> = emptyList(),
    val pendingRestartProfileId: String? = null,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
) {
    val selectedProfile: SandboxProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
}

private data class FilesLocalState(
    val selectedProfileId: String? = null,
    val selectedRootTab: FileRootTab = FileRootTab.WORKSPACE,
    val workspacePath: String = "",
    val workspaceEntries: List<HostFileItem> = emptyList(),
    val hostVolumes: List<RungvVolumeLsEntry> = emptyList(),
    val selectedVolumeName: String? = null,
    val selectedVolumePause: VolumeAccessPauseState? = null,
    val volumePath: String = "/",
    val volumeEntries: List<VolumeDirEntry> = emptyList(),
    val hasActiveCheckpoint: Boolean = false,
    val pendingRestartProfileId: String? = null,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Manages `/workspace` host files, streaming SAF imports, volume lifecycle actions, and checkpoint locks.
 */
class FilesViewModel(
    private val profileRepository: ProfileRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val localState = MutableStateFlow(FilesLocalState())

    val uiState: StateFlow<FilesUiState> = combine(
        profileRepository.observeProfiles(),
        runtimeGateway.runStates,
        localState,
    ) { profiles, runStates, local ->
        val selected = profiles.firstOrNull { it.id == local.selectedProfileId } ?: profiles.firstOrNull()
        val runState = selected?.let { runStates[it.id] } ?: SandboxRunState.STOPPED
        val locked = runState.locksWorkspaceWrites || local.hasActiveCheckpoint

        val hostSizeByName = local.hostVolumes.associate { it.name to it.sizeBytes }
        val attachmentsByVol = LinkedHashMap<String, MutableList<VolumeMountAttachment>>()
        for (vol in local.hostVolumes) {
            attachmentsByVol.getOrPut(vol.name) { mutableListOf() }
        }
        for (profile in profiles) {
            val profileRunState = runStates[profile.id] ?: SandboxRunState.STOPPED
            val holdsMount = profileRunState.holdsVolumeMount()
            if (profile.persistentRootfs) {
                attachmentsByVol.getOrPut(profile.rootfsVolumeName) { mutableListOf() }.add(
                    VolumeMountAttachment(
                        profileId = profile.id,
                        profileDisplayName = profile.displayName,
                        guestMountPath = "/",
                        isRunning = holdsMount,
                        runState = profileRunState,
                    ),
                )
            }
            for (mount in profile.volumes) {
                if (mount.kind == VolumeKind.NAMED_EXT4LITE) {
                    attachmentsByVol.getOrPut(mount.sourceName) { mutableListOf() }.add(
                        VolumeMountAttachment(
                            profileId = profile.id,
                            profileDisplayName = profile.displayName,
                            guestMountPath = mount.guestMountPath,
                            isRunning = holdsMount,
                            runState = profileRunState,
                        ),
                    )
                }
            }
        }
        val managedVolumes = attachmentsByVol.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, mounts) ->
                ManagedVolumeItem(
                    name = name,
                    sizeBytes = hostSizeByName[name] ?: 0L,
                    mounts = mounts,
                )
            }

        val activeVolItem = local.selectedVolumeName?.let { selName ->
            managedVolumes.firstOrNull { it.name == selName }
        }
        val effectiveVolumePause = computeReactiveVolumePause(
            activeVolItem = activeVolItem,
            local = local,
        )

        FilesUiState(
            profiles = profiles,
            selectedProfileId = selected?.id,
            selectedRootTab = local.selectedRootTab,
            runState = runState,
            runStates = runStates,
            writeLocked = locked,
            workspacePath = local.workspacePath,
            workspaceEntries = local.workspaceEntries,
            volumes = managedVolumes,
            selectedVolumeName = local.selectedVolumeName,
            selectedVolumePause = effectiveVolumePause,
            volumePath = local.volumePath,
            volumeEntries = local.volumeEntries,
            pendingRestartProfileId = local.pendingRestartProfileId,
            busy = local.busy,
            statusMessage = local.statusMessage,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = FilesUiState(),
    )

    init {
        scope.launch {
            profileRepository.observeProfiles().collect { profiles ->
                val current = localState.value.selectedProfileId
                if (current == null && profiles.isNotEmpty()) {
                    selectProfile(profiles.first().id)
                }
            }
        }
    }

    fun selectProfile(profileId: String) {
        localState.value = localState.value.copy(
            selectedProfileId = profileId,
            workspacePath = "",
            volumePath = "/",
            errorMessage = null,
            statusMessage = null,
        )
        refreshWorkspace()
    }

    fun selectRootTab(tab: FileRootTab) {
        localState.value = localState.value.copy(selectedRootTab = tab)
        if (tab == FileRootTab.WORKSPACE) {
            refreshWorkspace()
        } else {
            refreshVolumeList()
        }
    }

    fun selectVolumeName(volumeName: String) {
        localState.value = localState.value.copy(
            selectedVolumeName = volumeName,
            selectedVolumePause = null,
            volumePath = "/",
        )
        refreshVolume()
    }

    fun clearBanners() {
        localState.value = localState.value.copy(
            statusMessage = null,
            errorMessage = null,
            pendingRestartProfileId = null,
        )
    }

    fun refreshVolumeList() {
        scope.launch {
            runCatching { runtimeGateway.listVolumes() }
                .onSuccess { entries ->
                    localState.value = localState.value.copy(hostVolumes = entries)
                }
                .onFailure { err ->
                    if (err is CancellationException) throw err
                }
        }
    }

    fun createNamedVolume(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            localState.value = localState.value.copy(errorMessage = "Volume name cannot be blank.")
            return
        }
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                runtimeGateway.createVolume(clean)
                val updated = runCatching { runtimeGateway.listVolumes() }
                    .getOrDefault(localState.value.hostVolumes + RungvVolumeLsEntry(name = clean))
                localState.value = localState.value.copy(
                    hostVolumes = updated,
                    statusMessage = "Created volume $clean.",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to create volume",
                )
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun deleteNamedVolume(name: String) {
        val item = uiState.value.volumes.firstOrNull { it.name == name }
        if (item != null && item.hasRunningMount) {
            localState.value = localState.value.copy(
                errorMessage = "Stop containers mounting $name before deleting.",
            )
            return
        }
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                runtimeGateway.removeVolume(name, force = false)
                for (profile in uiState.value.profiles) {
                    if (profile.volumes.any { it.kind == VolumeKind.NAMED_EXT4LITE && it.sourceName == name }) {
                        val cleaned = profile.volumes.filterNot {
                            it.kind == VolumeKind.NAMED_EXT4LITE && it.sourceName == name
                        }
                        profileRepository.upsertProfile(profile.copy(volumes = cleaned))
                    }
                }
                val updated = runCatching { runtimeGateway.listVolumes() }
                    .getOrDefault(localState.value.hostVolumes.filterNot { it.name == name })
                val nextSelected = if (localState.value.selectedVolumeName == name) {
                    null
                } else {
                    localState.value.selectedVolumeName
                }
                localState.value = localState.value.copy(
                    hostVolumes = updated,
                    selectedVolumeName = nextSelected,
                    selectedVolumePause = if (nextSelected == null) null else localState.value.selectedVolumePause,
                    volumeEntries = if (nextSelected == null) emptyList() else localState.value.volumeEntries,
                    statusMessage = "Deleted volume $name.",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to delete volume",
                )
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun mountVolumeToProfile(
        volumeName: String,
        profileId: String,
        guestMountPath: String = "/data",
    ) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        val cleanPath = guestMountPath.trim().ifEmpty { "/data" }
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val mount = VolumeMountSpec(
                    sourceName = volumeName,
                    guestMountPath = cleanPath,
                    kind = VolumeKind.NAMED_EXT4LITE,
                    readOnly = false,
                )
                val updatedVolumes = profile.volumes.filterNot {
                    it.kind == VolumeKind.NAMED_EXT4LITE &&
                        (it.sourceName == volumeName || it.guestMountPath == cleanPath)
                } + mount
                profileRepository.upsertProfile(profile.copy(volumes = updatedVolumes))
                val isRunning = runtimeGateway.runStates.value[profileId] == SandboxRunState.RUNNING
                localState.value = localState.value.copy(
                    pendingRestartProfileId = if (isRunning) profileId else null,
                    statusMessage = if (isRunning) {
                        "Mounted $volumeName at $cleanPath. Restart ${profile.displayName} to apply."
                    } else {
                        "Mounted $volumeName at $cleanPath on ${profile.displayName}."
                    },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Mount failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun unmountVolumeFromProfile(volumeName: String, profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val updatedVolumes = profile.volumes.filterNot {
                    it.kind == VolumeKind.NAMED_EXT4LITE && it.sourceName == volumeName
                }
                profileRepository.upsertProfile(profile.copy(volumes = updatedVolumes))
                val isRunning = runtimeGateway.runStates.value[profileId] == SandboxRunState.RUNNING
                localState.value = localState.value.copy(
                    pendingRestartProfileId = if (isRunning) profileId else null,
                    statusMessage = if (isRunning) {
                        "Unmounted $volumeName. Restart ${profile.displayName} to apply."
                    } else {
                        "Unmounted $volumeName from ${profile.displayName}."
                    },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Unmount failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun restartProfileForMount(profileId: String) {
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        scope.launch {
            localState.value = localState.value.copy(
                busy = true,
                errorMessage = null,
                pendingRestartProfileId = null,
            )
            try {
                runtimeGateway.stopSandbox(profile.id)
                runtimeGateway.startOrResumeSandbox(profile)
                localState.value = localState.value.copy(statusMessage = "Restarted ${profile.displayName}.")
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Restart failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun browseVolume(volumeName: String) {
        val profiles = uiState.value.profiles
        val attachedProfiles = findAttachedProfiles(profiles, volumeName)
        val preferredProfile = attachedProfiles.firstOrNull { profile ->
            (runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED).holdsVolumeMount()
        } ?: attachedProfiles.firstOrNull()
        val matchingProfileId = preferredProfile?.id ?: localState.value.selectedProfileId
        localState.value = localState.value.copy(
            selectedProfileId = matchingProfileId,
            selectedVolumeName = volumeName,
            selectedVolumePause = null,
            volumePath = "/",
            errorMessage = null,
        )
        refreshVolume()
    }

    fun closeVolumeBrowser() {
        localState.value = localState.value.copy(
            selectedVolumeName = null,
            selectedVolumePause = null,
            volumeEntries = emptyList(),
        )
    }

    /**
     * Switches to the `Workspace` tab for the container attached to [volumeName] so live host edits work.
     */
    fun openWorkspaceForVolume(volumeName: String? = null) {
        scope.launch {
            val targetVolume = volumeName ?: localState.value.selectedVolumeName
            val profiles = currentProfilesSnapshot()
            val attached = if (targetVolume != null) {
                findAttachedProfiles(profiles, targetVolume)
            } else {
                emptyList()
            }
            val preferred = attached.firstOrNull { profile ->
                (runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED).holdsVolumeMount()
            } ?: attached.firstOrNull()
            val nextProfileId = preferred?.id ?: localState.value.selectedProfileId ?: profiles.firstOrNull()?.id
            localState.value = localState.value.copy(
                selectedProfileId = nextProfileId,
                selectedRootTab = FileRootTab.WORKSPACE,
                workspacePath = "",
                errorMessage = null,
            )
            refreshWorkspace()
        }
    }

    /**
     * Stops active or frozen containers holding [volumeName] and refreshes volume browsing when unlocked.
     */
    fun stopContainersForVolume(volumeName: String) {
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            var shouldRefreshVolume = false
            try {
                val profiles = currentProfilesSnapshot()
                val attached = findAttachedProfiles(profiles, volumeName)
                val toStop = attached.filter { profile ->
                    val state = runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED
                    state != SandboxRunState.STOPPED || runtimeGateway.hasActiveCheckpoint(profile.id)
                }
                for (profile in toStop) {
                    runtimeGateway.stopSandbox(profile.id)
                }
                val stoppedNames = toStop.map { it.displayName }
                val selectedStopped = localState.value.selectedProfileId in toStop.map { it.id }
                val updatedVolumes = runCatching { runtimeGateway.listVolumes() }
                    .getOrDefault(localState.value.hostVolumes)
                val summary = when {
                    stoppedNames.size == 1 -> "Stopped ${stoppedNames.single()}."
                    stoppedNames.size > 1 -> "Stopped ${stoppedNames.size} containers for $volumeName."
                    else -> "Volume $volumeName is ready."
                }
                localState.value = localState.value.copy(
                    hostVolumes = updatedVolumes,
                    hasActiveCheckpoint = if (selectedStopped) false else localState.value.hasActiveCheckpoint,
                    selectedVolumePause = null,
                    pendingRestartProfileId = if (localState.value.pendingRestartProfileId in toStop.map { it.id }) {
                        null
                    } else {
                        localState.value.pendingRestartProfileId
                    },
                    statusMessage = summary,
                )
                shouldRefreshVolume = localState.value.selectedVolumeName == volumeName
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(
                    errorMessage = err.message ?: "Failed to stop container",
                )
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
            if (shouldRefreshVolume) {
                refreshVolume()
            }
        }
    }

    fun navigateWorkspace(relativePath: String) {
        localState.value = localState.value.copy(workspacePath = relativePath.trim('/'))
        refreshWorkspace()
    }

    fun navigateWorkspaceUp() {
        val current = localState.value.workspacePath
        if (current.isEmpty()) return
        val parent = current.substringBeforeLast('/', "")
        navigateWorkspace(parent)
    }

    fun refreshWorkspace() {
        scope.launch {
            try {
                val profile = resolveSelectedProfile() ?: return@launch
                val hasCheckpoint = runtimeGateway.hasActiveCheckpoint(profile.id)
                val entries = withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val rel = localState.value.workspacePath
                    val targetDir = if (rel.isEmpty()) root else File(root, rel)
                    val canonicalRoot = root.canonicalFile.toPath()
                    if (
                        !isPathConfinedToWorkspace(canonicalRoot, targetDir) ||
                        !targetDir.isDirectory
                    ) {
                        emptyList()
                    } else {
                        targetDir.listFiles()
                            .orEmpty()
                            .asSequence()
                            .filter { child ->
                                !SafUriUtils.isReservedWorkspaceName(child.name) &&
                                    isPathConfinedToWorkspace(canonicalRoot, child)
                            }
                            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                            .map { child ->
                                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                                HostFileItem(
                                    name = child.name,
                                    relativePath = childRel,
                                    isDirectory = child.isDirectory,
                                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                                )
                            }
                            .toList()
                    }
                }
                localState.value = localState.value.copy(
                    hasActiveCheckpoint = hasCheckpoint,
                    workspaceEntries = entries,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Workspace read failed")
            }
        }
    }

    fun createWorkspaceDirectory(name: String) {
        val state = uiState.value
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        val clean = sanitizeSingleName(name) ?: run {
            localState.value = localState.value.copy(errorMessage = "Invalid folder name.")
            return
        }
        val targetWorkspacePath = localState.value.workspacePath
        scope.launch {
            try {
                val profile = resolveSelectedProfile() ?: return@launch
                withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val canonicalRoot = root.canonicalFile.toPath()
                    val parent = if (targetWorkspacePath.isEmpty()) root else File(root, targetWorkspacePath)
                    val dir = File(parent, clean)
                    if (!isPathConfinedToWorkspace(canonicalRoot, parent) || !isPathConfinedToWorkspace(canonicalRoot, dir)) {
                        throw SecurityException("Path escapes workspace")
                    }
                    if (!dir.mkdir()) error("Unable to create folder $clean")
                }
                refreshWorkspace()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Folder creation failed")
            }
        }
    }

    fun deleteWorkspaceItem(item: HostFileItem) {
        val state = uiState.value
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        scope.launch {
            try {
                val profile = resolveSelectedProfile() ?: return@launch
                withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id)
                    val canonicalRoot = root.canonicalFile.toPath()
                    val target = File(root, item.relativePath)
                    if (!isPathConfinedToWorkspace(canonicalRoot, target)) {
                        throw SecurityException("Path escapes workspace")
                    }
                    deleteConfinedWorkspacePath(target.toPath(), canonicalRoot)
                }
                refreshWorkspace()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Delete failed")
            }
        }
    }

    /**
     * Streams a SAF document into `/workspace` on [ioDispatcher] without loading the full file into memory.
     */
    fun importWorkspaceStream(
        resolveDisplayName: () -> String,
        openStream: () -> InputStream?,
        maxBytes: Long? = null,
    ) {
        if (uiState.value.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        val targetWorkspacePath = localState.value.workspacePath
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null, statusMessage = null)
            try {
                val profile = resolveSelectedProfile()
                    ?: throw IllegalStateException("Select a container before importing files.")
                val importedName = withContext(ioDispatcher) {
                    if (
                        (runtimeGateway.runStates.value[profile.id]?.locksWorkspaceWrites == true) ||
                        runtimeGateway.hasActiveCheckpoint(profile.id)
                    ) {
                        throw SecurityException("Workspace is locked by an active checkpoint.")
                    }
                    val rawName = resolveDisplayName()
                    val clean = sanitizeSingleName(rawName)
                        ?: throw IllegalArgumentException("Invalid file name.")
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val canonicalRoot = root.canonicalFile.toPath()
                    val parent = if (targetWorkspacePath.isEmpty()) root else File(root, targetWorkspacePath)
                    val target = File(parent, clean)
                    if (
                        Files.isSymbolicLink(parent.toPath()) ||
                        Files.isSymbolicLink(target.toPath()) ||
                        !isPathConfinedToWorkspace(canonicalRoot, parent) ||
                        !isPathConfinedToWorkspace(canonicalRoot, target)
                    ) {
                        throw SecurityException("Path escapes workspace")
                    }
                    SafUriUtils.copyStreamAtomicallyToFile(
                        openStream = openStream,
                        targetFile = target,
                        maxBytes = maxBytes,
                    )
                    clean
                }
                localState.value = localState.value.copy(
                    busy = false,
                    statusMessage = "Imported $importedName.",
                )
                refreshWorkspace()
            } catch (ce: CancellationException) {
                localState.value = localState.value.copy(busy = false)
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(
                    busy = false,
                    errorMessage = err.message ?: "Import failed",
                )
            }
        }
    }

    /**
     * Streams [openStream] into `/workspace` under the explicit single-segment [displayName].
     */
    fun importWorkspaceStream(
        displayName: String,
        openStream: () -> InputStream?,
        maxBytes: Long? = null,
    ) = importWorkspaceStream(
        resolveDisplayName = { displayName },
        openStream = openStream,
        maxBytes = maxBytes,
    )

    /**
     * Imports an in-memory [bytes] buffer by delegating to the atomic streaming workspace importer.
     */
    fun importWorkspaceFile(displayName: String, bytes: ByteArray) {
        importWorkspaceStream(
            displayName = displayName,
            openStream = { ByteArrayInputStream(bytes) },
        )
    }

    fun shareWorkspaceFile(item: HostFileItem) {
        if (item.isDirectory) return
        scope.launch {
            try {
                val profile = resolveSelectedProfile() ?: return@launch
                val bytes = withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id)
                    val canonicalRoot = root.canonicalFile.toPath()
                    val target = File(root, item.relativePath)
                    if (Files.isSymbolicLink(target.toPath()) || !isPathConfinedToWorkspace(canonicalRoot, target)) {
                        throw SecurityException("Path escapes workspace")
                    }
                    target.readBytes()
                }
                runtimeGateway.shareNamedFile(item.name, bytes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Share failed")
            }
        }
    }

    fun discardCheckpointAndUnlock() {
        scope.launch {
            val profile = resolveSelectedProfile() ?: return@launch
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            try {
                runtimeGateway.discardCheckpoint(profile.id)
                localState.value = localState.value.copy(
                    hasActiveCheckpoint = false,
                    selectedVolumePause = null,
                    statusMessage = "Unlocked workspace.",
                )
                refreshWorkspace()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to unlock workspace")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun navigateVolume(path: String) {
        val normalized = if (path.startsWith('/')) path else "/$path"
        localState.value = localState.value.copy(volumePath = normalized)
        refreshVolume()
    }

    fun navigateVolumeUp() {
        val current = localState.value.volumePath
        if (current == "/" || current.isEmpty()) return
        val parent = current.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        navigateVolume(parent)
    }

    fun refreshVolume() {
        val local = localState.value
        val volumeName = local.selectedVolumeName ?: uiState.value.selectedVolumeName ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            try {
                val profiles = currentProfilesSnapshot()
                val pause = inspectVolumeAccessPause(profiles, volumeName)
                if (pause != null) {
                    localState.value = localState.value.copy(
                        selectedVolumePause = pause,
                        volumeEntries = emptyList(),
                        errorMessage = null,
                    )
                    return@launch
                }

                val profile = resolveProfileForVolume(profiles, volumeName) ?: return@launch
                val relPath = toVolumeRelativePath(localState.value.volumePath)
                val entries = withVolumeOps(profiles, profile, volumeName, readOnly = true) { mux, root ->
                    withContext(ioDispatcher) {
                        mux.volumeReadDir(root, relPath)
                    }
                }
                localState.value = localState.value.copy(
                    selectedVolumePause = null,
                    volumeEntries = entries.sortedWith(
                        compareBy<VolumeDirEntry>({ !it.isDir }, { it.name.lowercase() }),
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                val profiles = currentProfilesSnapshot()
                val activeAttached = findAttachedProfiles(profiles, volumeName).filter { profile ->
                    (runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED).holdsVolumeMount()
                }
                if (activeAttached.isNotEmpty()) {
                    localState.value = localState.value.copy(
                        selectedVolumePause = buildActiveSingleWriterPause(volumeName, activeAttached),
                        volumeEntries = emptyList(),
                        errorMessage = null,
                    )
                } else {
                    localState.value = localState.value.copy(errorMessage = err.message ?: "Volume read failed")
                }
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun createVolumeDirectory(name: String) {
        val state = uiState.value
        val volumeName = state.selectedVolumeName ?: return
        if (state.writeLocked || state.selectedVolumePause != null) {
            localState.value = localState.value.copy(
                errorMessage = state.selectedVolumePause?.message ?: "Volume is locked while frozen.",
            )
            return
        }
        val clean = sanitizeSingleName(name) ?: run {
            localState.value = localState.value.copy(errorMessage = "Invalid folder name.")
            return
        }
        val relPath = toVolumeRelativePath(joinGuestPath(state.volumePath, clean))
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            try {
                val profiles = currentProfilesSnapshot()
                val profile = resolveProfileForVolume(profiles, volumeName) ?: return@launch
                withVolumeOps(profiles, profile, volumeName, readOnly = false) { mux, root ->
                    withContext(ioDispatcher) { mux.volumeMkdir(root, relPath) }
                }
                refreshVolume()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Folder creation failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun deleteVolumeEntry(entry: VolumeDirEntry) {
        val state = uiState.value
        val volumeName = state.selectedVolumeName ?: return
        if (state.writeLocked || state.selectedVolumePause != null) {
            localState.value = localState.value.copy(
                errorMessage = state.selectedVolumePause?.message ?: "Volume is locked while frozen.",
            )
            return
        }
        val relPath = toVolumeRelativePath(joinGuestPath(state.volumePath, entry.name))
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            try {
                val profiles = currentProfilesSnapshot()
                val profile = resolveProfileForVolume(profiles, volumeName) ?: return@launch
                withVolumeOps(profiles, profile, volumeName, readOnly = false) { mux, root ->
                    withContext(ioDispatcher) { mux.volumeUnlink(root, relPath, recursive = entry.isDir) }
                }
                refreshVolume()
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Delete failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    fun shareVolumeFile(entry: VolumeDirEntry) {
        if (entry.isDir) return
        val state = uiState.value
        val volumeName = state.selectedVolumeName ?: return
        if (state.selectedVolumePause != null) {
            localState.value = localState.value.copy(errorMessage = state.selectedVolumePause.message)
            return
        }
        val relPath = toVolumeRelativePath(joinGuestPath(state.volumePath, entry.name))
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            try {
                val profiles = currentProfilesSnapshot()
                val profile = resolveProfileForVolume(profiles, volumeName) ?: return@launch
                val bytes = withVolumeOps(profiles, profile, volumeName, readOnly = true) { mux, root ->
                    withContext(ioDispatcher) {
                        val out = ByteArrayOutputStream()
                        mux.volumeReadFile(root = root, path = relPath, output = out)
                        out.toByteArray()
                    }
                }
                runtimeGateway.shareNamedFile(entry.name, bytes)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                localState.value = localState.value.copy(errorMessage = err.message ?: "Share failed")
            } finally {
                localState.value = localState.value.copy(busy = false)
            }
        }
    }

    private suspend fun <T> withVolumeOps(
        profiles: List<SandboxProfile>,
        fallbackProfile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        block: suspend (dev.gfire.termvr.core.runtime.MuxSessionClient, String) -> T,
    ): T {
        val ownerProfile = profiles.firstOrNull {
            it.persistentRootfs && it.rootfsVolumeName == volumeName
        }
        val attachedProfiles = findAttachedProfiles(profiles, volumeName)
        for (candidate in attachedProfiles) {
            if (localState.value.pendingRestartProfileId == candidate.id) continue
            val liveMux = runtimeGateway.getMuxClient(candidate.id) ?: continue
            val mountPoint = if (candidate.persistentRootfs && candidate.rootfsVolumeName == volumeName) {
                "/"
            } else {
                candidate.volumes
                    .firstOrNull { it.sourceName == volumeName && it.kind == VolumeKind.NAMED_EXT4LITE }
                    ?.guestMountPath
                    ?: "/data"
            }
            return block(liveMux, mountPoint)
        }

        val effectiveProfile = ownerProfile ?: attachedProfiles.firstOrNull() ?: fallbackProfile
        return runtimeGateway.withVolumeClient(
            profile = effectiveProfile,
            volumeName = volumeName,
            readOnly = readOnly,
            isRootfsVolume = ownerProfile != null,
            block = block,
        )
    }

    private fun computeReactiveVolumePause(
        activeVolItem: ManagedVolumeItem?,
        local: FilesLocalState,
    ): VolumeAccessPauseState? {
        if (activeVolItem == null) return null
        val explicit = local.selectedVolumePause?.takeIf { it.volumeName == activeVolItem.name }
        if (explicit != null && (activeVolItem.hasRunningMount || local.hasActiveCheckpoint)) {
            return explicit
        }
        if (activeVolItem.hasFrozenMount) {
            val frozen = activeVolItem.mounts.filter { it.runState.locksWorkspaceWrites }
            return VolumeAccessPauseState(
                volumeName = activeVolItem.name,
                kind = VolumePauseKind.FROZEN_CHECKPOINT_LOCK,
                message = FROZEN_VOLUME_LOCK_MESSAGE,
                attachedProfileIds = frozen.map { it.profileId }.distinct(),
                attachedProfileNames = frozen.map { it.profileDisplayName }.distinct(),
            )
        }
        if (activeVolItem.hasRunningMount) {
            val hasLiveMux = activeVolItem.mounts.any { mount ->
                mount.runState == SandboxRunState.RUNNING &&
                    local.pendingRestartProfileId != mount.profileId &&
                    runtimeGateway.getMuxClient(mount.profileId) != null
            }
            if (!hasLiveMux) {
                val active = activeVolItem.mounts.filter { it.isRunning }
                return VolumeAccessPauseState(
                    volumeName = activeVolItem.name,
                    kind = VolumePauseKind.ACTIVE_CONTAINER_SINGLE_WRITER,
                    message = SINGLE_WRITER_VOLUME_LOCK_MESSAGE,
                    attachedProfileIds = active.map { it.profileId }.distinct(),
                    attachedProfileNames = active.map { it.profileDisplayName }.distinct(),
                )
            }
        }
        return null
    }

    private suspend fun inspectVolumeAccessPause(
        profiles: List<SandboxProfile>,
        volumeName: String,
    ): VolumeAccessPauseState? {
        val attached = findAttachedProfiles(profiles, volumeName)
        if (attached.isEmpty()) return null

        val frozen = ArrayList<SandboxProfile>()
        for (profile in attached) {
            val state = runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED
            if (state.locksWorkspaceWrites || runtimeGateway.hasActiveCheckpoint(profile.id)) {
                frozen.add(profile)
            }
        }
        if (frozen.isNotEmpty()) {
            return VolumeAccessPauseState(
                volumeName = volumeName,
                kind = VolumePauseKind.FROZEN_CHECKPOINT_LOCK,
                message = FROZEN_VOLUME_LOCK_MESSAGE,
                attachedProfileIds = frozen.map { it.id },
                attachedProfileNames = frozen.map { it.displayName },
            )
        }

        val active = attached.filter { profile ->
            (runtimeGateway.runStates.value[profile.id] ?: SandboxRunState.STOPPED).holdsVolumeMount()
        }
        if (active.isNotEmpty()) {
            val hasLiveMux = active.any { profile ->
                (runtimeGateway.runStates.value[profile.id] == SandboxRunState.RUNNING) &&
                    localState.value.pendingRestartProfileId != profile.id &&
                    runtimeGateway.getMuxClient(profile.id) != null
            }
            if (!hasLiveMux) {
                return buildActiveSingleWriterPause(volumeName, active)
            }
        }
        return null
    }

    private fun buildActiveSingleWriterPause(
        volumeName: String,
        activeProfiles: List<SandboxProfile>,
    ): VolumeAccessPauseState = VolumeAccessPauseState(
        volumeName = volumeName,
        kind = VolumePauseKind.ACTIVE_CONTAINER_SINGLE_WRITER,
        message = SINGLE_WRITER_VOLUME_LOCK_MESSAGE,
        attachedProfileIds = activeProfiles.map { it.id }.distinct(),
        attachedProfileNames = activeProfiles.map { it.displayName }.distinct(),
    )

    private fun findAttachedProfiles(
        profiles: List<SandboxProfile>,
        volumeName: String,
    ): List<SandboxProfile> = profiles.filter { profile ->
        (profile.persistentRootfs && profile.rootfsVolumeName == volumeName) ||
            profile.volumes.any { it.kind == VolumeKind.NAMED_EXT4LITE && it.sourceName == volumeName }
    }

    private suspend fun currentProfilesSnapshot(): List<SandboxProfile> {
        val cached = uiState.value.profiles
        return if (cached.isNotEmpty()) cached else profileRepository.observeProfiles().first()
    }

    private suspend fun resolveSelectedProfile(): SandboxProfile? {
        val targetId = localState.value.selectedProfileId ?: uiState.value.selectedProfileId
        val profiles = currentProfilesSnapshot()
        if (targetId != null) {
            profiles.firstOrNull { it.id == targetId }?.let { return it }
            profileRepository.getProfile(targetId)?.let { return it }
        }
        return profiles.firstOrNull()
    }

    private fun resolveProfileForVolume(
        profiles: List<SandboxProfile>,
        volumeName: String,
    ): SandboxProfile? {
        val attached = findAttachedProfiles(profiles, volumeName)
        attached.firstOrNull()?.let { return it }
        val targetId = localState.value.selectedProfileId ?: uiState.value.selectedProfileId
        return profiles.firstOrNull { it.id == targetId } ?: profiles.firstOrNull()
    }

    private fun isPathConfinedToWorkspace(canonicalRoot: Path, candidate: File): Boolean {
        val candidateNio = candidate.toPath()
        if (Files.isSymbolicLink(candidateNio)) {
            val linkTarget = runCatching { Files.readSymbolicLink(candidateNio) }.getOrNull() ?: return false
            val parentBase = candidate.parentFile?.canonicalFile?.toPath() ?: return false
            val resolvedLink = parentBase.resolve(linkTarget).normalize()
            if (resolvedLink != canonicalRoot && !resolvedLink.startsWith(canonicalRoot)) {
                return false
            }
        }
        val canonicalCandidate = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: return false
        return canonicalCandidate == canonicalRoot || canonicalCandidate.startsWith(canonicalRoot)
    }

    private fun deleteConfinedWorkspacePath(path: Path, canonicalRoot: Path) {
        val name = path.fileName?.toString().orEmpty()
        if (
            name.equals(WorkspaceDocumentsEngine.HIDDEN_META_DIR, ignoreCase = true) ||
            name.equals(WorkspaceDocumentsEngine.HIDDEN_META_TEMP_FILE, ignoreCase = true)
        ) {
            throw SecurityException("Refusing to delete workspace metadata file: $name")
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            val canonicalDir = path.toFile().canonicalFile.toPath()
            if (canonicalDir != canonicalRoot && !canonicalDir.startsWith(canonicalRoot)) {
                throw SecurityException("Directory escapes workspace: $path")
            }
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    val childName = child.fileName?.toString().orEmpty()
                    if (
                        childName.equals(WorkspaceDocumentsEngine.HIDDEN_META_DIR, ignoreCase = true) ||
                        childName.equals(WorkspaceDocumentsEngine.HIDDEN_META_TEMP_FILE, ignoreCase = true)
                    ) {
                        continue
                    }
                    deleteConfinedWorkspacePath(child, canonicalRoot)
                }
            }
        }
        Files.deleteIfExists(path)
    }

    private fun SandboxRunState.holdsVolumeMount(): Boolean =
        this != SandboxRunState.STOPPED && this != SandboxRunState.FAILED

    private fun joinGuestPath(base: String, name: String): String {
        val cleanBase = base.trimEnd('/')
        return if (cleanBase.isEmpty()) "/$name" else "$cleanBase/$name"
    }

    internal fun toVolumeRelativePath(guestPath: String): String {
        return guestPath.trim('/').ifEmpty { "." }
    }

    private fun sanitizeSingleName(raw: String): String? {
        return SafUriUtils.sanitizeStrictSingleName(raw)
    }

    fun close() {
        scope.cancel()
    }

    companion object {
        const val SINGLE_WRITER_VOLUME_LOCK_MESSAGE =
            "Single-writer ext4lite protection keeps the live filesystem consistent while the container runs."
        const val FROZEN_VOLUME_LOCK_MESSAGE =
            "Checkpoint protection locks this ext4lite volume while the attached container is frozen."
    }
}
