package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface VtSessionBackend : AutoCloseable {
    val nativeHandle: Long
    fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean
    fun writePtyBytes(data: ByteArray, offset: Int, length: Int)
    fun setPalette(palette: ColorPalette)
    fun setScrollbackOffset(linesFromBottom: Int)
    fun getScrollbackOffset(): Int
    fun getScrollbackCount(): Int
    fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int)
    fun clearSelection()
    fun isDirty(): Boolean
    fun isMouseTrackingActive(): Boolean
    fun isBracketedPasteActive(): Boolean
    fun isApplicationCursorActive(): Boolean
    fun getWindowTitle(): String
    fun getCursorState(): VtCursorState
    fun encodeKey(event: VtKeyEvent): ByteArray
    fun encodeMouse(event: VtMouseEvent): ByteArray
    fun isPasteSafe(text: String): Boolean
    fun encodePaste(text: String, allowUnsafe: Boolean): ByteArray?
    fun formatText(
        emitVt: Boolean,
        useSelection: Boolean,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        trimTrailingWhitespace: Boolean,
        unwrapSoftWraps: Boolean,
    ): String
    fun searchScrollback(query: String, useRegex: Boolean, caseSensitive: Boolean): List<VtSearchMatch>
    fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink?
}

interface GlRendererBackend : AutoCloseable {
    fun setShaders(shaders: RendererShaderPack)
    fun initGl(): Boolean
    fun releaseGl()
    fun notifyContextLost(newEpoch: Long)
    fun setBundledFonts(pack: BundledFontPack)
    fun configureFont(config: FontConfig, density: Float): CellMetrics
    fun detachSession(sessionHandle: Long)
    fun drawFrame(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        forceFullRedraw: Boolean,
        surfaceClearRgba: UInt,
        panes: List<PaneRenderTarget>,
        dividers: List<SplitDividerRect>,
    ): Boolean
}

