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
import dev.gfire.termvr.core.model.GitWorktreeSpec
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

    private val gitProjectsRootDir: File
        get() = File(filesDir, "git-projects")

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
     * Lists available shared Git project identifiers under `<filesDir>/git-projects`.
     */
    fun listGitProjectIds(): List<String> {
        val root = gitProjectsRootDir
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter {
                !Files.isSymbolicLink(it.toPath()) &&
                    it.isDirectory &&
                    GitWorktreeSpec.isValidProjectId(it.name) &&
                    File(it, "repo.git").isDirectory
            }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }

    /**
     * Lists provisioned container worktree identifiers under `<filesDir>/git-projects/<projectId>/worktrees`.
     */
    fun listGitWorktreeProfileIds(projectId: String): List<String> {
        val projDir = requireGitProjectDir(projectId)
        val wtRoot = File(projDir, "worktrees")
        if (!wtRoot.isDirectory || Files.isSymbolicLink(wtRoot.toPath())) return emptyList()
        return wtRoot.listFiles()
            ?.asSequence()
            ?.filter {
                !Files.isSymbolicLink(it.toPath()) &&
                    it.isDirectory &&
                    SandboxProfile.isValidProfileId(it.name) &&
                    !isHiddenName(it.name)
            }
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
     * Resolves a document identifier into a verified workspace or shared Git worktree target.
     */
    fun resolveDocumentId(documentId: String): ResolvedDocument {
        if (documentId == ROOT_DOCUMENT_ID) {
            return ResolvedDocument.RootDirectory
        }
        if (documentId == GIT_ROOT_DOCUMENT_ID) {
            return ResolvedDocument.GitRootDirectory
        }
        if (documentId.startsWith(GIT_PROJECT_PREFIX)) {
            val projectId = documentId.removePrefix(GIT_PROJECT_PREFIX)
            val projDir = requireGitProjectDir(projectId)
            return ResolvedDocument.GitProjectDirectory(
                projectId = projectId,
                projectDir = projDir,
            )
        }
        if (documentId.startsWith(GIT_WORKTREE_PREFIX)) {
            val remainder = documentId.removePrefix(GIT_WORKTREE_PREFIX)
            val slashIdx = remainder.indexOf('/')
            if (slashIdx <= 0 || remainder.indexOf('/', slashIdx + 1) >= 0) {
                throw SecurityException("Invalid git-worktree documentId: $documentId")
            }
            val projectId = remainder.substring(0, slashIdx)
            val profileId = remainder.substring(slashIdx + 1)
            val wtDir = requireGitWorktreeDir(projectId, profileId)
            return ResolvedDocument.ProfileEntry(
                profileId = profileId,
                relativePath = "",
                workspaceRoot = wtDir,
                targetFile = wtDir,
                writeLocked = isProfileWriteLocked(profileId),
                gitProjectId = projectId,
            )
        }
        if (documentId.startsWith(GIT_ENTRY_PREFIX)) {
            val remainder = documentId.removePrefix(GIT_ENTRY_PREFIX)
            val firstSlash = remainder.indexOf('/')
            if (firstSlash <= 0) {
                throw SecurityException("Invalid git-entry documentId: $documentId")
            }
            val secondSlash = remainder.indexOf('/', firstSlash + 1)
            val projectId = remainder.substring(0, firstSlash)
            val profileId = if (secondSlash < 0) {
                remainder.substring(firstSlash + 1)
            } else {
                remainder.substring(firstSlash + 1, secondSlash)
            }
            val relative = if (secondSlash < 0) "" else remainder.substring(secondSlash + 1)
            val wtRoot = requireGitWorktreeDir(projectId, profileId)
            val entryFile = resolveConfinedRootTarget(wtRoot, relative, forbidDotGit = true)
            return ResolvedDocument.ProfileEntry(
                profileId = profileId,
                relativePath = normalizeRelativePath(relative),
                workspaceRoot = wtRoot,
                targetFile = entryFile,
                writeLocked = isProfileWriteLocked(profileId),
                gitProjectId = projectId,
            )
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

    private fun buildEntryDocumentId(entry: ResolvedDocument.ProfileEntry, relativePath: String): String {
        val gitProj = entry.gitProjectId
        return if (gitProj != null) {
            buildWorktreeDocumentId(gitProj, entry.profileId, relativePath)
        } else {
            buildDocumentId(entry.profileId, relativePath)
        }
    }

    /**
     * Lists visible child entries inside a workspace or shared Git worktree directory.
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

            ResolvedDocument.GitRootDirectory -> {
                listGitProjectIds().map { projectId ->
                    val dir = requireGitProjectDir(projectId)
                    WorkspaceEntry(
                        documentId = "$GIT_PROJECT_PREFIX$projectId",
                        displayName = projectId,
                        isDirectory = true,
                        sizeBytes = 0L,
                        lastModifiedMs = dir.lastModified(),
                        flags = 0,
                    )
                }
            }

            is ResolvedDocument.GitProjectDirectory -> {
                listGitWorktreeProfileIds(resolved.projectId).map { profileId ->
                    val wtDir = requireGitWorktreeDir(resolved.projectId, profileId)
                    WorkspaceEntry(
                        documentId = "$GIT_WORKTREE_PREFIX${resolved.projectId}/$profileId",
                        displayName = profileId,
                        isDirectory = true,
                        sizeBytes = 0L,
                        lastModifiedMs = wtDir.lastModified(),
                        flags = computeDirectoryFlags(isProfileRoot = true, writeLocked = isProfileWriteLocked(profileId)),
                    )
                }
            }

            is ResolvedDocument.ProfileEntry -> {
                if (!resolved.targetFile.isDirectory) return emptyList()
                val canonicalRoot = resolved.workspaceRoot.canonicalFile
                val children = resolved.targetFile.listFiles() ?: return emptyList()
                val forbidDotGit = resolved.gitProjectId != null
                children.asSequence()
                    .filter { child -> !isHiddenName(child.name, forbidDotGit = forbidDotGit) }
                    .filter { child -> isCanonicalPathConfined(canonicalRoot, child) }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                    .map { child ->
                        val childRel = if (resolved.relativePath.isEmpty()) {
                            child.name
                        } else {
                            "${resolved.relativePath}/${child.name}"
                        }
                        val childDocId = buildEntryDocumentId(resolved, childRel)
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

            ResolvedDocument.GitRootDirectory -> WorkspaceEntry(
                documentId = GIT_ROOT_DOCUMENT_ID,
                displayName = "TermVR Git Worktrees",
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMs = gitProjectsRootDir.lastModified(),
                flags = 0,
            )

            is ResolvedDocument.GitProjectDirectory -> WorkspaceEntry(
                documentId = documentId,
                displayName = resolved.projectId,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedMs = resolved.projectDir.lastModified(),
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
            ResolvedDocument.RootDirectory -> child is ResolvedDocument.ProfileEntry && child.gitProjectId == null
            ResolvedDocument.GitRootDirectory -> {
                child is ResolvedDocument.GitProjectDirectory ||
                    (child is ResolvedDocument.ProfileEntry && child.gitProjectId != null)
            }
            is ResolvedDocument.GitProjectDirectory -> {
                child is ResolvedDocument.ProfileEntry && child.gitProjectId == parent.projectId
            }
            is ResolvedDocument.ProfileEntry -> {
                if (
                    child !is ResolvedDocument.ProfileEntry ||
                    parent.gitProjectId != child.gitProjectId ||
                    parent.profileId != child.profileId
                ) {
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
        val forbidDotGit = parent.gitProjectId != null
        val cleanName = sanitizeSingleSegment(displayName, forbidDotGit = forbidDotGit)
        val newRel = if (parent.relativePath.isEmpty()) cleanName else "${parent.relativePath}/$cleanName"
        val target = resolveConfinedRootTarget(parent.workspaceRoot, newRel, forbidDotGit = forbidDotGit)
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
        return buildEntryDocumentId(parent, newRel)
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
        deleteConfinedPath(targetPath, canonicalRoot, forbidDotGit = entry.gitProjectId != null)
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
        val forbidDotGit = entry.gitProjectId != null
        val cleanName = sanitizeSingleSegment(newDisplayName, forbidDotGit = forbidDotGit)
        val parentRel = entry.relativePath.substringBeforeLast('/', "")
        val newRel = if (parentRel.isEmpty()) cleanName else "$parentRel/$cleanName"
        val destination = resolveConfinedRootTarget(entry.workspaceRoot, newRel, forbidDotGit = forbidDotGit)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Destination already exists: $cleanName")
        }
        Files.move(sourcePath, destination.toPath())
        return buildEntryDocumentId(entry, newRel)
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
            val primaryName = SafUriUtils.buildBoundedStagingFileName(targetName = targetName)
            val primary = File(parentDir, primaryName)
            val primaryKey = primary.toPath().toAbsolutePath().normalize()
            if (activeStagingPaths.add(primaryKey)) {
                return primary
            }
            while (true) {
                val seq = concurrentStagingSeq.getAndIncrement()
                val altName = SafUriUtils.buildBoundedStagingFileName(
                    targetName = targetName,
                    seq = seq,
                )
                val alt = File(parentDir, altName)
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

    internal fun deleteConfinedPath(path: Path, canonicalRoot: Path, forbidDotGit: Boolean = false) {
        val fileName = path.fileName?.toString().orEmpty()
        if (isReservedMetaName(fileName, forbidDotGit = forbidDotGit)) {
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
                    if (isReservedMetaName(childName, forbidDotGit = forbidDotGit)) {
                        continue
                    }
                    deleteConfinedPath(child, canonicalRoot, forbidDotGit = forbidDotGit)
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

    private fun validateGitProjectId(projectId: String): String {
        if (!GitWorktreeSpec.isValidProjectId(projectId) || isHiddenName(projectId)) {
            throw SecurityException("Invalid Git project id: $projectId")
        }
        return projectId
    }

    private fun requireProfileWorkspaceDir(profileId: String): File {
        val validId = validateProfileId(profileId)
        val root = File(workspacesRootDir, validId)
        if (!root.exists() || Files.isSymbolicLink(root.toPath())) {
            throw FileNotFoundException("Workspace does not exist for profile $validId")
        }
        return root.canonicalFile
    }

    private fun requireGitProjectDir(projectId: String): File {
        val validProject = validateGitProjectId(projectId)
        val root = gitProjectsRootDir
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw FileNotFoundException("Git projects root does not exist")
        }
        val projDir = File(root, validProject)
        if (Files.isSymbolicLink(projDir.toPath())) {
            throw SecurityException("Git project path is a symbolic link: $validProject")
        }
        if (!projDir.isDirectory || !isCanonicalPathConfined(root.canonicalFile, projDir)) {
            throw FileNotFoundException("Git project does not exist: $validProject")
        }
        return projDir.canonicalFile
    }

    private fun requireGitWorktreeDir(projectId: String, profileId: String): File {
        val projDir = requireGitProjectDir(projectId)
        val validProfile = validateProfileId(profileId)
        val worktreesDir = File(projDir, "worktrees")
        if (Files.isSymbolicLink(worktreesDir.toPath()) || !worktreesDir.isDirectory) {
            throw FileNotFoundException("Git worktrees directory does not exist for $projectId")
        }
        val wtDir = File(worktreesDir, validProfile)
        if (Files.isSymbolicLink(wtDir.toPath())) {
            throw SecurityException("Git worktree path is a symbolic link: $projectId/$validProfile")
        }
        if (!wtDir.isDirectory || !isCanonicalPathConfined(projDir, wtDir)) {
            throw FileNotFoundException("Git worktree does not exist: $projectId/$validProfile")
        }
        return wtDir.canonicalFile
    }

    private fun resolveConfinedTarget(profileId: String, rawRelativePath: String): File {
        val workspaceRoot = requireProfileWorkspaceDir(profileId)
        return resolveConfinedRootTarget(workspaceRoot, rawRelativePath, forbidDotGit = false)
    }

    private fun resolveConfinedRootTarget(
        workspaceRoot: File,
        rawRelativePath: String,
        forbidDotGit: Boolean,
    ): File {
        val normalized = normalizeRelativePath(rawRelativePath)
        if (normalized.isEmpty()) return workspaceRoot

        val segments = normalized.split('/')
        var cursor = workspaceRoot
        for (segment in segments) {
            if (segment.isEmpty() || segment == "." || segment == ".." || isHiddenName(segment, forbidDotGit = forbidDotGit)) {
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

    private fun normalizeRelativePath(raw: String): String = normalizeCleanRelativePath(raw)

    private fun sanitizeSingleSegment(displayName: String, forbidDotGit: Boolean = false): String {
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
            isHiddenName(trimmed, forbidDotGit = forbidDotGit) ||
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

    private fun isReservedMetaName(name: String, forbidDotGit: Boolean = false): Boolean {
        return name.equals(HIDDEN_META_DIR, ignoreCase = true) ||
            name.equals(HIDDEN_META_LOCK_FILE, ignoreCase = true) ||
            name.equals(HIDDEN_META_TEMP_FILE, ignoreCase = true) ||
            name.startsWith("$HIDDEN_META_TEMP_FILE.", ignoreCase = true) ||
            (forbidDotGit && name.equals(HIDDEN_GIT_LINK_FILE, ignoreCase = true))
    }

    private fun isHiddenName(name: String, forbidDotGit: Boolean = false): Boolean {
        return isReservedMetaName(name, forbidDotGit = forbidDotGit) ||
            name.endsWith(TEMP_WRITE_SUFFIX, ignoreCase = true)
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
        data object GitRootDirectory : ResolvedDocument
        data class GitProjectDirectory(
            val projectId: String,
            val projectDir: File,
        ) : ResolvedDocument
        data class ProfileEntry(
            val profileId: String,
            val relativePath: String,
            val workspaceRoot: File,
            val targetFile: File,
            val writeLocked: Boolean,
            val gitProjectId: String? = null,
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

    data class ParsedOpenMode(
        val wantsWrite: Boolean,
        val truncateExisting: Boolean,
        val append: Boolean,
    )

    companion object {
        const val ROOT_ID = "termvr_workspaces"
        const val ROOT_DOCUMENT_ID = "root"
        const val PROFILE_PREFIX = "profile:"
        const val ENTRY_PREFIX = "entry:"
        const val GIT_ROOT_ID = "termvr_git_worktrees"
        const val GIT_ROOT_DOCUMENT_ID = "git-root"
        const val GIT_PROJECT_PREFIX = "git-project:"
        const val GIT_WORKTREE_PREFIX = "git-worktree:"
        const val GIT_ENTRY_PREFIX = "git-entry:"
        const val HIDDEN_META_DIR = ".gvx_meta"
        const val HIDDEN_META_LOCK_FILE = ".gvx_meta.lock"
        const val HIDDEN_META_TEMP_FILE = ".gvx_meta.tmp"
        const val HIDDEN_GIT_LINK_FILE = ".git"
        const val TEMP_WRITE_SUFFIX = ".termvr.tmp"

        fun buildWorktreeDocumentId(projectId: String, profileId: String, relativePath: String): String {
            if (!GitWorktreeSpec.isValidProjectId(projectId)) {
                throw SecurityException("Invalid Git project id: $projectId")
            }
            if (!SandboxProfile.isValidProfileId(profileId)) {
                throw SecurityException("Invalid workspace profile id: $profileId")
            }
            val cleanRel = normalizeCleanRelativePath(relativePath)
            return if (cleanRel.isEmpty()) {
                "$GIT_WORKTREE_PREFIX$projectId/$profileId"
            } else {
                "$GIT_ENTRY_PREFIX$projectId/$profileId/$cleanRel"
            }
        }

        private fun normalizeCleanRelativePath(raw: String): String {
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

        fun parseOpenMode(mode: String): ParsedOpenMode = when (mode) {
            "r" -> ParsedOpenMode(wantsWrite = false, truncateExisting = false, append = false)
            "w", "wt" -> ParsedOpenMode(wantsWrite = true, truncateExisting = true, append = false)
            "wa" -> ParsedOpenMode(wantsWrite = true, truncateExisting = false, append = true)
            "rw" -> ParsedOpenMode(wantsWrite = true, truncateExisting = false, append = false)
            "rwt" -> ParsedOpenMode(wantsWrite = true, truncateExisting = true, append = false)
            else -> throw IllegalArgumentException("Unsupported document open mode: $mode")
        }
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
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceDocumentsEngine.ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, WorkspaceDocumentsEngine.ROOT_DOCUMENT_ID)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_TITLE, "TermVR Workspaces")
            add(Root.COLUMN_SUMMARY, "Sandbox workspaces")
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, WorkspaceDocumentsEngine.GIT_ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, WorkspaceDocumentsEngine.GIT_ROOT_DOCUMENT_ID)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_TITLE, "TermVR Git Worktrees")
            add(Root.COLUMN_SUMMARY, "Shared Git worktrees")
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
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
        val parsedMode = WorkspaceDocumentsEngine.parseOpenMode(mode)
        val pfdMode = ParcelFileDescriptor.parseMode(mode)
        if (!parsedMode.wantsWrite) {
            val resolved = engine.resolveDocumentId(documentId) as? WorkspaceDocumentsEngine.ResolvedDocument.ProfileEntry
                ?: throw FileNotFoundException("Not a readable file: $documentId")
            if (resolved.relativePath.isEmpty() || resolved.targetFile.isDirectory || !resolved.targetFile.exists()) {
                throw FileNotFoundException("File not found: $documentId")
            }
            return ParcelFileDescriptor.open(resolved.targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val truncate = parsedMode.truncateExisting && (pfdMode and ParcelFileDescriptor.MODE_TRUNCATE) != 0
        val tx = engine.beginWriteTransaction(documentId, truncateExisting = truncate)
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
