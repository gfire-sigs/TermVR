package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.model.KeySlotSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuxiliaryKeyBarMathTest {

    @Test
    fun `horizontal scrub accumulates 12dp steps and preserves fractional remainder`() {
        // Property: consumeHorizontalScrubSteps emits integer cell steps without losing sub-step remainder pixels.
        // Seam: AuxiliaryKeyBarMath.consumeHorizontalScrubSteps.
        // Oracle: truncated integer division steps * stepPx + remainder == accumulatedPx.
        // Fault: resetting sub-step pixels on small drags so slow finger movement never triggers LEFT/RIGHT.
        val stepPx = 36f // e.g., 12 dp at 3.0 density
        val (steps1, rem1) = AuxiliaryKeyBarMath.consumeHorizontalScrubSteps(20f, stepPx)
        assertEquals(0, steps1)
        assertEquals(20f, rem1)

        val (steps2, rem2) = AuxiliaryKeyBarMath.consumeHorizontalScrubSteps(rem1 + 25f, stepPx)
        assertEquals(1, steps2)
        assertEquals(9f, rem2)

        val (stepsNeg, remNeg) = AuxiliaryKeyBarMath.consumeHorizontalScrubSteps(-80f, stepPx)
        assertEquals(-2, stepsNeg)
        assertEquals(-8f, remNeg)
    }

    @Test
    fun `halfScreenLines always yields at least one row`() {
        // Property: halfScreenLines(rows) == max(1, rows / 2) for any terminal height.
        // Seam: AuxiliaryKeyBarMath.halfScreenLines.
        // Oracle: Section 14.2 vertical swipe on PGUP/PGDN scrolls half screen, minimum 1 line.
        // Fault: returning 0 on 1-row pane and stalling scrollback.
        assertEquals(1, AuxiliaryKeyBarMath.halfScreenLines(0))
        assertEquals(1, AuxiliaryKeyBarMath.halfScreenLines(1))
        assertEquals(1, AuxiliaryKeyBarMath.halfScreenLines(2))
        assertEquals(12, AuxiliaryKeyBarMath.halfScreenLines(24))
        assertEquals(12, AuxiliaryKeyBarMath.halfScreenLines(25))
    }

    @Test
    fun `repeatable and page scroll slot predicates match Section 14_2 key roles`() {
        // Property: arrows and PGUP/PGDN repeat; PGUP/PGDN support half-screen vertical swipe; modifiers/ESC/TAB do not.
        // Seam: AuxiliaryKeyBarMath.isRepeatableSlot + isPageScrollSlot.
        // Oracle: Design 0001 Section 14.2.
        // Fault: repeating sticky modifiers or failing to repeat PAGE_UP/PAGE_DOWN aliases.
        val repeatable = listOf("UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PAGE_UP", "PGDN", "PAGE_DOWN")
        for (name in repeatable) {
            assertTrue(AuxiliaryKeyBarMath.isRepeatableSlot(KeySlotSpec.GhosttyKey(name, name)))
        }
        assertFalse(AuxiliaryKeyBarMath.isRepeatableSlot(KeySlotSpec.GhosttyKey("ESC", "ESCAPE")))
        assertFalse(AuxiliaryKeyBarMath.isRepeatableSlot(KeySlotSpec.GhosttyKey("TAB", "TAB")))

        assertTrue(AuxiliaryKeyBarMath.isPageScrollSlot(KeySlotSpec.GhosttyKey("PGUP", "PGUP")))
        assertTrue(AuxiliaryKeyBarMath.isPageScrollSlot(KeySlotSpec.GhosttyKey("PGDN", "PAGE_DOWN")))
        assertFalse(AuxiliaryKeyBarMath.isPageScrollSlot(KeySlotSpec.GhosttyKey("UP", "UP")))
    }
}
