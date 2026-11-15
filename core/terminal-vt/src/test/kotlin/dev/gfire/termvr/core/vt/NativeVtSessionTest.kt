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
}
