package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.model.ModifierLatchState
import dev.gfire.termvr.core.vt.VtModifierMask
import java.util.EnumMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks touch modifier latch transitions for CTRL, ALT, SHIFT, and SUPER slots.
 */
class ModifierLatchMachine(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class SlotState(
        var state: ModifierLatchState = ModifierLatchState.RELEASED,
        var stateBeforePress: ModifierLatchState = ModifierLatchState.RELEASED,
        var pressDownTimeMs: Long = -1L,
        var lastOneShotUpTimeMs: Long = -1L,
        var chordedWhileHeld: Boolean = false,
        var lockedByHoldDuringPress: Boolean = false,
    )

    private val slots: EnumMap<ModifierKey, SlotState> = EnumMap(ModifierKey::class.java)
    private val _statesFlow: MutableStateFlow<Map<ModifierKey, ModifierLatchState>>

    val statesFlow: StateFlow<Map<ModifierKey, ModifierLatchState>>

    init {
        for (mod in ModifierKey.entries) {
            slots[mod] = SlotState()
        }
        _statesFlow = MutableStateFlow(snapshotStatesLocked())
        statesFlow = _statesFlow.asStateFlow()
    }

    private fun snapshotStatesLocked(): Map<ModifierKey, ModifierLatchState> =
        ModifierKey.entries.associateWith { slots.getValue(it).state }

    private fun publishStatesLocked() {
        _statesFlow.value = snapshotStatesLocked()
    }

    @Synchronized
    fun stateOf(mod: ModifierKey): ModifierLatchState = slots.getValue(mod).state

    @Synchronized
    fun allStates(): Map<ModifierKey, ModifierLatchState> =
        ModifierKey.entries.associateWith { slots.getValue(it).state }

    @Synchronized
    fun activeModifierMask(): Int {
        var mask = VtModifierMask.NONE
        for (mod in ModifierKey.entries) {
            if (slots.getValue(mod).state != ModifierLatchState.RELEASED) {
                mask = mask or toMaskBit(mod)
            }
        }
        return mask
    }

    @Synchronized
    fun isShiftActive(): Boolean = slots.getValue(ModifierKey.SHIFT).state != ModifierLatchState.RELEASED

    @Synchronized
    fun onFingerDown(mod: ModifierKey) {
        val now = clockMs()
        val slot = slots.getValue(mod)
        slot.stateBeforePress = slot.state
        slot.pressDownTimeMs = now
        slot.chordedWhileHeld = false
        slot.lockedByHoldDuringPress = false

        if (slot.state == ModifierLatchState.RELEASED) {
            slot.state = ModifierLatchState.HELD
        }
        publishStatesLocked()
    }

    @Synchronized
    fun onHoldTimeout(mod: ModifierKey) {
        val now = clockMs()
        val slot = slots.getValue(mod)
        if (slot.state == ModifierLatchState.HELD &&
            !slot.chordedWhileHeld &&
            slot.pressDownTimeMs >= 0L &&
            now - slot.pressDownTimeMs >= LONG_PRESS_MS
        ) {
            slot.state = ModifierLatchState.LOCKED
            slot.lockedByHoldDuringPress = true
            publishStatesLocked()
        }
    }

    @Synchronized
    fun onFingerUp(mod: ModifierKey) {
        val now = clockMs()
        val slot = slots.getValue(mod)
        val downAt = slot.pressDownTimeMs
        val heldDuration = if (downAt >= 0L) (now - downAt).coerceAtLeast(0L) else 0L

        if (slot.lockedByHoldDuringPress) {
            slot.lockedByHoldDuringPress = false
            slot.pressDownTimeMs = -1L
            publishStatesLocked()
            return
        }

        when (slot.stateBeforePress) {
            ModifierLatchState.RELEASED -> {
                slot.state = when {
                    slot.chordedWhileHeld -> ModifierLatchState.RELEASED
                    heldDuration >= LONG_PRESS_MS -> ModifierLatchState.LOCKED
                    heldDuration < TAP_MAX_MS -> {
                        slot.lastOneShotUpTimeMs = now
                        ModifierLatchState.ONE_SHOT
                    }
                    else -> ModifierLatchState.RELEASED
                }
            }
            ModifierLatchState.ONE_SHOT -> {
                val sinceOneShot = if (slot.lastOneShotUpTimeMs >= 0L && downAt >= 0L) {
                    downAt - slot.lastOneShotUpTimeMs
                } else {
                    Long.MAX_VALUE
                }
                slot.state = when {
                    slot.chordedWhileHeld -> ModifierLatchState.RELEASED
                    heldDuration >= LONG_PRESS_MS -> ModifierLatchState.LOCKED
                    sinceOneShot in 0L until DOUBLE_TAP_MS -> ModifierLatchState.LOCKED
                    else -> ModifierLatchState.RELEASED
                }
            }
            ModifierLatchState.LOCKED -> {
                slot.state = ModifierLatchState.RELEASED
            }
            ModifierLatchState.HELD -> {
                slot.state = ModifierLatchState.RELEASED
            }
        }
        slot.pressDownTimeMs = -1L
        publishStatesLocked()
    }

    @Synchronized
    fun onNonModifierKeyEmitted() {
        for (mod in ModifierKey.entries) {
            val slot = slots.getValue(mod)
            when (slot.state) {
                ModifierLatchState.HELD -> slot.chordedWhileHeld = true
                ModifierLatchState.ONE_SHOT -> {
                    slot.state = ModifierLatchState.RELEASED
                    slot.lastOneShotUpTimeMs = -1L
                }
                ModifierLatchState.LOCKED, ModifierLatchState.RELEASED -> Unit
            }
        }
        publishStatesLocked()
    }

    @Synchronized
    fun clearAll() {
        for (mod in ModifierKey.entries) {
            val slot = slots.getValue(mod)
            slot.state = ModifierLatchState.RELEASED
            slot.stateBeforePress = ModifierLatchState.RELEASED
            slot.pressDownTimeMs = -1L
            slot.lastOneShotUpTimeMs = -1L
            slot.chordedWhileHeld = false
            slot.lockedByHoldDuringPress = false
        }
        publishStatesLocked()
    }

    fun resetAll() {
        clearAll()
    }

    private fun toMaskBit(mod: ModifierKey): Int = when (mod) {
        ModifierKey.CTRL -> VtModifierMask.CTRL
        ModifierKey.ALT -> VtModifierMask.ALT
        ModifierKey.SHIFT -> VtModifierMask.SHIFT
        ModifierKey.SUPER -> VtModifierMask.SUPER
    }

    companion object {
        const val TAP_MAX_MS: Long = 300L
        const val LONG_PRESS_MS: Long = 400L
        const val DOUBLE_TAP_MS: Long = 350L
    }
}
