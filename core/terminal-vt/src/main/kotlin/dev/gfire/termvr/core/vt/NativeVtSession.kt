package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.ColorPalette
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe Kotlin handle to one Ghostty VT terminal session with deterministic close teardown.
 */
class NativeVtSession(
    initialCols: Int = DEFAULT_COLS,
    initialRows: Int = DEFAULT_ROWS,
    val maxScrollbackLines: Int = DEFAULT_MAX_SCROLLBACK_LINES,
    private val onDetachPane: ((Long) -> Unit)? = null,
    backendFactory: ((Int, Int, Int) -> VtSessionBackend)? = null,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val backend: VtSessionBackend = when {
        backendFactory != null -> backendFactory(initialCols, initialRows, maxScrollbackLines)
        JniVtBindings.isNativeAvailable() -> JniVtSessionBackend(initialCols, initialRows, maxScrollbackLines)
        else -> JvmFallbackVtSessionBackend(initialCols, initialRows, maxScrollbackLines)
    }

    @Volatile
    var cols: Int = initialCols.coerceAtLeast(1)
        private set

    @Volatile
    var rows: Int = initialRows.coerceAtLeast(1)
        private set

    val nativeHandle: Long
        get() = if (closed.get()) 0L else backend.nativeHandle

    val isClosed: Boolean
        get() = closed.get()

    fun resizeGrid(
        newCols: Int,
        newRows: Int,
        cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX,
        cellHeightPx: Int = DEFAULT_CELL_HEIGHT_PX,
    ): Boolean {
        if (closed.get() || newCols <= 0 || newRows <= 0) return false
        val ok = backend.resizeGrid(
            cols = newCols,
            rows = newRows,
            cellWidthPx = cellWidthPx.coerceAtLeast(1),
            cellHeightPx = cellHeightPx.coerceAtLeast(1),
        )
        if (ok) {
            cols = newCols
            rows = newRows
        }
        return ok
    }

    fun writePtyBytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (closed.get() || length <= 0 || offset < 0 || offset >= data.size) return
        backend.writePtyBytes(data, offset, length)
    }

    fun setPalette(palette: ColorPalette) {
        if (closed.get()) return
        backend.setPalette(palette)
    }

    fun setScrollbackOffset(linesFromBottom: Int) {
        if (closed.get()) return
        backend.setScrollbackOffset(linesFromBottom.coerceAtLeast(0))
    }

    fun getScrollbackOffset(): Int = if (closed.get()) 0 else backend.getScrollbackOffset()

    fun getScrollbackCount(): Int = if (closed.get()) 0 else backend.getScrollbackCount()

    fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        if (closed.get()) return
        backend.setSelection(active, startRow, startCol, endRow, endCol)
    }

    fun clearSelection() {
        if (closed.get()) return
        backend.clearSelection()
    }

    fun isDirty(): Boolean = !closed.get() && backend.isDirty()

    fun isMouseTrackingActive(): Boolean = !closed.get() && backend.isMouseTrackingActive()

    fun isBracketedPasteActive(): Boolean = !closed.get() && backend.isBracketedPasteActive()

    fun isApplicationCursorActive(): Boolean = !closed.get() && backend.isApplicationCursorActive()

    fun getWindowTitle(): String = if (closed.get()) "" else backend.getWindowTitle()

    fun getCursorState(): VtCursorState = if (closed.get()) {
        VtCursorState(0, 0, visible = false, blink = false, shape = VtCursorShape.BLOCK)
    } else {
        backend.getCursorState()
    }

    fun encodeKey(event: VtKeyEvent): ByteArray =
        if (closed.get()) ByteArray(0) else backend.encodeKey(event)

    fun encodeMouse(event: VtMouseEvent): ByteArray =
        if (closed.get()) ByteArray(0) else backend.encodeMouse(event)

    fun isPasteSafe(text: String): Boolean = backend.isPasteSafe(text)

    fun encodePaste(text: String, allowUnsafe: Boolean = false): ByteArray? =
        if (closed.get()) null else backend.encodePaste(text, allowUnsafe)

    fun formatViewportText(
        emitVt: Boolean = false,
        trimTrailingWhitespace: Boolean = true,
        unwrapSoftWraps: Boolean = true,
    ): String {
        if (closed.get()) return ""
        return backend.formatText(
            emitVt = emitVt,
            useSelection = false,
            startRow = 0,
            startCol = 0,
            endRow = -1,
            endCol = -1,
            trimTrailingWhitespace = trimTrailingWhitespace,
            unwrapSoftWraps = unwrapSoftWraps,
        )
    }

    fun formatRangeText(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        emitVt: Boolean = false,
        trimTrailingWhitespace: Boolean = true,
        unwrapSoftWraps: Boolean = true,
    ): String {
        if (closed.get()) return ""
        return backend.formatText(
            emitVt = emitVt,
            useSelection = false,
            startRow = startRow,
            startCol = startCol,
            endRow = endRow,
            endCol = endCol,
            trimTrailingWhitespace = trimTrailingWhitespace,
            unwrapSoftWraps = unwrapSoftWraps,
        )
    }

    fun formatSelectionText(
        emitVt: Boolean = false,
        trimTrailingWhitespace: Boolean = true,
        unwrapSoftWraps: Boolean = true,
    ): String {
        if (closed.get()) return ""
        return backend.formatText(
            emitVt = emitVt,
            useSelection = true,
            startRow = 0,
            startCol = 0,
            endRow = 0,
            endCol = 0,
            trimTrailingWhitespace = trimTrailingWhitespace,
            unwrapSoftWraps = unwrapSoftWraps,
        )
    }

    fun searchScrollback(
        query: String,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false,
    ): List<VtSearchMatch> {
        if (closed.get() || query.isEmpty()) return emptyList()
        return backend.searchScrollback(query, useRegex, caseSensitive)
    }

    fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? {
        if (closed.get() || viewportRow < 0 || viewportCol < 0) return null
        return backend.detectLinkAt(viewportRow, viewportCol)
    }

    /**
     * Detaches the pane from the GL render thread before freeing native VT state.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = backend.nativeHandle
        if (handle != 0L) {
            onDetachPane?.invoke(handle)
        }
        backend.close()
    }

    companion object {
        const val DEFAULT_COLS: Int = 80
        const val DEFAULT_ROWS: Int = 24
        const val DEFAULT_MAX_SCROLLBACK_LINES: Int = 10_000
        const val DEFAULT_CELL_WIDTH_PX: Int = 10
        const val DEFAULT_CELL_HEIGHT_PX: Int = 20

        fun isNativeLibraryLoaded(): Boolean = JniVtBindings.isNativeAvailable()
    }
}
