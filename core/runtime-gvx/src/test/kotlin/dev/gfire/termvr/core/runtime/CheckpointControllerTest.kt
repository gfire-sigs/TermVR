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
                // Simulate gvx writing checkpoint files inside the requested slot directory.
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val targetDir = File(payloadLine.substring(start, end))
                File(targetDir, "checkpoint.img").writeText("img-$nowMs", StandardCharsets.UTF_8)
                "ok"
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
                "ok"
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
        var replyValue = "ok"
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
        replyValue = "err: busy"
        assertThrows(CheckpointFailedException::class.java) {
            controller.checkpoint(
                profileId = "app",
                controlSocketFile = ctlSock,
                currentPssBytes = 10_000_000L,
                awaitProcessExit = { 0 },
            )
        }
        assertEquals(CheckpointSlotMetadata.SLOT_A, controller.resolveActiveCheckpoint("app")?.metadata?.activeSlot)
        assertFalse(File(tempDir, "checkpoints/app/slot-b").exists())

        // Non-zero exit cleans failed slot-b and keeps slot-a active.
        replyValue = "ok"
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
                "ok"
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
}
