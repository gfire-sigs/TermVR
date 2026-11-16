package dev.gfire.termvr.ui

import androidx.datastore.core.DataStore
import dev.gfire.termvr.core.data.AppPersistenceState
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxCodec
import dev.gfire.termvr.core.runtime.MuxFrame
import dev.gfire.termvr.core.runtime.MuxFrameType
import dev.gfire.termvr.core.runtime.MuxHelloPayload
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.core.vt.NativeVtSession
import dev.gfire.termvr.core.vt.VtCursorShape
import dev.gfire.termvr.core.vt.VtCursorState
import dev.gfire.termvr.core.vt.VtDetectedLink
import dev.gfire.termvr.core.vt.VtKeyEvent
import dev.gfire.termvr.core.vt.VtMouseEvent
import dev.gfire.termvr.core.vt.VtSearchMatch
import dev.gfire.termvr.core.vt.VtSessionBackend
import dev.gfire.termvr.runtime.ClipboardReadPrompt
import dev.gfire.termvr.runtime.SandboxRuntimeGateway
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import dev.gfire.termvr.ui.terminal.TerminalViewModel
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotResumeFlowTest {

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
    fun `freeze ignores stale socket writes and resume rebinds stream without socket error`() = runTest(testDispatcher) {
        val pool = Executors.newCachedThreadPool()
        val gateway = PipedMuxGateway(tempDir, pool)
        val profile = SandboxProfile(
            id = "snap-mux",
            displayName = "Snap Mux",
            imageRef = "docker.io/library/debian:trixie-slim",
            muxMode = MuxMode.STDIO_MUX,
        )
        val receivedChunks = CopyOnWriteArrayList<String>()
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(
                    maxScrollbackLines = maxLines,
                    backendFactory = { _, _, _ -> RecordingVtBackend(receivedChunks) },
                )
            },
        )

        try {
            // 1. Cold boot and open shell tab (stream 1).
            assertEquals(SandboxRunState.RUNNING, gateway.startOrResumeSandbox(profile))
            coordinator.ensureTabsForProfile(profile, maxScrollbackLines = 1000, palette = ColorPalette.DEFAULT_DARK)
            val deadline1 = System.currentTimeMillis() + 3_000L
            while (gateway.latestGuestSession()?.openedStreamIds?.contains(1L) != true &&
                System.currentTimeMillis() < deadline1
            ) {
                Thread.sleep(10L)
            }
            assertTrue(gateway.latestGuestSession()!!.openedStreamIds.contains(1L))
            val tabId = coordinator.tabs.value.single().tabId
            val paneId = coordinator.tabs.value.single().focusedPaneId

            // 2. Freeze closes old MuxSessionClient; input/resize while frozen must NOT emit "MuxSessionClient is closed".
            assertEquals(SandboxRunState.FROZEN, gateway.freezeSandbox(profile.id))
            coordinator.sendPtyBytes(paneId, "stale-while-frozen\n".toByteArray(StandardCharsets.UTF_8))
            coordinator.notifyGridResized(paneId, cols = 100, rows = 30, widthPx = 1000, heightPx = 600)
            Thread.sleep(60L)
            assertNull(coordinator.lastError.value)
            assertEquals(listOf(tabId), coordinator.tabs.value.map { it.tabId })

            // 3. Resume creates a new MuxSessionClient with resync openStreams=[1], reattaches existing tab cleanly.
            assertEquals(SandboxRunState.RUNNING, gateway.startOrResumeSandbox(profile))
            coordinator.reattachAfterResume(profile, maxScrollbackLines = 1000, palette = ColorPalette.DEFAULT_DARK)
            val resumedGuest = gateway.latestGuestSession()!!
            resumedGuest.awaitResync(2_000L)

            coordinator.sendPtyBytes(paneId, "echo back\n".toByteArray(StandardCharsets.UTF_8))
            val deadline2 = System.currentTimeMillis() + 3_000L
            while (!resumedGuest.receivedInputForStream(1L).contains("echo back") &&
                System.currentTimeMillis() < deadline2
            ) {
                Thread.sleep(10L)
            }
            assertTrue(resumedGuest.receivedInputForStream(1L).contains("echo back"))

            // Guest writes PTY_DATA on stream 1 and existing tab VT backend receives it.
            resumedGuest.sendPtyDataToHost(1L, "resumed-output\r\n")
            val deadline3 = System.currentTimeMillis() + 3_000L
            while (receivedChunks.none { it.contains("resumed-output") } &&
                System.currentTimeMillis() < deadline3
            ) {
                Thread.sleep(10L)
            }
            assertTrue(receivedChunks.any { it.contains("resumed-output") })
            assertNull(coordinator.lastError.value)
        } finally {
            gateway.closeAll()
            pool.shutdownNow()
        }
    }

    @Test
    fun `TerminalViewModel auto-reattaches tabs after background checkpoint-restore and handles cold-boot fallback`() =
        runTest(testDispatcher) {
            val pool = Executors.newCachedThreadPool()
            val gateway = PipedMuxGateway(tempDir, pool)
            val repo = ProfileRepository(SnapshotDataStore(), tempDir, SnapshotSecretStore())
            val profile = SandboxProfile(
                id = "vm-snap",
                displayName = "VM Snap",
                imageRef = "docker.io/library/alpine:3.21",
                muxMode = MuxMode.STDIO_MUX,
            )
            repo.upsertProfile(profile)
            val coordinator = TerminalSessionCoordinator(
                runtimeGateway = gateway,
                sessionFactory = { maxLines ->
                    NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> RecordingVtBackend() })
                },
            )
            val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
            val vm = TerminalViewModel(repo, gateway, coordinator, scope = vmScope)
            advanceUntilIdle()

            try {
                vm.switchToOrStartProfile(profile)
                advanceUntilIdle()
                val deadline1 = System.currentTimeMillis() + 3_000L
                while (gateway.latestGuestSession()?.openedStreamIds?.contains(1L) != true &&
                    System.currentTimeMillis() < deadline1
                ) {
                    Thread.sleep(10L)
                }
                val paneId = coordinator.tabs.value.single().focusedPaneId

                // Simulate applyPortForwards(checkpointAndRestore = true) swapping MuxSessionClient in background.
                assertEquals(
                    SandboxRunState.RUNNING,
                    gateway.applyPortForwards(profile.id, emptyList(), checkpointAndRestore = true),
                )
                advanceUntilIdle()
                val secondGuest = gateway.latestGuestSession()!!
                vm.sendPtyBytes(paneId, "after-port-restore\n".toByteArray(StandardCharsets.UTF_8))
                val deadline2 = System.currentTimeMillis() + 3_000L
                while (!secondGuest.receivedInputForStream(1L).contains("after-port-restore") &&
                    System.currentTimeMillis() < deadline2
                ) {
                    Thread.sleep(10L)
                }
                assertTrue(secondGuest.receivedInputForStream(1L).contains("after-port-restore"))
                assertNull(coordinator.lastError.value)

                // Simulate superseded checkpoint cold-boot fallback where resync openStreams is empty: opens fresh PTY.
                gateway.forceColdBootOnNextResume = true
                assertEquals(SandboxRunState.FROZEN, gateway.freezeSandbox(profile.id))
                advanceUntilIdle()
                assertEquals(SandboxRunState.RUNNING, gateway.startOrResumeSandbox(profile))
                advanceUntilIdle()
                val coldGuest = gateway.latestGuestSession()!!
                val deadline3 = System.currentTimeMillis() + 3_000L
                while (coldGuest.openedStreamIds.isEmpty() && System.currentTimeMillis() < deadline3) {
                    Thread.sleep(10L)
                }
                assertTrue(coldGuest.openedStreamIds.isNotEmpty())
                assertNull(coordinator.lastError.value)
            } finally {
                vmScope.cancel()
                gateway.closeAll()
                pool.shutdownNow()
            }
        }

    @Test
    fun `split panes share one tab controller and concurrent reattach is idempotent`() = runTest(testDispatcher) {
        val pool = Executors.newCachedThreadPool()
        val gateway = PipedMuxGateway(tempDir, pool)
        val profile = SandboxProfile(
            id = "split-idem",
            displayName = "Split Idem",
            imageRef = "docker.io/library/debian:trixie-slim",
            muxMode = MuxMode.STDIO_MUX,
        )
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> RecordingVtBackend() })
            },
        )
        try {
            assertEquals(SandboxRunState.RUNNING, gateway.startOrResumeSandbox(profile))
            val tab = coordinator.openNewShellTab(profile, 1000, ColorPalette.DEFAULT_DARK)!!
            val firstController = coordinator.activeController()
            val deadline1 = System.currentTimeMillis() + 3_000L
            while (gateway.latestGuestSession()?.openedStreamIds?.size != 1 &&
                System.currentTimeMillis() < deadline1
            ) {
                Thread.sleep(10L)
            }
            assertEquals(1, gateway.latestGuestSession()!!.openedStreamIds.size)

            // Splitting creates a second pane sharing the same tab controller.
            coordinator.splitFocusedPane(
                tabId = tab.tabId,
                direction = dev.gfire.termvr.core.ui.SplitDirection.HORIZONTAL,
                profile = profile,
                maxScrollbackLines = 1000,
                palette = ColorPalette.DEFAULT_DARK,
            )
            val splitController = coordinator.activeController()
            assertSame(firstController, splitController)

            val deadline2 = System.currentTimeMillis() + 3_000L
            while (gateway.latestGuestSession()?.openedStreamIds?.size != 2 &&
                System.currentTimeMillis() < deadline2
            ) {
                Thread.sleep(10L)
            }
            assertEquals(2, gateway.latestGuestSession()!!.openedStreamIds.size)

            // Concurrent reattach calls for the same running mux must NOT open duplicate PTYs.
            coordinator.reattachAfterResume(profile, 1000, ColorPalette.DEFAULT_DARK)
            coordinator.reattachAfterResume(profile, 1000, ColorPalette.DEFAULT_DARK)
            Thread.sleep(60L)
            assertEquals(2, gateway.latestGuestSession()!!.openedStreamIds.size)

            // Closing the tab clears _tabs first and cleanly closes sessions.
            coordinator.closeTab(tab.tabId)
            assertTrue(coordinator.tabs.value.isEmpty())
            assertNull(coordinator.lastError.value)
        } finally {
            gateway.closeAll()
            pool.shutdownNow()
        }
    }
}

