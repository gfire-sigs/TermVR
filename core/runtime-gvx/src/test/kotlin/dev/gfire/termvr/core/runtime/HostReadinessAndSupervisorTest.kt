package dev.gfire.termvr.core.runtime

import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.os.Build
import dev.gfire.termvr.core.data.InMemorySecretStore
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.GitWorktreeSpec
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.LiveResourceUpdate
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.NetBackend
import dev.gfire.termvr.core.model.NetworkModeConfig
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.model.VolumeKind
import dev.gfire.termvr.core.model.VolumeMountSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HostReadinessAndSupervisorTest {

    @Test
    fun `HostReadinessInspector detects phantom process kills, proc descendants, and disk usage`(
        @TempDir tempDir: File,
    ) {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        File(filesDir, "gvx-store/gvx/layers/l1.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(100))
        }
        File(filesDir, "gvx-store/gvx/volumes/v1.img").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(250))
        }
        File(filesDir, "workspaces/ws1/main.txt").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(50))
        }
        File(filesDir, "checkpoints/p1/slot-a/img.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(400))
        }

        // Synthetic /proc tree: root=100, children=101,102, grandchild=103 under 101, unrelated=200.
        val procRoot = File(tempDir, "proc").apply { mkdirs() }
        writeProcStat(procRoot, pid = 100, comm = "app_process64", ppid = 1)
        writeProcStat(procRoot, pid = 101, comm = "librungv.so", ppid = 100)
        writeProcStat(procRoot, pid = 102, comm = "systrap (helper)", ppid = 100)
        writeProcStat(procRoot, pid = 103, comm = "sysmsg", ppid = 101)
        writeProcStat(procRoot, pid = 200, comm = "other_app", ppid = 1)

        // SDK 34 + REASON_EXCESSIVE_RESOURCE_USAGE maps to Android 14 developer toggle.
        val sdk34Inspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = procRoot,
            exitReasonProvider = {
                listOf(
                    HistoricalExitRecord(
                        reason = ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                        status = 0,
                        importance = 100,
                        description = "phantom process killer",
                    ),
                )
            },
            sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 16384 },
            totalHostRamMiBProvider = { 8192 },
            freeDiskMiBProvider = { 4096 },
            selfPidProvider = { 100 },
        )
        val report34 = sdk34Inspector.inspect()
        assertTrue(report34.phantomKillerDetected)
        assertEquals(PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE, report34.remediationKind)
        assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, report34.lastKilledExitReasonCode)
        assertEquals(3, report34.liveChildProcessCount)
        assertEquals(16384, report34.kernelPageSizeBytes)
        assertEquals(100L, report34.diskUsage.layersBytes)
        assertEquals(250L, report34.diskUsage.volumesBytes)
        assertEquals(50L, report34.diskUsage.workspacesBytes)
        assertEquals(400L, report34.diskUsage.checkpointsBytes)

        // SDK 32 + SIGKILL (9) maps to Android 12/13 ADB command.
        val sdk32Inspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = procRoot,
            exitReasonProvider = {
                listOf(HistoricalExitRecord(reason = ApplicationExitInfo.REASON_SIGNALED, status = 9, importance = 100))
            },
            sdkIntProvider = { Build.VERSION_CODES.S_V2 },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 4096 },
            totalHostRamMiBProvider = { 4096 },
            freeDiskMiBProvider = { 1024 },
            selfPidProvider = { 100 },
        )
        val report32 = sdk32Inspector.inspect()
        assertTrue(report32.phantomKillerDetected)
        assertEquals(PhantomProcessRemediation.ANDROID_12_13_ADB_COMMAND, report32.remediationKind)

        // Normal clean exit triggers zero remediation.
        val cleanInspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = procRoot,
            exitReasonProvider = {
                listOf(HistoricalExitRecord(reason = ApplicationExitInfo.REASON_EXIT_SELF, status = 0, importance = 100))
            },
            sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 16384 },
            totalHostRamMiBProvider = { 8192 },
            freeDiskMiBProvider = { 2048 },
            selfPidProvider = { 100 },
        )
        val cleanReport = cleanInspector.inspect()
        assertFalse(cleanReport.phantomKillerDetected)
        assertEquals(PhantomProcessRemediation.NONE, cleanReport.remediationKind)
        assertNull(cleanReport.lastKilledExitReasonCode)
    }

    @Test
    fun `SandboxForegroundService formats CPU, PSS, and active policy badges accurately`() {
        val text = SandboxForegroundService.formatStatusNotificationText(
            activeCount = 2,
            snapshots = listOf(
                SandboxTelemetrySnapshot(
                    profileId = "prof-a",
                    cpuPercent = 12.6,
                    pssBytes = 100L * 1024L * 1024L,
                    activeProxyLabel = "SOCKS5 10.0.0.1:1080",
                    exposesLan = true,
                ),
                SandboxTelemetrySnapshot(
                    profileId = "prof-b",
                    cpuPercent = 5.2,
                    pssBytes = 50L * 1024L * 1024L,
                ),
            ),
            wakeLockRemainingSeconds = 125L,
        )
        assertTrue(text.contains("2 active"))
        assertTrue(text.contains("2 active containers"))
        assertTrue(text.contains("CPU 18%"))
        assertTrue(text.contains("150 MiB PSS"))
        assertTrue(text.contains("[Wake Lock 3m]"))
        assertTrue(text.contains("[Proxy: SOCKS5 10.0.0.1:1080]"))
        assertTrue(text.contains("[LAN 0.0.0.0]"))
        val indefiniteText = SandboxForegroundService.formatStatusNotificationText(
            activeCount = 1,
            snapshots = emptyList(),
            wakeLockRemainingSeconds = SandboxSupervisor.INDEFINITE_WAKE_LOCK_SECONDS,
        )
        assertTrue(indefiniteText.contains("[Wake Lock]"))
        assertFalse(indefiniteText.contains("[Wake Lock 1m]"))
        assertEquals(
            "2 containers running",
            SandboxForegroundService.formatActiveNotificationTitle(2, listOf("prof-a", "prof-b")),
        )
        assertEquals(
            "TermVR (prof-a)",
            SandboxForegroundService.formatActiveNotificationTitle(1, listOf("prof-a")),
        )
        assertTrue(SandboxForegroundService.isForegroundActiveState(SandboxRunState.STARTING))
        assertTrue(SandboxForegroundService.isForegroundActiveState(SandboxRunState.RUNNING))
        assertTrue(SandboxForegroundService.isForegroundActiveState(SandboxRunState.PAUSED))
        assertTrue(SandboxForegroundService.isForegroundActiveState(SandboxRunState.RESTORING))
        assertTrue(SandboxForegroundService.isForegroundActiveState(SandboxRunState.FREEZING))
        assertFalse(SandboxForegroundService.isForegroundActiveState(SandboxRunState.STOPPED))
        assertFalse(SandboxForegroundService.isForegroundActiveState(SandboxRunState.FROZEN))
        assertFalse(SandboxForegroundService.isForegroundActiveState(SandboxRunState.FAILED))
    }

    @Test
    fun `SandboxSupervisor enforces screen-off telemetry pause, idle auto-freeze, wake lock cap, LAN gate, and volume helper`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        var nowMs = 10_000_000L
        var nowNs = 1_000_000_000L
        val launchedCommands = CopyOnWriteArrayList<List<String>>()
        val pool = Executors.newCachedThreadPool()
        val activeHandles = CopyOnWriteArrayList<FakeMuxProcessHandle>()

        val commandBuilder = GvxCommandBuilder(
            filesDir = filesDir,
            cacheDir = cacheDir,
            caExporter = { pem, dir ->
                pem.parentFile?.mkdirs()
                pem.writeText("PEM")
                dir.mkdirs()
            },
        )
        val proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore())
        val dnsProxy = LoopbackDnsProxy(
            networkDnsBackend = { _, _ -> ByteArray(0) },
            proxyBridge = proxyBridge,
        )
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "cp.img").writeText("saved")
                activeHandles.lastOrNull()?.markCheckpointExited(0)
                """{"ok":true}"""
            },
            clockMillis = { nowMs },
        )
        val readinessInspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = File(tempDir, "empty-proc"),
            sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 16384 },
            totalHostRamMiBProvider = { 8192 },
            freeDiskMiBProvider = { 4096 },
            selfPidProvider = { 100 },
        )

        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = proxyBridge,
            dnsProxy = dnsProxy,
            checkpointController = checkpointController,
            readinessInspector = readinessInspector,
            processLauncher = { args, _, _ ->
                launchedCommands.add(args)
                val handle = FakeMuxProcessHandle(
                    executor = pool,
                    stderrText = "rungv: publishing 127.0.0.1:41234 -> 10.0.0.2:80/tcp\n",
                )
                activeHandles.add(handle)
                handle
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub-agent")
                }
            },
            guestArch = GuestLinuxArch.ARM64,
            hostPssSampler = { 48L * 1024L * 1024L },
            clockMillis = { nowMs },
            nanoClock = { nowNs },
        )

        try {
            val lanProfile = SandboxProfile(
                id = "devbox",
                displayName = "DevBox",
                imageRef = "debian:bookworm-slim",
                muxMode = MuxMode.STDIO_MUX,
                autoCheckpointOnLowMemory = true,
                portForwards = listOf(
                    PortForwardRule(
                        id = "http",
                        protocol = ForwardProtocol.TCP,
                        listenHost = ListenHost.LAN_ALL_V4,
                        hostPortStart = 0,
                        hostPortEnd = 0,
                        guestPortStart = 80,
                        guestPortEnd = 80,
                    ),
                ),
            )

            // 1. Start sandbox and verify telemetry polls honor screen interactivity strictly.
            val started = supervisor.startOrResumeSandbox(lanProfile)
            assertEquals(SandboxRunState.RUNNING, started)

            // Screen OFF produces zero telemetry polls.
            val offResult = supervisor.pollTelemetryOnce(isInteractive = false)
            assertTrue(offResult.isEmpty())
            assertEquals(0L, supervisor.completedTelemetryPolls)

            // Screen ON polls guest mux + host PSS.
            nowNs += 1_000_000_000L
            val onResult = supervisor.pollTelemetryOnce(isInteractive = true)
            assertEquals(1L, supervisor.completedTelemetryPolls)
            assertEquals(48L * 1024L * 1024L, onResult["devbox"]?.pssBytes)

            // Give stderr reader thread a brief moment to parse ephemeral port publishing.
            Thread.sleep(50L)
            assertEquals(41234, supervisor.publishedPortsByProfile.value["devbox"]?.firstOrNull()?.hostPort)

            // 2. Default wake lock is indefinite when notifications are granted, and clamps to 30 minutes when denied.
            val hookCalls = CopyOnWriteArrayList<Int>()
            supervisor.wakeLockAcquireHook = { hookCalls.add(it) }
            supervisor.setNotificationsPermissionGranted(true)
            val defaultMinutes = supervisor.acquireWakeLock()
            assertEquals(SandboxSupervisor.INDEFINITE_WAKE_LOCK_MINUTES, defaultMinutes)
            assertEquals(SandboxSupervisor.INDEFINITE_WAKE_LOCK_SECONDS, supervisor.wakeLockRemainingSeconds.value)
            assertEquals(listOf(SandboxSupervisor.INDEFINITE_WAKE_LOCK_MINUTES), hookCalls.toList())

            // Revoking POST_NOTIFICATIONS downgrades an active indefinite wake lock to 30 minutes.
            supervisor.setNotificationsPermissionGranted(false)
            assertTrue(supervisor.notificationsDeniedBanner.value)
            assertEquals(
                SandboxSupervisor.MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES * 60L,
                supervisor.wakeLockRemainingSeconds.value,
            )
            assertEquals(SandboxSupervisor.MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES, hookCalls.last())

            // Requesting indefinite or 90 minutes while denied stays capped at 30 minutes.
            assertEquals(
                SandboxSupervisor.MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES,
                supervisor.acquireWakeLock(requestedMinutes = SandboxSupervisor.INDEFINITE_WAKE_LOCK_MINUTES),
            )
            val grantedMinutes = supervisor.acquireWakeLock(requestedMinutes = 90)
            assertEquals(SandboxSupervisor.MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES, grantedMinutes)

            // TRIM_MEMORY_BACKGROUND skips while wake lock is active.
            nowMs += 10 * 60_000L
            assertTrue(supervisor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND).isEmpty())

            // Releasing wake lock enables >= 5 min idle auto-freeze.
            supervisor.releaseWakeLock()
            val frozen = supervisor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
            assertEquals(listOf("devbox"), frozen)
            assertEquals(SandboxRunState.FROZEN, supervisor.runStates.value["devbox"])

            // 3. Wi-Fi handle change requires LAN reconfirmation and downgrades 0.0.0.0 -> 127.0.0.1 on restore.
            supervisor.onNetworkHandleChanged(1001L)
            supervisor.onNetworkHandleChanged(2002L)
            assertTrue("devbox" in supervisor.lanReconfirmationRequired.value)

            launchedCommands.clear()
            supervisor.startOrResumeSandbox(lanProfile, reconfirmLan = false)
            val restoreArgs = launchedCommands.last()
            assertEquals("restore", restoreArgs.first())
            assertTrue(restoreArgs.any { it.startsWith("127.0.0.1:") })
            assertFalse(restoreArgs.any { it.startsWith("0.0.0.0:") })

            // Reconfirming clears the gate.
            supervisor.stopSandbox("devbox")
            launchedCommands.clear()
            supervisor.startOrResumeSandbox(lanProfile, reconfirmLan = true)
            assertFalse("devbox" in supervisor.lanReconfirmationRequired.value)
            assertTrue(launchedCommands.last().any { it.startsWith("0.0.0.0:") })

            // 4. Stopped volume helper launches with named volume mounted at /mnt/volume or -rootfs-volume at /.
            supervisor.stopSandbox("devbox")
            launchedCommands.clear()
            val helperMount = supervisor.withStoppedVolumeHelper(
                volumeName = "proj-vol",
                helperImageRef = "busybox:latest",
                readOnly = true,
            ) { _, mountPath ->
                mountPath
            }
            assertEquals("/mnt/volume", helperMount)
            assertTrue(launchedCommands.last().contains("proj-vol:/mnt/volume:ro"))

            launchedCommands.clear()
            val rootfsMount = supervisor.withStoppedVolumeHelper(
                volumeName = "rootfs-devbox",
                helperImageRef = "debian:trixie-slim",
                readOnly = true,
                isRootfsVolume = true,
            ) { _, mountPath ->
                mountPath
            }
            assertEquals("/", rootfsMount)
            val rootHelperCmd = launchedCommands.last()
            val rootVolIdx = rootHelperCmd.indexOf("-rootfs-volume")
            assertTrue(rootVolIdx >= 0)
            assertEquals("rootfs-devbox", rootHelperCmd[rootVolIdx + 1])
            assertFalse(rootHelperCmd.any { it.contains(":/mnt/volume") })

            // 5. Superseded checkpoint notice + cold boot fallback.
            checkpointController.markSuperseded("devbox", "Upgraded gvx")
            launchedCommands.clear()
            supervisor.startOrResumeSandbox(lanProfile)
            assertEquals("run", launchedCommands.last().first())
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SandboxSupervisor surfaces cold-boot notice when checkpoint metadata is from older gvxVersion`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val launchedCommands = CopyOnWriteArrayList<List<String>>()

        // Write a checkpoint using an older gvxVersion.
        val oldCp = CheckpointController(
            filesDir = filesDir,
            gvxVersion = "v0.14.0",
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "cp.img").writeText("old")
                """{"ok":true}"""
            },
        )
        val ctlSock = File(filesDir, "ctl/oldprof.sock").apply { parentFile?.mkdirs() }
        oldCp.checkpoint("oldprof", ctlSock, currentPssBytes = 8L * 1024L * 1024L, awaitProcessExit = { 0 })

        val newCp = CheckpointController(
            filesDir = filesDir,
            gvxVersion = "v0.15.10002026110900",
            freeSpaceProvider = { 512L * 1024L * 1024L },
        )
        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir, caExporter = { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        })
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(networkDnsBackend = { _, _ -> ByteArray(0) }),
            checkpointController = newCp,
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = { args, _, _ ->
                launchedCommands.add(args)
                FakeMuxProcessHandle(pool)
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
        )
        try {
            val profile = SandboxProfile(id = "oldprof", displayName = "OldProf", imageRef = "alpine:3.21")
            val state = supervisor.startOrResumeSandbox(profile)
            assertEquals(SandboxRunState.RUNNING, state)
            assertEquals("run", launchedCommands.last().first())
            assertTrue(supervisor.activeServiceNotice.value.orEmpty().contains("superseded"))
            assertTrue(newCp.readMetadata("oldprof")?.superseded == true)
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `RAW_PTY mode attaches slave PTY file to process launcher and ProcessBuilder stdin and stdout`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val slaveFile = File(tempDir, "pts-9").apply { writeText("") }
        val fakeExec = File(tempDir, "librungv.so").apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

        // Verify AndroidRungvProcessLauncher redirects stdin/stdout to slave PTY device.
        val launcher = AndroidRungvProcessLauncher(fakeExec)
        val pb = launcher.buildProcessBuilder(listOf("run"), emptyMap(), ptySlaveDevice = slaveFile)
        assertEquals(slaveFile, pb.redirectInput().file())
        assertEquals(slaveFile, pb.redirectOutput().file())

        // Verify SandboxSupervisor passes rawPtyHandle.slavePath to processLauncher and forwards resize childPid.
        val capturedSlaves = CopyOnWriteArrayList<File?>()
        val resizedCalls = CopyOnWriteArrayList<Triple<Int, Int, Long>>()
        val fakePtyBackend = object : PtyBackend {
            override fun openPty(rows: Int, cols: Int, xpixel: Int, ypixel: Int): HostPtyHandle {
                return object : HostPtyHandle {
                    override val slavePath: String = slaveFile.absolutePath
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun write(data: ByteArray, offset: Int, length: Int) {}
                    override fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int, childPid: Long) {
                        resizedCalls.add(Triple(rows, cols, childPid))
                    }
                    override fun close() {}
                }
            }
        }
        val pool = Executors.newCachedThreadPool()
        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir, caExporter = { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        })
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(networkDnsBackend = { _, _ -> ByteArray(0) }),
            checkpointController = CheckpointController(filesDir = filesDir),
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = { _, _, ptySlave ->
                capturedSlaves.add(ptySlave)
                FakeMuxProcessHandle(pool)
            },
            agentExtractor = { arch -> File(filesDir, "agent/${arch.ociArch}/termvr-agent") },
            ptyBackend = fakePtyBackend,
        )
        try {
            val rawProfile = SandboxProfile(
                id = "rawbox",
                displayName = "RawBox",
                imageRef = "busybox:latest",
                muxMode = MuxMode.RAW_PTY,
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(rawProfile))
            assertEquals(listOf(slaveFile), capturedSlaves)
            supervisor.resizeRawPty("rawbox", rows = 45, cols = 132)
            assertEquals(listOf(Triple(45, 132, 4242L)), resizedCalls)
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SandboxSupervisor supports concurrent multi-container start, freeze, telemetry, and stop without clobbering`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val handlesById = java.util.concurrent.ConcurrentHashMap<String, FakeMuxProcessHandle>()
        var nowNs = 1_000_000_000L

        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir, caExporter = { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        })
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { controlSockPath, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                File(payloadLine.substring(start, end), "cp.img").writeText("saved")
                val id = controlSockPath.nameWithoutExtension
                handlesById[id]?.markCheckpointExited(0)
                """{"ok":true}"""
            },
        )
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(networkDnsBackend = { _, _ -> ByteArray(0) }),
            checkpointController = checkpointController,
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = { args, _, _ ->
                val ctlIdx = args.indexOf("-control-socket")
                val profileId = File(args[ctlIdx + 1]).nameWithoutExtension
                val handle = FakeMuxProcessHandle(pool)
                handlesById[profileId] = handle
                handle
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
            nanoClock = { nowNs },
        )

        try {
            val profileA = SandboxProfile(id = "debian-a", displayName = "Debian A", imageRef = "debian:trixie-slim")
            val profileB = SandboxProfile(id = "ubuntu-b", displayName = "Ubuntu B", imageRef = "ubuntu:24.04")

            val results = awaitAll(
                async { supervisor.startOrResumeSandbox(profileA) },
                async { supervisor.startOrResumeSandbox(profileB) },
            )
            assertEquals(listOf(SandboxRunState.RUNNING, SandboxRunState.RUNNING), results)
            assertEquals(SandboxRunState.RUNNING, supervisor.runStates.value["debian-a"])
            assertEquals(SandboxRunState.RUNNING, supervisor.runStates.value["ubuntu-b"])

            supervisor.acquireWakeLock(15)
            assertNotNull(supervisor.wakeLockRemainingSeconds.value)

            nowNs += 1_000_000_000L
            val telemetry = supervisor.pollTelemetryOnce(isInteractive = true)
            assertEquals(setOf("debian-a", "ubuntu-b"), telemetry.keys)

            // Freeze profileA; profileB and wake lock must stay active.
            assertEquals(SandboxRunState.FROZEN, supervisor.freezeSandbox("debian-a"))
            assertEquals(SandboxRunState.FROZEN, supervisor.runStates.value["debian-a"])
            assertEquals(SandboxRunState.RUNNING, supervisor.runStates.value["ubuntu-b"])
            assertNotNull(supervisor.wakeLockRemainingSeconds.value)

            // Stop profileB; wake lock releases once no containers are running.
            supervisor.stopSandbox("ubuntu-b")
            assertEquals(SandboxRunState.STOPPED, supervisor.runStates.value["ubuntu-b"])
            assertEquals(SandboxRunState.FROZEN, supervisor.runStates.value["debian-a"])
            assertNull(supervisor.wakeLockRemainingSeconds.value)
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SandboxSupervisor passes -checkpoint-dir on run and completes multi-cycle Start-Freeze-Resume-Freeze-Resume`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val launchedCommands = CopyOnWriteArrayList<List<String>>()
        val activeHandles = CopyOnWriteArrayList<FakeMuxProcessHandle>()
        val preopenedDirs = CopyOnWriteArrayList<File>()
        val resyncStreamLists = CopyOnWriteArrayList<String>()

        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir, caExporter = { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        })
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val reqDir = File(payloadLine.substring(start, end))
                val launchCkptDir = preopenedDirs.last()
                if (reqDir.canonicalFile != launchCkptDir.canonicalFile) {
                    """{"ok":false,"error":"checkpoint directory ${reqDir.absolutePath} does not match pre-opened -checkpoint-dir ${launchCkptDir.absolutePath}"}"""
                } else {
                    File(reqDir, "manifest.json").writeText("{}")
                    File(reqDir, "state.img").writeText("saved")
                    activeHandles.lastOrNull()?.markCheckpointExited(0)
                    """{"ok":true}"""
                }
            },
        )
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(networkDnsBackend = { _, _ -> ByteArray(0) }),
            checkpointController = checkpointController,
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = { args, _, _ ->
                launchedCommands.add(args)
                val ckptIdx = args.indexOf("-checkpoint-dir")
                assertTrue(ckptIdx >= 0, "Expected -checkpoint-dir in args: $args")
                val ckptDir = File(args[ckptIdx + 1])
                assertTrue(ckptDir.isDirectory)
                // Simulate gvx pre-opening bundle files prior to hostlockdown.
                File(ckptDir, "pages.img").apply { if (!exists()) writeText("preopened-inode") }
                preopenedDirs.add(ckptDir)
                val openStreamsJson = if (args.first() == "restore") """[{"id":1,"rows":24,"cols":80}]""" else "[]"
                resyncStreamLists.add(openStreamsJson)
                val handle = FakeMuxProcessHandle(pool, resyncOpenStreamsJson = openStreamsJson)
                activeHandles.add(handle)
                handle
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
        )

        try {
            val profile = SandboxProfile(
                id = "snap-loop",
                displayName = "Snap Loop",
                imageRef = "docker.io/library/debian:trixie-slim",
            )

            // Cycle 1: Start -> Freeze
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals("run", launchedCommands.last().first())
            val slotA = File(filesDir, "checkpoints/snap-loop/slot-a")
            assertEquals(slotA, preopenedDirs.last())

            assertEquals(SandboxRunState.FROZEN, supervisor.freezeSandbox(profile.id))
            assertEquals("preopened-inode", File(slotA, "pages.img").readText())

            // Cycle 2: Resume marks the consumed slot superseded, and Freeze writes a fresh active snapshot.
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals("restore", launchedCommands.last().first())
            assertEquals(slotA, preopenedDirs.last())
            assertTrue(checkpointController.readMetadata(profile.id)!!.superseded)
            val restoredMux = supervisor.getMuxClient(profile.id)!!
            assertTrue(restoredMux.isStreamOpen(1L))

            assertEquals(SandboxRunState.FROZEN, supervisor.freezeSandbox(profile.id))
            assertFalse(checkpointController.readMetadata(profile.id)!!.superseded)
            assertTrue(File(slotA, "state.img").isFile)

            // Cycle 3: Resume a second time and applyPortForwards via checkpoint & restore
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            val updatedRules = listOf(
                PortForwardRule("http", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 18080, 18080, 80, 80),
            )
            assertEquals(
                SandboxRunState.RUNNING,
                supervisor.applyPortForwards(profile.id, updatedRules, checkpointAndRestore = true),
            )
            assertEquals("restore", launchedCommands.last().first())
            assertTrue(launchedCommands.last().contains("127.0.0.1:18080:80/tcp"))
            assertTrue(checkpointController.readMetadata(profile.id)!!.superseded)

            // Stop the restored session and verify the next Start runs a clean cold boot without a notice.
            supervisor.stopSandbox(profile.id)
            assertTrue(checkpointController.readMetadata(profile.id)!!.superseded)
            launchedCommands.clear()
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals("run", launchedCommands.last().first())
            assertEquals(null, supervisor.activeServiceNotice.value)
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `pollTelemetryOnce cleans up unexpected process exit and updates runState`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val handles = CopyOnWriteArrayList<FakeMuxProcessHandle>()
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = GvxCommandBuilder(filesDir, cacheDir, CaCertificateExporter { pem, dir ->
                pem.parentFile?.mkdirs()
                pem.writeText("pem")
                dir.mkdirs()
            }),
            proxyBridge = LoopbackProxyBridge(InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(NetworkDnsBackend { _, _ -> ByteArray(0) }),
            checkpointController = CheckpointController(filesDir),
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = GvxProcessLauncher { _, _, _ ->
                FakeMuxProcessHandle(pool).also { handles += it }
            },
            agentExtractor = AgentAssetExtractor { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
        )

        try {
            val profile = SandboxProfile(
                id = "exit-watch",
                displayName = "Exit Watch",
                imageRef = "docker.io/library/debian:trixie-slim",
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals(1, handles.size)

            handles.last().markCheckpointExited(1)
            supervisor.pollTelemetryOnce(isInteractive = true)
            assertEquals(SandboxRunState.FAILED, supervisor.runStates.value[profile.id])
            assertNull(supervisor.getMuxClient(profile.id))

            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals(2, handles.size)
            handles.last().markCheckpointExited(0)
            supervisor.pollTelemetryOnce(isInteractive = true)
            assertEquals(SandboxRunState.STOPPED, supervisor.runStates.value[profile.id])
            assertNull(supervisor.getMuxClient(profile.id))
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed restore marks checkpoint superseded so retry cold boots cleanly`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val launchedCommands = CopyOnWriteArrayList<List<String>>()
        lateinit var currentHandle: FakeMuxProcessHandle
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { _, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val slotDir = File(payloadLine.substring(start, end))
                slotDir.mkdirs()
                File(slotDir, "state.img").writeText("state")
                currentHandle.markCheckpointExited(0)
                """{"ok":true}"""
            },
        )
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = GvxCommandBuilder(filesDir, cacheDir, CaCertificateExporter { pem, dir ->
                pem.parentFile?.mkdirs()
                pem.writeText("pem")
                dir.mkdirs()
            }),
            proxyBridge = LoopbackProxyBridge(InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(NetworkDnsBackend { _, _ -> ByteArray(0) }),
            checkpointController = checkpointController,
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                totalHostRamMiBProvider = { 8192 },
                freeDiskMiBProvider = { 4096 },
                selfPidProvider = { 100 },
            ),
            processLauncher = GvxProcessLauncher { args, _, _ ->
                launchedCommands += args
                FakeMuxProcessHandle(pool).also { handle ->
                    currentHandle = handle
                    if (args.firstOrNull() == "restore") {
                        handle.destroyGracefully()
                    }
                }
            },
            agentExtractor = AgentAssetExtractor { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
        )

        try {
            val profile = SandboxProfile(
                id = "restore-fallback",
                displayName = "Restore Fallback",
                imageRef = "docker.io/library/debian:trixie-slim",
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals(SandboxRunState.FROZEN, supervisor.freezeSandbox(profile.id))

            val err = try {
                supervisor.startOrResumeSandbox(profile)
                null
            } catch (e: java.io.IOException) {
                e
            }
            assertNotNull(err)
            assertEquals(SandboxRunState.FAILED, supervisor.runStates.value[profile.id])
            assertTrue(checkpointController.readMetadata(profile.id)?.superseded == true)

            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals("run", launchedCommands.last().first())
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    private fun writeProcStat(procRoot: File, pid: Int, comm: String, ppid: Int) {
        val pidDir = File(procRoot, pid.toString()).apply { mkdirs() }
        File(pidDir, "stat").writeText("$pid ($comm) S $ppid 1 1 0 -1 4194304 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0")
    }

    @Test
    fun `SandboxSupervisor provisions Git worktrees before launch and rejects colliding branch checkouts`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val launchedArgs = CopyOnWriteArrayList<List<String>>()
        val commandBuilder = GvxCommandBuilder(
            filesDir = filesDir,
            cacheDir = cacheDir,
            caExporter = { pem, dir ->
                pem.parentFile?.mkdirs()
                pem.writeText("PEM")
                dir.mkdirs()
            },
        )
        val proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore())
        val dnsProxy = LoopbackDnsProxy(
            networkDnsBackend = { _, _ -> ByteArray(0) },
            proxyBridge = proxyBridge,
        )
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = proxyBridge,
            dnsProxy = dnsProxy,
            checkpointController = CheckpointController(filesDir),
            readinessInspector = HostReadinessInspector(
                filesDir = filesDir,
                procRoot = File(tempDir, "proc"),
                sdkIntProvider = { 35 },
                hostAbiProvider = { "arm64-v8a" },
                pageSizeProvider = { 16384 },
                freeDiskMiBProvider = { 2048 },
                selfPidProvider = { 100 },
            ),
            processLauncher = { args, _, _ ->
                // Verify git worktree metadata ALREADY exists on disk at the moment librungv.so is spawned!
                assertTrue(File(filesDir, "git-projects/mono/repo.git/HEAD").isFile)
                launchedArgs.add(args)
                FakeMuxProcessHandle(pool)
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("agent")
                }
            },
        )
        try {
            val workerA = SandboxProfile(
                id = "worker-a",
                displayName = "Worker A",
                imageRef = "debian:trixie-slim",
                gitWorktree = GitWorktreeSpec(projectId = "mono", branchName = "wt/shared-branch"),
            )
            val stateA = supervisor.startOrResumeSandbox(workerA)
            assertEquals(SandboxRunState.RUNNING, stateA)
            assertEquals(1, launchedArgs.size)
            assertEquals(
                "gitdir: /git/mono.git/worktrees/worker-a\n",
                File(filesDir, "git-projects/mono/worktrees/worker-a/.git").readText(),
            )
            assertTrue(launchedArgs.single().contains("${File(filesDir, "git-projects/mono/repo.git").absolutePath}:/git/mono.git:rw"))

            // Worker B attempting to launch on the SAME branch wt/shared-branch must fail BEFORE spawning librungv.so!
            val workerB = SandboxProfile(
                id = "worker-b",
                displayName = "Worker B",
                imageRef = "debian:trixie-slim",
                gitWorktree = GitWorktreeSpec(projectId = "mono", branchName = "wt/shared-branch"),
            )
            var collisionCaught = false
            try {
                supervisor.startOrResumeSandbox(workerB)
            } catch (e: IllegalStateException) {
                collisionCaught = e.message?.contains("already checked out") == true
            }
            assertTrue(collisionCaught)
            assertEquals(SandboxRunState.FAILED, supervisor.runStates.value["worker-b"])
            assertEquals(1, launchedArgs.size)
        } finally {
            supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SandboxSupervisor drives gvx-ctrl v2 pause, live resources, live loopback ports, volume attach, inspector, and RAW_PTY stats`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val pool = Executors.newCachedThreadPool()
        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir) { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        }
        val proxyBridge = LoopbackProxyBridge(secretStore = InMemorySecretStore())
        val dnsProxy = LoopbackDnsProxy(networkDnsBackend = { _, _ -> ByteArray(0) }, proxyBridge = proxyBridge)
        val readinessInspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = File(tempDir, "empty-proc"),
            sdkIntProvider = { Build.VERSION_CODES.VANILLA_ICE_CREAM },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 16384 },
            totalHostRamMiBProvider = { 8192 },
            freeDiskMiBProvider = { 4096 },
            selfPidProvider = { 100 },
        )

        var pausedOnServer = false
        val dynamicPorts = CopyOnWriteArrayList<String>()
        val controlServer = FakeGvxControlServer { call ->
            when (call.method) {
                GvxControlClient.METHOD_PAUSE -> {
                    if (pausedOnServer) {
                        """{"ok":false,"error":{"code":"ALREADY_PAUSED","message":"already paused"}}"""
                    } else {
                        pausedOnServer = true
                        """{"ok":true,"result":{"state":"paused"}}"""
                    }
                }
                GvxControlClient.METHOD_UNPAUSE -> {
                    if (!pausedOnServer) {
                        """{"ok":false,"error":{"code":"NOT_PAUSED","message":"not paused"}}"""
                    } else {
                        pausedOnServer = false
                        """{"ok":true,"result":{"state":"running"}}"""
                    }
                }
                GvxControlClient.METHOD_STOP -> """{"ok":true,"result":{"signaled":"SIGTERM","killed":false}}"""
                GvxControlClient.METHOD_RESOURCE_UPDATE ->
                    """{"ok":true,"result":{"memory_bytes":536870912,"cpu_millis":2000,"guest_cpus":2,"pids_limit":128,"isolate_lan":true}}"""
                GvxControlClient.METHOD_PORT_ADD -> {
                    dynamicPorts.add("""{"proto":"tcp","host_ip":"127.0.0.1","host_port":8088,"guest_port":80,"source":"dynamic"}""")
                    """{"ok":true,"result":{"ports":[${dynamicPorts.joinToString(",")}]}}"""
                }
                GvxControlClient.METHOD_PORT_REMOVE -> {
                    dynamicPorts.clear()
                    """{"ok":true,"result":{"removed":[{"proto":"tcp","host_ip":"127.0.0.1","host_port":8088,"guest_port":80,"source":"dynamic"}]}}"""
                }
                GvxControlClient.METHOD_PORT_LIST ->
                    """{"ok":true,"result":{"ports":[${dynamicPorts.joinToString(",")}]}}"""
                GvxControlClient.METHOD_MOUNT_ADD -> {
                    assertTrue(call.hasFdsFlag)
                    assertEquals(1, call.fdCount)
                    """{"ok":true,"result":{"target":"/data","type":"ext4lite","source":"hotvol","read_only":false,"mutable":true}}"""
                }
                GvxControlClient.METHOD_MOUNT_REMOUNT -> """{"ok":true,"result":{"target":"/data","read_only":true}}"""
                GvxControlClient.METHOD_MOUNT_REMOVE -> """{"ok":true,"result":{"removed":"/data"}}"""
                GvxControlClient.METHOD_INSPECT ->
                    """{"ok":true,"result":{"id":"ctrl-box","state":"${if (pausedOnServer) "paused" else "running"}","platform":"systrap","architecture":"arm64","lockdown_applied":true,"network_mode":"nat","eth0_ip":"10.88.0.2","pid_count":3,"resources":{"memory_bytes":536870912,"cpu_millis":2000,"pids_limit":128,"isolate_lan":true},"mounts":[{"target":"/data","type":"ext4lite","source":"hotvol","read_only":true,"mutable":true}]}}"""
                GvxControlClient.METHOD_STATS ->
                    """{"ok":true,"result":{"cpu":{"user_ns":300000000,"sys_ns":100000000},"memory":{"total_bytes":50331648,"anon_bytes":33554432},"pids":3,"pids_limit":128}}"""
                GvxControlClient.METHOD_PROC_LIST ->
                    """{"ok":true,"result":{"processes":[{"pid":1,"state":"S","comm":"init"},{"pid":42,"state":"R","comm":"worker","cmdline":["worker","--serve"]}]}}"""
                GvxControlClient.METHOD_PROC_SIGNAL -> """{"ok":true,"result":{"signal":"SIGTERM","pid":42}}"""
                GvxControlClient.METHOD_KERNEL_LOG ->
                    """{"ok":true,"result":{"records":[{"seq":1,"level":6,"usec":500000,"text":"ready"}]}}"""
                else -> """{"ok":true,"result":{}}"""
            }
        }
        val controlClient = GvxControlClient(transport = controlServer.transport)
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlClient = controlClient,
        )
        var nowNs = 1_000_000_000L
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = proxyBridge,
            dnsProxy = dnsProxy,
            checkpointController = checkpointController,
            readinessInspector = readinessInspector,
            processLauncher = { _, _, _ ->
                commandBuilder.controlSocketFile("ctrl-box").apply {
                    parentFile?.mkdirs()
                    writeText("sock")
                }
                commandBuilder.controlSocketFile("raw-box").apply {
                    parentFile?.mkdirs()
                    writeText("sock")
                }
                FakeMuxProcessHandle(pool)
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("agent")
                }
            },
            ptyBackend = object : PtyBackend {
                override fun openPty(rows: Int, cols: Int, xpixel: Int, ypixel: Int): HostPtyHandle = object : HostPtyHandle {
                    override val slavePath: String = "/dev/pts/0"
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                    override fun write(data: ByteArray, offset: Int, length: Int) = Unit
                    override fun resize(rows: Int, cols: Int, xpixel: Int, ypixel: Int, childPid: Long) = Unit
                    override fun close() = Unit
                }
            },
            controlClient = controlClient,
            nanoClock = { nowNs },
        )
        try {
            val profile = SandboxProfile(
                id = "ctrl-box",
                displayName = "Ctrl Box",
                imageRef = "docker.io/library/debian:trixie-slim",
                network = NetworkModeConfig(backend = NetBackend.NAT),
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))

            // Pause -> PAUSED, telemetry emits PAUSED with 0% CPU, startOrResumeSandbox unpauses -> RUNNING.
            assertEquals(SandboxRunState.PAUSED, supervisor.pauseSandbox(profile.id))
            assertTrue(PortConflictDetector.isActivePortHoldingState(SandboxRunState.PAUSED))
            val pausedSnap = supervisor.pollTelemetryOnce(isInteractive = true)[profile.id]
            assertNotNull(pausedSnap)
            assertEquals(SandboxRunState.PAUSED, pausedSnap!!.state)
            assertEquals(0.0, pausedSnap.cpuPercent)

            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(profile))
            assertEquals(SandboxRunState.RUNNING, supervisor.runStates.value[profile.id])

            // Live resource update syncs RAM/CPU/PIDs/isolateLan and proxyBridge isolation policy.
            val limits = supervisor.updateResourcesLive(
                profile.id,
                LiveResourceUpdate(memoryMiB = 512, cpuCores = 2.0, pidsLimit = 128, isolateLan = true),
            )
            assertEquals(128L, limits.pidsLimit)
            assertTrue(limits.isolateLan)
            assertTrue(proxyBridge.currentPolicy.isolateLanEgress)

            // Live loopback port add & remove work; LAN add is rejected at runtime boundary.
            val lanRule = PortForwardRule("lan", ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 8089, 8089, 80, 80)
            var lanRejected = false
            try {
                supervisor.addPortRuleLive(profile.id, lanRule)
            } catch (_: IllegalArgumentException) {
                lanRejected = true
            }
            assertTrue(lanRejected)

            val loopRule = PortForwardRule("web", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 8088, 8088, 80, 80)
            val published = supervisor.addPortRuleLive(profile.id, loopRule)
            assertEquals(1, published.size)
            assertEquals(8088, published.single().hostPort)

            val afterRemove = supervisor.removePortRuleLive(profile.id, loopRule)
            assertTrue(afterRemove.isEmpty())

            // Live named ext4lite volume attach (SCM_RIGHTS), remount RO, and detach.
            commandBuilder.volumeImageFile("hotvol").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4096))
            }
            val attached = supervisor.attachVolumeLive(
                profile.id,
                VolumeMountSpec("hotvol", "/data", VolumeKind.NAMED_EXT4LITE, readOnly = false),
            )
            assertEquals("/data", attached.target)
            assertTrue(supervisor.remountLive(profile.id, "/data", readOnly = true))
            supervisor.detachMountLive(profile.id, "/data")

            // Protected system/restore mounts cannot be detached or remounted live.
            assertTrue(SandboxSupervisor.isProtectedRestoreMount("/tmp"))
            assertTrue(SandboxSupervisor.isProtectedRestoreMount("/run"))
            assertTrue(SandboxSupervisor.isProtectedRestoreMount("/git/demo.git"))
            var protectedBlocked = false
            try {
                supervisor.detachMountLive(profile.id, "/tmp")
            } catch (_: IllegalArgumentException) {
                protectedBlocked = true
            }
            assertTrue(protectedBlocked)

            // CoW overlay mounts reject remountLive before mutating gvx (preserving restore parity).
            val cowProfile = SandboxProfile(
                id = "cow-box",
                displayName = "CoW Box",
                imageRef = "docker.io/library/alpine:3.21",
                muxMode = MuxMode.RAW_PTY,
                volumes = listOf(VolumeMountSpec("base", "/cow", VolumeKind.OVERLAY_EXT4LITE, readOnly = false)),
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(cowProfile))
            val remountCallsBefore = controlServer.calls.count { it.method == GvxControlClient.METHOD_MOUNT_REMOUNT }
            var cowRemountBlocked = false
            try {
                supervisor.remountLive(cowProfile.id, "/cow", readOnly = true)
            } catch (_: IllegalArgumentException) {
                cowRemountBlocked = true
            }
            assertTrue(cowRemountBlocked)
            assertEquals(remountCallsBefore, controlServer.calls.count { it.method == GvxControlClient.METHOD_MOUNT_REMOUNT })

            // Inspector RPCs
            assertEquals("10.88.0.2", supervisor.inspectSandbox(profile.id).eth0Ip)
            assertEquals(3, supervisor.statsSandbox(profile.id).pids)
            assertEquals(2, supervisor.listGuestProcesses(profile.id).size)
            supervisor.signalGuestProcess(profile.id, pid = 42, signal = "SIGTERM")
            assertEquals("ready", supervisor.readKernelLog(profile.id).single().text)

            // RAW_PTY telemetry falls back to gvx-ctrl/2 sandbox.stats when muxClient is null.
            val rawProfile = SandboxProfile(
                id = "raw-box",
                displayName = "Raw Box",
                imageRef = "docker.io/library/alpine:3.21",
                muxMode = MuxMode.RAW_PTY,
            )
            assertEquals(SandboxRunState.RUNNING, supervisor.startOrResumeSandbox(rawProfile))
            nowNs += 1_000_000_000L
            val rawSnap = supervisor.pollTelemetryOnce(isInteractive = true)["raw-box"]
            assertNotNull(rawSnap)
            assertEquals(50_331_648L, rawSnap!!.guestRssBytes)
            assertEquals(3, rawSnap.guestProcessCount)

            // Graceful stop invokes lifecycle.stop before process destroy.
            supervisor.stopSandbox(profile.id)
            assertTrue(controlServer.calls.any { it.method == GvxControlClient.METHOD_STOP })
        } finally {
            supervisor.close()
            controlServer.shutdown()
            pool.shutdownNow()
        }
    }

    private class FakeMuxProcessHandle(
        executor: java.util.concurrent.ExecutorService,
        stderrText: String = "",
        private val resyncOpenStreamsJson: String = "[]",
    ) : GvxProcessHandle {
        private val agentToHostOut = PipedOutputStream()
        override val stdoutStream: InputStream = PipedInputStream(agentToHostOut, 65536)

        private val hostToAgentIn = PipedInputStream(65536)
        override val stdinStream: OutputStream = PipedOutputStream(hostToAgentIn)

        override val stderrStream: InputStream = ByteArrayInputStream(stderrText.toByteArray(StandardCharsets.UTF_8))
        override val pid: Long = 4242L

        private val alive = AtomicBoolean(true)
        private val exitCode = java.util.concurrent.atomic.AtomicInteger(0)
        private val exited = java.util.concurrent.CountDownLatch(1)

        override val isAlive: Boolean get() = alive.get()

        init {
            executor.execute {
                try {
                    while (true) {
                        val frame = MuxCodec.readFrame(hostToAgentIn) ?: break
                        when (frame.type) {
                            MuxFrameType.MUX_HELLO -> {
                                val ack = MuxCodec.json.encodeToString(
                                    MuxHelloPayload.serializer(),
                                    MuxHelloPayload(protocol = 1, agentVersion = "test", initDaemon = false),
                                )
                                synchronized(agentToHostOut) {
                                    MuxCodec.writeFrame(
                                        agentToHostOut,
                                        MuxFrame(MuxFrameType.MUX_HELLO, 0L, ack.toByteArray(StandardCharsets.UTF_8)),
                                    )
                                }
                            }

                            MuxFrameType.MUX_RESYNC -> {
                                synchronized(agentToHostOut) {
                                    MuxCodec.writeFrame(
                                        agentToHostOut,
                                        MuxFrame(
                                            MuxFrameType.MUX_RESYNC_ACK,
                                            0L,
                                            """{"openStreams":$resyncOpenStreamsJson}""".toByteArray(StandardCharsets.UTF_8),
                                        ),
                                    )
                                }
                            }

                            MuxFrameType.TELEMETRY_REQ -> {
                                val json = """{"cpuNanos":150000000,"rssBytes":33554432,"procCount":2,"listenPorts":[8080]}"""
                                synchronized(agentToHostOut) {
                                    MuxCodec.writeFrame(
                                        agentToHostOut,
                                        MuxFrame(MuxFrameType.TELEMETRY_RESP, 0L, json.toByteArray(StandardCharsets.UTF_8)),
                                    )
                                }
                            }

                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun markCheckpointExited(code: Int) {
            exitCode.set(code)
            alive.set(false)
            exited.countDown()
        }

        override fun waitForExit(): Int {
            exited.await()
            return exitCode.get()
        }

        override fun destroyGracefully() {
            alive.set(false)
            exited.countDown()
            runCatching { agentToHostOut.close() }
            runCatching { hostToAgentIn.close() }
        }
    }
}
