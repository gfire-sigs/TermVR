package dev.gfire.termvr.core.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.SplitDividerRect
import dev.gfire.termvr.core.vt.VtKey
import dev.gfire.termvr.core.vt.VtKeyAction
import dev.gfire.termvr.core.vt.VtKeyEvent
import dev.gfire.termvr.core.vt.VtModifierMask
import kotlin.math.max
import kotlin.math.roundToInt

enum class SplitDirection {
    HORIZONTAL,
    VERTICAL,
}

/**
 * Recursive split-pane tree passed to [TerminalViewport] and [TerminalSurfaceView].
 */
sealed interface TerminalSplitNode {
    data class Leaf(
        val paneId: String,
        val session: NativeVtSession,
    ) : TerminalSplitNode

    data class Split(
        val direction: SplitDirection,
        val ratio: Float = 0.5f,
        val first: TerminalSplitNode,
        val second: TerminalSplitNode,
    ) : TerminalSplitNode
}

data class PaneLayoutRect(
    val paneId: String,
    val session: NativeVtSession,
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
    val focused: Boolean,
)

data class SplitLayoutResult(
    val panes: List<PaneLayoutRect>,
    val dividers: List<SplitDividerRect>,
)

/**
 * Partitions a single surface into non-overlapping pane rectangles and 1 dp dividers.
 */
object SplitPaneLayoutCalculator {
    const val MIN_RATIO: Float = 0.1f
    const val MAX_RATIO: Float = 0.9f
    const val DEFAULT_DIVIDER_RGBA: UInt = 0x30363DFFu

    fun compute(
        root: TerminalSplitNode,
        focusedPaneId: String,
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        dividerThicknessPx: Int,
        dividerRgba: UInt = DEFAULT_DIVIDER_RGBA,
    ): SplitLayoutResult {
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) {
            return SplitLayoutResult(emptyList(), emptyList())
        }
        val panes = mutableListOf<PaneLayoutRect>()
        val dividers = mutableListOf<SplitDividerRect>()
        layoutNode(
            node = root,
            focusedPaneId = focusedPaneId,
            x = 0,
            y = 0,
            width = surfaceWidthPx,
            height = surfaceHeightPx,
            dividerThickness = max(1, dividerThicknessPx),
            dividerRgba = dividerRgba,
            outPanes = panes,
            outDividers = dividers,
        )
        return SplitLayoutResult(panes = panes, dividers = dividers)
    }

    private fun layoutNode(
        node: TerminalSplitNode,
        focusedPaneId: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        dividerThickness: Int,
        dividerRgba: UInt,
        outPanes: MutableList<PaneLayoutRect>,
        outDividers: MutableList<SplitDividerRect>,
    ) {
        if (width <= 0 || height <= 0) return
        when (node) {
            is TerminalSplitNode.Leaf -> {
                outPanes += PaneLayoutRect(
                    paneId = node.paneId,
                    session = node.session,
                    xPx = x,
                    yPx = y,
                    widthPx = width,
                    heightPx = height,
                    focused = node.paneId == focusedPaneId,
                )
            }
            is TerminalSplitNode.Split -> {
                val safeRatio = if (node.ratio.isFinite()) {
                    node.ratio.coerceIn(MIN_RATIO, MAX_RATIO)
                } else {
                    0.5f
                }
                when (node.direction) {
                    SplitDirection.HORIZONTAL -> {
                        val divW = dividerThickness.coerceAtMost(max(0, width - 2))
                        val availW = width - divW
                        if (availW <= 1) {
                            layoutNode(node.first, focusedPaneId, x, y, width, height, dividerThickness, dividerRgba, outPanes, outDividers)
                            return
                        }
                        val firstW = (availW * safeRatio).roundToInt().coerceIn(1, availW - 1)
                        val secondW = availW - firstW
                        val divX = x + firstW
                        if (divW > 0) {
                            outDividers += SplitDividerRect(
                                xPx = divX,
                                yPx = y,
                                widthPx = divW,
                                heightPx = height,
                                rgba = dividerRgba,
                            )
                        }
                        layoutNode(node.first, focusedPaneId, x, y, firstW, height, dividerThickness, dividerRgba, outPanes, outDividers)
                        layoutNode(node.second, focusedPaneId, divX + divW, y, secondW, height, dividerThickness, dividerRgba, outPanes, outDividers)
                    }
                    SplitDirection.VERTICAL -> {
                        val divH = dividerThickness.coerceAtMost(max(0, height - 2))
                        val availH = height - divH
                        if (availH <= 1) {
                            layoutNode(node.first, focusedPaneId, x, y, width, height, dividerThickness, dividerRgba, outPanes, outDividers)
                            return
                        }
                        val firstH = (availH * safeRatio).roundToInt().coerceIn(1, availH - 1)
                        val secondH = availH - firstH
                        val divY = y + firstH
                        if (divH > 0) {
                            outDividers += SplitDividerRect(
                                xPx = x,
                                yPx = divY,
                                widthPx = width,
                                heightPx = divH,
                                rgba = dividerRgba,
                            )
                        }
                        layoutNode(node.first, focusedPaneId, x, y, width, firstH, dividerThickness, dividerRgba, outPanes, outDividers)
                        layoutNode(node.second, focusedPaneId, x, divY + divH, width, secondH, dividerThickness, dividerRgba, outPanes, outDividers)
                    }
                }
            }
        }
    }

    fun paneAt(layout: SplitLayoutResult, xPx: Float, yPx: Float): PaneLayoutRect? {
        val ix = xPx.toInt()
        val iy = yPx.toInt()
        return layout.panes.firstOrNull { p ->
            ix >= p.xPx && ix < (p.xPx + p.widthPx) && iy >= p.yPx && iy < (p.yPx + p.heightPx)
        }
    }
}

