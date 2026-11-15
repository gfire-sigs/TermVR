package dev.gfire.termvr.core.ui

import android.view.KeyEvent
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.VtKey
import dev.gfire.termvr.core.vt.VtModifierMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalSurfaceLayoutTest {

    @Test
    fun `split pane layout conserves exact pixels with non-overlapping rects and 1dp dividers`() {
        // Property: for any nested split tree on positive surface dimensions, pane rects and divider rects partition the surface with zero overlap.
        // Seam: SplitPaneLayoutCalculator.compute + paneAt.
        // Oracle: Euclidean partition invariant (sum of widths/heights along split axes equals parent dimension).
        // Fault: off-by-one divider overlap or clipped rightmost/bottommost pixels.
        val s1 = NativeVtSession(80, 24)
        val s2 = NativeVtSession(80, 24)
        val s3 = NativeVtSession(80, 24)
        try {
            val tree = TerminalSplitNode.Split(
                direction = SplitDirection.HORIZONTAL,
                ratio = 0.5f,
                first = TerminalSplitNode.Leaf("p1", s1),
                second = TerminalSplitNode.Split(
                    direction = SplitDirection.VERTICAL,
                    ratio = 0.25f,
                    first = TerminalSplitNode.Leaf("p2", s2),
                    second = TerminalSplitNode.Leaf("p3", s3),
                ),
            )

            val result = SplitPaneLayoutCalculator.compute(
                root = tree,
                focusedPaneId = "p2",
                surfaceWidthPx = 1001,
                surfaceHeightPx = 803,
                dividerThicknessPx = 3,
            )

            assertEquals(3, result.panes.size)
            assertEquals(2, result.dividers.size)

            val p1 = result.panes[0]
            val p2 = result.panes[1]
            val p3 = result.panes[2]

            assertFalse(p1.focused)
            assertTrue(p2.focused)
            assertFalse(p3.focused)

            val vDiv = result.dividers[0]
            val hDiv = result.dividers[1]

            // Horizontal axis conservation: p1 + vDiv + right subtree == 1001.
            assertEquals(0, p1.xPx)
            assertEquals(p1.widthPx, vDiv.xPx)
            assertEquals(3, vDiv.widthPx)
            assertEquals(vDiv.xPx + vDiv.widthPx, p2.xPx)
            assertEquals(p2.xPx, p3.xPx)
            assertEquals(p2.widthPx, p3.widthPx)
            assertEquals(1001, p1.widthPx + vDiv.widthPx + p2.widthPx)

            // Vertical axis conservation on right subtree: p2 + hDiv + p3 == 803.
            assertEquals(0, p2.yPx)
            assertEquals(p2.heightPx, hDiv.yPx)
            assertEquals(3, hDiv.heightPx)
            assertEquals(hDiv.yPx + hDiv.heightPx, p3.yPx)
            assertEquals(803, p2.heightPx + hDiv.heightPx + p3.heightPx)

            // Hit testing resolves exact focused/unfocused panes and returns null on dividers.
            assertEquals("p1", SplitPaneLayoutCalculator.paneAt(result, 10f, 10f)?.paneId)
            assertEquals("p2", SplitPaneLayoutCalculator.paneAt(result, (p2.xPx + 5).toFloat(), 10f)?.paneId)
            assertEquals("p3", SplitPaneLayoutCalculator.paneAt(result, (p3.xPx + 5).toFloat(), (p3.yPx + 5).toFloat())?.paneId)
            assertNull(SplitPaneLayoutCalculator.paneAt(result, vDiv.xPx.toFloat(), 10f))
        } finally {
            s1.close()
            s2.close()
            s3.close()
        }
    }

    @Test
    fun `split pane layout clamps extreme ratios and rejects non-positive surfaces`() {
        // Property: extreme ratios stay within [0.1, 0.9] and zero/negative surface yields empty layout.
        // Seam: SplitPaneLayoutCalculator.compute.
        // Oracle: MIN_RATIO = 0.1f, MAX_RATIO = 0.9f.
        // Fault: division producing zero-width pane or negative coordinates on extreme ratios.
        val s1 = NativeVtSession(80, 24)
        val s2 = NativeVtSession(80, 24)
        try {
            val tree = TerminalSplitNode.Split(
                direction = SplitDirection.HORIZONTAL,
                ratio = 0.999f,
                first = TerminalSplitNode.Leaf("a", s1),
                second = TerminalSplitNode.Leaf("b", s2),
            )
            val empty = SplitPaneLayoutCalculator.compute(tree, "a", 0, 500, 2)
            assertTrue(empty.panes.isEmpty())

            val clamped = SplitPaneLayoutCalculator.compute(tree, "a", 1000, 500, 2)
            val a = clamped.panes[0]
            val b = clamped.panes[1]
            assertTrue(a.widthPx <= 900)
            assertTrue(b.widthPx >= 98)
        } finally {
            s1.close()
            s2.close()
        }
    }

    @Test
    fun `pinch to zoom clamps between 8sp and 32sp in half-sp steps`() {
        // Property: applyScaleFactor output always lies in [8.0, 32.0] and is an exact multiple of 0.5.
        // Seam: PinchZoomCalculator.applyScaleFactor.
        // Oracle: Design 0001 Section 13.3 (8.0..32.0 sp in 0.5 sp steps).
        // Fault: out-of-range zoom, non-0.5 step drift, or NaN propagation.
        assertEquals(8.0f, PinchZoomCalculator.applyScaleFactor(10.0f, 0.2f))
        assertEquals(32.0f, PinchZoomCalculator.applyScaleFactor(20.0f, 3.0f))
        assertEquals(13.5f, PinchZoomCalculator.applyScaleFactor(13.0f, 1.03f))
        assertEquals(13.0f, PinchZoomCalculator.applyScaleFactor(13.0f, Float.NaN))
        assertEquals(13.0f, PinchZoomCalculator.applyScaleFactor(13.0f, -1.0f))

        val samples = listOf(0.5f, 0.91f, 1.04f, 1.17f, 1.5f, 2.2f)
        for (factor in samples) {
            val result = PinchZoomCalculator.applyScaleFactor(14.0f, factor)
            assertTrue(result in 8.0f..32.0f)
            assertEquals(0.0f, (result * 2f) % 1f)
        }
    }

    @Test
    fun `gesture edge guard excludes 24dp from left and right edges`() {
        // Property: x in [0, 24*density) or (width - 24*density, width] is guarded; interior points are not.
        // Seam: GestureEdgeGuard.isWithinEdgeGuard.
        // Oracle: Design 0001 Section 14.2 (24 dp display edge exclusion).
        // Fault: forgetting density scaling or missing right-edge exclusion.
        val density = 3.0f
        val widthPx = 1080
        // 24 dp * 3.0 = 72 px.
        assertTrue(GestureEdgeGuard.isWithinEdgeGuard(0f, widthPx, density))
        assertTrue(GestureEdgeGuard.isWithinEdgeGuard(71.9f, widthPx, density))
        assertFalse(GestureEdgeGuard.isWithinEdgeGuard(72.0f, widthPx, density))
        assertFalse(GestureEdgeGuard.isWithinEdgeGuard(1008.0f, widthPx, density))
        assertTrue(GestureEdgeGuard.isWithinEdgeGuard(1008.1f, widthPx, density))
        assertTrue(GestureEdgeGuard.isWithinEdgeGuard(1079f, widthPx, density))
    }

    @Test
    fun `hardware key mapper intercepts host shortcuts unless passAllShortcutsToTerminal is enabled`() {
        // Property: Ctrl+Shift+{T,W,C,V,F} and Ctrl+{=,-} map to HostShortcut when passAllShortcutsToTerminal=false,
        // and forward as TerminalKey with merged (soft or hardware) modifiers when passAllShortcutsToTerminal=true.
        // Seam: HardwareKeyMapper.mapKeyEvent.
        // Oracle: Design 0001 Section 14.4.
        // Fault: ignoring passAllShortcutsToTerminal, dropping softModifierMask, or emitting bytes on bare modifier press.
        val ctrlShift = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON

        val newTab = HardwareKeyMapper.mapKeyEvent(
            keyCode = KeyEvent.KEYCODE_T,
            isRepeat = false,
            isUp = false,
            hardwareMetaState = ctrlShift,
            unicodeChar = 'T'.code,
            softModifierMask = 0,
            passAllShortcutsToTerminal = false,
        )
        assertEquals(HardwareKeyResult.HostShortcut(HostShortcutAction.NEW_TAB), newTab)

        val zoomIn = HardwareKeyMapper.mapKeyEvent(
            keyCode = KeyEvent.KEYCODE_EQUALS,
            isRepeat = false,
            isUp = false,
            hardwareMetaState = KeyEvent.META_CTRL_ON,
            unicodeChar = '='.code,
            softModifierMask = 0,
            passAllShortcutsToTerminal = false,
        )
        assertEquals(HardwareKeyResult.HostShortcut(HostShortcutAction.ZOOM_IN), zoomIn)

        val zoomOut = HardwareKeyMapper.mapKeyEvent(
            keyCode = KeyEvent.KEYCODE_MINUS,
            isRepeat = false,
            isUp = false,
            hardwareMetaState = KeyEvent.META_CTRL_ON,
            unicodeChar = '-'.code,
            softModifierMask = 0,
            passAllShortcutsToTerminal = false,
        )
        assertEquals(HardwareKeyResult.HostShortcut(HostShortcutAction.ZOOM_OUT), zoomOut)

        val passedThrough = HardwareKeyMapper.mapKeyEvent(
            keyCode = KeyEvent.KEYCODE_C,
            isRepeat = false,
            isUp = false,
            hardwareMetaState = ctrlShift,
            unicodeChar = 'c'.code,
            softModifierMask = VtModifierMask.ALT,
            passAllShortcutsToTerminal = true,
        ) as? HardwareKeyResult.TerminalKey
        assertNotNull(passedThrough)
        assertEquals(VtKey.UNICODE, passedThrough!!.event.key)
        assertEquals(
            VtModifierMask.CTRL or VtModifierMask.SHIFT or VtModifierMask.ALT,
            passedThrough.event.modifierMask,
        )

        val loneModifier = HardwareKeyMapper.mapKeyEvent(
            keyCode = KeyEvent.KEYCODE_CTRL_LEFT,
            isRepeat = false,
            isUp = false,
            hardwareMetaState = KeyEvent.META_CTRL_ON,
            unicodeChar = 0,
            softModifierMask = 0,
            passAllShortcutsToTerminal = false,
        )
        assertEquals(HardwareKeyResult.Ignored, loneModifier)
    }

    @Test
    fun `hardware special key codes map to VtKey enums and combine soft and hardware modifiers`() {
        // Property: navigation, ESCAPE, TAB, and function keys map to exact VtKey values with combined modifiers.
        // Seam: HardwareKeyMapper.mapKeyEvent.
        // Oracle: Android KeyEvent to VtKey mapping table in TerminalSurfaceLayout.kt.
        // Fault: falling through to unicodeChar == 0 and dropping non-printable hardware keys.
        val cases = listOf(
            KeyEvent.KEYCODE_ESCAPE to VtKey.ESCAPE,
            KeyEvent.KEYCODE_TAB to VtKey.TAB,
            KeyEvent.KEYCODE_DPAD_UP to VtKey.UP,
            KeyEvent.KEYCODE_PAGE_DOWN to VtKey.PAGE_DOWN,
            KeyEvent.KEYCODE_F1 to VtKey.F1,
            KeyEvent.KEYCODE_F12 to VtKey.F12,
        )
        for ((keyCode, expectedKey) in cases) {
            val mapped = HardwareKeyMapper.mapKeyEvent(
                keyCode = keyCode,
                isRepeat = false,
                isUp = false,
                hardwareMetaState = KeyEvent.META_ALT_ON,
                unicodeChar = 0,
                softModifierMask = VtModifierMask.CTRL,
                passAllShortcutsToTerminal = false,
            ) as? HardwareKeyResult.TerminalKey
            assertNotNull(mapped, "Expected TerminalKey for keyCode=$keyCode")
            assertEquals(expectedKey, mapped!!.event.key)
            assertEquals(VtModifierMask.CTRL or VtModifierMask.ALT, mapped.event.modifierMask)
        }
    }
}
