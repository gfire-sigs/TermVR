package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.PortForwardRule
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CheckpointControllerTest {

    @Test
    fun `checkpoint rotates dual slots atomically and deletes old slot only after metadata swap`(
        @TempDir tempDir: File,
    ) {
        val ctlSock = File(tempDir, "ctl/debian.sock").apply { parentFile?.mkdirs() }
        val sentCommands = ArrayList<String>()
        var nowMs = 1_000_000L

        val controller = CheckpointController(
            filesDir = tempDir,
            gvxVersion = "v0.15.10002026110900",
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { socketFile, payloadLine, connectWriteTimeoutMs, readTimeoutMs ->
                assertEquals(ctlSock, socketFile)
                assertEquals(CheckpointController.CONNECT_WRITE_TIMEOUT_MS, connectWriteTimeoutMs)
                assertEquals(CheckpointController.CHECKPOINT_READ_TIMEOUT_MS, readTimeoutMs)
                sentCommands.add(payloadLine)
                assertTrue(payloadLine.contains("\"leave_running\":false"))
                assertFalse(payloadLine.contains("\"op\""))
                // Simulate gvx writing checkpoint files inside the requested slot directory.
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val targetDir = File(payloadLine.substring(start, end))
                File(targetDir, "checkpoint.img").writeText("img-$nowMs", StandardCharsets.UTF_8)
                """{"ok":true}"""
            },
            clockMillis = { nowMs },
        )

        val ports = listOf(
            PortForwardRule(
                id = "web",
                protocol = ForwardProtocol.TCP,
                listenHost = ListenHost.LOOPBACK_V4,
                hostPortStart = 8080,
                hostPortEnd = 8080,
                guestPortStart = 80,
                guestPortEnd = 80,
            ),
        )

        // First checkpoint writes into slot-a.
        val meta1 = controller.checkpoint(
            profileId = "debian",
            controlSocketFile = ctlSock,
            currentPssBytes = 64L * 1024L * 1024L,
            resolvedPorts = ports,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, meta1.activeSlot)
        assertEquals(1, sentCommands.size)
        val slotA = File(tempDir, "checkpoints/debian/slot-a")
        val slotB = File(tempDir, "checkpoints/debian/slot-b")
        assertTrue(File(slotA, "checkpoint.img").isFile)
        assertFalse(slotB.exists())

        val active1 = controller.resolveActiveCheckpoint("debian")
        assertNotNull(active1)
        assertEquals(slotA, active1?.slotDir)
        assertEquals(ports, active1?.metadata?.resolvedPorts)

        // Second checkpoint writes into slot-b and deletes slot-a after metadata swap.
        nowMs = 2_000_000L
        val meta2 = controller.checkpoint(
            profileId = "debian",
            controlSocketFile = ctlSock,
            currentPssBytes = 64L * 1024L * 1024L,
            resolvedPorts = ports,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_B, meta2.activeSlot)
        assertFalse(slotA.exists())
        assertTrue(File(slotB, "checkpoint.img").isFile)

        // Third checkpoint rotates back to slot-a and deletes slot-b.
        nowMs = 3_000_000L
        val meta3 = controller.checkpoint(
            profileId = "debian",
            controlSocketFile = ctlSock,
            currentPssBytes = 64L * 1024L * 1024L,
            resolvedPorts = ports,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, meta3.activeSlot)
        assertTrue(File(slotA, "checkpoint.img").isFile)
        assertFalse(slotB.exists())
    }

    @Test
    fun `checkpoint rejects insufficient disk space at 1_2x PSS boundary and keeps active slot`(
        @TempDir tempDir: File,
    ) {
        val pssBytes = 100_000_000L
        val exactRequiredBytes = 120_000_000L
        var availableBytes = exactRequiredBytes // Condition requires strictly greater than requiredBytes.
        val ctlSock = File(tempDir, "ctl/work.sock").apply { parentFile?.mkdirs() }

        val controller = CheckpointController(
            filesDir = tempDir,
            freeSpaceProvider = { availableBytes },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "state.bin").writeText("ok")
                """{"ok":true}"""
            },
        )

        val ex = assertThrows(InsufficientCheckpointSpaceException::class.java) {
            controller.checkpoint(
                profileId = "work",
                controlSocketFile = ctlSock,
                currentPssBytes = pssBytes,
                awaitProcessExit = { 0 },
            )
        }
        assertEquals(exactRequiredBytes, ex.requiredBytes)
        assertEquals(exactRequiredBytes, ex.availableBytes)
        assertFalse(controller.activeSlotFile("work").exists())

        // Raising available bytes above 1.2x permits checkpoint creation.
        availableBytes = exactRequiredBytes + 1L
        val meta = controller.checkpoint(
            profileId = "work",
            controlSocketFile = ctlSock,
            currentPssBytes = pssBytes,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, meta.activeSlot)
    }

    @Test
    fun `checkpoint preserves previous slot when gvx replies error or exits non-zero`(
        @TempDir tempDir: File,
    ) {
        val ctlSock = File(tempDir, "ctl/app.sock").apply { parentFile?.mkdirs() }
        var replyValue = """{"ok":true}"""
        var exitValue = 0

        val controller = CheckpointController(
            filesDir = tempDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "img").writeText("data")
                replyValue
            },
        )

        val first = controller.checkpoint(
            profileId = "app",
            controlSocketFile = ctlSock,
            currentPssBytes = 10_000_000L,
            awaitProcessExit = { exitValue },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, first.activeSlot)

        // Error reply cleans failed slot-b and keeps slot-a active.
        replyValue = """{"ok":false,"error":"busy"}"""
        val rejected = assertThrows(CheckpointFailedException::class.java) {
            controller.checkpoint(
                profileId = "app",
                controlSocketFile = ctlSock,
                currentPssBytes = 10_000_000L,
                awaitProcessExit = { 0 },
            )
        }
        assertTrue(rejected.message.orEmpty().contains("busy"))
        assertEquals(CheckpointSlotMetadata.SLOT_A, controller.resolveActiveCheckpoint("app")?.metadata?.activeSlot)
        assertFalse(File(tempDir, "checkpoints/app/slot-b").exists())

        // Non-JSON reply is rejected cleanly.
        replyValue = "not-json"
        assertThrows(CheckpointFailedException::class.java) {
            controller.checkpoint(
                profileId = "app",
                controlSocketFile = ctlSock,
                currentPssBytes = 10_000_000L,
                awaitProcessExit = { 0 },
            )
        }
        assertEquals(CheckpointSlotMetadata.SLOT_A, controller.resolveActiveCheckpoint("app")?.metadata?.activeSlot)

        // Non-zero exit cleans failed slot-b and keeps slot-a active.
        replyValue = """{"ok":true}"""
        exitValue = 2
        assertThrows(CheckpointFailedException::class.java) {
            controller.checkpoint(
                profileId = "app",
                controlSocketFile = ctlSock,
                currentPssBytes = 10_000_000L,
                awaitProcessExit = { exitValue },
            )
        }
        assertEquals(CheckpointSlotMetadata.SLOT_A, controller.resolveActiveCheckpoint("app")?.metadata?.activeSlot)
        assertFalse(File(tempDir, "checkpoints/app/slot-b").exists())
    }

    @Test
    fun `gvx upgrade marks checkpoint superseded without deleting volumes and supports discard`(
        @TempDir tempDir: File,
    ) {
        val ctlSock = File(tempDir, "ctl/upg.sock").apply { parentFile?.mkdirs() }
        val volumeMarker = File(tempDir, "gvx-store/gvx/volumes/work/keep.txt").apply {
            parentFile?.mkdirs()
            writeText("important")
        }

        val v1Controller = CheckpointController(
            filesDir = tempDir,
            gvxVersion = "v0.15.10002026110900",
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "img").writeText("v1")
                """{"ok":true}"""
            },
        )
        v1Controller.checkpoint(
            profileId = "upg",
            controlSocketFile = ctlSock,
            currentPssBytes = 16L * 1024L * 1024L,
            awaitProcessExit = { 0 },
        )

        val v2Controller = CheckpointController(
            filesDir = tempDir,
            gvxVersion = "v0.16.0",
            freeSpaceProvider = { 512L * 1024L * 1024L },
        )
        assertNull(v2Controller.resolveActiveCheckpoint("upg"))
        val meta = v2Controller.readMetadata("upg")
        assertNotNull(meta)
        assertTrue(meta?.superseded == true)
        assertTrue(meta?.supersededReason.orEmpty().contains("v0.16.0"))
        assertTrue(volumeMarker.isFile)

        v2Controller.discardCheckpoint("upg")
        assertNull(v2Controller.readMetadata("upg"))
        assertFalse(File(tempDir, "checkpoints/upg/slot-a").exists())
        assertTrue(volumeMarker.isFile)
    }

    @Test
    fun `preopened checkpoint slot avoids unlinking directory and supports repeated restore checkpoints`(
        @TempDir tempDir: File,
    ) {
        val ctlSock = File(tempDir, "ctl/preopen.sock").apply { parentFile?.mkdirs() }
        var reply = """{"ok":true}"""
        val seenDirs = ArrayList<String>()

        val controller = CheckpointController(
            filesDir = tempDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val dirPath = payloadLine.substring(start, end)
                seenDirs.add(dirPath)
                if (reply.contains("\"ok\":true")) {
                    File(dirPath, "state.img").writeText("state-${seenDirs.size}")
                    File(dirPath, "manifest.json").writeText("{}")
                }
                reply
            },
        )

        // 1. Cold start prepares slot-a before launch, rungv pre-opens files inside slot-a.
        val slotA = controller.prepareCheckpointSlotDir("preopen")
        assertEquals(CheckpointSlotMetadata.SLOT_A, slotA.name)
        val preopenedMarker = File(slotA, "pages.img").apply { writeText("preopened-fd") }

        val firstMeta = controller.checkpoint(
            profileId = "preopen",
            controlSocketFile = ctlSock,
            currentPssBytes = 10_000_000L,
            preopenedSlotDir = slotA,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, firstMeta.activeSlot)
        // Must NOT have deleted/recreated slot-a after rungv pre-opened files.
        assertEquals("preopened-fd", preopenedMarker.readText())
        assertEquals(slotA.absolutePath, seenDirs.single())

        // 2. Resume uses slot-a as its preopened checkpoint dir; a failed second freeze preserves slot-a.
        val activeBeforeSecondFreeze = controller.resolveActiveCheckpoint("preopen")!!
        assertEquals(slotA, activeBeforeSecondFreeze.slotDir)

        reply = """{"ok":false,"error":"control socket timeout"}"""
        assertThrows(CheckpointFailedException::class.java) {
            controller.checkpoint(
                profileId = "preopen",
                controlSocketFile = ctlSock,
                currentPssBytes = 10_000_000L,
                preopenedSlotDir = activeBeforeSecondFreeze.slotDir,
                awaitProcessExit = { 0 },
            )
        }
        assertTrue(File(slotA, "state.img").isFile)
        assertNotNull(controller.resolveActiveCheckpoint("preopen"))

        // 3. Second freeze into preopened slot-a succeeds and keeps slot-a alive on disk!
        reply = """{"ok":true}"""
        val secondMeta = controller.checkpoint(
            profileId = "preopen",
            controlSocketFile = ctlSock,
            currentPssBytes = 10_000_000L,
            preopenedSlotDir = activeBeforeSecondFreeze.slotDir,
            awaitProcessExit = { 0 },
        )
        assertEquals(CheckpointSlotMetadata.SLOT_A, secondMeta.activeSlot)
        assertTrue(File(slotA, "state.img").isFile)
        assertNotNull(controller.resolveActiveCheckpoint("preopen"))

        // 4. Subsequent cold start after superseded/new run prepares the opposite slot-b.
        val slotB = controller.prepareCheckpointSlotDir("preopen")
        assertEquals(CheckpointSlotMetadata.SLOT_B, slotB.name)
    }

    @Test
    fun `AndroidLocalSocketTransport rejects Unix socket paths longer than 107 bytes`() {
        val longPath = File("/tmp/" + "a".repeat(120) + ".sock")
        val ex = assertThrows(java.io.IOException::class.java) {
            AndroidLocalSocketTransport.sendCommand(longPath, "{}", 2000, 2000)
        }
        assertTrue(ex.message.orEmpty().contains("Unix domain limit"))
    }
}
