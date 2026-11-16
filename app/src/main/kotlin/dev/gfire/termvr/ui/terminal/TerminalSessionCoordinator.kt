package dev.gfire.termvr.ui.terminal

import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxPtyHandle
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.MuxStreamListener
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSplitNode
import dev.gfire.termvr.core.ui.TerminalSurfaceController
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
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
    val sessionTitle: String? = null,
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
    @Volatile var streamId: Long = -1L,
    @Volatile var muxPtyHandle: MuxPtyHandle? = null,
    @Volatile var rawPtyHandle: HostPtyHandle? = null,
    @Volatile var boundMuxRef: MuxSessionClient? = null,
    var rawReaderThread: Thread? = null,
    @Volatile var desiredCols: Int = 0,
    @Volatile var desiredRows: Int = 0,
    @Volatile var desiredWidthPx: Int = 0,
    @Volatile var desiredHeightPx: Int = 0,
    val streamBindingInFlight: AtomicBoolean = AtomicBoolean(false),
    val pendingPtyReplies: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue(),
    val replySendLock: Any = Any(),
    @Volatile var closed: Boolean = false,
)

private data class GridDims(
    val cols: Int,
    val rows: Int,
    val widthPx: Int,
    val heightPx: Int,
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
    private val tabControllers = ConcurrentHashMap<String, TerminalSurfaceController>()
    private val boundMuxByProfile = ConcurrentHashMap<String, MuxSessionClient>()
    private val profileReattachLocks = ConcurrentHashMap<String, Any>()
    private val lastKnownGridByProfile = ConcurrentHashMap<String, GridDims>()

    private val _tabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    val tabs: StateFlow<List<TerminalTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun clearError() {
        _lastError.value = null
    }

    fun reportError(message: String) {
        _lastError.value = message
    }

    fun needsReattach(profileId: String): Boolean {
        val hasTabs = _tabs.value.any { it.profileId == profileId }
        if (!hasTabs) return false
        val currentMux = runtimeGateway.getMuxClient(profileId) ?: return false
        if (currentMux.isClosed) return false
        val boundMux = boundMuxByProfile[profileId]
        return boundMux == null || boundMux !== currentMux || boundMux.isClosed
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
        val activeTab = _tabs.value.firstOrNull { it.tabId == _activeTabId.value }
            ?: _tabs.value.firstOrNull()
            ?: return null
        return tabControllers[activeTab.tabId] ?: paneRuntimes[activeTab.focusedPaneId]?.controller
    }

    fun activeSession(): NativeVtSession? {
        val activeTab = _tabs.value.firstOrNull { it.tabId == _activeTabId.value }
            ?: _tabs.value.firstOrNull()
            ?: return null
        return paneRuntimes[activeTab.focusedPaneId]?.session
    }

    private fun controllerForTab(tabId: String): TerminalSurfaceController =
        tabControllers.computeIfAbsent(tabId) { TerminalSurfaceController() }

    private fun reattachLockFor(profileId: String): Any =
        profileReattachLocks.computeIfAbsent(profileId) { Any() }

    fun applyPaletteToAll(palette: ColorPalette) {
        for (runtime in paneRuntimes.values) {
            if (!runtime.session.isClosed) {
                runtime.session.setPalette(palette)
                runtime.controller.notifyPtyOutput()
            }
        }
    }

    /**
     * Ensures Stream-0 Daemon Logs (for daemon recipes) and optionally an interactive shell tab for [profile].
     */
    fun ensureTabsForProfile(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
        openInitialShell: Boolean = true,
    ) {
        val existingTabs = _tabs.value.filter { it.profileId == profile.id }
        if (existingTabs.isNotEmpty()) {
            reattachExistingProfileTabs(profile, existingTabs)
            val preferred = if (openInitialShell) {
                existingTabs.firstOrNull { !it.isDaemonLogs } ?: existingTabs.first()
            } else {
                existingTabs.firstOrNull { it.isDaemonLogs } ?: existingTabs.first()
            }
            _activeTabId.value = preferred.tabId
            return
        }

        var daemonTab: TerminalTabState? = null
        if (profile.muxMode == MuxMode.STDIO_MUX && profile.initDaemonArgv.isNotEmpty()) {
            daemonTab = openDaemonLogsTab(profile, maxScrollbackLines, palette)
        }
        if (openInitialShell) {
            val shellTab = openNewShellTab(profile, maxScrollbackLines, palette)
            if (shellTab != null) {
                _activeTabId.value = shellTab.tabId
            }
        } else if (daemonTab != null) {
            _activeTabId.value = daemonTab.tabId
        }
    }

    fun hasShellTab(profileId: String): Boolean =
        _tabs.value.any { it.profileId == profileId && !it.isDaemonLogs }

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
        val controller = controllerForTab(tabId)
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
            title = "Shell $shellIndex",
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
        val sharedController = controllerForTab(tabId)
        val newRuntime = PaneRuntime(
            paneId = newPaneId,
            tabId = tabId,
            profileId = profile.id,
            session = newSession,
            controller = sharedController,
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
        if (remaining == null) {
            closeTabInternal(tabId)
            disposePaneRuntime(paneId)
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
        disposePaneRuntime(paneId)
    }

    /**
     * Closes a tab and disposes every `NativeVtSession` and mux stream inside it.
     */
    fun closeTab(tabId: String) {
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        val paneIds = tab.allPaneIds()
        closeTabInternal(tabId)
        for (paneId in paneIds) {
            disposePaneRuntime(paneId)
        }
    }

    /**
     * Closes every tab belonging to [profileId] after sandbox termination.
     */
    fun closeProfileTabs(profileId: String) {
        boundMuxByProfile.remove(profileId)
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
        if (runtimeGateway.runStates.value[runtime.profileId] != SandboxRunState.RUNNING) {
            return
        }
        runtimeGateway.markProfileActivity(runtime.profileId)
        ioScope.launch {
            if (runtime.closed || runtimeGateway.runStates.value[runtime.profileId] != SandboxRunState.RUNNING) {
                return@launch
            }
            runCatching {
                when {
                    runtime.isStreamZero -> runtimeGateway.getMuxClient(runtime.profileId)?.writeStream0Stdin(bytes)
                    runtime.muxPtyHandle != null -> {
                        val currentMux = runtimeGateway.getMuxClient(runtime.profileId)
                        val bound = boundMuxByProfile[runtime.profileId]
                        if (currentMux != null && !currentMux.isClosed && bound !== currentMux &&
                            runtime.streamId >= 1L && currentMux.isStreamOpen(runtime.streamId)
                        ) {
                            boundMuxByProfile[runtime.profileId] = currentMux
                            runtime.muxPtyHandle = currentMux.attachPtyHandle(
                                streamId = runtime.streamId,
                                listener = createStreamListener(runtime),
                            )
                        }
                        runtime.muxPtyHandle?.writeInput(bytes)
                    }
                    else -> runtime.rawPtyHandle?.write(bytes)
                }
            }.onFailure { err ->
                if (runtimeGateway.runStates.value[runtime.profileId] == SandboxRunState.RUNNING) {
                    _lastError.value = err.message ?: "PTY write failed"
                }
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
        if (runtime.closed) return
        val clampedCols = cols.coerceAtLeast(2)
        val clampedRows = rows.coerceAtLeast(2)
        val clampedWidthPx = widthPx.coerceAtLeast(0)
        val clampedHeightPx = heightPx.coerceAtLeast(0)
        runtime.desiredCols = clampedCols
        runtime.desiredRows = clampedRows
        runtime.desiredWidthPx = clampedWidthPx
        runtime.desiredHeightPx = clampedHeightPx
        lastKnownGridByProfile[runtime.profileId] = GridDims(
            cols = clampedCols,
            rows = clampedRows,
            widthPx = clampedWidthPx,
            heightPx = clampedHeightPx,
        )
        if (!runtime.session.isClosed &&
            (runtime.session.cols != clampedCols || runtime.session.rows != clampedRows)
        ) {
            val cw = if (clampedWidthPx > 0) max(1, clampedWidthPx / clampedCols) else 10
            val ch = if (clampedHeightPx > 0) max(1, clampedHeightPx / clampedRows) else 20
            runtime.session.resizeGrid(clampedCols, clampedRows, cw, ch)
        }
        if (runtime.isStreamZero) return
        if (runtimeGateway.runStates.value[runtime.profileId] != SandboxRunState.RUNNING) {
            return
        }
        ioScope.launch {
            if (runtime.closed || runtimeGateway.runStates.value[runtime.profileId] != SandboxRunState.RUNNING) {
                return@launch
            }
            sendPtyResize(
                runtime = runtime,
                rows = clampedRows,
                cols = clampedCols,
                widthPx = clampedWidthPx,
                heightPx = clampedHeightPx,
            )
        }
    }

    private fun openDaemonLogsTab(
        profile: SandboxProfile,
        maxScrollbackLines: Int,
        palette: ColorPalette,
    ): TerminalTabState {
        val tabId = "daemon-${profile.id}"
        val paneId = "daemon-pane-${profile.id}"
        val session = sessionFactory(maxScrollbackLines).apply {
            setPalette(palette)
        }
        val controller = controllerForTab(tabId)
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
        return tab
    }

    private fun bindStreamZero(profileId: String, runtime: PaneRuntime) {
        val mux = runtimeGateway.getMuxClient(profileId) ?: return
        if (mux.isClosed || runtime.closed) return
        boundMuxByProfile[profileId] = mux
        runtime.boundMuxRef = mux
        mux.setStreamListener(0L, createStreamListener(runtime))
    }

    private fun resolveInitialGridDims(runtime: PaneRuntime): GridDims {
        val fallback = lastKnownGridByProfile[runtime.profileId]
        val useFallback = runtime.desiredCols < 2 &&
            fallback != null &&
            runtime.session.cols == 80 &&
            runtime.session.rows == 24
        val cols = when {
            runtime.desiredCols >= 2 -> runtime.desiredCols
            useFallback -> fallback!!.cols.coerceAtLeast(2)
            else -> runtime.session.cols.coerceAtLeast(2)
        }
        val rows = when {
            runtime.desiredRows >= 2 -> runtime.desiredRows
            useFallback -> fallback!!.rows.coerceAtLeast(2)
            else -> runtime.session.rows.coerceAtLeast(2)
        }
        val widthPx = when {
            runtime.desiredWidthPx > 0 -> runtime.desiredWidthPx
            fallback != null && fallback.widthPx > 0 -> fallback.widthPx
            else -> 0
        }
        val heightPx = when {
            runtime.desiredHeightPx > 0 -> runtime.desiredHeightPx
            fallback != null && fallback.heightPx > 0 -> fallback.heightPx
            else -> 0
        }
        if (!runtime.session.isClosed && (runtime.session.cols != cols || runtime.session.rows != rows)) {
            val cw = if (widthPx > 0) max(1, widthPx / cols) else 10
            val ch = if (heightPx > 0) max(1, heightPx / rows) else 20
            runtime.session.resizeGrid(cols, rows, cw, ch)
        }
        return GridDims(cols = cols, rows = rows, widthPx = widthPx, heightPx = heightPx)
    }

    private fun sendPtyResize(
        runtime: PaneRuntime,
        rows: Int,
        cols: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        runCatching {
            val mux = runtime.muxPtyHandle
            val currentMux = runtimeGateway.getMuxClient(runtime.profileId)
            if (mux != null && (currentMux == null || !currentMux.isClosed)) {
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

    private fun enqueuePtyReplies(runtime: PaneRuntime, replies: ByteArray) {
        if (replies.isEmpty() || runtime.closed) return
        runtime.pendingPtyReplies.add(replies)
        ioScope.launch {
            flushPendingPtyReplies(runtime)
        }
    }

    private fun flushPendingPtyReplies(runtime: PaneRuntime) {
        if (runtime.closed) {
            runtime.pendingPtyReplies.clear()
            return
        }
        synchronized(runtime.replySendLock) {
            while (!runtime.closed) {
                val next = runtime.pendingPtyReplies.peek() ?: break
                val delivered = runCatching {
                    when {
                        runtime.isStreamZero -> {
                            val mux = runtimeGateway.getMuxClient(runtime.profileId) ?: return@runCatching false
                            if (mux.isClosed) return@runCatching false
                            mux.writeStream0Stdin(next)
                            true
                        }
                        runtime.muxPtyHandle != null -> {
                            runtime.muxPtyHandle?.writeInput(next)
                            true
                        }
                        runtime.rawPtyHandle != null -> {
                            runtime.rawPtyHandle?.write(next)
                            true
                        }
                        else -> false
                    }
                }.getOrDefault(false)
                if (!delivered) {
                    break
                }
                runtime.pendingPtyReplies.poll()
            }
        }
    }

    private fun attachRawPtyReader(runtime: PaneRuntime, rawPty: HostPtyHandle) {
        runtime.rawReaderThread?.interrupt()
        runtime.rawPtyHandle = rawPty
        val dims = resolveInitialGridDims(runtime)
        ioScope.launch {
            if (!runtime.closed) {
                runtimeGateway.resizeRawPty(
                    profileId = runtime.profileId,
                    rows = dims.rows,
                    cols = dims.cols,
                    xpixel = dims.widthPx,
                    ypixel = dims.heightPx,
                )
                flushPendingPtyReplies(runtime)
            }
        }
        val reader = Thread({
            val buf = ByteArray(8192)
            while (!runtime.closed) {
                val count = runCatching { rawPty.read(buf) }.getOrDefault(-1)
                if (count <= 0) break
                if (!runtime.session.isClosed) {
                    runtime.session.writePtyBytes(buf, 0, count)
                    val replies = runtime.session.drainPtyReplies()
                    if (replies.isNotEmpty()) {
                        enqueuePtyReplies(runtime, replies)
                    }
                    runtime.controller.notifyPtyOutput()
                }
            }
        }, "termvr-rawpty-${runtime.paneId}").apply {
            isDaemon = true
            start()
        }
        runtime.rawReaderThread = reader
    }

    private fun bindInteractivePaneStream(profile: SandboxProfile, runtime: PaneRuntime) {
        if (runtime.closed) return
        if (profile.muxMode == MuxMode.RAW_PTY) {
            val rawPty = runtimeGateway.getRawPtyHandle(profile.id)
            if (rawPty == null) {
                appendTerminalNotice(runtime, "\r\n[RAW PTY handle unavailable]\r\n")
                return
            }
            if (runtime.rawPtyHandle === rawPty && runtime.rawReaderThread?.isAlive == true) {
                return
            }
            attachRawPtyReader(runtime, rawPty)
            return
        }

        val mux = runtimeGateway.getMuxClient(profile.id)
        if (mux == null || mux.isClosed) {
            appendTerminalNotice(runtime, "\r\n[Sandbox mux stream is unavailable]\r\n")
            return
        }
        if (!runtime.streamBindingInFlight.compareAndSet(false, true)) {
            return
        }
        boundMuxByProfile[profile.id] = mux
        runtime.boundMuxRef = mux
        ioScope.launch {
            try {
                if (runtime.closed || mux.isClosed) return@launch
                val listener = createStreamListener(runtime)
                val openDims = resolveInitialGridDims(runtime)
                val pty = mux.openPty(
                    argv = profile.defaultShellArgv,
                    cwd = "/workspace",
                    env = profile.envVars,
                    rows = openDims.rows,
                    cols = openDims.cols,
                    xpixel = openDims.widthPx,
                    ypixel = openDims.heightPx,
                    listener = listener,
                )
                if (runtime.closed) {
                    runCatching { pty.close() }
                    return@launch
                }
                runtime.streamId = pty.streamId
                runtime.muxPtyHandle = pty
                val latestCols = runtime.desiredCols
                val latestRows = runtime.desiredRows
                val latestWidthPx = runtime.desiredWidthPx
                val latestHeightPx = runtime.desiredHeightPx
                if (latestCols >= 2 && latestRows >= 2 &&
                    (latestCols != openDims.cols ||
                        latestRows != openDims.rows ||
                        latestWidthPx != openDims.widthPx ||
                        latestHeightPx != openDims.heightPx)
                ) {
                    runCatching {
                        pty.resize(
                            rows = latestRows,
                            cols = latestCols,
                            xpixel = latestWidthPx,
                            ypixel = latestHeightPx,
                        )
                    }
                }
                flushPendingPtyReplies(runtime)
            } catch (err: Throwable) {
                if (runtime.boundMuxRef === mux) {
                    runtime.boundMuxRef = null
                }
                if (!runtime.closed) {
                    _lastError.value = err.message ?: "Failed to open PTY session"
                    appendTerminalNotice(runtime, "\r\n[Failed to open PTY: ${err.message}]\r\n")
                }
            } finally {
                runtime.streamBindingInFlight.set(false)
            }
        }
    }

    private fun reattachExistingProfileTabs(
        profile: SandboxProfile,
        existingTabs: List<TerminalTabState>,
    ) {
        synchronized(reattachLockFor(profile.id)) {
            if (profile.muxMode == MuxMode.RAW_PTY) {
                val rawPty = runtimeGateway.getRawPtyHandle(profile.id) ?: return
                for (tab in existingTabs) {
                    for (paneId in tab.allPaneIds()) {
                        val runtime = paneRuntimes[paneId] ?: continue
                        if (runtime.closed || runtime.isStreamZero) continue
                        if (runtime.rawPtyHandle === rawPty && runtime.rawReaderThread?.isAlive == true) continue
                        attachRawPtyReader(runtime, rawPty)
                    }
                }
                return
            }

            val mux = runtimeGateway.getMuxClient(profile.id) ?: return
            if (mux.isClosed) return
            boundMuxByProfile[profile.id] = mux
            for (tab in existingTabs) {
                for (paneId in tab.allPaneIds()) {
                    val runtime = paneRuntimes[paneId] ?: continue
                    if (runtime.closed) continue
                    if (runtime.boundMuxRef === mux && !mux.isClosed) continue
                    if (runtime.isStreamZero) {
                        bindStreamZero(profile.id, runtime)
                    } else if (runtime.streamId >= 1L && mux.isStreamOpen(runtime.streamId)) {
                        val handle = mux.attachPtyHandle(
                            streamId = runtime.streamId,
                            listener = createStreamListener(runtime),
                        )
                        runtime.muxPtyHandle = handle
                        runtime.boundMuxRef = mux
                        val dims = resolveInitialGridDims(runtime)
                        if (dims.cols >= 2 && dims.rows >= 2) {
                            runCatching {
                                handle.resize(
                                    rows = dims.rows,
                                    cols = dims.cols,
                                    xpixel = dims.widthPx,
                                    ypixel = dims.heightPx,
                                )
                            }
                        }
                        ioScope.launch {
                            flushPendingPtyReplies(runtime)
                        }
                    } else {
                        runtime.muxPtyHandle = null
                        runtime.streamId = -1L
                        bindInteractivePaneStream(profile, runtime)
                    }
                }
            }
        }
    }

    private fun createStreamListener(runtime: PaneRuntime): MuxStreamListener = object : MuxStreamListener {
        override fun onData(streamId: Long, bytes: ByteArray) {
            if (runtime.closed || runtime.session.isClosed) return
            runtime.session.writePtyBytes(bytes)
            val replies = runtime.session.drainPtyReplies()
            if (replies.isNotEmpty()) {
                enqueuePtyReplies(runtime, replies)
            }
            if (!runtime.isStreamZero) {
                val oscTitle = runtime.session.getWindowTitle().trim().takeIf { it.isNotEmpty() }
                if (oscTitle != null) {
                    _tabs.update { list ->
                        var changed = false
                        val updated = list.map { tab ->
                            if (tab.tabId == runtime.tabId &&
                                tab.focusedPaneId == runtime.paneId &&
                                tab.sessionTitle != oscTitle
                            ) {
                                changed = true
                                tab.copy(sessionTitle = oscTitle)
                            } else {
                                tab
                            }
                        }
                        if (changed) updated else list
                    }
                }
            }
            runtime.controller.notifyPtyOutput()
        }

        override fun onExit(streamId: Long, exitCode: Int) {
            if (runtime.closed) return
            appendTerminalNotice(runtime, "\r\n[Process exited with status $exitCode]\r\n")
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
        if (runtime.closed || runtime.session.isClosed) return
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        runtime.session.writePtyBytes(bytes)
        runtime.controller.notifyPtyOutput()
    }

    private fun disposePaneRuntime(paneId: String) {
        val runtime = paneRuntimes.remove(paneId) ?: return
        runtime.closed = true
        val handle = runtime.session.nativeHandle
        if (handle != 0L) {
            runtime.controller.notifySessionClosed(handle)
        }
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
            if (handle != 0L) {
                runtime.controller.detachSessionBlocking(handle, timeoutMs = 150L)
            }
            if (!runtime.session.isClosed) {
                runtime.session.close()
            }
        }
    }

    private fun closeTabInternal(tabId: String) {
        tabControllers.remove(tabId)
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
