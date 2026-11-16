package dev.gfire.termvr.core.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.KeySlotSpec
import dev.gfire.termvr.core.vt.BundledFontPack
import dev.gfire.termvr.core.vt.CellMetrics
import dev.gfire.termvr.core.vt.NativeGlRenderer
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.PaneRenderTarget
import dev.gfire.termvr.core.vt.RendererShaderPack
import dev.gfire.termvr.core.vt.VtDetectedLink
import dev.gfire.termvr.core.vt.VtKey
import dev.gfire.termvr.core.vt.VtKeyAction
import dev.gfire.termvr.core.vt.VtKeyEvent
import dev.gfire.termvr.core.vt.VtLinkKind
import dev.gfire.termvr.core.vt.VtMouseAction
import dev.gfire.termvr.core.vt.VtMouseButton
import dev.gfire.termvr.core.vt.VtMouseEvent
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Snapshot of active terminal selection coordinates for floating handles and action chips.
 */
data class TerminalSelectionUiState(
    val active: Boolean = false,
    val paneId: String = "",
    val paneXPx: Int = 0,
    val paneYPx: Int = 0,
    val paneWidthPx: Int = 0,
    val paneHeightPx: Int = 0,
    val cellWidthPx: Int = 10,
    val cellHeightPx: Int = 20,
    val startRow: Int = 0,
    val startCol: Int = 0,
    val endRow: Int = 0,
    val endCol: Int = 0,
)

/**
 * Imperative bridge exposed to Compose screens and [AuxiliaryKeyBar].
 */
class TerminalSurfaceController {
    private val viewRef = AtomicReference<TerminalSurfaceView?>(null)

    internal fun attach(view: TerminalSurfaceView) {
        viewRef.set(view)
    }

    internal fun detach(view: TerminalSurfaceView) {
        viewRef.compareAndSet(view, null)
    }

    fun notifyPtyOutput() {
        viewRef.get()?.notifyPtyOutput()
    }

    fun notifySessionClosed(sessionHandle: Long) {
        viewRef.get()?.notifySessionClosed(sessionHandle)
    }

    fun detachSessionBlocking(sessionHandle: Long, timeoutMs: Long = 150L) {
        viewRef.get()?.detachSessionBlocking(sessionHandle, timeoutMs)
    }

    fun flushImeAndRestartInput() {
        viewRef.get()?.flushImeAndRestartInput()
    }

    fun emitKeySlot(slot: KeySlotSpec) {
        viewRef.get()?.emitKeySlot(slot)
    }

    fun scrubCursor(steps: Int) {
        viewRef.get()?.scrubCursor(steps)
    }

    fun scrollHalfScreenUp(lines: Int) {
        viewRef.get()?.scrollHalfScreenUp(lines)
    }

    fun copySelection(): String? = viewRef.get()?.copySelectionToClipboard()

    fun pasteFromClipboard() {
        viewRef.get()?.pasteFromClipboard()
    }

    fun clearSelection() {
        viewRef.get()?.clearSelection()
    }

    fun requestShowKeyboard() {
        viewRef.get()?.requestShowKeyboard()
    }
}

/**
 * Single OpenGL ES 3.0 SurfaceView rendering all split panes of the active terminal tab.
 */
class TerminalSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var onSendPtyBytes: (paneId: String, bytes: ByteArray) -> Unit = { _, _ -> }
    var onPaneFocused: (paneId: String) -> Unit = {}
    var onGridResized: (paneId: String, cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit = { _, _, _, _, _ -> }
    var onFontConfigChanged: (FontConfig) -> Unit = {}
    var onHostShortcut: (HostShortcutAction) -> Unit = {}
    var onSelectionChanged: (TerminalSelectionUiState) -> Unit = {}
    var onLinkDetected: (VtDetectedLink?) -> Unit = {}
    var onShareText: (String) -> Unit = {}
    var onSearchRequested: () -> Unit = {}
    var onUnsafePastePrompt: (paneId: String, text: String) -> Unit = { _, _ -> }
    var onModifierStateChanged: () -> Unit = {}

    private val imm: InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    private val accessibilityManager: AccessibilityManager? =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager

    private data class PaneGeometrySnapshot(
        val sessionRef: NativeVtSession,
        val cols: Int,
        val rows: Int,
        val widthPx: Int,
        val heightPx: Int,
        val cellWidthPx: Int,
        val cellHeightPx: Int,
    )

    private val lastNotifiedPaneGeometry = HashMap<String, PaneGeometrySnapshot>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var glThread: TermVrGlThread? = null

    private var modifierLatchMachine: ModifierLatchMachine = ModifierLatchMachine()
    private var passAllShortcutsToTerminal: Boolean = false
    private var palette: ColorPalette = ColorPalette.DEFAULT_DARK
    private var fontConfig: FontConfig = FontConfig()

    private var splitRoot: TerminalSplitNode? = null
    private var focusedPaneId: String = ""
    private var latestLayout: SplitLayoutResult = SplitLayoutResult(emptyList(), emptyList())
    private var latestMetrics: CellMetrics = CellMetrics(10, 20, 16)

    private var selectionState: TerminalSelectionUiState = TerminalSelectionUiState()
    private var activeInputConnection: TerminalInputConnection? = null
    private var a11yProvider: TerminalAccessibilityNodeProvider? = null

    private val inputSession = TerminalInputSession(
        sessionProvider = { focusedPane()?.session },
        modifierLatchProvider = { modifierLatchMachine },
        onPreeditChanged = { preedit ->
            glThread?.updatePreedit(preedit)
            markUserOrPtyActivity()
        },
        onSendPtyBytes = { bytes ->
            val paneId = focusedPane()?.paneId ?: focusedPaneId
            if (bytes.isNotEmpty() && paneId.isNotEmpty()) {
                onSendPtyBytes(paneId, bytes)
            }
            markUserOrPtyActivity()
        },
        onUnsafePasteConfirmationNeeded = { text ->
            val paneId = focusedPane()?.paneId ?: focusedPaneId
            onUnsafePastePrompt(paneId, text)
        },
        onResetImeSelection = { start, end ->
            imm?.updateSelection(this, start, end, -1, -1)
        },
        onRestartInput = {
            imm?.restartInput(this)
        },
    )

    // Touch gesture trackers.
    private var ignoreCurrentGesture = false
    private var isDraggingScrollOrMouse = false
    private var isLongPressSelecting = false
    private var scrollAccumYPx = 0f
    private var velocityTracker: VelocityTracker? = null
    private val flingScroller = OverScroller(context)
    private var flingLastY = 0
    private var lastZoomUpdateVSyncTime = 0L

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!flingScroller.computeScrollOffset()) return
            val currY = flingScroller.currY
            val dy = currY - flingLastY
            flingLastY = currY
            if (dy != 0) {
                applyScrollbackPixelDelta(dy.toFloat())
            }
            if (!flingScroller.isFinished) {
                postOnAnimation(this)
            }
        }
    }

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val now = SystemClock.uptimeMillis()
                if (now - lastZoomUpdateVSyncTime < 14L && abs(detector.scaleFactor - 1f) < 0.04f) {
                    return false
                }
                val nextSp = PinchZoomCalculator.applyScaleFactor(fontConfig.sizeSp, detector.scaleFactor)
                if (nextSp != fontConfig.sizeSp) {
                    lastZoomUpdateVSyncTime = now
                    val updated = fontConfig.copy(sizeSp = nextSp)
                    setFontConfig(updated)
                    onFontConfigChanged(updated)
                    return true
                }
                return false
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                flingScroller.forceFinished(true)
                removeCallbacks(flingRunnable)
                isLongPressSelecting = false
                isDraggingScrollOrMouse = false
                scrollAccumYPx = 0f
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val pane = SplitPaneLayoutCalculator.paneAt(latestLayout, e.x, e.y) ?: return false
                if (pane.paneId != focusedPaneId) {
                    focusedPaneId = pane.paneId
                    onPaneFocused(pane.paneId)
                    glThread?.updateLayout(splitRoot, focusedPaneId)
                }
                if (selectionState.active) {
                    clearSelection()
                } else if (pane.session.isMouseTrackingActive() && !isShiftOverrideActive(e.metaState)) {
                    val (col, row) = pixelToCell(pane, e.x, e.y)
                    val mods = modifierLatchMachine.activeModifierMask()
                    sendMouse(pane, VtMouseAction.PRESS, VtMouseButton.LEFT, mods, col, row, e.x.toInt(), e.y.toInt())
                    sendMouse(pane, VtMouseAction.RELEASE, VtMouseButton.LEFT, mods, col, row, e.x.toInt(), e.y.toInt())
                }
                requestShowKeyboard()
                markUserOrPtyActivity()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val pane = SplitPaneLayoutCalculator.paneAt(latestLayout, e.x, e.y) ?: return false
                val (col, row) = pixelToCell(pane, e.x, e.y)
                val link = pane.session.detectLinkAt(row, col)
                if (link != null) {
                    pane.session.setSelection(true, link.startRow, link.startCol, link.endRow, link.endCol)
                    glThread?.requestRender(dirty = true)
                    onLinkDetected(link)
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val pane = SplitPaneLayoutCalculator.paneAt(latestLayout, e.x, e.y) ?: return
                if (pane.paneId != focusedPaneId) {
                    focusedPaneId = pane.paneId
                    onPaneFocused(pane.paneId)
                }
                val (col, row) = pixelToCell(pane, e.x, e.y)
                isLongPressSelecting = true
                updateSelectionBounds(pane, row, col, row, col)
                markUserOrPtyActivity()
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleGestureDetector.isInProgress) return false
                val pane = focusedPane() ?: return false
                if (isLongPressSelecting) {
                    val (col, row) = pixelToCell(pane, e2.x, e2.y)
                    updateSelectionBounds(pane, selectionState.startRow, selectionState.startCol, row, col)
                    return true
                }
                isDraggingScrollOrMouse = true
                if (pane.session.isMouseTrackingActive() && !isShiftOverrideActive(e2.metaState)) {
                    scrollAccumYPx += distanceY
                    val cellH = max(1, latestMetrics.cellHeightPx).toFloat()
                    val steps = (scrollAccumYPx / cellH).toInt()
                    if (steps != 0) {
                        scrollAccumYPx -= steps * cellH
                        val (col, row) = pixelToCell(pane, e2.x, e2.y)
                        val action = if (steps > 0) VtMouseAction.WHEEL_DOWN else VtMouseAction.WHEEL_UP
                        val mods = modifierLatchMachine.activeModifierMask()
                        repeat(abs(steps)) {
                            sendMouse(pane, action, VtMouseButton.NONE, mods, col, row, e2.x.toInt(), e2.y.toInt())
                        }
                    }
                } else {
                    applyScrollbackPixelDelta(-distanceY)
                }
                markUserOrPtyActivity()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val pane = focusedPane() ?: return false
                if (isLongPressSelecting || (pane.session.isMouseTrackingActive() && !isShiftOverrideActive(e2.metaState))) {
                    return false
                }
                flingLastY = 0
                flingScroller.fling(0, 0, 0, velocityY.roundToInt(), 0, 0, -50_000, 50_000)
                postOnAnimation(flingRunnable)
                return true
            }
        },
    )

    private fun isShiftOverrideActive(metaState: Int): Boolean =
        modifierLatchMachine.isShiftActive() || (metaState and KeyEvent.META_SHIFT_ON) != 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureGlThread()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(flingRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        glThread?.shutdown()
        glThread = null
        super.onDetachedFromWindow()
    }

    private fun ensureGlThread(): TermVrGlThread {
        val existing = glThread
        if (existing != null) return existing
        val created = TermVrGlThread(
            context = context,
            density = resources.displayMetrics.density,
            onLayoutComputed = { layout, metrics ->
                mainHandler.post {
                    latestLayout = layout
                    latestMetrics = metrics
                    val activePaneIds = HashSet<String>(layout.panes.size)
                    var anyGridResized = false
                    for (pane in layout.panes) {
                        activePaneIds.add(pane.paneId)
                        if (pane.session.isClosed) continue
                        val cols = max(1, pane.widthPx / max(1, metrics.cellWidthPx))
                        val rows = max(1, pane.heightPx / max(1, metrics.cellHeightPx))
                        val gridChanged = pane.session.cols != cols || pane.session.rows != rows
                        val snapshot = PaneGeometrySnapshot(
                            sessionRef = pane.session,
                            cols = cols,
                            rows = rows,
                            widthPx = pane.widthPx,
                            heightPx = pane.heightPx,
                            cellWidthPx = metrics.cellWidthPx,
                            cellHeightPx = metrics.cellHeightPx,
                        )
                        val prev = lastNotifiedPaneGeometry[pane.paneId]
                        if (gridChanged || prev != snapshot) {
                            if (pane.session.resizeGrid(cols, rows, metrics.cellWidthPx, metrics.cellHeightPx)) {
                                lastNotifiedPaneGeometry[pane.paneId] = snapshot
                                onGridResized(pane.paneId, cols, rows, pane.widthPx, pane.heightPx)
                                if (gridChanged) {
                                    anyGridResized = true
                                }
                            }
                        }
                    }
                    lastNotifiedPaneGeometry.keys.retainAll(activePaneIds)
                    if (anyGridResized) {
                        glThread?.requestRender(dirty = true)
                    }
                    invalidateAccessibilityTree()
                }
            },
        )
        created.start()
        created.waitUntilReady()
        created.updateFontConfig(fontConfig)
        created.updatePalette(palette)
        created.updateLayout(splitRoot, focusedPaneId)
        glThread = created
        return created
    }

    fun setSingleSession(session: NativeVtSession, paneId: String = "main") {
        setSplitLayout(TerminalSplitNode.Leaf(paneId, session), paneId)
    }

    fun setSplitLayout(root: TerminalSplitNode, focusedPaneId: String) {
        this.splitRoot = root
        this.focusedPaneId = focusedPaneId
        glThread?.updateLayout(root, focusedPaneId)
    }

    fun setPalette(newPalette: ColorPalette) {
        palette = newPalette
        glThread?.updatePalette(newPalette)
    }

    fun setFontConfig(newConfig: FontConfig) {
        fontConfig = newConfig
        glThread?.updateFontConfig(newConfig)
    }

    fun setPassAllShortcutsToTerminal(passAll: Boolean) {
        passAllShortcutsToTerminal = passAll
    }

    fun setModifierLatchMachine(machine: ModifierLatchMachine) {
        modifierLatchMachine = machine
    }

    fun notifyPtyOutput() {
        markUserOrPtyActivity()
        glThread?.requestRender(dirty = true)
        invalidateAccessibilityTree()
    }

    fun notifySessionClosed(sessionHandle: Long) {
        if (sessionHandle != 0L) {
            glThread?.detachSession(sessionHandle)
        }
    }

    fun detachSessionBlocking(sessionHandle: Long, timeoutMs: Long = 150L) {
        if (sessionHandle != 0L) {
            glThread?.detachSessionBlocking(sessionHandle, timeoutMs)
        }
    }

    fun requestShowKeyboard() {
        requestFocus()
        imm?.showSoftInput(this, 0)
    }

    fun flushImeAndRestartInput() {
        inputSession.flushComposingBeforeControlKey()
        activeInputConnection?.recenterEditableAndImm()
    }

    fun emitKeySlot(slot: KeySlotSpec) {
        val pane = focusedPane() ?: return
        flushImeAndRestartInput()
        val mods = modifierLatchMachine.activeModifierMask()
        when (slot) {
            is KeySlotSpec.Modifier -> Unit
            is KeySlotSpec.GhosttyKey -> {
                val vtKey = VtKey.fromKeyName(slot.keyName)
                if (vtKey != VtKey.NONE) {
                    val bytes = pane.session.encodeKey(VtKeyEvent(vtKey, VtKeyAction.PRESS, mods))
                    if (bytes.isNotEmpty()) onSendPtyBytes(pane.paneId, bytes)
                }
            }
            is KeySlotSpec.LiteralText -> {
                val bytes = if (mods != 0 && slot.text.length == 1) {
                    pane.session.encodeKey(
                        VtKeyEvent(
                            key = VtKey.UNICODE,
                            action = VtKeyAction.PRESS,
                            modifierMask = mods,
                            unicodeCodepoint = slot.text[0].code,
                            utf8Text = slot.text,
                        ),
                    )
                } else {
                    slot.text.toByteArray(StandardCharsets.UTF_8)
                }
                if (bytes.isNotEmpty()) onSendPtyBytes(pane.paneId, bytes)
            }
            is KeySlotSpec.CtrlChord -> {
                val bytes = pane.session.encodeKey(
                    VtKeyEvent(
                        key = VtKey.UNICODE,
                        action = VtKeyAction.PRESS,
                        modifierMask = mods or dev.gfire.termvr.core.vt.VtModifierMask.CTRL,
                        unicodeCodepoint = slot.asciiUpper.lowercaseChar().code,
                        utf8Text = slot.asciiUpper.lowercase(),
                    ),
                )
                if (bytes.isNotEmpty()) onSendPtyBytes(pane.paneId, bytes)
            }
        }
        markUserOrPtyActivity()
    }

    fun scrubCursor(steps: Int) {
        if (steps == 0) return
        val pane = focusedPane() ?: return
        flushImeAndRestartInput()
        val key = if (steps < 0) VtKey.LEFT else VtKey.RIGHT
        val mods = modifierLatchMachine.activeModifierMask()
        repeat(abs(steps)) {
            val bytes = pane.session.encodeKey(VtKeyEvent(key, VtKeyAction.PRESS, mods))
            if (bytes.isNotEmpty()) onSendPtyBytes(pane.paneId, bytes)
        }
        if (mods != 0) {
            modifierLatchMachine.onNonModifierKeyEmitted()
            onModifierStateChanged()
        }
        markUserOrPtyActivity()
    }

    fun scrollHalfScreenUp(lines: Int) {
        val pane = focusedPane() ?: return
        val count = pane.session.getScrollbackCount()
        val next = (pane.session.getScrollbackOffset() + max(1, lines)).coerceIn(0, count)
        pane.session.setScrollbackOffset(next)
        glThread?.requestRender(dirty = true)
        markUserOrPtyActivity()
    }

    fun clearSelection() {
        focusedPane()?.session?.clearSelection()
        if (selectionState.active) {
            selectionState = TerminalSelectionUiState()
            onSelectionChanged(selectionState)
        }
        glThread?.requestRender(dirty = true)
    }

    fun copySelectionToClipboard(): String? {
        val pane = focusedPane() ?: return null
        val text = pane.session.formatSelectionText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("TermVR", text))
        }
        clearSelection()
        return text.ifEmpty { null }
    }

    fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        val pane = focusedPane() ?: return
        if (!pane.session.isPasteSafe(text)) {
            onUnsafePastePrompt(pane.paneId, text)
            return
        }
        val encoded = pane.session.encodePaste(text, allowUnsafe = false) ?: return
        if (encoded.isNotEmpty()) {
            onSendPtyBytes(pane.paneId, encoded)
            markUserOrPtyActivity()
        }
    }

    fun confirmUnsafePaste(paneId: String, text: String) {
        val pane = latestLayout.panes.firstOrNull { it.paneId == paneId } ?: focusedPane() ?: return
        val encoded = pane.session.encodePaste(text, allowUnsafe = true) ?: return
        if (encoded.isNotEmpty()) {
            onSendPtyBytes(pane.paneId, encoded)
            markUserOrPtyActivity()
        }
    }

    fun requestZoomDelta(deltaSp: Float) {
        val next = FontConfig.clampSizeSp(fontConfig.sizeSp + deltaSp)
        if (next != fontConfig.sizeSp) {
            val updated = fontConfig.copy(sizeSp = next)
            setFontConfig(updated)
            onFontConfigChanged(updated)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        TerminalInputConnection.configureEditorInfo(outAttrs)
        val conn = TerminalInputConnection(
            targetView = this,
            imm = imm,
            inputSession = inputSession,
            onKeyEventDispatch = { event -> handleHardwareOrImeKeyEvent(event) },
            onCopyRequested = { copySelectionToClipboard() },
            onPasteRequested = { pasteFromClipboard() },
        )
        activeInputConnection = conn
        return conn
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (inputSession.activePreedit.isEmpty() && handleHardwareOrImeKeyEvent(event)) {
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleHardwareOrImeKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleHardwareOrImeKeyEvent(event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleHardwareOrImeKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        if (event.action != KeyEvent.ACTION_DOWN && !isUp) {
            return false
        }
        val pane = focusedPane() ?: return false

        // Flush active preedit before non-printable/control keys or modifier chords.
        val hasCtrlOrAlt = (event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)) != 0
        if (!isUp && inputSession.activePreedit.isNotEmpty()) {
            if (hasCtrlOrAlt || HardwareKeyMapper.mapKeyEvent(
                    keyCode = event.keyCode,
                    isRepeat = event.repeatCount > 0,
                    isUp = false,
                    hardwareMetaState = event.metaState,
                    unicodeChar = 0,
                    softModifierMask = modifierLatchMachine.activeModifierMask(),
                    passAllShortcutsToTerminal = passAllShortcutsToTerminal,
                ) is HardwareKeyResult.TerminalKey
            ) {
                flushImeAndRestartInput()
            }
        }

        val shiftMeta = event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON)
        val unicode = event.getUnicodeChar(shiftMeta)
        val mapped = HardwareKeyMapper.mapKeyEvent(
            keyCode = event.keyCode,
            isRepeat = event.repeatCount > 0,
            isUp = isUp,
            hardwareMetaState = event.metaState,
            unicodeChar = unicode,
            softModifierMask = modifierLatchMachine.activeModifierMask(),
            passAllShortcutsToTerminal = passAllShortcutsToTerminal,
        )

        return when (mapped) {
            HardwareKeyResult.Ignored -> false
            is HardwareKeyResult.HostShortcut -> {
                when (mapped.action) {
                    HostShortcutAction.COPY -> copySelectionToClipboard()
                    HostShortcutAction.PASTE -> pasteFromClipboard()
                    HostShortcutAction.SEARCH -> onSearchRequested()
                    HostShortcutAction.ZOOM_IN -> requestZoomDelta(FontConfig.SIZE_STEP_SP)
                    HostShortcutAction.ZOOM_OUT -> requestZoomDelta(-FontConfig.SIZE_STEP_SP)
                    HostShortcutAction.NEW_TAB,
                    HostShortcutAction.CLOSE_TAB -> Unit
                }
                onHostShortcut(mapped.action)
                true
            }
            is HardwareKeyResult.TerminalKey -> {
                val bytes = pane.session.encodeKey(mapped.event)
                if (bytes.isNotEmpty()) {
                    onSendPtyBytes(pane.paneId, bytes)
                }
                if (!isUp) {
                    modifierLatchMachine.onNonModifierKeyEmitted()
                    onModifierStateChanged()
                    markUserOrPtyActivity()
                }
                true
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignoreCurrentGesture = GestureEdgeGuard.isWithinEdgeGuard(event.x, width, density)
                if (ignoreCurrentGesture) {
                    return false
                }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoreCurrentGesture) return false
                velocityTracker?.addMovement(event)
                if (isLongPressSelecting) {
                    val pane = focusedPane()
                    if (pane != null) {
                        val (col, row) = pixelToCell(pane, event.x, event.y)
                        updateSelectionBounds(pane, selectionState.startRow, selectionState.startCol, row, col)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ignoreCurrentGesture) {
                    ignoreCurrentGesture = false
                    return false
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isLongPressSelecting = false
                isDraggingScrollOrMouse = false
            }
        }

        if (ignoreCurrentGesture) return false
        var handled = scaleGestureDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val thread = ensureGlThread()
        thread.bindSurface(holder.surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
        markUserOrPtyActivity()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        glThread?.resizeSurface(width, height)
        markUserOrPtyActivity()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Lock-free UNBIND_SURFACE handoff: never acquires vt_mu.
        glThread?.unbindSurfaceBlocking(timeoutMs = 50L)
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider {
        val existing = a11yProvider
        if (existing != null) return existing
        val created = TerminalAccessibilityNodeProvider(this) { focusedPane() to latestMetrics }
        a11yProvider = created
        return created
    }

    private fun invalidateAccessibilityTree() {
        if (accessibilityManager?.isEnabled == true) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun focusedPane(): PaneLayoutRect? =
        latestLayout.panes.firstOrNull { it.focused } ?: latestLayout.panes.firstOrNull()

    private fun pixelToCell(pane: PaneLayoutRect, xPx: Float, yPx: Float): Pair<Int, Int> {
        val cw = max(1, latestMetrics.cellWidthPx)
        val ch = max(1, latestMetrics.cellHeightPx)
        val col = ((xPx - pane.xPx) / cw).toInt().coerceIn(0, max(0, pane.session.cols - 1))
        val row = ((yPx - pane.yPx) / ch).toInt().coerceIn(0, max(0, pane.session.rows - 1))
        return col to row
    }

    private fun sendMouse(
        pane: PaneLayoutRect,
        action: VtMouseAction,
        button: VtMouseButton,
        mods: Int,
        col: Int,
        row: Int,
        pixelX: Int,
        pixelY: Int,
    ) {
        val bytes = pane.session.encodeMouse(
            VtMouseEvent(
                action = action,
                button = button,
                modifierMask = mods,
                col = col,
                row = row,
                pixelX = pixelX,
                pixelY = pixelY,
            ),
        )
        if (bytes.isNotEmpty()) {
            onSendPtyBytes(pane.paneId, bytes)
        }
    }

    private fun applyScrollbackPixelDelta(deltaYPx: Float) {
        val pane = focusedPane() ?: return
        scrollAccumYPx += deltaYPx
        val cellH = max(1, latestMetrics.cellHeightPx).toFloat()
        val deltaLines = (scrollAccumYPx / cellH).toInt()
        if (deltaLines != 0) {
            scrollAccumYPx -= deltaLines * cellH
            val maxOff = pane.session.getScrollbackCount()
            val next = (pane.session.getScrollbackOffset() + deltaLines).coerceIn(0, maxOff)
            if (next != pane.session.getScrollbackOffset()) {
                pane.session.setScrollbackOffset(next)
                glThread?.requestRender(dirty = true)
            }
        }
    }

    internal fun updateSelectionBounds(
        pane: PaneLayoutRect,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ) {
        val maxR = max(0, pane.session.rows - 1)
        val maxC = max(0, pane.session.cols - 1)
        val sr = startRow.coerceIn(0, maxR)
        val sc = startCol.coerceIn(0, maxC)
        val er = endRow.coerceIn(0, maxR)
        val ec = endCol.coerceIn(0, maxC)
        pane.session.setSelection(true, sr, sc, er, ec)
        selectionState = TerminalSelectionUiState(
            active = true,
            paneId = pane.paneId,
            paneXPx = pane.xPx,
            paneYPx = pane.yPx,
            paneWidthPx = pane.widthPx,
            paneHeightPx = pane.heightPx,
            cellWidthPx = max(1, latestMetrics.cellWidthPx),
            cellHeightPx = max(1, latestMetrics.cellHeightPx),
            startRow = sr,
            startCol = sc,
            endRow = er,
            endCol = ec,
        )
        onSelectionChanged(selectionState)
        glThread?.requestRender(dirty = true)
    }

    internal fun adjustSelectionHandle(isStart: Boolean, dxPx: Float, dyPx: Float) {
        val pane = latestLayout.panes.firstOrNull { it.paneId == selectionState.paneId } ?: focusedPane() ?: return
        val cw = max(1, latestMetrics.cellWidthPx)
        val ch = max(1, latestMetrics.cellHeightPx)
        val dCol = (dxPx / cw).roundToInt()
        val dRow = (dyPx / ch).roundToInt()
        if (dCol == 0 && dRow == 0) return
        if (isStart) {
            updateSelectionBounds(
                pane,
                selectionState.startRow + dRow,
                selectionState.startCol + dCol,
                selectionState.endRow,
                selectionState.endCol,
            )
        } else {
            updateSelectionBounds(
                pane,
                selectionState.startRow,
                selectionState.startCol,
                selectionState.endRow + dRow,
                selectionState.endCol + dCol,
            )
        }
    }

    private fun markUserOrPtyActivity() {
        glThread?.markActivity()
    }
}

/**
 * Dedicated GL thread owning a persistent surfaceless EGLContext and dirty-driven Choreographer loop.
 */
private class TermVrGlThread(
    private val context: Context,
    private val density: Float,
    private val onLayoutComputed: (SplitLayoutResult, CellMetrics) -> Unit,
) : HandlerThread("TermVrGlThread"), Choreographer.FrameCallback {

    private var handler: Handler? = null
    private val readyLatch = CountDownLatch(1)

    private var choreographer: Choreographer? = null
    private val renderer = NativeGlRenderer()

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var hasSurfacelessContext: Boolean = false
    private var activeNativeSurface: Surface? = null

    private var surfaceWidthPx: Int = 0
    private var surfaceHeightPx: Int = 0
    private var needsFullRedrawFrames: Int = 0
    private var frameScheduled: Boolean = false
    private var dirtyPending: Boolean = false
    private var preeditDirty: Boolean = false

    private var splitRoot: TerminalSplitNode? = null
    private var focusedPaneId: String = ""
    private var currentFontConfig: FontConfig = FontConfig()
    private var currentPalette: ColorPalette = ColorPalette.DEFAULT_DARK
    private var currentMetrics: CellMetrics = CellMetrics(10, 20, 16)
    private var latestLayout: SplitLayoutResult = SplitLayoutResult(emptyList(), emptyList())
    private var activePreedit: String = ""

    private var lastActivityMs: Long = SystemClock.uptimeMillis()
    private var cursorBlinkVisible: Boolean = true
    private var blinkScheduled: Boolean = false
    @Volatile
    private var shuttingDown: Boolean = false

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkScheduled = false
            if (windowSurface == EGL14.EGL_NO_SURFACE) return
            val elapsed = SystemClock.uptimeMillis() - lastActivityMs
            if (elapsed >= BLINK_IDLE_SUSPEND_MS) {
                if (!cursorBlinkVisible) {
                    cursorBlinkVisible = true
                    dirtyPending = true
                    scheduleVsync()
                }
                return
            }
            cursorBlinkVisible = !cursorBlinkVisible
            dirtyPending = true
            scheduleVsync()
            scheduleNextBlinkTick()
        }
    }

    override fun onLooperPrepared() {
        choreographer = Choreographer.getInstance()
        handler = object : Handler(looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_BIND_SURFACE -> {
                        val payload = msg.obj as BindPayload
                        handleBindSurface(payload.surface, payload.width, payload.height)
                    }
                    MSG_RESIZE_SURFACE -> handleResizeSurface(msg.arg1, msg.arg2)
                    MSG_UNBIND_SURFACE -> handleUnbindSurface(msg.obj as CountDownLatch)
                    MSG_REQUEST_RENDER -> {
                        if (msg.arg1 != 0) dirtyPending = true
                        scheduleVsync()
                    }
                    MSG_UPDATE_LAYOUT -> {
                        val payload = msg.obj as LayoutPayload
                        splitRoot = payload.root
                        focusedPaneId = payload.focusedPaneId
                        recomputeLayout()
                        needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
                        scheduleVsync()
                    }
                    MSG_UPDATE_FONT -> {
                        currentFontConfig = msg.obj as FontConfig
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            currentMetrics = renderer.configureFont(currentFontConfig, density)
                            recomputeLayout()
                            needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
                            scheduleVsync()
                        }
                    }
                    MSG_UPDATE_PALETTE -> {
                        currentPalette = msg.obj as ColorPalette
                        for (pane in latestLayout.panes) {
                            if (!pane.session.isClosed) {
                                pane.session.setPalette(currentPalette)
                            }
                        }
                        needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
                        scheduleVsync()
                    }
                    MSG_UPDATE_PREEDIT -> {
                        activePreedit = msg.obj as String
                        preeditDirty = true
                        scheduleVsync()
                    }
                    MSG_DETACH_SESSION -> {
                        val payload = msg.obj as? DetachPayload
                        val handle = payload?.handle ?: (msg.obj as? Long ?: 0L)
                        try {
                            if (handle != 0L) {
                                renderer.detachSession(handle)
                                latestLayout = latestLayout.copy(
                                    panes = latestLayout.panes.filterNot {
                                        it.session.isClosed ||
                                            it.session.nativeHandle == handle ||
                                            it.session.nativeHandle == 0L
                                    },
                                )
                            }
                        } finally {
                            payload?.latch?.countDown()
                        }
                    }
                    MSG_MARK_ACTIVITY -> {
                        lastActivityMs = SystemClock.uptimeMillis()
                        if (!cursorBlinkVisible) {
                            cursorBlinkVisible = true
                            dirtyPending = true
                            scheduleVsync()
                        }
                        if (!blinkScheduled && windowSurface != EGL14.EGL_NO_SURFACE) {
                            scheduleNextBlinkTick()
                        }
                    }
                    MSG_SHUTDOWN -> handleShutdown()
                }
            }
        }
        initEglAndRenderer()
        readyLatch.countDown()
    }

    fun waitUntilReady() {
        readyLatch.await()
    }

    fun bindSurface(surface: Surface, width: Int, height: Int) {
        handler?.obtainMessage(MSG_BIND_SURFACE, BindPayload(surface, width, height))?.sendToTarget()
    }

    fun resizeSurface(width: Int, height: Int) {
        handler?.obtainMessage(MSG_RESIZE_SURFACE, width, height)?.sendToTarget()
    }

    fun unbindSurfaceBlocking(timeoutMs: Long) {
        if (shuttingDown || !isAlive) return
        val h = handler ?: return
        val latch = CountDownLatch(1)
        val msg = h.obtainMessage(MSG_UNBIND_SURFACE, latch)
        if (h.sendMessageAtFrontOfQueue(msg)) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    fun requestRender(dirty: Boolean) {
        if (shuttingDown) return
        handler?.obtainMessage(MSG_REQUEST_RENDER, if (dirty) 1 else 0, 0)?.sendToTarget()
    }

    fun updateLayout(root: TerminalSplitNode?, focusedId: String) {
        if (shuttingDown) return
        handler?.obtainMessage(MSG_UPDATE_LAYOUT, LayoutPayload(root, focusedId))?.sendToTarget()
    }

    fun updateFontConfig(config: FontConfig) {
        if (shuttingDown) return
        handler?.obtainMessage(MSG_UPDATE_FONT, config)?.sendToTarget()
    }

    fun updatePalette(palette: ColorPalette) {
        if (shuttingDown) return
        handler?.obtainMessage(MSG_UPDATE_PALETTE, palette)?.sendToTarget()
    }

    fun updatePreedit(preedit: String) {
        if (shuttingDown) return
        handler?.obtainMessage(MSG_UPDATE_PREEDIT, preedit)?.sendToTarget()
    }

    fun detachSession(sessionHandle: Long) {
        if (shuttingDown || sessionHandle == 0L) return
        handler?.obtainMessage(MSG_DETACH_SESSION, DetachPayload(sessionHandle))?.sendToTarget()
    }

    fun detachSessionBlocking(sessionHandle: Long, timeoutMs: Long) {
        if (shuttingDown || sessionHandle == 0L || !isAlive) return
        val h = handler ?: return
        val latch = CountDownLatch(1)
        val msg = h.obtainMessage(MSG_DETACH_SESSION, DetachPayload(sessionHandle, latch))
        if (h.sendMessage(msg)) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    fun markActivity() {
        if (shuttingDown) return
        handler?.sendEmptyMessage(MSG_MARK_ACTIVITY)
    }

    fun shutdown() {
        shuttingDown = true
        val h = handler
        if (h != null) {
            h.removeCallbacks(blinkRunnable)
            if (!h.sendEmptyMessage(MSG_SHUTDOWN)) {
                quitSafely()
            }
        } else {
            quitSafely()
        }
        runCatching { join(1000L) }
    }

    private fun initEglAndRenderer() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        val versions = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) return

        val exts = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS) ?: ""
        hasSurfacelessContext = exts.contains("EGL_KHR_surfaceless_context")

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            return
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return

        if (!hasSurfacelessContext) {
            val pbAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE,
            )
            pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        }

        makeSurfacelessCurrent()
        renderer.setShaders(loadShadersFromAssets())
        renderer.setBundledFonts(loadBundledFontsFromAssets())
        renderer.initGl()
        currentMetrics = renderer.configureFont(currentFontConfig, density)
    }

    private fun makeSurfacelessCurrent() {
        val target = if (hasSurfacelessContext) EGL14.EGL_NO_SURFACE else pbufferSurface
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, target, target, eglContext)
        }
    }

    private fun handleBindSurface(surface: Surface, width: Int, height: Int) {
        if (shuttingDown) return
        activeNativeSurface = surface
        surfaceWidthPx = width
        surfaceHeightPx = height
        destroyWindowSurface()

        val cfg = eglConfig ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT || !surface.isValid) {
            return
        }

        val winAttribs = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg, surface, winAttribs, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            activeNativeSurface = null
            return
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
                handleContextLost()
            } else {
                destroyWindowSurface()
                activeNativeSurface = null
            }
            return
        }
        recomputeLayout()
        needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
        scheduleVsync()
        if (!blinkScheduled) {
            scheduleNextBlinkTick()
        }
    }

    private fun handleResizeSurface(width: Int, height: Int) {
        surfaceWidthPx = width
        surfaceHeightPx = height
        recomputeLayout()
        needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
        scheduleVsync()
    }

    private fun handleUnbindSurface(latch: CountDownLatch) {
        try {
            if (frameScheduled) {
                choreographer?.removeFrameCallback(this)
                frameScheduled = false
            }
            handler?.removeCallbacks(blinkRunnable)
            blinkScheduled = false
            activeNativeSurface = null
            makeSurfacelessCurrent()
            destroyWindowSurface()
        } finally {
            latch.countDown()
        }
    }

    private fun destroyWindowSurface() {
        if (windowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(eglDisplay, windowSurface)
        }
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    private fun handleShutdown() {
        shuttingDown = true
        if (frameScheduled) {
            choreographer?.removeFrameCallback(this)
            frameScheduled = false
        }
        handler?.removeCallbacks(blinkRunnable)
        blinkScheduled = false
        activeNativeSurface = null
        makeSurfacelessCurrent()
        renderer.close()
        destroyWindowSurface()
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        quitSafely()
    }

    private fun scheduleVsync() {
        if (!shuttingDown && !frameScheduled && windowSurface != EGL14.EGL_NO_SURFACE) {
            frameScheduled = true
            choreographer?.postFrameCallback(this)
        }
    }

    private fun scheduleNextBlinkTick() {
        val h = handler ?: return
        if (!shuttingDown && !blinkScheduled) {
            blinkScheduled = true
            h.postDelayed(blinkRunnable, BLINK_PERIOD_MS)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (shuttingDown ||
            windowSurface == EGL14.EGL_NO_SURFACE ||
            activeNativeSurface?.isValid == false ||
            surfaceWidthPx <= 0 ||
            surfaceHeightPx <= 0
        ) {
            return
        }

        val activePanes = latestLayout.panes.filter { !it.session.isClosed }
        val forceFull = needsFullRedrawFrames > 0
        val anySessionDirty = activePanes.any { it.session.isDirty() }
        if (!forceFull && !dirtyPending && !preeditDirty && !anySessionDirty) {
            return
        }

        dirtyPending = false
        preeditDirty = false

        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) {
            if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
                handleContextLost()
            }
            return
        }

        val renderTargets = activePanes.mapNotNull { pane ->
            if (pane.session.isClosed) return@mapNotNull null
            val cursor = pane.session.getCursorState()
            val showPreedit = pane.focused && activePreedit.isNotEmpty()
            PaneRenderTarget(
                session = pane.session,
                xPx = pane.xPx,
                yPx = pane.yPx,
                widthPx = pane.widthPx,
                heightPx = pane.heightPx,
                focused = pane.focused,
                cursorBlinkVisible = cursorBlinkVisible || !cursor.blink,
                preeditText = if (showPreedit) activePreedit else null,
                preeditCursorCol = cursor.col,
                preeditCursorRow = cursor.row,
                preeditFgRgba = currentPalette.foregroundRgba,
                preeditBgRgba = currentPalette.backgroundRgba,
                preeditUnderlineRgba = currentPalette.cursorRgba,
            )
        }

        renderer.drawFrame(
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            panes = renderTargets,
            dividers = latestLayout.dividers,
            forceFullRedraw = forceFull,
            surfaceClearRgba = currentPalette.backgroundRgba,
        )

        if (!EGL14.eglSwapBuffers(eglDisplay, windowSurface)) {
            if (EGL14.eglGetError() == EGL14.EGL_CONTEXT_LOST) {
                handleContextLost()
            }
            return
        }

        if (needsFullRedrawFrames > 0) {
            needsFullRedrawFrames--
            if (needsFullRedrawFrames > 0) {
                scheduleVsync()
            }
        }
    }

    private fun handleContextLost() {
        renderer.notifyContextLost()
        destroyWindowSurface()
        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        initEglAndRenderer()
        val surf = activeNativeSurface
        if (surf != null && surf.isValid && surfaceWidthPx > 0 && surfaceHeightPx > 0) {
            handleBindSurface(surf, surfaceWidthPx, surfaceHeightPx)
        }
        needsFullRedrawFrames = TRIPLE_BUFFER_FRAMES
        scheduleVsync()
    }

    private fun recomputeLayout() {
        val root = splitRoot ?: return
        val dividerPx = max(1, (1f * density).roundToInt())
        val computed = SplitPaneLayoutCalculator.compute(
            root = root,
            focusedPaneId = focusedPaneId,
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            dividerThicknessPx = dividerPx,
        )
        latestLayout = computed.copy(panes = computed.panes.filterNot { it.session.isClosed })
        for (pane in latestLayout.panes) {
            if (!pane.session.isClosed) {
                pane.session.setPalette(currentPalette)
            }
        }
        onLayoutComputed(latestLayout, currentMetrics)
    }

    private fun loadShadersFromAssets(): RendererShaderPack = RendererShaderPack(
        bgVert = readAssetText("shaders/bg_pass.vert"),
        bgFrag = readAssetText("shaders/bg_pass.frag"),
        glyphVert = readAssetText("shaders/glyph_pass.vert"),
        glyphFrag = readAssetText("shaders/glyph_pass.frag"),
    )

    private fun loadBundledFontsFromAssets(): BundledFontPack {
        val termvrRegular = readAssetBytes("fonts/TermVRMonoNF-Regular.ttf")
        val primaryRegular = if (termvrRegular.isNotEmpty()) {
            termvrRegular
        } else {
            readAssetBytes("fonts/JetBrainsMono-Regular.ttf")
        }
        return BundledFontPack(
            jetbrainsRegular = primaryRegular,
            jetbrainsBold = readAssetBytes("fonts/JetBrainsMono-Bold.ttf"),
            jetbrainsItalic = readAssetBytes("fonts/JetBrainsMono-Italic.ttf"),
            jetbrainsBoldItalic = readAssetBytes("fonts/JetBrainsMono-BoldItalic.ttf"),
            nerdSymbols = readAssetBytes("fonts/SymbolsNerdFontMono-Regular.ttf"),
        )
    }

    private fun readAssetText(path: String): String =
        runCatching { context.assets.open(path).bufferedReader(StandardCharsets.UTF_8).use { it.readText() } }
            .getOrElse { "" }

    private fun readAssetBytes(path: String): ByteArray =
        runCatching { context.assets.open(path).use { it.readBytes() } }
            .getOrElse { ByteArray(0) }

    private data class BindPayload(val surface: Surface, val width: Int, val height: Int)
    private data class LayoutPayload(val root: TerminalSplitNode?, val focusedPaneId: String)
    private data class DetachPayload(val handle: Long, val latch: CountDownLatch? = null)

    companion object {
        private const val MSG_BIND_SURFACE = 1
        private const val MSG_RESIZE_SURFACE = 2
        private const val MSG_UNBIND_SURFACE = 3
        private const val MSG_REQUEST_RENDER = 4
        private const val MSG_UPDATE_LAYOUT = 5
        private const val MSG_UPDATE_FONT = 6
        private const val MSG_UPDATE_PALETTE = 7
        private const val MSG_UPDATE_PREEDIT = 8
        private const val MSG_DETACH_SESSION = 9
        private const val MSG_MARK_ACTIVITY = 10
        private const val MSG_SHUTDOWN = 11

        private const val TRIPLE_BUFFER_FRAMES = 3
        private const val BLINK_PERIOD_MS = 500L
        private const val BLINK_IDLE_SUSPEND_MS = 10_000L
    }
}

