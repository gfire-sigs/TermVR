package dev.gfire.termvr.provider

import android.provider.DocumentsContract.Document
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.CheckpointController
import dev.gfire.termvr.core.runtime.CheckpointSlotMetadata
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TermVrDocumentsProviderTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun hidesGvxMetaAndTermvrTmpEntries() {
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        File(workspace, ".gvx_meta").apply { mkdirs() }
        File(workspace, ".gvx_meta/internal.json").writeText("{}")
        File(workspace, "notes.txt").writeText("hello")
        File(workspace, "notes.txt.termvr.tmp").writeText("partial")
        File(workspace, "src").mkdirs()

        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
        )
        val entries = engine.listChildren("profile:dev-shell")
        val names = entries.map { it.displayName }

        assertEquals(listOf("src", "notes.txt"), names)
        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/.gvx_meta")
        }
        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/notes.txt.termvr.tmp")
        }
    }

    @Test
    fun rejectsDotDotAndSymlinkEscapeOutsideWorkspace() {
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val outside = File(tempDir, "outside-secret.txt").apply { writeText("secret") }
        val outsideDir = File(tempDir, "outside-dir").apply { mkdirs() }
        File(outsideDir, "key.pem").writeText("private")

        val symlinkFile = File(workspace, "escape-link.txt")
        val symlinkDir = File(workspace, "escape-dir")
        Files.createSymbolicLink(symlinkFile.toPath(), outside.toPath())
        Files.createSymbolicLink(symlinkDir.toPath(), outsideDir.toPath())
        File(workspace, "visible.txt").writeText("safe")

        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
        )

        val visibleNames = engine.listChildren("profile:dev-shell").map { it.displayName }
        assertEquals(listOf("visible.txt"), visibleNames)

        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/../outside-secret.txt")
        }
        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/escape-link.txt")
        }
        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/escape-dir/key.pem")
        }
    }

    @Test
    fun enforcesWriteLockForLiveFrozenAndPersistedActiveCheckpoint() {
        val workspace = File(tempDir, "workspaces/frozen-box").apply { mkdirs() }
        val target = File(workspace, "main.py").apply { writeText("print(1)\n") }

        var liveState: SandboxRunState? = SandboxRunState.FROZEN
        val checkpointController = CheckpointController(tempDir)
        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { liveState },
            checkpointController = checkpointController,
        )

        val lockedChildren = engine.listChildren("profile:frozen-box")
        assertEquals(0, lockedChildren.single().flags)
        assertThrows(SecurityException::class.java) {
            engine.beginWriteTransaction("entry:frozen-box/main.py", truncateExisting = true)
        }
        assertThrows(SecurityException::class.java) {
            engine.createEntry("profile:frozen-box", "text/plain", "new.txt")
        }
        assertThrows(SecurityException::class.java) {
            engine.deleteEntry("entry:frozen-box/main.py")
        }

        // Simulate app restart: live state is null/STOPPED, but active-slot.json remains non-superseded.
        liveState = SandboxRunState.STOPPED
        val slotDir = File(checkpointController.profileCheckpointRoot("frozen-box"), CheckpointSlotMetadata.SLOT_A)
        slotDir.mkdirs()
        File(slotDir, "pages.img").writeText("ckpt")
        val metaFile = checkpointController.activeSlotFile("frozen-box")
        metaFile.parentFile?.mkdirs()
        metaFile.writeText(
            """
            {
              "schemaVersion": 1,
              "activeSlot": "${CheckpointSlotMetadata.SLOT_A}",
              "createdAtEpochMillis": 1700000000000,
              "gvxVersion": "${CheckpointController.DEFAULT_GVX_VERSION}",
              "superseded": false
            }
            """.trimIndent(),
        )

        assertTrue(engine.isProfileWriteLocked("frozen-box"))
        val persistedLockedEntry = engine.statDocument("entry:frozen-box/main.py")
        assertEquals(0, persistedLockedEntry.flags)
        assertThrows(SecurityException::class.java) {
            engine.renameEntry("entry:frozen-box/main.py", "renamed.py")
        }

        // Discarding the checkpoint unlocks mutations and restores SAF flags.
        checkpointController.discardCheckpoint("frozen-box")
        assertFalse(engine.isProfileWriteLocked("frozen-box"))
        val unlockedEntry = engine.statDocument("entry:frozen-box/main.py")
        assertTrue((unlockedEntry.flags and Document.FLAG_SUPPORTS_WRITE) != 0)
        assertTrue(target.exists())
    }

    @Test
    fun performsAtomicTempSwapOnCleanCloseAndCleansUpOnError() {
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val target = File(workspace, "config.yaml").apply { writeText("original: true\n") }
        val fsyncCalls = AtomicInteger(0)

        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
            fsyncFile = {
                fsyncCalls.incrementAndGet()
            },
        )

        // Failed / aborted write must delete staging file and keep original intact.
        val abortedTx = engine.beginWriteTransaction("entry:dev-shell/config.yaml", truncateExisting = true)
        assertEquals("config.yaml.termvr.tmp", abortedTx.stagingFile.name)
        abortedTx.stagingFile.writeText("corrupted-incomplete")
        engine.finishWriteTransaction(abortedTx, errorOccurred = true)

        assertFalse(abortedTx.stagingFile.exists())
        assertEquals("original: true\n", target.readText())
        assertEquals(0, fsyncCalls.get())

        // Clean write must fsync staging file and atomically replace target.
        val commitTx = engine.beginWriteTransaction("entry:dev-shell/config.yaml", truncateExisting = true)
        commitTx.stagingFile.writeText("updated: true\n")
        engine.finishWriteTransaction(commitTx, errorOccurred = false)

        assertFalse(commitTx.stagingFile.exists())
        assertEquals("updated: true\n", target.readText())
        assertEquals(1, fsyncCalls.get())
    }

    @Test
    fun rejectsUnknownDocumentIds() {
        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { null },
        )
        assertThrows(FileNotFoundException::class.java) {
            engine.resolveDocumentId("invalid:doc")
        }
    }
}