internal class JniVtSessionBackend(
    cols: Int,
    rows: Int,
    maxScrollback: Int,
) : VtSessionBackend {
    private var handle: Long = JniVtBindings.nativeCreateSession(cols, rows, maxScrollback)

    init {
        check(handle != 0L) { "Failed to allocate native GhosttyTerminal session" }
    }

    override val nativeHandle: Long
        get() = handle

    override fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean {
        if (handle == 0L) return false
        return JniVtBindings.nativeResizeGrid(handle, cols, rows, cellWidthPx, cellHeightPx)
    }

    override fun writePtyBytes(data: ByteArray, offset: Int, length: Int) {
        if (handle == 0L || length <= 0) return
        JniVtBindings.nativeWritePtyBytes(handle, data, offset, length)
    }

    override fun setPalette(palette: ColorPalette) {
        if (handle == 0L) return
        JniVtBindings.nativeSetPalette(
            handle = handle,
            fgRgba = palette.foregroundRgba.toInt(),
            bgRgba = palette.backgroundRgba.toInt(),
            cursorRgba = palette.cursorRgba.toInt(),
            selectionRgba = palette.selectionRgba.toInt(),
            ansi16 = IntArray(ColorPalette.ANSI_COLOR_COUNT) { idx -> palette.ansi16Rgba[idx].toInt() },
        )
    }

    override fun setScrollbackOffset(linesFromBottom: Int) {
        if (handle != 0L) JniVtBindings.nativeSetScrollbackOffset(handle, linesFromBottom)
    }

    override fun getScrollbackOffset(): Int =
        if (handle == 0L) 0 else JniVtBindings.nativeGetScrollbackOffset(handle)

    override fun getScrollbackCount(): Int =
        if (handle == 0L) 0 else JniVtBindings.nativeGetScrollbackCount(handle)

    override fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        if (handle != 0L) JniVtBindings.nativeSetSelection(handle, active, startRow, startCol, endRow, endCol)
    }

    override fun clearSelection() {
        if (handle != 0L) JniVtBindings.nativeClearSelection(handle)
    }

    override fun isDirty(): Boolean = handle != 0L && JniVtBindings.nativeIsDirty(handle)

    override fun isMouseTrackingActive(): Boolean =
        handle != 0L && JniVtBindings.nativeIsMouseTrackingActive(handle)

    override fun isBracketedPasteActive(): Boolean =
        handle != 0L && JniVtBindings.nativeIsBracketedPasteActive(handle)

    override fun isApplicationCursorActive(): Boolean =
        handle != 0L && JniVtBindings.nativeIsApplicationCursorActive(handle)

    override fun getWindowTitle(): String =
        if (handle == 0L) "" else JniVtBindings.nativeGetWindowTitle(handle)

    override fun getCursorState(): VtCursorState {
        if (handle == 0L) {
            return VtCursorState(0, 0, visible = false, blink = false, shape = VtCursorShape.BLOCK)
        }
        val arr = JniVtBindings.nativeGetCursorState(handle)
        return VtCursorState(
            col = arr.getOrElse(0) { 0 },
            row = arr.getOrElse(1) { 0 },
            visible = arr.getOrElse(2) { 1 } != 0,
            blink = arr.getOrElse(3) { 1 } != 0,
            shape = VtCursorShape.fromNativeCode(arr.getOrElse(4) { 1 }),
        )
    }

    override fun encodeKey(event: VtKeyEvent): ByteArray {
        if (handle == 0L) return ByteArray(0)
        return JniVtBindings.nativeEncodeKey(
            handle = handle,
            keyCode = event.key.nativeCode,
            action = event.action.nativeCode,
            mods = event.modifierMask,
            codepoint = event.unicodeCodepoint,
            utf8Text = event.utf8Text,
        )
    }

    override fun encodeMouse(event: VtMouseEvent): ByteArray {
        if (handle == 0L) return ByteArray(0)
        return JniVtBindings.nativeEncodeMouse(
            handle = handle,
            action = event.action.nativeCode,
            button = event.button.nativeCode,
            mods = event.modifierMask,
            col = event.col,
            row = event.row,
            pixelX = event.pixelX,
            pixelY = event.pixelY,
        )
    }

    override fun isPasteSafe(text: String): Boolean = JniVtBindings.nativeIsPasteSafe(text)

    override fun encodePaste(text: String, allowUnsafe: Boolean): ByteArray? {
        if (handle == 0L) return null
        return JniVtBindings.nativeEncodePaste(handle, text, allowUnsafe)
    }

    override fun formatText(
        emitVt: Boolean,
        useSelection: Boolean,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        trimTrailingWhitespace: Boolean,
        unwrapSoftWraps: Boolean,
    ): String {
        if (handle == 0L) return ""
        return JniVtBindings.nativeFormatText(
            handle, emitVt, useSelection, startRow, startCol, endRow, endCol,
            trimTrailingWhitespace, unwrapSoftWraps,
        )
    }

    override fun searchScrollback(query: String, useRegex: Boolean, caseSensitive: Boolean): List<VtSearchMatch> {
        if (handle == 0L) return emptyList()
        val raw = JniVtBindings.nativeSearchScrollback(handle, query, useRegex, caseSensitive)
        return raw.mapNotNull { entry ->
            val parts = entry.split('\t', limit = 5)
            if (parts.size < 5) null else VtSearchMatch(
                absoluteLine = parts[0].toIntOrNull() ?: 0,
                viewportRow = parts[1].toIntOrNull() ?: -1,
                startCol = parts[2].toIntOrNull() ?: 0,
                endCol = parts[3].toIntOrNull() ?: 0,
                matchedText = parts[4],
            )
        }
    }

    override fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? {
        if (handle == 0L) return null
        val packed = JniVtBindings.nativeDetectLinkAt(handle, viewportRow, viewportCol) ?: return null
        val parts = packed.split('\t', limit = 6)
        if (parts.size < 6) return null
        return VtDetectedLink(
            kind = VtLinkKind.fromNativeCode(parts[0].toIntOrNull() ?: 0),
            startRow = parts[1].toIntOrNull() ?: viewportRow,
            startCol = parts[2].toIntOrNull() ?: viewportCol,
            endRow = parts[3].toIntOrNull() ?: viewportRow,
            endCol = parts[4].toIntOrNull() ?: viewportCol,
            target = parts[5],
        )
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            JniVtBindings.nativeDestroySession(h)
        }
    }
}