/**
 * Clamps font zoom scale factors to 8.0..32.0 sp in 0.5 sp steps.
 */
object PinchZoomCalculator {
    const val MIN_SP: Float = 8.0f
    const val MAX_SP: Float = 32.0f
    const val STEP_SP: Float = 0.5f

    fun applyScaleFactor(currentSizeSp: Float, scaleFactor: Float): Float {
        if (!currentSizeSp.isFinite() || !scaleFactor.isFinite() || scaleFactor <= 0f) {
            return currentSizeSp.coerceIn(MIN_SP, MAX_SP)
        }
        val raw = (currentSizeSp * scaleFactor).coerceIn(MIN_SP, MAX_SP)
        return ((raw / STEP_SP).roundToInt() * STEP_SP).coerceIn(MIN_SP, MAX_SP)
    }
}

/**
 * Rejects touch gesture starts inside the 24 dp horizontal edge back-gesture zones.
 */
object GestureEdgeGuard {
    const val DEFAULT_EDGE_GUARD_DP: Float = 24f

    fun isWithinEdgeGuard(
        xPx: Float,
        viewWidthPx: Int,
        density: Float,
        edgeGuardDp: Float = DEFAULT_EDGE_GUARD_DP,
    ): Boolean {
        if (viewWidthPx <= 0 || density <= 0f) return false
        val guardPx = edgeGuardDp * density
        return xPx < guardPx || xPx > (viewWidthPx.toFloat() - guardPx)
    }
}

enum class HostShortcutAction {
    NEW_TAB,
    CLOSE_TAB,
    COPY,
    PASTE,
    SEARCH,
    ZOOM_IN,
    ZOOM_OUT,
}

sealed interface HardwareKeyResult {
    data class HostShortcut(val action: HostShortcutAction) : HardwareKeyResult
    data class TerminalKey(val event: VtKeyEvent) : HardwareKeyResult
    data object Ignored : HardwareKeyResult
}

/**
 * Maps physical keyboard events to host shortcuts or mode-aware Ghostty VT key events.
 */
object HardwareKeyMapper {

    fun hardwareMetaToMask(metaState: Int): Int {
        var mask = VtModifierMask.NONE
        if ((metaState and KeyEvent.META_SHIFT_ON) != 0) mask = mask or VtModifierMask.SHIFT
        if ((metaState and KeyEvent.META_ALT_ON) != 0) mask = mask or VtModifierMask.ALT
        if ((metaState and KeyEvent.META_CTRL_ON) != 0) mask = mask or VtModifierMask.CTRL
        if ((metaState and KeyEvent.META_META_ON) != 0) mask = mask or VtModifierMask.SUPER
        return mask
    }