/**
 * Exposes visible viewport rows and cursor position as virtual nodes for TalkBack.
 */
private class TerminalAccessibilityNodeProvider(
    private val host: TerminalSurfaceView,
    private val snapshotProvider: () -> Pair<PaneLayoutRect?, CellMetrics>,
) : AccessibilityNodeProvider() {

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        val (pane, metrics) = snapshotProvider()
        if (virtualViewId == View.NO_ID) {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.view.View"
            val cols = pane?.session?.cols ?: 80
            val rows = pane?.session?.rows ?: 24
            info.contentDescription = "Terminal viewport, $cols columns by $rows rows"
            if (pane != null) {
                for (r in 0 until pane.session.rows) {
                    info.addChild(host, r)
                }
                info.addChild(host, CURSOR_VIRTUAL_ID)
            }
            return info
        }

        if (pane == null) return null
        val cellH = max(1, metrics.cellHeightPx)
        val cellW = max(1, metrics.cellWidthPx)

        if (virtualViewId == CURSOR_VIRTUAL_ID) {
            val cursor = pane.session.getCursorState()
            val info = AccessibilityNodeInfo.obtain(host, CURSOR_VIRTUAL_ID)
            info.className = "android.view.View"
            info.packageName = host.context.packageName
            info.contentDescription = "Cursor at row ${cursor.row + 1}, column ${cursor.col + 1}"
            val left = pane.xPx + cursor.col * cellW
            val top = pane.yPx + cursor.row * cellH
            @Suppress("DEPRECATION")
            info.setBoundsInParent(Rect(left, top, left + cellW, top + cellH))
            info.isVisibleToUser = cursor.visible
            info.isFocusable = true
            return info
        }

        if (virtualViewId in 0 until pane.session.rows) {
            val info = AccessibilityNodeInfo.obtain(host, virtualViewId)
            info.className = "android.widget.TextView"
            info.packageName = host.context.packageName
            val rowText = pane.session.formatRangeText(
                startRow = virtualViewId,
                startCol = 0,
                endRow = virtualViewId,
                endCol = max(0, pane.session.cols - 1),
            )
            info.text = rowText.ifEmpty { "Empty row ${virtualViewId + 1}" }
            val top = pane.yPx + virtualViewId * cellH
            @Suppress("DEPRECATION")
            info.setBoundsInParent(Rect(pane.xPx, top, pane.xPx + pane.widthPx, top + cellH))
            info.isVisibleToUser = true
            info.isFocusable = true
            info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            return info
        }

        return null
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == View.NO_ID) {
            return host.performAccessibilityAction(action, arguments)
        }
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                host.sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                host.sendAccessibilityEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                host.requestShowKeyboard()
                true
            }
            else -> false
        }
    }

    private fun View.sendAccessibilityEventForVirtualView(virtualId: Int, eventType: Int) {
        val parent = parent ?: return
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = context.packageName
        event.className = "android.widget.TextView"
        event.setSource(this, virtualId)
        parent.requestSendAccessibilityEvent(this, event)
    }

    companion object {
        private const val CURSOR_VIRTUAL_ID = 100_000
    }
}

