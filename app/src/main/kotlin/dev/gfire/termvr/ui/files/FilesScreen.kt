package dev.gfire.termvr.ui.files

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.provider.TermVrDocumentsProvider
import dev.gfire.termvr.provider.WorkspaceDocumentsEngine
import dev.gfire.termvr.ui.home.formatBytes

/**
 * Renders the adaptive `/workspace` and `ext4lite` volume browser with checkpoint write locks.
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
    var showNewFolderFor by remember { mutableStateOf<FileRootTab?>(null) }

    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@rememberLauncherForActivityResult
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported-file"
                viewModel.importWorkspaceFile(name, bytes)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sandbox Files", style = MaterialTheme.typography.headlineSmall)

        if (state.profiles.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (profile in state.profiles) {
                    FilterChip(
                        selected = profile.id == state.selectedProfile?.id,
                        onClick = { viewModel.selectProfile(profile.id) },
                        label = { Text(profile.displayName) },
                    )
                }
            }
        }

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.errorMessage != null) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(banner, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::clearBanners) { Text("Dismiss") }
                }
            }
        }

        if (state.writeLocked) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Workspace Write-Locked (Checkpoint Active)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Editing workspace or volume files while frozen breaks checkpoint consistency.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = viewModel::discardCheckpointAndUnlock,
                        enabled = !state.busy,
                    ) {
                        Text("Discard Checkpoint & Unlock Workspace")
                    }
                }
            }
        }

        if (state.selectedProfile == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("Select or create a sandbox profile on Home.", modifier = Modifier.padding(16.dp))
            }
            return@Column
        }

        val profile = state.selectedProfile!!
        val openSafEditor: (HostFileItem) -> Unit = { item ->
            val docId = "${WorkspaceDocumentsEngine.ENTRY_PREFIX}${profile.id}/${item.relativePath}"
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

        if (isExpandedWidth) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                VolumePane(
                    state = state,
                    onSelectVolume = viewModel::selectVolumeName,
                    onRefresh = viewModel::refreshVolume,
                    onUp = viewModel::navigateVolumeUp,
                    onOpenDir = { entry ->
                        val next = if (state.volumePath == "/") "/${entry.name}" else "${state.volumePath}/${entry.name}"
                        viewModel.navigateVolume(next)
                    },
                    onNewFolder = { showNewFolderFor = FileRootTab.VOLUME },
                    onShareEntry = viewModel::shareVolumeFile,
                    onDeleteEntry = viewModel::deleteVolumeEntry,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            TabRow(selectedTabIndex = state.selectedRootTab.ordinal) {
                Tab(
                    selected = state.selectedRootTab == FileRootTab.WORKSPACE,
                    onClick = { viewModel.selectRootTab(FileRootTab.WORKSPACE) },
                    text = { Text("/workspace") },
                )
                Tab(
                    selected = state.selectedRootTab == FileRootTab.VOLUME,
                    onClick = { viewModel.selectRootTab(FileRootTab.VOLUME) },
                    text = { Text("ext4lite Volumes") },
                )
            }
            if (state.selectedRootTab == FileRootTab.WORKSPACE) {
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
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                VolumePane(
                    state = state,
                    onSelectVolume = viewModel::selectVolumeName,
                    onRefresh = viewModel::refreshVolume,
                    onUp = viewModel::navigateVolumeUp,
                    onOpenDir = { entry ->
                        val next = if (state.volumePath == "/") "/${entry.name}" else "${state.volumePath}/${entry.name}"
                        viewModel.navigateVolume(next)
                    },
                    onNewFolder = { showNewFolderFor = FileRootTab.VOLUME },
                    onShareEntry = viewModel::shareVolumeFile,
                    onDeleteEntry = viewModel::deleteVolumeEntry,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
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
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "/workspace/${state.workspacePath}",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onUp, enabled = state.workspacePath.isNotEmpty()) { Text("Up") }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                OutlinedButton(onClick = onNewFolder, enabled = !state.writeLocked) { Text("+ Dir") }
                OutlinedButton(onClick = onImportFile, enabled = !state.writeLocked) { Text("Import") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.workspaceEntries, key = { it.relativePath }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (item.isDirectory) "[dir] ${item.name}" else item.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!item.isDirectory) {
                                Text(
                                    text = formatBytes(item.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row {
                            if (item.isDirectory) {
                                TextButton(onClick = { onOpenDir(item) }) { Text("Open") }
                            } else {
                                TextButton(onClick = { onOpenSafEditor(item) }) { Text("Edit") }
                                TextButton(onClick = { onShareFile(item) }) { Text("Share") }
                            }
                            TextButton(
                                onClick = { onDeleteItem(item) },
                                enabled = !state.writeLocked,
                            ) {
                                Text("Del")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumePane(
    state: FilesUiState,
    onSelectVolume: (String) -> Unit,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onOpenDir: (dev.gfire.termvr.core.runtime.VolumeDirEntry) -> Unit,
    onNewFolder: () -> Unit,
    onShareEntry: (dev.gfire.termvr.core.runtime.VolumeDirEntry) -> Unit,
    onDeleteEntry: (dev.gfire.termvr.core.runtime.VolumeDirEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val volumes = state.selectedProfile?.volumes
        ?.filter { it.kind == VolumeKind.NAMED_EXT4LITE }
        ?.map { it.sourceName }
        .orEmpty()
        .ifEmpty { listOf("data") }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (vol in volumes) {
                    FilterChip(
                        selected = vol == state.selectedVolumeName,
                        onClick = { onSelectVolume(vol) },
                        label = { Text(vol) },
                    )
                }
            }
            Text(text = "ext4lite:${state.selectedVolumeName} ${state.volumePath}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onUp, enabled = state.volumePath != "/") { Text("Up") }
                OutlinedButton(onClick = onRefresh) { Text("Load") }
                OutlinedButton(onClick = onNewFolder, enabled = !state.writeLocked) { Text("+ Dir") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.volumeEntries, key = { it.name }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.isDir) "[dir] ${entry.name}" else entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!entry.isDir) {
                                Text(
                                    text = formatBytes(entry.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row {
                            if (entry.isDir) {
                                TextButton(onClick = { onOpenDir(entry) }) { Text("Open") }
                            } else {
                                TextButton(onClick = { onShareEntry(entry) }) { Text("Share") }
                            }
                            TextButton(
                                onClick = { onDeleteEntry(entry) },
                                enabled = !state.writeLocked,
                            ) {
                                Text("Del")
                            }
                        }
                    }
                }
            }
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
        title = { Text("Create Folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
