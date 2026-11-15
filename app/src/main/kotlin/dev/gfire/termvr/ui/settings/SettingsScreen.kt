package dev.gfire.termvr.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.KeySlotSpec
import dev.gfire.termvr.core.model.ModifierKey
import kotlin.math.roundToInt

/**
 * Renders Typography, Palettes, 2 x 7 Key Bar slot customization, and Runtime defaults.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expectedSha256 by remember { mutableStateOf("") }
    var editingSlot by remember { mutableStateOf<SlotEditTarget?>(null) }

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    viewModel.importCustomFont(input, expectedSha256)
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            item {
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
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Terminal Typography", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("jetbrains-mono", "noto-sans-mono-cjk", "dejavu-sans-mono").forEach { family ->
                            FilterChip(
                                selected = state.fontConfig.familyId == family,
                                onClick = { viewModel.updateFontFamily(family) },
                                label = { Text(family) },
                            )
                        }
                    }
                    state.fontConfig.customFontSha256?.let { sha ->
                        Text(
                            text = "Custom font SHA-256: $sha",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = expectedSha256,
                        onValueChange = { expectedSha256 = it },
                        label = { Text("Expected Font SHA-256 (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) {
                        Text("Import Custom .ttf / .otf")
                    }

                    Text("Font Size: ${state.fontConfig.sizeSp.roundToInt()} sp")
                    Slider(
                        value = state.fontConfig.sizeSp,
                        onValueChange = { viewModel.updateFontMetrics(it, state.fontConfig.cellHeightMultiplier) },
                        valueRange = 8f..32f,
                    )

                    Text("Line Height Multiplier: ${String.format("%.2f", state.fontConfig.cellHeightMultiplier)}x")
                    Slider(
                        value = state.fontConfig.cellHeightMultiplier,
                        onValueChange = { viewModel.updateFontMetrics(state.fontConfig.sizeSp, it) },
                        valueRange = 0.90f..1.80f,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.fontConfig.ligaturesEnabled,
                            onCheckedChange = {
                                viewModel.updateFontFeatures(it, state.fontConfig.nerdFontSymbolsEnabled)
                            },
                        )
                        Text("Enable Programming Ligatures")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.fontConfig.nerdFontSymbolsEnabled,
                            onCheckedChange = {
                                viewModel.updateFontFeatures(state.fontConfig.ligaturesEnabled, it)
                            },
                        )
                        Text("Enable Nerd Font Symbols")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Color Palettes", style = MaterialTheme.typography.titleMedium)
                    state.palettes.forEach { palette ->
                        val contrast = ColorPalette.contrastRatio(palette.foregroundRgba, palette.backgroundRgba)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = state.appSettings.selectedPaletteId == palette.id,
                                onClick = { viewModel.selectPalette(palette.id) },
                                label = { Text(palette.displayName) },
                            )
                            Text(
                                text = "Contrast ${String.format("%.1f", contrast)}:1",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
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
                        Text("Auxiliary Key Bar (2 x 7)", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = viewModel::resetKeyBarDefaults) { Text("Reset") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.keyBarConfig.visible,
                            onCheckedChange = { viewModel.updateKeyBarSettings(it, state.keyBarConfig.hapticFeedback) },
                        )
                        Text("Show Key Bar")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.keyBarConfig.hapticFeedback,
                            onCheckedChange = { viewModel.updateKeyBarSettings(state.keyBarConfig.visible, it) },
                        )
                        Text("Haptic Feedback")
                    }

                    Text("Top Row", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.keyBarConfig.topRow.forEachIndexed { idx, slot ->
                            OutlinedButton(onClick = { editingSlot = SlotEditTarget(true, idx, slot) }) {
                                Text(slotLabel(slot))
                            }
                        }
                    }
                    Text("Bottom Row", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.keyBarConfig.bottomRow.forEachIndexed { idx, slot ->
                            OutlinedButton(onClick = { editingSlot = SlotEditTarget(false, idx, slot) }) {
                                Text(slotLabel(slot))
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Keyboard & Sandbox Defaults", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.appSettings.pinKeyBarWithHardwareKeyboard,
                            onCheckedChange = { checked ->
                                viewModel.updateAppSettings { it.copy(pinKeyBarWithHardwareKeyboard = checked) }
                            },
                        )
                        Text("Pin Key Bar with Hardware Keyboard")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.appSettings.passAllShortcutsToTerminal,
                            onCheckedChange = { checked ->
                                viewModel.updateAppSettings { it.copy(passAllShortcutsToTerminal = checked) }
                            },
                        )
                        Text("Pass All Shortcuts to Terminal")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.appSettings.confirmUnsafeMultiLinePaste,
                            onCheckedChange = { checked ->
                                viewModel.updateAppSettings { it.copy(confirmUnsafeMultiLinePaste = checked) }
                            },
                        )
                        Text("Confirm Unsafe Multi-Line Paste")
                    }

                    Text("Default Wake Lock: ${state.appSettings.defaultWakeLockMinutes} minutes")
                    Slider(
                        value = state.appSettings.defaultWakeLockMinutes.toFloat(),
                        onValueChange = { mins ->
                            viewModel.updateAppSettings { it.copy(defaultWakeLockMinutes = mins.roundToInt()) }
                        },
                        valueRange = 5f..240f,
                    )

                    Text("Idle Auto-Freeze Timeout: ${state.appSettings.idleFreezeTimeoutMinutes} minutes (0 = off)")
                    Slider(
                        value = state.appSettings.idleFreezeTimeoutMinutes.toFloat(),
                        onValueChange = { mins ->
                            viewModel.updateAppSettings { it.copy(idleFreezeTimeoutMinutes = mins.roundToInt()) }
                        },
                        valueRange = 0f..120f,
                    )

                    Text("Scrollback History: ${state.appSettings.defaultScrollbackLines} lines")
                    Slider(
                        value = state.appSettings.defaultScrollbackLines.toFloat(),
                        onValueChange = { lines ->
                            viewModel.updateAppSettings { it.copy(defaultScrollbackLines = lines.roundToInt()) }
                        },
                        valueRange = 1000f..50000f,
                    )
                }
            }
        }
    }

    val target = editingSlot
    if (target != null) {
        EditKeySlotDialog(
            target = target,
            onDismiss = { editingSlot = null },
            onSave = { updated ->
                viewModel.updateKeyBarSlot(target.isTopRow, target.index, updated)
                editingSlot = null
            },
        )
    }
}

private data class SlotEditTarget(
    val isTopRow: Boolean,
    val index: Int,
    val current: KeySlotSpec,
)

private fun slotLabel(slot: KeySlotSpec): String = when (slot) {
    is KeySlotSpec.Modifier -> slot.mod.name
    is KeySlotSpec.GhosttyKey -> slot.label
    is KeySlotSpec.LiteralText -> slot.label
    is KeySlotSpec.CtrlChord -> slot.label
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditKeySlotDialog(
    target: SlotEditTarget,
    onDismiss: () -> Unit,
    onSave: (KeySlotSpec) -> Unit,
) {
    var mode by remember {
        mutableStateOf(
            when (target.current) {
                is KeySlotSpec.Modifier -> 0
                is KeySlotSpec.GhosttyKey -> 1
                is KeySlotSpec.CtrlChord -> 2
                is KeySlotSpec.LiteralText -> 3
            },
        )
    }
    var selectedMod by remember {
        mutableStateOf((target.current as? KeySlotSpec.Modifier)?.mod ?: ModifierKey.CTRL)
    }
    var label by remember { mutableStateOf(slotLabel(target.current)) }
    var valueText by remember {
        mutableStateOf(
            when (val cur = target.current) {
                is KeySlotSpec.GhosttyKey -> cur.keyName
                is KeySlotSpec.CtrlChord -> cur.asciiUpper.toString()
                is KeySlotSpec.LiteralText -> cur.text
                is KeySlotSpec.Modifier -> cur.mod.name
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Key Bar Slot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Modifier", "Ghostty Key", "Ctrl Chord", "Literal").forEachIndexed { idx, title ->
                        FilterChip(selected = mode == idx, onClick = { mode = idx }, label = { Text(title) })
                    }
                }
                if (mode == 0) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModifierKey.entries.forEach { mod ->
                            FilterChip(
                                selected = selectedMod == mod,
                                onClick = { selectedMod = mod },
                                label = { Text(mod.name) },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Button Label") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it },
                        label = {
                            Text(
                                when (mode) {
                                    1 -> "Ghostty Key Name (e.g. Escape, Tab)"
                                    2 -> "Uppercase Letter (e.g. C, D, Z)"
                                    else -> "Literal Text"
                                },
                            )
                        },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val spec = when (mode) {
                    0 -> KeySlotSpec.Modifier(selectedMod)
                    1 -> KeySlotSpec.GhosttyKey(
                        keyName = valueText.trim().ifEmpty { "Escape" },
                        label = label.trim().ifEmpty { "Key" },
                    )
                    2 -> {
                        val ch = valueText.trim().uppercase().firstOrNull { it in 'A'..'Z' } ?: 'C'
                        KeySlotSpec.CtrlChord(asciiUpper = ch, label = label.trim().ifEmpty { "^$ch" })
                    }
                    else -> KeySlotSpec.LiteralText(
                        text = valueText.ifEmpty { "/" },
                        label = label.trim().ifEmpty { "/" },
                    )
                }
                onSave(spec)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