internal class JniGlRendererBackend : GlRendererBackend {
    private var handle: Long = JniVtBindings.nativeCreateRenderer()

    init {
        check(handle != 0L) { "Failed to allocate native Gles3Renderer" }
    }

    override fun setShaders(shaders: RendererShaderPack) {
        if (handle != 0L) {
            JniVtBindings.nativeRendererSetShaders(
                handle, shaders.bgVert, shaders.bgFrag, shaders.glyphVert, shaders.glyphFrag,
            )
        }
    }

    override fun initGl(): Boolean = handle != 0L && JniVtBindings.nativeRendererInitGl(handle)

    override fun releaseGl() {
        if (handle != 0L) JniVtBindings.nativeRendererReleaseGl(handle)
    }

    override fun notifyContextLost(newEpoch: Long) {
        if (handle != 0L) JniVtBindings.nativeRendererContextLost(handle, newEpoch)
    }

    override fun setBundledFonts(pack: BundledFontPack) {
        if (handle != 0L) {
            JniVtBindings.nativeRendererSetBundledFonts(
                handle,
                pack.jetbrainsRegular,
                pack.jetbrainsBold,
                pack.jetbrainsItalic,
                pack.jetbrainsBoldItalic,
                pack.nerdSymbols,
            )
        }
    }

    override fun configureFont(config: FontConfig, density: Float): CellMetrics {
        if (handle == 0L) return CellMetrics(10, 20, 16)
        val arr = JniVtBindings.nativeRendererConfigureFont(
            rendererHandle = handle,
            familyId = config.familyId,
            customPath = config.customFontPath ?: "",
            customSha256 = config.customFontSha256 ?: "",
            sizeSp = config.sizeSp,
            cellHeightMultiplier = config.cellHeightMultiplier,
            density = density,
            ligaturesEnabled = config.ligaturesEnabled,
            nerdSymbolsEnabled = config.nerdFontSymbolsEnabled,
        )
        return CellMetrics(
            cellWidthPx = max(1, arr.getOrElse(0) { 10 }),
            cellHeightPx = max(1, arr.getOrElse(1) { 20 }),
            baselinePx = max(1, arr.getOrElse(2) { 16 }),
        )
    }

    override fun detachSession(sessionHandle: Long) {
        if (handle != 0L && sessionHandle != 0L) {
            JniVtBindings.nativeRendererDetachSession(handle, sessionHandle)
        }
    }

    override fun drawFrame(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        forceFullRedraw: Boolean,
        surfaceClearRgba: UInt,
        panes: List<PaneRenderTarget>,
        dividers: List<SplitDividerRect>,
    ): Boolean {
        if (handle == 0L) return false
        val count = panes.size
        val handles = LongArray(count)
        val bounds = IntArray(count * 8)
        val preeditTexts = arrayOfNulls<String>(count)
        val preeditColors = IntArray(count * 3)

        for (i in 0 until count) {
            val p = panes[i]
            handles[i] = p.session.nativeHandle
            val bi = i * 8
            bounds[bi + 0] = p.xPx
            bounds[bi + 1] = p.yPx
            bounds[bi + 2] = p.widthPx
            bounds[bi + 3] = p.heightPx
            bounds[bi + 4] = if (p.focused) 1 else 0
            bounds[bi + 5] = if (p.cursorBlinkVisible) 1 else 0
            bounds[bi + 6] = p.preeditCursorCol
            bounds[bi + 7] = p.preeditCursorRow
            preeditTexts[i] = p.preeditText
            val ci = i * 3
            preeditColors[ci + 0] = p.preeditFgRgba.toInt()
            preeditColors[ci + 1] = p.preeditBgRgba.toInt()
            preeditColors[ci + 2] = p.preeditUnderlineRgba.toInt()
        }

        val divData = IntArray(dividers.size * 5)
        for (i in dividers.indices) {
            val d = dividers[i]
            val di = i * 5
            divData[di + 0] = d.xPx
            divData[di + 1] = d.yPx
            divData[di + 2] = d.widthPx
            divData[di + 3] = d.heightPx
            divData[di + 4] = d.rgba.toInt()
        }

        return JniVtBindings.nativeRendererDrawFrame(
            rendererHandle = handle,
            surfaceWidthPx = surfaceWidthPx,
            surfaceHeightPx = surfaceHeightPx,
            forceFullRedraw = forceFullRedraw,
            surfaceClearRgba = surfaceClearRgba.toInt(),
            paneSessionHandles = handles,
            paneBoundsAndFlags = bounds,
            panePreeditTexts = preeditTexts,
            panePreeditColors = preeditColors,
            dividerRectsAndColors = divData,
        )
    }

    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            JniVtBindings.nativeDestroyRenderer(h)
        }
    }
}

