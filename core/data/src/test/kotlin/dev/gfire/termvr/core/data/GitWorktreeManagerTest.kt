package dev.gfire.termvr.core.data

import dev.gfire.termvr.core.model.GitWorktreeSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorktreeManagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `INV-G1 createOrEnsureProject initializes valid POSIX bare repo layout and preserves idempotent metadata`() {
        // Property: createOrEnsureProject creates <filesDir>/git-projects/<id>/repo.git with HEAD, bare config,
        // objects/{info,pack}, refs/{heads,tags}, worktrees, and project.json metadata outside repo.git.
        // Seam: GitWorktreeManager.createOrEnsureProject, listProjects.
        // Oracle: POSIX Git repository-layout(5) and Phase 3 specification.
        // Fault detected: Missing bare=true config, missing objects/pack or worktrees dir, or clobbering createdAtEpochMs.
        var now = 1763350000000L
        val manager = GitWorktreeManager(filesDir = tempDir, clockMillis = { now })

        val created = manager.createOrEnsureProject(
            projectId = "core-repo",
            name = "Core Repo",
            defaultBranch = "main",
        )
        assertEquals("core-repo", created.id)
        assertEquals("Core Repo", created.name)
        assertEquals("main", created.defaultBranch)
        assertEquals(1763350000000L, created.createdAtEpochMs)
        assertEquals(0, created.worktreeCount)

        val repoGit = File(tempDir, "git-projects/core-repo/repo.git")
        assertTrue(repoGit.isDirectory)
        assertEquals("ref: refs/heads/main\n", File(repoGit, "HEAD").readText())
        val configText = File(repoGit, "config").readText()
        assertTrue(configText.contains("repositoryformatversion = 0"))
        assertTrue(configText.contains("filemode = true"))
        assertTrue(configText.contains("bare = true"))
        assertTrue(File(repoGit, "objects/info").isDirectory)
        assertTrue(File(repoGit, "objects/pack").isDirectory)
        assertTrue(File(repoGit, "refs/heads").isDirectory)
        assertTrue(File(repoGit, "refs/tags").isDirectory)
        assertTrue(File(repoGit, "worktrees").isDirectory)
        assertTrue(File(tempDir, "git-projects/core-repo/project.json").isFile)
        assertFalse(File(repoGit, "project.json").exists())

        now += 60_000L
        val ensured = manager.createOrEnsureProject(
            projectId = "core-repo",
        )
        assertEquals(1763350000000L, ensured.createdAtEpochMs)
        assertEquals("Core Repo", ensured.name)

        // Provisioning a container worktree must also preserve the existing human display name!
        manager.provisionWorktreeForContainer(
            profileId = "dev-one",
            spec = GitWorktreeSpec(projectId = "core-repo", branchName = "wt/dev-one"),
        )
        val afterProvision = manager.listProjects().single()
        assertEquals("core-repo", afterProvision.id)
        assertEquals("Core Repo", afterProvision.name)
    }

    @Test
    fun `INV-G2 provisionWorktreeForContainer writes guest gitdir links enforces branch collision guard and supports detach and delete`() {
        // Property: Provisioning creates worktrees/<profileId>/.git -> /git/<projectId>.git/worktrees/<profileId>,
        // repo.git/worktrees/<profileId>/{commondir,gitdir,HEAD}, rejects checkout of an already-checked-out branch
        // by a second profile while allowing idempotent re-provision by the same profile, and blocks deleteProject while attached.
        // Seam: GitWorktreeManager.provisionWorktreeForContainer, detachWorktree, deleteProject, listProjects.
        // Oracle: git-worktree(1) linked worktree layout and Phase 3 specification.
        // Fault detected: Host path written into .git instead of /git/<id>.git/..., two containers corrupting one branch index/HEAD,
        // or deleting a bare repo while containers remain attached.
        val manager = GitWorktreeManager(filesDir = tempDir)
        manager.createOrEnsureProject("shared-api", "Shared API", "main")

        val wt1 = manager.provisionWorktreeForContainer(
            profileId = "agent-alpha",
            spec = GitWorktreeSpec(projectId = "shared-api", branchName = "wt/alpha"),
        )
        assertEquals(
            File(tempDir, "git-projects/shared-api/worktrees/agent-alpha").canonicalPath,
            wt1.canonicalPath,
        )
        assertEquals(
            "gitdir: /git/shared-api.git/worktrees/agent-alpha\n",
            File(wt1, ".git").readText(),
        )

        val wtMetaDir = File(tempDir, "git-projects/shared-api/repo.git/worktrees/agent-alpha")
        assertEquals("../..\n", File(wtMetaDir, "commondir").readText())
        assertEquals("/workspace/.git\n", File(wtMetaDir, "gitdir").readText())
        assertEquals("ref: refs/heads/wt/alpha\n", File(wtMetaDir, "HEAD").readText())

        // Unborn repo has no commit SHA yet, so refs/heads/wt/alpha remains unborn until first commit.
        assertFalse(File(tempDir, "git-projects/shared-api/repo.git/refs/heads/wt/alpha").exists())

        // Re-provisioning the exact same container and branch is idempotent.
        manager.provisionWorktreeForContainer(
            profileId = "agent-alpha",
            spec = GitWorktreeSpec(projectId = "shared-api", branchName = "wt/alpha"),
        )

        // Another container attempting to check out wt/alpha must be rejected!
        assertThrows(IllegalStateException::class.java) {
            manager.provisionWorktreeForContainer(
                profileId = "agent-beta",
                spec = GitWorktreeSpec(projectId = "shared-api", branchName = "wt/alpha"),
            )
        }

        // Distinct branch wt/beta succeeds concurrently.
        val wt2 = manager.provisionWorktreeForContainer(
            profileId = "agent-beta",
            spec = GitWorktreeSpec(projectId = "shared-api", branchName = "wt/beta"),
        )
        assertTrue(wt2.isDirectory)
        assertEquals(2, manager.listProjects().single().worktreeCount)

        // Deleting project while worktrees or active profiles remain attached must fail.
        assertThrows(IllegalStateException::class.java) {
            manager.deleteProject("shared-api")
        }
        assertThrows(IllegalStateException::class.java) {
            manager.deleteProject("shared-api", attachedProfileIds = setOf("agent-alpha"))
        }

        // Detaching agent-alpha frees branch wt/alpha for another container.
        manager.detachWorktree("shared-api", "agent-alpha", deleteWorktreeFiles = true)
        assertFalse(wt1.exists())
        assertEquals(1, manager.listProjects().single().worktreeCount)

        val wt3 = manager.provisionWorktreeForContainer(
            profileId = "agent-gamma",
            spec = GitWorktreeSpec(projectId = "shared-api", branchName = "wt/alpha"),
        )
        assertTrue(wt3.isDirectory)

        manager.detachWorktree("shared-api", "agent-beta", deleteWorktreeFiles = true)
        manager.detachWorktree("shared-api", "agent-gamma", deleteWorktreeFiles = true)
        assertEquals(0, manager.listProjects().single().worktreeCount)

        manager.deleteProject("shared-api")
        assertTrue(manager.listProjects().isEmpty())
        assertFalse(File(tempDir, "git-projects/shared-api").exists())
    }

    @Test
    fun `INV-G3 provisionWorktreeForContainer seeds new branch SHA from loose refs and packed-refs without clobbering existing branches`() {
        // Property: New worktree branches inherit the 40-hex commit SHA from loose HEAD/defaultBranch or packed-refs,
        // while existing loose or packed feature branches keep their own commit SHA untouched.
        // Seam: GitWorktreeManager.provisionWorktreeForContainer.
        // Oracle: Git ref resolution rules (loose refs + packed-refs format).
        // Fault detected: Creating orphan branches when main is already committed or packed, or resetting an existing packed branch to main.
        val manager = GitWorktreeManager(filesDir = tempDir)

        // Case 1: Loose refs/heads/main
        manager.createOrEnsureProject("loose-proj", "Loose Proj", "main")
        val looseMainSha = "0123456789abcdef0123456789abcdef01234567"
        File(tempDir, "git-projects/loose-proj/repo.git/refs/heads/main").writeText("$looseMainSha\n")

        manager.provisionWorktreeForContainer(
            profileId = "dev-one",
            spec = GitWorktreeSpec("loose-proj", "wt/dev-one"),
        )
        assertEquals(
            "$looseMainSha\n",
            File(tempDir, "git-projects/loose-proj/repo.git/refs/heads/wt/dev-one").readText(),
        )

        // Case 2: packed-refs fallback (no loose refs/heads/main) + existing packed feature branch preservation
        manager.createOrEnsureProject("packed-proj", "Packed Proj", "main")
        val packedMainSha = "89abcdef0123456789abcdef0123456789abcdef"
        val existingFeatureSha = "fedcba9876543210fedcba9876543210fedcba98"
        File(tempDir, "git-projects/packed-proj/repo.git/packed-refs").writeText(
            """
            # pack-refs with: peeled fully-peeled sorted
            $packedMainSha refs/heads/main
            $existingFeatureSha refs/heads/wt/existing
            1111111111111111111111111111111111111111 refs/tags/v1.0.0
            ^$packedMainSha
            """.trimIndent() + "\n",
        )

        manager.provisionWorktreeForContainer(
            profileId = "dev-new",
            spec = GitWorktreeSpec("packed-proj", "wt/dev-new"),
        )
        assertEquals(
            "$packedMainSha\n",
            File(tempDir, "git-projects/packed-proj/repo.git/refs/heads/wt/dev-new").readText(),
        )

        // Existing packed branch wt/existing must NOT be overwritten with packedMainSha!
        manager.provisionWorktreeForContainer(
            profileId = "dev-existing",
            spec = GitWorktreeSpec("packed-proj", "wt/existing"),
        )
        val looseExistingRef = File(tempDir, "git-projects/packed-proj/repo.git/refs/heads/wt/existing")
        assertFalse(looseExistingRef.exists())
    }

    @Test
    fun `INV-G4 rejects invalid profile slugs and symlink escapes under git-projects`() {
        // Property: Invalid profileId slugs and symlinked project/worktree directories fail closed before writing metadata.
        // Seam: GitWorktreeManager.createOrEnsureProject, provisionWorktreeForContainer.
        // Oracle: Section 17.2 host path confinement invariants.
        // Fault detected: Writing .git or bare repo metadata outside <filesDir>/git-projects via symlinked directories.
        val manager = GitWorktreeManager(filesDir = tempDir)
        manager.createOrEnsureProject("safe-proj", "Safe", "main")

        for (badProfile in listOf("", "..", "../escape", "Upper", "ab")) {
            assertThrows(IllegalArgumentException::class.java) {
                manager.provisionWorktreeForContainer(
                    profileId = badProfile,
                    spec = GitWorktreeSpec("safe-proj", "wt/test"),
                )
            }
        }

        val outsideDir = File(tempDir, "outside-target").apply { mkdirs() }
        val gitProjectsRoot = File(tempDir, "git-projects").apply { mkdirs() }
        Files.createSymbolicLink(File(gitProjectsRoot, "symlink-proj").toPath(), outsideDir.toPath())

        assertThrows(SecurityException::class.java) {
            manager.createOrEnsureProject("symlink-proj", "Symlink", "main")
        }

        val worktreesDir = File(tempDir, "git-projects/safe-proj/worktrees").apply { mkdirs() }
        Files.createSymbolicLink(File(worktreesDir, "evil-box").toPath(), outsideDir.toPath())

        assertThrows(SecurityException::class.java) {
            manager.provisionWorktreeForContainer(
                profileId = "evil-box",
                spec = GitWorktreeSpec("safe-proj", "wt/evil"),
            )
        }

        // Symlinked entries inside repo.git/worktrees must be ignored during branch-collision scanning
        val fakeOutsideWorktree = File(outsideDir, "fake-wt").apply { mkdirs() }
        File(fakeOutsideWorktree, "HEAD").writeText("ref: refs/heads/wt/clean-box\n")
        val repoWorktrees = File(tempDir, "git-projects/safe-proj/repo.git/worktrees").apply { mkdirs() }
        Files.createSymbolicLink(File(repoWorktrees, "symlink-other").toPath(), fakeOutsideWorktree.toPath())
        val cleanWt = manager.provisionWorktreeForContainer(
            profileId = "clean-box",
            spec = GitWorktreeSpec("safe-proj", "wt/clean-box"),
        )
        assertTrue(cleanWt.isDirectory)
    }

    @Test
    fun `INV-G5 provisioned bare repo and worktrees interoperate cleanly with real host git status commit and branch`() {
        // Property: Real POSIX git CLI accepts the pure-Kotlin bare repo and linked worktrees for status, commit,
        // branch listing, and commit-SHA seeding onto subsequent container worktrees.
        // Seam: GitWorktreeManager on-disk repository + host git subprocess.
        // Oracle: Independent upstream git binary execution.
        // Fault detected: Subtle header/newline/commondir malformation rejected by real git.
        val gitAvailable = runCatching {
            val proc = ProcessBuilder("git", "--version").start()
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        }.getOrDefault(false)
        if (!gitAvailable) return

        val manager = GitWorktreeManager(filesDir = tempDir)
        manager.createOrEnsureProject("live-git", "Live Git", "main")

        val wt1 = manager.provisionWorktreeForContainer(
            profileId = "box-one",
            spec = GitWorktreeSpec("live-git", "wt/box-one"),
        )
        val repoGit = manager.repoGitDir("live-git")
        val wt1GitDir = File(repoGit, "worktrees/box-one")

        File(wt1, "README.md").writeText("# Shared TermVR Project\n")
        assertEquals(0, runGit(wt1GitDir, wt1, "status").first)
        assertEquals(0, runGit(wt1GitDir, wt1, "add", "README.md").first)
        val (commitCode, commitOut) = runGit(
            wt1GitDir,
            wt1,
            "-c",
            "user.name=TermVR Test",
            "-c",
            "user.email=test@termvr.local",
            "commit",
            "-m",
            "Initial shared commit",
        )
        assertEquals(0, commitCode, commitOut)

        // Advance bare repo's main branch to wt/box-one's commit, then provision box-two!
        val (pushCode, pushOut) = runGitBare(repoGit, "branch", "-f", "main", "wt/box-one")
        assertEquals(0, pushCode, pushOut)
        val (_, mainShaRaw) = runGitBare(repoGit, "rev-parse", "refs/heads/main")
        val mainSha = mainShaRaw.trim()
        assertEquals(40, mainSha.length)

        val wt2 = manager.provisionWorktreeForContainer(
            profileId = "box-two",
            spec = GitWorktreeSpec("live-git", "wt/box-two"),
        )
        val wt2GitDir = File(repoGit, "worktrees/box-two")
        val (wt2RevCode, wt2ShaRaw) = runGit(wt2GitDir, wt2, "rev-parse", "HEAD")
        assertEquals(0, wt2RevCode)
        assertEquals(mainSha, wt2ShaRaw.trim())

        val (branchCode, branchOut) = runGitBare(repoGit, "branch")
        assertEquals(0, branchCode)
        assertTrue(branchOut.contains("main"))
        assertTrue(branchOut.contains("wt/box-one"))
        assertTrue(branchOut.contains("wt/box-two"))
    }

    private fun runGit(gitDir: File, workTree: File, vararg gitArgs: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("git") + gitArgs)
        pb.directory(workTree)
        pb.environment()["GIT_DIR"] = gitDir.absolutePath
        pb.environment()["GIT_WORK_TREE"] = workTree.absolutePath
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(10, TimeUnit.SECONDS)) { "git command timed out" }
        return proc.exitValue() to output
    }

    private fun runGitBare(repoGit: File, vararg gitArgs: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf("git", "--git-dir=${repoGit.absolutePath}") + gitArgs)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor(10, TimeUnit.SECONDS)) { "git bare command timed out" }
        return proc.exitValue() to output
    }
}
