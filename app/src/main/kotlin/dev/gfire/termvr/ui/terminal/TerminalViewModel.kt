package dev.gfire.termvr.ui.terminal

import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.ui.HostShortcutAction
import dev.gfire.termvr.core.ui.ModifierLatchMachine
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSurfaceController
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.VtSearchMatch
import dev.gfire.termvr.runtime.ClipboardReadPrompt
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
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

/**
 * UI state for the multi-tab split-pane Terminal screen and session drawer.
 */
data class TerminalUiState(
    val tabs: List<TerminalTabState> = emptyList(),
    val activeTabId: String? = null,
    val profiles: List<SandboxProfile> = emptyList(),
    val runStates: Map<String, SandboxRunState> = emptyMap(),
    val wakeLockRemainingSeconds: Long? = null,
    val fontConfig: FontConfig = FontConfig(),
    val keyBarConfig: KeyBarConfig = KeyBarConfig(),
    val appSettings: GlobalAppSettings = GlobalAppSettings(),
    val palette: ColorPalette = ColorPalette.DEFAULT_DARK,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val searchUseRegex: Boolean = false,
    val searchCaseSensitive: Boolean = false,
    val searchMatches: List<VtSearchMatch> = emptyList(),
    val selectedMatchIndex: Int = 0,
    val keyBarCollapsed: Boolean = false,
    val clipboardPrompt: ClipboardReadPrompt? = null,
    val errorMessage: String? = null,
) {
    val activeTab: TerminalTabState?
        get() = tabs.firstOrNull { it.tabId == activeTabId } ?: tabs.firstOrNull()
}

private data class TerminalSearchUi(
    val visible: Boolean = false,
    val query: String = "",
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val matches: List<VtSearchMatch> = emptyList(),
    val selectedIndex: Int = 0,
)

/**
 * Coordinates terminal tabs, split panes, scrollback search, key bar, and host shortcuts.
 */
