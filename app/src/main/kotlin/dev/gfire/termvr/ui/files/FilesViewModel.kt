package dev.gfire.termvr.ui.files

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.runtime.VolumeDirEntry
import dev.gfire.termvr.provider.WorkspaceDocumentsEngine
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileRootTab {
    WORKSPACE,
    VOLUME,
}

data class HostFileItem(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

data class FilesUiState(
    val profiles: List<SandboxProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val selectedRootTab: FileRootTab = FileRootTab.WORKSPACE,
    val runState: SandboxRunState = SandboxRunState.STOPPED,
    val writeLocked: Boolean = false,
    val workspacePath: String = "",
    val workspaceEntries: List<HostFileItem> = emptyList(),
    val selectedVolumeName: String = "data",
    val volumePath: String = "/",
    val volumeEntries: List<VolumeDirEntry> = emptyList(),
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
    val selectedVolumeName: String = "data",
    val volumePath: String = "/",
    val volumeEntries: List<VolumeDirEntry> = emptyList(),
    val hasActiveCheckpoint: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Manages `/workspace` host files, `ext4lite` volume ops, and frozen-checkpoint unlock controls.
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
        val defaultVol = selected?.volumes
            ?.firstOrNull { it.kind == VolumeKind.NAMED_EXT4LITE }
            ?.sourceName
            ?: local.selectedVolumeName

        FilesUiState(
            profiles = profiles,
            selectedProfileId = selected?.id,
            selectedRootTab = local.selectedRootTab,
            runState = runState,
            writeLocked = locked,
            workspacePath = local.workspacePath,
            workspaceEntries = local.workspaceEntries,
            selectedVolumeName = if (selected != null && local.selectedProfileId == null) defaultVol else local.selectedVolumeName,
            volumePath = local.volumePath,
            volumeEntries = local.volumeEntries,
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
        val profile = uiState.value.profiles.firstOrNull { it.id == profileId }
        val defaultVol = profile?.volumes
            ?.firstOrNull { it.kind == VolumeKind.NAMED_EXT4LITE }
            ?.sourceName
            ?: "data"
        localState.value = localState.value.copy(
            selectedProfileId = profileId,
            workspacePath = "",
            volumePath = "/",
            selectedVolumeName = defaultVol,
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
            refreshVolume()
        }
    }

    fun selectVolumeName(volumeName: String) {
        localState.value = localState.value.copy(selectedVolumeName = volumeName, volumePath = "/")
        refreshVolume()
    }

    fun clearBanners() {
        localState.value = localState.value.copy(statusMessage = null, errorMessage = null)
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
        val profile = uiState.value.selectedProfile ?: return
        scope.launch {
            runCatching {
                val hasCheckpoint = runtimeGateway.hasActiveCheckpoint(profile.id)
                val entries = withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val rel = localState.value.workspacePath
                    val targetDir = if (rel.isEmpty()) root else File(root, rel)
                    val canonicalRoot = root.canonicalFile.toPath()
                    if (!targetDir.canonicalFile.toPath().startsWith(canonicalRoot) || !targetDir.isDirectory) {
                        emptyList()
                    } else {
                        targetDir.listFiles()
                            .orEmpty()
                            .asSequence()
                            .filter {
                                it.name != WorkspaceDocumentsEngine.HIDDEN_META_DIR &&
                                    !it.name.endsWith(WorkspaceDocumentsEngine.TEMP_WRITE_SUFFIX) &&
                                    it.canonicalFile.toPath().startsWith(canonicalRoot)
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
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Workspace read failed")
            }
        }
    }

    fun createWorkspaceDirectory(name: String) {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        val clean = sanitizeSingleName(name) ?: run {
            localState.value = localState.value.copy(errorMessage = "Invalid folder name.")
            return
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val parent = if (state.workspacePath.isEmpty()) root else File(root, state.workspacePath)
                    val dir = File(parent, clean)
                    if (!dir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                        throw SecurityException("Path escapes workspace")
                    }
                    if (!dir.mkdir()) error("Unable to create folder $clean")
                }
                refreshWorkspace()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Folder creation failed")
            }
        }
    }

    fun deleteWorkspaceItem(item: HostFileItem) {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id)
                    val target = File(root, item.relativePath)
                    if (!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                        throw SecurityException("Path escapes workspace")
                    }
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                }
                refreshWorkspace()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Delete failed")
            }
        }
    }

    fun importWorkspaceFile(displayName: String, bytes: ByteArray) {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Workspace is locked by an active checkpoint.")
            return
        }
        val clean = sanitizeSingleName(displayName) ?: run {
            localState.value = localState.value.copy(errorMessage = "Invalid file name.")
            return
        }
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val root = runtimeGateway.workspaceDir(profile.id).apply { mkdirs() }
                    val parent = if (state.workspacePath.isEmpty()) root else File(root, state.workspacePath)
                    val target = File(parent, clean)
                    if (!target.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                        throw SecurityException("Path escapes workspace")
                    }
                    target.writeBytes(bytes)
                }
                localState.value = localState.value.copy(statusMessage = "Imported $clean into /workspace.")
                refreshWorkspace()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Import failed")
            }
        }
    }

    fun shareWorkspaceFile(item: HostFileItem) {
        val profile = uiState.value.selectedProfile ?: return
        if (item.isDirectory) return
        scope.launch {
            runCatching {
                val bytes = withContext(ioDispatcher) {
                    File(runtimeGateway.workspaceDir(profile.id), item.relativePath).readBytes()
                }
                runtimeGateway.shareNamedFile(item.name, bytes)
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Share failed")
            }
        }
    }

    /**
     * Discards a saved snapshot so `/workspace` becomes writable immediately.
     */
    fun discardCheckpointAndUnlock() {
        val profile = uiState.value.selectedProfile ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                runtimeGateway.discardCheckpoint(profile.id)
                localState.value = localState.value.copy(
                    hasActiveCheckpoint = false,
                    statusMessage = "Discarded checkpoint and unlocked workspace.",
                )
                refreshWorkspace()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Failed to discard checkpoint")
            }
            localState.value = localState.value.copy(busy = false)
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
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                val entries = withVolumeOps(profile, state.selectedVolumeName, readOnly = true) { mux, root ->
                    withContext(ioDispatcher) {
                        mux.volumeReadDir(root, localState.value.volumePath)
                    }
                }
                localState.value = localState.value.copy(
                    volumeEntries = entries.sortedWith(compareBy<VolumeDirEntry>({ !it.isDir }, { it.name.lowercase() })),
                )
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Volume read failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    fun createVolumeDirectory(name: String) {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Volume writes blocked while checkpoint is active.")
            return
        }
        val clean = sanitizeSingleName(name) ?: return
        val fullPath = joinGuestPath(state.volumePath, clean)
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                withVolumeOps(profile, state.selectedVolumeName, readOnly = false) { mux, root ->
                    withContext(ioDispatcher) { mux.volumeMkdir(root, fullPath) }
                }
                refreshVolume()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Volume mkdir failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    fun deleteVolumeEntry(entry: VolumeDirEntry) {
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        if (state.writeLocked) {
            localState.value = localState.value.copy(errorMessage = "Volume deletes blocked while checkpoint is active.")
            return
        }
        val fullPath = joinGuestPath(state.volumePath, entry.name)
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                withVolumeOps(profile, state.selectedVolumeName, readOnly = false) { mux, root ->
                    withContext(ioDispatcher) { mux.volumeUnlink(root, fullPath, recursive = entry.isDir) }
                }
                refreshVolume()
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Volume delete failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    fun shareVolumeFile(entry: VolumeDirEntry) {
        if (entry.isDir) return
        val state = uiState.value
        val profile = state.selectedProfile ?: return
        val fullPath = joinGuestPath(state.volumePath, entry.name)
        scope.launch {
            localState.value = localState.value.copy(busy = true, errorMessage = null)
            runCatching {
                val bytes = withVolumeOps(profile, state.selectedVolumeName, readOnly = true) { mux, root ->
                    withContext(ioDispatcher) {
                        val out = ByteArrayOutputStream()
                        mux.volumeReadFile(root = root, path = fullPath, output = out)
                        out.toByteArray()
                    }
                }
                runtimeGateway.shareNamedFile(entry.name, bytes)
            }.onFailure { err ->
                localState.value = localState.value.copy(errorMessage = err.message ?: "Volume share failed")
            }
            localState.value = localState.value.copy(busy = false)
        }
    }

    private suspend fun <T> withVolumeOps(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        block: suspend (dev.gfire.termvr.core.runtime.MuxSessionClient, String) -> T,
    ): T {
        val liveMux = runtimeGateway.getMuxClient(profile.id)
        if (liveMux != null) {
            val mountPoint = profile.volumes
                .firstOrNull { it.sourceName == volumeName && it.kind == VolumeKind.NAMED_EXT4LITE }
                ?.guestMountPath
                ?: "/data"
            return block(liveMux, mountPoint)
        }
        return runtimeGateway.withVolumeClient(profile, volumeName, readOnly, block)
    }

    private fun joinGuestPath(base: String, name: String): String {
        val cleanBase = base.trimEnd('/')
        return if (cleanBase.isEmpty()) "/$name" else "$cleanBase/$name"
    }

    private fun sanitizeSingleName(raw: String): String? {
        val trimmed = raw.trim()
        if (
            trimmed.isEmpty() ||
            trimmed == "." ||
            trimmed == ".." ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            trimmed == WorkspaceDocumentsEngine.HIDDEN_META_DIR ||
            trimmed.endsWith(WorkspaceDocumentsEngine.TEMP_WRITE_SUFFIX)
        ) {
            return null
        }
        return trimmed
    }

    fun close() {
        scope.cancel()
    }
}
