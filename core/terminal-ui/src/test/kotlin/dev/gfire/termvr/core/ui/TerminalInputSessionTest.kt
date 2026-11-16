package dev.gfire.termvr.core.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
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

    @Test
    fun `setComposingText immediately dispatches CTRL and ALT single ASCII chords and clears preedit`() {
        var nowMs = 0L
        val h = createHarness(clock = { nowMs })
        h.vtSession.use {
            // Ctrl+c via setComposingText (Gboard/Samsung/SwiftKey behavior)
            h.latch.onFingerDown(ModifierKey.CTRL)
            nowMs += 40L
            h.latch.onFingerUp(ModifierKey.CTRL)
            assertEquals(ModifierLatchState.ONE_SHOT, h.latch.stateOf(ModifierKey.CTRL))

            h.input.setComposingText("c", 1)
            assertArrayEquals(byteArrayOf(0x03), h.allSentBytes())
            assertEquals("", h.input.activePreedit)
            assertEquals(ModifierLatchState.RELEASED, h.latch.stateOf(ModifierKey.CTRL))
            assertEquals(1, h.restartCountProvider())

            // Uppercase 'C' with Ctrl latched also emits 0x03
            h.sentBytes.clear()
            h.latch.onFingerDown(ModifierKey.CTRL)
            nowMs += 40L
            h.latch.onFingerUp(ModifierKey.CTRL)
            h.input.setComposingText("C", 1)
            assertArrayEquals(byteArrayOf(0x03), h.allSentBytes())
            assertEquals("", h.input.activePreedit)
            assertEquals(2, h.restartCountProvider())

            // Alt+b via setComposingText emits ESC + b
            h.sentBytes.clear()
            h.latch.onFingerDown(ModifierKey.ALT)
            nowMs += 40L
            h.latch.onFingerUp(ModifierKey.ALT)
            h.input.setComposingText("b", 1)
            assertArrayEquals("\u001bb".toByteArray(), h.allSentBytes())
            assertEquals(ModifierLatchState.RELEASED, h.latch.stateOf(ModifierKey.ALT))
            assertEquals(3, h.restartCountProvider())
        }
    }

    @Test
    fun `dynamic modifierLatchProvider sees updated latch machine and configureEditorInfo omits cap flags`() {
        val session = NativeVtSession(initialCols = 80, initialRows = 24)
        var currentLatch = ModifierLatchMachine(clockMs = { 0L })
        val sent = mutableListOf<ByteArray>()
        val input = TerminalInputSession(
            sessionProvider = { session },
            modifierLatchProvider = { currentLatch },
            onSendPtyBytes = { sent += it },
        )
        session.use {
            val swappedLatch = ModifierLatchMachine(clockMs = { 0L })
            currentLatch = swappedLatch
            swappedLatch.onFingerDown(ModifierKey.CTRL)
            swappedLatch.onFingerUp(ModifierKey.CTRL)
            input.commitText("d", 1)
            assertArrayEquals(byteArrayOf(0x04), sent.single())
            assertEquals(ModifierLatchState.RELEASED, swappedLatch.stateOf(ModifierKey.CTRL))
        }

        val attrs = EditorInfo()
        TerminalInputConnection.configureEditorInfo(attrs)
        assertEquals(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            attrs.inputType,
        )
        assertEquals(0, attrs.inputType and InputType.TYPE_MASK_VARIATION)
        val capAndAutoFlags = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        assertEquals(0, attrs.inputType and capAndAutoFlags)
        val expectedImeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        assertEquals(expectedImeOptions, attrs.imeOptions)
        assertEquals(32, attrs.initialSelStart)
        assertEquals(32, attrs.initialSelEnd)
    }

    @Test
    fun `korean 2-beolsik liaison backspace in preedit enter commit and jamo CTRL chords work cleanly`() {
        // Property: Hangul liaison batch transitions, in-preedit backspace, soft Return CR, and 2-beolsik CTRL/ALT chords conserve exact bytes.
        // Seam: TerminalInputSession.
        // Oracle: KS X 5002 2-beolsik layout and Design 0001 Section 14.3 CJK composition contract.
        // Fault: deleting shell prompt chars during preedit, emitting LF on soft Enter, or ignoring Ctrl with Korean jamo.
        var nowMs = 0L
        val h = createHarness(clock = { nowMs })
        h.vtSession.use {
            // 1. Compose "한", then trigger liaison batch commit("하") plus compose("나") into "난", then commit Space.
            h.input.setComposingText("ㅎ", 1)
            h.input.setComposingText("하", 1)
            h.input.setComposingText("한", 1)
            h.selectionResets.clear()

            h.input.beginBatchEdit()
            h.input.commitText("하", 1)
            h.input.setComposingText("나", 1)
            // Batch edit keeps active preedit without sending intermediate selection resets.
            assertEquals(0, h.selectionResets.size)
            h.input.endBatchEdit()

            assertEquals("하", h.allSentUtf8())
            assertEquals("나", h.input.activePreedit)

            h.input.setComposingText("난", 1)
            h.input.finishComposingText()
            h.input.commitText(" ", 1)
            assertEquals("하난 ", h.allSentUtf8())

            // 2. Backspace inside active preedit consumes uncommitted preedit chars before sending 0x7F.
            h.sentBytes.clear()
            h.input.setComposingText("한글", 1)
            h.input.deleteSurroundingText(1, 0)
            assertEquals("한", h.input.activePreedit)
            assertEquals(0, h.sentBytes.size, "Deleting uncommitted preedit char must not send 0x7F to PTY")

            // Deleting 2 chars when 1 char remains in preedit clears preedit and sends one backspace (0x7F).
            h.input.deleteSurroundingTextInCodePoints(2, 0)
            assertEquals("", h.input.activePreedit)
            assertArrayEquals(byteArrayOf(0x7F), h.allSentBytes())

            // 3. Soft keyboard Return via commitText("\n", 1) encodes Enter (\r).
            h.sentBytes.clear()
            h.input.commitText("\n", 1)
            assertArrayEquals("\r".toByteArray(), h.allSentBytes())

            // 4. Mid-preedit CTRL tap flushes "한" once, and tapping 'ㅊ' (2-beolsik 'c' key) emits 0x03.
            h.sentBytes.clear()
            h.input.setComposingText("한", 1)
            h.input.flushComposingBeforeControlKey()
            h.latch.onFingerDown(ModifierKey.CTRL)
            nowMs += 40L
            h.latch.onFingerUp(ModifierKey.CTRL)
            assertEquals(ModifierLatchState.ONE_SHOT, h.latch.stateOf(ModifierKey.CTRL))

            h.input.setComposingText("ㅊ", 1)
            assertEquals("", h.input.activePreedit)
            assertEquals(ModifierLatchState.RELEASED, h.latch.stateOf(ModifierKey.CTRL))
            assertArrayEquals("한".toByteArray(Charsets.UTF_8) + byteArrayOf(0x03), h.allSentBytes())

            // 5. ALT plus 'ㅠ' (2-beolsik 'b' key) via commitText emits ESC plus b.
            h.sentBytes.clear()
            h.latch.onFingerDown(ModifierKey.ALT)
            nowMs += 40L
            h.latch.onFingerUp(ModifierKey.ALT)
            h.input.commitText("ㅠ", 1)
            assertArrayEquals("\u001bb".toByteArray(), h.allSentBytes())
            assertEquals(ModifierLatchState.RELEASED, h.latch.stateOf(ModifierKey.ALT))
        }
    }

    @Test
    fun `gboard non-password setComposingRegion and deleteSurroundingText word replacements preserve exact PTY text`() {
        // Property: setComposingRegion(s, e) erases retroactive committed chars only when replaced and is a no-op when cancelled.
        // Seam: TerminalInputSession.setComposingRegion, commitText, finishComposingText, and deleteSurroundingText.
        // Oracle: Conservation of shell line buffer under simulated Backspace, Left, and Right edits.
        // Fault: appending "the" onto "teh" ("tehthe") on setComposingRegion or deleting text on cancelled recomposition.
        val h = createHarness()
        h.vtSession.use {
            // Simulate shell line buffer interpreter for ASCII, 0x7F, ESC[D, and ESC[C.
            fun applyPtyStreamToLineBuffer(chunks: List<ByteArray>): String {
                val raw = chunks.fold(ByteArray(0)) { a, b -> a + b }.toString(Charsets.UTF_8)
                val buf = StringBuilder()
                var cursor = 0
                var i = 0
                while (i < raw.length) {
                    when {
                        raw.startsWith("\u001b[D", i) -> {
                            cursor = (cursor - 1).coerceAtLeast(0)
                            i += 3
                        }
                        raw.startsWith("\u001b[C", i) -> {
                            cursor = (cursor + 1).coerceAtMost(buf.length)
                            i += 3
                        }
                        raw[i] == '\u007f' -> {
                            if (cursor > 0) {
                                buf.deleteCharAt(cursor - 1)
                                cursor--
                            }
                            i++
                        }
                        else -> {
                            buf.insert(cursor, raw[i])
                            cursor++
                            i++
                        }
                    }
                }
                return buf.toString()
            }

            // Case 1: "teh" committed per char, followed by setComposingRegion(29, 32) and commitText("the", 1), yields "the".
            h.input.commitText("t", 1)
            h.input.commitText("e", 1)
            h.input.commitText("h", 1)
            h.input.setComposingRegion(29, 32)
            h.input.commitText("the", 1)
            assertEquals("the", applyPtyStreamToLineBuffer(h.sentBytes))

            // Case 2: setComposingRegion(29, 32) followed by finishComposingText() with no edit leaves "the" unchanged.
            val countBeforeCancel = h.sentBytes.size
            h.input.setComposingRegion(29, 32)
            h.input.finishComposingText()
            assertEquals(countBeforeCancel, h.sentBytes.size)
            assertEquals("the", applyPtyStreamToLineBuffer(h.sentBytes))

            // Case 3: trailing space committed ("teh "), followed by setComposingRegion(28, 31) and commitText("the", 1), yields "the ".
            h.sentBytes.clear()
            h.input.commitText("teh ", 1)
            h.input.beginBatchEdit()
            h.input.setComposingRegion(28, 31)
            h.input.commitText("the", 1)
            h.input.endBatchEdit()
            assertEquals("the ", applyPtyStreamToLineBuffer(h.sentBytes))

            // Case 4: deleteSurroundingText(3, 0) plus commitText("cat", 1) on "dog" yields "cat".
            h.sentBytes.clear()
            h.input.commitText("dog", 1)
            h.input.deleteSurroundingText(3, 0)
            h.input.commitText("cat", 1)
            assertEquals("cat", applyPtyStreamToLineBuffer(h.sentBytes))
        }
    }
}
