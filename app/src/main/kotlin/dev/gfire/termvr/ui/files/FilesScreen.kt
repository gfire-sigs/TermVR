package dev.gfire.termvr.ui.files

import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.VolumeLockMode
import dev.gfire.termvr.core.model.VolumeMountMode
import dev.gfire.termvr.core.model.VolumeMountSpec
import dev.gfire.termvr.core.runtime.VolumeDirEntry
import dev.gfire.termvr.provider.TermVrDocumentsProvider
import dev.gfire.termvr.provider.WorkspaceDocumentsEngine
import dev.gfire.termvr.ui.home.formatBytes
import dev.gfire.termvr.ui.theme.LocalTermVrMonoFontFamily
import dev.gfire.termvr.ui.theme.LocalTermVrPaletteTokens
import dev.gfire.termvr.ui.theme.TermVrBadge
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrCard
import dev.gfire.termvr.ui.theme.TermVrControlShape
import dev.gfire.termvr.ui.theme.TermVrIcons
import dev.gfire.termvr.ui.theme.TermVrNoticeBanner
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrSelectableChip
import dev.gfire.termvr.ui.theme.TermVrTextButton
import dev.gfire.termvr.ui.theme.runStateColor
import dev.gfire.termvr.util.SafUriUtils

/**
 * Renders the adaptive Workspace file browser and full Volume management console.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    isExpandedWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContext = context.applicationContext
    var showNewFolderFor by remember { mutableStateOf<FileRootTab?>(null) }
    var showNewVolumeDialog by remember { mutableStateOf(false) }
    var showNewGitProjectDialog by remember { mutableStateOf(false) }
    var mountDialogVolume by remember { mutableStateOf<ManagedVolumeItem?>(null) }
    var mountDialogInitialMode by remember { mutableStateOf(VolumeMountMode.EXCLUSIVE_RW) }
    var expandedRightTab by remember { mutableStateOf(FileRootTab.VOLUME) }

    LaunchedEffect(Unit) {
        viewModel.refreshVolumeList()
        viewModel.refreshGitProjects()
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null && msg.startsWith("Compacted ")) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val resolver = appContext.contentResolver
            viewModel.importWorkspaceStream(
                resolveDisplayName = { SafUriUtils.resolveDisplayName(resolver, uri) },
                openStream = { resolver.openInputStream(uri) },
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Files", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isExpandedWidth || state.selectedRootTab == FileRootTab.GIT) {
                    TermVrOutlinedButton(
                        onClick = { showNewGitProjectDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("New Git Project")
                    }
                }
                if (isExpandedWidth || state.selectedRootTab == FileRootTab.VOLUME) {
                    TermVrOutlinedButton(
                        onClick = { showNewVolumeDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("New Volume")
                    }
                }
            }
        }

        if (state.profiles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (profile in state.profiles) {
                    TermVrSelectableChip(
                        label = profile.displayName,
                        selected = profile.id == state.selectedProfile?.id,
                        onClick = { viewModel.selectProfile(profile.id) },
                    )
                }
            }
        }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            val restartId = state.pendingRestartProfileId
            TermVrNoticeBanner(
                message = banner,
                isError = state.errorMessage != null,
                actionLabel = if (restartId != null) "Restart" else null,
                onAction = if (restartId != null) {
                    { viewModel.restartProfileForMount(restartId) }
                } else {
                    null
                },
                onDismiss = viewModel::clearBanners,
            )
        }

        if (state.writeLocked) {
            FrozenWorkspaceBanner(
                busy = state.busy || state.runState == dev.gfire.termvr.core.model.SandboxRunState.FREEZING,
                onUnlock = viewModel::discardCheckpointAndUnlock,
            )
        }

        val profile = state.selectedProfile
        val openSafEditor: (HostFileItem) -> Unit = { item ->
            if (profile != null) {
                val gitSpec = profile.gitWorktree
                val docId = if (gitSpec != null) {
                    WorkspaceDocumentsEngine.buildWorktreeDocumentId(
                        projectId = gitSpec.projectId,
                        profileId = profile.id,
                        relativePath = item.relativePath,
                    )
                } else {
                    "${WorkspaceDocumentsEngine.ENTRY_PREFIX}${profile.id}/${item.relativePath}"
                }
                val docUri = DocumentsContract.buildDocumentUri(TermVrDocumentsProvider.AUTHORITY, docId)
                val flags = if (state.writeLocked) {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val editIntent = Intent(if (state.writeLocked) Intent.ACTION_VIEW else Intent.ACTION_EDIT).apply {
                    setDataAndType(docUri, "*/*")
                    addFlags(flags)
                }
                runCatching { context.startActivity(editIntent) }.onFailure {
                    viewModel.shareWorkspaceFile(item)
                }
            }
        }

        if (isExpandedWidth) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WorkspacePane(
                    state = state,
                    onRefresh = viewModel::refreshWorkspace,
                    onUp = viewModel::navigateWorkspaceUp,
                    onOpenDir = { viewModel.navigateWorkspace(it.relativePath) },
                    onNewFolder = { showNewFolderFor = FileRootTab.WORKSPACE },
                    onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
                    onOpenSafEditor = openSafEditor,
                    onShareFile = viewModel::shareWorkspaceFile,
                    onDeleteItem = viewModel::deleteWorkspaceItem,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TermVrSelectableChip(
                            label = "Volumes (${state.volumes.size})",
                            selected = expandedRightTab != FileRootTab.GIT,
                            onClick = {
                                expandedRightTab = FileRootTab.VOLUME
                                viewModel.refreshVolumeList()
                            },
                        )
                        TermVrSelectableChip(
                            label = "Git Projects (${state.gitProjects.size})",
                            selected = expandedRightTab == FileRootTab.GIT,
                            onClick = {
                                expandedRightTab = FileRootTab.GIT
                                viewModel.refreshGitProjects()
                            },
                        )
                    }
                    if (expandedRightTab == FileRootTab.GIT) {
                        GitProjectsPane(
                            state = state,
                            onRefresh = viewModel::refreshGitProjects,
                            onNewProject = { showNewGitProjectDialog = true },
                            onOpenWorktree = viewModel::openWorktreeInWorkspace,
                            onDeleteProject = viewModel::deleteGitProject,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    } else {
                        VolumesPane(
                            state = state,
                            isExpandedWidth = true,
                            onRefreshList = viewModel::refreshVolumeList,
                            onNewVolume = { showNewVolumeDialog = true },
                            onMountVolume = {
                                mountDialogInitialMode = VolumeMountMode.EXCLUSIVE_RW
                                mountDialogVolume = it
                            },
                            onBranchVolume = {
                                mountDialogInitialMode = VolumeMountMode.COW_PERSISTENT
                                mountDialogVolume = it
                            },
                            onCompactVolume = { viewModel.compactNamedVolume(it.name) },
                            onUnmountFromSelected = { volName ->
                                profile?.let { viewModel.unmountVolumeFromProfile(volName, it.id) }
                            },
                            onBrowseVolume = { viewModel.browseVolume(it.name) },
                            onOpenWorkspace = viewModel::openWorkspaceForVolume,
                            onStopVolumeContainers = viewModel::stopContainersForVolume,
                            onUnlockFrozen = viewModel::discardCheckpointsForVolume,
                            onDeleteVolume = { viewModel.deleteNamedVolume(it.name) },
                            onCloseBrowser = viewModel::closeVolumeBrowser,
                            onRefreshFiles = viewModel::refreshVolume,
                            onUp = viewModel::navigateVolumeUp,
                            onOpenDir = { entry ->
                                val next = if (state.volumePath == "/") "/${entry.name}" else "${state.volumePath}/${entry.name}"
                                viewModel.navigateVolume(next)
                            },
                            onNewFolder = { showNewFolderFor = FileRootTab.VOLUME },
                            onShareEntry = viewModel::shareVolumeFile,
                            onDeleteEntry = viewModel::deleteVolumeEntry,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            TabRow(
                selectedTabIndex = state.selectedRootTab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outline) },
            ) {
                Tab(
                    selected = state.selectedRootTab == FileRootTab.WORKSPACE,
                    onClick = { viewModel.selectRootTab(FileRootTab.WORKSPACE) },
                    text = { Text("Workspace") },
                )
                Tab(
                    selected = state.selectedRootTab == FileRootTab.VOLUME,
                    onClick = { viewModel.selectRootTab(FileRootTab.VOLUME) },
                    text = { Text("Volumes") },
                )
                Tab(
                    selected = state.selectedRootTab == FileRootTab.GIT,
                    onClick = { viewModel.selectRootTab(FileRootTab.GIT) },
                    text = { Text("Git Projects") },
                )
            }
            when (state.selectedRootTab) {
                FileRootTab.WORKSPACE -> WorkspacePane(
                    state = state,
                    onRefresh = viewModel::refreshWorkspace,
                    onUp = viewModel::navigateWorkspaceUp,
                    onOpenDir = { viewModel.navigateWorkspace(it.relativePath) },
                    onNewFolder = { showNewFolderFor = FileRootTab.WORKSPACE },
                    onImportFile = { importFileLauncher.launch(arrayOf("*/*")) },
                    onOpenSafEditor = openSafEditor,
                    onShareFile = viewModel::shareWorkspaceFile,
                    onDeleteItem = viewModel::deleteWorkspaceItem,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                FileRootTab.VOLUME -> VolumesPane(
                    state = state,
                    isExpandedWidth = false,
                    onRefreshList = viewModel::refreshVolumeList,
                    onNewVolume = { showNewVolumeDialog = true },
                    onMountVolume = {
                        mountDialogInitialMode = VolumeMountMode.EXCLUSIVE_RW
                        mountDialogVolume = it
                    },
                    onBranchVolume = {
                        mountDialogInitialMode = VolumeMountMode.COW_PERSISTENT
                        mountDialogVolume = it
                    },
                    onCompactVolume = { viewModel.compactNamedVolume(it.name) },
                    onUnmountFromSelected = { volName ->
                        profile?.let { viewModel.unmountVolumeFromProfile(volName, it.id) }
                    },
                    onBrowseVolume = { viewModel.browseVolume(it.name) },
                    onOpenWorkspace = viewModel::openWorkspaceForVolume,
                    onStopVolumeContainers = viewModel::stopContainersForVolume,
                    onUnlockFrozen = viewModel::discardCheckpointsForVolume,
                    onDeleteVolume = { viewModel.deleteNamedVolume(it.name) },
                    onCloseBrowser = viewModel::closeVolumeBrowser,
                    onRefreshFiles = viewModel::refreshVolume,
                    onUp = viewModel::navigateVolumeUp,
                    onOpenDir = { entry ->
                        val next = if (state.volumePath == "/") "/${entry.name}" else "${state.volumePath}/${entry.name}"
                        viewModel.navigateVolume(next)
                    },
                    onNewFolder = { showNewFolderFor = FileRootTab.VOLUME },
                    onShareEntry = viewModel::shareVolumeFile,
                    onDeleteEntry = viewModel::deleteVolumeEntry,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                FileRootTab.GIT -> GitProjectsPane(
                    state = state,
                    onRefresh = viewModel::refreshGitProjects,
                    onNewProject = { showNewGitProjectDialog = true },
                    onOpenWorktree = viewModel::openWorktreeInWorkspace,
                    onDeleteProject = viewModel::deleteGitProject,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }

    val dialogTarget = showNewFolderFor
    if (dialogTarget != null) {
        NewFolderDialog(
            onDismiss = { showNewFolderFor = null },
            onCreate = { name ->
                if (dialogTarget == FileRootTab.WORKSPACE) {
                    viewModel.createWorkspaceDirectory(name)
                } else {
                    viewModel.createVolumeDirectory(name)
                }
                showNewFolderFor = null
            },
        )
    }

    if (showNewVolumeDialog) {
        NewVolumeDialog(
            onDismiss = { showNewVolumeDialog = false },
            onCreate = { name ->
                viewModel.createNamedVolume(name)
                showNewVolumeDialog = false
            },
        )
    }

    if (showNewGitProjectDialog) {
        NewGitProjectDialog(
            onDismiss = { showNewGitProjectDialog = false },
            onCreate = { name, defaultBranch ->
                viewModel.createGitProject(name = name, defaultBranch = defaultBranch)
                showNewGitProjectDialog = false
            },
        )
    }

    val targetVol = mountDialogVolume
    if (targetVol != null) {
        MountVolumeDialog(
            volume = targetVol,
            profiles = state.profiles,
            initialProfileId = state.selectedProfile?.id,
            initialMode = mountDialogInitialMode,
            onMount = { profileId, mountPath, mode, upperName ->
                viewModel.mountVolumeToProfile(
                    volumeName = targetVol.name,
                    profileId = profileId,
                    guestMountPath = mountPath,
                    mode = mode,
                    overlayUpperVolumeName = upperName,
                )
                mountDialogVolume = null
            },
            onUnmount = { profileId ->
                viewModel.unmountVolumeFromProfile(targetVol.name, profileId)
                mountDialogVolume = null
            },
            onDismiss = { mountDialogVolume = null },
        )
    }
}

@Composable
private fun FrozenWorkspaceBanner(
    busy: Boolean,
    onUnlock: () -> Unit,
) {
    TermVrCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.errorContainer,
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TermVrIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Locked while frozen",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Saved checkpoints verify workspace files on resume. Unlock to discard the snapshot and edit offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TermVrButton(
                onClick = onUnlock,
                enabled = !busy,
            ) {
                Text("Unlock")
            }
        }
    }
}

