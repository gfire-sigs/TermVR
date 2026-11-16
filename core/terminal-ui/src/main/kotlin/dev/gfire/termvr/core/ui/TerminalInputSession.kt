package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.VtKey
import dev.gfire.termvr.core.vt.VtKeyAction
import dev.gfire.termvr.core.vt.VtKeyEvent
import dev.gfire.termvr.core.vt.VtModifierMask
import dev.gfire.termvr.core.vt.VtPasteChunker
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    private var retroactiveDeleteCount: Int = 0
    private var retroactiveAfterGap: Int = 0
    private var pendingPostCommitRightMoves: Int = 0
    private var batchEditDepth: Int = 0
    private var pendingSelectionResetAfterBatch: Boolean = false

    val hasRetroactiveComposingRegion: Boolean
        get() = retroactiveDeleteCount > 0

    fun beginBatchEdit(): Boolean {
        batchEditDepth++
        return true
    }

    fun endBatchEdit(): Boolean {
        if (batchEditDepth > 0) {
            batchEditDepth--
        }
        if (batchEditDepth == 0 && pendingSelectionResetAfterBatch) {
            pendingSelectionResetAfterBatch = false
            if (activePreedit.isEmpty() && retroactiveDeleteCount == 0) {
                onResetImeSelection(SENTINEL_ANCHOR, SENTINEL_ANCHOR)
            }
        }
        return batchEditDepth > 0
    }

    fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val next = text?.toString() ?: ""
        val mods = modifierLatchProvider().activeModifierMask()
        if ((mods and (VtModifierMask.CTRL or VtModifierMask.ALT)) != 0 && next.length == 1) {
            val chord = resolveModifiedSingleChar(next[0], allowJamoMapping = true)
            if (chord != null) {
                retroactiveDeleteCount = 0
                retroactiveAfterGap = 0
                pendingPostCommitRightMoves = 0
                emitSingleAsciiModifiedKey(chord.codepoint, chord.utf8, mods or chord.extraMods)
                clearPreeditAndRecenter()
                onRestartInput()
                return true
            }
        }

        if (retroactiveDeleteCount > 0) {
            val gap = retroactiveAfterGap
            val delCount = retroactiveDeleteCount
            retroactiveDeleteCount = 0
            retroactiveAfterGap = 0
            emitCursorMove(VtKey.LEFT, gap)
            emitBackspaces(delCount)
            pendingPostCommitRightMoves += gap
        }

        if (next.isEmpty()) {
            val rightMoves = pendingPostCommitRightMoves
            pendingPostCommitRightMoves = 0
            clearPreeditAndRecenter()
            emitCursorMove(VtKey.RIGHT, rightMoves)
            return true
        }

        updatePreeditBuffer(next, newCursorPosition)
        return true
    }

    fun setComposingRegion(start: Int, end: Int): Boolean {
        val s = min(start, end).coerceIn(0, sentinelText.length)
        val e = max(start, end).coerceIn(0, sentinelText.length)
        if (activePreedit.isNotEmpty()) {
            if (s == e) {
                return finishComposingText()
            }
            val compStart = SENTINEL_ANCHOR
            val compEnd = SENTINEL_ANCHOR + activePreedit.length
            if (s > compStart) {
                val committedPrefixLen = (s - compStart).coerceAtMost(activePreedit.length)
                val prefix = activePreedit.substring(0, committedPrefixLen)
                if (prefix.isNotEmpty()) {
                    onSendPtyBytes(prefix.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val preStart = (s - compStart).coerceIn(0, activePreedit.length)
            val preEnd = (e - compStart).coerceIn(preStart, activePreedit.length)
            val remainingPreedit = activePreedit.substring(preStart, preEnd)
            retroactiveDeleteCount = if (s < compStart) (compStart - s) else 0
            retroactiveAfterGap = 0
            if (remainingPreedit.isEmpty()) {
                activePreedit = ""
                onPreeditChanged("")
                resetSentinel()
            } else {
                updatePreeditBuffer(remainingPreedit, 1)
            }
            return true
        }

        if (s == e) {
            retroactiveDeleteCount = 0
            retroactiveAfterGap = 0
            return true
        }
        if (e <= SENTINEL_ANCHOR) {
            retroactiveDeleteCount = (e - s).coerceIn(0, SENTINEL_ANCHOR)
            retroactiveAfterGap = (SENTINEL_ANCHOR - e).coerceIn(0, SENTINEL_ANCHOR)
        } else {
            retroactiveDeleteCount = (SENTINEL_ANCHOR - s).coerceAtLeast(0)
            retroactiveAfterGap = 0
        }
        return true
    }

    fun finishComposingText(): Boolean {
        retroactiveDeleteCount = 0
        retroactiveAfterGap = 0
        val rightMoves = pendingPostCommitRightMoves
        pendingPostCommitRightMoves = 0
        if (activePreedit.isNotEmpty()) {
            val flushed = activePreedit
            clearPreeditAndRecenter()
            onSendPtyBytes(flushed.toByteArray(StandardCharsets.UTF_8))
            emitCursorMove(VtKey.RIGHT, rightMoves)
        } else {
            resetSentinel()
        }
        return true
    }

    fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString() ?: ""
        if (activePreedit.isNotEmpty()) {
            activePreedit = ""
            onPreeditChanged("")
        }

        var rightMovesAfter = pendingPostCommitRightMoves
        pendingPostCommitRightMoves = 0
        if (retroactiveDeleteCount > 0) {
            val gap = retroactiveAfterGap
            val delCount = retroactiveDeleteCount
            retroactiveDeleteCount = 0
            retroactiveAfterGap = 0
            emitCursorMove(VtKey.LEFT, gap)
            emitBackspaces(delCount)
            rightMovesAfter += gap
        }

        resetSentinel()
        if (committed.isEmpty()) {
            emitCursorMove(VtKey.RIGHT, rightMovesAfter)
            return true
        }

        val session = sessionProvider()
        val latch = modifierLatchProvider()
        val mods = latch.activeModifierMask()
        val isMultiLine = (committed.contains('\n') || committed.contains('\r')) && committed.length > 1

        if (mods != VtModifierMask.NONE && committed.length == 1) {
            val allowJamo = (mods and (VtModifierMask.CTRL or VtModifierMask.ALT)) != 0
            val chord = resolveModifiedSingleChar(committed[0], allowJamoMapping = allowJamo)
            if (chord != null) {
                emitSingleAsciiModifiedKey(chord.codepoint, chord.utf8, mods or chord.extraMods)
                emitCursorMove(VtKey.RIGHT, rightMovesAfter)
                return true
            }
        }

        if ((committed == "\n" || committed == "\r") && mods == VtModifierMask.NONE) {
            val enterBytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.ENTER, action = VtKeyAction.PRESS, modifierMask = VtModifierMask.NONE),
            ) ?: "\r".toByteArray(StandardCharsets.US_ASCII)
            if (enterBytes.isNotEmpty()) {
                onSendPtyBytes(enterBytes)
            }
            emitCursorMove(VtKey.RIGHT, rightMovesAfter)
            return true
        }

        if (isMultiLine) {
            if (session != null && !session.isPasteSafe(committed)) {
                onUnsafePasteConfirmationNeeded(committed)
                emitCursorMove(VtKey.RIGHT, rightMovesAfter)
                return true
            }
            val chunks = session?.encodePasteChunks(committed, allowUnsafe = false)
                ?: VtPasteChunker.chunkUtf8Safe(committed.toByteArray(StandardCharsets.UTF_8))
            for (chunk in chunks) {
                if (chunk.isNotEmpty()) {
                    onSendPtyBytes(chunk)
                }
            }
            emitCursorMove(VtKey.RIGHT, rightMovesAfter)
            return true
        }

        if (mods != VtModifierMask.NONE) {
            latch.onNonModifierKeyEmitted()
        }
        onSendPtyBytes(committed.toByteArray(StandardCharsets.UTF_8))
        emitCursorMove(VtKey.RIGHT, rightMovesAfter)
        return true
    }

    private fun updatePreeditBuffer(next: String, newCursorPosition: Int) {
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
        retroactiveDeleteCount = 0
        retroactiveAfterGap = 0
        if (start == end && start != SENTINEL_ANCHOR) {
            val delta = start - SENTINEL_ANCHOR
            val steps = abs(delta).coerceAtMost(SENTINEL_LENGTH)
            val key = if (delta < 0) VtKey.LEFT else VtKey.RIGHT
            val latch = modifierLatchProvider()
            val mods = latch.activeModifierMask()
            emitCursorMove(key, steps, mods)
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
        var before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        retroactiveDeleteCount = 0
        retroactiveAfterGap = 0

        if (activePreedit.isNotEmpty() && before > 0) {
            val consumed = min(before, activePreedit.length)
            before -= consumed
            val remainingPreedit = activePreedit.substring(0, activePreedit.length - consumed)
            if (remainingPreedit.isEmpty()) {
                activePreedit = ""
                onPreeditChanged("")
            } else {
                updatePreeditBuffer(remainingPreedit, 1)
                if (before == 0 && after == 0) {
                    return true
                }
            }
        }

        return emitDeletesAndRecenter(before, after)
    }

    fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        var before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        retroactiveDeleteCount = 0
        retroactiveAfterGap = 0

        if (activePreedit.isNotEmpty() && before > 0) {
            val preeditCpCount = activePreedit.codePointCount(0, activePreedit.length)
            val consumedCp = min(before, preeditCpCount)
            before -= consumedCp
            val keepCp = preeditCpCount - consumedCp
            val cutIndex = if (keepCp <= 0) 0 else activePreedit.offsetByCodePoints(0, keepCp)
            val remainingPreedit = activePreedit.substring(0, cutIndex)
            if (remainingPreedit.isEmpty()) {
                activePreedit = ""
                onPreeditChanged("")
            } else {
                updatePreeditBuffer(remainingPreedit, 1)
                if (before == 0 && after == 0) {
                    return true
                }
            }
        }

        return emitDeletesAndRecenter(before, after)
    }

    private fun emitDeletesAndRecenter(beforeCount: Int, afterCount: Int): Boolean {
        val session = sessionProvider()
        val latch = modifierLatchProvider()
        val mods = latch.activeModifierMask()

        repeat(beforeCount) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.BACKSPACE, action = VtKeyAction.PRESS, modifierMask = mods),
            ) ?: byteArrayOf(0x7F)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
        repeat(afterCount) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.DELETE, action = VtKeyAction.PRESS, modifierMask = mods),
            ) ?: "\u001b[3~".toByteArray(StandardCharsets.US_ASCII)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
        if (beforeCount > 0 || afterCount > 0) {
            latch.onNonModifierKeyEmitted()
        }
        if (activePreedit.isEmpty()) {
            resetSentinel()
        }
        return true
    }

    fun flushComposingBeforeControlKey(): Boolean {
        val hadRetroactive = retroactiveDeleteCount > 0
        retroactiveDeleteCount = 0
        retroactiveAfterGap = 0
        val rightMoves = pendingPostCommitRightMoves
        pendingPostCommitRightMoves = 0

        if (activePreedit.isEmpty()) {
            if (hadRetroactive) {
                resetSentinel()
                onRestartInput()
                return true
            }
            return false
        }
        val flushed = activePreedit
        clearPreeditAndRecenter()
        onSendPtyBytes(flushed.toByteArray(StandardCharsets.UTF_8))
        emitCursorMove(VtKey.RIGHT, rightMoves)
        onRestartInput()
        return true
    }

    fun resetSentinel() {
        sentinelText = SENTINEL_TEXT
        selectionStart = SENTINEL_ANCHOR
        selectionEnd = SENTINEL_ANCHOR
        if (batchEditDepth > 0) {
            pendingSelectionResetAfterBatch = true
        } else {
            onResetImeSelection(SENTINEL_ANCHOR, SENTINEL_ANCHOR)
        }
    }

    private fun clearPreeditAndRecenter() {
        if (activePreedit.isNotEmpty()) {
            activePreedit = ""
            onPreeditChanged("")
        }
        resetSentinel()
    }

    private fun emitBackspaces(count: Int) {
        if (count <= 0) return
        val session = sessionProvider()
        repeat(count) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = VtKey.BACKSPACE, action = VtKeyAction.PRESS, modifierMask = VtModifierMask.NONE),
            ) ?: byteArrayOf(0x7F)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
    }

    private fun emitCursorMove(key: VtKey, steps: Int, mods: Int = VtModifierMask.NONE) {
        if (steps <= 0) return
        val session = sessionProvider()
        repeat(steps) {
            val bytes = session?.encodeKey(
                VtKeyEvent(key = key, action = VtKeyAction.PRESS, modifierMask = mods),
            ) ?: defaultArrowBytes(key)
            if (bytes.isNotEmpty()) {
                onSendPtyBytes(bytes)
            }
        }
    }

    private fun defaultArrowBytes(key: VtKey): ByteArray = when (key) {
        VtKey.LEFT -> "\u001b[D".toByteArray(StandardCharsets.US_ASCII)
        VtKey.RIGHT -> "\u001b[C".toByteArray(StandardCharsets.US_ASCII)
        else -> ByteArray(0)
    }

    private data class ResolvedChordChar(
        val codepoint: Int,
        val utf8: String,
        val extraMods: Int = VtModifierMask.NONE,
    )

    private fun resolveModifiedSingleChar(ch: Char, allowJamoMapping: Boolean): ResolvedChordChar? {
        if (ch.code in 0x00..0x7F) {
            return ResolvedChordChar(ch.code, ch.toString())
        }
        if (!allowJamoMapping) return null
        val mapped = mapTwoBeolsikJamoToAscii(ch) ?: return null
        val extra = if (mapped.isUpperCase()) VtModifierMask.SHIFT else VtModifierMask.NONE
        return ResolvedChordChar(mapped.code, mapped.toString(), extra)
    }

    companion object {
        const val SENTINEL_LENGTH: Int = 64
        const val SENTINEL_ANCHOR: Int = 32
        val SENTINEL_TEXT: String = " ".repeat(SENTINEL_LENGTH)

        /**
         * Maps a Korean KS X 5002 2-beolsik Hangul jamo to its underlying QWERTY ASCII key for CTRL/ALT chords.
         */
        internal fun mapTwoBeolsikJamoToAscii(ch: Char): Char? = when (ch) {
            'ㅂ', '\u1107' -> 'q'
            'ㅃ', '\u1108' -> 'Q'
            'ㅈ', '\u110C' -> 'w'
            'ㅉ', '\u110D' -> 'W'
            'ㄷ', '\u1103' -> 'e'
            'ㄸ', '\u1104' -> 'E'
            'ㄱ', '\u1100' -> 'r'
            'ㄲ', '\u1101' -> 'R'
            'ㅅ', '\u1109' -> 't'
            'ㅆ', '\u110A' -> 'T'
            'ㅛ', '\u116D' -> 'y'
            'ㅕ', '\u1167' -> 'u'
            'ㅑ', '\u1163' -> 'i'
            'ㅐ', '\u1162' -> 'o'
            'ㅒ', '\u1164' -> 'O'
            'ㅔ', '\u1166' -> 'p'
            'ㅖ', '\u1168' -> 'P'
            'ㅁ', '\u1106' -> 'a'
            'ㄴ', '\u1102' -> 's'
            'ㅇ', '\u110B' -> 'd'
            'ㄹ', '\u1105' -> 'f'
            'ㅎ', '\u1112' -> 'g'
            'ㅗ', '\u1169' -> 'h'
            'ㅓ', '\u1165' -> 'j'
            'ㅏ', '\u1161' -> 'k'
            'ㅣ', '\u1175' -> 'l'
            'ㅋ', '\u110F' -> 'z'
            'ㅌ', '\u1110' -> 'x'
            'ㅊ', '\u110E' -> 'c'
            'ㅍ', '\u1111' -> 'v'
            'ㅠ', '\u1172' -> 'b'
            'ㅜ', '\u116E' -> 'n'
            'ㅡ', '\u1173' -> 'm'
            else -> null
        }
    }
}
