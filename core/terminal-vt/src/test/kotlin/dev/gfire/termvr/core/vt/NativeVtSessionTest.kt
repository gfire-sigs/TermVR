package dev.gfire.termvr.core.vt

import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class NativeVtSessionTest {

    @Test
    fun `session writes PTY bytes resizes grid and formats viewport`() {
        NativeVtSession(initialCols = 40, initialRows = 10).use { session ->
            val payload = "hello termvr\r\nsecond line".toByteArray(StandardCharsets.UTF_8)
            session.writePtyBytes(payload)

            val text = session.formatViewportText()
            assertTrue(text.contains("hello termvr"))
            assertTrue(text.contains("second line"))
            assertTrue(session.isDirty())

            assertTrue(session.resizeGrid(newCols = 80, newRows = 24, cellWidthPx = 12, cellHeightPx = 24))
            assertEquals(80, session.cols)
            assertEquals(24, session.rows)
        }
    }

    @Test
    fun `key encoder respects DECCKM and CTRL chords`() {
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            val normalUp = session.encodeKey(VtKeyEvent(key = VtKey.UP))
            assertArrayEquals("\u001b[A".toByteArray(), normalUp)

            session.writePtyBytes("\u001b[?1h".toByteArray())
            assertTrue(session.isApplicationCursorActive())
            val appUp = session.encodeKey(VtKeyEvent(key = VtKey.UP))
            assertArrayEquals("\u001bOA".toByteArray(), appUp)

            val ctrlC = session.encodeKey(
                VtKeyEvent(
                    key = VtKey.UNICODE,
                    modifierMask = VtModifierMask.CTRL,
                    unicodeCodepoint = 'c'.code,
                    utf8Text = "c",
                ),
            )
            assertArrayEquals(byteArrayOf(0x03), ctrlC)
        }
    }

    @Test
    fun `mouse encoder emits SGR 1006 sequences only when tracking enabled`() {
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            assertFalse(session.isMouseTrackingActive())
            val disabled = session.encodeMouse(
                VtMouseEvent(VtMouseAction.PRESS, VtMouseButton.LEFT, col = 4, row = 2),
            )
            assertEquals(0, disabled.size)

            session.writePtyBytes("\u001b[?1000h\u001b[?1006h".toByteArray())
            assertTrue(session.isMouseTrackingActive())
            val press = session.encodeMouse(
                VtMouseEvent(VtMouseAction.PRESS, VtMouseButton.LEFT, col = 4, row = 2),
            )
            assertEquals("\u001b[<0;5;3M", String(press, StandardCharsets.UTF_8))

            val release = session.encodeMouse(
                VtMouseEvent(VtMouseAction.RELEASE, VtMouseButton.LEFT, col = 4, row = 2),
            )
            assertEquals("\u001b[<0;5;3m", String(release, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `paste safety rejects multiline and bracketed paste wraps payload`() {
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            assertTrue(session.isPasteSafe("ls -la"))
            assertFalse(session.isPasteSafe("echo hi\nrm -rf /"))
            assertFalse(session.isPasteSafe("esc\u001bpayload"))
            assertNull(session.encodePaste("line1\nline2", allowUnsafe = false))

            session.writePtyBytes("\u001b[?2004h".toByteArray())
            assertTrue(session.isBracketedPasteActive())
            val wrapped = session.encodePaste("line1\nline2", allowUnsafe = true)
            assertNotNull(wrapped)
            assertEquals("\u001b[200~line1\nline2\u001b[201~", String(wrapped!!, StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `search scrollback and link detection locate matches and targets`() {
        NativeVtSession(initialCols = 80, initialRows = 5).use { session ->
            val lines = (1..8).joinToString("\r\n") { idx ->
                if (idx == 6) "visit https://termvr.dev/docs and /workspace/app.kt:42"
                else "log entry #$idx"
            }
            session.writePtyBytes(lines.toByteArray(StandardCharsets.UTF_8))

            assertTrue(session.getScrollbackCount() >= 3)
            val matches = session.searchScrollback("entry #1", useRegex = false, caseSensitive = false)
            assertEquals(1, matches.size)
            assertEquals(-1, matches[0].viewportRow)

            val regexMatches = session.searchScrollback("""app\.kt:[0-9]+""", useRegex = true, caseSensitive = true)
            assertEquals(1, regexMatches.size)
            assertTrue(regexMatches[0].viewportRow >= 0)

            val urlRow = regexMatches[0].viewportRow
            val urlLink = session.detectLinkAt(urlRow, 10)
            assertNotNull(urlLink)
            assertEquals(VtLinkKind.URL, urlLink!!.kind)
            assertEquals("https://termvr.dev/docs", urlLink.target)

            val pathLink = session.detectLinkAt(urlRow, 40)
            assertNotNull(pathLink)
            assertEquals(VtLinkKind.FILEPATH, pathLink!!.kind)
            assertEquals("/workspace/app.kt:42", pathLink.target)
        }
    }

    @Test
    fun `close detaches pane before freeing backend and is idempotent`() {
        val events = mutableListOf<String>()
        val fakeBackend = object : VtSessionBackend {
            override val nativeHandle: Long = 42L
            override fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) = true
            override fun writePtyBytes(data: ByteArray, offset: Int, length: Int) = Unit
            override fun drainPtyReplies() = ByteArray(0)
            override fun setPalette(palette: ColorPalette) = Unit
            override fun setScrollbackOffset(linesFromBottom: Int) = Unit
            override fun getScrollbackOffset() = 0
            override fun getScrollbackCount() = 0
            override fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) = Unit
            override fun clearSelection() = Unit
            override fun isDirty() = false
            override fun isMouseTrackingActive() = false
            override fun isBracketedPasteActive() = false
            override fun isApplicationCursorActive() = false
            override fun getWindowTitle() = ""
            override fun getCursorState() = VtCursorState(0, 0, true, true, VtCursorShape.BLOCK)
            override fun encodeKey(event: VtKeyEvent) = ByteArray(0)
            override fun encodeMouse(event: VtMouseEvent) = ByteArray(0)
            override fun isPasteSafe(text: String) = true
            override fun encodePaste(text: String, allowUnsafe: Boolean) = ByteArray(0)
            override fun formatText(
                emitVt: Boolean,
                useSelection: Boolean,
                startRow: Int,
                startCol: Int,
                endRow: Int,
                endCol: Int,
                trimTrailingWhitespace: Boolean,
                unwrapSoftWraps: Boolean,
            ) = ""
            override fun searchScrollback(query: String, useRegex: Boolean, caseSensitive: Boolean) = emptyList<VtSearchMatch>()
            override fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? = null
            override fun close() {
                events += "backend_close"
            }
        }

        val session = NativeVtSession(
            onDetachPane = { handle -> events += "detach:$handle" },
            backendFactory = { _, _, _ -> fakeBackend },
        )

        session.close()
        session.close()

        assertTrue(session.isClosed)
        assertEquals(0L, session.nativeHandle)
        assertEquals(listOf("detach:42", "backend_close"), events)
    }

    @Test
    fun `renderer configures fonts tracks context epoch and renders multi-pane targets`() {
        NativeVtSession().use { pane1 ->
            NativeVtSession().use { pane2 ->
                NativeGlRenderer().use { renderer ->
                    assertTrue(renderer.initGl())
                    val metrics = renderer.configureFont(FontConfig(sizeSp = 16.0f), density = 2.0f)
                    assertTrue(metrics.cellWidthPx > 0)
                    assertTrue(metrics.cellHeightPx > metrics.cellWidthPx)

                    val epoch2 = renderer.notifyContextLost()
                    assertEquals(2L, epoch2)
                    assertEquals(2L, renderer.contextEpoch)

                    val drawn = renderer.drawFrame(
                        surfaceWidthPx = 1080,
                        surfaceHeightPx = 1920,
                        panes = listOf(
                            PaneRenderTarget(
                                session = pane1,
                                xPx = 0,
                                yPx = 0,
                                widthPx = 539,
                                heightPx = 1920,
                                focused = true,
                                cursorBlinkVisible = true,
                                preeditText = "한",
                                preeditCursorCol = 3,
                                preeditCursorRow = 0,
                            ),
                            PaneRenderTarget(
                                session = pane2,
                                xPx = 541,
                                yPx = 0,
                                widthPx = 539,
                                heightPx = 1920,
                                focused = false,
                                cursorBlinkVisible = false,
                            ),
                        ),
                        dividers = listOf(SplitDividerRect(539, 0, 2, 1920, 0x30363DFFu)),
                        forceFullRedraw = true,
                    )
                    assertTrue(drawn)
                }
            }
        }
    }

    @Test
    fun `terminal queries queue replies and drain resets queue`() {
        NativeVtSession(initialCols = 40, initialRows = 12).use { session ->
            assertTrue(session.resizeGrid(newCols = 50, newRows = 20, cellWidthPx = 10, cellHeightPx = 20))
            val seq = (
                "\u001b[4;7H" +
                    "\u001b[6n" +
                    "\u001b[5n" +
                    "\u001b[c" +
                    "\u001b[14t" +
                    "\u001b[18t" +
                    "\u001b]10;?\u0007" +
                    "\u001b]11;?\u001b\\"
                ).toByteArray(StandardCharsets.UTF_8)
            session.writePtyBytes(seq)

            val replies = String(session.drainPtyReplies(), StandardCharsets.UTF_8)
            assertTrue(replies.contains("\u001b[4;7R"))
            assertTrue(replies.contains("\u001b[0n"))
            assertTrue(replies.contains("\u001b[?62;"))
            assertTrue(replies.contains("\u001b[4;400;500t"))
            assertTrue(replies.contains("\u001b[8;20;50t"))
            assertTrue(replies.contains("\u001b]10;rgb:"))
            assertTrue(replies.contains("\u001b]11;rgb:"))

            assertEquals(0, session.drainPtyReplies().size)
        }
    }

    @Test
    fun `clear resets viewport and scrollback and DECAWM positioning renders columns`() {
        NativeVtSession(initialCols = 32, initialRows = 8).use { session ->
            val filler = (1..12).joinToString("\r\n") { "row-$it" } + "\r\n"
            session.writePtyBytes(filler.toByteArray(StandardCharsets.UTF_8))
            assertTrue(session.getScrollbackCount() > 0)
            session.setScrollbackOffset(2)
            assertEquals(2, session.getScrollbackOffset())

            session.writePtyBytes("\u001b[H\u001b[2J\u001b[3J".toByteArray(StandardCharsets.UTF_8))
            assertEquals(0, session.getScrollbackCount())
            assertEquals(0, session.getScrollbackOffset())
            assertTrue(session.formatViewportText(trimTrailingWhitespace = true).trim().isEmpty())

            val fastfetchLike = (
                "\u001b[?7l" +
                    "ART1\r\n" +
                    "ART2\r\n" +
                    "ART3" +
                    "\u001b[2A\u001b[4CHost: TermVR\r\n" +
                    "\u001b[8CTerm: xterm-256color" +
                    "\u001b[?7h"
                ).toByteArray(StandardCharsets.UTF_8)
            session.writePtyBytes(fastfetchLike)
            val text = session.formatViewportText()
            assertTrue(text.contains("ART1    Host: TermVR"))
            assertTrue(text.contains("ART2    Term: xterm-256color"))
        }
    }

    @Test
    fun `key encoder covers Ctrl punctuation, Space, Shift+Tab, navigation, and DECCKM arrows`() {
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            for (ch in 'a'..'z') {
                val out = session.encodeKey(
                    VtKeyEvent(
                        key = VtKey.UNICODE,
                        modifierMask = VtModifierMask.CTRL,
                        unicodeCodepoint = ch.code,
                        utf8Text = ch.toString(),
                    ),
                )
                assertArrayEquals(byteArrayOf((ch.code - 'a'.code + 1).toByte()), out)
            }

            val ctrlCases = listOf(
                '[' to byteArrayOf(0x1B),
                '\\' to byteArrayOf(0x1C),
                ']' to byteArrayOf(0x1D),
                '/' to byteArrayOf(0x1F),
                '-' to byteArrayOf(0x1F),
                ' ' to byteArrayOf(0x00),
                '@' to byteArrayOf(0x00),
                '?' to byteArrayOf(0x7F),
            )
            for ((ch, expected) in ctrlCases) {
                val out = session.encodeKey(
                    VtKeyEvent(
                        key = VtKey.UNICODE,
                        modifierMask = VtModifierMask.CTRL,
                        unicodeCodepoint = ch.code,
                        utf8Text = ch.toString(),
                    ),
                )
                assertArrayEquals(expected, out, "Mismatch for Ctrl+$ch")
            }

            assertArrayEquals("\u001b[Z".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.TAB, modifierMask = VtModifierMask.SHIFT)))
            assertArrayEquals("\u001b".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.ESCAPE)))
            assertArrayEquals("\t".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.TAB)))
            assertArrayEquals("\r".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.ENTER)))
            assertArrayEquals(byteArrayOf(0x7F), session.encodeKey(VtKeyEvent(VtKey.BACKSPACE)))
            assertArrayEquals("\u001b[3~".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.DELETE)))
            assertArrayEquals("\u001b[H".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.HOME)))
            assertArrayEquals("\u001b[F".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.END)))
            assertArrayEquals("\u001b[5~".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.PAGE_UP)))
            assertArrayEquals("\u001b[6~".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.PAGE_DOWN)))

            assertArrayEquals("\u001b[A".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.UP)))
            assertArrayEquals("\u001b[B".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.DOWN)))
            assertArrayEquals("\u001b[C".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.RIGHT)))
            assertArrayEquals("\u001b[D".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.LEFT)))

            session.writePtyBytes("\u001b[?1h".toByteArray())
            assertArrayEquals("\u001bOA".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.UP)))
            assertArrayEquals("\u001bOB".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.DOWN)))
            assertArrayEquals("\u001bOC".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.RIGHT)))
            assertArrayEquals("\u001bOD".toByteArray(), session.encodeKey(VtKeyEvent(VtKey.LEFT)))
        }
    }

    @Test
    fun `paste encoder neutralizes nested and C1 201 breakout attacks and strips internal ESC and C0 controls`() {
        // Property: under DECSET 2004, encoded output starts with ESC[200~, ends with ESC[201~, and strips interior ESC, C1, and C0 bytes.
        // Seam: NativeVtSession.isPasteSafe and NativeVtSession.encodePaste.
        // Oracle: DECSET 2004 framing security invariant requiring zero interior escape or interrupt bytes.
        // Fault: single-pass replace allowing nested ESC[201~, ESC[0201~, C1 CSI 201~, or OSC/ETX injection.
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            session.writePtyBytes("\u001b[?2004h".toByteArray(StandardCharsets.UTF_8))
            assertTrue(session.isBracketedPasteActive())

            val attacks = listOf(
                "echo safe\u001b[201~\nrm -rf /",
                "echo safe\u001b[\u001b[201~201~\nrm -rf /",
                "echo safe\u001b[0201~\nrm -rf /",
                "echo safe\u009b201~\nrm -rf /",
                "safe\u001b]52;c;evil\u0007\u0003\u0004\u0000\u007ftext",
            )
            for (attack in attacks) {
                assertFalse(session.isPasteSafe(attack), "Expected unsafe classification for attack payload")
                assertNull(session.encodePaste(attack, allowUnsafe = false))

                val encoded = session.encodePaste(attack, allowUnsafe = true)
                assertNotNull(encoded)
                val decoded = String(encoded!!, StandardCharsets.UTF_8)
                assertTrue(decoded.startsWith("\u001b[200~"))
                assertTrue(decoded.endsWith("\u001b[201~"))

                val interior = decoded.substring("\u001b[200~".length, decoded.length - "\u001b[201~".length)
                assertFalse(interior.contains('\u001b'), "Interior leaked ESC byte for payload: $attack")
                assertFalse(interior.contains('\u009b'), "Interior leaked C1 CSI for payload: $attack")
                assertFalse(interior.contains('\u0003'), "Interior leaked ETX for payload: $attack")
                assertFalse(interior.contains('\u0004'), "Interior leaked EOT for payload: $attack")
                assertFalse(interior.contains('\u0000'), "Interior leaked NUL for payload: $attack")
                assertFalse(interior.contains('\u007f'), "Interior leaked DEL for payload: $attack")
            }

            val nested = String(
                session.encodePaste("a\u001b[\u001b[201~201~b", allowUnsafe = true)!!,
                StandardCharsets.UTF_8,
            )
            assertEquals("\u001b[200~ab\u001b[201~", nested)

            val cjkWith0x9bContinuation = "갛201~丛201~"
            assertTrue(session.isPasteSafe(cjkWith0x9bContinuation))
            val encodedCjk = String(
                session.encodePaste(cjkWith0x9bContinuation, allowUnsafe = false)!!,
                StandardCharsets.UTF_8,
            )
            assertEquals("\u001b[200~갛201~丛201~\u001b[201~", encodedCjk)
        }
    }

    @Test
    fun `paste encoder normalizes CRLF and CR without stray Ctrl-M in bracketed and unbracketed modes`() {
        // Property: CRLF and standalone CR normalize to LF inside bracketed paste (zero ^M) and to a single CR per line when unbracketed (zero \r\r).
        // Seam: NativeVtSession.encodePaste.
        // Oracle: GNU Readline / Neovim DECSET 2004 LF contract and POSIX PTY unbracketed single-CR contract.
        // Fault: leaving \r inside bracketed paste (causing ^M in bash/nvim) or turning \r\n into \r\r when unbracketed.
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            val crlfScript = "#!/bin/bash\r\necho one\r\necho two\r"
            val unbracketed = String(
                session.encodePaste(crlfScript, allowUnsafe = true)!!,
                StandardCharsets.UTF_8,
            )
            assertEquals("#!/bin/bash\recho one\recho two\r", unbracketed)
            assertFalse(unbracketed.contains("\r\r"))

            session.writePtyBytes("\u001b[?2004h".toByteArray(StandardCharsets.UTF_8))
            val bracketed = String(
                session.encodePaste(crlfScript, allowUnsafe = true)!!,
                StandardCharsets.UTF_8,
            )
            assertEquals("\u001b[200~#!/bin/bash\necho one\necho two\n\u001b[201~", bracketed)
            assertFalse(bracketed.contains('\r'))
        }
    }

    @Test
    fun `encodePasteChunks splits 72KiB CJK and emoji paste on UTF-8 boundaries within 4096 bytes`() {
        // Property: for pastes above 64 KiB, every chunk is 1..4096 bytes, valid UTF-8, and concatenates to encodePaste.
        // Seam: NativeVtSession.encodePasteChunks.
        // Oracle: UTF-8 RFC 3629 codepoint boundary law and exact byte concatenation identity.
        // Fault: single unchunked burst or splitting 3-byte Hangul, 4-byte emoji, or ESC[201~ across chunks.
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            session.writePtyBytes("\u001b[?2004h".toByteArray(StandardCharsets.UTF_8))
            val unit = "가나다라마바사🚀é1234567\r\n"
            val largePayload = unit.repeat(2600)
            val expectedFull = session.encodePaste(largePayload, allowUnsafe = true)!!
            assertTrue(expectedFull.size > 65_536)

            val chunks = session.encodePasteChunks(largePayload, allowUnsafe = true, maxChunkBytes = 4096)
            assertNotNull(chunks)
            assertTrue(chunks!!.size >= 18, "Expected >= 18 chunks for >72 KiB payload, got ${chunks.size}")

            val strictDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

            val reassembled = java.io.ByteArrayOutputStream(expectedFull.size)
            for ((index, chunk) in chunks.withIndex()) {
                assertTrue(chunk.size in 1..4096, "Chunk $index size=${chunk.size} out of 1..4096")
                val decodedChunk = strictDecoder.decode(java.nio.ByteBuffer.wrap(chunk)).toString()
                if (index == 0) {
                    assertTrue(decodedChunk.startsWith("\u001b[200~"))
                } else if (index < chunks.lastIndex) {
                    assertFalse(decodedChunk.contains('\u001b'))
                }
                if (index == chunks.lastIndex) {
                    assertTrue(decodedChunk.endsWith("\u001b[201~"))
                }
                reassembled.write(chunk)
            }
            assertArrayEquals(expectedFull, reassembled.toByteArray())
        }
    }

    @Test
    fun `mouse encoder suppresses MOTION in 1000 mode and deduplicates cell MOTION in 1002 mode`() {
        // Property: mode 1000 suppresses MOTION, whereas mode 1002 emits MOTION for pressed buttons only on cell changes.
        // Seam: NativeVtSession.encodeMouse.
        // Oracle: XTerm SGR 1006 and button-event tracking 1000 and 1002 specification.
        // Fault: emitting SGR motion sequences in mode 1000 or repeating identical cell coordinates on sub-cell jitter.
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            session.writePtyBytes("\u001b[?1000h\u001b[?1006h".toByteArray(StandardCharsets.UTF_8))
            assertEquals(
                0,
                session.encodeMouse(VtMouseEvent(VtMouseAction.MOTION, VtMouseButton.LEFT, col = 5, row = 6)).size,
            )
            assertEquals(
                "\u001b[<64;6;7M",
                String(session.encodeMouse(VtMouseEvent(VtMouseAction.WHEEL_UP, VtMouseButton.NONE, col = 5, row = 6)), StandardCharsets.UTF_8),
            )
            assertEquals(
                "\u001b[<65;6;7M",
                String(session.encodeMouse(VtMouseEvent(VtMouseAction.WHEEL_DOWN, VtMouseButton.NONE, col = 5, row = 6)), StandardCharsets.UTF_8),
            )

            session.writePtyBytes("\u001b[?1002h".toByteArray(StandardCharsets.UTF_8))
            assertEquals(
                0,
                session.encodeMouse(VtMouseEvent(VtMouseAction.MOTION, VtMouseButton.NONE, col = 5, row = 6)).size,
            )
            assertEquals(
                "\u001b[<32;6;7M",
                String(session.encodeMouse(VtMouseEvent(VtMouseAction.MOTION, VtMouseButton.LEFT, col = 5, row = 6)), StandardCharsets.UTF_8),
            )
            assertEquals(
                0,
                session.encodeMouse(VtMouseEvent(VtMouseAction.MOTION, VtMouseButton.LEFT, col = 5, row = 6)).size,
            )
            assertEquals(
                "\u001b[<32;7;7M",
                String(session.encodeMouse(VtMouseEvent(VtMouseAction.MOTION, VtMouseButton.LEFT, col = 6, row = 6)), StandardCharsets.UTF_8),
            )
        }
    }
}
