package dev.gfire.termvr.ui

import androidx.datastore.core.DataStore
import dev.gfire.termvr.core.data.AppPersistenceState
import dev.gfire.termvr.core.data.AssetSource
import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvCommandExecutor
import dev.gfire.termvr.core.data.RungvCommandResult
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.core.ui.HostShortcutAction
import dev.gfire.termvr.core.ui.SplitDirection
import dev.gfire.termvr.core.ui.TerminalSplitNode
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
import dev.gfire.termvr.ui.catalog.CatalogViewModel
import dev.gfire.termvr.ui.files.FilesViewModel
import dev.gfire.termvr.ui.home.HomeViewModel
import dev.gfire.termvr.ui.network.NetworkViewModel
import dev.gfire.termvr.ui.network.resolveEffectiveHostPort
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import dev.gfire.termvr.ui.terminal.TerminalViewModel
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelsTest {
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
    fun networkViewModelBlocksDockerLan2375AndStagesLivePortRestarts() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        val devProfile = SandboxProfile(id = "dev-net", displayName = "Dev Net", imageRef = "alpine:3.21")
        profileRepo.upsertProfile(devProfile)
        gateway.runStates.value = mapOf("dev-net" to SandboxRunState.RUNNING)

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = NetworkViewModel(profileRepo, gateway, scope = vmScope)
        advanceUntilIdle()

        // Hard-block Docker port 2375 exposure on LAN
        vm.addPortRule(
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LAN_ALL_V4,
            hostPort = 2375,
            guestPort = 2375,
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage?.contains("2375") == true)
        assertTrue(vm.uiState.value.selectedProfile?.portForwards?.isEmpty() == true)

        // Loopback 127.0.0.1 port 8080 stages Checkpoint & Restore while RUNNING
        vm.addPortRule(
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LOOPBACK_V4,
            hostPort = 8080,
            guestPort = 8080,
        )
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertNotNull(vm.uiState.value.stagedPortApply)

        vm.confirmStagedPortApply(checkpointAndRestore = true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.stagedPortApply)
        assertEquals(listOf(Triple("dev-net", 1, true)), gateway.appliedPortCalls)

        // Toggling transparentTcp on a running sandbox flags restart required for --net-raw
        vm.saveOutboundProxy(
            mode = OutboundProxyMode.CUSTOM,
            protocol = ProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = 1080,
            username = "alice",
            password = "secret",
            routeDnsViaProxy = true,
            transparentTcp = true,
            noProxyEntries = OutboundProxyConfig.DEFAULT_NO_PROXY,
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.transparentTcpRestartRequired)
        assertNotNull(gateway.lastLiveProxyUpdate)

        vmScope.cancel()
    }

    @Test
    fun homeViewModelLaunchesQuickAlpineAndTransitionsStates() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = HomeViewModel(profileRepo, catalogRepo, gateway, coordinator, scope = vmScope)
        advanceUntilIdle()

        var navigated = false
        vm.launchQuickAlpine { navigated = true }
        advanceUntilIdle()

        assertTrue(navigated)
        assertEquals(SandboxRunState.RUNNING, vm.uiState.value.runStates[HomeViewModel.QUICK_ALPINE_PROFILE_ID])
        assertTrue(vm.uiState.value.profiles.any { it.id == HomeViewModel.QUICK_ALPINE_PROFILE_ID })

        vm.freezeProfile(HomeViewModel.QUICK_ALPINE_PROFILE_ID)
        advanceUntilIdle()
        assertEquals(SandboxRunState.FROZEN, vm.uiState.value.runStates[HomeViewModel.QUICK_ALPINE_PROFILE_ID])

        vm.stopProfile(HomeViewModel.QUICK_ALPINE_PROFILE_ID)
        advanceUntilIdle()
        assertEquals(SandboxRunState.STOPPED, vm.uiState.value.runStates[HomeViewModel.QUICK_ALPINE_PROFILE_ID])

        vmScope.cancel()
    }

    @Test
    fun catalogViewModelRaisesCalicoRamFloorAndRewritesExportedKubeconfig() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = CatalogViewModel(
            catalogRepository = catalogRepo,
            profileRepository = profileRepo,
            runtimeGateway = gateway,
            sessionCoordinator = coordinator,
            scope = vmScope,
            tcpProbe = { port -> port == 6443 },
            probeIntervalMs = null,
        )
        advanceUntilIdle()

        val k3s = vm.uiState.value.recipes.first { it.id == "k3s-single-node" }
        assertEquals(6144, vm.uiState.value.preflightFor(k3s).requiredRamMiB)
        assertEquals(2560, vm.uiState.value.preflightFor(k3s).profileRamMiB)

        vm.toggleCalicoVxlan()
        advanceUntilIdle()
        assertEquals(6144, vm.uiState.value.preflightFor(k3s).requiredRamMiB)
        assertEquals(4096, vm.uiState.value.preflightFor(k3s).profileRamMiB)

        var opened = false
        vm.launchRecipe(k3s) { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
        val savedK3s = profileRepo.getProfile("k3s-single-node")
        assertNotNull(savedK3s)
        assertEquals(4096, savedK3s!!.memoryMiB)
        assertTrue(savedK3s.initDaemonArgv.contains("--flannel-backend=none"))
        assertTrue(File(gateway.workspaceDir("k3s-single-node"), "calico-vxlan.yaml").isFile)

        // Create kubeconfig and export via share sheet
        val k3sDir = File(gateway.workspaceDir("k3s-single-node"), "k3s").apply { mkdirs() }
        File(k3sDir, "kubeconfig.yaml").writeText("server: https://10.88.0.2:6443\n")
        vm.exportKubeconfig("k3s-single-node")
        advanceUntilIdle()
        val shared = gateway.sharedFiles["kubeconfig.yaml"]
        assertNotNull(shared)
        assertTrue(shared!!.decodeToString().contains("https://127.0.0.1:6443"))

        vmScope.cancel()
    }

    @Test
    fun filesViewModelEnforcesFrozenLockAndUnlocksOnDiscard() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val profile = SandboxProfile(id = "files-box", displayName = "Files Box", imageRef = "alpine:3.21")
        profileRepo.upsertProfile(profile)
        gateway.activeCheckpoints.add("files-box")
        gateway.runStates.value = mapOf("files-box" to SandboxRunState.FROZEN)

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = FilesViewModel(profileRepo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.writeLocked)
        vm.createWorkspaceDirectory("blocked")
        advanceUntilIdle()
        assertFalse(File(gateway.workspaceDir("files-box"), "blocked").exists())

        vm.discardCheckpointAndUnlock()
        gateway.runStates.value = mapOf("files-box" to SandboxRunState.STOPPED)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.writeLocked)

        vm.createWorkspaceDirectory("allowed")
        advanceUntilIdle()
        assertTrue(File(gateway.workspaceDir("files-box"), "allowed").isDirectory)

        vmScope.cancel()
    }

    @Test
    fun terminalViewModelOpensDaemonAndShellTabsAndSplitsPanes() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val daemonProfile = SandboxProfile(
            id = "daemon-box",
            displayName = "Docker Box",
            imageRef = "docker:27-dind",
            initDaemonArgv = listOf("dockerd", "--host=tcp://127.0.0.1:2375"),
        )
        profileRepo.upsertProfile(daemonProfile)
        gateway.runStates.value = mapOf("daemon-box" to SandboxRunState.RUNNING)

        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val settings = profileRepo.observeAppSettings().first()
        coordinator.ensureTabsForProfile(
            profile = daemonProfile,
            maxScrollbackLines = settings.defaultScrollbackLines,
            palette = ColorPalette.DEFAULT_DARK,
        )

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = TerminalViewModel(profileRepo, gateway, coordinator, scope = vmScope)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.tabs.size)
        assertTrue(vm.uiState.value.tabs.first().isDaemonLogs)
        assertEquals("Daemon Logs", vm.uiState.value.tabs.first().title)

        val activeTab = vm.uiState.value.activeTab!!
        assertFalse(activeTab.isDaemonLogs)
        vm.splitActivePane(SplitDirection.HORIZONTAL)
        advanceUntilIdle()

        val splitTab = vm.uiState.value.activeTab!!
        assertTrue(splitTab.splitRoot is TerminalSplitNode.Split)
        assertEquals(2, splitTab.allPaneIds().size)

        vm.closeFocusedPane()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.activeTab!!.allPaneIds().size)

        vmScope.cancel()
    }

    @Test
    fun terminalViewModelHostShortcutsDoNotThrowOrOverrideSurfaceZoomAtBounds() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = TerminalViewModel(profileRepo, gateway, coordinator, scope = vmScope)
        advanceUntilIdle()

        val initialFont = vm.uiState.value.fontConfig
        vm.updateFontConfig(initialFont.copy(sizeSp = 8.0f))
        advanceUntilIdle()
        vm.handleHostShortcut(HostShortcutAction.ZOOM_OUT)
        advanceUntilIdle()
        assertEquals(8.0f, vm.uiState.value.fontConfig.sizeSp)

        vm.updateFontConfig(initialFont.copy(sizeSp = 32.0f))
        advanceUntilIdle()
        vm.handleHostShortcut(HostShortcutAction.ZOOM_IN)
        advanceUntilIdle()
        assertEquals(32.0f, vm.uiState.value.fontConfig.sizeSp)

        vmScope.cancel()
    }

    @Test
    fun resolveEffectiveHostPortMapsEphemeralRulesFromPublishedPorts() {
        val ephemeral = PortForwardRule(
            id = "pf-ephemeral",
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LOOPBACK_V4,
            hostPortStart = 0,
            hostPortEnd = 0,
            guestPortStart = 8080,
            guestPortEnd = 8080,
        )
        assertNull(resolveEffectiveHostPort(ephemeral, emptyList()))

        val published = listOf(
            PublishedPortMapping(
                protocol = ForwardProtocol.TCP,
                listenHost = ListenHost.LOOPBACK_V4.wireLiteral,
                hostPort = 43210,
                guestPort = 8080,
            ),
        )
        assertEquals(43210, resolveEffectiveHostPort(ephemeral, published))

        val explicit = ephemeral.copy(hostPortStart = 9090, hostPortEnd = 9090)
        assertEquals(9090, resolveEffectiveHostPort(explicit, published))
    }

    @Test
    fun catalogViewModelOpensDaemonLogsFirstAndWaitsForReadinessBeforeOpeningShell() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        var dindReady = false
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = CatalogViewModel(
            catalogRepository = catalogRepo,
            profileRepository = profileRepo,
            runtimeGateway = gateway,
            sessionCoordinator = coordinator,
            scope = vmScope,
            tcpProbe = { port -> port == 2375 && dindReady },
            probeIntervalMs = null,
            readinessPollStepMs = 100L,
        )
        advanceUntilIdle()

        val dind = vm.uiState.value.recipes.first { it.id == "docker-dind" }
        var navigated = false
        vm.launchRecipe(dind) { navigated = true }
        runCurrent()

        assertTrue(navigated)
        assertEquals(1, coordinator.tabs.value.size)
        assertTrue(coordinator.tabs.value.first().isDaemonLogs)
        assertEquals(coordinator.tabs.value.first().tabId, coordinator.activeTabId.value)

        dindReady = true
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(2, coordinator.tabs.value.size)
        val shellTab = coordinator.tabs.value.firstOrNull { !it.isDaemonLogs }
        assertNotNull(shellTab)
        assertEquals(shellTab!!.tabId, coordinator.activeTabId.value)

        vmScope.cancel()
    }

    private fun createFixtures(scope: CoroutineScope): Triple<ProfileRepository, CatalogRepository, FakeGateway> {
        val secretStore = InMemorySecretStore()
        val dataStore = InMemoryDataStore()
        val profileRepo = ProfileRepository(dataStore, tempDir, secretStore)

        val assetsRoot = findCoreDataAssetsDir()
        val assetSource = AssetSource { path -> FileInputStream(File(assetsRoot, path)) }
        val catalogRepo = CatalogRepository(
            filesDir = tempDir,
            assetSource = assetSource,
            commandExecutor = RungvCommandExecutor { _, _ -> RungvCommandResult(0, "", "") },
            hostAbiProvider = { "arm64-v8a" },
        )
        val gateway = FakeGateway(tempDir)
        return Triple(profileRepo, catalogRepo, gateway)
    }

    private fun findCoreDataAssetsDir(): File {
        val candidates = listOf(
            File("core/data/src/main/assets"),
            File("../core/data/src/main/assets"),
        )
        return candidates.first { it.isDirectory }
    }
}

