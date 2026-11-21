package dev.gfire.termvr.ui.terminal

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.ui.AuxiliaryKeyBar
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSplitNode
import dev.gfire.termvr.core.ui.TerminalSurfaceController
import dev.gfire.termvr.core.ui.TerminalViewport
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
import dev.gfire.termvr.ui.theme.runStateLabel
import dev.gfire.termvr.ui.theme.shortImageRef
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
                onNewShellForProfile = { profile ->
                    viewModel.openNewTabForProfile(profile)
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
            TerminalTopBar(
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
                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TermVrNoticeBanner(
                        message = state.errorMessage.orEmpty(),
                        isError = true,
                        onDismiss = viewModel::clearError,
                    )
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
                        Icon(
                            imageVector = TermVrIcons.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "No active session",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TermVrButton(onClick = onNavigateHome) {
                                Text("Containers")
                            }
                            TermVrOutlinedButton(onClick = { scope.launch { drawerState.open() } }) {
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
            shape = TermVrControlShape,
            title = { Text("Allow clipboard read?") },
            text = {
                Text("\"${prompt.profileId}\" requested read access to the clipboard.")
            },
            confirmButton = {
                TermVrButton(onClick = { viewModel.resolveClipboardPrompt(true) }) {
                    Text("Allow Once")
                }
            },
            dismissButton = {
                TermVrTextButton(onClick = { viewModel.resolveClipboardPrompt(false) }) {
                    Text("Deny")
                }
            },
        )
    }
}

private sealed interface PendingCloseTarget {
    val label: String

    data class Tab(val tabId: String, override val label: String) : PendingCloseTarget
    data class SplitPane(val tabId: String, val paneId: String, override val label: String) : PendingCloseTarget
}

@Composable
private fun TerminalTopBar(
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
    val mono = LocalTermVrMonoFontFamily.current
    val tokens = LocalTermVrPaletteTokens.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val activeTab = state.activeTab
    val activeProfile = activeTab?.let { tab -> state.profiles.firstOrNull { it.id == tab.profileId } }
    val activeRunState = activeProfile?.let { state.runStates[it.id] } ?: SandboxRunState.STOPPED
    val runningCount = state.runStates.values.count { it == SandboxRunState.RUNNING }
    val openContainerCount = state.tabs.map { it.profileId }.distinct().size
    val hasMultiplePanes = activeTab?.splitRoot is TerminalSplitNode.Split
    var pendingClose by remember { mutableStateOf<PendingCloseTarget?>(null) }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Left: Container / Sessions Drawer Button
                Surface(
                    modifier = Modifier
                        .clip(TermVrControlShape)
                        .clickable(onClick = onOpenDrawer),
                    shape = TermVrControlShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (activeProfile != null) {
                            TermVrRunStateDot(runState = activeRunState, size = 7.dp)
                            Text(
                                text = activeProfile.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = mono,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Icon(
                                imageVector = TermVrIcons.Menu,
                                contentDescription = "Sessions",
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "Sessions",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = mono,
                            )
                        }
                        if (runningCount > 1) {
                            Surface(
                                shape = TermVrControlShape,
                                color = tokens.statusRunning.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    text = "$runningCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = mono,
                                    color = tokens.statusRunning,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }

                // Middle: Scrollable Session Tabs + compact '+' button
                val tabScrollState = rememberScrollState()
                var tabRowViewportWidthPx by remember { mutableIntStateOf(0) }
                val tabRowViewportWidthDp = with(density) { tabRowViewportWidthPx.toDp() }
                val tabBoundsPx = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
                val activeTabId = activeTab?.tabId
                LaunchedEffect(activeTabId, state.tabs.size, hasMultiplePanes, tabRowViewportWidthPx) {
                    val bounds = activeTabId?.let { tabBoundsPx[it] } ?: return@LaunchedEffect
                    if (tabRowViewportWidthPx <= 0) return@LaunchedEffect
                    val (leftPx, widthPx) = bounds
                    val rightPx = leftPx + widthPx
                    val currentScroll = tabScrollState.value
                    val targetScroll = when {
                        leftPx < currentScroll -> leftPx
                        rightPx > currentScroll + tabRowViewportWidthPx -> rightPx - tabRowViewportWidthPx
                        else -> currentScroll
                    }.coerceIn(0, tabScrollState.maxValue)
                    if (targetScroll != currentScroll) {
                        tabScrollState.animateScrollTo(targetScroll)
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { tabRowViewportWidthPx = it.size.width }
                        .horizontalScroll(tabScrollState),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (tab in state.tabs) {
                        val selected = tab.tabId == activeTab?.tabId
                        val tabLabel = formatTabLabel(tab, state.profiles, openContainerCount)
                        val showInlineClose = shouldShowInlineTabClose(
                            tab = tab,
                            selected = selected,
                            tabCount = state.tabs.size,
                            screenWidthDp = configuration.screenWidthDp,
                            tabRowViewportWidthDp = tabRowViewportWidthDp,
                        )
                        Surface(
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    tabBoundsPx[tab.tabId] = coords.positionInParent().x.toInt() to coords.size.width
                                }
                                .clip(TermVrControlShape)
                                .clickable { onSelectTab(tab.tabId) },
                            shape = TermVrControlShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    start = 10.dp,
                                    end = if (showInlineClose) 4.dp else 10.dp,
                                    top = 4.dp,
                                    bottom = 4.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = tabLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = mono,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
                                )
                                if (showInlineClose) {
                                    Surface(
                                        onClick = {
                                            if (shouldConfirmTabClose(
                                                    tab = tab,
                                                    screenWidthDp = configuration.screenWidthDp,
                                                    tabRowViewportWidthDp = tabRowViewportWidthDp,
                                                    tabCount = state.tabs.size,
                                                )
                                            ) {
                                                pendingClose = PendingCloseTarget.Tab(tab.tabId, tabLabel)
                                            } else {
                                                onCloseTab(tab.tabId)
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = TermVrIcons.Close,
                                                contentDescription = "Close tab",
                                                tint = if (selected) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.size(13.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (activeTab != null) {
                        IconButton(
                            onClick = onNewTab,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = TermVrIcons.Add,
                                contentDescription = "New tab",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // Right: Compact icon buttons
                if (activeTab != null) {
                    IconButton(
                        onClick = onSplitHorizontal,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.SplitHorizontal,
                            contentDescription = "Split Horizontal",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onSplitVertical,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.SplitVertical,
                            contentDescription = "Split Vertical",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (hasMultiplePanes) {
                        IconButton(
                            onClick = {
                                if (shouldConfirmPaneClose(
                                        tab = activeTab,
                                        paneId = activeTab.focusedPaneId,
                                        screenWidthDp = configuration.screenWidthDp,
                                        tabRowViewportWidthDp = tabRowViewportWidthDp,
                                        tabCount = state.tabs.size,
                                    )
                                ) {
                                    val label = formatTabLabel(activeTab, state.profiles, openContainerCount)
                                    pendingClose = PendingCloseTarget.SplitPane(
                                        tabId = activeTab.tabId,
                                        paneId = activeTab.focusedPaneId,
                                        label = label,
                                    )
                                } else {
                                    onClosePane()
                                }
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = TermVrIcons.Close,
                                contentDescription = "Close Pane",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleSearch,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.Search,
                            contentDescription = "Find",
                            tint = if (state.searchVisible) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    IconButton(
                        onClick = onToggleKeyBar,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = TermVrIcons.Keyboard,
                            contentDescription = if (state.keyBarCollapsed) "Show Keys" else "Hide Keys",
                            tint = if (!state.keyBarCollapsed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
        }
    }

    val confirmTarget = pendingClose
    if (confirmTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            shape = TermVrControlShape,
            title = {
                Text(
                    if (confirmTarget is PendingCloseTarget.SplitPane) "Close split pane?" else "Close tab?",
                )
            },
            text = {
                Text("Close \"${confirmTarget.label}\"? Running processes in this shell will terminate.")
            },
            confirmButton = {
                TermVrButton(
                    onClick = {
                        pendingClose = null
                        when (confirmTarget) {
                            is PendingCloseTarget.Tab -> onCloseTab(confirmTarget.tabId)
                            is PendingCloseTarget.SplitPane -> onClosePane()
                        }
                    },
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                TermVrTextButton(onClick = { pendingClose = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

internal fun formatTabLabel(
    tab: TerminalTabState,
    profiles: List<SandboxProfile>,
    openContainerCount: Int,
): String {
    val containerName = profiles.firstOrNull { it.id == tab.profileId }?.displayName ?: tab.profileId
    if (tab.isDaemonLogs) {
        return if (openContainerCount >= 2) "$containerName Logs" else "Logs"
    }
    val oscTitle = tab.sessionTitle?.trim()?.takeIf { it.isNotEmpty() }
    val baseTitle = oscTitle ?: normalizeShellTabTitle(tab.title)
    if (openContainerCount >= 2) {
        return if (baseTitle.startsWith(containerName)) baseTitle else "$containerName · $baseTitle"
    }
    return baseTitle
}

private fun normalizeShellTabTitle(rawTitle: String): String {
    val hashIdx = rawTitle.lastIndexOf('#')
    if (hashIdx >= 0) {
        val suffix = rawTitle.substring(hashIdx + 1).trim()
        if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) {
            return "Shell $suffix"
        }
    }
    return rawTitle
}

internal fun shouldShowInlineTabClose(
    tab: TerminalTabState,
    selected: Boolean,
    tabCount: Int,
    screenWidthDp: Int,
    tabRowViewportWidthDp: Dp,
): Boolean {
    if (tab.isDaemonLogs) return false
    val isCompactStrip = screenWidthDp < 600 || (tabRowViewportWidthDp > 0.dp && tabRowViewportWidthDp < 420.dp)
    if (isCompactStrip && tabCount >= 2) {
        return selected
    }
    return true
}

internal fun shouldConfirmTabClose(
    tab: TerminalTabState,
    screenWidthDp: Int,
    tabRowViewportWidthDp: Dp,
    tabCount: Int,
): Boolean {
    val allExited = tab.allPaneIds().all { it in tab.exitedPanes }
    if (allExited) return false
    return isNarrowOrCompactTabBar(screenWidthDp, tabRowViewportWidthDp, tabCount)
}

internal fun shouldConfirmPaneClose(
    tab: TerminalTabState,
    paneId: String,
    screenWidthDp: Int,
    tabRowViewportWidthDp: Dp,
    tabCount: Int,
): Boolean {
    if (paneId in tab.exitedPanes) return false
    return isNarrowOrCompactTabBar(screenWidthDp, tabRowViewportWidthDp, tabCount)
}

private fun isNarrowOrCompactTabBar(
    screenWidthDp: Int,
    tabRowViewportWidthDp: Dp,
    tabCount: Int,
): Boolean {
    if (screenWidthDp < 600) return true
    if (tabRowViewportWidthDp <= 0.dp) return false
    val avgTabWidthDp = tabRowViewportWidthDp / tabCount.coerceAtLeast(1)
    return tabRowViewportWidthDp < 420.dp || avgTabWidthDp < 140.dp
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search scrollback") },
                    singleLine = true,
                    shape = TermVrControlShape,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = TermVrIcons.Close,
                        contentDescription = "Close search",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TermVrSelectableChip(
                    label = "Regex",
                    selected = state.searchUseRegex,
                    onClick = onToggleRegex,
                )
                TermVrSelectableChip(
                    label = "Case",
                    selected = state.searchCaseSensitive,
                    onClick = onToggleCaseSensitive,
                )
                val statusLabel = if (state.searchMatches.isEmpty()) {
                    "0 matches"
                } else {
                    "${state.selectedMatchIndex + 1}/${state.searchMatches.size}"
                }
                TermVrBadge(text = statusLabel)
                TermVrTextButton(onClick = onPrev, enabled = state.searchMatches.isNotEmpty()) {
                    Text("Prev")
                }
                TermVrTextButton(onClick = onNext, enabled = state.searchMatches.isNotEmpty()) {
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
    onActivateProfile: (SandboxProfile) -> Unit,
    onNewShellForProfile: (SandboxProfile) -> Unit,
    onFreezeProfile: (String) -> Unit,
    onStopProfile: (String) -> Unit,
    onAcquireWakeLock: (Int) -> Unit,
    onReleaseWakeLock: () -> Unit,
) {
    val mono = LocalTermVrMonoFontFamily.current
    val tokens = LocalTermVrPaletteTokens.current

    ModalDrawerSheet(
        modifier = Modifier.width(310.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("Containers & Sessions", style = MaterialTheme.typography.titleMedium)
            }

            items(state.profiles, key = { it.id }) { profile ->
                val runState = state.runStates[profile.id] ?: SandboxRunState.STOPPED
                val containerTabs = state.tabs.filter { it.profileId == profile.id }

                TermVrCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TermVrRunStateDot(runState = runState, size = 8.dp)
                                Column {
                                    Text(
                                        text = profile.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = shortImageRef(profile.imageRef),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = mono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            TermVrBadge(text = runStateLabel(runState))
                        }

                        if (containerTabs.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (tab in containerTabs) {
                                    val isCurrent = tab.tabId == state.activeTab?.tabId
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(TermVrControlShape)
                                            .clickable { onSelectTab(tab.tabId) },
                                        shape = TermVrControlShape,
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = tab.title,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = mono,
                                                color = if (isCurrent) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                            )
                                            if (isCurrent) {
                                                TermVrStatusDot(color = tokens.statusRunning, size = 6.dp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TermVrOutlinedButton(
                                onClick = { onActivateProfile(profile) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = when (runState) {
                                        SandboxRunState.RUNNING -> "Open"
                                        SandboxRunState.FROZEN -> "Resume"
                                        else -> "Start"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            if (runState == SandboxRunState.RUNNING) {
                                TermVrOutlinedButton(
                                    onClick = { onNewShellForProfile(profile) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text("+ New Shell", style = MaterialTheme.typography.labelMedium)
                                }
                                TermVrTextButton(onClick = { onFreezeProfile(profile.id) }) {
                                    Text("Freeze")
                                }
                            }
                            if (runState != SandboxRunState.STOPPED) {
                                TermVrTextButton(
                                    onClick = { onStopProfile(profile.id) },
                                    contentColor = MaterialTheme.colorScheme.error,
                                ) {
                                    Text("Stop")
                                }
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(4.dp))
                Text("Wake Lock", style = MaterialTheme.typography.titleSmall)
                val remaining = state.wakeLockRemainingSeconds
                Text(
                    text = when {
                        remaining == null -> "Inactive"
                        remaining <= 0L -> "Active (indefinite)"
                        else -> "${remaining / 60L}m ${remaining % 60L}s left"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TermVrOutlinedButton(
                        onClick = { onAcquireWakeLock(0) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Indefinite", style = MaterialTheme.typography.labelSmall, fontFamily = mono)
                    }
                    listOf(15, 30, 60).forEach { mins ->
                        TermVrOutlinedButton(
                            onClick = { onAcquireWakeLock(mins) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("${mins}m", style = MaterialTheme.typography.labelSmall, fontFamily = mono)
                        }
                    }
                    if (remaining != null) {
                        TermVrTextButton(onClick = onReleaseWakeLock) {
                            Text("Release")
                        }
                    }
                }
            }
        }
    }
}
