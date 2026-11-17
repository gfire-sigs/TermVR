package dev.gfire.termvr.files

import androidx.datastore.core.DataStore
import dev.gfire.termvr.core.data.AppPersistenceState
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeLockMode
import dev.gfire.termvr.core.model.VolumeMountMode
import dev.gfire.termvr.core.model.VolumeMountSpec
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.runtime.ClipboardReadPrompt
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.files.FileRootTab
import dev.gfire.termvr.ui.files.FilesViewModel
import dev.gfire.termvr.ui.files.VolumePauseKind
import dev.gfire.termvr.util.SafUriUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    @TempDir
    lateinit var tempDir: File

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun streamsWorkspaceImportsWithoutHeapBufferingAndCleansUpOnFailure() = runTest(testDispatcher) {
        // Property: Streaming SAF import writes multi-chunk files atomically, resolves SAF display names
        // instead of opaque document IDs, refreshes workspaceEntries, and leaves zero .termvr.tmp on error.
        // Seam: FilesViewModel.importWorkspaceStream + uiState.
        // Fault: Loading full SAF file via readBytes(), saving under "document:100001234", or leaking .termvr.tmp.
        val (repo, gateway) = createFixtures()
        val profile = SandboxProfile(id = "dev-box", displayName = "Dev Box", imageRef = "debian:trixie-slim")
        repo.upsertProfile(profile)

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val payloadSize = (SafUriUtils.STREAM_CHUNK_BYTES * 2) + 9_917
        val payload = ByteArray(payloadSize) { idx -> ((idx * 17 + 3) and 0xFF).toByte() }

        vm.importWorkspaceStream(
            resolveDisplayName = {
                SafUriUtils.resolveDisplayName(
                    queriedDisplayName = "dataset-2026.tar.zst",
                    rawLastPathSegment = "document:100001234",
                )
            },
            openStream = { ByteArrayInputStream(payload) },
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("Imported dataset-2026.tar.zst.", vm.uiState.value.statusMessage)
        assertEquals(listOf("dataset-2026.tar.zst"), vm.uiState.value.workspaceEntries.map { it.name })
        val importedFile = File(gateway.workspaceDir("dev-box"), "dataset-2026.tar.zst")
        assertArrayEquals(payload, importedFile.readBytes())

        // Mid-stream IOException sets errorMessage, resets busy, preserves prior files, and deletes .termvr.tmp.
        vm.importWorkspaceStream(
            displayName = "broken.bin",
            openStream = {
                object : InputStream() {
                    private var count = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (count >= SafUriUtils.STREAM_CHUNK_BYTES) {
                            throw IOException("Storage read error")
                        }
                        val step = minOf(len, 4096)
                        count += step
                        return step
                    }

                    override fun read(): Int = throw IOException("Storage read error")
                }
            },
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.busy)
        assertTrue(vm.uiState.value.errorMessage?.contains("Storage read error") == true)
        assertFalse(File(gateway.workspaceDir("dev-box"), "broken.bin").exists())
        assertTrue(gateway.workspaceDir("dev-box").listFiles().orEmpty().none { it.name.endsWith(".termvr.tmp") })
        assertEquals(listOf("dataset-2026.tar.zst"), vm.uiState.value.workspaceEntries.map { it.name })

        // Reserved or traversal names are rejected without opening the stream.
        val openedForInvalid = AtomicInteger(0)
        vm.importWorkspaceStream(
            displayName = ".gvx_meta",
            openStream = {
                openedForInvalid.incrementAndGet()
                ByteArrayInputStream(byteArrayOf(1))
            },
        )
        advanceUntilIdle()
        assertEquals(0, openedForInvalid.get())
        assertNotNull(vm.uiState.value.errorMessage)

        vmScope.cancel()
    }

    @Test
    fun pausesDirectVolumeBrowseWithSingleWriterGuidanceWhileRunningAndRecoversOnStopOrWorkspaceSwitch() =
        runTest(testDispatcher) {
            // Property: When a persistent rootfs or named ext4lite volume has an active or frozen container
            // without a live mux stream, browsing pauses cleanly with single-writer / checkpoint explanation
            // instead of spawning a colliding helper container, and provides working Workspace and Stop actions.
            // Seam: FilesViewModel.browseVolume, openWorkspaceForVolume, stopContainersForVolume, uiState.
            // Fault: Calling withVolumeClient while ext4lite is locked by a running/frozen sandbox, causing
            // dead-end "volume file is in use" errors or treating FAILED containers as running mounts.
            val (repo, gateway) = createFixtures()
            val stoppedPeer = SandboxProfile(
                id = "peer-box",
                displayName = "Peer",
                imageRef = "alpine:3.21",
            )
            val runningOwner = SandboxProfile(
                id = "debian-main",
                displayName = "Debian Main",
                imageRef = "debian:trixie-slim",
                persistentRootfs = true,
                volumes = listOf(
                    VolumeMountSpec(
                        sourceName = "app-data",
                        guestMountPath = "/data",
                        kind = VolumeKind.NAMED_EXT4LITE,
                    ),
                ),
            )
            val failedProfile = SandboxProfile(
                id = "crashed-box",
                displayName = "Crashed",
                imageRef = "alpine:3.21",
                volumes = listOf(
                    VolumeMountSpec(
                        sourceName = "orphan-vol",
                        guestMountPath = "/mnt",
                        kind = VolumeKind.NAMED_EXT4LITE,
                    ),
                ),
            )
            repo.upsertProfile(stoppedPeer)
            repo.upsertProfile(runningOwner)
            repo.upsertProfile(failedProfile)

            gateway.createVolume(runningOwner.rootfsVolumeName)
            gateway.createVolume("app-data")
            gateway.createVolume("orphan-vol")
            gateway.runStates.value = mapOf(
                stoppedPeer.id to SandboxRunState.STOPPED,
                runningOwner.id to SandboxRunState.RUNNING,
                failedProfile.id to SandboxRunState.FAILED,
            )

            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
            vm.selectRootTab(FileRootTab.VOLUME)
            advanceUntilIdle()

            // FAILED profile must not mark orphan-vol as running, whereas RUNNING owner marks both volumes active.
            val orphanVol = vm.uiState.value.volumes.first { it.name == "orphan-vol" }
            assertFalse(orphanVol.hasRunningMount)

            val rootVol = vm.uiState.value.volumes.first { it.name == runningOwner.rootfsVolumeName }
            val namedVol = vm.uiState.value.volumes.first { it.name == "app-data" }
            assertTrue(rootVol.hasRunningMount)
            assertTrue(namedVol.hasRunningMount)

            // Browsing active named volume pauses cleanly with single-writer ext4lite message and 0 helper calls.
            vm.browseVolume("app-data")
            advanceUntilIdle()

            assertEquals(0, gateway.volumeHelperCalls.get())
            assertNull(vm.uiState.value.errorMessage)
            val activePause = vm.uiState.value.selectedVolumePause
            assertNotNull(activePause)
            assertEquals(VolumePauseKind.ACTIVE_CONTAINER_SINGLE_WRITER, activePause!!.kind)
            assertEquals(FilesViewModel.SINGLE_WRITER_VOLUME_LOCK_MESSAGE, activePause.message)
            assertEquals(listOf("Debian Main"), activePause.attachedProfileNames)

            // Switching to Workspace from the volume guidance selects the owning profile and WORKSPACE tab.
            vm.selectProfile(stoppedPeer.id)
            advanceUntilIdle()
            vm.selectRootTab(FileRootTab.VOLUME)
            advanceUntilIdle()
            vm.openWorkspaceForVolume("app-data")
            advanceUntilIdle()
            assertEquals(FileRootTab.WORKSPACE, vm.uiState.value.selectedRootTab)
            assertEquals(runningOwner.id, vm.uiState.value.selectedProfileId)

            // Frozen checkpoint also pauses volume access without spawning a helper container.
            vm.selectRootTab(FileRootTab.VOLUME)
            gateway.activeCheckpoints.add(runningOwner.id)
            gateway.runStates.value = gateway.runStates.value + (runningOwner.id to SandboxRunState.FROZEN)
            vm.browseVolume(runningOwner.rootfsVolumeName)
            advanceUntilIdle()
            assertEquals(0, gateway.volumeHelperCalls.get())
            assertNull(vm.uiState.value.errorMessage)
            assertEquals(VolumePauseKind.FROZEN_CHECKPOINT_LOCK, vm.uiState.value.selectedVolumePause?.kind)

            // Stopping the attached container from Volumes clears the lock and invokes the stopped helper cleanly.
            vm.stopContainersForVolume(runningOwner.rootfsVolumeName)
            advanceUntilIdle()
            assertEquals(SandboxRunState.STOPPED, gateway.runStates.value[runningOwner.id])
            assertNull(vm.uiState.value.selectedVolumePause)
            assertFalse(
                vm.uiState.value.volumes.first { it.name == runningOwner.rootfsVolumeName }.hasRunningMount,
            )
            assertEquals(1, gateway.volumeHelperCalls.get())

            vmScope.cancel()
        }

    @Test
    fun deleteWorkspaceItemDoesNotTraverseSymlinkedDirectoryTargets() = runTest(testDispatcher) {
        // Property: Deleting a workspace directory from FilesViewModel unlinks symlinks without deleting
        // host files outside workspaceDir.
        // Seam: FilesViewModel.deleteWorkspaceItem.
        // Fault: File.deleteRecursively() walking into symlinked outside directories.
        val (repo, gateway) = createFixtures()
        val profile = SandboxProfile(id = "safe-del", displayName = "Safe Del", imageRef = "alpine:3.21")
        repo.upsertProfile(profile)

        val outside = File(tempDir, "outside-host-dir").apply { mkdirs() }
        val hostSecret = File(outside, "keep.txt").apply { writeText("host-data") }
        val ws = gateway.workspaceDir(profile.id)
        val sub = File(ws, "dir").apply { mkdirs() }
        Files.createSymbolicLink(File(sub, "link-out").toPath(), outside.toPath())

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val dirItem = vm.uiState.value.workspaceEntries.first { it.name == "dir" }
        vm.deleteWorkspaceItem(dirItem)
        advanceUntilIdle()

        assertFalse(sub.exists())
        assertTrue(hostSecret.exists())
        assertEquals("host-data", hostSecret.readText())

        vmScope.cancel()
    }

    @Test
    fun unlocksLiveFrozenStateBlocksMidFreezeUnlockAndUnlocksDualPaneFrozenVolumes() = runTest(testDispatcher) {
        // Property: Unlocking a live FROZEN profile clears both persisted checkpoint state and live FROZEN
        // runState, refuses unlock while FREEZING is mid-flight, and unlocks frozen volume owners in dual-pane
        // mode even when another unlocked profile chip is selected.
        // Seam: FilesViewModel.discardCheckpointAndUnlock + discardCheckpointsForVolume + uiState.
        // Fault: Leaving runStates[id]==FROZEN so writeLocked stays true after clicking Unlock, or checking
        // selectedProfile.writeLocked instead of pauseState.attachedProfileIds on frozen volumes.
        val (repo, gateway) = createFixtures()
        val unlockedSelected = SandboxProfile(
            id = "shell-a",
            displayName = "Shell A",
            imageRef = "alpine:3.21",
        )
        val frozenVolumeOwner = SandboxProfile(
            id = "worker-b",
            displayName = "Worker B",
            imageRef = "debian:trixie-slim",
            persistentRootfs = true,
        )
        repo.upsertProfile(unlockedSelected)
        repo.upsertProfile(frozenVolumeOwner)
        gateway.createVolume(frozenVolumeOwner.rootfsVolumeName)

        // 1. Reject unlock while FREEZING is mid-flight.
        gateway.activeCheckpoints.add(unlockedSelected.id)
        gateway.runStates.value = mapOf(
            unlockedSelected.id to SandboxRunState.FREEZING,
            frozenVolumeOwner.id to SandboxRunState.FROZEN,
        )
        gateway.activeCheckpoints.add(frozenVolumeOwner.id)

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.writeLocked)
        vm.discardCheckpointAndUnlock()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.writeLocked)
        assertEquals(SandboxRunState.FREEZING, gateway.runStates.value[unlockedSelected.id])
        assertNotNull(vm.uiState.value.errorMessage)

        // 2. Once FROZEN, discardCheckpointAndUnlock clears both checkpoint and live FROZEN runState immediately.
        gateway.runStates.value = gateway.runStates.value + (unlockedSelected.id to SandboxRunState.FROZEN)
        vm.discardCheckpointAndUnlock()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.writeLocked)
        assertEquals(SandboxRunState.STOPPED, gateway.runStates.value[unlockedSelected.id])

        // 3. Dual-pane scenario: shell-a is selected and unlocked, while worker-b's rootfs volume is FROZEN.
        vm.browseVolume(frozenVolumeOwner.rootfsVolumeName)
        advanceUntilIdle()
        vm.selectProfile(unlockedSelected.id)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.writeLocked)
        assertEquals(VolumePauseKind.FROZEN_CHECKPOINT_LOCK, vm.uiState.value.selectedVolumePause?.kind)
        assertEquals(0, gateway.volumeHelperCalls.get())

        // Discarding checkpoints for the frozen volume unlocks worker-b and refreshes the volume via helper.
        vm.discardCheckpointsForVolume(frozenVolumeOwner.rootfsVolumeName)
        advanceUntilIdle()

        assertEquals(SandboxRunState.STOPPED, gateway.runStates.value[frozenVolumeOwner.id])
        assertFalse(gateway.hasActiveCheckpoint(frozenVolumeOwner.id))
        assertNull(vm.uiState.value.selectedVolumePause)
        assertEquals(1, gateway.volumeHelperCalls.get())

        vmScope.cancel()
    }

    private fun createFixtures(): Pair<ProfileRepository, TestRuntimeGateway> {
        val secretStore = object : SecretStore {
            private val map = ConcurrentHashMap<String, CharArray>()
            override fun putSecret(alias: String, secret: CharArray) {
                map[alias] = secret.copyOf()
            }

            override fun getSecret(alias: String): CharArray? = map[alias]?.copyOf()
            override fun deleteSecret(alias: String) {
                map.remove(alias)
            }

            override fun containsAlias(alias: String): Boolean = map.containsKey(alias)
        }
        val dataStore = object : DataStore<AppPersistenceState> {
            private val state = MutableStateFlow(AppPersistenceState())
            private val mutex = Mutex()
            override val data: Flow<AppPersistenceState> = state
            override suspend fun updateData(
                transform: suspend (t: AppPersistenceState) -> AppPersistenceState,
            ): AppPersistenceState = mutex.withLock {
                val next = transform(state.value)
                state.value = next
                next
            }
        }
        return ProfileRepository(dataStore, tempDir, secretStore) to TestRuntimeGateway(tempDir)
    }

    @Test
    fun displaysPhysicalAllocatedSizeLockBadgesCompactsStoppedVolumesAndBranchesOverlayMounts() =
        runTest(testDispatcher) {
            // Property: Sparse volumes display allocatedBytes alongside logical sizeBytes, lockMode distinguishes
            // Shared (RO) from Active (RW), compactNamedVolume runs only on unlocked stopped volumes and reports
            // reclaimed bytes, and mountVolumeToProfile supports CoW overlay branching with suggested upper names.
            // Seam: FilesViewModel.uiState, compactNamedVolume, mountVolumeToProfile.
            // Fault: Hiding sparse savings, blocking CoW branching on Shared (RO) volumes, or compacting locked volumes.
            val (repo, gateway) = createFixtures()
            val readerBox = SandboxProfile(
                id = "reader-box",
                displayName = "Reader Box",
                imageRef = "debian:trixie-slim",
                volumes = listOf(
                    VolumeMountSpec(
                        sourceName = "shared-sdk",
                        guestMountPath = "/opt/sdk",
                        kind = VolumeKind.NAMED_EXT4LITE,
                        readOnly = true,
                    ),
                ),
            )
            val branchTarget = SandboxProfile(
                id = "worker-two",
                displayName = "Worker Two",
                imageRef = "debian:trixie-slim",
            )
            repo.upsertProfile(readerBox)
            repo.upsertProfile(branchTarget)

            gateway.putVolume(
                RungvVolumeLsEntry(
                    name = "sparse-vol",
                    driver = "ext4lite",
                    sizeBytes = 1500L * 1024L * 1024L,
                    allocatedBytes = 512L * 1024L * 1024L,
                    inUse = false,
                    rawLockMode = "none",
                ),
            )
            gateway.putVolume(
                RungvVolumeLsEntry(
                    name = "shared-sdk",
                    driver = "ext4lite",
                    sizeBytes = 256L * 1024L * 1024L,
                    allocatedBytes = 256L * 1024L * 1024L,
                    inUse = true,
                    rawLockMode = "shared",
                ),
            )
            gateway.runStates.value = mapOf("reader-box" to SandboxRunState.RUNNING)
            gateway.nextCompactedAllocatedBytes["sparse-vol"] = 128L * 1024L * 1024L

            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
            vm.refreshVolumeList()
            advanceUntilIdle()

            val sparseBefore = vm.uiState.value.volumes.first { it.name == "sparse-vol" }
            assertEquals("512 MB on disk (1.46 GB logical)", sparseBefore.diskUsageLabel)
            assertEquals(VolumeLockMode.NONE, sparseBefore.lockMode)
            assertTrue(sparseBefore.canCompact)

            val sharedSdk = vm.uiState.value.volumes.first { it.name == "shared-sdk" }
            assertEquals("256 MB", sharedSdk.diskUsageLabel)
            assertEquals(VolumeLockMode.SHARED, sharedSdk.lockMode)
            assertFalse(sharedSdk.canCompact)

            // Compacting locked shared-sdk must fail with clear error
            vm.compactNamedVolume("shared-sdk")
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)

            // Compacting stopped sparse-vol reclaims 384 MB and reports reclaimed bytes!
            vm.compactNamedVolume("sparse-vol")
            advanceUntilIdle()
            assertNull(vm.uiState.value.errorMessage)
            assertTrue(vm.uiState.value.statusMessage?.contains("384 MB") == true)
            val sparseAfter = vm.uiState.value.volumes.first { it.name == "sparse-vol" }
            assertEquals(128L * 1024L * 1024L, sparseAfter.allocatedBytes)

            // Branching shared-sdk onto worker-two creates OVERLAY_EXT4LITE with deterministic suggested upper name!
            vm.mountVolumeToProfile(
                volumeName = "shared-sdk",
                profileId = "worker-two",
                guestMountPath = "/opt/sdk",
                mode = VolumeMountMode.COW_PERSISTENT,
            )
            advanceUntilIdle()
            val updatedWorker = vm.uiState.value.profiles.first { it.id == "worker-two" }
            val overlayMount = updatedWorker.volumes.first { it.guestMountPath == "/opt/sdk" }
            assertEquals(VolumeKind.OVERLAY_EXT4LITE, overlayMount.kind)
            assertEquals("shared-sdk-wt-worker-two", overlayMount.overlayUpperVolumeName)

            vmScope.cancel()
        }

    @Test
    fun managesSharedGitProjectsWorktreeWorkspaceBrowsingAndBlocksDeletingAttachedProjects() =
        runTest(testDispatcher) {
            // Property: Git projects can be created and browsed from FilesViewModel; profiles attached to a Git worktree
            // browse <filesDir>/git-projects/<id>/worktrees/<profileId> while hiding .git and .gvx_meta.lock;
            // deleteGitProject fails while attached and succeeds after detachment.
            // Seam: FilesViewModel Git project actions + Workspace browsing.
            // Fault: Browsing empty workspaces/<id> instead of Git worktree dir, exposing .git pointer file, or deleting active repo.git.
            val (repo, gateway) = createFixtures()
            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = FilesViewModel(repo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
            advanceUntilIdle()

            vm.createGitProject(name = "Platform Service", defaultBranch = "main")
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.gitProjects.size)
            val projectId = vm.uiState.value.gitProjects.single().summary.id
            assertEquals("platform-service", projectId)

            val wtProfile = SandboxProfile(
                id = "svc-dev",
                displayName = "Service Dev",
                imageRef = "debian:trixie-slim",
                gitWorktree = GitWorktreeSpec(projectId = projectId, branchName = "wt/svc-dev"),
            )
            val wtDir = gateway.gitWorktreeManager().provisionWorktreeForContainer(wtProfile.id, wtProfile.gitWorktree!!)
            File(wtDir, ".gvx_meta.lock").writeText("")
            File(wtDir, "server.kt").writeText("fun main() {}\n")
            repo.upsertProfile(wtProfile)
            vm.selectProfile("svc-dev")
            vm.refreshGitProjects()
            advanceUntilIdle()

            // Workspace entries list server.kt from the Git worktree while hiding .git and .gvx_meta.lock!
            assertEquals(listOf("server.kt"), vm.uiState.value.workspaceEntries.map { it.name })
            assertEquals(1, vm.uiState.value.gitProjects.single().worktrees.size)

            // Deleting while svc-dev is attached must fail!
            vm.deleteGitProject(projectId)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.errorMessage)
            assertEquals(1, vm.uiState.value.gitProjects.size)

            // Detaching profile allows clean project deletion
            repo.deleteProfile("svc-dev")
            gateway.gitWorktreeManager().detachWorktree(projectId, "svc-dev", deleteWorktreeFiles = true)
            vm.deleteGitProject(projectId)
            advanceUntilIdle()
            assertNull(vm.uiState.value.errorMessage)
            assertTrue(vm.uiState.value.gitProjects.isEmpty())

            vmScope.cancel()
        }
}

