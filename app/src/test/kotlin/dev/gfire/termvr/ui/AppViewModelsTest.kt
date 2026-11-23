package dev.gfire.termvr.ui

import androidx.datastore.core.DataStore
import dev.gfire.termvr.core.data.AppPersistenceState
import dev.gfire.termvr.core.data.AssetSource
import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvCommandExecutor
import dev.gfire.termvr.core.data.RungvCommandResult
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountMode
import dev.gfire.termvr.core.model.VolumeMountSpec
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
import dev.gfire.termvr.ui.files.FileRootTab
import dev.gfire.termvr.ui.files.FilesViewModel
import dev.gfire.termvr.ui.home.HomeViewModel
import dev.gfire.termvr.ui.home.formatBitsPerSecond
import dev.gfire.termvr.ui.licenses.LicenseCatalogLoader
import dev.gfire.termvr.ui.network.NetworkViewModel
import dev.gfire.termvr.ui.network.resolveEffectiveHostPort
import dev.gfire.termvr.ui.settings.SettingsTab
import dev.gfire.termvr.ui.settings.SettingsViewModel
import androidx.compose.ui.unit.dp
import dev.gfire.termvr.ui.terminal.TerminalSessionCoordinator
import dev.gfire.termvr.ui.terminal.TerminalTabState
import dev.gfire.termvr.ui.terminal.formatTabLabel
import dev.gfire.termvr.ui.terminal.shouldConfirmPaneClose
import dev.gfire.termvr.ui.terminal.shouldConfirmTabClose
import dev.gfire.termvr.ui.terminal.shouldShowInlineTabClose
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

        // Loopback 127.0.0.1 port 8080 applies LIVE on NAT without staging restart!
        vm.addPortRule(
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LOOPBACK_V4,
            hostPort = 8080,
            guestPort = 8080,
        )
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertNull(vm.uiState.value.stagedPortApply)
        assertEquals(1, gateway.liveAddedPorts.size)
        assertEquals(8080, gateway.liveAddedPorts.single().second.hostPortStart)

        // LAN 0.0.0.0 port 8081 stages Checkpoint & Restore while RUNNING (lockdown forbids dynamic LAN binds)
        vm.addPortRule(
            protocol = ForwardProtocol.TCP,
            listenHost = ListenHost.LAN_ALL_V4,
            hostPort = 8081,
            guestPort = 8081,
        )
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertNotNull(vm.uiState.value.stagedPortApply)

        vm.confirmStagedPortApply(checkpointAndRestore = true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.stagedPortApply)
        assertEquals(listOf(Triple("dev-net", 2, true)), gateway.appliedPortCalls)

        // Toggling isolateLanEgress on live NAT applies LIVE via resource.update
        vm.updateNetworkBackend(NetBackend.NAT, isolateLanEgress = true, wireGuardName = null)
        advanceUntilIdle()
        assertEquals(true, gateway.lastLiveResourceUpdate?.second?.isolateLan)

        // Setting NAT TX/RX Mbps caps on live NAT applies LIVE via resource.update and persists to profile
        vm.updateNatBandwidthLimits(txMbpsRaw = "12.5", rxMbpsRaw = "50")
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(12_500_000L, gateway.lastLiveResourceUpdate?.second?.netTxBps)
        assertEquals(50_000_000L, gateway.lastLiveResourceUpdate?.second?.netRxBps)
        assertEquals(12_500_000L, vm.uiState.value.selectedProfile?.network?.netTxBps)
        assertEquals(50_000_000L, vm.uiState.value.selectedProfile?.network?.netRxBps)

        // Clearing bandwidth inputs removes caps (0 bps)
        vm.updateNatBandwidthLimits(txMbpsRaw = "", rxMbpsRaw = "0")
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals(0L, gateway.lastLiveResourceUpdate?.second?.netTxBps)
        assertEquals(0L, gateway.lastLiveResourceUpdate?.second?.netRxBps)

        // Negative or non-numeric bandwidth inputs are rejected without mutating live limits
        vm.updateNatBandwidthLimits(txMbpsRaw = "-5", rxMbpsRaw = "10")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage?.contains("Mbps") == true)

        // Removing a live NAT port rule calls removePortRuleLive without staging restart
        val loopbackId = vm.uiState.value.selectedProfile!!.portForwards.first { it.hostPortStart == 8080 }.id
        vm.removePortRule(loopbackId)
        advanceUntilIdle()
        assertEquals(1, gateway.liveRemovedPorts.size)
        assertNull(vm.uiState.value.stagedPortApply)

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
    fun homeViewModelLaunchesQuickDebianWithUnlimitedDefaultsAndTransitionsStates() = runTest(testDispatcher) {
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
        vm.launchQuickDebian { navigated = true }
        advanceUntilIdle()

        assertTrue(navigated)
        assertEquals(SandboxRunState.RUNNING, vm.uiState.value.runStates[HomeViewModel.QUICK_DEBIAN_PROFILE_ID])
        val debian = vm.uiState.value.profiles.first { it.id == HomeViewModel.QUICK_DEBIAN_PROFILE_ID }
        assertEquals("docker.io/library/debian:trixie-slim", debian.imageRef)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, debian.memoryMiB)
        assertEquals(SandboxProfile.UNLIMITED_CPU_CORES, debian.cpuCores)

        vm.freezeProfile(HomeViewModel.QUICK_DEBIAN_PROFILE_ID)
        advanceUntilIdle()
        assertEquals(SandboxRunState.FROZEN, vm.uiState.value.runStates[HomeViewModel.QUICK_DEBIAN_PROFILE_ID])

        vm.stopProfile(HomeViewModel.QUICK_DEBIAN_PROFILE_ID)
        advanceUntilIdle()
        assertEquals(SandboxRunState.STOPPED, vm.uiState.value.runStates[HomeViewModel.QUICK_DEBIAN_PROFILE_ID])

        vmScope.cancel()
    }

    @Test
    fun homeViewModelSupportsConcurrentContainersFreezeAllStopAllAndVolumeAttach() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        gateway.namedVolumes.add(RungvVolumeLsEntry(name = "shared-data", sizeBytes = 4096))
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = HomeViewModel(profileRepo, catalogRepo, gateway, coordinator, scope = vmScope)
        advanceUntilIdle()

        vm.createProfile("Worker A", "docker.io/library/debian:trixie-slim")
        vm.createProfile("Worker B", "docker.io/library/alpine:3.21")
        vm.createProfile("", "")
        advanceUntilIdle()

        val pA = vm.uiState.value.profiles.first { it.displayName == "Worker A" }
        val pB = vm.uiState.value.profiles.first { it.displayName == "Worker B" }
        val pDefault = vm.uiState.value.profiles.first { it.id == "debian" }
        assertEquals("Debian", pDefault.displayName)
        assertTrue(pDefault.defaultShellArgv.isEmpty())
        assertTrue(pA.defaultShellArgv.isEmpty())
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, pA.memoryMiB)
        assertEquals(SandboxProfile.UNLIMITED_CPU_CORES, pA.cpuCores)

        vm.startOrOpenProfile(pA) {}
        vm.startOrOpenProfile(pB) {}
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.runningCount)

        vm.attachVolumeToProfile(pA.id, "shared-data", "/data")
        advanceUntilIdle()
        val updatedA = profileRepo.getProfile(pA.id)!!
        assertTrue(updatedA.volumes.any { it.kind == VolumeKind.NAMED_EXT4LITE && it.sourceName == "shared-data" && it.guestMountPath == "/data" })

        vm.freezeAllRunning()
        advanceUntilIdle()
        assertEquals(SandboxRunState.FROZEN, vm.uiState.value.runStates[pA.id])
        assertEquals(SandboxRunState.FROZEN, vm.uiState.value.runStates[pB.id])

        vm.stopAllRunning()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.runningCount)

        vmScope.cancel()
    }

    @Test
    fun catalogViewModelKeepsUnlimitedResourcesWithCalicoAndRewritesExportedKubeconfig() = runTest(testDispatcher) {
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
        assertEquals(0, vm.uiState.value.preflightFor(k3s).requiredRamMiB)
        assertEquals(0L, vm.uiState.value.preflightFor(k3s).requiredDiskMiB)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, vm.uiState.value.preflightFor(k3s).profileRamMiB)
        assertTrue(vm.uiState.value.preflightFor(k3s).passed)

        vm.toggleCalicoVxlan()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.preflightFor(k3s).requiredRamMiB)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, vm.uiState.value.preflightFor(k3s).profileRamMiB)

        var opened = false
        vm.launchRecipe(k3s) { opened = true }
        advanceUntilIdle()
        assertTrue(opened)
        val savedK3s = profileRepo.getProfile("k3s-single-node")
        assertNotNull(savedK3s)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, savedK3s!!.memoryMiB)
        assertEquals(SandboxProfile.UNLIMITED_CPU_CORES, savedK3s.cpuCores)
        assertEquals("legacy", savedK3s.envVars["IPTABLES_MODE"])
        assertEquals("/workspace/k3s/kubeconfig.yaml", savedK3s.envVars["KUBECONFIG"])
        assertTrue(savedK3s.initDaemonArgv.contains("--flannel-backend=none"))
        assertTrue(File(gateway.workspaceDir("k3s-single-node"), "k3s").isDirectory)
        assertTrue(File(gateway.workspaceDir("k3s-single-node"), "calico-vxlan.yaml").isFile)

        // Create kubeconfig and export via share sheet
        val k3sDir = File(gateway.workspaceDir("k3s-single-node"), "k3s")
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
        assertEquals(".", vm.toVolumeRelativePath("/"))
        assertEquals("hello.txt", vm.toVolumeRelativePath("/hello.txt"))
        assertEquals("sub/dir", vm.toVolumeRelativePath("/sub/dir/"))

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

        val savedDind = profileRepo.getProfile("docker-dind")
        assertNotNull(savedDind)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, savedDind!!.memoryMiB)
        assertEquals(SandboxProfile.UNLIMITED_CPU_CORES, savedDind.cpuCores)
        assertEquals("1", savedDind.envVars["DOCKER_IPTABLES_LEGACY"])
        assertEquals("unix:///var/run/docker.sock", savedDind.envVars["DOCKER_HOST"])
        assertFalse(savedDind.initDaemonArgv.any { it.startsWith("--host=") })

        vmScope.cancel()
    }

    @Test
    fun terminalViewModelHidesSearchByDefaultAndOpensTabsAcrossMultipleContainers() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val p1 = SandboxProfile(id = "deb-1", displayName = "Debian 1", imageRef = "docker.io/library/debian:trixie-slim")
        val p2 = SandboxProfile(id = "alp-2", displayName = "Alpine 2", imageRef = "docker.io/library/alpine:3.21")
        profileRepo.upsertProfile(p1)
        profileRepo.upsertProfile(p2)
        gateway.runStates.value = mapOf(p1.id to SandboxRunState.RUNNING, p2.id to SandboxRunState.RUNNING)

        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = TerminalViewModel(profileRepo, gateway, coordinator, scope = vmScope)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.searchVisible)

        vm.openNewTabForProfile(p1)
        vm.openNewTabForProfile(p2)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.tabs.size)
        assertEquals(p2.id, vm.uiState.value.activeTab?.profileId)

        vm.openSearch()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.searchVisible)

        vm.closeSearch()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.searchVisible)

        vmScope.cancel()
    }

    @Test
    fun catalogViewModelListsDebianFirstWithUnlimitedDefaults() = runTest(testDispatcher) {
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
            probeIntervalMs = null,
        )
        advanceUntilIdle()

        val firstDistro = vm.uiState.value.distributions.first()
        assertEquals("debian-trixie", firstDistro.id)
        assertEquals(SandboxProfile.UNLIMITED_MEMORY_MIB, firstDistro.defaultMemoryMiB)

        vmScope.cancel()
    }

    @Test
    fun filesViewModelCreatesMountsUnmountsAndDeletesNamedVolumes() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val profile = SandboxProfile(id = "deb-vol", displayName = "Debian", imageRef = "docker.io/library/debian:trixie-slim")
        profileRepo.upsertProfile(profile)

        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = FilesViewModel(profileRepo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectRootTab(FileRootTab.VOLUME)
        vm.createNamedVolume("cache-vol")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.volumes.any { it.name == "cache-vol" })

        vm.mountVolumeToProfile("cache-vol", profile.id, "/data")
        advanceUntilIdle()
        val mountedVol = vm.uiState.value.volumes.first { it.name == "cache-vol" }
        assertEquals(1, mountedVol.mounts.size)
        assertEquals("/data", mountedVol.mounts.first().guestMountPath)

        // Running container blocks volume deletion
        gateway.runStates.value = mapOf(profile.id to SandboxRunState.RUNNING)
        advanceUntilIdle()
        vm.deleteNamedVolume("cache-vol")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.volumes.any { it.name == "cache-vol" })

        // Unmount & stop, then delete succeeds
        gateway.runStates.value = mapOf(profile.id to SandboxRunState.STOPPED)
        vm.unmountVolumeFromProfile("cache-vol", profile.id)
        advanceUntilIdle()
        vm.deleteNamedVolume("cache-vol")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.volumes.any { it.name == "cache-vol" })

        vmScope.cancel()
    }

    @Test
    fun settingsViewModelLoadsLicensesSystemInfoAndTermVrMonoDefault() = runTest(testDispatcher) {
        val (profileRepo, _, gateway) = createFixtures(backgroundScope)
        val assetsRoot = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, LicenseCatalogLoader.INDEX_ASSET_PATH).isFile }
        val loader = LicenseCatalogLoader { rel -> File(assetsRoot, rel).inputStream() }
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val vm = SettingsViewModel(
            profileRepository = profileRepo,
            runtimeGateway = gateway,
            licenseLoader = loader,
            appVersionName = "0.1.0-test",
            gvxVersion = "0.1.0-gvx",
            scope = vmScope,
        )
        advanceUntilIdle()

        assertEquals(FontConfig.DEFAULT_FAMILY_ID, vm.uiState.value.fontConfig.familyId)
        assertEquals(8, vm.uiState.value.licenses.size)
        assertEquals("arm64-v8a", vm.uiState.value.hostReadiness?.hostAbi)

        vm.selectTab(SettingsTab.LICENSES)
        val entry = vm.uiState.value.licenses.first { it.id == "termvr-mono-nf" }
        vm.openLicense(entry)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.activeLicenseText)
        assertTrue(vm.uiState.value.activeLicenseText!!.isNotBlank())

        vm.closeLicense()
        advanceUntilIdle()
        assertNull(vm.uiState.value.activeLicenseText)
        vmScope.cancel()
    }

    @Test
    fun persistentRootfsToggleCreatesPersistedProfileCleansUpRootfsVolumeAndListsInFiles() = runTest(testDispatcher) {
        val (profileRepo, catalogRepo, gateway) = createFixtures(backgroundScope)
        val coordinator = TerminalSessionCoordinator(
            runtimeGateway = gateway,
            sessionFactory = { maxLines ->
                NativeVtSession(maxScrollbackLines = maxLines, backendFactory = { _, _, _ -> FakeVtBackend() })
            },
        )
        val vmScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val homeVm = HomeViewModel(profileRepo, catalogRepo, gateway, coordinator, scope = vmScope)
        val catalogVm = CatalogViewModel(
            catalogRepository = catalogRepo,
            profileRepository = profileRepo,
            runtimeGateway = gateway,
            sessionCoordinator = coordinator,
            scope = vmScope,
            probeIntervalMs = null,
        )
        val filesVm = FilesViewModel(profileRepo, gateway, scope = vmScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()

        // HomeViewModel: create with persistentRootfs = true
        homeVm.createProfile(
            displayName = "Persist Home",
            imageRef = "docker.io/library/debian:trixie-slim",
            persistentRootfs = true,
        )
        advanceUntilIdle()
        val homeProfile = profileRepo.observeProfiles().first().first { it.displayName == "Persist Home" }
        assertTrue(homeProfile.persistentRootfs)

        // Simulate ext4lite volume created on boot
        gateway.createVolume(homeProfile.rootfsVolumeName)
        filesVm.selectRootTab(FileRootTab.VOLUME)
        filesVm.refreshVolumeList()
        advanceUntilIdle()
        val listedRootVol = filesVm.uiState.value.volumes.first { it.name == homeProfile.rootfsVolumeName }
        assertTrue(listedRootVol.mounts.any { it.profileId == homeProfile.id && it.guestMountPath == "/" })

        // Browsing stopped persistent rootfs volume passes ownerProfile and isRootfsVolume = true
        filesVm.browseVolume(homeProfile.rootfsVolumeName)
        advanceUntilIdle()
        val volCall = gateway.lastVolumeClientCall
        assertEquals(homeProfile.id, volCall?.profile?.id)
        assertEquals(homeProfile.rootfsVolumeName, volCall?.volumeName)
        assertEquals(true, volCall?.readOnly)
        assertEquals(true, volCall?.isRootfsVolume)

        // Deleting profile cleans up its rootfs-<id> volume
        homeVm.deleteProfile(homeProfile.id)
        advanceUntilIdle()
        assertFalse(gateway.namedVolumes.any { it.name == homeProfile.rootfsVolumeName })

        // CatalogViewModel: toggle persistence on a distro entry before launch
        val alpine = catalogVm.uiState.value.distributions.first { it.distroSlug == "alpine" }
        catalogVm.toggleDistributionPersistentRootfs(alpine.id)
        advanceUntilIdle()
        assertTrue(alpine.id in catalogVm.uiState.value.persistentDistroIds)
        catalogVm.createAndLaunchDistribution(alpine, pullFirst = false, onOpenTerminal = {})
        advanceUntilIdle()

        val catalogProfile = profileRepo.observeProfiles().first().first { it.id == alpine.id }
        assertTrue(catalogProfile.persistentRootfs)
        assertEquals(SandboxRunState.RUNNING, gateway.runStates.value[catalogProfile.id])

        vmScope.cancel()
    }

    @Test
    fun terminalTabBarLabelAndCloseConfirmationRules() {
        val debian = SandboxProfile(id = "debian", displayName = "Debian", imageRef = "debian:bookworm-slim")
        val session = NativeVtSession(maxScrollbackLines = 100, backendFactory = { _, _, _ -> FakeVtBackend() })
        val liveTab = TerminalTabState(
            tabId = "tab-1",
            profileId = "debian",
            title = "Shell #1",
            isDaemonLogs = false,
            splitRoot = TerminalSplitNode.Leaf("pane-1", session),
            focusedPaneId = "pane-1",
        )
        val oscTab = liveTab.copy(sessionTitle = "bash")
        val logsTab = TerminalTabState(
            tabId = "tab-logs",
            profileId = "debian",
            title = "Logs",
            isDaemonLogs = true,
            splitRoot = TerminalSplitNode.Leaf("pane-logs", session),
            focusedPaneId = "pane-logs",
        )
        val exitedTab = liveTab.copy(exitedPanes = setOf("pane-1"))

        assertEquals("Shell 1", formatTabLabel(liveTab, listOf(debian), openContainerCount = 1))
        assertEquals("Debian · Shell 1", formatTabLabel(liveTab, listOf(debian), openContainerCount = 2))
        assertEquals("bash", formatTabLabel(oscTab, listOf(debian), openContainerCount = 1))
        assertEquals("Debian · bash", formatTabLabel(oscTab, listOf(debian), openContainerCount = 2))
        assertEquals("Logs", formatTabLabel(logsTab, listOf(debian), openContainerCount = 1))
        assertEquals("Debian Logs", formatTabLabel(logsTab, listOf(debian), openContainerCount = 2))

        // Compact phone (< 600dp) hides inline X on unselected background tabs when >= 2 tabs exist.
        assertFalse(shouldShowInlineTabClose(liveTab, selected = false, tabCount = 2, screenWidthDp = 390, tabRowViewportWidthDp = 280.dp))
        assertTrue(shouldShowInlineTabClose(liveTab, selected = true, tabCount = 2, screenWidthDp = 390, tabRowViewportWidthDp = 280.dp))
        assertTrue(shouldShowInlineTabClose(liveTab, selected = false, tabCount = 1, screenWidthDp = 390, tabRowViewportWidthDp = 280.dp))
        assertTrue(shouldShowInlineTabClose(liveTab, selected = false, tabCount = 2, screenWidthDp = 840, tabRowViewportWidthDp = 640.dp))
        assertFalse(shouldShowInlineTabClose(logsTab, selected = true, tabCount = 2, screenWidthDp = 840, tabRowViewportWidthDp = 640.dp))

        // Confirmation required on compact/narrow live tabs, skipped on wide tablets or already-exited tabs.
        assertTrue(shouldConfirmTabClose(liveTab, screenWidthDp = 390, tabRowViewportWidthDp = 280.dp, tabCount = 2))
        assertTrue(shouldConfirmTabClose(liveTab, screenWidthDp = 840, tabRowViewportWidthDp = 380.dp, tabCount = 2))
        assertTrue(shouldConfirmTabClose(liveTab, screenWidthDp = 840, tabRowViewportWidthDp = 500.dp, tabCount = 4))
        assertFalse(shouldConfirmTabClose(liveTab, screenWidthDp = 840, tabRowViewportWidthDp = 640.dp, tabCount = 2))
        assertFalse(shouldConfirmTabClose(exitedTab, screenWidthDp = 390, tabRowViewportWidthDp = 280.dp, tabCount = 2))

        assertTrue(shouldConfirmPaneClose(liveTab, paneId = "pane-1", screenWidthDp = 390, tabRowViewportWidthDp = 280.dp, tabCount = 1))
        assertFalse(shouldConfirmPaneClose(exitedTab, paneId = "pane-1", screenWidthDp = 390, tabRowViewportWidthDp = 280.dp, tabCount = 1))
        assertFalse(shouldConfirmPaneClose(liveTab, paneId = "pane-1", screenWidthDp = 840, tabRowViewportWidthDp = 640.dp, tabCount = 2))
    }

    @Test
    fun homeViewModelSupportsOverlayMountModesAndInlineSharedGitWorktreeProvisioning() = runTest(testDispatcher) {
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

        // Create first container with inline Shared Git Worktree project creation!
        vm.createProfile(
            displayName = "Alpha Agent",
            imageRef = "debian:trixie-slim",
            gitWorktree = GitWorktreeSpec(projectId = "core-engine", branchName = "wt/alpha-agent"),
            gitProjectDisplayName = "Core Engine",
        )
        advanceUntilIdle()

        val alpha = vm.uiState.value.profiles.first { it.id == "alpha-agent" }
        assertEquals(GitWorktreeSpec("core-engine", "wt/alpha-agent"), alpha.gitWorktree)
        assertEquals(listOf("core-engine"), vm.uiState.value.gitProjects.map { it.id })
        assertEquals("Core Engine", vm.uiState.value.gitProjects.single().name)
        assertTrue(File(tempDir, "git-projects/core-engine/worktrees/alpha-agent/.git").isFile)

        // Creating a second container on the SAME branch wt/alpha-agent must report a branch collision error!
        vm.createProfile(
            displayName = "Beta Agent",
            imageRef = "debian:trixie-slim",
            gitWorktree = GitWorktreeSpec(projectId = "core-engine", branchName = "wt/alpha-agent"),
        )
        advanceUntilIdle()
        assertTrue(vm.uiState.value.errorMessage?.contains("already checked out") == true)

        // Creating Beta Agent on distinct branch wt/beta-agent succeeds AND preserves "Core Engine" display name!
        vm.createProfile(
            displayName = "Beta Agent",
            imageRef = "debian:trixie-slim",
            gitWorktree = GitWorktreeSpec(projectId = "core-engine", branchName = "wt/beta-agent"),
        )
        advanceUntilIdle()
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("Core Engine", vm.uiState.value.gitProjects.single().name)

        // Editing Beta Agent config without a new project display name and re-provisioning on launch also preserve "Core Engine"!
        vm.updateProfileConfig(
            profileId = "beta-agent",
            displayName = "Beta Agent v2",
            memoryMiB = SandboxProfile.UNLIMITED_MEMORY_MIB,
            cpuCores = SandboxProfile.UNLIMITED_CPU_CORES,
            persistentRootfs = false,
            gitWorktree = GitWorktreeSpec(projectId = "core-engine", branchName = "wt/beta-agent-v2"),
            gitProjectDisplayName = null,
        )
        advanceUntilIdle()
        gateway.gitWorktreeManager().provisionWorktreeForContainer(
            profileId = "beta-agent",
            spec = GitWorktreeSpec(projectId = "core-engine", branchName = "wt/beta-agent-v2"),
        )
        vm.refreshGitProjects()
        advanceUntilIdle()
        assertEquals("Core Engine", vm.uiState.value.gitProjects.single().name)

        // Attach Shared Read-Only, CoW Persistent Branch, and CoW Scratch mounts on Alpha Agent
        vm.attachVolumeToProfile(
            profileId = "alpha-agent",
            volumeName = "toolchain-base",
            guestMountPath = "/opt/ro",
            mode = VolumeMountMode.SHARED_RO,
        )
        vm.attachVolumeToProfile(
            profileId = "alpha-agent",
            volumeName = "toolchain-base",
            guestMountPath = "/opt/cow-persist",
            mode = VolumeMountMode.COW_PERSISTENT,
        )
        vm.attachVolumeToProfile(
            profileId = "alpha-agent",
            volumeName = "toolchain-base",
            guestMountPath = "/opt/cow-ram",
            mode = VolumeMountMode.COW_EPHEMERAL,
        )
        advanceUntilIdle()

        val updatedAlpha = vm.uiState.value.profiles.first { it.id == "alpha-agent" }
        val roMount = updatedAlpha.volumes.first { it.guestMountPath == "/opt/ro" }
        assertEquals(VolumeMountMode.SHARED_RO, roMount.mountMode)
        val persistMount = updatedAlpha.volumes.first { it.guestMountPath == "/opt/cow-persist" }
        assertEquals(VolumeKind.OVERLAY_EXT4LITE, persistMount.kind)
        assertEquals("toolchain-base-wt-alpha-agent", persistMount.overlayUpperVolumeName)
        val ramMount = updatedAlpha.volumes.first { it.guestMountPath == "/opt/cow-ram" }
        assertEquals(VolumeKind.OVERLAY_EXT4LITE, ramMount.kind)
        assertNull(ramMount.overlayUpperVolumeName)

        // Deleting Alpha Agent detaches its worktree so wt/alpha-agent is freed!
        vm.deleteProfile("alpha-agent")
        advanceUntilIdle()
        assertEquals(1, gateway.gitWorktreeManager().listProjects().single().worktreeCount)

        vmScope.cancel()
    }

    @Test
    fun homeViewModelDrivesPauseResumeLiveResourceUpdateHotMountAndInspector() = runTest(testDispatcher) {
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

        vm.createProfile("Live Box", "docker.io/library/debian:trixie-slim", pidsLimit = 128)
        advanceUntilIdle()
        val liveBox = vm.uiState.value.profiles.single()
        assertEquals(128, liveBox.pidsLimit)

        vm.startOrOpenProfile(liveBox) {}
        advanceUntilIdle()
        assertEquals(SandboxRunState.RUNNING, vm.uiState.value.runStates[liveBox.id])

        // Pause & unpause
        vm.pauseProfile(liveBox.id)
        advanceUntilIdle()
        assertEquals(SandboxRunState.PAUSED, vm.uiState.value.runStates[liveBox.id])
        assertEquals(1, vm.uiState.value.pausedCount)
        assertEquals(1, vm.uiState.value.liveInMemoryCount)

        vm.unpauseProfile(liveBox.id)
        advanceUntilIdle()
        assertEquals(SandboxRunState.RUNNING, vm.uiState.value.runStates[liveBox.id])

        // Live RAM/CPU/PIDs edit applies via updateResourcesLive without restart notice
        vm.updateProfileConfig(
            profileId = liveBox.id,
            displayName = "Live Box",
            memoryMiB = 768,
            cpuCores = 1.5,
            persistentRootfs = false,
            gitWorktree = null,
            pidsLimit = 256,
        )
        advanceUntilIdle()
        assertEquals(768, gateway.lastLiveResourceUpdate?.second?.memoryMiB)
        assertEquals(1.5, gateway.lastLiveResourceUpdate?.second?.cpuCores)
        assertEquals(256, gateway.lastLiveResourceUpdate?.second?.pidsLimit)
        assertTrue(vm.uiState.value.statusMessage.orEmpty().contains("live"))

        // Hot-attach and hot-detach named ext4lite volume
        vm.attachVolumeToProfile(liveBox.id, "hot-data", "/data", VolumeMountMode.EXCLUSIVE_RW)
        advanceUntilIdle()
        assertEquals(listOf("hot-data"), gateway.liveAttachedVolumes.map { it.second.sourceName })
        assertTrue(vm.uiState.value.statusMessage.orEmpty().contains("live"))

        vm.detachVolumeFromProfile(liveBox.id, "hot-data", "/data")
        advanceUntilIdle()
        assertEquals(listOf("/data"), gateway.liveDetachedMounts.map { it.second })

        // Inspector loads snapshot, processes, mounts, dmesg, and dispatches signal/clear
        vm.openInspector(liveBox.id)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.inspector)
        assertEquals(liveBox.id, vm.uiState.value.inspector!!.inspect?.id)
        vm.signalInspectorProcess(42, "SIGTERM")
        advanceUntilIdle()
        assertEquals(listOf(Triple(liveBox.id, 42, "SIGTERM")), gateway.signaledProcesses)
        vm.clearInspectorKernelLog()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.inspector!!.kernelLog.isEmpty())
        vm.closeInspector()
        advanceUntilIdle()
        assertNull(vm.uiState.value.inspector)

        assertEquals("Unlimited", formatBitsPerSecond(0L))
        assertEquals("500 bps", formatBitsPerSecond(500L))
        assertEquals("250 Kbps", formatBitsPerSecond(250_000L))
        assertEquals("25 Mbps", formatBitsPerSecond(25_000_000L))
        assertEquals("12.5 Mbps", formatBitsPerSecond(12_500_000L))
        assertEquals("1.50 Gbps", formatBitsPerSecond(1_500_000_000L))

        vmScope.cancel()
    }

    @Test
    fun catalogViewModelSupportsKeepBlobsOfflineSaveAndImagePush() = runTest(testDispatcher) {
        val recordedCommands = mutableListOf<List<String>>()
        val secretStore = InMemorySecretStore()
        val dataStore = InMemoryDataStore()
        val profileRepo = ProfileRepository(dataStore, tempDir, secretStore)
        val assetsRoot = findCoreDataAssetsDir()
        val assetSource = AssetSource { path -> FileInputStream(File(assetsRoot, path)) }
        val catalogRepo = CatalogRepository(
            filesDir = tempDir,
            assetSource = assetSource,
            commandExecutor = RungvCommandExecutor { args, _ ->
                recordedCommands += args
                RungvCommandResult(0, "", "")
            },
            hostAbiProvider = { "arm64-v8a" },
        )
        val gateway = FakeGateway(tempDir)
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
            probeIntervalMs = null,
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.keepBlobs)
        vm.toggleKeepBlobs()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.keepBlobs)

        val inTar = File(tempDir, "in.tar")
        val outTar = File(tempDir, "out.tar")
        var exportedFile: File? = null
        vm.pullImage("alpine:3.21")
        vm.loadImageTar(inTar)
        vm.exportImageTar("alpine:3.21", outTar, isOnline = false) { exportedFile = it }
        vm.pushImage("alpine:3.21", targetRef = "ghcr.io/gfire-sigs/alpine:3.21")
        advanceUntilIdle()

        assertEquals(outTar, exportedFile)
        assertTrue(recordedCommands.contains(listOf("pull", "-keep-blobs", "--host-dns", "127.0.0.1:1053", "alpine:3.21")))
        assertTrue(recordedCommands.contains(listOf("image", "load", "-keep-blobs", "-i", inTar.absolutePath)))
        assertTrue(recordedCommands.contains(listOf("image", "save", "-o", outTar.absolutePath, "alpine:3.21")))
        assertTrue(
            recordedCommands.contains(
                listOf("image", "push", "--host-dns", "127.0.0.1:1053", "alpine:3.21", "ghcr.io/gfire-sigs/alpine:3.21"),
            ),
        )
        assertNull(vm.uiState.value.errorMessage)

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

    data class VolumeClientCall(
        val profile: SandboxProfile,
        val volumeName: String,
        val readOnly: Boolean,
        val isRootfsVolume: Boolean,
    )

    val activeCheckpoints = mutableSetOf<String>()
    val appliedPortCalls = mutableListOf<Triple<String, Int, Boolean>>()
    val liveAddedPorts = mutableListOf<Pair<String, PortForwardRule>>()
    val liveRemovedPorts = mutableListOf<Pair<String, PortForwardRule>>()
    val liveAttachedVolumes = mutableListOf<Pair<String, VolumeMountSpec>>()
    val liveDetachedMounts = mutableListOf<Pair<String, String>>()
    val signaledProcesses = mutableListOf<Triple<String, Int, String>>()
    val sharedFiles = mutableMapOf<String, ByteArray>()
    val namedVolumes = mutableListOf<RungvVolumeLsEntry>()
    var lastLiveProxyUpdate: OutboundProxyConfig? = null
    var lastLiveResourceUpdate: Pair<String, dev.gfire.termvr.core.model.LiveResourceUpdate>? = null
    var lastVolumeClientCall: VolumeClientCall? = null

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

    override suspend fun pauseSandbox(profileId: String, quiesceNet: Boolean): SandboxRunState {
        runStates.value = runStates.value + (profileId to SandboxRunState.PAUSED)
        return SandboxRunState.PAUSED
    }

    override suspend fun unpauseSandbox(profileId: String): SandboxRunState {
        runStates.value = runStates.value + (profileId to SandboxRunState.RUNNING)
        return SandboxRunState.RUNNING
    }

    override suspend fun updateResourcesLive(
        profileId: String,
        update: dev.gfire.termvr.core.model.LiveResourceUpdate,
    ): dev.gfire.termvr.core.model.LiveResourceLimits {
        lastLiveResourceUpdate = profileId to update
        return dev.gfire.termvr.core.model.LiveResourceLimits(
            memoryBytes = (update.memoryMiB ?: 0).toLong() * 1024L * 1024L,
            pidsLimit = (update.pidsLimit ?: 0).toLong(),
            isolateLan = update.isolateLan ?: false,
            netTxBps = update.netTxBps ?: 0L,
            netRxBps = update.netRxBps ?: 0L,
        )
    }

    override suspend fun addPortRuleLive(
        profileId: String,
        rule: PortForwardRule,
        replace: Boolean,
    ): List<PublishedPortMapping> {
        liveAddedPorts.add(profileId to rule)
        return listOf(PublishedPortMapping(rule.listenHost.wireLiteral, rule.hostPortStart, rule.guestPortStart, rule.protocol))
    }

    override suspend fun removePortRuleLive(
        profileId: String,
        rule: PortForwardRule,
    ): List<PublishedPortMapping> {
        liveRemovedPorts.add(profileId to rule)
        return emptyList()
    }

    override suspend fun attachVolumeLive(
        profileId: String,
        mount: VolumeMountSpec,
    ): dev.gfire.termvr.core.model.LiveMountInfo {
        liveAttachedVolumes.add(profileId to mount)
        return dev.gfire.termvr.core.model.LiveMountInfo(
            target = mount.guestMountPath,
            type = "ext4lite",
            source = mount.sourceName,
            readOnly = mount.readOnly,
            mutable = !mount.readOnly,
        )
    }

    override suspend fun detachMountLive(profileId: String, targetPath: String, detach: Boolean) {
        liveDetachedMounts.add(profileId to targetPath)
    }

    override suspend fun signalGuestProcess(profileId: String, pid: Int, signal: String, all: Boolean) {
        signaledProcesses.add(Triple(profileId, pid, signal))
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

    override suspend fun listVolumes(): List<RungvVolumeLsEntry> = namedVolumes.toList()

    override suspend fun createVolume(name: String) {
        if (namedVolumes.none { it.name == name }) {
            namedVolumes.add(RungvVolumeLsEntry(name = name, sizeBytes = 0L))
        }
    }

    override suspend fun removeVolume(name: String, force: Boolean) {
        namedVolumes.removeAll { it.name == name }
    }

    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T {
        lastVolumeClientCall = VolumeClientCall(profile, volumeName, readOnly, isRootfsVolume)
        throw UnsupportedOperationException("No volume mux in unit fake")
    }

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
