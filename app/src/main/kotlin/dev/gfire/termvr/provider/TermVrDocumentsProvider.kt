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
import dev.gfire.termvr.util.SafUriUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong

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
    private val stagingLock = Any()
    private val activeStagingPaths = HashSet<Path>()
    private val concurrentStagingSeq = AtomicLong(1L)

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
                if (!Files.exists(resolved.targetFile.toPath(), LinkOption.NOFOLLOW_LINKS) && !resolved.targetFile.exists()) {
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
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
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
     * Deletes a confined file or directory inside a non-frozen workspace profile without following symlinks.
     */
    fun deleteEntry(documentId: String) {
        val entry = resolveProfileEntry(documentId)
        if (entry.relativePath.isEmpty()) {
            throw SecurityException("Cannot delete workspace root for ${entry.profileId}")
        }
        requireUnlocked(entry.profileId)
        val targetPath = entry.targetFile.toPath()
        if (!Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        val canonicalRoot = entry.workspaceRoot.canonicalFile.toPath()
        deleteConfinedPath(targetPath, canonicalRoot)
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
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
        val sourcePath = entry.targetFile.toPath()
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        val cleanName = sanitizeSingleSegment(newDisplayName)
        val parentRel = entry.relativePath.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val destination = resolveConfinedTarget(entry.profileId, newRel)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Destination already exists: $cleanName")
        }
        Files.move(sourcePath, destination.toPath())
        return buildDocumentId(entry.profileId, newRel)
    }

    /**
     * Prepares a confined target for atomic `.termvr.tmp` write staging while isolating concurrent writers.
     */
    fun beginWriteTransaction(documentId: String, truncateExisting: Boolean): StagedWriteTransaction {
        val entry = resolveProfileEntry(documentId)
        if (entry.relativePath.isEmpty()) {
            throw SecurityException("Cannot open workspace root directory as file")
        }
        requireUnlocked(entry.profileId)
        val targetPath = entry.targetFile.toPath()
        if (Files.isDirectory(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Cannot open directory for writing: $documentId")
        }
        val parentDir = entry.targetFile.parentFile
            ?: throw FileNotFoundException("Missing parent directory for $documentId")
        if (!parentDir.isDirectory && !parentDir.mkdirs()) {
            throw IOException("Cannot prepare parent directory for $documentId")
        }
        if (Files.isSymbolicLink(parentDir.toPath()) || !isCanonicalPathConfined(entry.workspaceRoot, parentDir)) {
            throw SecurityException("Parent directory escapes workspace root for $documentId")
        }

        val preservedPermissions = SafUriUtils.captureExistingPermissions(targetPath)
        val stagingFile = allocateStagingFile(parentDir, entry.targetFile.name)
        val stagingPath = stagingFile.toPath()
        try {
            if (!truncateExisting && Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(
                    targetPath,
                    stagingPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                Files.deleteIfExists(stagingPath)
            }
            if (!Files.exists(stagingPath, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(stagingPath)
            }
        } catch (t: Throwable) {
            releaseStagingFile(stagingFile)
            runCatching { Files.deleteIfExists(stagingPath) }
            throw t
        }
        return StagedWriteTransaction(
            profileId = entry.profileId,
            workspaceRoot = entry.workspaceRoot,
            targetFile = entry.targetFile,
            stagingFile = stagingFile,
            preservedPermissions = preservedPermissions,
        )
    }

    /**
     * Commits a staged `.termvr.tmp` write with mode preservation, `fsync`, and atomic replace.
     */
    fun finishWriteTransaction(transaction: StagedWriteTransaction, errorOccurred: Boolean) {
        val stagingPath = transaction.stagingFile.toPath()
        try {
            if (errorOccurred) {
                runCatching { Files.deleteIfExists(stagingPath) }
                return
            }
            if (isProfileWriteLocked(transaction.profileId)) {
                runCatching { Files.deleteIfExists(stagingPath) }
                throw SecurityException(
                    "Workspace became write-locked before commit for profile ${transaction.profileId}",
                )
            }
            val parentDir = transaction.targetFile.parentFile
                ?: throw IOException("Missing parent directory for ${transaction.targetFile}")
            if (
                Files.isSymbolicLink(parentDir.toPath()) ||
                !isCanonicalPathConfined(transaction.workspaceRoot, parentDir) ||
                !isCanonicalPathConfined(transaction.workspaceRoot, transaction.targetFile)
            ) {
                throw SecurityException("Workspace target escaped confinement before commit")
            }

            val effectivePermissions = SafUriUtils.captureExistingPermissions(transaction.targetFile.toPath())
                ?: transaction.preservedPermissions
            if (effectivePermissions != null) {
                SafUriUtils.applyPreservedPermissions(stagingPath, effectivePermissions)
            }

            fsyncFile(transaction.stagingFile)
            SafUriUtils.moveAtomically(stagingPath, transaction.targetFile.toPath())
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(stagingPath) }
            throw t
        } finally {
            releaseStagingFile(transaction.stagingFile)
        }
    }

    private fun allocateStagingFile(parentDir: File, targetName: String): File {
        synchronized(stagingLock) {
            val primary = File(parentDir, "$targetName$TEMP_WRITE_SUFFIX")
            val primaryKey = primary.toPath().toAbsolutePath().normalize()
            if (activeStagingPaths.add(primaryKey)) {
                return primary
            }
            while (true) {
                val seq = concurrentStagingSeq.getAndIncrement()
                val alt = File(parentDir, "$targetName.$seq$TEMP_WRITE_SUFFIX")
                val altKey = alt.toPath().toAbsolutePath().normalize()
                if (activeStagingPaths.add(altKey)) {
                    return alt
                }
            }
        }
    }

    private fun releaseStagingFile(stagingFile: File) {
        val key = stagingFile.toPath().toAbsolutePath().normalize()
        synchronized(stagingLock) {
            activeStagingPaths.remove(key)
        }
    }

    internal fun deleteConfinedPath(path: Path, canonicalRoot: Path) {
        val fileName = path.fileName?.toString().orEmpty()
        if (isReservedMetaName(fileName)) {
            throw SecurityException("Refusing to delete workspace metadata file: $fileName")
        }
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            val canonicalDir = path.toFile().canonicalFile.toPath()
            if (canonicalDir != canonicalRoot && !canonicalDir.startsWith(canonicalRoot)) {
                throw SecurityException("Directory escapes workspace root: $path")
            }
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    val childName = child.fileName?.toString().orEmpty()
                    if (isReservedMetaName(childName)) {
                        continue
                    }
                    deleteConfinedPath(child, canonicalRoot)
                }
            }
        }
        Files.deleteIfExists(path)
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
            if (containsControlChar(segment)) {
                throw SecurityException("Control character in workspace path")
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
        if (containsControlChar(displayName)) {
            throw SecurityException("Invalid document name: $displayName")
        }
        val trimmed = displayName.trim()
        if (
            trimmed.isEmpty() ||
            trimmed == "." ||
            trimmed == ".." ||
            trimmed.contains('/') ||
            trimmed.contains('\\') ||
            isHiddenName(trimmed) ||
            trimmed.toByteArray(StandardCharsets.UTF_8).size > SafUriUtils.MAX_FILENAME_BYTES
        ) {
            throw SecurityException("Invalid document name: $displayName")
        }
        return trimmed
    }

    private fun containsControlChar(value: String): Boolean {
        for (ch in value) {
            if (ch == '\u0000' || ch.code < 0x20 || ch.code == 0x7F || Character.isISOControl(ch)) {
                return true
            }
        }
        return false
    }

    private fun isCanonicalPathConfined(canonicalRoot: File, candidate: File): Boolean {
        val rootPath = canonicalRoot.canonicalFile.toPath()
        val candidateNio = candidate.toPath()
        if (Files.isSymbolicLink(candidateNio)) {
            val linkTarget = runCatching { Files.readSymbolicLink(candidateNio) }.getOrNull() ?: return false
            val parentBase = candidate.parentFile?.canonicalFile?.toPath() ?: return false
            val resolvedLink = parentBase.resolve(linkTarget).normalize()
            if (resolvedLink != rootPath && !resolvedLink.startsWith(rootPath)) {
                return false
            }
        }
        val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrNull() ?: return false
        return candidatePath == rootPath || candidatePath.startsWith(rootPath)
    }

    private fun isReservedMetaName(name: String): Boolean {
        return name.equals(HIDDEN_META_DIR, ignoreCase = true) ||
            name.equals(HIDDEN_META_TEMP_FILE, ignoreCase = true)
    }

    private fun isHiddenName(name: String): Boolean {
        return isReservedMetaName(name) || name.endsWith(TEMP_WRITE_SUFFIX, ignoreCase = true)
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

    @ConsistentCopyVisibility
    data class StagedWriteTransaction internal constructor(
        val profileId: String,
        internal val workspaceRoot: File,
        val targetFile: File,
        val stagingFile: File,
        internal val preservedPermissions: SafUriUtils.PreservedFilePermissions? = null,
    ) {
        constructor(
            profileId: String,
            targetFile: File,
            stagingFile: File,
        ) : this(
            profileId = profileId,
            workspaceRoot = targetFile.parentFile?.canonicalFile ?: targetFile.canonicalFile,
            targetFile = targetFile,
            stagingFile = stagingFile,
            preservedPermissions = null,
        )
    }

    companion object {
        const val ROOT_ID = "termvr_workspaces"
        const val ROOT_DOCUMENT_ID = "root"
        const val PROFILE_PREFIX = "profile:"
        const val ENTRY_PREFIX = "entry:"
        const val HIDDEN_META_DIR = ".gvx_meta"
        const val HIDDEN_META_TEMP_FILE = ".gvx_meta.tmp"
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
                runCatching {
                    engine.finishWriteTransaction(tx, errorOccurred = err != null || cancelled)
                }
            }
        } catch (t: Throwable) {
            runCatching {
                engine.finishWriteTransaction(tx, errorOccurred = true)
            }
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
