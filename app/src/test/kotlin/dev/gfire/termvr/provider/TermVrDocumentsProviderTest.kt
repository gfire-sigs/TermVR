package dev.gfire.termvr.provider

import android.provider.DocumentsContract.Document
import dev.gfire.termvr.core.data.GitWorktreeManager
import dev.gfire.termvr.core.model.GitWorktreeSpec
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

    @Test
    fun supportsAppendModeWithoutTruncatingAndHandlesMax255ByteUtf8DocumentSaves() {
        // Property: Open mode "wa" preserves existing file bytes for append instead of truncating, invalid
        // open modes fail before staging, and 255-byte UTF-8 filenames keep .termvr.tmp <= 255 UTF-8 bytes.
        // Seam: WorkspaceDocumentsEngine.parseOpenMode, createEntry, beginWriteTransaction, finishWriteTransaction.
        // Fault: Treating "wa" as truncateExisting=true or exceeding 255-byte NAME_MAX on <name>.termvr.tmp.
        val readMode = WorkspaceDocumentsEngine.parseOpenMode("r")
        assertFalse(readMode.wantsWrite)
        assertFalse(readMode.truncateExisting)

        val writeMode = WorkspaceDocumentsEngine.parseOpenMode("w")
        assertTrue(writeMode.wantsWrite)
        assertTrue(writeMode.truncateExisting)

        val writeTruncMode = WorkspaceDocumentsEngine.parseOpenMode("wt")
        assertTrue(writeTruncMode.wantsWrite)
        assertTrue(writeTruncMode.truncateExisting)

        val readWriteTruncMode = WorkspaceDocumentsEngine.parseOpenMode("rwt")
        assertTrue(readWriteTruncMode.wantsWrite)
        assertTrue(readWriteTruncMode.truncateExisting)

        val readWriteMode = WorkspaceDocumentsEngine.parseOpenMode("rw")
        assertTrue(readWriteMode.wantsWrite)
        assertFalse(readWriteMode.truncateExisting)
        assertFalse(readWriteMode.append)

        val writeAppendMode = WorkspaceDocumentsEngine.parseOpenMode("wa")
        assertTrue(writeAppendMode.wantsWrite)
        assertFalse(writeAppendMode.truncateExisting)
        assertTrue(writeAppendMode.append)

        for (badMode in listOf("", "x", "rwa", "tw")) {
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceDocumentsEngine.parseOpenMode(badMode)
            }
        }

        val workspace = File(tempDir, "workspaces/dev-shell").apply { mkdirs() }
        val logFile = File(workspace, "build.log").apply { writeText("line-1\n") }
        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { SandboxRunState.RUNNING },
        )

        val appendTx = engine.beginWriteTransaction(
            documentId = "entry:dev-shell/build.log",
            truncateExisting = writeAppendMode.truncateExisting,
        )
        java.io.FileOutputStream(appendTx.stagingFile, writeAppendMode.append).use { out ->
            out.write("line-2\n".toByteArray(Charsets.UTF_8))
        }
        engine.finishWriteTransaction(appendTx, errorOccurred = false)
        assertEquals("line-1\nline-2\n", logFile.readText())

        // 255-byte UTF-8 filename save through SAF write transaction without ENAMETOOLONG.
        val maxUtf8Name = "가".repeat(83) + ".txt" // 83 * 3 + 4 = 253 UTF-8 bytes (+2 ascii = 255)
            .let { "ab$it" }
        assertEquals(255, maxUtf8Name.toByteArray(Charsets.UTF_8).size)

        val createdDocId = engine.createEntry("profile:dev-shell", "text/plain", maxUtf8Name)
        val longTx1 = engine.beginWriteTransaction(createdDocId, truncateExisting = true)
        val longTx2 = engine.beginWriteTransaction(createdDocId, truncateExisting = true)
        assertTrue(longTx1.stagingFile.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(longTx2.stagingFile.name.toByteArray(Charsets.UTF_8).size <= 255)
        assertNotEquals(longTx1.stagingFile.absolutePath, longTx2.stagingFile.absolutePath)

        engine.finishWriteTransaction(longTx1, errorOccurred = true)
        longTx2.stagingFile.writeText("saved-255-utf8")
        engine.finishWriteTransaction(longTx2, errorOccurred = false)

        val savedLongFile = File(workspace, maxUtf8Name)
        assertEquals("saved-255-utf8", savedLongFile.readText())
        assertTrue(workspace.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })
    }

    @Test
    fun exposesSharedGitWorktreesWithCanonicalConfinementPosixModePreservationAndDotGitProtection() {
        // Property: Shared Git worktrees under <filesDir>/git-projects/<projectId>/worktrees/<profileId>
        // browse via git-root -> git-project -> git-worktree -> git-entry, support "r", "wt", "wa", "rw"
        // atomic saves with 0755 POSIX mode preservation, hide/protect .git and .gvx_meta.lock/.gvx_meta.tmp.*,
        // enforce FROZEN write locks, and reject .. or symlink escapes into repo.git or outside filesDir.
        // Seam: WorkspaceDocumentsEngine Git worktree document operations.
        // Oracle: Phase 3 SAF external editor specification and Section 17.2 confinement invariants.
        // Fault detected: Symlink escape into ../../repo.git/config, overwriting worktree .git pointer, or dropping 0755 mode.
        val manager = GitWorktreeManager(tempDir)
        manager.createOrEnsureProject("mono-app", "Mono App", "main")
        val wtDir = manager.provisionWorktreeForContainer(
            profileId = "dev-worker",
            spec = GitWorktreeSpec("mono-app", "wt/dev-worker"),
        )
        val dotGitOriginal = File(wtDir, ".git").readText()
        File(wtDir, ".gvx_meta").writeText("meta")
        File(wtDir, ".gvx_meta.lock").writeText("")
        File(wtDir, ".gvx_meta.tmp.a1b2c3").writeText("tmp")
        File(wtDir, ".gitignore").writeText("build/\n")

        val posix755 = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        val runSh = File(wtDir, "run.sh").apply { writeText("#!/bin/sh\necho v1\n") }
        Files.setPosixFilePermissions(runSh.toPath(), posix755)

        // Symlink attempting to escape into ../../repo.git/config
        val repoConfig = File(tempDir, "git-projects/mono-app/repo.git/config")
        Files.createSymbolicLink(File(wtDir, "repo-escape").toPath(), repoConfig.toPath())

        var currentRunState = SandboxRunState.RUNNING
        val engine = WorkspaceDocumentsEngine(
            filesDir = tempDir,
            liveRunStateProvider = { profileId ->
                if (profileId == "dev-worker") currentRunState else null
            },
        )

        val projectEntries = engine.listChildren(WorkspaceDocumentsEngine.GIT_ROOT_DOCUMENT_ID)
        assertEquals(listOf("git-project:mono-app"), projectEntries.map { it.documentId })

        val worktreeEntries = engine.listChildren("git-project:mono-app")
        assertEquals(listOf("git-worktree:mono-app/dev-worker"), worktreeEntries.map { it.documentId })
        assertTrue(engine.isChildDocument(WorkspaceDocumentsEngine.GIT_ROOT_DOCUMENT_ID, "git-project:mono-app"))
        assertTrue(engine.isChildDocument("git-project:mono-app", "git-worktree:mono-app/dev-worker"))

        // Listing hides .git, .gvx_meta, .gvx_meta.lock, .gvx_meta.tmp.*, and escaping symlinks, while keeping .gitignore!
        val visibleChildren = engine.listChildren("git-worktree:mono-app/dev-worker").map { it.displayName }
        assertEquals(listOf(".gitignore", "run.sh"), visibleChildren)

        val scriptDocId = WorkspaceDocumentsEngine.buildWorktreeDocumentId("mono-app", "dev-worker", "run.sh")
        assertEquals("git-entry:mono-app/dev-worker/run.sh", scriptDocId)
        assertTrue(engine.isChildDocument("git-worktree:mono-app/dev-worker", scriptDocId))

        // Mode "r": read resolved file
        val readResolved = engine.resolveDocumentId(scriptDocId) as WorkspaceDocumentsEngine.ResolvedDocument.ProfileEntry
        assertEquals("#!/bin/sh\necho v1\n", readResolved.targetFile.readText())

        // Mode "wt": truncate write preserving 0755
        val wtMode = WorkspaceDocumentsEngine.parseOpenMode("wt")
        val wtTx = engine.beginWriteTransaction(scriptDocId, truncateExisting = wtMode.truncateExisting)
        wtTx.stagingFile.writeText("#!/bin/sh\necho v2\n")
        engine.finishWriteTransaction(wtTx, errorOccurred = false)
        assertEquals("#!/bin/sh\necho v2\n", runSh.readText())
        assertEquals(posix755, Files.getPosixFilePermissions(runSh.toPath()))

        // Mode "wa": append preserving existing bytes and 0755
        val waMode = WorkspaceDocumentsEngine.parseOpenMode("wa")
        val waTx = engine.beginWriteTransaction(scriptDocId, truncateExisting = waMode.truncateExisting)
        java.io.FileOutputStream(waTx.stagingFile, waMode.append).use { out ->
            out.write("echo v3\n".toByteArray(Charsets.UTF_8))
        }
        engine.finishWriteTransaction(waTx, errorOccurred = false)
        assertEquals("#!/bin/sh\necho v2\necho v3\n", runSh.readText())
        assertEquals(posix755, Files.getPosixFilePermissions(runSh.toPath()))

        // Mode "rw": in-place read-write preserving 0755
        val rwMode = WorkspaceDocumentsEngine.parseOpenMode("rw")
        val rwTx = engine.beginWriteTransaction(scriptDocId, truncateExisting = rwMode.truncateExisting)
        assertEquals("#!/bin/sh\necho v2\necho v3\n", rwTx.stagingFile.readText())
        rwTx.stagingFile.writeText("#!/bin/sh\necho final\n")
        engine.finishWriteTransaction(rwTx, errorOccurred = false)
        assertEquals("#!/bin/sh\necho final\n", runSh.readText())
        assertEquals(posix755, Files.getPosixFilePermissions(runSh.toPath()))

        // Confinement & reserved metadata rejections
        for (badDocId in listOf(
            "git-entry:mono-app/dev-worker/../../repo.git/config",
            "git-entry:../escape/dev-worker/run.sh",
            "git-entry:mono-app/../escape/run.sh",
            "git-entry:mono-app/dev-worker/repo-escape",
            "git-entry:mono-app/dev-worker/.git",
            "git-entry:mono-app/dev-worker/.gvx_meta.lock",
            "git-entry:mono-app/dev-worker/.gvx_meta.tmp.a1b2c3",
        )) {
            assertThrows(SecurityException::class.java, {
                engine.resolveDocumentId(badDocId)
            }, "Expected '$badDocId' to fail")
        }
        assertThrows(SecurityException::class.java) {
            engine.createEntry("git-worktree:mono-app/dev-worker", "text/plain", ".git")
        }
        assertEquals(dotGitOriginal, File(wtDir, ".git").readText())

        // FROZEN lock blocks worktree writes
        currentRunState = SandboxRunState.FROZEN
        assertEquals(0, engine.statDocument(scriptDocId).flags)
        assertThrows(SecurityException::class.java) {
            engine.beginWriteTransaction(scriptDocId, truncateExisting = true)
        }
    }
}
