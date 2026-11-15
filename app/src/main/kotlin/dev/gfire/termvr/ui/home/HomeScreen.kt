package dev.gfire.termvr.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import kotlin.math.roundToInt

/**
 * Renders the Home sandbox dashboard, Quick Shell tile, and collapsible Host Readiness Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "TermVR Sandboxes",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "Host ABI ${state.hostReadiness.hostAbi} / Guest ${state.hostReadiness.guestArch?.ociArch ?: "unsupported"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { showCreateDialog = true }) {
                    Text("New Profile")
                }
            }
        }

        if (state.serviceNotice != null) {
            item {
                NoticeBannerCard(
                    title = "Sandbox Notice",
                    message = state.serviceNotice.orEmpty(),
                    onDismiss = viewModel::clearNotice,
                )
            }
        }

        if (state.errorMessage != null) {
            item {
                NoticeBannerCard(
                    title = "Runtime Error",
                    message = state.errorMessage.orEmpty(),
                    isError = true,
                    onDismiss = viewModel::clearNotice,
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Quick Shell (Alpine)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Launch an isolated Alpine Linux 3.21 container with multiplexed PTY tabs and /workspace storage.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { viewModel.launchQuickAlpine(onOpenTerminal) },
                        enabled = state.busyProfileId == null,
                    ) {
                        Text(
                            if (state.busyProfileId == HomeViewModel.QUICK_ALPINE_PROFILE_ID) {
                                "Starting Alpine..."
                            } else {
                                "Open Quick Shell"
                            },
                        )
                    }
                }
            }
        }

        item {
            HostReadinessCard(
                report = state.hostReadiness,
                expanded = state.readinessExpanded,
                adbCommand = state.recommendedAdbCommand,
                onToggleExpanded = viewModel::toggleReadinessExpanded,
                onRefresh = viewModel::refreshHostReadiness,
                onOpenDeveloperSettings = {
                    val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(devIntent) }.onFailure {
                        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(fallback) }
                    }
                },
                onCopyAdbCommand = { cmd ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TermVR ADB Command", cmd))
                },
            )
        }

        item {
            Text(
                text = "Configured Profiles (${state.profiles.size})",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (state.profiles.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No saved profiles yet. Launch Quick Shell or add a distribution from Catalog.",
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
                ProfileCard(
                    profile = profile,
                    runState = runState,
                    telemetry = telemetry,
                    busy = state.busyProfileId == profile.id,
                    onOpenOrStart = { viewModel.startOrOpenProfile(profile, onOpenTerminal = onOpenTerminal) },
                    onFreeze = { viewModel.freezeProfile(profile.id) },
                    onStop = { viewModel.stopProfile(profile.id) },
                    onDelete = { viewModel.deleteProfile(profile.id) },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateProfileDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, image, memMiB, cpu, muxMode ->
                viewModel.createProfile(name, image, memMiB, cpu, muxMode)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun NoticeBannerCard(
    title: String,
    message: String,
    isError: Boolean = false,
    onDismiss: () -> Unit,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostReadinessCard(
    report: HostReadinessReport,
    expanded: Boolean,
    adbCommand: String,
    onToggleExpanded: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    onCopyAdbCommand: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Host Readiness",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (report.phantomKillerDetected) {
                            "Phantom process termination detected"
                        } else if (report.exceedsRecommendedProcessBudget) {
                            "Child process count is near OS ceiling (${report.liveChildProcessCount} > 12)"
                        } else {
                            "Healthy (${report.liveChildProcessCount} child procs)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.phantomKillerDetected || report.exceedsRecommendedProcessBudget) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = onToggleExpanded) {
                    Text(if (expanded) "Hide" else "Inspect")
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val pageLabel = if (report.is16KbPageKernel) "16 KB pages" else "${report.kernelPageSizeBytes / 1024} KB pages"
                AssistChip(onClick = onToggleExpanded, label = { Text(pageLabel) })
                AssistChip(
                    onClick = onToggleExpanded,
                    label = { Text("Child procs: ${report.liveChildProcessCount} / 32") },
                )
                AssistChip(
                    onClick = onToggleExpanded,
                    label = { Text("Free disk: ${report.freeDiskMiB} MiB") },
                )
            }

            if (expanded) {
                HorizontalDivider()
                if (report.phantomKillerDetected || report.remediationKind != PhantomProcessRemediation.NONE) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Android Phantom Process Remediation",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            when (report.remediationKind) {
                                PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE -> {
                                    Text(
                                        text = "Open Android Developer Options and enable \"Disable child process restrictions\" to stop background sandbox kills.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(onClick = onOpenDeveloperSettings) {
                                        Text("Open Developer Options")
                                    }
                                }

                                PhantomProcessRemediation.ANDROID_12_13_ADB_COMMAND -> {
                                    Text(
                                        text = "Run this ADB command from a host computer once to disable phantom process termination on Android 12 or 13:",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = adbCommand,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Button(onClick = { onCopyAdbCommand(adbCommand) }) {
                                        Text("Copy adb command")
                                    }
                                }

                                PhantomProcessRemediation.NONE -> {
                                    Text(
                                        text = "Excessive resource SIGKILL detected (exit reason ${report.lastKilledExitReasonCode}). Freeze unused sandboxes to lower memory pressure.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                val usage = report.diskUsage
                Text(
                    text = "Storage Breakdown (Total ${formatBytes(usage.totalBytes)})",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "layers: ${formatBytes(usage.layersBytes)}  volumes: ${formatBytes(usage.volumesBytes)}\n" +
                        "workspaces: ${formatBytes(usage.workspacesBytes)}  checkpoints: ${formatBytes(usage.checkpointsBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onRefresh) {
                        Text("Refresh Checks")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    profile: SandboxProfile,
    runState: SandboxRunState,
    telemetry: SandboxTelemetrySnapshot?,
    busy: Boolean,
    onOpenOrStart: () -> Unit,
    onFreeze: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = profile.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${profile.imageRef} (${profile.memoryMiB} MiB, ${profile.cpuCores} CPU)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(runState.name) })
            }

            if (telemetry != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(onClick = {}, label = { Text("CPU ${telemetry.cpuPercent.roundToInt()}%") })
                    AssistChip(onClick = {}, label = { Text("${telemetry.pssMiB} MiB PSS") })
                    if (telemetry.guestListenPorts.isNotEmpty()) {
                        AssistChip(onClick = {}, label = { Text("${telemetry.guestListenPorts.size} ports") })
                    }
                    if (telemetry.exposesLan) {
                        AssistChip(onClick = {}, label = { Text("LAN 0.0.0.0") })
                    }
                    telemetry.activeProxyLabel?.let { label ->
                        AssistChip(onClick = {}, label = { Text("Proxy: $label") })
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val primaryLabel = when (runState) {
                    SandboxRunState.RUNNING -> "Open"
                    SandboxRunState.FROZEN -> "Resume"
                    else -> "Start"
                }
                Button(
                    onClick = onOpenOrStart,
                    enabled = !busy && runState != SandboxRunState.FREEZING && runState != SandboxRunState.RESTORING,
                ) {
                    Text(if (busy) "Working..." else primaryLabel)
                }
                if (runState == SandboxRunState.RUNNING) {
                    OutlinedButton(onClick = onFreeze, enabled = !busy) {
                        Text("Freeze")
                    }
                }
                if (runState != SandboxRunState.STOPPED) {
                    OutlinedButton(onClick = onStop, enabled = !busy) {
                        Text("Stop")
                    }
                } else {
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateProfileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Int, Double, MuxMode) -> Unit,
) {
    var name by remember { mutableStateOf("Dev Shell") }
    var imageRef by remember { mutableStateOf("alpine:3.21") }
    var memoryText by remember { mutableStateOf("1024") }
    var cpuText by remember { mutableStateOf("2.0") }
    var muxMode by remember { mutableStateOf(MuxMode.STDIO_MUX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Sandbox Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = imageRef,
                    onValueChange = { imageRef = it },
                    label = { Text("OCI Image Reference") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = memoryText,
                        onValueChange = { memoryText = it },
                        label = { Text("Memory MiB") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cpuText,
                        onValueChange = { cpuText = it },
                        label = { Text("CPU Cores") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("PTY Mode:", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = {
                        muxMode = if (muxMode == MuxMode.STDIO_MUX) MuxMode.RAW_PTY else MuxMode.STDIO_MUX
                    }) {
                        Text(muxMode.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val mem = memoryText.toIntOrNull() ?: 1024
                val cpu = cpuText.toDoubleOrNull() ?: 2.0
                onCreate(name, imageRef, mem, cpu, muxMode)
            }) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
