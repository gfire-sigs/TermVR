package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.ColorPalette
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

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
    private val backendLock = ReentrantReadWriteLock()
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

    private inline fun <T> withOpenBackend(defaultValue: T, block: (VtSessionBackend) -> T): T {
        if (closed.get()) return defaultValue
        return backendLock.read {
            if (closed.get()) defaultValue else block(backend)
        }
    }

    fun resizeGrid(
        newCols: Int,
        newRows: Int,
        cellWidthPx: Int = DEFAULT_CELL_WIDTH_PX,
        cellHeightPx: Int = DEFAULT_CELL_HEIGHT_PX,
    ): Boolean = withOpenBackend(false) {
        if (newCols <= 0 || newRows <= 0) return@withOpenBackend false
        val ok = it.resizeGrid(
            cols = newCols,
            rows = newRows,
            cellWidthPx = cellWidthPx.coerceAtLeast(1),
            cellHeightPx = cellHeightPx.coerceAtLeast(1),
        )
        if (ok) {
            cols = newCols
            rows = newRows
        }
        ok
    }

    fun writePtyBytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0 || offset < 0 || offset >= data.size) return
        withOpenBackend(Unit) { it.writePtyBytes(data, offset, length) }
    }

    /**
     * Drains terminal query replies queued during VT processing.
     */
    fun drainPtyReplies(): ByteArray = withOpenBackend(ByteArray(0)) { it.drainPtyReplies() }

    fun setPalette(palette: ColorPalette) {
        withOpenBackend(Unit) { it.setPalette(palette) }
    }

    fun setScrollbackOffset(linesFromBottom: Int) {
        withOpenBackend(Unit) { it.setScrollbackOffset(linesFromBottom.coerceAtLeast(0)) }
    }

    fun getScrollbackOffset(): Int = withOpenBackend(0) { it.getScrollbackOffset() }

    fun getScrollbackCount(): Int = withOpenBackend(0) { it.getScrollbackCount() }

    fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        withOpenBackend(Unit) { it.setSelection(active, startRow, startCol, endRow, endCol) }
    }

    fun clearSelection() {
        withOpenBackend(Unit) { it.clearSelection() }
    }

    fun isDirty(): Boolean = withOpenBackend(false) { it.isDirty() }

    fun isMouseTrackingActive(): Boolean = withOpenBackend(false) { it.isMouseTrackingActive() }

    fun isBracketedPasteActive(): Boolean = withOpenBackend(false) { it.isBracketedPasteActive() }

    fun isApplicationCursorActive(): Boolean = withOpenBackend(false) { it.isApplicationCursorActive() }

    fun getWindowTitle(): String = withOpenBackend("") { it.getWindowTitle() }

    fun getCursorState(): VtCursorState = withOpenBackend(
        VtCursorState(0, 0, visible = false, blink = false, shape = VtCursorShape.BLOCK),
    ) { it.getCursorState() }

    fun encodeKey(event: VtKeyEvent): ByteArray = withOpenBackend(ByteArray(0)) { it.encodeKey(event) }

    fun encodeMouse(event: VtMouseEvent): ByteArray = withOpenBackend(ByteArray(0)) { it.encodeMouse(event) }

    fun isPasteSafe(text: String): Boolean = backend.isPasteSafe(text)

    fun encodePaste(text: String, allowUnsafe: Boolean = false): ByteArray? =
        withOpenBackend<ByteArray?>(null) { it.encodePaste(text, allowUnsafe) }

    fun formatViewportText(
        emitVt: Boolean = false,
        trimTrailingWhitespace: Boolean = true,
        unwrapSoftWraps: Boolean = true,
    ): String = withOpenBackend("") {
        it.formatText(
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
    ): String = withOpenBackend("") {
        it.formatText(
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
    ): String = withOpenBackend("") {
        it.formatText(
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
        if (query.isEmpty()) return emptyList()
        return withOpenBackend(emptyList()) { it.searchScrollback(query, useRegex, caseSensitive) }
    }

    fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? {
        if (viewportRow < 0 || viewportCol < 0) return null
        return withOpenBackend<VtDetectedLink?>(null) { it.detectLinkAt(viewportRow, viewportCol) }
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
        backendLock.write {
            backend.close()
        }
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
