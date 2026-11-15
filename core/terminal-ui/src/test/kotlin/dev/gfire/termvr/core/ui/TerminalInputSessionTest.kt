package dev.gfire.termvr.core.ui

import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.model.ModifierLatchState
import dev.gfire.termvr.core.vt.NativeVtSession
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TerminalInputSessionTest {

    private fun createHarness(
        clock: () -> Long = { 0L },
    ): TestHarness {
        val session = NativeVtSession(initialCols = 80, initialRows = 24)
        val latch = ModifierLatchMachine(clockMs = clock)
        val preedits = mutableListOf<String>()
        val sentBytes = mutableListOf<ByteArray>()
        val unsafePrompts = mutableListOf<String>()
        val selectionResets = mutableListOf<Pair<Int, Int>>()
        var restartCount = 0

        val input = TerminalInputSession(
            sessionProvider = { session },
            modifierLatchMachine = latch,
            onPreeditChanged = { preedits += it },
            onSendPtyBytes = { sentBytes += it },
            onUnsafePasteConfirmationNeeded = { unsafePrompts += it },
            onResetImeSelection = { s, e -> selectionResets += (s to e) },
            onRestartInput = { restartCount++ },
        )
        return TestHarness(
            vtSession = session,
            latch = latch,
            input = input,
            preedits = preedits,
            sentBytes = sentBytes,
            unsafePrompts = unsafePrompts,
            selectionResets = selectionResets,
            restartCountProvider = { restartCount },
        )
    }

    private data class TestHarness(
        val vtSession: NativeVtSession,
        val latch: ModifierLatchMachine,
        val input: TerminalInputSession,
        val preedits: MutableList<String>,
        val sentBytes: MutableList<ByteArray>,
        val unsafePrompts: MutableList<String>,
        val selectionResets: MutableList<Pair<Int, Int>>,
        val restartCountProvider: () -> Int,
    ) {
        fun allSentUtf8(): String = sentBytes.fold(ByteArray(0)) { acc, b -> acc + b }.toString(Charsets.UTF_8)
        fun allSentBytes(): ByteArray = sentBytes.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    @Test
    fun `hangul jamo composition writes zero PTY bytes until syllable commit and assembles 한+글`() {
        // Property: setComposingText updates activePreedit with zero PTY writes; commitText sends exact UTF-8 and parks at (32, 32).
        // Seam: TerminalInputSession.setComposingText + commitText.
        // Oracle: Design 0001 Section 14.3 ("ㅎ"+"ㅏ"+"ㄴ"+"글" produces "한글").
        // Fault: premature PTY write of partial jamo or broken sentinel recenter after commit.
        val h = createHarness()
        h.vtSession.use {
            assertEquals(TerminalInputSession.SENTINEL_LENGTH, h.input.sentinelText.length)
            assertEquals(32, h.input.selectionStart)
            assertEquals(32, h.input.selectionEnd)

            h.input.setComposingText("ㅎ", 1)
            h.input.setComposingText("하", 1)
            h.input.setComposingText("한", 1)
            assertEquals("한", h.input.activePreedit)
            assertEquals(0, h.sentBytes.size)

            h.input.commitText("한", 1)
            assertEquals("", h.input.activePreedit)
            assertEquals("한", h.allSentUtf8())
            assertEquals(TerminalInputSession.SENTINEL_TEXT, h.input.sentinelText)
            assertEquals(32, h.input.selectionStart)
            assertEquals(32, h.input.selectionEnd)

            h.input.setComposingText("ㄱ", 1)
            h.input.setComposingText("그", 1)
            h.input.setComposingText("글", 1)
            assertEquals("한", h.allSentUtf8())

            h.input.commitText("글", 1)
            assertEquals("", h.input.activePreedit)
            assertEquals("한글", h.allSentUtf8())
            assertEquals(32, h.input.selectionStart)
            assertEquals(32, h.input.selectionEnd)
        }
    }

    @Test
    fun `latched CTRL encodes single ASCII character and clears ONE_SHOT`() {
        // Property: active modifier mask on single ASCII commit encodes via NativeVtSession.encodeKey and clears ONE_SHOT.
        // Seam: TerminalInputSession.commitText + ModifierLatchMachine.
        // Oracle: ASCII Ctrl+C = 0x03 (ETX).
        // Fault: bypassing key encoder on IME commitText or leaking ONE_SHOT across commits.
        var now = 0L
        val h = createHarness(clock = { now })
        h.vtSession.use {
            h.latch.onFingerDown(ModifierKey.CTRL)
            now += 50L
            h.latch.onFingerUp(ModifierKey.CTRL)
            assertEquals(ModifierLatchState.ONE_SHOT, h.latch.stateOf(ModifierKey.CTRL))

            h.input.commitText("c", 1)
            assertArrayEquals(byteArrayOf(0x03), h.allSentBytes())
            assertEquals(ModifierLatchState.RELEASED, h.latch.stateOf(ModifierKey.CTRL))

            h.input.commitText("d", 1)
            assertArrayEquals(byteArrayOf(0x03, 'd'.code.toByte()), h.allSentBytes())
        }
    }

    @Test
    fun `multiline commit triggers unsafe paste confirmation before writing PTY`() {
        // Property: multi-line commitText invokes isPasteSafe and defers PTY write when unsafe.
        // Seam: TerminalInputSession.commitText + NativeVtSession.isPasteSafe.
        // Oracle: Design 0001 Section 14.3 multi-line paste safety rule.
        // Fault: silently injecting raw newlines into shell PTY without confirmation.
        val h = createHarness()
        h.vtSession.use {
            val payload = "echo 1\nrm -rf /"
            h.input.commitText(payload, 1)

            assertEquals(0, h.sentBytes.size)
            assertEquals(listOf(payload), h.unsafePrompts)
        }
    }

    @Test
    fun `gboard spacebar scrub emits LEFT or RIGHT deltas and recenters at 32`() {
        // Property: setSelection(start, start) emits abs(start - 32) directional keys when preedit is empty, then recenters.
        // Seam: TerminalInputSession.setSelection.
        // Oracle: Design 0001 Section 14.3 Gboard spacebar scrub math + DECCKM awareness.
        // Fault: wrong step count, inverted direction, or emitting scrub while composing.
        val h = createHarness()
        h.vtSession.use {
            // While preedit is active, setSelection must NOT emit cursor moves.
            h.input.setComposingText("ㅎ", 1)
            h.input.setSelection(30, 30)
            assertEquals(0, h.sentBytes.size)

            h.input.commitText("ㅎ", 1)
            h.sentBytes.clear()
            h.selectionResets.clear()

            // Move left by 3 cells: 32 to 29.
            h.input.setSelection(29, 29)
            assertEquals("\u001b[D\u001b[D\u001b[D", h.allSentUtf8())
            assertEquals(32 to 32, h.selectionResets.last())
            assertEquals(32, h.input.selectionStart)

            h.sentBytes.clear()
            // Enable DECCKM and move right by 2 cells: 32 to 34.
            h.vtSession.writePtyBytes("\u001b[?1h".toByteArray())
            h.input.setSelection(34, 34)
            assertEquals("\u001bOC\u001bOC", h.allSentUtf8())
            assertEquals(32 to 32, h.selectionResets.last())
        }
    }

    @Test
    fun `deleteSurroundingText emits backspace 0x7F and forward delete ESC bracket 3 tilde`() {
        // Property: beforeLength maps to 0x7F and afterLength maps to ESC[3~, then sentinel recenters at (32, 32).
        // Seam: TerminalInputSession.deleteSurroundingText & deleteSurroundingTextInCodePoints.
        // Oracle: Design 0001 Section 14.3 delete surrounding contract.
        // Fault: dropping forward delete or leaving sentinel off-center.
        val h = createHarness()
        h.vtSession.use {
            h.input.deleteSurroundingText(2, 0)
            assertArrayEquals(byteArrayOf(0x7F, 0x7F), h.allSentBytes())
            assertEquals(32 to 32, h.selectionResets.last())

            h.sentBytes.clear()
            h.input.deleteSurroundingTextInCodePoints(1, 2)
            val expected = byteArrayOf(0x7F) + "\u001b[3~\u001b[3~".toByteArray()
            assertArrayEquals(expected, h.allSentBytes())
            assertEquals(32 to 32, h.selectionResets.last())
        }
    }

    @Test
    fun `compose flush before control key commits preedit first and restarts input`() {
        // Property: flushComposingBeforeControlKey commits non-empty preedit, clears preedit, resets sentinel, and calls restartInput.
        // Seam: TerminalInputSession.flushComposingBeforeControlKey.
        // Oracle: Design 0001 Section 14.3 compose-flush rule.
        // Fault: discarding Hangul syllable or forgetting imm.restartInput before ESC/CTRL.
        val h = createHarness()
        h.vtSession.use {
            h.input.setComposingText("한", 1)
            assertEquals(0, h.sentBytes.size)

            val flushed = h.input.flushComposingBeforeControlKey()
            assertTrue(flushed)
            assertEquals("", h.input.activePreedit)
            assertEquals("한", h.allSentUtf8())
            assertEquals(1, h.restartCountProvider())
            assertEquals(32, h.input.selectionStart)
            assertEquals(32, h.input.selectionEnd)

            // Subsequent flush when preedit is empty must be a no-op.
            val second = h.input.flushComposingBeforeControlKey()
            assertEquals(false, second)
            assertEquals(1, h.restartCountProvider())
        }
    }

    @Test
    fun `bracketed paste wraps confirmed multi-line paste under DECSET 2004 and LOCKED modifier persists`() {
        // Property: multi-line commitText triggers unsafe-paste gate; confirmed encodePaste wraps in ESC[200~...ESC[201~ under DECSET 2004; LOCKED CTRL persists across ASCII commit.
        // Seam: TerminalInputSession.commitText + NativeVtSession.encodePaste + ModifierLatchMachine.
        // Oracle: DECSET 2004 bracketed paste and Section 14.2 LOCKED modifier rule.
        // Fault: bypassing unsafe-paste prompt on multi-line commitText or clearing LOCKED modifier after one character.
        var nowMs = 100L
        val h = createHarness(clock = { nowMs })
        h.vtSession.use {
            h.vtSession.writePtyBytes("\u001b[?2004h".toByteArray())
            assertTrue(h.vtSession.isBracketedPasteActive())

            h.input.commitText("echo 1\necho 2", 1)
            assertEquals(listOf("echo 1\necho 2"), h.unsafePrompts)
            assertEquals(0, h.sentBytes.size)

            val confirmed = h.vtSession.encodePaste("echo 1\necho 2", allowUnsafe = true)!!
            assertEquals("\u001b[200~echo 1\necho 2\u001b[201~", confirmed.toString(Charsets.UTF_8))

            h.latch.onFingerDown(ModifierKey.CTRL)
            nowMs += 450L
            h.latch.onHoldTimeout(ModifierKey.CTRL)
            h.latch.onFingerUp(ModifierKey.CTRL)
            assertEquals(ModifierLatchState.LOCKED, h.latch.stateOf(ModifierKey.CTRL))

            h.input.commitText("c", 1)
            assertArrayEquals(byteArrayOf(0x03), h.allSentBytes())
            assertEquals(ModifierLatchState.LOCKED, h.latch.stateOf(ModifierKey.CTRL))
        }
    }
}