/**
 * Pure-JVM VT backend used when running host JUnit 5 tests without loading Android `.so` binaries.
 */
internal class JvmFallbackVtSessionBackend(
    initialCols: Int,
    initialRows: Int,
    private val maxScrollback: Int,
) : VtSessionBackend {
    private val lock = ReentrantLock()
    private var cols = max(1, initialCols)
    private var rows = max(1, initialRows)
    private var grid = Array(rows) { CharArray(cols) { ' ' } }
    private val scrollback = ArrayDeque<CharArray>()
    private var cursorRow = 0
    private var cursorCol = 0
    private var cursorVisible = true
    private var cursorBlink = true
    private var cursorShape = VtCursorShape.BLOCK
    private var decckm = false
    private var bracketedPaste = false
    private var mouseTrackingMode = 0
    private var mouseFormatMode = 0
    private var scrollbackOffset = 0
    private var dirty = true
    private var closed = false
    private var windowTitle = ""
    private var palette = ColorPalette.DEFAULT_DARK
    private var selActive = false
    private var selStartAbsRow = 0
    private var selStartCol = 0
    private var selEndAbsRow = 0
    private var selEndCol = 0

    private var inEsc = false
    private var inCsi = false
    private var inOsc = false
    private val escBuf = StringBuilder()

    override val nativeHandle: Long = 0L

    override fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean = lock.withLock {
        if (closed || cols <= 0 || rows <= 0) return@withLock false
        val newGrid = Array(rows) { r ->
            CharArray(cols) { c ->
                if (r < this.rows && c < this.cols) grid[r][c] else ' '
            }
        }
        this.cols = cols
        this.rows = rows
        this.grid = newGrid
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        dirty = true
        true
    }

    override fun writePtyBytes(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset < 0 || offset >= data.size) return
        val end = min(data.size, offset + length)
        val text = String(data, offset, end - offset, StandardCharsets.UTF_8)
        lock.withLock {
            if (closed) return
            for (ch in text) {
                feedChar(ch)
            }
            dirty = true
        }
    }

    private fun feedChar(ch: Char) {
        if (inOsc) {
            if (ch == '\u0007') {
                val content = escBuf.toString()
                val semi = content.indexOf(';')
                if (semi > 0) {
                    val code = content.substring(0, semi).toIntOrNull()
                    if (code == 0 || code == 2) {
                        windowTitle = content.substring(semi + 1)
                    }
                }
                escBuf.clear()
                inOsc = false
            } else if (ch == '\u001b') {
                inEsc = true
            } else if (inEsc && ch == '\\') {
                val content = escBuf.toString()
                val semi = content.indexOf(';')
                if (semi > 0) {
                    val code = content.substring(0, semi).toIntOrNull()
                    if (code == 0 || code == 2) {
                        windowTitle = content.substring(semi + 1)
                    }
                }
                escBuf.clear()
                inOsc = false
                inEsc = false
            } else {
                inEsc = false
                if (escBuf.length < 4096) escBuf.append(ch)
            }
            return
        }
        if (inCsi) {
            if (ch.code in 0x40..0x7E) {
                dispatchCsi(escBuf.toString(), ch)
                escBuf.clear()
                inCsi = false
            } else {
                if (escBuf.length < 128) escBuf.append(ch)
            }
            return
        }
        if (inEsc) {
            inEsc = false
            when (ch) {
                '[' -> {
                    inCsi = true
                    escBuf.clear()
                }
                ']' -> {
                    inOsc = true
                    escBuf.clear()
                }
                'c' -> {
                    grid = Array(rows) { CharArray(cols) { ' ' } }
                    cursorRow = 0
                    cursorCol = 0
                    decckm = false
                    bracketedPaste = false
                    mouseTrackingMode = 0
                    mouseFormatMode = 0
                }
            }
            return
        }
        when (ch) {
            '\u001b' -> inEsc = true
            '\r' -> cursorCol = 0
            '\n' -> lineFeed()
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> cursorCol = min(cols - 1, ((cursorCol / 8) + 1) * 8)
            else -> if (ch.code >= 0x20) {
                if (cursorCol >= cols) {
                    cursorCol = 0
                    lineFeed()
                }
                grid[cursorRow][cursorCol] = ch
                cursorCol++
            }
        }
    }

    private fun lineFeed() {
        if (cursorRow + 1 < rows) {
            cursorRow++
        } else {
            scrollback.addLast(grid[0])
            while (scrollback.size > max(1, maxScrollback)) {
                scrollback.removeFirst()
            }
            for (r in 0 until rows - 1) {
                grid[r] = grid[r + 1]
            }
            grid[rows - 1] = CharArray(cols) { ' ' }
        }
    }

    private fun dispatchCsi(raw: String, finalChar: Char) {
        val prefix = if (raw.startsWith("?") || raw.startsWith(">")) raw[0] else '\u0000'
        val body = if (prefix != '\u0000') raw.substring(1) else raw
        val params = if (body.isEmpty()) emptyList() else body.split(';').map { it.toIntOrNull() ?: 0 }
        if (prefix == '?' && (finalChar == 'h' || finalChar == 'l')) {
            val enable = finalChar == 'h'
            for (m in params) {
                when (m) {
                    1 -> decckm = enable
                    12 -> cursorBlink = enable
                    25 -> cursorVisible = enable
                    1000, 1002, 1003 -> if (enable) mouseTrackingMode = m else if (mouseTrackingMode == m) mouseTrackingMode = 0
                    1005, 1006 -> if (enable) mouseFormatMode = m else if (mouseFormatMode == m) mouseFormatMode = 0
                    2004 -> bracketedPaste = enable
                }
            }
            return
        }
        when (finalChar) {
            'H', 'f' -> {
                val r = (params.getOrElse(0) { 1 }.takeIf { it > 0 } ?: 1) - 1
                val c = (params.getOrElse(1) { 1 }.takeIf { it > 0 } ?: 1) - 1
                cursorRow = r.coerceIn(0, rows - 1)
                cursorCol = c.coerceIn(0, cols - 1)
            }
            'J' -> if (params.getOrElse(0) { 0 } == 2) {
                grid = Array(rows) { CharArray(cols) { ' ' } }
            }
            'K' -> for (c in cursorCol until cols) grid[cursorRow][c] = ' '
        }
    }

    override fun setPalette(palette: ColorPalette) {
        lock.withLock {
            this.palette = palette
            dirty = true
        }
    }

    override fun setScrollbackOffset(linesFromBottom: Int) {
        lock.withLock {
            scrollbackOffset = linesFromBottom.coerceIn(0, scrollback.size)
            dirty = true
        }
    }

    override fun getScrollbackOffset(): Int = lock.withLock { scrollbackOffset }
    override fun getScrollbackCount(): Int = lock.withLock { scrollback.size }

    override fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        lock.withLock {
            val base = scrollback.size - scrollbackOffset
            selActive = active
            selStartAbsRow = base + startRow
            selStartCol = startCol
            selEndAbsRow = base + endRow
            selEndCol = endCol
            dirty = true
        }
    }

    override fun clearSelection() {
        lock.withLock {
            selActive = false
            dirty = true
        }
    }

    override fun isDirty(): Boolean = lock.withLock { dirty }
    override fun isMouseTrackingActive(): Boolean = lock.withLock { mouseTrackingMode != 0 }
    override fun isBracketedPasteActive(): Boolean = lock.withLock { bracketedPaste }
    override fun isApplicationCursorActive(): Boolean = lock.withLock { decckm }
    override fun getWindowTitle(): String = lock.withLock { windowTitle }

    override fun getCursorState(): VtCursorState = lock.withLock {
        VtCursorState(
            col = cursorCol.coerceIn(0, cols - 1),
            row = cursorRow,
            visible = cursorVisible && scrollbackOffset == 0,
            blink = cursorBlink,
            shape = cursorShape,
        )
    }

    override fun encodeKey(event: VtKeyEvent): ByteArray = lock.withLock {
        if (event.action == VtKeyAction.RELEASE) return@withLock ByteArray(0)
        val xmod = 1 +
            (if ((event.modifierMask and VtModifierMask.SHIFT) != 0) 1 else 0) +
            (if ((event.modifierMask and VtModifierMask.ALT) != 0) 2 else 0) +
            (if ((event.modifierMask and VtModifierMask.CTRL) != 0) 4 else 0) +
            (if ((event.modifierMask and VtModifierMask.SUPER) != 0) 8 else 0)
        val seq = when (event.key) {
            VtKey.ESCAPE -> "\u001b"
            VtKey.ENTER -> if ((event.modifierMask and VtModifierMask.ALT) != 0) "\u001b\r" else "\r"
            VtKey.TAB -> if ((event.modifierMask and VtModifierMask.SHIFT) != 0) "\u001b[Z" else "\t"
            VtKey.BACKSPACE -> if ((event.modifierMask and VtModifierMask.CTRL) != 0) "\u0008" else "\u007f"
            VtKey.UP -> if (xmod > 1) "\u001b[1;${xmod}A" else if (decckm) "\u001bOA" else "\u001b[A"
            VtKey.DOWN -> if (xmod > 1) "\u001b[1;${xmod}B" else if (decckm) "\u001bOB" else "\u001b[B"
            VtKey.RIGHT -> if (xmod > 1) "\u001b[1;${xmod}C" else if (decckm) "\u001bOC" else "\u001b[C"
            VtKey.LEFT -> if (xmod > 1) "\u001b[1;${xmod}D" else if (decckm) "\u001bOD" else "\u001b[D"
            VtKey.HOME -> if (xmod > 1) "\u001b[1;${xmod}H" else if (decckm) "\u001bOH" else "\u001b[H"
            VtKey.END -> if (xmod > 1) "\u001b[1;${xmod}F" else if (decckm) "\u001bOF" else "\u001b[F"
            VtKey.INSERT -> if (xmod > 1) "\u001b[2;${xmod}~" else "\u001b[2~"
            VtKey.DELETE -> if (xmod > 1) "\u001b[3;${xmod}~" else "\u001b[3~"
            VtKey.PAGE_UP -> if (xmod > 1) "\u001b[5;${xmod}~" else "\u001b[5~"
            VtKey.PAGE_DOWN -> if (xmod > 1) "\u001b[6;${xmod}~" else "\u001b[6~"
            VtKey.UNICODE -> {
                val cp = event.unicodeCodepoint
                val prefix = if ((event.modifierMask and VtModifierMask.ALT) != 0) "\u001b" else ""
                if ((event.modifierMask and VtModifierMask.CTRL) != 0 && cp in 32..126) {
                    val upper = cp.toChar().uppercaseChar().code
                    val ctrlByte = when (upper) {
                        ' '.code, '@'.code -> 0
                        in 'A'.code..'_'.code -> upper - 0x40
                        '?'.code -> 0x7f
                        '/'.code -> 0x1f
                        else -> cp
                    }
                    prefix + ctrlByte.toChar()
                } else {
                    prefix + (event.utf8Text ?: String(Character.toChars(max(1, cp))))
                }
            }
            else -> ""
        }
        seq.toByteArray(StandardCharsets.UTF_8)
    }

    override fun encodeMouse(event: VtMouseEvent): ByteArray = lock.withLock {
        if (mouseTrackingMode == 0) return@withLock ByteArray(0)
        var cb = when (event.action) {
            VtMouseAction.WHEEL_UP -> 64
            VtMouseAction.WHEEL_DOWN -> 65
            VtMouseAction.RELEASE -> if (mouseFormatMode == 1006) {
                when (event.button) {
                    VtMouseButton.LEFT -> 0
                    VtMouseButton.MIDDLE -> 1
                    VtMouseButton.RIGHT -> 2
                    VtMouseButton.NONE -> 3
                }
            } else 3
            else -> when (event.button) {
                VtMouseButton.LEFT -> 0
                VtMouseButton.MIDDLE -> 1
                VtMouseButton.RIGHT -> 2
                VtMouseButton.NONE -> 3
            }
        }
        if (event.action == VtMouseAction.MOTION) cb += 32
        if ((event.modifierMask and VtModifierMask.SHIFT) != 0) cb += 4
        if ((event.modifierMask and VtModifierMask.ALT) != 0) cb += 8
        if ((event.modifierMask and VtModifierMask.CTRL) != 0) cb += 16
        val cx = event.col + 1
        val cy = event.row + 1
        val out = if (mouseFormatMode == 1006) {
            val fin = if (event.action == VtMouseAction.RELEASE) 'm' else 'M'
            "\u001b[<$cb;$cx;$cy$fin"
        } else {
            if (cx > 223 || cy > 223) "" else "\u001b[M${(cb + 32).toChar()}${(cx + 32).toChar()}${(cy + 32).toChar()}"
        }
        out.toByteArray(StandardCharsets.UTF_8)
    }

    override fun isPasteSafe(text: String): Boolean {
        return text.none {
            it == '\n' || it == '\r' || it == '\u001b' || it == '\u0000' ||
                it == '\u0003' || it == '\u0004' || it == '\u0085' || it == '\u2028' || it == '\u2029'
        }
    }

    override fun encodePaste(text: String, allowUnsafe: Boolean): ByteArray? = lock.withLock {
        if (!allowUnsafe && !isPasteSafe(text)) return@withLock null
        val sanitized = text.replace("\u001b[201~", "")
        val wrapped = if (bracketedPaste) {
            "\u001b[200~$sanitized\u001b[201~"
        } else {
            sanitized.replace('\n', '\r')
        }
        wrapped.toByteArray(StandardCharsets.UTF_8)
    }

    private fun getViewportRow(vrow: Int): CharArray {
        val abs = (scrollback.size - scrollbackOffset) + vrow
        return if (abs < scrollback.size) {
            scrollback.elementAt(max(0, abs))
        } else {
            grid[(abs - scrollback.size).coerceIn(0, rows - 1)]
        }
    }

    override fun formatText(
        emitVt: Boolean,
        useSelection: Boolean,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        trimTrailingWhitespace: Boolean,
        unwrapSoftWraps: Boolean,
    ): String = lock.withLock {
        val r0: Int
        val c0: Int
        val r1: Int
        val c1: Int
        val getRow: (Int) -> CharArray?
        if (useSelection) {
            if (!selActive) return@withLock ""
            val swap = selStartAbsRow > selEndAbsRow || (selStartAbsRow == selEndAbsRow && selStartCol > selEndCol)
            r0 = if (swap) selEndAbsRow else selStartAbsRow
            c0 = if (swap) selEndCol else selStartCol
            r1 = if (swap) selStartAbsRow else selEndAbsRow
            c1 = if (swap) selStartCol else selEndCol
            getRow = { absR ->
                when {
                    absR < 0 -> null
                    absR < scrollback.size -> scrollback.elementAt(absR)
                    absR - scrollback.size < rows -> grid[absR - scrollback.size]
                    else -> null
                }
            }
        } else {
            if (endRow < 0) {
                r0 = 0; c0 = 0; r1 = rows - 1; c1 = cols - 1
            } else {
                val swap = startRow > endRow || (startRow == endRow && startCol > endCol)
                r0 = if (swap) endRow else startRow
                c0 = if (swap) endCol else startCol
                r1 = if (swap) startRow else endRow
                c1 = if (swap) startCol else endCol
            }
            getRow = { vr -> if (vr in 0 until rows) getViewportRow(vr) else null }
        }

        val sb = StringBuilder()
        for (r in r0..r1) {
            val rowChars = getRow(r) ?: continue
            val cs = if (r == r0) c0.coerceAtLeast(0) else 0
            val ce = if (r == r1) min(rowChars.size - 1, c1) else rowChars.size - 1
            var line = if (cs <= ce) String(rowChars, cs, ce - cs + 1) else ""
            if (trimTrailingWhitespace && !emitVt) {
                line = line.trimEnd(' ')
            }
            sb.append(line)
            if (r < r1) sb.append('\n')
        }
        sb.toString()
    }

    override fun searchScrollback(query: String, useRegex: Boolean, caseSensitive: Boolean): List<VtSearchMatch> =
        lock.withLock {
            if (query.isEmpty()) return@withLock emptyList()
            val regex = if (useRegex) {
                runCatching {
                    val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    Regex(query, opts)
                }.getOrNull() ?: return@withLock emptyList()
            } else {
                null
            }
            val total = scrollback.size + rows
            val viewportTop = scrollback.size - scrollbackOffset
            val out = mutableListOf<VtSearchMatch>()
            for (abs in 0 until total) {
                val rowChars = if (abs < scrollback.size) scrollback.elementAt(abs) else grid[abs - scrollback.size]
                val line = String(rowChars).trimEnd(' ')
                if (line.isEmpty()) continue
                val vrow = if (abs in viewportTop until (viewportTop + rows)) abs - viewportTop else -1
                if (regex != null) {
                    for (m in regex.findAll(line)) {
                        if (m.range.first <= m.range.last) {
                            out += VtSearchMatch(abs, vrow, m.range.first, m.range.last, m.value)
                        }
                    }
                } else {
                    var idx = 0
                    while (true) {
                        val hit = line.indexOf(query, startIndex = idx, ignoreCase = !caseSensitive)
                        if (hit < 0) break
                        out += VtSearchMatch(
                            abs,
                            vrow,
                            hit,
                            hit + query.length - 1,
                            line.substring(hit, hit + query.length),
                        )
                        idx = hit + max(1, query.length)
                    }
                }
            }
            out
        }

    override fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? = lock.withLock {
        if (viewportRow !in 0 until rows || viewportCol !in 0 until cols) return@withLock null
        val line = String(getViewportRow(viewportRow)).trimEnd(' ')
        val urlRegex = Regex("""https?://[^\s<>'"`]+""")
        for (m in urlRegex.findAll(line)) {
            val trimmed = m.value.trimEnd('.', ',', ';', ')')
            val endCol = m.range.first + trimmed.length - 1
            if (viewportCol in m.range.first..endCol) {
                return@withLock VtDetectedLink(VtLinkKind.URL, viewportRow, m.range.first, viewportRow, endCol, trimmed)
            }
        }
        val pathRegex = Regex("""(?:~|/|\./|\.\./)[A-Za-z0-9._/\-]+(?::[0-9]+(?::[0-9]+)?)?""")
        for (m in pathRegex.findAll(line)) {
            if (m.value.length >= 2 && viewportCol in m.range) {
                return@withLock VtDetectedLink(
                    VtLinkKind.FILEPATH, viewportRow, m.range.first, viewportRow, m.range.last, m.value,
                )
            }
        }
        null
    }

    override fun close() {
        lock.withLock {
            closed = true
        }
    }
}

