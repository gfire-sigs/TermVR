package dev.gfire.termvr.ui.terminal

import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxPtyHandle
import dev.gfire.termvr.core.runtime.MuxStreamListener
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSplitNode
import dev.gfire.termvr.core.ui.TerminalSurfaceController
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Represents an open tab inside the Terminal screen backed by a split-pane tree.
 */
data class TerminalTabState(
    val tabId: String,
    val profileId: String,
    val title: String,
    val isDaemonLogs: Boolean,
    val splitRoot: TerminalSplitNode,
    val focusedPaneId: String,
    val exitedPanes: Set<String> = emptySet(),
) {
    fun allPaneIds(): List<String> = collectPaneIds(splitRoot)

    private fun collectPaneIds(node: TerminalSplitNode): List<String> = when (node) {
        is TerminalSplitNode.Leaf -> listOf(node.paneId)
        is TerminalSplitNode.Split -> collectPaneIds(node.first) + collectPaneIds(node.second)
    }
}

private data class PaneRuntime(
    val paneId: String,
    val tabId: String,
    val profileId: String,
    val session: NativeVtSession,
    val controller: TerminalSurfaceController,
    val isStreamZero: Boolean,
    var streamId: Long = -1L,
    var muxPtyHandle: MuxPtyHandle? = null,
    var rawPtyHandle: HostPtyHandle? = null,
    var rawReaderThread: Thread? = null,
    @Volatile var closed: Boolean = false,
)

/**
 * Owns active terminal tabs, split-pane trees, `NativeVtSession` instances, and mux stream bindings.
 */
