package dev.gfire.termvr.util

import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SafUriUtilsTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun prioritizesOpenableDisplayNameOverOpaqueSafDocumentIds() {
        // Property: Non-blank OpenableColumns.DISPLAY_NAME always wins over opaque SAF lastPathSegment IDs.
        // Seam: SafUriUtils.resolveDisplayName + extractDisplayNameFromCursor.
        // Fault: Using uri.lastPathSegment produces bogus "document:100001234" or "msf:4821" filenames.
        val cursor = SingleRowDisplayNameCursor("project-backup.tar.gz")
        val extracted = SafUriUtils.extractDisplayNameFromCursor(cursor)
        assertEquals("project-backup.tar.gz", extracted)
        assertTrue(cursor.closed)

        val opaqueSegments = listOf(
            "document:100001234",
            "msf:4821",
            "image:99881",
            "primary:Download/wrong-fallback.bin",
        )
        for (segment in opaqueSegments) {
            val resolved = SafUriUtils.resolveDisplayName(
                queriedDisplayName = "project-backup.tar.gz",
                rawLastPathSegment = segment,
            )
            assertEquals("project-backup.tar.gz", resolved)
        }
    }

    @Test
    fun fallsBackCleanlyWhenDisplayNameMissingAndRejectsOpaqueDocumentIds() {
        // Property: Missing/failing DISPLAY_NAME decodes real filenames from lastPathSegment, while
        // opaque provider IDs (document:1234, msf:4821) fall back to the default filename.
        // Seam: SafUriUtils.resolveDisplayName.
        // Fault: Returning "document:100001234" or corrupting "+" in filenames during percent-decoding.
        val opaqueIds = listOf(
            "document:100001234",
            "msf:4821",
            "msd:9102",
            "video:77",
            "audio:12",
            "document%3A100001234",
        )
        for (opaque in opaqueIds) {
            val resolved = SafUriUtils.resolveDisplayName(
                queryDisplayName = { throw SecurityException("Provider denied column query") },
                rawLastPathSegment = { opaque },
                fallback = "imported-file",
            )
            assertEquals("imported-file", resolved, "Opaque segment $opaque must use fallback")
        }

        assertEquals(
            "bundle.tar.zst",
            SafUriUtils.resolveDisplayName(
                queriedDisplayName = "   ",
                rawLastPathSegment = "primary%3ADownload%2Fbundle.tar.zst",
            ),
        )
        assertEquals(
            "c++_notes.md",
            SafUriUtils.resolveDisplayName(
                queriedDisplayName = null,
                rawLastPathSegment = "primary%3Ac++_notes.md",
            ),
        )
        assertEquals(
            "app-arm64.apk",
            SafUriUtils.resolveDisplayName(
                queriedDisplayName = null,
                rawLastPathSegment = "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fapp-arm64.apk",
            ),
        )
    }

    @Test
    fun stripsTraversalSeparatorsControlCharsAndRejectsReservedWorkspaceNames() {
        // Property: For all adversarial inputs, resolved names contain zero '/', '\\', '..', NUL, or
        // control characters, never equal reserved workspace metadata/staging names, and fit <= 255 UTF-8 bytes.
        // Seam: SafUriUtils.resolveDisplayName, sanitizeImportedFileName, sanitizeStrictSingleName, isReservedWorkspaceName.
        // Fault: Allowing ".gvx_meta", ".gvx_meta.tmp", "*.termvr.tmp", or "../" traversal into workspace root.
        val reservedInputs = listOf(
            ".",
            "..",
            "../..",
            ".gvx_meta",
            ".GVX_META",
            ".gvx_meta.tmp",
            ".GVX_META.TMP",
            "notes.txt.termvr.tmp",
            "NOTES.TXT.TERMVR.TMP",
            ".termvr.tmp",
        )
        for (reserved in reservedInputs) {
            assertTrue(
                reserved == "." || reserved == ".." || reserved == "../.." || SafUriUtils.isReservedWorkspaceName(reserved),
                "Expected reserved recognition for $reserved",
            )
            assertNull(SafUriUtils.sanitizeImportedFileName(reserved), "Expected null imported name for $reserved")
            assertNull(SafUriUtils.sanitizeStrictSingleName(reserved), "Expected null strict name for $reserved")
            assertEquals(
                "imported-file",
                SafUriUtils.resolveDisplayName(queriedDisplayName = reserved, rawLastPathSegment = null),
            )
        }

        assertEquals(
            "passwd",
            SafUriUtils.resolveDisplayName(queriedDisplayName = "../../etc/passwd", rawLastPathSegment = null),
        )
        assertEquals(
            "deploy.sh",
            SafUriUtils.resolveDisplayName(queriedDisplayName = "....\\\\scripts\\\\deploy.sh", rawLastPathSegment = null),
        )
        assertEquals(
            "clean_name.txt",
            SafUriUtils.resolveDisplayName(
                queriedDisplayName = "clean\u0000_name\r\n\u0007.txt",
                rawLastPathSegment = null,
            ),
        )
        assertNull(SafUriUtils.sanitizeStrictSingleName("sub/file.txt"))
        assertNull(SafUriUtils.sanitizeStrictSingleName("sub\\file.txt"))
        assertNull(SafUriUtils.sanitizeStrictSingleName("bad\u0000.txt"))

        val longHangul = "가".repeat(120) + ".tar.gz"
        val truncated = SafUriUtils.resolveDisplayName(queriedDisplayName = longHangul, rawLastPathSegment = null)
        val utf8Bytes = truncated.toByteArray(StandardCharsets.UTF_8)
        assertTrue(utf8Bytes.size <= SafUriUtils.MAX_FILENAME_BYTES)
        assertTrue(truncated.endsWith(".tar.gz"))
        assertFalse(truncated.contains('\uFFFD'))
    }

    @Test
    fun streamsMultiChunkDataAtomicallyAndPreservesExistingTargetUntilCommit() = runTest {
        // Property: Multi-chunk streaming (> 64 KiB) writes identical bytes via sibling .termvr.tmp,
        // invokes fsync before atomic move, leaves zero temp files, and preserves existing executable mode.
        // Seam: SafUriUtils.copyStreamAtomicallyToFile.
        // Fault: Partial target file visible mid-stream, missing fsync, leaked .termvr.tmp, or lost 0755 mode.
        val totalSize = (SafUriUtils.STREAM_CHUNK_BYTES * 3) + 17_123
        val expected = ByteArray(totalSize) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val target = File(tempDir, "build.sh").apply { writeText("#!/bin/sh\necho old\n") }
        val posix755 = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        Files.setPosixFilePermissions(target.toPath(), posix755)

        val fsyncCount = AtomicInteger(0)
        val closedInput = AtomicBoolean(false)
        var sawOldTargetDuringFsync = false

        val written = SafUriUtils.copyStreamAtomicallyToFile(
            openStream = {
                object : ByteArrayInputStream(expected) {
                    override fun close() {
                        closedInput.set(true)
                        super.close()
                    }
                }
            },
            targetFile = target,
            fsyncChannel = { channel ->
                // Before atomic move completes, targetFile still holds the old complete script.
                sawOldTargetDuringFsync = target.readText() == "#!/bin/sh\necho old\n"
                channel.force(true)
                fsyncCount.incrementAndGet()
            },
        )

        assertEquals(totalSize.toLong(), written)
        assertTrue(closedInput.get())
        assertTrue(sawOldTargetDuringFsync)
        assertEquals(1, fsyncCount.get())
        assertArrayEquals(expected, target.readBytes())
        assertEquals(posix755, Files.getPosixFilePermissions(target.toPath()))
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })
    }

    @Test
    fun cleansUpTempFileAndPreservesTargetOnMidStreamIoExceptionFsyncFailureMaxBytesAndCancel() = runTest {
        // Property: Any mid-stream IOException, fsync failure, maxBytes overflow, null stream, or cancellation
        // deletes the sibling .termvr.tmp file and leaves any prior target file byte-identical.
        // Seam: SafUriUtils.copyStreamAtomicallyToFile.
        // Fault: Orphaned .termvr.tmp files filling storage or truncated/corrupted target files on error.
        val target = File(tempDir, "archive.tar")
        val originalBytes = "intact-archive-payload".encodeToByteArray()
        target.writeBytes(originalBytes)

        // 1. Mid-stream IOException after > 64 KiB written.
        val closedOnError = AtomicBoolean(false)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                SafUriUtils.copyStreamAtomicallyToFile(
                    openStream = {
                        object : InputStream() {
                            private var emitted = 0
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (emitted >= SafUriUtils.STREAM_CHUNK_BYTES + 4096) {
                                    throw IOException("Simulated mid-stream EIO")
                                }
                                val step = minOf(len, 8192)
                                for (i in 0 until step) {
                                    b[off + i] = 0x5A
                                }
                                emitted += step
                                return step
                            }

                            override fun read(): Int = throw IOException("Unexpected single-byte read")

                            override fun close() {
                                closedOnError.set(true)
                            }
                        }
                    },
                    targetFile = target,
                )
            }
        }
        assertTrue(closedOnError.get())
        assertArrayEquals(originalBytes, target.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })

        // 2. ENOSPC / IOException during fsync.
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                SafUriUtils.copyStreamAtomicallyToFile(
                    openStream = { ByteArrayInputStream(ByteArray(4096) { 1 }) },
                    targetFile = target,
                    fsyncChannel = { throw IOException("No space left on device") },
                )
            }
        }
        assertArrayEquals(originalBytes, target.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })

        // 3. maxBytes cap exceeded.
        val capBytes = 100_000L
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                SafUriUtils.copyStreamAtomicallyToFile(
                    openStream = { ByteArrayInputStream(ByteArray(100_001) { 2 }) },
                    targetFile = target,
                    maxBytes = capBytes,
                )
            }
        }
        assertArrayEquals(originalBytes, target.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })

        // Exact maxBytes boundary succeeds.
        val exactPayload = ByteArray(100_000) { idx -> (idx and 0x7F).toByte() }
        val exactTarget = File(tempDir, "exact-cap.bin")
        val copiedExact = SafUriUtils.copyStreamAtomicallyToFile(
            openStream = { ByteArrayInputStream(exactPayload) },
            targetFile = exactTarget,
            maxBytes = capBytes,
        )
        assertEquals(capBytes, copiedExact)
        assertArrayEquals(exactPayload, exactTarget.readBytes())

        // 4. Coroutine cancellation mid-stream cleans up temp file and preserves target.
        var jobRef: kotlinx.coroutines.Job? = null
        val job = launch {
            SafUriUtils.copyStreamAtomicallyToFile(
                openStream = {
                    object : InputStream() {
                        private var chunks = 0
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            chunks++
                            if (chunks == 2) {
                                jobRef?.cancel(CancellationException("User cancelled import"))
                            }
                            if (chunks > 8) {
                                return -1
                            }
                            val n = minOf(len, SafUriUtils.STREAM_CHUNK_BYTES)
                            for (i in 0 until n) b[off + i] = 0x33
                            return n
                        }

                        override fun read(): Int = 0x33
                    }
                },
                targetFile = target,
            )
        }
        jobRef = job
        job.join()
        assertTrue(job.isCancelled)
        assertArrayEquals(originalBytes, target.readBytes())
        assertTrue(tempDir.listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })
    }
}

