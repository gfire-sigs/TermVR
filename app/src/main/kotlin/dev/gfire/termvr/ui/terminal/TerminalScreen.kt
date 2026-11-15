package dev.gfire.termvr.ui.terminal

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.ui.AuxiliaryKeyBar
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSurfaceController
import dev.gfire.termvr.core.ui.TerminalViewport
import dev.gfire.termvr.ui.theme.toComposeBackground
import kotlinx.coroutines.launch

/**
 * Renders the single-surface multi-tab split-pane Terminal screen, session drawer, and key bar.
 */
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val hardwareKeyboardAttached = configuration.keyboard == Configuration.KEYBOARD_QWERTY

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            TerminalSessionDrawer(
                state = state,
                onSelectTab = { tabId ->
                    viewModel.selectTab(tabId)
                    scope.launch { drawerState.close() }
                },
                onActivateProfile = { profile ->
                    viewModel.switchToOrStartProfile(profile)
                    scope.launch { drawerState.close() }
                },
                onFreezeProfile = viewModel::freezeProfile,
                onStopProfile = viewModel::stopProfile,
                onAcquireWakeLock = viewModel::acquireWakeLock,
                onReleaseWakeLock = viewModel::releaseWakeLock,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(state.palette.toComposeBackground())
                .imePadding(),
        ) {
            TerminalTabHeader(
                state = state,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onSelectTab = viewModel::selectTab,
                onNewTab = viewModel::openNewTabForActiveSandbox,
                onCloseTab = viewModel::closeTab,
                onSplitHorizontal = { viewModel.splitActivePane(SplitDirection.HORIZONTAL) },
                onSplitVertical = { viewModel.splitActivePane(SplitDirection.VERTICAL) },
                onClosePane = viewModel::closeFocusedPane,
                onToggleSearch = {
                    if (state.searchVisible) viewModel.closeSearch() else viewModel.openSearch()
                },
                onToggleKeyBar = viewModel::toggleKeyBarCollapsed,
            )

            if (state.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::clearError) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            if (state.searchVisible) {
                ScrollbackSearchBar(
                    state = state,
                    onQueryChange = viewModel::updateSearchQuery,
                    onToggleRegex = viewModel::toggleSearchRegex,
                    onToggleCaseSensitive = viewModel::toggleSearchCaseSensitive,
                    onPrev = viewModel::prevSearchMatch,
                    onNext = viewModel::nextSearchMatch,
                    onClose = viewModel::closeSearch,
                )
            }

            val activeTab = state.activeTab
            if (activeTab == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = "No Active Terminal Session",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "Start a sandbox from Home or open the session drawer to switch profiles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onNavigateHome) {
                                Text("Go to Home")
                            }
                            OutlinedButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("Sessions")
                            }
                        }
                    }
                }
            } else {
                val controller = viewModel.activeController() ?: TerminalSurfaceController()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    TerminalViewport(
                        splitRoot = activeTab.splitRoot,
                        focusedPaneId = activeTab.focusedPaneId,
                        palette = state.palette,
                        fontConfig = state.fontConfig,
                        latchMachine = viewModel.latchMachine,
                        passAllShortcutsToTerminal = state.appSettings.passAllShortcutsToTerminal,
                        controller = controller,
                        onSendPtyBytes = viewModel::sendPtyBytes,
                        onPaneFocused = { paneId -> viewModel.focusPane(activeTab.tabId, paneId) },
                        onGridResized = viewModel::onGridResized,
                        onFontConfigChanged = viewModel::updateFontConfig,
                        onHostShortcut = viewModel::handleHostShortcut,
                        onShareText = viewModel::shareSelectionText,
                        onSearchRequested = viewModel::openSearch,
                        onOpenUrl = viewModel::openExternalUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                AuxiliaryKeyBar(
                    config = state.keyBarConfig,
                    latchMachine = viewModel.latchMachine,
                    hardwareKeyboardAttached = hardwareKeyboardAttached,
                    pinWithHardwareKeyboard = state.appSettings.pinKeyBarWithHardwareKeyboard,
                    viewportRows = viewModel.activeSession()?.rows ?: 24,
                    onBeforeControlKey = { controller.flushImeAndRestartInput() },
                    onEmitKeySlot = { slot -> controller.emitKeySlot(slot) },
                    onCursorScrub = { steps -> controller.scrubCursor(steps) },
                    onHalfScreenScrollUp = { lines -> controller.scrollHalfScreenUp(lines) },
                    collapsed = state.keyBarCollapsed,
                )
            }
        }
    }

    val prompt = state.clipboardPrompt
    if (prompt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveClipboardPrompt(false) },
            title = { Text("Allow Clipboard Read?") },
            text = {
                Text(
                    "Sandbox profile \"${prompt.profileId}\" requested read access to the Android clipboard.",
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.resolveClipboardPrompt(true) }) {
                    Text("Allow Once")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveClipboardPrompt(false) }) {
                    Text("Deny")
                }
            },
        )
    }
}

