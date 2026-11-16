package dev.gfire.termvr.provider

import android.provider.DocumentsContract.Document
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.CheckpointController
import dev.gfire.termvr.core.runtime.CheckpointSlotMetadata
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    @Test
    fun preservesExecutablePosixModeAndLeavesGvxMetaIntactAcrossExternalSaves() {
        // Property: External editor saves (truncate and non-truncate) preserve existing POSIX executable
        // permissions on the workspace file and never mutate or delete the .gvx_meta journal file.
        // Seam: WorkspaceDocumentsEngine.beginWriteTransaction + finishWriteTransaction.
        // Fault: Creating a fresh .termvr.tmp file drops 0755 executable bits to 0644 on atomic move.
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val gvxMetaBytes = "GVXMETA\u0003frame-0755-build-sh".encodeToByteArray()
        val gvxMeta = File(workspace, ".gvx_meta").apply { writeBytes(gvxMetaBytes) }
        val script = File(workspace, "build.sh").apply { writeText("#!/bin/sh\necho v1\n") }
        val posix755 = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        Files.setPosixFilePermissions(script.toPath(), posix755)

        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
        )

        val truncateTx = engine.beginWriteTransaction("entry:dev-shell/build.sh", truncateExisting = true)
        truncateTx.stagingFile.writeText("#!/bin/sh\necho v2\n")
        engine.finishWriteTransaction(truncateTx, errorOccurred = false)

        assertEquals("#!/bin/sh\necho v2\n", script.readText())
        assertEquals(posix755, Files.getPosixFilePermissions(script.toPath()))
        assertTrue(script.canExecute())
        assertArrayEquals(gvxMetaBytes, gvxMeta.readBytes())

        val editTx = engine.beginWriteTransaction("entry:dev-shell/build.sh", truncateExisting = false)
        assertEquals("#!/bin/sh\necho v2\n", editTx.stagingFile.readText())
        editTx.stagingFile.writeText("#!/bin/sh\necho v3\n")
        engine.finishWriteTransaction(editTx, errorOccurred = false)

        assertEquals("#!/bin/sh\necho v3\n", script.readText())
        assertEquals(posix755, Files.getPosixFilePermissions(script.toPath()))
        assertArrayEquals(gvxMetaBytes, gvxMeta.readBytes())
    }

    @Test
    fun concurrentReadsSeeCompleteOldFileDuringStagingAndConcurrentWritersDoNotClobberEachOther() {
        // Property: Concurrent readers always observe the complete prior file while staging is uncommitted,
        // and overlapping write transactions isolate their .termvr.tmp files without clobbering each other.
        // Seam: WorkspaceDocumentsEngine.beginWriteTransaction, statDocument, resolveDocumentId, listChildren.
        // Fault: Second writer truncating or deleting the first writer's fixed <name>.termvr.tmp file.
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val target = File(workspace, "app.conf").apply { writeText("version=1\ncomplete=true\n") }

        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
        )

        val tx1 = engine.beginWriteTransaction("entry:dev-shell/app.conf", truncateExisting = true)
        tx1.stagingFile.writeText("ver")

        // Concurrent read while tx1 is half-written sees old complete file and zero .termvr.tmp entries.
        val statDuringWrite = engine.statDocument("entry:dev-shell/app.conf")
        assertEquals("version=1\ncomplete=true\n".length.toLong(), statDuringWrite.sizeBytes)
        val resolvedDuringWrite = engine.resolveDocumentId("entry:dev-shell/app.conf")
            as WorkspaceDocumentsEngine.ResolvedDocument.ProfileEntry
        assertEquals("version=1\ncomplete=true\n", resolvedDuringWrite.targetFile.readText())
        assertEquals(listOf("app.conf"), engine.listChildren("profile:dev-shell").map { it.displayName })

        // Second overlapping writer gets an isolated .termvr.tmp file.
        val tx2 = engine.beginWriteTransaction("entry:dev-shell/app.conf", truncateExisting = true)
        assertNotEquals(tx1.stagingFile.absolutePath, tx2.stagingFile.absolutePath)
        assertTrue(tx2.stagingFile.name.endsWith(".termvr.tmp"))
        assertEquals("ver", tx1.stagingFile.readText())
        tx2.stagingFile.writeText("version=2\ncomplete=true\n")

        // Aborting tx1 removes only tx1.stagingFile and leaves tx2 intact.
        engine.finishWriteTransaction(tx1, errorOccurred = true)
        assertFalse(tx1.stagingFile.exists())
        assertTrue(tx2.stagingFile.exists())
        assertEquals("version=1\ncomplete=true\n", target.readText())

        // Committing tx2 atomically replaces target and leaves zero staging files.
        engine.finishWriteTransaction(tx2, errorOccurred = false)
        assertFalse(tx2.stagingFile.exists())
        assertEquals("version=2\ncomplete=true\n", target.readText())
        assertTrue(workspace.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })
    }

    @Test
    fun protectsGvxMetaVariantsRejectsMidEditFreezeAndDoesNotFollowSymlinksOnRecursiveDelete() {
        // Property: .gvx_meta and .gvx_meta.tmp (any case) are hidden and immutable, mid-edit freeze
        // rejects commit with SecurityException while cleaning staging, fsync IOException cleans staging,
        // and recursive directory delete unlinks symlinks without deleting outside symlink targets.
        // Seam: WorkspaceDocumentsEngine CRUD + write transaction commit.
        // Fault: Following symlinked directories in deleteRecursively(), silently swallowing mid-edit locks,
        // or allowing case-variant ".GVX_META" / ".gvx_meta.tmp" mutation.
        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val metaJournal = File(workspace, ".gvx_meta").apply { writeText("journal-v3") }
        val metaCompaction = File(workspace, ".gvx_meta.tmp").apply { writeText("compaction-v3") }
        val notes = File(workspace, "notes.md").apply { writeText("safe-v1") }

        val outsideKeepDir = File(tempDir, "outside-keep").apply { mkdirs() }
        val outsideKeepFile = File(outsideKeepDir, "precious.txt").apply { writeText("do-not-delete") }
        val subDir = File(workspace, "nested").apply { mkdirs() }
        File(subDir, "local.txt").writeText("delete-me")
        Files.createSymbolicLink(File(subDir, "escape-sub").toPath(), outsideKeepDir.toPath())
        Files.createSymbolicLink(File(workspace, "dangling-escape").toPath(), Paths.get("../missing-outside.txt"))

        var currentState = SandboxRunState.RUNNING
        var failNextFsync = false
        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { currentState },
            fsyncFile = {
                if (failNextFsync) {
                    throw IOException("Simulated ENOSPC during fsync")
                }
            },
        )

        // Hidden metadata and outside symlinks never appear in listing.
        assertEquals(listOf("nested", "notes.md"), engine.listChildren("profile:dev-shell").map { it.displayName })

        // Case variants of .gvx_meta and .gvx_meta.tmp and control chars are rejected across all operations.
        for (forbidden in listOf(".gvx_meta", ".GVX_META", ".gvx_meta.tmp", ".GVX_META.TMP", "bad\nname.txt")) {
            assertThrows(SecurityException::class.java) {
                engine.createEntry("profile:dev-shell", "text/plain", forbidden)
            }
            assertThrows(SecurityException::class.java) {
                engine.renameEntry("entry:dev-shell/notes.md", forbidden)
            }
            assertThrows(SecurityException::class.java) {
                engine.resolveDocumentId("entry:dev-shell/$forbidden")
            }
        }
        assertThrows(SecurityException::class.java) {
            engine.resolveDocumentId("entry:dev-shell/dangling-escape")
        }

        // Fsync failure cleans up .termvr.tmp and propagates IOException.
        failNextFsync = true
        val fsyncFailTx = engine.beginWriteTransaction("entry:dev-shell/notes.md", truncateExisting = true)
        fsyncFailTx.stagingFile.writeText("corrupt")
        assertThrows(IOException::class.java) {
            engine.finishWriteTransaction(fsyncFailTx, errorOccurred = false)
        }
        assertFalse(fsyncFailTx.stagingFile.exists())
        assertEquals("safe-v1", notes.readText())
        failNextFsync = false

        // Mid-edit transition to FREEZING rejects commit with SecurityException and deletes staging file.
        val midFreezeTx = engine.beginWriteTransaction("entry:dev-shell/notes.md", truncateExisting = true)
        midFreezeTx.stagingFile.writeText("written-while-freezing")
        currentState = SandboxRunState.FREEZING
        assertThrows(SecurityException::class.java) {
            engine.finishWriteTransaction(midFreezeTx, errorOccurred = false)
        }
        assertFalse(midFreezeTx.stagingFile.exists())
        assertEquals("safe-v1", notes.readText())
        currentState = SandboxRunState.RUNNING

        // Deleting nested directory unlinks symlink without touching outsideKeepFile or .gvx_meta.
        engine.deleteEntry("entry:dev-shell/nested")
        assertFalse(subDir.exists())
        assertTrue(outsideKeepFile.exists())
        assertEquals("do-not-delete", outsideKeepFile.readText())
        assertEquals("journal-v3", metaJournal.readText())
        assertEquals("compaction-v3", metaCompaction.readText())
    }
}