    fun isStandaloneModifierKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT,
        KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT,
        KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT,
        KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK,
        KeyEvent.KEYCODE_NUM_LOCK,
        KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION,
        KeyEvent.KEYCODE_SYM -> true
        else -> false
    }

    fun mapKeyEvent(
        keyCode: Int,
        isRepeat: Boolean,
        isUp: Boolean,
        hardwareMetaState: Int,
        unicodeChar: Int,
        softModifierMask: Int,
        passAllShortcutsToTerminal: Boolean,
    ): HardwareKeyResult {
        if (isStandaloneModifierKey(keyCode)) {
            return HardwareKeyResult.Ignored
        }

        val hwMask = hardwareMetaToMask(hardwareMetaState)
        val combinedMods = softModifierMask or hwMask

        if (!isUp && !passAllShortcutsToTerminal) {
            val shortcut = matchHostShortcut(keyCode, combinedMods)
            if (shortcut != null) {
                return HardwareKeyResult.HostShortcut(shortcut)
            }
        }

        val action = when {
            isUp -> VtKeyAction.RELEASE
            isRepeat -> VtKeyAction.REPEAT
            else -> VtKeyAction.PRESS
        }

        val specialKey = mapSpecialVtKey(keyCode)
        if (specialKey != VtKey.NONE) {
            return HardwareKeyResult.TerminalKey(
                VtKeyEvent(
                    key = specialKey,
                    action = action,
                    modifierMask = combinedMods,
                ),
            )
        }

        if ((unicodeChar and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return HardwareKeyResult.Ignored
        }

        val resolvedCodepoint = resolveCodepoint(keyCode, unicodeChar, combinedMods)
        if (resolvedCodepoint <= 0) {
            return HardwareKeyResult.Ignored
        }

        val utf8 = String(Character.toChars(resolvedCodepoint))
        return HardwareKeyResult.TerminalKey(
            VtKeyEvent(
                key = VtKey.UNICODE,
                action = action,
                modifierMask = combinedMods,
                unicodeCodepoint = resolvedCodepoint,
                utf8Text = utf8,
            ),
        )
    }

    private fun matchHostShortcut(keyCode: Int, mods: Int): HostShortcutAction? {
        val hasCtrl = (mods and VtModifierMask.CTRL) != 0
        val hasShift = (mods and VtModifierMask.SHIFT) != 0
        val hasAltOrSuper = (mods and (VtModifierMask.ALT or VtModifierMask.SUPER)) != 0
        if (!hasCtrl || hasAltOrSuper) return null

        if (hasShift) {
            return when (keyCode) {
                KeyEvent.KEYCODE_T -> HostShortcutAction.NEW_TAB
                KeyEvent.KEYCODE_W -> HostShortcutAction.CLOSE_TAB
                KeyEvent.KEYCODE_C -> HostShortcutAction.COPY
                KeyEvent.KEYCODE_V -> HostShortcutAction.PASTE
                KeyEvent.KEYCODE_F -> HostShortcutAction.SEARCH
                else -> null
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_EQUALS,
            KeyEvent.KEYCODE_PLUS,
            KeyEvent.KEYCODE_NUMPAD_ADD -> HostShortcutAction.ZOOM_IN
            KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> HostShortcutAction.ZOOM_OUT
            else -> null
        }
    }

    private fun mapSpecialVtKey(keyCode: Int): VtKey = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> VtKey.ESCAPE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> VtKey.ENTER
        KeyEvent.KEYCODE_TAB -> VtKey.TAB
        KeyEvent.KEYCODE_DEL -> VtKey.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> VtKey.DELETE
        KeyEvent.KEYCODE_INSERT -> VtKey.INSERT
        KeyEvent.KEYCODE_DPAD_LEFT -> VtKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> VtKey.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> VtKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> VtKey.DOWN
        KeyEvent.KEYCODE_PAGE_UP -> VtKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> VtKey.PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> VtKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> VtKey.END
        KeyEvent.KEYCODE_F1 -> VtKey.F1
        KeyEvent.KEYCODE_F2 -> VtKey.F2
        KeyEvent.KEYCODE_F3 -> VtKey.F3
        KeyEvent.KEYCODE_F4 -> VtKey.F4
        KeyEvent.KEYCODE_F5 -> VtKey.F5
        KeyEvent.KEYCODE_F6 -> VtKey.F6
        KeyEvent.KEYCODE_F7 -> VtKey.F7
        KeyEvent.KEYCODE_F8 -> VtKey.F8
        KeyEvent.KEYCODE_F9 -> VtKey.F9
        KeyEvent.KEYCODE_F10 -> VtKey.F10
        KeyEvent.KEYCODE_F11 -> VtKey.F11
        KeyEvent.KEYCODE_F12 -> VtKey.F12
        else -> VtKey.NONE
    }

    private fun resolveCodepoint(keyCode: Int, unicodeChar: Int, combinedMods: Int): Int {
        if (unicodeChar > 0) return unicodeChar
        if (keyCode == KeyEvent.KEYCODE_SPACE) return ' '.code
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val base = if ((combinedMods and VtModifierMask.SHIFT) != 0) 'A'.code else 'a'.code
            return base + (keyCode - KeyEvent.KEYCODE_A)
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return '0'.code + (keyCode - KeyEvent.KEYCODE_0)
        }
        return 0
    }
}
