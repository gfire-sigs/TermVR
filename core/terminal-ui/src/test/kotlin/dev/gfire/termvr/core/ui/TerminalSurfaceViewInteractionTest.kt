package dev.gfire.termvr.core.ui

import android.view.KeyEvent
import dev.gfire.termvr.core.model.ModifierKey
import dev.gfire.termvr.core.vt.CellMetrics
import dev.gfire.termvr.core.vt.NativeVtSession
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class TerminalSurfaceViewInteractionTest {

    private class FakeDelayedScheduler {
        data class Task(val runnable: Runnable, val runAtMs: Long)

        var nowMs: Long = 0L
            private set
        private val queue = mutableListOf<Task>()

        fun schedule(runnable: Runnable, delayMs: Long) {
            queue.add(Task(runnable, nowMs + delayMs))
        }

        fun cancel(runnable: Runnable) {
            queue.removeAll { it.runnable === runnable }
        }

        fun pendingCount(): Int = queue.size

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
            while (true) {
                val due = queue.filter { it.runAtMs <= nowMs }.minByOrNull { it.runAtMs } ?: break
                queue.remove(due)
                due.runnable.run()
            }
        }
    }

    @Test
    fun `resize debouncer applies initial pane size immediately and coalesces 120Hz IME resize storm into 1 SIGWINCH`() {
        // Property: first layout dispatches resize immediately at 0ms. Rapid updates within 48ms emit one trailing SIGWINCH.
        // Seam: PaneResizeDebouncer.onLayoutComputed, flushPending, and onSessionClosed.
        // Oracle: 0ms initial sizing rule and 48ms trailing debounce coalescence rule.
        // Fault: emitting 20 SIGWINCH signals during keyboard open animation or delaying initial shell dimensions.
        val scheduler = FakeDelayedScheduler()
        val resizedEvents = mutableListOf<Triple<String, Int, Int>>()
        var afterResizeCount = 0

        val debouncer = PaneResizeDebouncer(
            debounceDelayMs = 48L,
            scheduleDelayed = scheduler::schedule,
            cancelScheduled = scheduler::cancel,
            onGridResized = { paneId, cols, rows, _, _ ->
                resizedEvents += Triple(paneId, cols, rows)
            },
            onAfterGridResized = { afterResizeCount++ },
        )

        val metrics = CellMetrics(cellWidthPx = 10, cellHeightPx = 20, baselinePx = 16)
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            fun layoutWithHeight(heightPx: Int): SplitLayoutResult =
                SplitPaneLayoutCalculator.compute(
                    root = TerminalSplitNode.Leaf("main", session),
                    focusedPaneId = "main",
                    surfaceWidthPx = 1000,
                    surfaceHeightPx = heightPx,
                    dividerThicknessPx = 2,
                )

            // 1. Initial attach at 1000x840 (100 cols by 42 rows) dispatches immediately with zero delay.
            debouncer.onLayoutComputed(layoutWithHeight(840), metrics)
            assertEquals(listOf(Triple("main", 100, 42)), resizedEvents)
            assertEquals(100, session.cols)
            assertEquals(42, session.rows)
            assertEquals(0, scheduler.pendingCount())
            assertEquals(1, afterResizeCount)

            // 2. Sub-cell pixel jitter from 840px to 855px stays at 42 rows and emits zero SIGWINCH signals.
            resizedEvents.clear()
            debouncer.onLayoutComputed(layoutWithHeight(855), metrics)
            scheduler.advanceBy(100L)
            assertTrue(resizedEvents.isEmpty())
            assertEquals(0, scheduler.pendingCount())

            // 3. Simulate 20 frames of 120Hz (8ms per frame) soft-keyboard animation shrinking height to 480px.
            val heights = (0 until 20).map { frame -> 820 - frame * 18 }
            for (h in heights) {
                debouncer.onLayoutComputed(layoutWithHeight(h), metrics)
                scheduler.advanceBy(8L)
                // Active animation frames spaced by 8ms (< 48ms) must emit zero intermediate SIGWINCH signals.
                assertTrue(resizedEvents.isEmpty(), "Premature SIGWINCH during 120Hz animation at height=$h")
            }
            debouncer.onLayoutComputed(layoutWithHeight(480), metrics)
            scheduler.advanceBy(47L)
            assertTrue(resizedEvents.isEmpty(), "Expected trailing debounce to hold at 47ms < 48ms")

            scheduler.advanceBy(1L)
            assertEquals(listOf(Triple("main", 100, 24)), resizedEvents)
            assertEquals(24, session.rows)
            assertEquals(0, scheduler.pendingCount())

            // 4. Pending resize flushes immediately on detach or destroy and cancels the scheduled timer.
            resizedEvents.clear()
            debouncer.onLayoutComputed(layoutWithHeight(600), metrics)
            assertEquals(1, scheduler.pendingCount())
            assertTrue(resizedEvents.isEmpty())

            debouncer.flushPending()
            assertEquals(listOf(Triple("main", 100, 30)), resizedEvents)
            assertEquals(0, scheduler.pendingCount())
            scheduler.advanceBy(100L)
            assertEquals(1, resizedEvents.size)
        }
    }

    @Test
    fun `mouse tracking routes single tap quantized wheel scroll and horizontal drag while preserving long-press selection escape hatch`() {
        // Property: SGR 1006 mode routes taps, quantized wheel steps, and drags while long-press enters local selection.
        // Seam: TerminalTouchGestureRouter.
        // Oracle: XTerm SGR 1006 protocol and Design 0001 Section 14.2 mobile selection escape hatch.
        // Fault: raw-pixel wheel floods, missing drag release, blocked text copy in tmux, or dropped sub-cell handle drags.
        val metrics = CellMetrics(cellWidthPx = 10, cellHeightPx = 20, baselinePx = 16)
        val latch = ModifierLatchMachine(clockMs = { 0L })
        val ptySent = mutableListOf<String>()
        val selectionUpdates = mutableListOf<TerminalSelectionUiState>()
        var focusedId = "main"

        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            session.writePtyBytes(("hello tmux copy world\r\nsecond line here\u001b[?1002h\u001b[?1006h").toByteArray(StandardCharsets.UTF_8))
            assertTrue(session.isMouseTrackingActive())

            val layout = SplitPaneLayoutCalculator.compute(
                root = TerminalSplitNode.Leaf("main", session),
                focusedPaneId = focusedId,
                surfaceWidthPx = 800,
                surfaceHeightPx = 480,
                dividerThicknessPx = 2,
            )

            val router = TerminalTouchGestureRouter(
                layoutProvider = { layout },
                metricsProvider = { metrics },
                focusedPaneIdProvider = { focusedId },
                modifierLatchProvider = { latch },
                onFocusPane = { focusedId = it },
                onSendPtyBytes = { _, bytes -> ptySent += String(bytes, StandardCharsets.UTF_8) },
                onSelectionChanged = { selectionUpdates += it },
            )

            // 1. Single tap at (45px, 65px) maps to col 4, row 3 (1-based 5, 4) and emits SGR press and release.
            router.onDown(45f, 65f)
            assertTrue(router.onSingleTapConfirmed(45f, 65f))
            assertEquals(listOf("\u001b[<0;5;4M", "\u001b[<0;5;4m"), ptySent)

            // 2. Vertical scroll quantizes wheel steps by cellHeightPx (20px) instead of emitting per raw pixel.
            ptySent.clear()
            router.onDown(45f, 100f)
            // Three 6px frames total 18px (< 20px) and emit zero wheel events.
            router.onScroll(45f, 100f, 45f, 94f, 0f, 6f)
            router.onScroll(45f, 100f, 45f, 88f, 0f, 6f)
            router.onScroll(45f, 100f, 45f, 82f, 0f, 6f)
            assertTrue(ptySent.isEmpty(), "Expected 0 wheel events before reaching 20px cellHeight threshold")

            // Adding 7px reaches 25px and emits one WHEEL_DOWN at col 4, row 3 with 5px remainder.
            router.onScroll(45f, 100f, 45f, 75f, 0f, 7f)
            assertEquals(listOf("\u001b[<65;5;4M"), ptySent)

            // Reversing downward by 45px moves accumulator from +5px to -40px and emits two WHEEL_UP steps.
            ptySent.clear()
            router.onScroll(45f, 100f, 45f, 120f, 0f, -45f)
            assertEquals(listOf("\u001b[<64;5;7M", "\u001b[<64;5;7M"), ptySent)
            router.onPointerUpOrCancel(45f, 120f)

            // 3. Horizontal 1-finger drag emits PRESS(LEFT), cell-deduplicated MOTION(LEFT), and RELEASE(LEFT).
            ptySent.clear()
            router.onDown(25f, 45f)
            router.onScroll(25f, 45f, 35f, 45f, -10f, 0f)
            // Sub-cell horizontal move inside col 3, row 2 emits zero duplicate MOTION sequences.
            router.onScroll(25f, 45f, 38f, 45f, -3f, 0f)
            // Moving into col 4, row 2 emits the next MOTION sequence.
            router.onScroll(25f, 45f, 45f, 45f, -7f, 0f)
            router.onPointerUpOrCancel(45f, 45f)
            assertEquals(
                listOf(
                    "\u001b[<0;3;3M",
                    "\u001b[<32;4;3M",
                    "\u001b[<32;5;3M",
                    "\u001b[<0;5;3m",
                ),
                ptySent,
            )

            // 4. Mobile selection escape hatch: long-press enters local text selection even during active mouse tracking.
            ptySent.clear()
            selectionUpdates.clear()
            router.onDown(65f, 10f)
            router.onLongPress(65f, 10f)
            assertTrue(router.isLongPressSelecting)
            assertTrue(router.selectionState.active)
            assertEquals(0, router.selectionState.startRow)
            assertEquals(6, router.selectionState.startCol)

            // Dragging during long-press extends local selection to col 9 ("tmux") with zero mouse events sent.
            router.onPointerMove(95f, 10f)
            router.onScroll(65f, 10f, 95f, 10f, -30f, 0f)
            router.onPointerUpOrCancel(95f, 10f)

            assertTrue(ptySent.isEmpty(), "Long-press selection must never leak mouse events to TUI")
            assertTrue(router.selectionState.active, "Selection must stay active after finger up so handles and Copy chip work")
            assertEquals("tmux", session.formatSelectionText())

            // 5. Dragging the end handle across 20 frames of 2.5px (+50px = +5 cols) accumulates sub-cell deltas.
            repeat(20) {
                router.adjustSelectionHandle(isStart = false, dxPx = 2.5f, dyPx = 0f)
            }
            assertEquals(14, router.selectionState.endCol)
            assertEquals("tmux copy", session.formatSelectionText())

            // 6. Tapping while selection is active clears selection without sending mouse clicks to the TUI.
            router.onDown(20f, 20f)
            router.onSingleTapConfirmed(20f, 20f)
            assertFalse(router.selectionState.active)
            assertTrue(ptySent.isEmpty())

            // 7. SHIFT override enables local scrollback fling even when mouse tracking is active.
            latch.onFingerDown(ModifierKey.SHIFT)
            assertTrue(router.canFling(45f, 100f, KeyEvent.META_SHIFT_ON))
            latch.resetAll()

            // 8. Total displacement from (downX, downY) classifies vertical wheel even if single-frame deltaX jitters larger.
            ptySent.clear()
            router.onDown(100f, 100f)
            // Overall displacement from (100, 100) to (103, 75) is dx=+3, dy=-25 (vertical), while frame delta has abs(dx)=4 > abs(dy)=2.
            router.onScroll(downX = 100f, downY = 100f, currentX = 103f, currentY = 75f, distanceX = -4f, distanceY = 2f)
            router.onScroll(downX = 100f, downY = 100f, currentX = 103f, currentY = 55f, distanceX = 0f, distanceY = 20f)
            assertEquals(listOf("\u001b[<65;11;3M"), ptySent)
            router.onPointerUpOrCancel(103f, 55f)

            // 9. Multi-touch pinch suppresses SGR mouse wheel and releases any active drag immediately.
            ptySent.clear()
            router.onDown(25f, 45f)
            router.onScroll(25f, 45f, 35f, 45f, -10f, 0f)
            assertEquals(listOf("\u001b[<0;3;3M", "\u001b[<32;4;3M"), ptySent)
            ptySent.clear()

            router.onMultiTouchDetected(35f, 45f)
            assertEquals(listOf("\u001b[<0;4;3m"), ptySent)
            ptySent.clear()
            assertFalse(router.onScroll(25f, 45f, 35f, 15f, 0f, 30f, pointerCount = 2))
            assertTrue(ptySent.isEmpty())
            router.onPointerUpOrCancel(35f, 15f)

            // 10. During long-press selection (even with mouse tracking disabled), canFling stays false before onPointerUpOrCancel.
            session.writePtyBytes("\u001b[?1002l\u001b[?1006l".toByteArray(StandardCharsets.UTF_8))
            assertFalse(session.isMouseTrackingActive())
            router.onDown(65f, 10f)
            router.onLongPress(65f, 10f)
            router.onScroll(65f, 10f, 95f, 30f, -30f, -20f)
            assertFalse(router.canFling(65f, 10f, 0), "Long-press selection must block fling on finger lift")
            router.onPointerUpOrCancel(95f, 30f)
        }
    }

    @Test
    fun `paste dispatcher gates unsafe clipboard paste neutralizes 201 breakout and chunks 64KiB+ payloads`() {
        // Property: unsafe clipboard paste prompts first, while confirmed paste strips ESC[201~ and chunks at 4096 bytes.
        // Seam: TerminalPasteDispatcher.dispatchClipboardPaste.
        // Oracle: DECSET 2004 framing security invariant and 4096-byte UTF-8 chunking contract.
        // Fault: bypassing unsafe prompt or sending a single unchunked payload larger than 64 KiB.
        NativeVtSession(initialCols = 80, initialRows = 24).use { session ->
            session.writePtyBytes("\u001b[?2004h".toByteArray(StandardCharsets.UTF_8))

            val prompts = mutableListOf<Pair<String, String>>()
            val sentChunks = mutableListOf<ByteArray>()

            val malicious = "echo hello\r\n\u001b[201~rm -rf /\r\n" + "한글로그🚀".repeat(6000)
            val dispatchedUnsafe = TerminalPasteDispatcher.dispatchClipboardPaste(
                paneId = "main",
                session = session,
                text = malicious,
                allowUnsafe = false,
                maxChunkBytes = 4096,
                onUnsafePastePrompt = { p, t -> prompts += p to t },
                onSendPtyBytes = { _, bytes -> sentChunks += bytes },
            )
            assertFalse(dispatchedUnsafe)
            assertEquals(1, prompts.size)
            assertTrue(sentChunks.isEmpty())

            val dispatchedConfirmed = TerminalPasteDispatcher.dispatchClipboardPaste(
                paneId = "main",
                session = session,
                text = malicious,
                allowUnsafe = true,
                maxChunkBytes = 4096,
                onUnsafePastePrompt = { p, t -> prompts += p to t },
                onSendPtyBytes = { _, bytes -> sentChunks += bytes },
            )
            assertTrue(dispatchedConfirmed)
            assertTrue(sentChunks.size > 15)

            val strictDecoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val combined = StringBuilder()
            for (chunk in sentChunks) {
                assertTrue(chunk.size in 1..4096)
                combined.append(strictDecoder.decode(java.nio.ByteBuffer.wrap(chunk)))
            }
            val full = combined.toString()
            assertTrue(full.startsWith("\u001b[200~echo hello\nrm -rf /\n"))
            assertTrue(full.endsWith("\u001b[201~"))
            val interior = full.substring("\u001b[200~".length, full.length - "\u001b[201~".length)
            assertFalse(interior.contains('\u001b'))
            assertFalse(interior.contains('\r'))
        }
    }
}