internal class JvmFallbackGlRendererBackend : GlRendererBackend {
    private var cellMetrics = CellMetrics(10, 20, 16)
    private var closed = false

    override fun setShaders(shaders: RendererShaderPack) = Unit
    override fun initGl(): Boolean = !closed
    override fun releaseGl() = Unit
    override fun notifyContextLost(newEpoch: Long) = Unit
    override fun setBundledFonts(pack: BundledFontPack) = Unit

    override fun configureFont(config: FontConfig, density: Float): CellMetrics {
        val px = config.sizeSp * density.coerceIn(0.75f, 6.0f)
        val w = max(6, (px * 0.6f).roundToInt())
        val h = max(10, (px * config.cellHeightMultiplier).roundToInt())
        val base = max(1, (h * 0.78f).roundToInt())
        cellMetrics = CellMetrics(w, h, base)
        return cellMetrics
    }

    override fun detachSession(sessionHandle: Long) = Unit

    override fun drawFrame(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        forceFullRedraw: Boolean,
        surfaceClearRgba: UInt,
        panes: List<PaneRenderTarget>,
        dividers: List<SplitDividerRect>,
    ): Boolean = !closed && surfaceWidthPx > 0 && surfaceHeightPx > 0

    override fun close() {
        closed = true
    }
}
