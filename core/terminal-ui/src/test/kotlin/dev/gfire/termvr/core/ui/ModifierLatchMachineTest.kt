package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.model.ModifierLatchState
import dev.gfire.termvr.core.vt.VtModifierMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModifierLatchMachineTest {

    @Test
    fun `short tap transitions RELEASED to HELD to ONE_SHOT and clears on non-modifier key`() {
        // Property: tap shorter than 300ms without chord enters ONE_SHOT, then clears on next non-modifier key.
        // Seam: ModifierLatchMachine public methods.
        // Oracle: Design 0001 Section 14.1 state diagram (0..299ms tap).
        // Fault: tap below 300ms staying RELEASED or failing to clear ONE_SHOT after chord.
        var now = 1_000L
        val machine = ModifierLatchMachine(clockMs = { now })

        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))

        machine.onFingerDown(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.HELD, machine.stateOf(ModifierKey.CTRL))
        assertEquals(VtModifierMask.CTRL, machine.activeModifierMask())

        now += 299L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.ONE_SHOT, machine.stateOf(ModifierKey.CTRL))
        assertEquals(VtModifierMask.CTRL, machine.activeModifierMask())

        machine.onNonModifierKeyEmitted()
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))
        assertEquals(VtModifierMask.NONE, machine.activeModifierMask())
    }

    @Test
    fun `tap at exact 300ms boundary without long press returns RELEASED`() {
        // Property: finger up in 300..399ms without chord is neither <300ms tap nor >=400ms long press.
        // Seam: ModifierLatchMachine.onFingerUp.
        // Oracle: Design 0001 Section 14.1 (< 300ms ONE_SHOT, >= 400ms LOCKED).
        // Fault: off-by-one <= 300ms treating 300ms release as ONE_SHOT.
        var now = 10_000L
        val machine = ModifierLatchMachine(clockMs = { now })

        machine.onFingerDown(ModifierKey.ALT)
        now += 300L
        machine.onFingerUp(ModifierKey.ALT)
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.ALT))
    }

    @Test
    fun `chord while held releases on finger up even after long duration`() {
        // Property: HELD modifier with >= 1 non-modifier key emitted transitions to RELEASED on finger up.
        // Seam: ModifierLatchMachine.onNonModifierKeyEmitted + onHoldTimeout + onFingerUp.
        // Oracle: Design 0001 Section 14.1 (HELD to RELEASED after >= 1 key chorded while held).
        // Fault: chorded HELD modifier locking at 400ms or becoming ONE_SHOT on release.
        var now = 5_000L
        val machine = ModifierLatchMachine(clockMs = { now })

        machine.onFingerDown(ModifierKey.CTRL)
        now += 100L
        machine.onNonModifierKeyEmitted()
        now += 400L
        machine.onHoldTimeout(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.HELD, machine.stateOf(ModifierKey.CTRL))

        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))
    }

    @Test
    fun `long press at 400ms transitions HELD to LOCKED and release keeps LOCKED`() {
        // Property: holding >= 400ms without chord enters LOCKED; lifting that finger must keep LOCKED until next tap.
        // Seam: ModifierLatchMachine.onHoldTimeout + onFingerUp.
        // Oracle: Design 0001 Section 14.1 (HELD to LOCKED >= 400ms; LOCKED to RELEASED on subsequent single tap).
        // Fault: finger-up after long-press immediately toggling LOCKED back to RELEASED.
        var now = 2_000L
        val machine = ModifierLatchMachine(clockMs = { now })

        machine.onFingerDown(ModifierKey.SHIFT)
        now += 399L
        machine.onHoldTimeout(ModifierKey.SHIFT)
        assertEquals(ModifierLatchState.HELD, machine.stateOf(ModifierKey.SHIFT))

        now += 1L
        machine.onHoldTimeout(ModifierKey.SHIFT)
        assertEquals(ModifierLatchState.LOCKED, machine.stateOf(ModifierKey.SHIFT))
        assertTrue(machine.isShiftActive())

        // Releasing the finger that performed the long-press must stay LOCKED.
        now += 50L
        machine.onFingerUp(ModifierKey.SHIFT)
        assertEquals(ModifierLatchState.LOCKED, machine.stateOf(ModifierKey.SHIFT))

        // LOCKED survives multiple key emissions.
        machine.onNonModifierKeyEmitted()
        machine.onNonModifierKeyEmitted()
        assertEquals(ModifierLatchState.LOCKED, machine.stateOf(ModifierKey.SHIFT))

        // Subsequent single tap unlocks to RELEASED.
        now += 100L
        machine.onFingerDown(ModifierKey.SHIFT)
        now += 60L
        machine.onFingerUp(ModifierKey.SHIFT)
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.SHIFT))
        assertFalse(machine.isShiftActive())
    }

    @Test
    fun `second tap within 350ms promotes ONE_SHOT to LOCKED while later tap cancels to RELEASED`() {
        // Property: tap within < 350ms from ONE_SHOT enters LOCKED; tap >= 350ms cancels ONE_SHOT to RELEASED.
        // Seam: ModifierLatchMachine.onFingerDown / onFingerUp.
        // Oracle: Design 0001 Section 14.1 (ONE_SHOT to LOCKED < 350ms; ONE_SHOT to RELEASED single tap).
        // Fault: inverted double-tap window or off-by-one at 350ms.
        var now = 1_000L
        val machine = ModifierLatchMachine(clockMs = { now })

        // First tap sets ONE_SHOT at t=1050.
        machine.onFingerDown(ModifierKey.CTRL)
        now += 50L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.ONE_SHOT, machine.stateOf(ModifierKey.CTRL))

        // Second tap down at t=1399 (delta = 349ms < 350ms) transitions to LOCKED.
        now = 1_399L
        machine.onFingerDown(ModifierKey.CTRL)
        now += 40L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.LOCKED, machine.stateOf(ModifierKey.CTRL))

        // Reset via single tap.
        now += 200L
        machine.onFingerDown(ModifierKey.CTRL)
        now += 40L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))

        // Re-enter ONE_SHOT at t=2050.
        now = 2_000L
        machine.onFingerDown(ModifierKey.CTRL)
        now += 50L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.ONE_SHOT, machine.stateOf(ModifierKey.CTRL))

        // Tap at t=2400 (delta = 350ms, not < 350ms) cancels ONE_SHOT to RELEASED.
        now = 2_400L
        machine.onFingerDown(ModifierKey.CTRL)
        now += 40L
        machine.onFingerUp(ModifierKey.CTRL)
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))
    }

    @Test
    fun `multiple modifiers combine bitmasks independently`() {
        // Property: activeModifierMask equals bitwise OR of all active modifiers; ONE_SHOT clears only ONE_SHOT.
        // Seam: ModifierLatchMachine.activeModifierMask.
        // Oracle: VtModifierMask bit constants in core:terminal-vt.
        // Fault: clearing LOCKED modifier when another ONE_SHOT modifier is consumed.
        var now = 0L
        val machine = ModifierLatchMachine(clockMs = { now })

        machine.onFingerDown(ModifierKey.ALT)
        now += 450L
        machine.onHoldTimeout(ModifierKey.ALT)
        machine.onFingerUp(ModifierKey.ALT)

        now += 100L
        machine.onFingerDown(ModifierKey.CTRL)
        now += 50L
        machine.onFingerUp(ModifierKey.CTRL)

        assertEquals(VtModifierMask.ALT or VtModifierMask.CTRL, machine.activeModifierMask())

        machine.onNonModifierKeyEmitted()
        assertEquals(ModifierLatchState.RELEASED, machine.stateOf(ModifierKey.CTRL))
        assertEquals(ModifierLatchState.LOCKED, machine.stateOf(ModifierKey.ALT))
        assertEquals(VtModifierMask.ALT, machine.activeModifierMask())
    }
}
