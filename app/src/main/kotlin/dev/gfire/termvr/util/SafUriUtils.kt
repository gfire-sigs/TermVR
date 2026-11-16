package dev.gfire.termvr.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.gfire.termvr.provider.WorkspaceDocumentsEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Resolves safe display names from Android SAF URIs and streams imports atomically to disk.
 */
object SafUriUtils {
    const val DEFAULT_FALLBACK_NAME: String = "imported-file"
    const val STREAM_CHUNK_BYTES: Int = 64 * 1024
    const val MAX_FILENAME_BYTES: Int = 255
    const val HIDDEN_META_TEMP_FILE: String = ".gvx_meta.tmp"

    private val OPAQUE_SAF_ID_REGEX = Regex("^[A-Za-z0-9._-]+(?::[A-Za-z0-9._-]+)*:\\d+$")
    private val stagingCounter = AtomicLong(1L)

    /**
     * Queries `OpenableColumns.DISPLAY_NAME` first and falls back to a sanitized URI segment.
     */
    fun resolveDisplayName(
        contentResolver: ContentResolver,
        uri: Uri,
        fallback: String = DEFAULT_FALLBACK_NAME,
    ): String = resolveDisplayName(
        queryDisplayName = {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            ).let { cursor -> extractDisplayNameFromCursor(cursor) }
        },
        rawLastPathSegment = { uri.lastPathSegment },
        fallback = fallback,
    )

    /**
     * Resolves a safe single-segment filename from display-name and path-segment suppliers.
     */
    fun resolveDisplayName(
        queryDisplayName: () -> String?,
        rawLastPathSegment: () -> String?,
        fallback: String = DEFAULT_FALLBACK_NAME,
    ): String {
        val queried = runCatching { queryDisplayName() }.getOrNull()
        sanitizeImportedFileName(queried)?.let { return it }

        val segment = runCatching { rawLastPathSegment() }.getOrNull()
        val segmentCandidate = extractLeafFromPathSegment(segment)
        sanitizeImportedFileName(segmentCandidate)?.let { return it }

        return sanitizeImportedFileName(fallback) ?: DEFAULT_FALLBACK_NAME
    }

    /**
     * Resolves a safe single-segment filename from raw column and path-segment values.
     */
    fun resolveDisplayName(
        queriedDisplayName: String?,
        rawLastPathSegment: String?,
        fallback: String = DEFAULT_FALLBACK_NAME,
    ): String = resolveDisplayName(
        queryDisplayName = { queriedDisplayName },
        rawLastPathSegment = { rawLastPathSegment },
        fallback = fallback,
    )

    internal fun extractDisplayNameFromCursor(cursor: Cursor?): String? {
        if (cursor == null) return null
        return cursor.use { c ->
            if (!c.moveToFirst() || c.columnCount <= 0) {
                null
            } else {
                val namedIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val colIdx = if (namedIdx >= 0) namedIdx else 0
                if (c.isNull(colIdx)) null else c.getString(colIdx)
            }
        }
    }

    /**
     * Strips path separators, traversal sequences, control characters, and reserved names.
     */
    fun sanitizeImportedFileName(raw: String?): String? {
        if (raw == null) return null
        val cleanedChars = buildString(raw.length) {
            for (ch in raw) {
                if (ch != '\u0000' && ch.code >= 0x20 && ch.code != 0x7F && !Character.isISOControl(ch)) {
                    append(ch)
                }
            }
        }
        val segments = cleanedChars
            .split('/', '\\')
            .map { part ->
                var current = part.trim()
                while (current.contains("..")) {
                    current = current.replace("..", "")
                }
                current.trim()
            }
            .filter { it.isNotEmpty() && it != "." && it != ".." }

        val leaf = segments.lastOrNull() ?: return null
        if (isReservedWorkspaceName(leaf)) return null

        val truncated = truncateUtf8PreservingExtension(leaf, MAX_FILENAME_BYTES)
        if (truncated.isEmpty() || isReservedWorkspaceName(truncated)) return null
        return truncated
    }

    /**
     * Validates an explicit single-segment name without silently rewriting separators.
     */
    fun sanitizeStrictSingleName(raw: String?): String? {
        if (raw == null) return null
        for (ch in raw) {
            if (
                ch == '/' ||
                ch == '\\' ||
                ch == '\u0000' ||
                ch.code < 0x20 ||
                ch.code == 0x7F ||
                Character.isISOControl(ch)
            ) {
                return null
            }
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || isReservedWorkspaceName(trimmed)) {
            return null
        }
        if (trimmed.toByteArray(StandardCharsets.UTF_8).size > MAX_FILENAME_BYTES) {
            return null
        }
        return trimmed
    }

    /**
     * Returns true when [name] collides with dot segments, `.gvx_meta`, or `.termvr.tmp` staging files.
     */
    fun isReservedWorkspaceName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            return true
        }
        return trimmed.equals(WorkspaceDocumentsEngine.HIDDEN_META_DIR, ignoreCase = true) ||
            trimmed.equals(HIDDEN_META_TEMP_FILE, ignoreCase = true) ||
            trimmed.endsWith(WorkspaceDocumentsEngine.TEMP_WRITE_SUFFIX, ignoreCase = true)
    }

    /**
     * Streams [openStream] in 64 KiB chunks to a sibling `.termvr.tmp` file, syncs disk, and moves atomically.
     */
    suspend fun copyStreamAtomicallyToFile(
        openStream: () -> InputStream?,
        targetFile: File,
        maxBytes: Long? = null,
        fsyncChannel: (FileChannel) -> Unit = { it.force(true) },
    ): Long {
        require(maxBytes == null || maxBytes >= 0L) { "maxBytes must be non-negative" }
        if (isReservedWorkspaceName(targetFile.name)) {
            throw SecurityException("Cannot write reserved workspace file: ${targetFile.name}")
        }
        val parentDir = targetFile.parentFile
            ?: throw IOException("Target file has no parent directory: $targetFile")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Failed to create parent directory: $parentDir")
        }
        val parentPath = parentDir.toPath()
        val targetPath = targetFile.toPath()
        if (!parentDir.isDirectory || Files.isSymbolicLink(parentPath)) {
            throw SecurityException("Parent path is not a regular directory: $parentDir")
        }
        if (Files.isSymbolicLink(targetPath)) {
            throw SecurityException("Refusing to overwrite symbolic link: $targetFile")
        }

        val preservedPermissions = captureExistingPermissions(targetPath)
        val input = openStream() ?: throw IOException("Unable to open input stream for ${targetFile.name}")
        val stagingFile = File(
            parentDir,
            ".${targetFile.name}.${stagingCounter.getAndIncrement()}${WorkspaceDocumentsEngine.TEMP_WRITE_SUFFIX}",
        )
        var committed = false
        try {
            var totalBytes = 0L
            input.use { stream ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = stream.read(buffer)
                        coroutineContext.ensureActive()
                        if (read < 0) break
                        if (read == 0) continue
                        totalBytes += read
                        if (maxBytes != null && totalBytes > maxBytes) {
                            throw IOException("Imported file exceeds maximum size of $maxBytes bytes")
                        }
                        output.write(buffer, 0, read)
                    }
                    coroutineContext.ensureActive()
                    output.flush()
                    fsyncChannel(output.channel)
                }
            }
            if (preservedPermissions != null) {
                applyPreservedPermissions(stagingFile.toPath(), preservedPermissions)
            }
            coroutineContext.ensureActive()
            if (Files.isSymbolicLink(parentPath) || Files.isSymbolicLink(targetPath)) {
                throw SecurityException("Workspace path changed to symbolic link before commit")
            }
            moveAtomically(stagingFile.toPath(), targetPath)
            committed = true
            return totalBytes
        } finally {
            if (!committed) {
                runCatching { Files.deleteIfExists(stagingFile.toPath()) }
            }
        }
    }

    internal data class PreservedFilePermissions(
        val posixPermissions: Set<PosixFilePermission>?,
        val readable: Boolean,
        val writable: Boolean,
        val executable: Boolean,
    )

    internal fun captureExistingPermissions(path: Path): PreservedFilePermissions? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return null
        }
        val posix = runCatching {
            Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        val file = path.toFile()
        return PreservedFilePermissions(
            posixPermissions = posix,
            readable = file.canRead(),
            writable = file.canWrite(),
            executable = file.canExecute(),
        )
    }

    internal fun applyPreservedPermissions(path: Path, preserved: PreservedFilePermissions) {
        val posix = preserved.posixPermissions
        if (posix != null) {
            try {
                Files.setPosixFilePermissions(path, posix)
                return
            } catch (_: UnsupportedOperationException) {
            } catch (_: IOException) {
            }
        }
        val file = path.toFile()
        file.setReadable(preserved.readable, false)
        file.setWritable(preserved.writable, true)
        file.setExecutable(preserved.executable, false)
    }

    internal fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun extractLeafFromPathSegment(rawSegment: String?): String? {
        if (rawSegment.isNullOrBlank()) return null
        val decoded = decodePercentUtf8(rawSegment).replace('\\', '/').trim('/')
        if (decoded.isBlank() || OPAQUE_SAF_ID_REGEX.matches(decoded)) {
            return null
        }
        val afterSlash = decoded.substringAfterLast('/')
        if (afterSlash.isBlank() || OPAQUE_SAF_ID_REGEX.matches(afterSlash)) {
            return null
        }
        val candidate = if (afterSlash.contains(':')) {
            afterSlash.substringAfterLast(':')
        } else {
            afterSlash
        }
        return candidate.takeIf { it.isNotBlank() }
    }

    private fun decodePercentUtf8(input: String): String {
        if (!input.contains('%')) return input
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '%' && i + 2 < input.length) {
                val hi = Character.digit(input[i + 1], 16)
                val lo = Character.digit(input[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun truncateUtf8PreservingExtension(name: String, maxBytes: Int): String {
        if (name.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) {
            return name
        }
        val extension = extractShortExtension(name)
        val extBytes = extension.toByteArray(StandardCharsets.UTF_8).size
        val stemBudget = (maxBytes - extBytes).coerceAtLeast(1)
        val stemSource = if (extension.isNotEmpty()) name.removeSuffix(extension) else name
        val truncatedStem = takeUtf8PrefixByCodePoints(stemSource, stemBudget)
        return if (truncatedStem.isEmpty()) {
            takeUtf8PrefixByCodePoints(name, maxBytes)
        } else {
            truncatedStem + extension
        }
    }

    private fun extractShortExtension(name: String): String {
        val lower = name.lowercase()
        val compoundSuffixes = listOf(".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4")
        for (compound in compoundSuffixes) {
            if (lower.endsWith(compound) && name.length > compound.length) {
                return name.substring(name.length - compound.length)
            }
        }
        val dotIdx = name.lastIndexOf('.')
        if (dotIdx <= 0 || dotIdx == name.length - 1) return ""
        val ext = name.substring(dotIdx)
        return if (ext.toByteArray(StandardCharsets.UTF_8).size <= 24) ext else ""
    }

    private fun takeUtf8PrefixByCodePoints(text: String, maxBytes: Int): String {
        val sb = StringBuilder()
        var usedBytes = 0
        var idx = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            val charCount = Character.charCount(cp)
            val cpBytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (usedBytes + cpBytes > maxBytes) break
            sb.appendCodePoint(cp)
            usedBytes += cpBytes
            idx += charCount
        }
        return sb.toString()
    }
}
