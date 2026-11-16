package dev.gfire.termvr.core.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.KeySlotSpec
import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.model.ModifierLatchState
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure math helpers for auxiliary key bar scrub, repeat, and half-screen scrollback.
 */
object AuxiliaryKeyBarMath {
    const val ROW_HEIGHT_DP: Float = 38f
    const val AUTO_REPEAT_DELAY_MS: Long = 350L
    const val AUTO_REPEAT_INTERVAL_MS: Long = 36L
    const val SCRUB_STEP_DP: Float = 12f
    const val WIDE_BREAKPOINT_DP: Float = 600f

    fun isRepeatableSlot(slot: KeySlotSpec): Boolean = when (slot) {
        is KeySlotSpec.GhosttyKey -> when (slot.keyName.uppercase()) {
            "UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PAGE_UP", "PGDN", "PAGE_DOWN" -> true
            else -> false
        }
        else -> false
    }

    fun isPageScrollSlot(slot: KeySlotSpec): Boolean = when (slot) {
        is KeySlotSpec.GhosttyKey -> when (slot.keyName.uppercase()) {
            "PGUP", "PAGE_UP", "PGDN", "PAGE_DOWN" -> true
            else -> false
        }
        else -> false
    }

    fun consumeHorizontalScrubSteps(accumulatedPx: Float, stepPx: Float): Pair<Int, Float> {
        if (stepPx <= 0f) return 0 to accumulatedPx
        val steps = (accumulatedPx / stepPx).toInt()
        val remainder = accumulatedPx - (steps * stepPx)
        return steps to remainder
    }

    fun halfScreenLines(viewportRows: Int): Int = max(1, viewportRows / 2)
}

/**
 * Renders the collapsible auxiliary terminal key bar above the soft keyboard.
 */