private class PipedMuxGateway(
    private val rootDir: File,
    private val pool: java.util.concurrent.ExecutorService,
) : SandboxRuntimeGateway {
    override val runStates = MutableStateFlow<Map<String, SandboxRunState>>(emptyMap())
    override val telemetryByProfile = MutableStateFlow<Map<String, SandboxTelemetrySnapshot>>(emptyMap())
    override val publishedPortsByProfile = MutableStateFlow<Map<String, List<PublishedPortMapping>>>(emptyMap())
    override val lanReconfirmationRequired = MutableStateFlow<Set<String>>(emptySet())
    override val wakeLockRemainingSeconds = MutableStateFlow<Long?>(null)
    override val notificationsDeniedBanner = MutableStateFlow(false)
    override val hostReadiness = MutableStateFlow(
        HostReadinessReport(hostSdkInt = 35, hostAbi = "arm64-v8a", totalHostRamMiB = 8192, freeDiskMiB = 16_000),
    )
    override val activeServiceNotice = MutableStateFlow<String?>(null)
    override val clipboardReadPrompt = MutableStateFlow<ClipboardReadPrompt?>(null)
    override val dnsProxyPort: Int = 5353

    var forceColdBootOnNextResume: Boolean = false
    private val muxClients = ConcurrentHashMap<String, MuxSessionClient>()
    private val guestSessions = CopyOnWriteArrayList<FakeGuestMuxPipe>()
    private val hasCheckpoint = mutableSetOf<String>()

    fun latestGuestSession(): FakeGuestMuxPipe? = guestSessions.lastOrNull()

    fun closeAll() {
        muxClients.values.forEach { runCatching { it.close() } }
        guestSessions.forEach { it.close() }
    }

    override fun refreshHostReadiness() = Unit
    override fun setNotificationsPermissionGranted(granted: Boolean) = Unit
    override fun clearNotice() = Unit

    override suspend fun startOrResumeSandbox(profile: SandboxProfile, reconfirmLan: Boolean): SandboxRunState {
        val restoring = profile.id in hasCheckpoint && !forceColdBootOnNextResume
        forceColdBootOnNextResume = false
        hasCheckpoint.remove(profile.id)
        runStates.value = runStates.value + (
            profile.id to if (restoring) SandboxRunState.RESTORING else SandboxRunState.STARTING
        )
        val openOnGuest = if (restoring) listOf(1L) else emptyList()
        val guestPipe = FakeGuestMuxPipe(pool, initialOpenStreams = openOnGuest)
        guestSessions.add(guestPipe)
        val client = MuxSessionClient(
            inputStream = guestPipe.hostReads,
            outputStream = guestPipe.hostWrites,
            sharesDir = File(rootDir, "shares"),
        )
        client.start(sendHostHello = true)
        if (restoring) {
            client.resync()
        } else {
            client.awaitHello()
        }
        muxClients[profile.id] = client
        runStates.value = runStates.value + (profile.id to SandboxRunState.RUNNING)
        return SandboxRunState.RUNNING
    }

    override suspend fun freezeSandbox(profileId: String): SandboxRunState {
        runStates.value = runStates.value + (profileId to SandboxRunState.FREEZING)
        hasCheckpoint.add(profileId)
        muxClients.remove(profileId)?.close()
        guestSessions.lastOrNull()?.close()
        runStates.value = runStates.value + (profileId to SandboxRunState.FROZEN)
        return SandboxRunState.FROZEN
    }

    override suspend fun stopSandbox(profileId: String) {
        muxClients.remove(profileId)?.close()
        runStates.value = runStates.value + (profileId to SandboxRunState.STOPPED)
    }

    override suspend fun stopAllSandboxes() {
        muxClients.keys.toList().forEach { stopSandbox(it) }
    }

    override suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) = Unit

    override suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState {
        if (checkpointAndRestore) {
            freezeSandbox(profileId)
        } else {
            stopSandbox(profileId)
        }
        return startOrResumeSandbox(
            SandboxProfile(id = profileId, displayName = profileId, imageRef = "alpine:3.21", portForwards = updatedRules),
        )
    }

    override suspend fun acquireWakeLock(minutes: Int): Int = minutes
    override suspend fun releaseWakeLock() = Unit
    override suspend fun discardCheckpoint(profileId: String): Boolean = hasCheckpoint.remove(profileId)
    override suspend fun hasActiveCheckpoint(profileId: String): Boolean = profileId in hasCheckpoint
    override suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult =
        ProxyTestResult(true, 5L, config.mode, "OK")
    override suspend fun listVolumes(): List<RungvVolumeLsEntry> = emptyList()
    override suspend fun createVolume(name: String) = Unit
    override suspend fun removeVolume(name: String, force: Boolean) = Unit
    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T = throw UnsupportedOperationException()

    override fun getMuxClient(profileId: String): MuxSessionClient? = muxClients[profileId]
    override fun getRawPtyHandle(profileId: String): HostPtyHandle? = null
    override fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int, ypixel: Int) = Unit
    override fun markProfileActivity(profileId: String) = Unit
    override fun resolveClipboardPrompt(allow: Boolean) = Unit
    override fun buildRungvHostEnv(trustHostCaCerts: Boolean, includeProxyVars: Boolean): Map<String, String> = emptyMap()
    override fun workspaceDir(profileId: String): File = File(rootDir, "workspaces/$profileId").apply { mkdirs() }
    override fun activeWifiIpv4(): String = "192.168.1.10"
    override fun openUrl(url: String) = Unit
    override fun shareText(text: String) = Unit
    override fun shareNamedFile(fileName: String, content: ByteArray) = Unit
    override fun startForegroundAction(action: String) = Unit
}