private class InMemoryDataStore : DataStore<AppPersistenceState> {
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

private class InMemorySecretStore : SecretStore {
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

private class FakeGateway(private val rootDir: File) : SandboxRuntimeGateway {
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
    val appliedPortCalls = mutableListOf<Triple<String, Int, Boolean>>()
    val sharedFiles = mutableMapOf<String, ByteArray>()
    var lastLiveProxyUpdate: OutboundProxyConfig? = null

    override fun refreshHostReadiness() = Unit
    override fun setNotificationsPermissionGranted(granted: Boolean) {
        notificationsDeniedBanner.value = !granted
    }

    override fun clearNotice() {
        activeServiceNotice.value = null
    }

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
        runStates.value = runStates.value + (profileId to SandboxRunState.STOPPED)
    }

    override suspend fun stopAllSandboxes() {
        runStates.value = runStates.value.mapValues { SandboxRunState.STOPPED }
    }

    override suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) {
        lastLiveProxyUpdate = outboundProxy
    }

    override suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState {
        appliedPortCalls.add(Triple(profileId, updatedRules.size, checkpointAndRestore))
        return SandboxRunState.RUNNING
    }

    override suspend fun acquireWakeLock(minutes: Int): Int {
        wakeLockRemainingSeconds.value = minutes * 60L
        return minutes
    }