@Composable
private fun TerminalTabHeader(
    state: TerminalUiState,
    onOpenDrawer: () -> Unit,
    onSelectTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: (String) -> Unit,
    onSplitHorizontal: () -> Unit,
    onSplitVertical: () -> Unit,
    onClosePane: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleKeyBar: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onOpenDrawer) {
                    Text("Sessions")
                }

                for (tab in state.tabs) {
                    val selected = tab.tabId == state.activeTab?.tabId
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectTab(tab.tabId) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = tab.title,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (!tab.isDaemonLogs) {
                                    Text(
                                        text = "x",
                                        modifier = Modifier.clickable { onCloseTab(tab.tabId) },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                }

                if (state.activeTab != null) {
                    TextButton(onClick = onNewTab) { Text("+ Tab") }
                    TextButton(onClick = onSplitHorizontal) { Text("Split H") }
                    TextButton(onClick = onSplitVertical) { Text("Split V") }
                    TextButton(onClick = onClosePane) { Text("Close Pane") }
                    TextButton(onClick = onToggleSearch) { Text("Find") }
                    TextButton(onClick = onToggleKeyBar) {
                        Text(if (state.keyBarCollapsed) "Show Keys" else "Hide Keys")
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScrollbackSearchBar(
    state: TerminalUiState,
    onQueryChange: (String) -> Unit,
    onToggleRegex: () -> Unit,
    onToggleCaseSensitive: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search terminal scrollback") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("Done") }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = state.searchUseRegex,
                    onClick = onToggleRegex,
                    label = { Text("Regex") },
                )
                FilterChip(
                    selected = state.searchCaseSensitive,
                    onClick = onToggleCaseSensitive,
                    label = { Text("Case") },
                )
                val statusLabel = if (state.searchMatches.isEmpty()) {
                    "0 matches"
                } else {
                    "${state.selectedMatchIndex + 1}/${state.searchMatches.size}"
                }
                AssistChip(onClick = {}, label = { Text(statusLabel) })
                TextButton(onClick = onPrev, enabled = state.searchMatches.isNotEmpty()) {
                    Text("Prev")
                }
                TextButton(onClick = onNext, enabled = state.searchMatches.isNotEmpty()) {
                    Text("Next")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalSessionDrawer(
    state: TerminalUiState,
    onSelectTab: (String) -> Unit,
    onActivateProfile: (dev.gfire.termvr.core.model.SandboxProfile) -> Unit,
    onFreezeProfile: (String) -> Unit,
    onStopProfile: (String) -> Unit,
    onAcquireWakeLock: (Int) -> Unit,
    onReleaseWakeLock: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Wake Lock", style = MaterialTheme.typography.titleMedium)
                val remaining = state.wakeLockRemainingSeconds
                Text(
                    text = if (remaining != null) {
                        "Active: ${(remaining / 60L)}m ${(remaining % 60L)}s left"
                    } else {
                        "Inactive"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60).forEach { mins ->
                        OutlinedButton(onClick = { onAcquireWakeLock(mins) }) {
                            Text("${mins}m")
                        }
                    }
                    if (remaining != null) {
                        TextButton(onClick = onReleaseWakeLock) {
                            Text("Release")
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Text("Open Tabs (${state.tabs.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(state.tabs, key = { it.tabId }) { tab ->
                TextButton(
                    onClick = { onSelectTab(tab.tabId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${tab.title} [${tab.profileId}]")
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Sandbox Profiles", style = MaterialTheme.typography.titleMedium)
            }
            items(state.profiles, key = { it.id }) { profile ->
                val runState = state.runStates[profile.id] ?: SandboxRunState.STOPPED
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${profile.displayName} (${runState.name})",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onActivateProfile(profile) }) {
                            Text(if (runState == SandboxRunState.RUNNING) "Open" else "Start")
                        }
                        if (runState == SandboxRunState.RUNNING) {
                            TextButton(onClick = { onFreezeProfile(profile.id) }) { Text("Freeze") }
                        }
                        if (runState != SandboxRunState.STOPPED) {
                            TextButton(onClick = { onStopProfile(profile.id) }) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }
}
