package dev.gfire.termvr.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.KeySlotSpec
import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.ui.licenses.ThirdPartyLicenseEntry
import dev.gfire.termvr.ui.theme.LocalTermVrMonoFontFamily
import dev.gfire.termvr.ui.theme.TermVrBadge
import dev.gfire.termvr.ui.theme.TermVrButton
import dev.gfire.termvr.ui.theme.TermVrCard
import dev.gfire.termvr.ui.theme.TermVrControlShape
import dev.gfire.termvr.ui.theme.TermVrIcons
import dev.gfire.termvr.ui.theme.TermVrNoticeBanner
import dev.gfire.termvr.ui.theme.TermVrOutlinedButton
import dev.gfire.termvr.ui.theme.TermVrSectionHeader
import dev.gfire.termvr.ui.theme.TermVrSelectableChip
import dev.gfire.termvr.ui.theme.TermVrTextButton
import dev.gfire.termvr.ui.theme.toComposeBackground
import dev.gfire.termvr.ui.theme.toComposeCursor
import dev.gfire.termvr.ui.theme.toComposeForeground
import kotlin.math.roundToInt

/**
 * Renders Settings across Appearance, Input, System, and Third-Party Licenses tabs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val monoFont = LocalTermVrMonoFontFamily.current
    var expectedSha256 by remember { mutableStateOf("") }
    var showShaField by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<SlotEditTarget?>(null) }

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    viewModel.importCustomFont(input, expectedSha256.takeIf { showShaField })
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TermVrSectionHeader(title = "Settings")
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTab.entries.forEach { tab ->
                    TermVrSelectableChip(
                        label = tab.label,
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                    )
                }
            }
        }

        val banner = state.errorMessage ?: state.statusMessage
        if (banner != null) {
            item {
                TermVrNoticeBanner(
                    message = banner,
                    isError = state.errorMessage != null,
                    onDismiss = viewModel::clearBanners,
                )
            }
        }

        when (state.selectedTab) {
            SettingsTab.APPEARANCE -> {
                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Font", style = MaterialTheme.typography.titleMedium)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TermVrSelectableChip(
                                    label = "TermVR Mono NF",
                                    selected = state.fontConfig.familyId == FontConfig.DEFAULT_FAMILY_ID &&
                                        state.fontConfig.customFontPath == null,
                                    onClick = { viewModel.updateFontFamily(FontConfig.DEFAULT_FAMILY_ID) },
                                )
                                if (state.fontConfig.customFontPath != null) {
                                    TermVrBadge(
                                        text = "Custom · ${state.fontConfig.customFontSha256?.take(8) ?: "local"}",
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TermVrOutlinedButton(
                                    onClick = { fontPicker.launch(arrayOf("*/*")) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text("Import .ttf / .otf")
                                }
                                TermVrTextButton(onClick = { showShaField = !showShaField }) {
                                    Text(if (showShaField) "Hide SHA-256" else "Verify SHA-256")
                                }
                            }

                            if (showShaField) {
                                OutlinedTextField(
                                    value = expectedSha256,
                                    onValueChange = { expectedSha256 = it },
                                    label = { Text("Expected SHA-256 (optional)") },
                                    singleLine = true,
                                    shape = TermVrControlShape,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Text(
                                text = "Size · ${state.fontConfig.sizeSp.roundToInt()} sp",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monoFont,
                            )
                            Slider(
                                value = state.fontConfig.sizeSp,
                                onValueChange = { size ->
                                    viewModel.updateFontMetrics(size, state.fontConfig.cellHeightMultiplier)
                                },
                                valueRange = FontConfig.MIN_SIZE_SP..FontConfig.MAX_SIZE_SP,
                            )

                            val cellRounded = ((state.fontConfig.cellHeightMultiplier * 100f).roundToInt() / 100f)
                            Text(
                                text = "Line height · ${cellRounded}x",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monoFont,
                            )
                            Slider(
                                value = state.fontConfig.cellHeightMultiplier,
                                onValueChange = { mult ->
                                    viewModel.updateFontMetrics(state.fontConfig.sizeSp, mult)
                                },
                                valueRange = FontConfig.MIN_CELL_HEIGHT_MULTIPLIER..FontConfig.MAX_CELL_HEIGHT_MULTIPLIER,
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.fontConfig.ligaturesEnabled,
                                    onCheckedChange = { checked ->
                                        viewModel.updateFontFeatures(checked, state.fontConfig.nerdFontSymbolsEnabled)
                                    },
                                )
                                Text("Font ligatures", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.fontConfig.nerdFontSymbolsEnabled,
                                    onCheckedChange = { checked ->
                                        viewModel.updateFontFeatures(state.fontConfig.ligaturesEnabled, checked)
                                    },
                                )
                                Text("Nerd Font symbols", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Color Theme", style = MaterialTheme.typography.titleMedium)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.palettes.forEach { palette ->
                                    val selected = state.appSettings.selectedPaletteId == palette.id
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(TermVrControlShape)
                                            .clickable { viewModel.selectPalette(palette.id) },
                                        shape = TermVrControlShape,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline
                                            },
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    SwatchDot(palette.toComposeBackground())
                                                    SwatchDot(palette.toComposeCursor())
                                                    SwatchDot(palette.toComposeForeground())
                                                }
                                                Text(
                                                    text = palette.displayName,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                            }
                                            if (selected) {
                                                Icon(
                                                    imageVector = TermVrIcons.Check,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SettingsTab.INPUT -> {
                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Keyboard & Input", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.keyBarConfig.visible,
                                    onCheckedChange = { checked ->
                                        viewModel.updateKeyBarSettings(checked, state.keyBarConfig.hapticFeedback)
                                    },
                                )
                                Text("Show key bar", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.keyBarConfig.hapticFeedback,
                                    onCheckedChange = { checked ->
                                        viewModel.updateKeyBarSettings(state.keyBarConfig.visible, checked)
                                    },
                                )
                                Text("Haptic key feedback", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.appSettings.pinKeyBarWithHardwareKeyboard,
                                    onCheckedChange = { checked ->
                                        viewModel.updateAppSettings { it.copy(pinKeyBarWithHardwareKeyboard = checked) }
                                    },
                                )
                                Text("Pin key bar with hardware keyboard", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.appSettings.passAllShortcutsToTerminal,
                                    onCheckedChange = { checked ->
                                        viewModel.updateAppSettings { it.copy(passAllShortcutsToTerminal = checked) }
                                    },
                                )
                                Text("Pass hardware shortcuts to terminal", style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.appSettings.confirmUnsafeMultiLinePaste,
                                    onCheckedChange = { checked ->
                                        viewModel.updateAppSettings { it.copy(confirmUnsafeMultiLinePaste = checked) }
                                    },
                                )
                                Text("Confirm multi-line paste", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Key Bar Layout (2 × 7)", style = MaterialTheme.typography.titleMedium)
                                TermVrTextButton(onClick = viewModel::resetKeyBarDefaults) {
                                    Text("Reset")
                                }
                            }
                            KeyBarRowEditor(
                                slots = state.keyBarConfig.topRow,
                                onEdit = { index, spec ->
                                    editingSlot = SlotEditTarget(isTopRow = true, index = index, current = spec)
                                },
                            )
                            KeyBarRowEditor(
                                slots = state.keyBarConfig.bottomRow,
                                onEdit = { index, spec ->
                                    editingSlot = SlotEditTarget(isTopRow = false, index = index, current = spec)
                                },
                            )
                        }
                    }
                }
            }

            SettingsTab.SYSTEM -> {
                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Session Defaults", style = MaterialTheme.typography.titleMedium)

                            Text(
                                text = "Idle auto-freeze · ${state.appSettings.idleFreezeTimeoutMinutes} min",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monoFont,
                            )
                            Slider(
                                value = state.appSettings.idleFreezeTimeoutMinutes.toFloat(),
                                onValueChange = { mins ->
                                    viewModel.updateAppSettings {
                                        it.copy(idleFreezeTimeoutMinutes = mins.roundToInt().coerceAtLeast(1))
                                    }
                                },
                                valueRange = 1f..120f,
                            )

                            Text(
                                text = "Default wake lock · ${state.appSettings.defaultWakeLockMinutes} min",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monoFont,
                            )
                            Slider(
                                value = state.appSettings.defaultWakeLockMinutes.toFloat(),
                                onValueChange = { mins ->
                                    viewModel.updateAppSettings { it.copy(defaultWakeLockMinutes = mins.roundToInt()) }
                                },
                                valueRange = 0f..240f,
                            )

                            Text(
                                text = "Scrollback history · ${state.appSettings.defaultScrollbackLines} lines",
                                style = MaterialTheme.typography.labelLarge,
                                fontFamily = monoFont,
                            )
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

                item {
                    TermVrCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("System Diagnostics", style = MaterialTheme.typography.titleMedium)
                                IconButton(
                                    onClick = viewModel::refreshSystemDiagnostics,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = TermVrIcons.Refresh,
                                        contentDescription = "Refresh",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            val report = state.hostReadiness
                            DiagnosticRow("App version", state.appVersionName.ifBlank { "-" })
                            DiagnosticRow("gvx version", state.gvxVersion.ifBlank { "-" })
                            if (report != null) {
                                DiagnosticRow(
                                    "Architecture",
                                    "${report.hostAbi} / ${report.guestArch?.ociArch ?: "-"}",
                                )
                                DiagnosticRow(
                                    "Page size",
                                    "${report.kernelPageSizeBytes / 1024} KB",
                                )
                                DiagnosticRow(
                                    "Child processes",
                                    "${report.liveChildProcessCount} / 32",
                                )
                                DiagnosticRow(
                                    "Free storage",
                                    "${report.freeDiskMiB} MB",
                                )
                                DiagnosticRow(
                                    "Host RAM",
                                    "${report.totalHostRamMiB} MB",
                                )
                                if (report.phantomKillerDetected) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    val hint = when (report.remediationKind) {
                                        PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE ->
                                            "Enable Disable child process restrictions in Developer Options."
                                        PhantomProcessRemediation.ANDROID_12_13_ADB_COMMAND ->
                                            "Run: adb shell device_config set_sync_disabled_for_tests persistent && adb shell device_config put activity_manager max_phantom_processes 2147483647"
                                        PhantomProcessRemediation.NONE ->
                                            "Background process kill detected."
                                    }
                                    TermVrNoticeBanner(
                                        message = hint,
                                        isError = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsTab.LICENSES -> {
                if (state.licenses.isEmpty()) {
                    item {
                        TermVrCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "No third-party notices loaded.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.licenses, key = { it.id }) { entry ->
                        LicenseCard(
                            entry = entry,
                            onClick = { viewModel.openLicense(entry) },
                        )
                    }
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

    val licenseEntry = state.activeLicense
    val licenseText = state.activeLicenseText
    if (licenseEntry != null && licenseText != null) {
        AlertDialog(
            onDismissRequest = viewModel::closeLicense,
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(licenseEntry.name, style = MaterialTheme.typography.titleMedium)
                    TermVrBadge(text = "${licenseEntry.version} · ${licenseEntry.spdxId}")
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = monoFont,
                    )
                }
            },
            confirmButton = {
                TermVrTextButton(onClick = viewModel::closeLicense) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SwatchDot(color: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier.size(12.dp),
        shape = CircleShape,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = {},
    )
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
) {
    val monoFont = LocalTermVrMonoFontFamily.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = monoFont,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LicenseCard(
    entry: ThirdPartyLicenseEntry,
    onClick: () -> Unit,
) {
    val monoFont = LocalTermVrMonoFontFamily.current
    TermVrCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
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
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    TermVrBadge(text = entry.spdxId)
                }
                Text(
                    text = "${entry.version} · ${entry.repositoryUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = monoFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = TermVrIcons.ChevronRight,
                contentDescription = "Open license",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun KeyBarRowEditor(
    slots: List<KeySlotSpec>,
    onEdit: (Int, KeySlotSpec) -> Unit,
) {
    val monoFont = LocalTermVrMonoFontFamily.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        slots.forEachIndexed { index, spec ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(TermVrControlShape)
                    .clickable { onEdit(index, spec) },
                shape = TermVrControlShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = slotLabel(spec),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = monoFont,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
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
        title = { Text("Edit Key Slot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("Modifier", "Key", "Ctrl Chord", "Literal").forEachIndexed { idx, title ->
                        TermVrSelectableChip(
                            label = title,
                            selected = mode == idx,
                            onClick = { mode = idx },
                        )
                    }
                }
                if (mode == 0) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ModifierKey.entries.forEach { mod ->
                            TermVrSelectableChip(
                                label = mod.name,
                                selected = selectedMod == mod,
                                onClick = { selectedMod = mod },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        singleLine = true,
                        shape = TermVrControlShape,
                    )
                    OutlinedTextField(
                        value = valueText,
                        onValueChange = { valueText = it },
                        label = {
                            Text(
                                when (mode) {
                                    1 -> "Ghostty Key (e.g. Escape, Tab)"
                                    2 -> "Letter (e.g. C, D, Z)"
                                    else -> "Literal Text"
                                },
                            )
                        },
                        singleLine = true,
                        shape = TermVrControlShape,
                    )
                }
            }
        },
        confirmButton = {
            TermVrButton(onClick = {
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
        dismissButton = { TermVrTextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
