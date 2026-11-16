package dev.gfire.termvr.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.ui.theme.LocalTermVrMonoFontFamily
import dev.gfire.termvr.ui.theme.LocalTermVrPaletteTokens
import dev.gfire.termvr.ui.theme.TermVrBadge
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrCard
import dev.gfire.termvr.ui.theme.TermVrControlShape
import dev.gfire.termvr.ui.theme.TermVrIcons
import dev.gfire.termvr.ui.theme.TermVrNoticeBanner
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrRunStateDot
import dev.gfire.termvr.ui.theme.TermVrSelectableChip
import dev.gfire.termvr.ui.theme.TermVrStatusDot
import dev.gfire.termvr.ui.theme.TermVrTextButton
import dev.gfire.termvr.ui.theme.runStateColor
import dev.gfire.termvr.ui.theme.runStateLabel
import dev.gfire.termvr.ui.theme.shortImageRef
import kotlin.math.roundToInt

/**
 * Renders the Containers dashboard for starting, shelling into, freezing, and mounting containers.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTerminal: () -> Unit,
    onOpenSystemDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
    var volumesTargetProfileId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Containers",
                    style = MaterialTheme.typography.headlineSmall,
                )
                TermVrOutlinedButton(
                    onClick = { showCreateDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = TermVrIcons.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("New Container")
                }
            }
        }

        if (state.hasReadinessWarning) {
            item {
                val warningText = if (state.hostReadiness.phantomKillerDetected) {
                    "Background process restriction detected."
                } else {
                    "Child process count is near the OS limit."
                }
                TermVrNoticeBanner(
                    message = warningText,
                    isError = true,
                    actionLabel = "System",
                    onAction = onOpenSystemDiagnostics,
                )
            }
        }

        val bannerText = state.errorMessage ?: state.serviceNotice ?: state.statusMessage
        if (bannerText != null) {
            item {
                TermVrNoticeBanner(
                    message = bannerText,
                    isError = state.errorMessage != null,
                    onDismiss = viewModel::clearNotice,
                )
            }
        }

        if (state.runningCount >= 1) {
            item {
                RunningStatusStrip(
                    runningCount = state.runningCount,
                    onFreezeAll = viewModel::freezeAllRunning,
                    onStopAll = viewModel::stopAllRunning,
                )
            }
        }

        if (state.profiles.none { it.id == HomeViewModel.QUICK_DEBIAN_PROFILE_ID }) {
            item {
                QuickDebianCard(
                    busy = HomeViewModel.QUICK_DEBIAN_PROFILE_ID in state.busyProfileIds,
                    onLaunch = { viewModel.launchQuickDebian(onOpenTerminal) },
                )
            }
        }

        if (state.profiles.isEmpty()) {
            item {
                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "No saved containers",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Launch Debian above or create a custom container.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.profiles, key = { it.id }) { profile ->
                val runState = state.runStates[profile.id] ?: SandboxRunState.STOPPED
                val telemetry = state.telemetry[profile.id]
                ContainerCard(
                    profile = profile,
                    runState = runState,
                    telemetry = telemetry,
                    busy = profile.id in state.busyProfileIds,
                    onOpenOrStart = { viewModel.startOrOpenProfile(profile, onOpenTerminal = onOpenTerminal) },
                    onOpenNewShell = { viewModel.openNewShellForProfile(profile, onOpenTerminal) },
                    onFreeze = { viewModel.freezeProfile(profile.id) },
                    onStop = { viewModel.stopProfile(profile.id) },
                    onOpenVolumes = {
                        viewModel.refreshAvailableVolumes()
                        volumesTargetProfileId = profile.id
                    },
                    onDelete = { viewModel.deleteProfile(profile.id) },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateContainerDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, image, memMiB, cpu, muxMode, persistentRootfs ->
                viewModel.createProfile(name, image, memMiB, cpu, muxMode, persistentRootfs)
                showCreateDialog = false
            },
        )
    }

    val targetProfile = volumesTargetProfileId?.let { id ->
        state.profiles.firstOrNull { it.id == id }
    }
    if (targetProfile != null) {
        ContainerVolumesDialog(
            profile = targetProfile,
            isRunning = state.runStates[targetProfile.id] == SandboxRunState.RUNNING,
            availableVolumes = state.availableVolumes,
            onAttach = { volumeName, guestPath ->
                viewModel.attachVolumeToProfile(targetProfile.id, volumeName, guestPath)
            },
            onDetach = { volumeName ->
                viewModel.detachVolumeFromProfile(targetProfile.id, volumeName)
            },
            onDismiss = { volumesTargetProfileId = null },
        )
    }
}

@Composable
private fun RunningStatusStrip(
    runningCount: Int,
    onFreezeAll: () -> Unit,
    onStopAll: () -> Unit,
) {
    val tokens = LocalTermVrPaletteTokens.current
    TermVrCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TermVrStatusDot(color = tokens.statusRunning, size = 8.dp)
                Text(
                    text = if (runningCount == 1) "1 running" else "$runningCount running",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = LocalTermVrMonoFontFamily.current,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermVrOutlinedButton(
                    onClick = onFreezeAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Freeze All", style = MaterialTheme.typography.labelMedium)
                }
                TermVrOutlinedButton(
                    onClick = onStopAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Stop All", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun QuickDebianCard(
    busy: Boolean,
    onLaunch: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    TermVrCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Debian",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TermVrBadge(
                        text = "Default",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                }
                Text(
                    text = "debian:trixie-slim",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TermVrButton(
                onClick = onLaunch,
                enabled = !busy,
            ) {
                Icon(
                    imageVector = TermVrIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Starting..." else "Launch")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerCard(
    profile: SandboxProfile,
    runState: SandboxRunState,
    telemetry: SandboxTelemetrySnapshot?,
    busy: Boolean,
    onOpenOrStart: () -> Unit,
    onOpenNewShell: () -> Unit,
    onFreeze: () -> Unit,
    onStop: () -> Unit,
    onOpenVolumes: () -> Unit,
    onDelete: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val stateColor = runStateColor(runState)
    val namedVolumes = profile.volumes.filter { it.kind == VolumeKind.NAMED_EXT4LITE }

    TermVrCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TermVrRunStateDot(runState = runState, size = 8.dp)
                        Text(
                            text = profile.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = shortImageRef(profile.imageRef),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                TermVrBadge(
                    text = runStateLabel(runState),
                    dotColor = stateColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (profile.persistentRootfs || namedVolumes.isNotEmpty() || (runState == SandboxRunState.RUNNING && telemetry != null)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (profile.persistentRootfs) {
                        TermVrBadge(
                            text = "Persistent",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        )
                    }
                    if (runState == SandboxRunState.RUNNING && telemetry != null) {
                        val metricsText = buildString {
                            append("CPU ${telemetry.cpuPercent.roundToInt()}% · ${telemetry.pssMiB} MB")
                            if (telemetry.guestListenPorts.isNotEmpty()) {
                                val count = telemetry.guestListenPorts.size
                                append(" · $count port${if (count == 1) "" else "s"}")
                            }
                        }
                        TermVrBadge(
                            text = metricsText,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f),
                        )
                    }
                    for (vol in namedVolumes) {
                        TermVrBadge(text = "${vol.sourceName} -> ${vol.guestMountPath}")
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val primaryLabel = when (runState) {
                    SandboxRunState.RUNNING -> "Open"
                    SandboxRunState.FROZEN -> "Resume"
                    else -> "Start"
                }
                TermVrButton(
                    onClick = onOpenOrStart,
                    enabled = !busy && runState != SandboxRunState.FREEZING && runState != SandboxRunState.RESTORING,
                ) {
                    Text(if (busy) "Working..." else primaryLabel)
                }

                if (runState == SandboxRunState.RUNNING) {
                    TermVrOutlinedButton(onClick = onOpenNewShell, enabled = !busy) {
                        Icon(
                            imageVector = TermVrIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Shell")
                    }
                    TermVrOutlinedButton(onClick = onFreeze, enabled = !busy) {
                        Icon(
                            imageVector = TermVrIcons.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Freeze")
                    }
                }

                if (runState != SandboxRunState.STOPPED) {
                    TermVrOutlinedButton(onClick = onStop, enabled = !busy) {
                        Icon(
                            imageVector = TermVrIcons.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                }

                TermVrOutlinedButton(onClick = onOpenVolumes, enabled = !busy) {
                    Icon(
                        imageVector = TermVrIcons.VolumeDisk,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Volumes")
                }

                if (runState == SandboxRunState.STOPPED) {
                    TermVrTextButton(
                        onClick = onDelete,
                        enabled = !busy,
                        contentColor = MaterialTheme.colorScheme.error,
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateContainerDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Int, Double, MuxMode, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("Debian") }
    var imageRef by remember { mutableStateOf(HomeViewModel.DEFAULT_DEBIAN_IMAGE_REF) }
    var showLimits by remember { mutableStateOf(false) }
    var memoryText by remember { mutableStateOf("") }
    var cpuText by remember { mutableStateOf("") }
    var muxMode by remember { mutableStateOf(MuxMode.STDIO_MUX) }
    var persistentRootfs by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = { Text("New Container") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Container Name") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = imageRef,
                    onValueChange = { imageRef = it },
                    label = { Text("Image") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Shell Mode", style = MaterialTheme.typography.labelMedium)
                    TermVrSelectableChip(
                        label = "Mux Tabs",
                        selected = muxMode == MuxMode.STDIO_MUX,
                        onClick = { muxMode = MuxMode.STDIO_MUX },
                    )
                    TermVrSelectableChip(
                        label = "Raw PTY",
                        selected = muxMode == MuxMode.RAW_PTY,
                        onClick = { muxMode = MuxMode.RAW_PTY },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { persistentRootfs = !persistentRootfs },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = persistentRootfs,
                        onCheckedChange = { persistentRootfs = it },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Persistent root (ext4lite)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Keep installed packages and system files across restarts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLimits = !showLimits }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Resource limits (optional)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (showLimits) "Hide" else "Unlimited",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (showLimits) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = memoryText,
                            onValueChange = { memoryText = it },
                            label = { Text("RAM MiB") },
                            placeholder = { Text("Unlimited") },
                            singleLine = true,
                            shape = TermVrControlShape,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = cpuText,
                            onValueChange = { cpuText = it },
                            label = { Text("CPU Cores") },
                            placeholder = { Text("Unlimited") },
                            singleLine = true,
                            shape = TermVrControlShape,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TermVrButton(onClick = {
                val mem = if (showLimits) {
                    memoryText.trim().toIntOrNull() ?: SandboxProfile.UNLIMITED_MEMORY_MIB
                } else {
                    SandboxProfile.UNLIMITED_MEMORY_MIB
                }
                val cpu = if (showLimits) {
                    cpuText.trim().toDoubleOrNull() ?: SandboxProfile.UNLIMITED_CPU_CORES
                } else {
                    SandboxProfile.UNLIMITED_CPU_CORES
                }
                onCreate(name, imageRef, mem, cpu, muxMode, persistentRootfs)
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContainerVolumesDialog(
    profile: SandboxProfile,
    isRunning: Boolean,
    availableVolumes: List<String>,
    onAttach: (String, String) -> Unit,
    onDetach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val mounted = profile.volumes.filter { it.kind == VolumeKind.NAMED_EXT4LITE }
    var volumeName by remember { mutableStateOf("data") }
    var guestPath by remember { mutableStateOf("/data") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TermVrControlShape,
        title = { Text("${profile.displayName} Volumes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isRunning) {
                    Text(
                        text = "Mount changes apply after container restart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (mounted.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (vol in mounted) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${vol.sourceName} -> ${vol.guestMountPath}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = mono,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { onDetach(vol.sourceName) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = TermVrIcons.Close,
                                        contentDescription = "Detach ${vol.sourceName}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                if (availableVolumes.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (vol in availableVolumes) {
                            TermVrSelectableChip(
                                label = vol,
                                selected = volumeName == vol,
                                onClick = { volumeName = vol },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = volumeName,
                    onValueChange = { volumeName = it },
                    label = { Text("Volume Name") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPath,
                    onValueChange = { guestPath = it },
                    label = { Text("Mount Path") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TermVrButton(
                onClick = { onAttach(volumeName, guestPath) },
                enabled = volumeName.isNotBlank() && guestPath.startsWith("/"),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text("Mount")
            }
        },
        dismissButton = {
            TermVrTextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return "${kib.roundToInt()} KB"
    val mib = kib / 1024.0
    if (mib < 1024.0) return "${mib.roundToInt()} MB"
    val gib = mib / 1024.0
    return String.format("%.2f GB", gib)
}