private class TestRuntimeGateway(private val rootDir: File) : SandboxRuntimeGateway {
    override val runStates = MutableStateFlow<Map<String, SandboxRunState>>(emptyMap())
    override val telemetryByProfile = MutableStateFlow<Map<String, SandboxTelemetrySnapshot>>(emptyMap())
    override val publishedPortsByProfile = MutableStateFlow<Map<String, List<PublishedPortMapping>>>(emptyMap())
    override val lanReconfirmationRequired = MutableStateFlow<Set<String>>(emptySet())
    override val wakeLockRemainingSeconds = MutableStateFlow<Long?>(null)
    override val notificationsDeniedBanner = MutableStateFlow(false)
    override val hostReadiness = MutableStateFlow(
        HostReadinessReport(
            hostSdkInt = 34,
            hostAbi = "arm64-v8a",
            totalHostRamMiB = 8192,
            freeDiskMiB = 20_000,
        ),
    )
    override val activeServiceNotice = MutableStateFlow<String?>(null)
    override val clipboardReadPrompt = MutableStateFlow<ClipboardReadPrompt?>(null)
    override val dnsProxyPort: Int = 1053

    val activeCheckpoints = mutableSetOf<String>()
    private val volumes = mutableListOf<RungvVolumeLsEntry>()
    val nextCompactedAllocatedBytes = mutableMapOf<String, Long>()
    val volumeHelperCalls = AtomicInteger(0)