    override suspend fun releaseWakeLock() {
        wakeLockRemainingSeconds.value = null
    }

    override suspend fun discardCheckpoint(profileId: String): Boolean = activeCheckpoints.remove(profileId)

    override suspend fun hasActiveCheckpoint(profileId: String): Boolean = profileId in activeCheckpoints

    override suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult =
        ProxyTestResult(success = true, latencyMillis = 12L, effectiveMode = config.mode, detail = "OK")

    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T = throw UnsupportedOperationException("No volume mux in unit fake")

    override fun getMuxClient(profileId: String): MuxSessionClient? = null
    override fun getRawPtyHandle(profileId: String): HostPtyHandle? = null
    override fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int, ypixel: Int) = Unit
    override fun markProfileActivity(profileId: String) = Unit
    override fun resolveClipboardPrompt(allow: Boolean) {
        clipboardReadPrompt.value = null
    }

    override fun buildRungvHostEnv(trustHostCaCerts: Boolean, includeProxyVars: Boolean): Map<String, String> = emptyMap()

    override fun workspaceDir(profileId: String): File = File(rootDir, "workspaces/$profileId").apply { mkdirs() }

    override fun activeWifiIpv4(): String = "192.168.1.50"

    override fun openUrl(url: String) = Unit
    override fun shareText(text: String) = Unit
    override fun shareNamedFile(fileName: String, content: ByteArray) {
        sharedFiles[fileName] = content
    }

    override fun startForegroundAction(action: String) = Unit
}

private class FakeVtBackend : VtSessionBackend {
    override val nativeHandle: Long = 1L
    private var offset = 0

    override fun resizeGrid(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int): Boolean = true
    override fun writePtyBytes(data: ByteArray, offset: Int, length: Int) = Unit
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