@Composable
fun AuxiliaryKeyBar(
    config: KeyBarConfig,
    latchMachine: ModifierLatchMachine,
    hardwareKeyboardAttached: Boolean,
    pinWithHardwareKeyboard: Boolean,
    viewportRows: Int,
    onBeforeControlKey: () -> Unit,
    onEmitKeySlot: (KeySlotSpec) -> Unit,
    onCursorScrub: (steps: Int) -> Unit,
    onHalfScreenScrollUp: (lines: Int) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    if (!config.visible || collapsed) return
    if (hardwareKeyboardAttached && !pinWithHardwareKeyboard) return

    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val currentConfig by rememberUpdatedState(config)
    val currentViewportRows by rememberUpdatedState(viewportRows)
    val currentOnBeforeControlKey by rememberUpdatedState(onBeforeControlKey)
    val currentOnEmitKeySlot by rememberUpdatedState(onEmitKeySlot)
    val currentOnCursorScrub by rememberUpdatedState(onCursorScrub)
    val currentOnHalfScreenScrollUp by rememberUpdatedState(onHalfScreenScrollUp)

    val modStates by latchMachine.statesFlow.collectAsState()
    var barWidthPx by remember { mutableIntStateOf(0) }

    val scrubStepPx = with(density) { AuxiliaryKeyBarMath.SCRUB_STEP_DP.dp.toPx() }
    val touchSlopPx = with(density) { 10.dp.toPx() }
    val swipeUpThresholdPx = with(density) { 18.dp.toPx() }

    fun triggerHaptic() {
        if (currentConfig.hapticFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun emitNonModifier(slot: KeySlotSpec) {
        currentOnBeforeControlKey()
        currentOnEmitKeySlot(slot)
        latchMachine.onNonModifierKeyEmitted()
        triggerHaptic()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(scrubStepPx, density.density) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (GestureEdgeGuard.isWithinEdgeGuard(down.position.x, barWidthPx, density.density)) {
                        return@awaitEachGesture
                    }
                    var totalDx = 0f
                    var accumDx = 0f
                    var scrubbing = false
                    var lastX = down.position.x

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        totalDx += dx
                        if (!scrubbing && abs(totalDx) > touchSlopPx) {
                            scrubbing = true
                            currentOnBeforeControlKey()
                        }
                        if (scrubbing) {
                            accumDx += dx
                            val (steps, rem) = AuxiliaryKeyBarMath.consumeHorizontalScrubSteps(accumDx, scrubStepPx)
                            accumDx = rem
                            if (steps != 0) {
                                currentOnCursorScrub(steps)
                                triggerHaptic()
                            }
                            change.consume()
                        }
                    }
                }
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        val isWide = maxWidth >= AuxiliaryKeyBarMath.WIDE_BREAKPOINT_DP.dp
        val rows: List<List<KeySlotSpec>> = if (isWide) {
            listOf(config.topRow + config.bottomRow)
        } else {
            listOf(config.topRow, config.bottomRow)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (rowSlots in rows) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AuxiliaryKeyBarMath.ROW_HEIGHT_DP.dp),
                ) {
                    for (slot in rowSlots) {
                        AuxiliaryKeySlot(
                            slot = slot,
                            modifierState = if (slot is KeySlotSpec.Modifier) {
                                modStates[slot.mod] ?: ModifierLatchState.RELEASED
                            } else {
                                ModifierLatchState.RELEASED
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onModifierPressDown = { mod ->
                                if (mod != ModifierKey.SHIFT) {
                                    currentOnBeforeControlKey()
                                }
                                latchMachine.onFingerDown(mod)
                                triggerHaptic()
                                scope.launch {
                                    delay(ModifierLatchMachine.LONG_PRESS_MS)
                                    latchMachine.onHoldTimeout(mod)
                                    if (latchMachine.stateOf(mod) == ModifierLatchState.LOCKED) {
                                        triggerHaptic()
                                    }
                                }
                            },
                            onModifierPressUp = { mod, holdJob ->
                                holdJob?.cancel()
                                latchMachine.onFingerUp(mod)
                            },
                            onSlotPointerInteraction = { interactionSlot ->
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val startX = down.position.x
                                    val startY = down.position.y
                                    val isPageSlot = AuxiliaryKeyBarMath.isPageScrollSlot(interactionSlot)
                                    val isRepeatable = AuxiliaryKeyBarMath.isRepeatableSlot(interactionSlot)

                                    var cancelledBySwipe = false
                                    var emittedInitial = false

                                    val repeatJob: Job? = if (isRepeatable) {
                                        scope.launch {
                                            delay(AuxiliaryKeyBarMath.AUTO_REPEAT_DELAY_MS)
                                            if (!emittedInitial && !cancelledBySwipe) {
                                                emitNonModifier(interactionSlot)
                                                emittedInitial = true
                                            }
                                            while (isActive && !cancelledBySwipe) {
                                                delay(AuxiliaryKeyBarMath.AUTO_REPEAT_INTERVAL_MS)
                                                if (!cancelledBySwipe) {
                                                    emitNonModifier(interactionSlot)
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    }

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        val dx = abs(change.position.x - startX)
                                        val dy = change.position.y - startY

                                        if (dx > touchSlopPx) {
                                            cancelledBySwipe = true
                                            repeatJob?.cancel()
                                            break
                                        }
                                        if (isPageSlot && dy < -swipeUpThresholdPx) {
                                            cancelledBySwipe = true
                                            repeatJob?.cancel()
                                            currentOnHalfScreenScrollUp(
                                                AuxiliaryKeyBarMath.halfScreenLines(currentViewportRows),
                                            )
                                            triggerHaptic()
                                            change.consume()
                                            break
                                        }
                                    }

                                    repeatJob?.cancel()
                                    if (!cancelledBySwipe && !emittedInitial) {
                                        emitNonModifier(interactionSlot)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuxiliaryKeySlot(
    slot: KeySlotSpec,
    modifierState: ModifierLatchState,
    modifier: Modifier = Modifier,
    onModifierPressDown: (ModifierKey) -> Job,
    onModifierPressUp: (ModifierKey, Job?) -> Unit,
    onSlotPointerInteraction: suspend androidx.compose.ui.input.pointer.PointerInputScope.(KeySlotSpec) -> Unit,
) {
    val label = slotLabel(slot)
    val a11yLabel = slotAccessibilityLabel(slot, modifierState)

    val containerColor = when (modifierState) {
        ModifierLatchState.RELEASED -> MaterialTheme.colorScheme.surfaceContainerHigh
        ModifierLatchState.ONE_SHOT -> MaterialTheme.colorScheme.primaryContainer
        ModifierLatchState.LOCKED -> MaterialTheme.colorScheme.primary
        ModifierLatchState.HELD -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (modifierState) {
        ModifierLatchState.LOCKED -> MaterialTheme.colorScheme.onPrimary
        ModifierLatchState.ONE_SHOT,
        ModifierLatchState.HELD -> MaterialTheme.colorScheme.onPrimaryContainer
        ModifierLatchState.RELEASED -> MaterialTheme.colorScheme.onSurface
    }
    val elevation = if (modifierState == ModifierLatchState.HELD) 4.dp else 1.dp
    val border = when (modifierState) {
        ModifierLatchState.HELD -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        ModifierLatchState.LOCKED -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ModifierLatchState.ONE_SHOT -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        ModifierLatchState.RELEASED -> BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    }

    val pointerModifier = if (slot is KeySlotSpec.Modifier) {
        Modifier.pointerInput(slot.mod) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val holdJob = onModifierPressDown(slot.mod)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                }
                onModifierPressUp(slot.mod, holdJob)
            }
        }
    } else {
        Modifier.pointerInput(slot) {
            onSlotPointerInteraction(slot)
        }
    }

    Surface(
        shape = RoundedCornerShape(5.dp),
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = elevation,
        border = border,
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = a11yLabel
            }
            .then(pointerModifier),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 0.2.sp,
                fontWeight = if (modifierState != ModifierLatchState.RELEASED) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )

            if (modifierState == ModifierLatchState.ONE_SHOT) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.58f)
                        .padding(bottom = 2.dp)
                        .height(2.5.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(1.5.dp),
                        ),
                )
            } else if (modifierState == ModifierLatchState.LOCKED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.75f)
                        .padding(bottom = 2.dp)
                        .height(2.5.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(1.5.dp),
                        ),
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 2.dp)
                        .size(10.dp)
                        .background(
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(3.dp),
                        ),
                ) {
                    Text(
                        text = "L",
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

private fun slotLabel(slot: KeySlotSpec): String = when (slot) {
    is KeySlotSpec.Modifier -> slot.mod.name
    is KeySlotSpec.GhosttyKey -> slot.label
    is KeySlotSpec.LiteralText -> slot.label
    is KeySlotSpec.CtrlChord -> slot.label
}

private fun slotAccessibilityLabel(slot: KeySlotSpec, state: ModifierLatchState): String {
    val base = when (slot) {
        is KeySlotSpec.Modifier -> when (slot.mod) {
            ModifierKey.CTRL -> "Control"
            ModifierKey.ALT -> "Alt"
            ModifierKey.SHIFT -> "Shift"
            ModifierKey.SUPER -> "Super"
        }
        is KeySlotSpec.GhosttyKey -> slot.label
        is KeySlotSpec.LiteralText -> slot.label
        is KeySlotSpec.CtrlChord -> "Control ${slot.asciiUpper}"
    }
    return if (slot is KeySlotSpec.Modifier) {
        val stateSuffix = when (state) {
            ModifierLatchState.RELEASED -> "released"
            ModifierLatchState.ONE_SHOT -> "one shot"
            ModifierLatchState.LOCKED -> "locked"
            ModifierLatchState.HELD -> "held"
        }
        "$base, $stateSuffix"
    } else {
        base
    }
}
