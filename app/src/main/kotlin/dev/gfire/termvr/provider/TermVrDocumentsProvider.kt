package dev.gfire.termvr.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.webkit.MimeTypeMap
import dev.gfire.termvr.TermVrApplication
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.runtime.CheckpointController
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Pure JVM workspace confinement, lock inspection, and atomic write staging engine for SAF access.
 */
class WorkspaceDocumentsEngine(
    private val filesDir: File,
    private val liveRunStateProvider: (String) -> SandboxRunState?,
    private val checkpointController: CheckpointController = CheckpointController(filesDir),
    private val fsyncFile: (File) -> Unit = { file ->
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
    },
) {
    private val workspacesRootDir: File
        get() = File(filesDir, "workspaces")

    /**
     * Lists available workspace profile identifiers under `<filesDir>/workspaces`.
     */
    fun listProfileIds(): List<String> {
        val root = workspacesRootDir
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && SandboxProfile.isValidProfileId(it.name) && !isHiddenName(it.name) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

    /**
     * Returns true when external writes must be rejected because the profile is freezing or frozen.
     */
    fun isProfileWriteLocked(profileId: String): Boolean {
        if (!SandboxProfile.isValidProfileId(profileId)) return true
        val liveState = liveRunStateProvider(profileId)
        if (liveState != null && liveState.locksWorkspaceWrites) {
            return true
        }
        val persistedCheckpoint = runCatching { checkpointController.readMetadata(profileId) }.getOrNull()
        return persistedCheckpoint != null && !persistedCheckpoint.superseded
    }

    /**
     * Resolves a document identifier into a verified workspace target inside `<filesDir>/workspaces/<profileId>`.
     */
    fun resolveDocumentId(documentId: String): ResolvedDocument {
        if (documentId == ROOT_DOCUMENT_ID) {
            return ResolvedDocument.RootDirectory
        }
        if (documentId.startsWith(PROFILE_PREFIX)) {
            val profileId = documentId.removePrefix(PROFILE_PREFIX)
            val rootDir = requireProfileWorkspaceDir(profileId)
            return ResolvedDocument.ProfileEntry(
                profileId = profileId,
                relativePath = "",
                workspaceRoot = rootDir,
                targetFile = rootDir,
                writeLocked = isProfileWriteLocked(profileId),
            )
        }
        if (documentId.startsWith(ENTRY_PREFIX)) {
            val remainder = documentId.removePrefix(ENTRY_PREFIX)
            val slashIdx = remainder.indexOf('/')
            val profileId = if (slashIdx < 0) remainder else remainder.substring(0, slashIdx)
            val relative = if (slashIdx < 0) "" else remainder.substring(slashIdx + 1)
            val entryFile = resolveConfinedTarget(profileId, relative)
            return ResolvedDocument.ProfileEntry(
                profileId = profileId,
                relativePath = normalizeRelativePath(relative),
                workspaceRoot = requireProfileWorkspaceDir(profileId),
                targetFile = entryFile,
                writeLocked = isProfileWriteLocked(profileId),
            )
        }
        throw FileNotFoundException("Unknown documentId: $documentId")
    }

    /**
     * Builds the document identifier for a relative path inside a workspace profile.
     */
    fun buildDocumentId(profileId: String, relativePath: String): String {
        val cleanProfile = validateProfileId(profileId)
        val cleanRel = normalizeRelativePath(relativePath)
        return if (cleanRel.isEmpty()) {
            "$PROFILE_PREFIX$cleanProfile"
        } else {
            "$ENTRY_PREFIX$cleanProfile/$cleanRel"
        }
    }

    /**
     * Lists visible child entries inside a workspace directory, filtering `.gvx_meta` and `*.termvr.tmp`.
     */
    fun listChildren(documentId: String): List<WorkspaceEntry> {
        return when (val resolved = resolveDocumentId(documentId)) {
            ResolvedDocument.RootDirectory -> {
                listProfileIds().map { profileId ->
                    val dir = requireProfileWorkspaceDir(profileId)
                    WorkspaceEntry(
                        documentId = "$PROFILE_PREFIX$profileId",
                        displayName = profileId,
                        isDirectory = true,
                        sizeBytes = 0L,
                        lastModifiedMs = dir.lastModified(),
                        flags = computeDirectoryFlags(isProfileRoot = true, writeLocked = isProfileWriteLocked(profileId)),
                    )
                }
            }

            is ResolvedDocument.ProfileEntry -> {
                if (!resolved.targetFile.isDirectory) return emptyList()
                val canonicalRoot = resolved.workspaceRoot.canonicalFile
                val children = resolved.targetFile.listFiles() ?: return emptyList()
                children.asSequence()
                    .filter { child -> !isHiddenName(child.name) }
                    .filter { child -> isCanonicalPathConfined(canonicalRoot, child) }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { child ->
                        val childRel = if (resolved.relativePath.isEmpty()) {
                            child.name
                        } else {
                            "${resolved.relativePath}/${child.name}"
                        }
                        val childDocId = buildDocumentId(resolved.profileId, childRel)
                        val isDir = child.isDirectory
                        val flags = if (isDir) {
                            computeDirectoryFlags(isProfileRoot = false, writeLocked = resolved.writeLocked)
                        } else {
                            computeFileFlags(writeLocked = resolved.writeLocked)
                        }
                        WorkspaceEntry(
                            documentId = childDocId,
                            displayName = child.name,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else child.length(),
                            lastModifiedMs = child.lastModified(),
                            flags = flags,
                        )
                    }
                    .toList()
            }
        }
    }

    /**
     * Computes `DocumentsContract.Document` metadata for a single document identifier.
     */
    fun statDocument(documentId: String): WorkspaceEntry {
        return when (val resolved = resolveDocumentId(documentId)) {
            ResolvedDocument.RootDirectory -> WorkspaceEntry(
                documentId = ROOT_DOCUMENT_ID,
                displayName = "TermVR Workspaces",
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMs = workspacesRootDir.lastModified(),
                flags = 0,
            )

            is ResolvedDocument.ProfileEntry -> {
                if (!resolved.targetFile.exists()) {
                    throw FileNotFoundException("Document does not exist: $documentId")
                }
                val isProfileRoot = resolved.relativePath.isEmpty()
                val isDir = resolved.targetFile.isDirectory
                val flags = if (isDir) {
                    computeDirectoryFlags(isProfileRoot = isProfileRoot, writeLocked = resolved.writeLocked)
                } else {
                    computeFileFlags(writeLocked = resolved.writeLocked)
                }
                WorkspaceEntry(
                    documentId = documentId,
                    displayName = if (isProfileRoot) resolved.profileId else resolved.targetFile.name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else resolved.targetFile.length(),
                    lastModifiedMs = resolved.targetFile.lastModified(),
                    flags = flags,
                )
            }
        }
    }

    /**
     * Returns true when [childDocId] is a direct or nested descendant of [parentDocId].
     */
    fun isChildDocument(parentDocId: String, childDocId: String): Boolean {
        val parent = runCatching { resolveDocumentId(parentDocId) }.getOrNull() ?: return false
        val child = runCatching { resolveDocumentId(childDocId) }.getOrNull() ?: return false
        return when (parent) {
            ResolvedDocument.RootDirectory -> child is ResolvedDocument.ProfileEntry
            is ResolvedDocument.ProfileEntry -> {
                if (child !is ResolvedDocument.ProfileEntry || parent.profileId != child.profileId) {
                    false
                } else if (parent.relativePath.isEmpty()) {
                    child.relativePath.isNotEmpty()
                } else {
                    child.relativePath.startsWith("${parent.relativePath}/")
                }
            }
        }
    }

    /**
     * Creates a confined file or directory inside a non-frozen workspace profile.
     */
    fun createEntry(parentDocId: String, mimeType: String, displayName: String): String {
        val parent = resolveProfileEntry(parentDocId)
        requireUnlocked(parent.profileId)
        if (!parent.targetFile.isDirectory) {
            throw FileNotFoundException("Parent is not a directory: $parentDocId")
        }
        val cleanName = sanitizeSingleSegment(displayName)
        val newRel = if (parent.relativePath.isEmpty()) cleanName else "${parent.relativePath}/$cleanName"
        val target = resolveConfinedTarget(parent.profileId, newRel)
        if (target.exists()) {
            throw IOException("Target already exists: $cleanName")
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) {
            target.mkdir()
        } else {
            target.createNewFile()
        }
        if (!created) {
            throw IOException("Failed to create document: $cleanName")
        }
        return buildDocumentId(parent.profileId, newRel)
    }

    /**
     * Deletes a confined file or directory inside a non-frozen workspace profile.
     */
    fun deleteEntry(documentId: String) {
        val entry = resolveProfileEntry(documentId)
        if (entry.relativePath.isEmpty()) {
            throw SecurityException("Cannot delete workspace root for ${entry.profileId}")
        }
        requireUnlocked(entry.profileId)
        if (!entry.targetFile.exists()) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        val deleted = if (entry.targetFile.isDirectory) {
            entry.targetFile.deleteRecursively()
        } else {
            entry.targetFile.delete()
        }
        if (!deleted) {
            throw IOException("Failed to delete $documentId")
        }
    }

    /**
     * Renames a confined workspace entry in place inside a non-frozen profile.
     */
    fun renameEntry(documentId: String, newDisplayName: String): String {
        val entry = resolveProfileEntry(documentId)
        if (entry.relativePath.isEmpty()) {
            throw SecurityException("Cannot rename workspace root for ${entry.profileId}")
        }
        requireUnlocked(entry.profileId)
        if (!entry.targetFile.exists()) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        val cleanName = sanitizeSingleSegment(newDisplayName)
        val parentRel = entry.relativePath.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val destination = resolveConfinedTarget(entry.profileId, newRel)
        if (destination.exists()) {
            throw IOException("Destination already exists: $cleanName")
        }
        Files.move(entry.targetFile.toPath(), destination.toPath())
        return buildDocumentId(entry.profileId, newRel)
    }

    /**
     * Prepares a confined target for read or atomic `<filename>.termvr.tmp` write access.
     */
    fun beginWriteTransaction(documentId: String, truncateExisting: Boolean): StagedWriteTransaction {
        val entry = resolveProfileEntry(documentId)
        if (entry.relativePath.isEmpty()) {
            throw SecurityException("Cannot open workspace root directory as file")
        }
        requireUnlocked(entry.profileId)
        if (entry.targetFile.isDirectory) {
            throw FileNotFoundException("Cannot open directory for writing: $documentId")
        }
        val parentDir = entry.targetFile.parentFile
            ?: throw FileNotFoundException("Missing parent directory for $documentId")
        if (!parentDir.isDirectory && !parentDir.mkdirs()) {
            throw IOException("Cannot prepare parent directory for $documentId")
        }
        val stagingFile = File(parentDir, "${entry.targetFile.name}$TEMP_WRITE_SUFFIX")
        if (!truncateExisting && entry.targetFile.exists()) {
            Files.copy(
                entry.targetFile.toPath(),
                stagingFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else if (stagingFile.exists()) {
            stagingFile.delete()
        }
        if (!stagingFile.exists()) {
            stagingFile.createNewFile()
        }
        return StagedWriteTransaction(
            profileId = entry.profileId,
            targetFile = entry.targetFile,
            stagingFile = stagingFile,
        )
    }

    /**
     * Commits a staged `<filename>.termvr.tmp` write with `fsync` and atomic replace, or aborts cleanly.
     */
    fun finishWriteTransaction(transaction: StagedWriteTransaction, errorOccurred: Boolean) {
        if (errorOccurred || isProfileWriteLocked(transaction.profileId)) {
            transaction.stagingFile.delete()
            return
        }
        try {
            fsyncFile(transaction.stagingFile)
            try {
                Files.move(
                    transaction.stagingFile.toPath(),
                    transaction.targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: IOException) {
                Files.move(
                    transaction.stagingFile.toPath(),
                    transaction.targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            transaction.stagingFile.delete()
            throw t
        }
    }

    private fun resolveProfileEntry(documentId: String): ResolvedDocument.ProfileEntry {
        val resolved = resolveDocumentId(documentId)
        return resolved as? ResolvedDocument.ProfileEntry
            ?: throw SecurityException("Operation requires a workspace profile entry: $documentId")
    }

    private fun requireUnlocked(profileId: String) {
        if (isProfileWriteLocked(profileId)) {
            throw SecurityException("Workspace is write-locked while profile $profileId is freezing or frozen")
        }
    }

    private fun validateProfileId(profileId: String): String {
        if (!SandboxProfile.isValidProfileId(profileId) || isHiddenName(profileId)) {
            throw SecurityException("Invalid workspace profile id: $profileId")
        }
        return profileId
    }

    private fun requireProfileWorkspaceDir(profileId: String): File {
        val validId = validateProfileId(profileId)
        val root = File(workspacesRootDir, validId)
        if (!root.exists()) {
            throw FileNotFoundException("Workspace does not exist for profile $validId")
        }
        return root.canonicalFile
    }

    private fun resolveConfinedTarget(profileId: String, rawRelativePath: String): File {
        val workspaceRoot = requireProfileWorkspaceDir(profileId)
        val normalized = normalizeRelativePath(rawRelativePath)
        if (normalized.isEmpty()) return workspaceRoot

        val segments = normalized.split('/')
        var cursor = workspaceRoot
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." || isHiddenName(segment)) {
                throw SecurityException("Disallowed workspace path segment: $segment")
            }
            if (segment.indexOf('\u0000') >= 0) {
                throw SecurityException("Null byte in workspace path")
            }
            cursor = File(cursor, segment)
            if (!isCanonicalPathConfined(workspaceRoot, cursor)) {
                throw SecurityException("Path escapes workspace root: $rawRelativePath")
            }
        }
        return cursor
    }

    private fun normalizeRelativePath(raw: String): String {
        val trimmed = raw.trim('/')
        if (trimmed.isEmpty()) return ""
        val parts = trimmed.split('/')
        for (part in parts) {
            if (part.isEmpty() || part == "." || part == "..") {
                throw SecurityException("Non-canonical relative path segment: $part")
            }
        }
        return parts.joinToString("/")
    }

    private fun sanitizeSingleSegment(displayName: String): String {
        val trimmed = displayName.trim()
        if (
            trimmed.isEmpty() ||
            trimmed == "." ||
            trimmed == ".." ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            trimmed.indexOf('\u0000') >= 0 ||
            isHiddenName(trimmed)
        ) {
            throw SecurityException("Invalid document name: $displayName")
        }
        return trimmed
    }

    private fun isCanonicalPathConfined(canonicalRoot: File, candidate: File): Boolean {
        val rootPath = canonicalRoot.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        return candidatePath == rootPath || candidatePath.startsWith(rootPath)
    }

    private fun isHiddenName(name: String): Boolean {
        return name == HIDDEN_META_DIR || name.endsWith(TEMP_WRITE_SUFFIX)
    }

    private fun computeDirectoryFlags(isProfileRoot: Boolean, writeLocked: Boolean): Int {
        if (writeLocked) return 0
        var flags = Document.FLAG_DIR_SUPPORTS_CREATE
        if (!isProfileRoot) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        return flags
    }

    private fun computeFileFlags(writeLocked: Boolean): Int {
        if (writeLocked) return 0
        return Document.FLAG_SUPPORTS_WRITE or
            Document.FLAG_SUPPORTS_DELETE or
            Document.FLAG_SUPPORTS_RENAME
    }

    sealed interface ResolvedDocument {
        data object RootDirectory : ResolvedDocument
        data class ProfileEntry(
            val profileId: String,
            val relativePath: String,
            val workspaceRoot: File,
            val targetFile: File,
            val writeLocked: Boolean,
        ) : ResolvedDocument
    }

    data class WorkspaceEntry(
        val documentId: String,
        val displayName: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val flags: Int,
    )

    data class StagedWriteTransaction(
        val profileId: String,
        val targetFile: File,
        val stagingFile: File,
    )

    companion object {
        const val ROOT_ID = "termvr_workspaces"
        const val ROOT_DOCUMENT_ID = "root"
        const val PROFILE_PREFIX = "profile:"
        const val ENTRY_PREFIX = "entry:"
        const val HIDDEN_META_DIR = ".gvx_meta"
        const val TEMP_WRITE_SUFFIX = ".termvr.tmp"
    }
}

/**
 * Publishes active sandbox `/workspace` directories to external Android SAF clients.
 */
class TermVrDocumentsProvider : DocumentsProvider() {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val engine: WorkspaceDocumentsEngine by lazy {
        val appContext = requireNotNull(context?.applicationContext) { "Missing DocumentsProvider context" }
        WorkspaceDocumentsEngine(
            filesDir = appContext.filesDir,
            liveRunStateProvider = { profileId ->
                val app = appContext as? TermVrApplication
                app?.container?.supervisor?.value?.runStates?.value?.get(profileId)
            },
            checkpointController = CheckpointController(appContext.filesDir),
            fsyncFile = { file ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                    Os.fsync(pfd.fileDescriptor)
                }
            },
        )
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        row.add(Root.COLUMN_ROOT_ID, WorkspaceDocumentsEngine.ROOT_ID)
        row.add(Root.COLUMN_DOCUMENT_ID, WorkspaceDocumentsEngine.ROOT_DOCUMENT_ID)
        row.add(
            Root.COLUMN_FLAGS,
            Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
        )
        row.add(Root.COLUMN_TITLE, "TermVR")
        row.add(Root.COLUMN_SUMMARY, "Sandbox workspaces")
        row.add(Root.COLUMN_MIME_TYPES, "*/*")
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        val entry = engine.statDocument(documentId)
        appendEntryRow(cursor, entry)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        for (entry in engine.listChildren(parentDocumentId)) {
            appendEntryRow(cursor, entry)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return engine.isChildDocument(parentDocumentId, documentId)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val wantsWrite = mode.contains('w') || mode.contains('a')
        if (!wantsWrite) {
            val resolved = engine.resolveDocumentId(documentId) as? WorkspaceDocumentsEngine.ResolvedDocument.ProfileEntry
                ?: throw FileNotFoundException("Not a readable file: $documentId")
            if (resolved.relativePath.isEmpty() || resolved.targetFile.isDirectory || !resolved.targetFile.exists()) {
                throw FileNotFoundException("File not found: $documentId")
            }
            return ParcelFileDescriptor.open(resolved.targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val truncate = mode.contains('t') || !mode.contains('r')
        val tx = engine.beginWriteTransaction(documentId, truncateExisting = truncate)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        return try {
            ParcelFileDescriptor.open(tx.stagingFile, pfdMode, mainHandler) { err ->
                val cancelled = signal?.isCanceled == true
                engine.finishWriteTransaction(tx, errorOccurred = err != null || cancelled)
            }
        } catch (t: Throwable) {
            engine.finishWriteTransaction(tx, errorOccurred = true)
            throw t
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        return engine.createEntry(parentDocumentId, mimeType, displayName)
    }

    override fun deleteDocument(documentId: String) {
        engine.deleteEntry(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        return engine.renameEntry(documentId, displayName)
    }

    private fun appendEntryRow(cursor: MatrixCursor, entry: WorkspaceDocumentsEngine.WorkspaceEntry) {
        val mime = if (entry.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            guessMimeType(entry.displayName)
        }
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, entry.documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, entry.displayName)
        row.add(Document.COLUMN_MIME_TYPE, mime)
        row.add(Document.COLUMN_SIZE, entry.sizeBytes)
        row.add(Document.COLUMN_LAST_MODIFIED, entry.lastModifiedMs)
        row.add(Document.COLUMN_FLAGS, entry.flags)
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        const val AUTHORITY = "dev.gfire.termvr.documents"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