private class FakeGuestMuxPipe(
    executor: java.util.concurrent.ExecutorService,
    initialOpenStreams: List<Long> = emptyList(),
) {
    private val hostToGuestOut = PipedOutputStream()
    private val guestReads = PipedInputStream(hostToGuestOut, 64 * 1024)
    private val guestToHostOut = PipedOutputStream()
    val hostReads = PipedInputStream(guestToHostOut, 64 * 1024)
    val hostWrites = hostToGuestOut

    val openedStreamIds = CopyOnWriteArrayList<Long>(initialOpenStreams)
    private val streamInputs = ConcurrentHashMap<Long, StringBuilder>()
    private val resyncLatch = CountDownLatch(1)

    init {
        executor.execute {
            try {
                while (true) {
                    val frame = MuxCodec.readFrame(guestReads) ?: break
                    when (frame.type) {
                        MuxFrameType.MUX_HELLO -> {
                            val ack = MuxCodec.json.encodeToString(
                                MuxHelloPayload(protocol = 1, agentVersion = "test-1.0", initDaemon = false),
                            )
                            synchronized(guestToHostOut) {
                                MuxCodec.writeFrame(
                                    guestToHostOut,
                                    MuxFrame(MuxFrameType.MUX_HELLO, 0L, ack.toByteArray(StandardCharsets.UTF_8)),
                                )
                            }
                        }

                        MuxFrameType.MUX_RESYNC -> {
                            val items = openedStreamIds.joinToString(",") { id ->
                                """{"id":$id,"rows":24,"cols":80}"""
                            }
                            synchronized(guestToHostOut) {
                                MuxCodec.writeFrame(
                                    guestToHostOut,
                                    MuxFrame(
                                        MuxFrameType.MUX_RESYNC_ACK,
                                        0L,
                                        """{"openStreams":[$items]}""".toByteArray(StandardCharsets.UTF_8),
                                    ),
                                )
                            }
                            resyncLatch.countDown()
                        }

                        MuxFrameType.OPEN_PTY -> {
                            openedStreamIds.addIfAbsent(frame.streamId)
                            synchronized(guestToHostOut) {
                                MuxCodec.writeFrame(
                                    guestToHostOut,
                                    MuxFrame(MuxFrameType.OPEN_PTY_ACK, frame.streamId, byteArrayOf(0)),
                                )
                            }
                        }

                        MuxFrameType.PTY_DATA -> {
                            val text = String(frame.payload, StandardCharsets.UTF_8)
                            synchronized(streamInputs) {
                                streamInputs.computeIfAbsent(frame.streamId) { StringBuilder() }.append(text)
                            }
                        }

                        else -> Unit
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun awaitResync(timeoutMs: Long) {
        resyncLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun sendPtyDataToHost(streamId: Long, text: String) {
        synchronized(guestToHostOut) {
            MuxCodec.writeFrame(
                guestToHostOut,
                MuxFrame(MuxFrameType.PTY_DATA, streamId, text.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    fun receivedInputForStream(streamId: Long): String = synchronized(streamInputs) {
        streamInputs[streamId]?.toString().orEmpty()
    }

    fun close() {
        runCatching { guestToHostOut.close() }
        runCatching { guestReads.close() }
    }
}

private class RecordingVtBackend(
    private val chunks: CopyOnWriteArrayList<String> = CopyOnWriteArrayList(),
) : VtSessionBackend {
    override val nativeHandle: Long = 1L
    private var offset = 0

    override fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean = true
    override fun writePtyBytes(data: ByteArray, offset: Int, length: Int) {
        chunks.add(String(data, offset, length, StandardCharsets.UTF_8))
    }

    override fun setPalette(palette: ColorPalette) = Unit
    override fun setScrollbackOffset(linesFromBottom: Int) {
        offset = linesFromBottom
    }

    override fun getScrollbackOffset(): Int = offset
    override fun getScrollbackCount(): Int = 0
    override fun setSelection(active: Boolean, startRow: Int, startCol: Int, endRow: Int, endCol: Int) = Unit
    override fun clearSelection() = Unit
    override fun isDirty(): Boolean = false
    override fun isMouseTrackingActive(): Boolean = false
    override fun isBracketedPasteActive(): Boolean = false
    override fun isApplicationCursorActive(): Boolean = false
    override fun getWindowTitle(): String = ""
    override fun getCursorState(): VtCursorState = VtCursorState(0, 0, true, false, VtCursorShape.BLOCK)
    override fun encodeKey(event: VtKeyEvent): ByteArray = ByteArray(0)
    override fun encodeMouse(event: VtMouseEvent): ByteArray = ByteArray(0)
    override fun isPasteSafe(text: String): Boolean = true
    override fun encodePaste(text: String, allowUnsafe: Boolean): ByteArray = text.toByteArray()
    override fun formatText(
        emitVt: Boolean,
        useSelection: Boolean,
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
        trimTrailingWhitespace: Boolean,
        unwrapSoftWraps: Boolean,
    ): String = ""

    override fun searchScrollback(query: String, useRegex: Boolean, caseSensitive: Boolean): List<VtSearchMatch> = emptyList()
    override fun detectLinkAt(viewportRow: Int, viewportCol: Int): VtDetectedLink? = null
    override fun close() = Unit
}

private class SnapshotDataStore : DataStore<AppPersistenceState> {
    private val state = MutableStateFlow(AppPersistenceState())
    private val mutex = Mutex()
    override val data: Flow<AppPersistenceState> = state
    override suspend fun updateData(transform: suspend (t: AppPersistenceState) -> AppPersistenceState): AppPersistenceState =
        mutex.withLock {
            val next = transform(state.value)
            state.value = next
            next
        }
}

private class SnapshotSecretStore : SecretStore {
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
