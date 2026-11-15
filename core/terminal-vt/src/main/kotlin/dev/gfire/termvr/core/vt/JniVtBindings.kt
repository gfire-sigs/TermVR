package dev.gfire.termvr.core.vt

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

@Keep
internal object JniVtBindings {
    private val loadAttempted = AtomicBoolean(false)
    @Volatile
    private var libraryLoaded = false

    fun isNativeAvailable(): Boolean {
        if (loadAttempted.compareAndSet(false, true)) {
            libraryLoaded = try {
                System.loadLibrary("termvr_vt")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        return libraryLoaded
    }

    @JvmStatic external fun nativeCreateSession(cols: Int, rows: Int, maxScrollback: Int): Long
    @JvmStatic external fun nativeDestroySession(handle: Long)
    @JvmStatic external fun nativeResizeGrid(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean
    @JvmStatic external fun nativeWritePtyBytes(handle: Long, data: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun nativeSetPalette(handle: Long, fgRgba: Int, bgRgba: Int, cursorRgba: Int, selectionRgba: Int, ansi16: IntArray)
    @JvmStatic external fun nativeSetScrollbackOffset(handle: Long, linesFromBottom: Int)
    @JvmStatic external fun nativeGetScrollbackOffset(handle: Long): Int
    @JvmStatic external fun nativeGetScrollbackCount(handle: Long): Int
    @JvmStatic external fun nativeSetSelection(handle: Long, active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int)
    @JvmStatic external fun nativeClearSelection(handle: Long)
    @JvmStatic external fun nativeIsDirty(handle: Long): Boolean
    @JvmStatic external fun nativeIsMouseTrackingActive(handle: Long): Boolean
    @JvmStatic external fun nativeIsBracketedPasteActive(handle: Long): Boolean
    @JvmStatic external fun nativeIsApplicationCursorActive(handle: Long): Boolean
    @JvmStatic external fun nativeGetWindowTitle(handle: Long): String
    @JvmStatic external fun nativeGetCursorState(handle: Long): IntArray
    @JvmStatic external fun nativeEncodeKey(handle: Long, keyCode: Int, action: Int, mods: Int, codepoint: Int, utf8Text: String?): ByteArray
    @JvmStatic external fun nativeEncodeMouse(handle: Long, action: Int, button: Int, mods: Int, col: Int, row: Int, pixelX: Int, pixelY: Int): ByteArray
    @JvmStatic external fun nativeIsPasteSafe(text: String): Boolean
    @JvmStatic external fun nativeEncodePaste(handle: Long, text: String, allowUnsafe: Boolean): ByteArray?
    @JvmStatic external fun nativeFormatText(
        handle: Long,
        emitVt: Boolean,
        useSelection: Boolean,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        trimTrailingWhitespace: Boolean,
        unwrapSoftWraps: Boolean,
    ): String
    @JvmStatic external fun nativeSearchScrollback(handle: Long, query: String, useRegex: Boolean, caseSensitive: Boolean): Array<String>
    @JvmStatic external fun nativeDetectLinkAt(handle: Long, viewportRow: Int, viewportCol: Int): String?

    @JvmStatic external fun nativeCreateRenderer(): Long
    @JvmStatic external fun nativeDestroyRenderer(rendererHandle: Long)
    @JvmStatic external fun nativeRendererSetShaders(
        rendererHandle: Long,
        bgVert: String,
        bgFrag: String,
        glyphVert: String,
        glyphFrag: String,
    )
    @JvmStatic external fun nativeRendererInitGl(rendererHandle: Long): Boolean
    @JvmStatic external fun nativeRendererReleaseGl(rendererHandle: Long)
    @JvmStatic external fun nativeRendererContextLost(rendererHandle: Long, newEpoch: Long)
    @JvmStatic external fun nativeRendererSetBundledFonts(
        rendererHandle: Long,
        regular: ByteArray,
        bold: ByteArray,
        italic: ByteArray,
        boldItalic: ByteArray,
        nerdSymbols: ByteArray,
    )
    @JvmStatic external fun nativeRendererConfigureFont(
        rendererHandle: Long,
        familyId: String,
        customPath: String,
        customSha256: String,
        sizeSp: Float,
        cellHeightMultiplier: Float,
        density: Float,
        ligaturesEnabled: Boolean,
        nerdSymbolsEnabled: Boolean,
    ): IntArray
    @JvmStatic external fun nativeRendererDetachSession(rendererHandle: Long, sessionHandle: Long)
    @JvmStatic external fun nativeRendererDrawFrame(
        rendererHandle: Long,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        forceFullRedraw: Boolean,
        surfaceClearRgba: Int,
        paneSessionHandles: LongArray,
        paneBoundsAndFlags: IntArray,
        panePreeditTexts: Array<String?>,
        panePreeditColors: IntArray,
        dividerRectsAndColors: IntArray,
    ): Boolean
}