private class SingleRowDisplayNameCursor(private val displayName: String?) : Cursor {
    private var pos = -1
    var closed = false
        private set

    override fun getCount(): Int = 1
    override fun getPosition(): Int = pos
    override fun move(offset: Int): Boolean = moveToPosition(pos + offset)
    override fun moveToPosition(position: Int): Boolean {
        pos = position
        return position == 0
    }

    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToLast(): Boolean = moveToPosition(0)
    override fun moveToNext(): Boolean = moveToPosition(pos + 1)
    override fun moveToPrevious(): Boolean = moveToPosition(pos - 1)
    override fun isFirst(): Boolean = pos == 0
    override fun isLast(): Boolean = pos == 0
    override fun isBeforeFirst(): Boolean = pos < 0
    override fun isAfterLast(): Boolean = pos > 0
    override fun getColumnIndex(columnName: String?): Int =
        if (columnName == OpenableColumns.DISPLAY_NAME) 0 else -1

    override fun getColumnIndexOrThrow(columnName: String?): Int =
        getColumnIndex(columnName).takeIf { it >= 0 } ?: throw IllegalArgumentException("No column $columnName")

    override fun getColumnName(columnIndex: Int): String = OpenableColumns.DISPLAY_NAME
    override fun getColumnNames(): Array<String> = arrayOf(OpenableColumns.DISPLAY_NAME)
    override fun getColumnCount(): Int = 1
    override fun getBlob(columnIndex: Int): ByteArray = ByteArray(0)
    override fun getString(columnIndex: Int): String? = if (pos == 0 && columnIndex == 0) displayName else null
    override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer?) = Unit
    override fun getShort(columnIndex: Int): Short = 0
    override fun getInt(columnIndex: Int): Int = 0
    override fun getLong(columnIndex: Int): Long = 0L
    override fun getFloat(columnIndex: Int): Float = 0f
    override fun getDouble(columnIndex: Int): Double = 0.0
    override fun getType(columnIndex: Int): Int =
        if (displayName == null) Cursor.FIELD_TYPE_NULL else Cursor.FIELD_TYPE_STRING

    override fun isNull(columnIndex: Int): Boolean = displayName == null || columnIndex != 0

    @Deprecated("Deprecated in Cursor")
    override fun deactivate() = Unit

    @Deprecated("Deprecated in Cursor")
    override fun requery(): Boolean = false

    override fun close() {
        closed = true
    }

    override fun isClosed(): Boolean = closed
    override fun registerContentObserver(observer: ContentObserver?) = Unit
    override fun unregisterContentObserver(observer: ContentObserver?) = Unit
    override fun registerDataSetObserver(observer: DataSetObserver?) = Unit
    override fun unregisterDataSetObserver(observer: DataSetObserver?) = Unit
    override fun setNotificationUri(cr: ContentResolver?, uri: Uri?) = Unit
    override fun getNotificationUri(): Uri? = null
    override fun getWantsAllOnMoveCalls(): Boolean = false
    override fun setExtras(extras: Bundle?) = Unit
    override fun getExtras(): Bundle = Bundle.EMPTY
    override fun respond(extras: Bundle?): Bundle = Bundle.EMPTY
}
