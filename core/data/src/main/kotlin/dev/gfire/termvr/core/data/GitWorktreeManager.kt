package dev.gfire.termvr.core.data

import dev.gfire.termvr.core.model.GitProjectSummary
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.SandboxProfile
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Describes one linked container `git worktree` attached to a shared bare Git project.
 */
data class GitLinkedWorktree(
    val projectId: String,
    val profileId: String,
    val branchName: String,
    val worktreeDir: File,
)

@Serializable
private data class GitProjectMetadata(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val defaultBranch: String = GitProjectSummary.DEFAULT_BRANCH,
    val createdAtEpochMs: Long,
)

/**
 * Provisions pure-Kotlin POSIX bare Git repositories and per-container linked `git worktree` directories.
 */
class GitWorktreeManager(
    private val filesDir: File,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    val gitProjectsRootDir: File
        get() = File(filesDir, GIT_PROJECTS_DIR_NAME)

    fun projectDir(projectId: String): File {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        return File(gitProjectsRootDir, projectId)
    }

    fun repoGitDir(projectId: String): File = File(projectDir(projectId), REPO_GIT_DIR_NAME)

    fun worktreesRootDir(projectId: String): File = File(projectDir(projectId), WORKTREES_DIR_NAME)

    fun containerWorktreeDir(projectId: String, profileId: String): File {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        require(SandboxProfile.isValidProfileId(profileId)) {
            "Invalid SandboxProfile id: '$profileId'"
        }
        return File(worktreesRootDir(projectId), profileId)
    }

    /**
     * Creates or ensures a POSIX bare Git repository under `<filesDir>/git-projects/<projectId>/repo.git`.
     */
    fun createOrEnsureProject(
        projectId: String,
        name: String? = null,
        defaultBranch: String = GitProjectSummary.DEFAULT_BRANCH,
        overwriteExistingName: Boolean = false,
    ): GitProjectSummary = synchronized(lock) {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        require(name == null || name.isNotBlank()) { "Git project name must not be blank" }
        require(GitWorktreeSpec.isValidBranchName(defaultBranch)) {
            "Invalid Git defaultBranch: '$defaultBranch'"
        }

        val rootDir = ensureSafeDirectory(gitProjectsRootDir, filesDir)
        val projDir = ensureSafeDirectory(File(rootDir, projectId), rootDir)
        val repoDir = ensureSafeDirectory(File(projDir, REPO_GIT_DIR_NAME), projDir)
        ensureSafeDirectory(File(projDir, WORKTREES_DIR_NAME), projDir)

        ensureSafeDirectory(File(repoDir, "objects"), repoDir)
        ensureSafeDirectory(File(repoDir, "objects/info"), repoDir)
        ensureSafeDirectory(File(repoDir, "objects/pack"), repoDir)
        ensureSafeDirectory(File(repoDir, "refs"), repoDir)
        ensureSafeDirectory(File(repoDir, "refs/heads"), repoDir)
        ensureSafeDirectory(File(repoDir, "refs/tags"), repoDir)
        ensureSafeDirectory(File(repoDir, WORKTREES_DIR_NAME), repoDir)

        val headFile = File(repoDir, "HEAD")
        if (!headFile.exists()) {
            writeAtomicText(headFile, "ref: refs/heads/$defaultBranch\n")
        }
        val configFile = File(repoDir, "config")
        if (!configFile.exists()) {
            writeAtomicText(configFile, BARE_GIT_CONFIG)
        }

        val metaFile = File(projDir, PROJECT_METADATA_FILE_NAME)
        val existingMeta = readMetadataOrNull(metaFile)
        val requestedName = name?.trim()
        val effectiveMeta = if (existingMeta != null) {
            if (overwriteExistingName && requestedName != null && existingMeta.name != requestedName) {
                existingMeta.copy(name = requestedName).also { updated ->
                    writeAtomicText(metaFile, json.encodeToString(updated) + "\n")
                }
            } else {
                existingMeta
            }
        } else {
            GitProjectMetadata(
                id = projectId,
                name = requestedName ?: projectId,
                defaultBranch = defaultBranch,
                createdAtEpochMs = clockMillis().coerceAtLeast(0L),
            ).also { created ->
                writeAtomicText(metaFile, json.encodeToString(created) + "\n")
            }
        }

        buildProjectSummary(projDir, repoDir, effectiveMeta)
    }

    /**
     * Lists all shared Git projects sorted by project identifier.
     */
    fun listProjects(): List<GitProjectSummary> = synchronized(lock) {
        val root = gitProjectsRootDir
        if (!root.exists() || Files.isSymbolicLink(root.toPath()) || !root.isDirectory) {
            return emptyList()
        }
        val dirs = root.listFiles() ?: return emptyList()
        dirs.asSequence()
            .filter { dir ->
                !Files.isSymbolicLink(dir.toPath()) &&
                    dir.isDirectory &&
                    GitWorktreeSpec.isValidProjectId(dir.name) &&
                    File(dir, REPO_GIT_DIR_NAME).isDirectory
            }
            .sortedBy { it.name.lowercase() }
            .map { projDir ->
                val repoDir = File(projDir, REPO_GIT_DIR_NAME)
                val meta = readMetadataOrNull(File(projDir, PROJECT_METADATA_FILE_NAME))
                    ?: GitProjectMetadata(
                        id = projDir.name,
                        name = projDir.name,
                        defaultBranch = inferDefaultBranch(repoDir),
                        createdAtEpochMs = projDir.lastModified().coerceAtLeast(0L),
                    )
                buildProjectSummary(projDir, repoDir, meta)
            }
            .toList()
    }

    /**
     * Lists linked container worktrees registered inside `<repo.git>/worktrees/`.
     */
    fun listWorktrees(projectId: String): List<GitLinkedWorktree> = synchronized(lock) {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        val projDir = projectDir(projectId)
        val repoWorktrees = File(projDir, "$REPO_GIT_DIR_NAME/$WORKTREES_DIR_NAME")
        if (!repoWorktrees.isDirectory || Files.isSymbolicLink(repoWorktrees.toPath())) {
            return emptyList()
        }
        val entries = repoWorktrees.listFiles() ?: return emptyList()
        entries.asSequence()
            .filter { entry ->
                !Files.isSymbolicLink(entry.toPath()) &&
                    entry.isDirectory &&
                    SandboxProfile.isValidProfileId(entry.name)
            }
            .sortedBy { it.name }
            .mapNotNull { entry ->
                val headFile = File(entry, "HEAD")
                if (Files.isSymbolicLink(headFile.toPath()) || !headFile.isFile) {
                    return@mapNotNull null
                }
                val headRaw = runCatching { headFile.readText(StandardCharsets.UTF_8).trim() }.getOrNull()
                    ?: return@mapNotNull null
                val branch = headRaw.removePrefix(REFS_HEADS_PREFIX).takeIf { headRaw.startsWith(REFS_HEADS_PREFIX) }
                    ?: headRaw
                GitLinkedWorktree(
                    projectId = projectId,
                    profileId = entry.name,
                    branchName = branch,
                    worktreeDir = File(projDir, "$WORKTREES_DIR_NAME/${entry.name}"),
                )
            }
            .toList()
    }

    /**
     * Checks without mutating disk whether [profileId]'s worktree and gitdir links already exist.
     */
    fun isWorktreeProvisioned(
        profileId: String,
        spec: GitWorktreeSpec,
    ): Boolean = synchronized(lock) {
        if (!SandboxProfile.isValidProfileId(profileId)) return false
        val wtDir = containerWorktreeDir(spec.projectId, profileId)
        if (!wtDir.isDirectory || Files.isSymbolicLink(wtDir.toPath())) return false
        val dotGitFile = File(wtDir, ".git")
        if (!dotGitFile.isFile || Files.isSymbolicLink(dotGitFile.toPath())) return false
        val expectedGitdirLine = "gitdir: /git/${spec.projectId}.git/worktrees/$profileId\n"
        val currentDotGit = runCatching { dotGitFile.readText(StandardCharsets.UTF_8) }.getOrNull()
        if (currentDotGit != expectedGitdirLine) return false

        val wtMetaDir = File(repoGitDir(spec.projectId), "$WORKTREES_DIR_NAME/$profileId")
        if (!wtMetaDir.isDirectory || Files.isSymbolicLink(wtMetaDir.toPath())) return false
        for (metaName in listOf("HEAD", "gitdir", "commondir")) {
            val metaFile = File(wtMetaDir, metaName)
            if (!metaFile.isFile || Files.isSymbolicLink(metaFile.toPath())) return false
        }
        true
    }

    /**
     * Provisions `<filesDir>/git-projects/<projectId>/worktrees/<profileId>` and `<repo.git>/worktrees/<profileId>`.
     */
    fun provisionWorktreeForContainer(
        profileId: String,
        spec: GitWorktreeSpec,
    ): File = synchronized(lock) {
        require(SandboxProfile.isValidProfileId(profileId)) {
            "SandboxProfile id must match [a-z0-9-]{3,32}, found '$profileId'"
        }
        val summary = createOrEnsureProject(
            projectId = spec.projectId,
        )
        val projDir = projectDir(spec.projectId)
        val repoDir = repoGitDir(spec.projectId)
        val repoWorktreesRoot = ensureSafeDirectory(File(repoDir, WORKTREES_DIR_NAME), repoDir)
        val hostWorktreesRoot = ensureSafeDirectory(worktreesRootDir(spec.projectId), projDir)

        val desiredHeadLine = "$REFS_HEADS_PREFIX${spec.branchName}"
        val existingEntries = repoWorktreesRoot.listFiles().orEmpty()
        for (otherDir in existingEntries) {
            if (Files.isSymbolicLink(otherDir.toPath()) || !otherDir.isDirectory || otherDir.name == profileId) continue
            val otherHeadFile = File(otherDir, "HEAD")
            if (Files.isSymbolicLink(otherHeadFile.toPath()) || !otherHeadFile.isFile) continue
            val otherHead = otherHeadFile.readText(StandardCharsets.UTF_8).trim()
            check(otherHead != desiredHeadLine) {
                "Branch '${spec.branchName}' is already checked out by container '${otherDir.name}' in project '${spec.projectId}'."
            }
        }

        val targetRefName = "refs/heads/${spec.branchName}"
        if (resolveRefSha(repoDir, targetRefName) == null) {
            val seedSha = resolveSeedCommitSha(repoDir, summary.defaultBranch)
            if (seedSha != null) {
                writeLooseBranchRef(repoDir, spec.branchName, seedSha)
            }
        }

        val wtMetaDir = ensureSafeDirectory(File(repoWorktreesRoot, profileId), repoWorktreesRoot)
        writeAtomicTextIfChanged(File(wtMetaDir, "commondir"), "../..\n")
        writeAtomicTextIfChanged(File(wtMetaDir, "gitdir"), "/workspace/.git\n")
        writeAtomicTextIfChanged(File(wtMetaDir, "HEAD"), "$desiredHeadLine\n")

        val wtDir = ensureSafeDirectory(File(hostWorktreesRoot, profileId), hostWorktreesRoot)
        val dotGitFile = File(wtDir, ".git")
        if (Files.isSymbolicLink(dotGitFile.toPath())) {
            throw SecurityException("Worktree .git must not be a symbolic link: ${dotGitFile.absolutePath}")
        }
        check(!dotGitFile.isDirectory) {
            "Worktree path ${wtDir.absolutePath} already contains a standalone .git directory."
        }
        val expectedGitdirLine = "gitdir: /git/${spec.projectId}.git/worktrees/$profileId\n"
        writeAtomicTextIfChanged(dotGitFile, expectedGitdirLine)
        wtDir
    }

    /**
     * Detaches a container's linked worktree registration from `<repo.git>/worktrees/<profileId>`.
     */
    fun detachWorktree(
        projectId: String,
        profileId: String,
        deleteWorktreeFiles: Boolean = false,
    ) = synchronized(lock) {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        require(SandboxProfile.isValidProfileId(profileId)) {
            "Invalid SandboxProfile id: '$profileId'"
        }
        val projDir = projectDir(projectId)
        if (!projDir.exists()) return
        val canonicalProj = projDir.canonicalFile.toPath()

        val wtMetaDir = File(repoGitDir(projectId), "$WORKTREES_DIR_NAME/$profileId")
        if (Files.exists(wtMetaDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            deleteConfinedTree(wtMetaDir.toPath(), canonicalProj)
        }

        val wtDir = containerWorktreeDir(projectId, profileId)
        if (deleteWorktreeFiles) {
            if (Files.exists(wtDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                deleteConfinedTree(wtDir.toPath(), canonicalProj)
            }
        } else {
            val dotGit = File(wtDir, ".git")
            if (dotGit.isFile && !Files.isSymbolicLink(dotGit.toPath())) {
                Files.deleteIfExists(dotGit.toPath())
            }
        }
    }

    /**
     * Deletes a shared Git project when zero container profiles or worktrees remain attached.
     */
    fun deleteProject(
        projectId: String,
        attachedProfileIds: Set<String> = emptySet(),
    ) = synchronized(lock) {
        require(GitWorktreeSpec.isValidProjectId(projectId)) {
            "Invalid Git projectId: '$projectId'"
        }
        check(attachedProfileIds.isEmpty()) {
            "Cannot delete Git project '$projectId' while attached to containers: ${attachedProfileIds.sorted().joinToString(", ")}"
        }
        val linked = listWorktrees(projectId)
        check(linked.isEmpty()) {
            "Cannot delete Git project '$projectId' while worktrees remain attached: ${linked.joinToString(", ") { it.profileId }}"
        }
        val projDir = projectDir(projectId)
        if (!Files.exists(projDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        if (Files.isSymbolicLink(projDir.toPath())) {
            throw SecurityException("Refusing to follow symbolic link on project delete: ${projDir.absolutePath}")
        }
        val canonicalProj = projDir.canonicalFile.toPath()
        deleteConfinedTree(projDir.toPath(), canonicalProj)
    }

    private fun resolveSeedCommitSha(repoDir: File, defaultBranch: String): String? {
        val headFile = File(repoDir, "HEAD")
        if (headFile.isFile && !Files.isSymbolicLink(headFile.toPath())) {
            val rawHead = headFile.readText(StandardCharsets.UTF_8).trim()
            val fromHead = resolveSymbolicOrDirectSha(repoDir, rawHead, depth = 0)
            if (fromHead != null) return fromHead
        }
        return resolveRefSha(repoDir, "refs/heads/$defaultBranch")
    }

    private fun resolveSymbolicOrDirectSha(repoDir: File, rawValue: String, depth: Int): String? {
        if (depth > 8) return null
        if (HEX_SHA1_REGEX.matches(rawValue)) {
            return rawValue.lowercase()
        }
        if (rawValue.startsWith("ref:")) {
            val refTarget = rawValue.removePrefix("ref:").trim()
            if (refTarget.isNotEmpty() && !refTarget.contains("..")) {
                return resolveRefSha(repoDir, refTarget, depth + 1)
            }
        }
        return null
    }

    private fun resolveRefSha(repoDir: File, refPath: String, depth: Int = 0): String? {
        if (refPath.contains("..") || refPath.startsWith('/')) return null
        val looseFile = File(repoDir, refPath)
        val canonicalRefsRoot = repoDir.canonicalFile.toPath()
        if (looseFile.isFile && !Files.isSymbolicLink(looseFile.toPath())) {
            val canonicalLoose = runCatching { looseFile.canonicalFile.toPath() }.getOrNull()
            if (canonicalLoose != null && canonicalLoose.startsWith(canonicalRefsRoot)) {
                val raw = looseFile.readText(StandardCharsets.UTF_8).trim()
                val resolved = resolveSymbolicOrDirectSha(repoDir, raw, depth)
                if (resolved != null) return resolved
            }
        }
        return lookupPackedRefSha(repoDir, refPath)
    }

    private fun lookupPackedRefSha(repoDir: File, targetRef: String): String? {
        val packedFile = File(repoDir, "packed-refs")
        if (!packedFile.isFile || Files.isSymbolicLink(packedFile.toPath())) {
            return null
        }
        packedFile.useLines(StandardCharsets.UTF_8) { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith('#') || line.startsWith('^')) continue
                val spaceIdx = line.indexOf(' ')
                if (spaceIdx <= 0) continue
                val sha = line.substring(0, spaceIdx).trim()
                val refName = line.substring(spaceIdx + 1).trim()
                if (refName == targetRef && HEX_SHA1_REGEX.matches(sha)) {
                    return sha.lowercase()
                }
            }
        }
        return null
    }

    private fun writeLooseBranchRef(repoDir: File, branchName: String, commitSha: String) {
        val refsHeadsRoot = ensureSafeDirectory(File(repoDir, "refs/heads"), repoDir)
        val canonicalHeadsRoot = refsHeadsRoot.canonicalFile.toPath()
        val segments = branchName.split('/')
        var cursor = refsHeadsRoot
        for (i in 0 until segments.size - 1) {
            val seg = segments[i]
            val nextDir = File(cursor, seg)
            if (nextDir.isFile) {
                error("Cannot create branch '$branchName' because '${segments.take(i + 1).joinToString("/")}' already exists as a branch ref.")
            }
            cursor = ensureSafeDirectory(nextDir, refsHeadsRoot)
        }
        val leafFile = File(cursor, segments.last())
        check(!leafFile.isDirectory) {
            "Cannot create branch '$branchName' because directory '${leafFile.name}' already exists under refs/heads."
        }
        val canonicalParent = cursor.canonicalFile.toPath()
        if (canonicalParent != canonicalHeadsRoot && !canonicalParent.startsWith(canonicalHeadsRoot)) {
            throw SecurityException("Branch ref path escaped refs/heads: $branchName")
        }
        writeAtomicText(leafFile, "${commitSha.lowercase()}\n")
    }

    private fun buildProjectSummary(
        projDir: File,
        repoDir: File,
        meta: GitProjectMetadata,
    ): GitProjectSummary {
        val wtRoot = File(repoDir, WORKTREES_DIR_NAME)
        val wtCount = wtRoot.listFiles()
            ?.count { !Files.isSymbolicLink(it.toPath()) && it.isDirectory && SandboxProfile.isValidProfileId(it.name) }
            ?: 0
        val bytes = computeConfinedDirectoryBytes(projDir.toPath(), projDir.canonicalFile.toPath())
        return GitProjectSummary(
            id = meta.id,
            name = meta.name,
            defaultBranch = meta.defaultBranch,
            createdAtEpochMs = meta.createdAtEpochMs,
            worktreeCount = wtCount,
            diskBytes = bytes,
        )
    }

    private fun inferDefaultBranch(repoDir: File): String {
        val headFile = File(repoDir, "HEAD")
        if (headFile.isFile && !Files.isSymbolicLink(headFile.toPath())) {
            val raw = headFile.readText(StandardCharsets.UTF_8).trim()
            if (raw.startsWith(REFS_HEADS_PREFIX)) {
                val candidate = raw.removePrefix(REFS_HEADS_PREFIX)
                if (GitWorktreeSpec.isValidBranchName(candidate)) {
                    return candidate
                }
            }
        }
        return GitProjectSummary.DEFAULT_BRANCH
    }

    private fun readMetadataOrNull(metaFile: File): GitProjectMetadata? {
        if (!metaFile.isFile || Files.isSymbolicLink(metaFile.toPath())) return null
        return runCatching {
            json.decodeFromString<GitProjectMetadata>(metaFile.readText(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private fun ensureSafeDirectory(target: File, confinementRoot: File): File {
        if (!confinementRoot.exists()) {
            confinementRoot.mkdirs()
        }
        if (Files.isSymbolicLink(target.toPath())) {
            throw SecurityException("Symbolic link rejected on Git storage path: ${target.absolutePath}")
        }
        if (!target.exists() && !target.mkdirs() && !target.isDirectory) {
            error("Failed to create Git directory: ${target.absolutePath}")
        }
        if (!target.isDirectory) {
            throw SecurityException("Expected directory at Git storage path: ${target.absolutePath}")
        }
        val canonicalRoot = confinementRoot.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        if (canonicalTarget != canonicalRoot && !canonicalTarget.startsWith(canonicalRoot)) {
            throw SecurityException("Git storage path escaped confinement root: ${target.absolutePath}")
        }
        return target
    }

    private fun writeAtomicTextIfChanged(target: File, content: String) {
        if (Files.isSymbolicLink(target.toPath())) {
            throw SecurityException("Refusing to write through symbolic link: ${target.absolutePath}")
        }
        if (target.isFile) {
            val existing = runCatching { target.readText(StandardCharsets.UTF_8) }.getOrNull()
            if (existing == content) return
        }
        writeAtomicText(target, content)
    }

    private fun writeAtomicText(target: File, content: String) {
        val parent = target.parentFile ?: error("Missing parent directory for ${target.absolutePath}")
        if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(target.toPath())) {
            throw SecurityException("Refusing to write through symbolic link: ${target.absolutePath}")
        }
        val tmp = File(parent, ".${target.name}.termvr.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun computeConfinedDirectoryBytes(path: Path, canonicalRoot: Path): Long {
        if (Files.isSymbolicLink(path)) return 0L
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { Files.size(path) }.getOrDefault(0L)
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        val canonicalDir = runCatching { path.toFile().canonicalFile.toPath() }.getOrNull() ?: return 0L
        if (canonicalDir != canonicalRoot && !canonicalDir.startsWith(canonicalRoot)) return 0L
        var sum = 0L
        Files.newDirectoryStream(path).use { stream ->
            for (child in stream) {
                sum += computeConfinedDirectoryBytes(child, canonicalRoot)
            }
        }
        return sum
    }

    private fun deleteConfinedTree(path: Path, canonicalRoot: Path) {
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            val canonicalDir = path.toFile().canonicalFile.toPath()
            if (canonicalDir != canonicalRoot && !canonicalDir.startsWith(canonicalRoot)) {
                throw SecurityException("Refusing to delete path outside Git project root: $path")
            }
            Files.newDirectoryStream(path).use { stream ->
                for (child in stream) {
                    deleteConfinedTree(child, canonicalRoot)
                }
            }
        }
        Files.deleteIfExists(path)
    }

    companion object {
        const val GIT_PROJECTS_DIR_NAME: String = "git-projects"
        const val REPO_GIT_DIR_NAME: String = "repo.git"
        const val WORKTREES_DIR_NAME: String = "worktrees"
        const val PROJECT_METADATA_FILE_NAME: String = "project.json"
        private const val REFS_HEADS_PREFIX: String = "ref: refs/heads/"
        private val HEX_SHA1_REGEX = Regex("^[0-9a-fA-F]{40}$")

        private const val BARE_GIT_CONFIG: String =
            "[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = true\n"
    }
}