@Composable
private fun WorkspacePane(
    state: FilesUiState,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onOpenDir: (HostFileItem) -> Unit,
    onNewFolder: () -> Unit,
    onImportFile: () -> Unit,
    onOpenSafEditor: (HostFileItem) -> Unit,
    onShareFile: (HostFileItem) -> Unit,
    onDeleteItem: (HostFileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalTermVrMonoFontFamily.current
    if (state.selectedProfile == null) {
        TermVrCard(modifier = modifier.fillMaxWidth()) {
            Text(
                text = "Create a container to browse its workspace.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    val gitSpec = state.selectedProfile?.gitWorktree
    TermVrCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "/workspace/${state.workspacePath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onUp,
                        enabled = state.workspacePath.isNotEmpty(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(TermVrIcons.ArrowUp, contentDescription = "Up", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(TermVrIcons.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (gitSpec != null) {
                TermVrBadge(
                    text = "Git worktree: ${gitSpec.projectId} (${gitSpec.branchName})",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermVrOutlinedButton(
                    onClick = onNewFolder,
                    enabled = !state.writeLocked && !state.busy,
                ) {
                    Text("+ Folder")
                }
                TermVrOutlinedButton(
                    onClick = onImportFile,
                    enabled = !state.writeLocked && !state.busy,
                ) {
                    Text("Import")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (state.workspaceEntries.isEmpty()) {
                Text(
                    text = "Empty directory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.workspaceEntries, key = { it.relativePath }) { item ->
                        WorkspaceEntryRow(
                            item = item,
                            writeLocked = state.writeLocked,
                            onOpenDir = onOpenDir,
                            onOpenSafEditor = onOpenSafEditor,
                            onShareFile = onShareFile,
                            onDeleteItem = onDeleteItem,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEntryRow(
    item: HostFileItem,
    writeLocked: Boolean,
    onOpenDir: (HostFileItem) -> Unit,
    onOpenSafEditor: (HostFileItem) -> Unit,
    onShareFile: (HostFileItem) -> Unit,
    onDeleteItem: (HostFileItem) -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.isDirectory) { onOpenDir(item) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.isDirectory) TermVrIcons.Folder else TermVrIcons.FileDoc,
            contentDescription = null,
            tint = if (item.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.isDirectory) {
                Text(
                    text = formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.isDirectory) {
            IconButton(onClick = { onOpenDir(item) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    TermVrIcons.ChevronRight,
                    contentDescription = "Open",
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            IconButton(onClick = { onOpenSafEditor(item) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    TermVrIcons.Edit,
                    contentDescription = "Edit",
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = { onShareFile(item) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    TermVrIcons.Share,
                    contentDescription = "Share",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(
            onClick = { onDeleteItem(item) },
            enabled = !writeLocked,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                TermVrIcons.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumesPane(
    state: FilesUiState,
    isExpandedWidth: Boolean,
    onRefreshList: () -> Unit,
    onNewVolume: () -> Unit,
    onMountVolume: (ManagedVolumeItem) -> Unit,
    onBranchVolume: (ManagedVolumeItem) -> Unit,
    onCompactVolume: (ManagedVolumeItem) -> Unit,
    onUnmountFromSelected: (String) -> Unit,
    onBrowseVolume: (ManagedVolumeItem) -> Unit,
    onOpenWorkspace: (String) -> Unit,
    onStopVolumeContainers: (String) -> Unit,
    onUnlockFrozen: (String) -> Unit,
    onDeleteVolume: (ManagedVolumeItem) -> Unit,
    onCloseBrowser: () -> Unit,
    onRefreshFiles: () -> Unit,
    onUp: () -> Unit,
    onOpenDir: (VolumeDirEntry) -> Unit,
    onNewFolder: () -> Unit,
    onShareEntry: (VolumeDirEntry) -> Unit,
    onDeleteEntry: (VolumeDirEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedProfileId = state.selectedProfile?.id

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Named Volumes (${state.volumes.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onRefreshList, modifier = Modifier.size(32.dp)) {
                        Icon(TermVrIcons.Refresh, contentDescription = "Refresh volumes", modifier = Modifier.size(16.dp))
                    }
                    TermVrOutlinedButton(
                        onClick = onNewVolume,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("+ New Volume", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (state.volumes.isEmpty()) {
            item {
                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No volumes yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Create a persistent volume to share data across container restarts.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.volumes, key = { it.name }) { vol ->
                VolumeSummaryCard(
                    volume = vol,
                    selectedProfileId = selectedProfileId,
                    hasSelectedProfile = state.selectedProfile != null,
                    isExpandedWidth = isExpandedWidth,
                    busy = state.busy,
                    onMountVolume = { onMountVolume(vol) },
                    onBranchVolume = { onBranchVolume(vol) },
                    onCompactVolume = { onCompactVolume(vol) },
                    onUnmountFromSelected = { onUnmountFromSelected(vol.name) },
                    onBrowseVolume = { onBrowseVolume(vol) },
                    onOpenWorkspace = { onOpenWorkspace(vol.name) },
                    onStopContainers = { onStopVolumeContainers(vol.name) },
                    onDeleteVolume = { onDeleteVolume(vol) },
                )
            }
        }

        val activeVolume = state.selectedVolumeName
        if (activeVolume != null) {
            val activeVolumeItem = state.volumes.firstOrNull { it.name == activeVolume }
            item {
                VolumeBrowserCard(
                    volumeName = activeVolume,
                    volumeItem = activeVolumeItem,
                    state = state,
                    isExpandedWidth = isExpandedWidth,
                    onUp = onUp,
                    onRefreshFiles = onRefreshFiles,
                    onCloseBrowser = onCloseBrowser,
                    onNewFolder = onNewFolder,
                    onOpenWorkspace = { onOpenWorkspace(activeVolume) },
                    onStopContainers = { onStopVolumeContainers(activeVolume) },
                    onUnlockFrozen = { onUnlockFrozen(activeVolume) },
                    onOpenDir = onOpenDir,
                    onShareEntry = onShareEntry,
                    onDeleteEntry = onDeleteEntry,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeSummaryCard(
    volume: ManagedVolumeItem,
    selectedProfileId: String?,
    hasSelectedProfile: Boolean,
    isExpandedWidth: Boolean,
    busy: Boolean,
    onMountVolume: () -> Unit,
    onBranchVolume: () -> Unit,
    onCompactVolume: () -> Unit,
    onUnmountFromSelected: () -> Unit,
    onBrowseVolume: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onStopContainers: () -> Unit,
    onDeleteVolume: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val paletteTokens = LocalTermVrPaletteTokens.current
    val mountedOnSelected = selectedProfileId != null &&
        volume.mounts.any { it.profileId == selectedProfileId && it.guestMountPath != "/" }

    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = TermVrIcons.VolumeDisk,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = volume.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (volume.hasFrozenMount) {
                    TermVrBadge(
                        text = "Frozen",
                        dotColor = paletteTokens.statusFrozen,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        monospace = false,
                    )
                } else if (volume.lockMode == VolumeLockMode.EXCLUSIVE) {
                    TermVrBadge(
                        text = "Active (RW)",
                        dotColor = paletteTokens.statusRunning,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        monospace = false,
                    )
                } else if (volume.lockMode == VolumeLockMode.SHARED) {
                    TermVrBadge(
                        text = "Shared (RO)",
                        dotColor = paletteTokens.statusRunning,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        monospace = false,
                    )
                }
            }

            if (volume.sizeBytes > 0L || volume.allocatedBytes > 0L) {
                TermVrBadge(text = volume.diskUsageLabel)
            }

            if (volume.mounts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (mount in volume.mounts) {
                        val modeTag = when {
                            mount.isUpperOverlay -> " (CoW upper)"
                            mount.mountMode == VolumeMountMode.COW_PERSISTENT -> " (CoW base)"
                            mount.mountMode == VolumeMountMode.COW_EPHEMERAL -> " (CoW scratch)"
                            mount.mountMode == VolumeMountMode.SHARED_RO -> " (RO)"
                            else -> ""
                        }
                        TermVrBadge(
                            text = "${mount.profileDisplayName}:${mount.guestMountPath}$modeTag",
                            dotColor = if (mount.isRunning) runStateColor(mount.runState) else null,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            if (volume.hasFrozenMount || volume.lockMode != VolumeLockMode.NONE) {
                val lockNotice = when {
                    volume.hasFrozenMount -> FilesViewModel.FROZEN_VOLUME_LOCK_MESSAGE
                    volume.lockMode == VolumeLockMode.SHARED -> FilesViewModel.SHARED_READER_VOLUME_LOCK_MESSAGE
                    else -> FilesViewModel.SINGLE_WRITER_VOLUME_LOCK_MESSAGE
                }
                Text(
                    text = lockNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TermVrOutlinedButton(
                    onClick = onMountVolume,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Mount", style = MaterialTheme.typography.labelMedium)
                }
                if (volume.lockMode.allowsSharedReaders && !volume.hasFrozenMount) {
                    TermVrOutlinedButton(
                        onClick = onBranchVolume,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Branch Volume", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (volume.canCompact) {
                    TermVrOutlinedButton(
                        onClick = onCompactVolume,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Compact", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (mountedOnSelected) {
                    TermVrOutlinedButton(
                        onClick = onUnmountFromSelected,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Unmount", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TermVrOutlinedButton(
                    onClick = onBrowseVolume,
                    enabled = hasSelectedProfile,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Browse", style = MaterialTheme.typography.labelMedium)
                }
                if (volume.hasRunningMount && !isExpandedWidth) {
                    TermVrOutlinedButton(
                        onClick = onOpenWorkspace,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Workspace", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (volume.hasRunningMount) {
                    TermVrOutlinedButton(
                        onClick = onStopContainers,
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        val stopLabel = if (volume.activeProfileNames.size > 1) "Stop Containers" else "Stop Container"
                        Text(stopLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
                TermVrTextButton(
                    onClick = onDeleteVolume,
                    enabled = !volume.hasRunningMount && !volume.hasFrozenMount && volume.lockMode == VolumeLockMode.NONE && !busy,
                    contentColor = MaterialTheme.colorScheme.error,
                ) {
                    Text("Delete")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeBrowserCard(
    volumeName: String,
    volumeItem: ManagedVolumeItem?,
    state: FilesUiState,
    isExpandedWidth: Boolean,
    onUp: () -> Unit,
    onRefreshFiles: () -> Unit,
    onCloseBrowser: () -> Unit,
    onNewFolder: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onStopContainers: () -> Unit,
    onUnlockFrozen: () -> Unit,
    onOpenDir: (VolumeDirEntry) -> Unit,
    onShareEntry: (VolumeDirEntry) -> Unit,
    onDeleteEntry: (VolumeDirEntry) -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val pauseState = state.selectedVolumePause?.takeIf { it.volumeName == volumeName }

    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$volumeName:${state.volumePath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onUp,
                        enabled = state.volumePath != "/" && pauseState == null,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(TermVrIcons.ArrowUp, contentDescription = "Up", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRefreshFiles, modifier = Modifier.size(30.dp)) {
                        Icon(TermVrIcons.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onCloseBrowser, modifier = Modifier.size(30.dp)) {
                        Icon(TermVrIcons.Close, contentDescription = "Close browser", modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (pauseState != null) {
                val freezingAttached = pauseState.attachedProfileIds.any { id ->
                    state.runStates[id] == dev.gfire.termvr.core.model.SandboxRunState.FREEZING
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VolumeAccessPausedNotice(
                    pauseState = pauseState,
                    isExpandedWidth = isExpandedWidth,
                    freezingAttached = freezingAttached,
                    busy = state.busy,
                    onOpenWorkspace = onOpenWorkspace,
                    onStopContainers = onStopContainers,
                    onUnlockFrozen = onUnlockFrozen,
                )
            } else {
                if (volumeItem?.hasRunningMount == true) {
                    TermVrCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        borderColor = MaterialTheme.colorScheme.outline,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = FilesViewModel.SINGLE_WRITER_VOLUME_LOCK_MESSAGE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Use Workspace (/workspace) for external Android editor saves and streaming file imports.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!isExpandedWidth) {
                                TermVrTextButton(onClick = onOpenWorkspace) {
                                    Text("Open Workspace")
                                }
                            }
                        }
                    }
                }

                TermVrOutlinedButton(
                    onClick = onNewFolder,
                    enabled = !state.writeLocked && !state.busy,
                ) {
                    Text("+ Folder")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (state.volumeEntries.isEmpty()) {
                    Text(
                        text = "Empty directory",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    for (entry in state.volumeEntries) {
                        VolumeEntryRow(
                            entry = entry,
                            writeLocked = state.writeLocked,
                            onOpenDir = onOpenDir,
                            onShareEntry = onShareEntry,
                            onDeleteEntry = onDeleteEntry,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeAccessPausedNotice(
    pauseState: VolumeAccessPauseState,
    isExpandedWidth: Boolean,
    freezingAttached: Boolean,
    busy: Boolean,
    onOpenWorkspace: () -> Unit,
    onStopContainers: () -> Unit,
    onUnlockFrozen: () -> Unit,
) {
    val isFrozen = pauseState.kind == VolumePauseKind.FROZEN_CHECKPOINT_LOCK
    val containerSummary = pauseState.attachedProfileNames.joinToString(", ").ifEmpty {
        "the attached container"
    }
    val heading = if (isFrozen) {
        "Direct volume access paused while frozen"
    } else {
        "Direct volume access paused while active"
    }
    val guidance = if (isFrozen) {
        "Resume or stop $containerSummary, or discard its checkpoint before modifying this volume."
    } else {
        "Switch to Workspace (/workspace) to share files live, or stop $containerSummary to browse and edit this volume."
    }

    TermVrCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        borderColor = MaterialTheme.colorScheme.outline,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = TermVrIcons.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = pauseState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!isExpandedWidth || pauseState.attachedProfileIds.isNotEmpty()) {
                    TermVrButton(
                        onClick = onOpenWorkspace,
                        enabled = !busy,
                    ) {
                        Text("Open Workspace")
                    }
                }
                TermVrOutlinedButton(
                    onClick = onStopContainers,
                    enabled = !busy,
                ) {
                    val stopLabel = if (pauseState.attachedProfileIds.size > 1) {
                        "Stop Containers"
                    } else {
                        "Stop Container"
                    }
                    Text(stopLabel)
                }
                if (isFrozen) {
                    TermVrTextButton(
                        onClick = onUnlockFrozen,
                        enabled = !busy && !freezingAttached,
                    ) {
                        Text("Discard Checkpoint")
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeEntryRow(
    entry: VolumeDirEntry,
    writeLocked: Boolean,
    onOpenDir: (VolumeDirEntry) -> Unit,
    onShareEntry: (VolumeDirEntry) -> Unit,
    onDeleteEntry: (VolumeDirEntry) -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.isDir) { onOpenDir(entry) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDir) TermVrIcons.Folder else TermVrIcons.FileDoc,
            contentDescription = null,
            tint = if (entry.isDir) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDir) {
                Text(
                    text = formatBytes(entry.size),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entry.isDir) {
            IconButton(onClick = { onOpenDir(entry) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    TermVrIcons.ChevronRight,
                    contentDescription = "Open",
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            IconButton(onClick = { onShareEntry(entry) }, modifier = Modifier.size(30.dp)) {
                Icon(
                    TermVrIcons.Share,
                    contentDescription = "Share",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(
            onClick = { onDeleteEntry(entry) },
            enabled = !writeLocked,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                TermVrIcons.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = { Text("New Folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder Name") },
                singleLine = true,
                shape = TermVrControlShape,
            )
        },
        confirmButton = {
            TermVrButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun NewVolumeDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = { Text("New Volume") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Volume Name") },
                placeholder = { Text("e.g. data") },
                singleLine = true,
                shape = TermVrControlShape,
            )
        },
        confirmButton = {
            TermVrButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GitProjectsPane(
    state: FilesUiState,
    onRefresh: () -> Unit,
    onNewProject: () -> Unit,
    onOpenWorktree: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = LocalTermVrMonoFontFamily.current
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Shared Git Projects (${state.gitProjects.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(TermVrIcons.Refresh, contentDescription = "Refresh Git projects", modifier = Modifier.size(16.dp))
                    }
                    TermVrOutlinedButton(
                        onClick = onNewProject,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("+ New Project", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (state.gitProjects.isEmpty()) {
            item {
                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No shared Git projects yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Create a shared bare Git repository so multiple containers can work concurrently in isolated branches.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.gitProjects, key = { it.summary.id }) { item ->
                val summary = item.summary
                val hasAttachments = item.worktrees.isNotEmpty() || summary.worktreeCount > 0
                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = summary.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "/git/${summary.id}.git (${summary.defaultBranch})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TermVrBadge(text = formatBytes(summary.diskBytes))
                        }

                        if (item.worktrees.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (wt in item.worktrees) {
                                    TermVrOutlinedButton(
                                        onClick = { onOpenWorktree(wt.profileId) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "${wt.profileDisplayName}: ${wt.branchName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = mono,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No container worktrees attached.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TermVrTextButton(
                                onClick = { onDeleteProject(summary.id) },
                                enabled = !hasAttachments && !state.busy,
                                contentColor = MaterialTheme.colorScheme.error,
                            ) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewGitProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var defaultBranch by remember { mutableStateOf("main") }
    val validBranch = GitWorktreeSpec.isValidBranchName(defaultBranch.trim().ifEmpty { "main" })

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = { Text("New Shared Git Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    placeholder = { Text("e.g. Core Backend") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = defaultBranch,
                    onValueChange = { defaultBranch = it },
                    label = { Text("Default Branch") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TermVrButton(
                onClick = { onCreate(name, defaultBranch.trim().ifEmpty { "main" }) },
                enabled = name.isNotBlank() && validBranch,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MountVolumeDialog(
    volume: ManagedVolumeItem,
    profiles: List<SandboxProfile>,
    initialProfileId: String?,
    initialMode: VolumeMountMode = VolumeMountMode.EXCLUSIVE_RW,
    onMount: (String, String, VolumeMountMode, String?) -> Unit,
    onUnmount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedProfileId by remember {
        mutableStateOf(initialProfileId ?: profiles.firstOrNull()?.id)
    }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    var mountPath by remember { mutableStateOf("/data") }
    var mountMode by remember { mutableStateOf(initialMode) }
    var upperVolumeName by remember(volume.name, selectedProfileId) {
        val label = selectedProfile?.displayName ?: selectedProfileId ?: "sandbox"
        mutableStateOf(VolumeMountSpec.suggestOverlayUpperVolumeName(volume.name, label))
    }
    val selectedAttachment = volume.mounts.firstOrNull { it.profileId == selectedProfileId }
    val validUpper = mountMode != VolumeMountMode.COW_PERSISTENT ||
        (VolumeMountSpec.isValidVolumeName(upperVolumeName.trim()) && upperVolumeName.trim() != volume.name)

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = {
            Text(
                if (mountMode == VolumeMountMode.COW_PERSISTENT) {
                    "Branch ${volume.name}"
                } else {
                    "Mount ${volume.name}"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (profiles.isEmpty()) {
                    Text("No containers available.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (profile in profiles) {
                            TermVrSelectableChip(
                                label = profile.displayName,
                                selected = profile.id == selectedProfileId,
                                onClick = { selectedProfileId = profile.id },
                            )
                        }
                    }
                    Text("Mount Mode", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TermVrSelectableChip(
                            label = "Exclusive Read-Write",
                            selected = mountMode == VolumeMountMode.EXCLUSIVE_RW,
                            onClick = { mountMode = VolumeMountMode.EXCLUSIVE_RW },
                        )
                        TermVrSelectableChip(
                            label = "Shared Read-Only",
                            selected = mountMode == VolumeMountMode.SHARED_RO,
                            onClick = { mountMode = VolumeMountMode.SHARED_RO },
                        )
                        TermVrSelectableChip(
                            label = "CoW Branch (Persistent)",
                            selected = mountMode == VolumeMountMode.COW_PERSISTENT,
                            onClick = { mountMode = VolumeMountMode.COW_PERSISTENT },
                        )
                        TermVrSelectableChip(
                            label = "CoW Scratch (RAM)",
                            selected = mountMode == VolumeMountMode.COW_EPHEMERAL,
                            onClick = { mountMode = VolumeMountMode.COW_EPHEMERAL },
                        )
                    }
                    if (mountMode == VolumeMountMode.COW_PERSISTENT) {
                        OutlinedTextField(
                            value = upperVolumeName,
                            onValueChange = { upperVolumeName = it },
                            label = { Text("Persistent Upper Volume") },
                            singleLine = true,
                            shape = TermVrControlShape,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = mountPath,
                        onValueChange = { mountPath = it },
                        label = { Text("Guest Path") },
                        singleLine = true,
                        shape = TermVrControlShape,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val pid = selectedProfileId
            if (pid != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (selectedAttachment != null) {
                        TermVrOutlinedButton(onClick = { onUnmount(pid) }) {
                            Text("Unmount")
                        }
                    }
                    TermVrButton(
                        onClick = {
                            onMount(
                                pid,
                                mountPath,
                                mountMode,
                                if (mountMode == VolumeMountMode.COW_PERSISTENT) upperVolumeName.trim() else null,
                            )
                        },
                        enabled = mountPath.startsWith("/") && validUpper,
                    ) {
                        Text(if (mountMode == VolumeMountMode.COW_PERSISTENT) "Branch & Mount" else "Mount")
                    }
                }
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
