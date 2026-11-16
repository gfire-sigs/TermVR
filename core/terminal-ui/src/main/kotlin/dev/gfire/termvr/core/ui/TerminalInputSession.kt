package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.VtKey
import dev.gfire.termvr.core.vt.VtKeyAction
import dev.gfire.termvr.core.vt.VtKeyEvent
import dev.gfire.termvr.core.vt.VtModifierMask
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/**
 * Manages the 64-space virtual sentinel buffer and CJK/Hangul composition state.
 */
class TerminalInputSession(
    private val sessionProvider: () -> NativeVtSession?,
    private val modifierLatchProvider: () -> ModifierLatchMachine,
    private val onPreeditChanged: (String) -> Unit = {},
    private val onSendPtyBytes: (ByteArray) -> Unit = {},
    private val onUnsafePasteConfirmationNeeded: (String) -> Unit = {},
    private val onResetImeSelection: (Int, Int) -> Unit = { _, _ -> },
    private val onRestartInput: () -> Unit = {},
) {
    constructor(
        sessionProvider: () -> NativeVtSession?,
        modifierLatchMachine: ModifierLatchMachine,
        onPreeditChanged: (String) -> Unit = {},
        onSendPtyBytes: (ByteArray) -> Unit = {},
        onUnsafePasteConfirmationNeeded: (String) -> Unit = {},
        onResetImeSelection: (Int, Int) -> Unit = { _, _ -> },
        onRestartInput: () -> Unit = {},
    ) : this(
        sessionProvider = sessionProvider,
        modifierLatchProvider = { modifierLatchMachine },
        onPreeditChanged = onPreeditChanged,
        onSendPtyBytes = onSendPtyBytes,
        onUnsafePasteConfirmationNeeded = onUnsafePasteConfirmationNeeded,
        onResetImeSelection = onResetImeSelection,
        onRestartInput = onRestartInput,
    )

    var activePreedit: String = ""
        private set

    var sentinelText: String = SENTINEL_TEXT
        private set

    var selectionStart: Int = SENTINEL_ANCHOR
        private set

    var selectionEnd: Int = SENTINEL_ANCHOR
        private set

    fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val next = text?.toString() ?: ""
        val mods = modifierLatchProvider().activeModifierMask()
        if ((mods and (VtModifierMask.CTRL or VtModifierMask.ALT)) != 0 &&
            next.length == 1 &&
            next[0].code in 0x20..0x7E
        ) {
            emitSingleAsciiModifiedKey(next[0].code, next, mods)
            clearPreeditAndRecenter()
            onRestartInput()
            return true
        }

        activePreedit = next
        val prefix = SENTINEL_TEXT.substring(0, SENTINEL_ANCHOR)
        val suffix = SENTINEL_TEXT.substring(SENTINEL_ANCHOR)
        sentinelText = prefix + next + suffix
        val cursorOffset = if (newCursorPosition > 0) {
            SENTINEL_ANCHOR + next.length + (newCursorPosition - 1)
        } else {
            SENTINEL_ANCHOR + newCursorPosition
        }.coerceIn(0, sentinelText.length)
        selectionStart = cursorOffset
        selectionEnd = cursorOffset
        onPreeditChanged(activePreedit)
        return true
    }

    fun finishComposingText(): Boolean {
        if (activePreedit.isNotEmpty()) {
            val flushed = activePreedit
            clearPreeditAndRecenter()
            onSendPtyBytes(flushed.toByteArray(StandardCharsets.UTF_8))
        } else {
            resetSentinel()
        }
        return true
    }

    fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString() ?: ""
        clearPreeditAndRecenter()
        if (committed.isEmpty()) {
            return true
        }

        val session = sessionProvider()
        val latch = modifierLatchProvider()
        val mods = latch.activeModifierMask()
        val isMultiLine = (committed.contains('\n') || committed.contains('\r')) && committed.length > 1

        if (mods != VtModifierMask.NONE && committed.length == 1 && committed[0].code in 0..0x7F) {
            emitSingleAsciiModifiedKey(committed[0].code, committed, mods)
            return true
        }

        if (isMultiLine) {
            if (session != null && !session.isPasteSafe(committed)) {
                onUnsafePasteConfirmationNeeded(committed)
                return true
            }
            val encoded = session?.encodePaste(committed, allowUnsafe = false)
                ?: committed.toByteArray(StandardCharsets.UTF_8)
            if (encoded.isNotEmpty()) {
                onSendPtyBytes(encoded)
            }
            return true
        }

        if (mods != VtModifierMask.NONE) {
            latch.onNonModifierKeyEmitted()
        }
        onSendPtyBytes(committed.toByteArray(StandardCharsets.UTF_8))
        return true
    }

    private fun emitSingleAsciiModifiedKey(cp: Int, text: String, mods: Int) {
        val encoded = sessionProvider()?.encodeKey(
            VtKeyEvent(
                key = VtKey.UNICODE,
                action = VtKeyAction.PRESS,
                modifierMask = mods,
                unicodeCodepoint = cp,
                utf8Text = text,
            ),
        ) ?: text.toByteArray(StandardCharsets.UTF_8)
        modifierLatchProvider().onNonModifierKeyEmitted()
        if (encoded.isNotEmpty()) {
            onSendPtyBytes(encoded)
        }
    }

    fun setSelection(start: Int, end: Int): Boolean {
        if (activePreedit.isNotEmpty()) {
            return true
        }
        if (start == end && start != SENTINEL_ANCHOR) {
            val delta = start - SENTINEL_ANCHOR
            val steps = abs(delta).coerceAtMost(SENTINEL_LENGTH)
            val key = if (delta < 0) VtKey.LEFT else VtKey.RIGHT
            val session = sessionProvider()
            val latch = modifierLatchProvider()
            val mods = latch.activeModifierMask()
            repeat(steps) {
                val bytes = session?.encodeKey(
                    VtKeyEvent(
                        key = key,
                        action = VtKeyAction.PRESS,
                        modifierMask = mods,
                    ),
                ) ?: defaultArrowBytes(key)
                if (bytes.isNotEmpty()) {
                    onSendPtyBytes(bytes)
                }
            }
            if (steps > 0) {
                latch.onNonModifierKeyEmitted()
            }
            resetSentinel()
            return true
        }
        selectionStart = start.coerceIn(0, sentinelText.length)
        selectionEnd = end.coerceIn(0, sentinelText.length)
        return true
    }

    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        val session = sessionProvider()
        val latch = modifierLatchProvider()
        val mods = latch.activeModifierMask()

        repeat(before) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.BACKSPACE, action = VtKeyAction.PRESS, modifierMask = mods),
            ) ?: byteArrayOf(0x7F)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
        repeat(after) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.DELETE, action = VtKeyAction.PRESS, modifierMask = mods),
            ) ?: "\u001b[3~".toByteArray(StandardCharsets.US_ASCII)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
        if (before > 0 || after > 0) {
            latch.onNonModifierKeyEmitted()
        }
        resetSentinel()
        return true
    }

    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        deleteSurroundingText(beforeLength, afterLength)

    fun flushComposingBeforeControlKey(): Boolean {
        if (activePreedit.isEmpty()) {
            return false
        }
        val flushed = activePreedit
        clearPreeditAndRecenter()
        onSendPtyBytes(flushed.toByteArray(StandardCharsets.UTF_8))
        onRestartInput()
        return true
    }

    fun resetSentinel() {
        sentinelText = SENTINEL_TEXT
        selectionStart = SENTINEL_ANCHOR
        selectionEnd = SENTINEL_ANCHOR
        onResetImeSelection(SENTINEL_ANCHOR, SENTINEL_ANCHOR)
    }

    private fun clearPreeditAndRecenter() {
        if (activePreedit.isNotEmpty()) {
            activePreedit = ""
            onPreeditChanged("")
        }
        resetSentinel()
    }

    private fun defaultArrowBytes(key: VtKey): ByteArray = when (key) {
        VtKey.LEFT -> "\u001b[D".toByteArray(StandardCharsets.US_ASCII)
        VtKey.RIGHT -> "\u001b[C".toByteArray(StandardCharsets.US_ASCII)
        else -> ByteArray(0)
    }

    companion object {
        const val SENTINEL_LENGTH: Int = 64
        const val SENTINEL_ANCHOR: Int = 32
        val SENTINEL_TEXT: String = " ".repeat(SENTINEL_LENGTH)
    }
}