    fun putVolume(entry: RungvVolumeLsEntry) {
        volumes.removeAll { it.name == entry.name }
        volumes.add(entry)
    }

    override fun refreshHostReadiness() = Unit
    override fun setNotificationsPermissionGranted(granted: Boolean) = Unit
    override fun clearNotice() = Unit

    override suspend fun startOrResumeSandbox(profile: SandboxProfile, reconfirmLan: Boolean): SandboxRunState {
        activeCheckpoints.remove(profile.id)
        runStates.value = runStates.value + (profile.id to SandboxRunState.RUNNING)
        return SandboxRunState.RUNNING
    }

    override suspend fun freezeSandbox(profileId: String): SandboxRunState {
        activeCheckpoints.add(profileId)
        runStates.value = runStates.value + (profileId to SandboxRunState.FROZEN)
        return SandboxRunState.FROZEN
    }

    override suspend fun stopSandbox(profileId: String) {
        activeCheckpoints.remove(profileId)
        runStates.value = runStates.value + (profileId to SandboxRunState.STOPPED)
    }

    override suspend fun stopAllSandboxes() {
        activeCheckpoints.clear()
        runStates.value = runStates.value.mapValues { SandboxRunState.STOPPED }
    }

    override suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) = Unit

    override suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState = SandboxRunState.RUNNING

    override suspend fun acquireWakeLock(minutes: Int): Int = minutes
    override suspend fun releaseWakeLock() = Unit
    override suspend fun discardCheckpoint(profileId: String): Boolean = activeCheckpoints.remove(profileId)
    override suspend fun hasActiveCheckpoint(profileId: String): Boolean = profileId in activeCheckpoints
    override suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult =
        ProxyTestResult(true, 5L, config.mode, "OK")

    override suspend fun listVolumes(): List<RungvVolumeLsEntry> = volumes.toList()

    override suspend fun createVolume(name: String) {
        if (volumes.none { it.name == name }) {
            volumes.add(RungvVolumeLsEntry(name = name, sizeBytes = 4096L))
        }
    }

    override suspend fun removeVolume(name: String, force: Boolean) {
        volumes.removeAll { it.name == name }
    }

    override suspend fun compactVolume(name: String): Result<Unit> = runCatching {
        val idx = volumes.indexOfFirst { it.name == name }
        check(idx >= 0) { "Volume not found: $name" }
        val existing = volumes[idx]
        check(existing.lockMode == VolumeLockMode.NONE) { "Volume $name is in use" }
        val targetAllocated = nextCompactedAllocatedBytes[name] ?: existing.allocatedBytes
        volumes[idx] = existing.copy(allocatedBytes = targetAllocated)
    }

    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T {
        volumeHelperCalls.incrementAndGet()
        throw IOException("Helper invoked for stopped volume $volumeName")
    }

    override fun getMuxClient(profileId: String): MuxSessionClient? = null
    override fun getRawPtyHandle(profileId: String): HostPtyHandle? = null
    override fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int, ypixel: Int) = Unit
    override fun markProfileActivity(profileId: String) = Unit
    override fun resolveClipboardPrompt(allow: Boolean) = Unit
    override fun buildRungvHostEnv(trustHostCaCerts: Boolean, includeProxyVars: Boolean): Map<String, String> =
        emptyMap()

    override fun workspaceDir(profileId: String): File = File(rootDir, "workspaces/$profileId").apply { mkdirs() }
    override fun activeWifiIpv4(): String = "192.168.1.10"
    override fun openUrl(url: String) = Unit
    override fun shareText(text: String) = Unit
    override fun shareNamedFile(fileName: String, content: ByteArray) = Unit
    override fun startForegroundAction(action: String) = Unit
}