/**
 * Composable wrapper hosting [TerminalSurfaceView], selection handles, link actions, and paste confirmation.
 */
@Composable
fun TerminalViewport(
    splitRoot: TerminalSplitNode,
    focusedPaneId: String,
    palette: ColorPalette,
    fontConfig: FontConfig,
    latchMachine: ModifierLatchMachine,
    passAllShortcutsToTerminal: Boolean,
    controller: TerminalSurfaceController,
    onSendPtyBytes: (paneId: String, bytes: ByteArray) -> Unit,
    onPaneFocused: (paneId: String) -> Unit,
    onGridResized: (paneId: String, cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit,
    onFontConfigChanged: (FontConfig) -> Unit,
    onHostShortcut: (HostShortcutAction) -> Unit,
    onShareText: (String) -> Unit,
    onSearchRequested: () -> Unit,
    modifier: Modifier = Modifier,
    onModifierStateChanged: () -> Unit = {},
    onOpenUrl: ((String) -> Unit)? = null,
) {
    var selectionState by remember { mutableStateOf(TerminalSelectionUiState()) }
    var detectedLink by remember { mutableStateOf<VtDetectedLink?>(null) }
    var unsafePastePrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var surfaceViewRef by remember { mutableStateOf<TerminalSurfaceView?>(null) }

    val density = LocalDensity.current

    DisposableEffect(controller, surfaceViewRef) {
        val view = surfaceViewRef
        if (view != null) {
            controller.attach(view)
        }
        onDispose {
            if (view != null) {
                controller.detach(view)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerW = constraints.maxWidth
        val containerH = constraints.maxHeight

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalSurfaceView(ctx).also { view ->
                    surfaceViewRef = view
                    controller.attach(view)
                }
            },
            update = { view ->
                surfaceViewRef = view
                view.setModifierLatchMachine(latchMachine)
                view.setPassAllShortcutsToTerminal(passAllShortcutsToTerminal)
                view.setPalette(palette)
                view.setFontConfig(fontConfig)
                view.setSplitLayout(splitRoot, focusedPaneId)
                view.onSendPtyBytes = onSendPtyBytes
                view.onPaneFocused = onPaneFocused
                view.onGridResized = onGridResized
                view.onFontConfigChanged = onFontConfigChanged
                view.onHostShortcut = onHostShortcut
                view.onSelectionChanged = { selectionState = it }
                view.onLinkDetected = { detectedLink = it }
                view.onShareText = onShareText
                view.onSearchRequested = onSearchRequested
                view.onUnsafePastePrompt = { paneId, text -> unsafePastePrompt = paneId to text }
                view.onModifierStateChanged = onModifierStateChanged
            },
        )

        if (selectionState.active) {
            val handleSizePx = with(density) { 22.dp.roundToPx() }
            val startX = (selectionState.paneXPx + selectionState.startCol * selectionState.cellWidthPx - handleSizePx / 2)
                .coerceIn(0, max(0, containerW - handleSizePx))
            val startY = (selectionState.paneYPx + (selectionState.startRow + 1) * selectionState.cellHeightPx)
                .coerceIn(0, max(0, containerH - handleSizePx))
            val endX = (selectionState.paneXPx + (selectionState.endCol + 1) * selectionState.cellWidthPx - handleSizePx / 2)
                .coerceIn(0, max(0, containerW - handleSizePx))
            val endY = (selectionState.paneYPx + (selectionState.endRow + 1) * selectionState.cellHeightPx)
                .coerceIn(0, max(0, containerH - handleSizePx))

            SelectionHandleDot(
                offset = IntOffset(startX, startY),
                onDragDelta = { dx, dy -> surfaceViewRef?.adjustSelectionHandle(isStart = true, dxPx = dx, dyPx = dy) },
            )
            SelectionHandleDot(
                offset = IntOffset(endX, endY),
                onDragDelta = { dx, dy -> surfaceViewRef?.adjustSelectionHandle(isStart = false, dxPx = dx, dyPx = dy) },
            )

            val menuTopPx = max(8, min(startY, endY) - with(density) { 56.dp.roundToPx() })
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, menuTopPx) },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    SelectionActionChip("Copy") { surfaceViewRef?.copySelectionToClipboard() }
                    SelectionActionChip("Paste") {
                        surfaceViewRef?.pasteFromClipboard()
                        surfaceViewRef?.clearSelection()
                    }
                    SelectionActionChip("Share") {
                        val copied = surfaceViewRef?.copySelectionToClipboard()
                        if (!copied.isNullOrEmpty()) onShareText(copied)
                    }
                    SelectionActionChip("Search") {
                        surfaceViewRef?.clearSelection()
                        onSearchRequested()
                    }
                }
            }
        }

        val activeLink = detectedLink
        if (activeLink != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (activeLink.kind == VtLinkKind.URL) {
                        SelectionActionChip("Open in Custom Tab") {
                            val target = activeLink.target
                            if (onOpenUrl != null) {
                                onOpenUrl(target)
                            } else {
                                openUrlInCustomTab(surfaceViewRef?.context, target)
                            }
                            surfaceViewRef?.clearSelection()
                            detectedLink = null
                        }
                    }
                    SelectionActionChip("Copy") {
                        val ctx = surfaceViewRef?.context
                        val cm = ctx?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("TermVR Link", activeLink.target))
                        surfaceViewRef?.clearSelection()
                        detectedLink = null
                    }
                    SelectionActionChip("Dismiss") {
                        surfaceViewRef?.clearSelection()
                        detectedLink = null
                    }
                }
            }
        }

        val pendingPaste = unsafePastePrompt
        if (pendingPaste != null) {
            AlertDialog(
                onDismissRequest = { unsafePastePrompt = null },
                title = { Text("Confirm multi-line paste") },
                text = {
                    Text(
                        "This clipboard text contains newlines or control sequences that execute commands immediately. Paste anyway?",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            surfaceViewRef?.confirmUnsafePaste(pendingPaste.first, pendingPaste.second)
                            unsafePastePrompt = null
                        },
                    ) {
                        Text("Paste")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unsafePastePrompt = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun SelectionHandleDot(
    offset: IntOffset,
    onDragDelta: (Float, Float) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset { offset }
            .size(22.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDragDelta(dragAmount.x, dragAmount.y)
                }
            },
    )
}

@Composable
private fun SelectionActionChip(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

private fun openUrlInCustomTab(context: Context?, rawUrl: String) {
    if (context == null) return
    val uri = Uri.parse(rawUrl)
    val scheme = uri.scheme?.lowercase() ?: return
    if (scheme != "http" && scheme != "https") return
    val extras = Bundle().apply {
        putBinder("android.support.customtabs.extra.SESSION", null)
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        putExtras(extras)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