class TerminalViewModel(
    private val profileRepository: ProfileRepository,
    private val runtimeGateway: SandboxRuntimeGateway,
    private val sessionCoordinator: TerminalSessionCoordinator,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val latchMachine = ModifierLatchMachine()

    private val searchUi = MutableStateFlow(TerminalSearchUi())
    private val keyBarCollapsed = MutableStateFlow(false)

    private val sessionPart = combine(
        sessionCoordinator.tabs,
        sessionCoordinator.activeTabId,
        sessionCoordinator.lastError,
        runtimeGateway.runStates,
        runtimeGateway.wakeLockRemainingSeconds,
    ) { tabs, activeId, error, runStates, wakeRemaining ->
        SessionFlowPart(tabs, activeId, error, runStates, wakeRemaining)
    }

    private val prefsPart = combine(
        profileRepository.observeProfiles(),
        profileRepository.observeFontConfig(),
        profileRepository.observeKeyBarConfig(),
        profileRepository.observeAppSettings(),
    ) { profiles, fontConfig, keyBarConfig, settings ->
        PrefsFlowPart(profiles, fontConfig, keyBarConfig, settings)
    }

    val uiState: StateFlow<TerminalUiState> = combine(
        sessionPart,
        prefsPart,
        searchUi,
        keyBarCollapsed,
        runtimeGateway.clipboardReadPrompt,
    ) { session, prefs, search, collapsed, clipboardPrompt ->
        val palette = ColorPalette.byId(prefs.settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK
        TerminalUiState(
            tabs = session.tabs,
            activeTabId = session.activeId,
            profiles = prefs.profiles,
            runStates = session.runStates,
            wakeLockRemainingSeconds = session.wakeRemaining,
            fontConfig = prefs.fontConfig,
            keyBarConfig = prefs.keyBarConfig,
            appSettings = prefs.settings,
            palette = palette,
            searchVisible = search.visible,
            searchQuery = search.query,
            searchUseRegex = search.useRegex,
            searchCaseSensitive = search.caseSensitive,
            searchMatches = search.matches,
            selectedMatchIndex = search.selectedIndex,
            keyBarCollapsed = collapsed,
            clipboardPrompt = clipboardPrompt,
            errorMessage = session.error,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = TerminalUiState(),
    )

    init {
        scope.launch {
            profileRepository.observeAppSettings().collect { settings ->
                sessionCoordinator.applyPaletteToAll(
                    ColorPalette.byId(settings.selectedPaletteId) ?: ColorPalette.DEFAULT_DARK,
                )
            }
        }
    }

    fun activeController(): TerminalSurfaceController? = sessionCoordinator.activeController()

    fun activeSession(): NativeVtSession? = sessionCoordinator.activeSession()

    fun selectTab(tabId: String) {
        sessionCoordinator.selectTab(tabId)
    }

    fun focusPane(tabId: String, paneId: String) {
        sessionCoordinator.focusPane(tabId, paneId)
    }

    fun sendPtyBytes(paneId: String, bytes: ByteArray) {
        sessionCoordinator.sendPtyBytes(paneId, bytes)
    }

    fun onGridResized(paneId: String, cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        sessionCoordinator.notifyGridResized(paneId, cols, rows, widthPx, heightPx)
    }

    fun openNewTabForActiveSandbox() {
        val activeTab = uiState.value.activeTab ?: return
        val profile = uiState.value.profiles.firstOrNull { it.id == activeTab.profileId } ?: return
        openNewTabForProfile(profile)
    }

    fun openNewTabForProfile(profile: SandboxProfile) {
        sessionCoordinator.openNewShellTab(
            profile = profile,
            maxScrollbackLines = uiState.value.appSettings.defaultScrollbackLines,
            palette = uiState.value.palette,
        )
    }

    fun closeActiveTab() {
        val tabId = uiState.value.activeTab?.tabId ?: return
        sessionCoordinator.closeTab(tabId)
    }

    fun closeTab(tabId: String) {
        sessionCoordinator.closeTab(tabId)
    }

    fun splitActivePane(direction: SplitDirection) {
        val activeTab = uiState.value.activeTab ?: return
        val profile = uiState.value.profiles.firstOrNull { it.id == activeTab.profileId } ?: return
        sessionCoordinator.splitFocusedPane(
            tabId = activeTab.tabId,
            direction = direction,
            profile = profile,
            maxScrollbackLines = uiState.value.appSettings.defaultScrollbackLines,
            palette = uiState.value.palette,
        )
    }

    fun closeFocusedPane() {
        val activeTab = uiState.value.activeTab ?: return
        sessionCoordinator.closePane(activeTab.tabId, activeTab.focusedPaneId)
    }

    fun switchToOrStartProfile(profile: SandboxProfile) {
        scope.launch {
            val state = uiState.value.runStates[profile.id] ?: SandboxRunState.STOPPED
            if (state != SandboxRunState.RUNNING) {
                runtimeGateway.startOrResumeSandbox(profile)
            }
            sessionCoordinator.ensureTabsForProfile(
                profile = profile,
                maxScrollbackLines = uiState.value.appSettings.defaultScrollbackLines,
                palette = uiState.value.palette,
            )
        }
    }

    fun freezeProfile(profileId: String) {
        scope.launch {
            runtimeGateway.freezeSandbox(profileId)
        }
    }

    fun stopProfile(profileId: String) {
        scope.launch {
            runtimeGateway.stopSandbox(profileId)
            sessionCoordinator.closeProfileTabs(profileId)
        }
    }

    fun acquireWakeLock(minutes: Int) {
        scope.launch {
            runtimeGateway.acquireWakeLock(minutes)
        }
    }

    fun releaseWakeLock() {
        scope.launch {
            runtimeGateway.releaseWakeLock()
        }
    }

    fun toggleKeyBarCollapsed() {
        keyBarCollapsed.value = !keyBarCollapsed.value
    }

    fun updateFontConfig(updated: FontConfig) {
        scope.launch {
            profileRepository.updateFontConfig(updated.withClampedMetrics())
        }
    }

    fun openSearch() {
        searchUi.value = searchUi.value.copy(visible = true)
    }

    fun closeSearch() {
        searchUi.value = TerminalSearchUi()
        activeSession()?.setScrollbackOffset(0)
        activeController()?.notifyPtyOutput()
    }

    fun updateSearchQuery(query: String) {
        val current = searchUi.value.copy(query = query)
        runSearch(current)
    }

    fun toggleSearchRegex() {
        val current = searchUi.value.copy(useRegex = !searchUi.value.useRegex)
        runSearch(current)
    }

    fun toggleSearchCaseSensitive() {
        val current = searchUi.value.copy(caseSensitive = !searchUi.value.caseSensitive)
        runSearch(current)
    }

    fun nextSearchMatch() {
        val current = searchUi.value
        if (current.matches.isEmpty()) return
        val nextIdx = (current.selectedIndex + 1) % current.matches.size
        jumpToMatch(current, nextIdx)
    }

    fun prevSearchMatch() {
        val current = searchUi.value
        if (current.matches.isEmpty()) return
        val prevIdx = if (current.selectedIndex <= 0) current.matches.lastIndex else current.selectedIndex - 1
        jumpToMatch(current, prevIdx)
    }

    fun handleHostShortcut(action: HostShortcutAction) {
        when (action) {
            HostShortcutAction.NEW_TAB -> openNewTabForActiveSandbox()
            HostShortcutAction.CLOSE_TAB -> closeActiveTab()
            // TerminalSurfaceView handles COPY, PASTE, SEARCH, ZOOM_IN, and ZOOM_OUT directly.
            HostShortcutAction.COPY,
            HostShortcutAction.PASTE,
            HostShortcutAction.SEARCH,
            HostShortcutAction.ZOOM_IN,
            HostShortcutAction.ZOOM_OUT,
            -> Unit
        }
    }

    fun resolveClipboardPrompt(allow: Boolean) {
        runtimeGateway.resolveClipboardPrompt(allow)
    }

    fun openExternalUrl(url: String) {
        runtimeGateway.openUrl(url)
    }

    fun shareSelectionText(text: String) {
        runtimeGateway.shareText(text)
    }

    fun clearError() {
        sessionCoordinator.clearError()
    }

    private fun runSearch(draft: TerminalSearchUi) {
        val session = activeSession()
        if (session == null || draft.query.isEmpty()) {
            searchUi.value = draft.copy(matches = emptyList(), selectedIndex = 0)
            return
        }
        val matches = runCatching {
            session.searchScrollback(
                query = draft.query,
                useRegex = draft.useRegex,
                caseSensitive = draft.caseSensitive,
            )
        }.getOrDefault(emptyList())
        val updated = draft.copy(matches = matches, selectedIndex = 0)
        if (matches.isEmpty()) {
            searchUi.value = updated
        } else {
            jumpToMatch(updated, 0)
        }
    }

    private fun jumpToMatch(current: TerminalSearchUi, index: Int) {
        val session = activeSession() ?: return
        val match = current.matches.getOrNull(index) ?: return
        val historyCount = session.getScrollbackCount()
        val offsetFromBottom = (historyCount - match.absoluteLine).coerceIn(0, historyCount)
        session.setScrollbackOffset(offsetFromBottom)
        activeController()?.notifyPtyOutput()
        searchUi.value = current.copy(selectedIndex = index)
    }

    fun close() {
        scope.cancel()
    }

    private data class SessionFlowPart(
        val tabs: List<TerminalTabState>,
        val activeId: String?,
        val error: String?,
        val runStates: Map<String, SandboxRunState>,
        val wakeRemaining: Long?,
    )

    private data class PrefsFlowPart(
        val profiles: List<SandboxProfile>,
        val fontConfig: FontConfig,
        val keyBarConfig: KeyBarConfig,
        val settings: GlobalAppSettings,
    )
}