class TerminalSessionCoordinator(
    private val runtimeGateway: SandboxRuntimeGateway,
    private val sessionFactory: (Int) -> NativeVtSession = { maxScrollback ->
        NativeVtSession(maxScrollbackLines = maxScrollback)
    },
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paneRuntimes = ConcurrentHashMap<String, PaneRuntime>()

    private val _tabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    val tabs: StateFlow<List<TerminalTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.tabId == tabId }) {
            _activeTabId.value = tabId
        }
    }

    fun focusPane(tabId: String, paneId: String) {
        _tabs.update { current ->
            current.map { tab ->
                if (tab.tabId == tabId) tab.copy(focusedPaneId = paneId) else tab
            }
        }
    }

    fun activeController(): TerminalSurfaceController? {
        val activeTab = _tabs.value.firstOrNull { it.tabId == _activeTabId.value } ?: return null
        return paneRuntimes[activeTab.focusedPaneId]?.controller
    }

    fun activeSession(): NativeVtSession? {
        val activeTab = _tabs.value.firstOrNull { it.tabId == _activeTabId.value } ?: return null
        return paneRuntimes[activeTab.focusedPaneId]?.session
    }

    fun applyPaletteToAll(palette: ColorPalette) {
        for (runtime in paneRuntimes.values) {
            if (!runtime.session.isClosed) {
                runtime.session.setPalette(palette)
                runtime.controller.notifyPtyOutput()
            }
        }
    }

    /**
     * Ensures Stream-0 Daemon Logs (for daemon recipes) and an interactive shell tab are open for [profile].
     */
    fun ensureTabsForProfile(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ) {
        val existingTabs = _tabs.value.filter { it.profileId == profile.id }
        if (existingTabs.isNotEmpty()) {
            reattachExistingProfileTabs(profile, existingTabs)
            val preferred = existingTabs.firstOrNull { !it.isDaemonLogs } ?: existingTabs.first()
            _activeTabId.value = preferred.tabId
            return
        }

        if (profile.muxMode == MuxMode.STDIO_MUX && profile.initDaemonArgv.isNotEmpty()) {
            openDaemonLogsTab(profile, maxScrollbackLines, palette)
        }
        val shellTab = openNewShellTab(profile, maxScrollbackLines, palette)
        if (shellTab != null) {
            _activeTabId.value = shellTab.tabId
        }
    }

    /**
     * Re-attaches all open tabs of [profile] after a CRIU checkpoint restore.
     */
    fun reattachAfterResume(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ) {
        val existingTabs = _tabs.value.filter { it.profileId == profile.id }
        if (existingTabs.isEmpty()) {
            ensureTabsForProfile(profile, maxScrollbackLines, palette)
        } else {
            reattachExistingProfileTabs(profile, existingTabs)
        }
    }

    /**
     * Opens a new interactive shell tab for [profile] and focuses it.
     */
    fun openNewShellTab(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ): TerminalTabState? {
        val tabId = "tab-${UUID.randomUUID().toString().take(8)}"
        val paneId = "pane-${UUID.randomUUID().toString().take(8)}"
        val session = sessionFactory(maxScrollbackLines).apply {
            setPalette(palette)
        }
        val controller = TerminalSurfaceController()
        val runtime = PaneRuntime(
            paneId = paneId,
            tabId = tabId,
            profileId = profile.id,
            session = session,
            controller = controller,
            isStreamZero = false,
        )
        paneRuntimes[paneId] = runtime

        val shellIndex = _tabs.value.count { it.profileId == profile.id && !it.isDaemonLogs } + 1
        val tab = TerminalTabState(
            tabId = tabId,
            profileId = profile.id,
            title = "${profile.displayName} #$shellIndex",
            isDaemonLogs = false,
            splitRoot = TerminalSplitNode.Leaf(paneId, session),
            focusedPaneId = paneId,
        )
        _tabs.update { it + tab }
        _activeTabId.value = tabId

        bindInteractivePaneStream(profile, runtime)
        return tab
    }

    /**
     * Splits the currently focused pane inside [tabId] along [direction].
     */
    fun splitFocusedPane(
        tabId: String,
        direction: SplitDirection,
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ) {
        if (profile.muxMode != MuxMode.STDIO_MUX) {
            _lastError.value = "Split panes require STDIO_MUX mode"
            return
        }
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        if (tab.isDaemonLogs) {
            _lastError.value = "Cannot split Stream-0 Daemon Logs tab"
            return
        }
        val newPaneId = "pane-${UUID.randomUUID().toString().take(8)}"
        val newSession = sessionFactory(maxScrollbackLines).apply {
            setPalette(palette)
        }
        val newController = TerminalSurfaceController()
        val newRuntime = PaneRuntime(
            paneId = newPaneId,
            tabId = tabId,
            profileId = profile.id,
            session = newSession,
            controller = newController,
            isStreamZero = false,
        )
        paneRuntimes[newPaneId] = newRuntime

        val updatedRoot = splitTreeLeaf(
            node = tab.splitRoot,
            targetPaneId = tab.focusedPaneId,
            newLeaf = TerminalSplitNode.Leaf(newPaneId, newSession),
            direction = direction,
        )
        _tabs.update { list ->
            list.map {
                if (it.tabId == tabId) {
                    it.copy(splitRoot = updatedRoot, focusedPaneId = newPaneId)
                } else {
                    it
                }
            }
        }
        bindInteractivePaneStream(profile, newRuntime)
    }

    /**
     * Closes a single pane or its owning tab when no sibling panes remain.
     */
    fun closePane(tabId: String, paneId: String) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        val remaining = removeLeafFromTree(tab.splitRoot, paneId)
        disposePaneRuntime(paneId)
        if (remaining == null) {
            closeTabInternal(tabId)
            return
        }
        val remainingIds = collectIds(remaining)
        val nextFocus = if (tab.focusedPaneId == paneId) {
            remainingIds.first()
        } else {
            tab.focusedPaneId
        }
        _tabs.update { list ->
            list.map {
                if (it.tabId == tabId) {
                    it.copy(
                        splitRoot = remaining,
                        focusedPaneId = nextFocus,
                        exitedPanes = it.exitedPanes - paneId,
                    )
                } else {
                    it
                }
            }
        }
    }

    /**
     * Closes a tab and disposes every `NativeVtSession` and mux stream inside it.
     */
    fun closeTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        for (paneId in tab.allPaneIds()) {
            disposePaneRuntime(paneId)
        }
        closeTabInternal(tabId)
    }

    /**
     * Closes every tab belonging to [profileId] after sandbox termination.
     */
    fun closeProfileTabs(profileId: String) {
        val matching = _tabs.value.filter { it.profileId == profileId }
        for (tab in matching) {
            closeTab(tab.tabId)
        }
    }

    /**
     * Sends host keyboard or paste bytes to the backing PTY or Stream-0 pipe for [paneId].
     */
    fun sendPtyBytes(paneId: String, bytes: ByteArray) {
        val runtime = paneRuntimes[paneId] ?: return
        if (runtime.closed) return
        runtimeGateway.markProfileActivity(runtime.profileId)
        ioScope.launch {
            runCatching {
                when {
                    runtime.isStreamZero -> runtimeGateway.getMuxClient(runtime.profileId)?.writeStream0Stdin(bytes)
                    runtime.muxPtyHandle != null -> runtime.muxPtyHandle?.writeInput(bytes)
                    else -> runtime.rawPtyHandle?.write(bytes)
                }
            }.onFailure { err ->
                _lastError.value = err.message ?: "PTY write failed"
            }
        }
    }

    /**
     * Propagates grid size changes to the active guest PTY stream.
     */
    fun notifyGridResized(
        paneId: String,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        val runtime = paneRuntimes[paneId] ?: return
        if (runtime.closed || runtime.isStreamZero) return
        ioScope.launch {
            runCatching {
                val mux = runtime.muxPtyHandle
                if (mux != null) {
                    mux.resize(rows = rows, cols = cols, xpixel = widthPx, ypixel = heightPx)
                } else if (runtime.rawPtyHandle != null) {
                    runtimeGateway.resizeRawPty(
                        profileId = runtime.profileId,
                        rows = rows,
                        cols = cols,
                        xpixel = widthPx,
                        ypixel = heightPx,
                    )
                }
            }
        }
    }

    private fun openDaemonLogsTab(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ) {
        val tabId = "daemon-${profile.id}"
        val paneId = "daemon-pane-${profile.id}"
        val session = sessionFactory(maxScrollbackLines).apply {
            setPalette(palette)
        }
        val controller = TerminalSurfaceController()
        val runtime = PaneRuntime(
            paneId = paneId,
            tabId = tabId,
            profileId = profile.id,
            session = session,
            controller = controller,
            isStreamZero = true,
            streamId = 0L,
        )
        paneRuntimes[paneId] = runtime

        val tab = TerminalTabState(
            tabId = tabId,
            profileId = profile.id,
            title = "Daemon Logs",
            isDaemonLogs = true,
            splitRoot = TerminalSplitNode.Leaf(paneId, session),
            focusedPaneId = paneId,
        )
        _tabs.update { listOf(tab) + it }
        bindStreamZero(profile.id, runtime)
    }

    private fun bindStreamZero(profileId: String, runtime: PaneRuntime) {
        val mux = runtimeGateway.getMuxClient(profileId) ?: return
        mux.setStreamListener(0L, createStreamListener(runtime))
    }

    private fun bindInteractivePaneStream(profile: SandboxProfile, runtime: PaneRuntime) {
        if (profile.muxMode == MuxMode.RAW_PTY) {
            val rawPty = runtimeGateway.getRawPtyHandle(profile.id)
            if (rawPty == null) {
                appendTerminalNotice(runtime, "\r\n[RAW PTY handle unavailable]\r\n")
                return
            }
            runtime.rawPtyHandle = rawPty
            val reader = Thread({
                val buf = ByteArray(8192)
                while (!runtime.closed) {
                    val count = runCatching { rawPty.read(buf) }.getOrDefault(-1)
                    if (count <= 0) break
                    if (!runtime.session.isClosed) {
                        runtime.session.writePtyBytes(buf, 0, count)
                        runtime.controller.notifyPtyOutput()
                    }
                }
            }, "termvr-rawpty-${runtime.paneId}").apply {
                isDaemon = true
                start()
            }
            runtime.rawReaderThread = reader
            return
        }

        val mux = runtimeGateway.getMuxClient(profile.id)
        if (mux == null) {
            appendTerminalNotice(runtime, "\r\n[Sandbox mux stream is unavailable]\r\n")
            return
        }
        ioScope.launch {
            runCatching {
                val listener = createStreamListener(runtime)
                val cols = runtime.session.cols.coerceAtLeast(2)
                val rows = runtime.session.rows.coerceAtLeast(2)
                val pty = mux.openPty(
                    argv = profile.defaultShellArgv,
                    cwd = "/workspace",
                    env = profile.envVars,
                    rows = rows,
                    cols = cols,
                    listener = listener,
                )
                runtime.streamId = pty.streamId
                runtime.muxPtyHandle = pty
            }.onFailure { err ->
                _lastError.value = err.message ?: "Failed to open PTY session"
                appendTerminalNotice(runtime, "\r\n[Failed to open PTY: ${err.message}]\r\n")
            }
        }
    }

    private fun reattachExistingProfileTabs(
        profile: SandboxProfile,
        existingTabs: List<TerminalTabState>,
    ) {
        val mux = runtimeGateway.getMuxClient(profile.id) ?: return
        for (tab in existingTabs) {
            for (paneId in tab.allPaneIds()) {
                val runtime = paneRuntimes[paneId] ?: continue
                if (runtime.closed) continue
                if (runtime.isStreamZero) {
                    bindStreamZero(profile.id, runtime)
                } else if (runtime.streamId >= 1L) {
                    runtime.muxPtyHandle = mux.attachPtyHandle(
                        streamId = runtime.streamId,
                        listener = createStreamListener(runtime),
                    )
                } else {
                    bindInteractivePaneStream(profile, runtime)
                }
            }
        }
    }

    private fun createStreamListener(runtime: PaneRuntime): MuxStreamListener = object : MuxStreamListener {
        override fun onData(streamId: Long, bytes: ByteArray) {
            if (runtime.closed || runtime.session.isClosed) return
            runtime.session.writePtyBytes(bytes)
            runtime.controller.notifyPtyOutput()
        }

        override fun onExit(streamId: Long, exitCode: Int) {
            if (runtime.closed) return
            appendTerminalNotice(runtime, "\r\n[Process exited with status $exitCode]\r\n")
            runtime.controller.notifySessionClosed(runtime.session.nativeHandle)
            _tabs.update { list ->
                list.map { tab ->
                    if (tab.tabId == runtime.tabId) {
                        tab.copy(exitedPanes = tab.exitedPanes + runtime.paneId)
                    } else {
                        tab
                    }
                }
            }
        }
    }

    private fun appendTerminalNotice(runtime: PaneRuntime, text: String) {
        if (runtime.session.isClosed) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        runtime.session.writePtyBytes(bytes)
        runtime.controller.notifyPtyOutput()
    }

    private fun disposePaneRuntime(paneId: String) {
        val runtime = paneRuntimes.remove(paneId) ?: return
        runtime.closed = true
        ioScope.launch {
            runCatching {
                val muxClient = runtimeGateway.getMuxClient(runtime.profileId)
                if (runtime.isStreamZero) {
                    muxClient?.setStreamListener(0L, null)
                } else {
                    runtime.muxPtyHandle?.close()
                }
            }
            runtime.rawReaderThread?.interrupt()
            if (!runtime.session.isClosed) {
                runtime.session.close()
            }
        }
    }

    private fun closeTabInternal(tabId: String) {
        val remainingTabs = _tabs.value.filterNot { it.tabId == tabId }
        _tabs.value = remainingTabs
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remainingTabs.firstOrNull { !it.isDaemonLogs }?.tabId
                ?: remainingTabs.firstOrNull()?.tabId
        }
    }

    private fun splitTreeLeaf(
        node: TerminalSplitNode,
        targetPaneId: String,
        newLeaf: TerminalSplitNode.Leaf,
        direction: SplitDirection,
    ): TerminalSplitNode {
        return when (node) {
            is TerminalSplitNode.Leaf -> {
                if (node.paneId == targetPaneId) {
                    TerminalSplitNode.Split(
                        direction = direction,
                        ratio = 0.5f,
                        first = node,
                        second = newLeaf,
                    )
                } else {
                    node
                }
            }

            is TerminalSplitNode.Split -> {
                node.copy(
                    first = splitTreeLeaf(node.first, targetPaneId, newLeaf, direction),
                    second = splitTreeLeaf(node.second, targetPaneId, newLeaf, direction),
                )
            }
        }
    }

    private fun removeLeafFromTree(node: TerminalSplitNode, targetPaneId: String): TerminalSplitNode? {
        return when (node) {
            is TerminalSplitNode.Leaf -> if (node.paneId == targetPaneId) null else node
            is TerminalSplitNode.Split -> {
                val nextFirst = removeLeafFromTree(node.first, targetPaneId)
                val nextSecond = removeLeafFromTree(node.second, targetPaneId)
                when {
                    nextFirst == null && nextSecond == null -> null
                    nextFirst == null -> nextSecond
                    nextSecond == null -> nextFirst
                    else -> node.copy(first = nextFirst, second = nextSecond)
                }
            }
        }
    }

    private fun collectIds(node: TerminalSplitNode): List<String> = when (node) {
        is TerminalSplitNode.Leaf -> listOf(node.paneId)
        is TerminalSplitNode.Split -> collectIds(node.first) + collectIds(node.second)
    }
}
