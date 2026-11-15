package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.ModifierKey

enum class VtKey(val nativeCode: Int) {
    NONE(0),
    ESCAPE(1),
    ENTER(2),
    TAB(3),
    BACKSPACE(4),
    INSERT(5),
    DELETE(6),
    LEFT(7),
    RIGHT(8),
    UP(9),
    DOWN(10),
    PAGE_UP(11),
    PAGE_DOWN(12),
    HOME(13),
    END(14),
    F1(15),
    F2(16),
    F3(17),
    F4(18),
    F5(19),
    F6(20),
    F7(21),
    F8(22),
    F9(23),
    F10(24),
    F11(25),
    F12(26),
    UNICODE(100);

    companion object {
        fun fromKeyName(name: String): VtKey = when (name.uppercase()) {
            "ESC", "ESCAPE" -> ESCAPE
            "ENTER", "RETURN" -> ENTER
            "TAB" -> TAB
            "BACKSPACE", "DEL" -> BACKSPACE
            "INSERT", "INS" -> INSERT
            "DELETE", "FORWARD_DEL" -> DELETE
            "LEFT" -> LEFT
            "RIGHT" -> RIGHT
            "UP" -> UP
            "DOWN" -> DOWN
            "PGUP", "PAGE_UP" -> PAGE_UP
            "PGDN", "PAGE_DOWN" -> PAGE_DOWN
            "HOME" -> HOME
            "END" -> END
            "F1" -> F1
            "F2" -> F2
            "F3" -> F3
            "F4" -> F4
            "F5" -> F5
            "F6" -> F6
            "F7" -> F7
            "F8" -> F8
            "F9" -> F9
            "F10" -> F10
            "F11" -> F11
            "F12" -> F12
            else -> NONE
        }
    }
}

enum class VtKeyAction(val nativeCode: Int) {
    PRESS(1),
    REPEAT(2),
    RELEASE(3),
}

object VtModifierMask {
    const val NONE: Int = 0
    const val SHIFT: Int = 1 shl 0
    const val ALT: Int = 1 shl 1
    const val CTRL: Int = 1 shl 2
    const val SUPER: Int = 1 shl 3

    fun fromModifierKeys(keys: Iterable<ModifierKey>): Int {
        var mask = 0
        for (k in keys) {
            mask = mask or when (k) {
                ModifierKey.SHIFT -> SHIFT
                ModifierKey.ALT -> ALT
                ModifierKey.CTRL -> CTRL
                ModifierKey.SUPER -> SUPER
            }
        }
        return mask
    }
}

data class VtKeyEvent(
    val key: VtKey,
    val action: VtKeyAction = VtKeyAction.PRESS,
    val modifierMask: Int = VtModifierMask.NONE,
    val unicodeCodepoint: Int = 0,
    val utf8Text: String? = null,
)

enum class VtMouseAction(val nativeCode: Int) {
    PRESS(0),
    RELEASE(1),
    MOTION(2),
    WHEEL_UP(3),
    WHEEL_DOWN(4),
}

enum class VtMouseButton(val nativeCode: Int) {
    NONE(0),
    LEFT(1),
    MIDDLE(2),
    RIGHT(3),
}

data class VtMouseEvent(
    val action: VtMouseAction,
    val button: VtMouseButton,
    val modifierMask: Int = VtModifierMask.NONE,
    val col: Int,
    val row: Int,
    val pixelX: Int = 0,
    val pixelY: Int = 0,
)

enum class VtCursorShape(val nativeCode: Int) {
    NONE(0),
    BLOCK(1),
    BAR(2),
    HOLLOW(3),
    UNDERLINE(4);

    companion object {
        fun fromNativeCode(code: Int): VtCursorShape =
            entries.firstOrNull { it.nativeCode == code } ?: BLOCK
    }
}

data class VtCursorState(
    val col: Int,
    val row: Int,
    val visible: Boolean,
    val blink: Boolean,
    val shape: VtCursorShape,
)

data class VtSearchMatch(
    val absoluteLine: Int,
    val viewportRow: Int,
    val startCol: Int,
    val endCol: Int,
    val matchedText: String,
)

enum class VtLinkKind(val nativeCode: Int) {
    URL(0),
    FILEPATH(1);

    companion object {
        fun fromNativeCode(code: Int): VtLinkKind =
            entries.firstOrNull { it.nativeCode == code } ?: URL
    }
}

data class VtDetectedLink(
    val kind: VtLinkKind,
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
    val target: String,
)

data class CellMetrics(
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val baselinePx: Int,
) {
    init {
        require(cellWidthPx > 0) { "cellWidthPx must be positive" }
        require(cellHeightPx > 0) { "cellHeightPx must be positive" }
    }
}

data class PaneRenderTarget(
    val session: NativeVtSession,
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val focused: Boolean,
    val cursorBlinkVisible: Boolean,
    val preeditText: String? = null,
    val preeditCursorCol: Int = 0,
    val preeditCursorRow: Int = 0,
    val preeditFgRgba: UInt = 0xF0F6FCFFu,
    val preeditBgRgba: UInt = 0x1F2937F2u,
    val preeditUnderlineRgba: UInt = 0x58A6FFFFu,
)

data class SplitDividerRect(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val rgba: UInt,
)

data class RendererShaderPack(
    val bgVert: String,
    val bgFrag: String,
    val glyphVert: String,
    val glyphFrag: String,
)

class BundledFontPack(
    val jetbrainsRegular: ByteArray = ByteArray(0),
    val jetbrainsBold: ByteArray = ByteArray(0),
    val jetbrainsItalic: ByteArray = ByteArray(0),
    val jetbrainsBoldItalic: ByteArray = ByteArray(0),
    val nerdSymbols: ByteArray = ByteArray(0),
)
