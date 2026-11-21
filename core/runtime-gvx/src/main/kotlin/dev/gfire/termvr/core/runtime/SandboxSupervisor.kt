package dev.gfire.termvr.core.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import dev.gfire.termvr.core.data.GitWorktreeManager
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.MuxMode
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wraps a spawned `librungv.so` child process for lifecycle management and unit-test injection.
 */
interface GvxProcessHandle : Closeable {
    val stdoutStream: InputStream
    val stdinStream: OutputStream
    val stderrStream: InputStream
    val pid: Long
    val isAlive: Boolean
    fun waitForExit(): Int
    fun destroyGracefully()
    override fun close() {
        destroyGracefully()
    }
}

/**
 * Spawns `librungv.so` processes with the specified CLI arguments, host environment, and optional PTY slave device.
 */
fun interface GvxProcessLauncher {
    fun launch(args: List<String>, env: Map<String, String>, ptySlaveDevice: File?): GvxProcessHandle
}

/**
 * Default production `ProcessBuilder` launcher targeting `applicationInfo.nativeLibraryDir/librungv.so`.
 */
class AndroidRungvProcessLauncher(
    private val executableFile: File,
) : GvxProcessLauncher {
    internal fun buildProcessBuilder(
        args: List<String>,
        env: Map<String, String>,
        ptySlaveDevice: File? = null,
    ): ProcessBuilder {
        require(executableFile.exists() && executableFile.canExecute()) {
            "librungv.so is missing or not executable at ${executableFile.absolutePath}"
        }
        val cmd = ArrayList<String>(1 + args.size).apply {
            add(executableFile.absolutePath)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(env)
        if (ptySlaveDevice != null) {
            pb.redirectInput(ptySlaveDevice)
            pb.redirectOutput(ptySlaveDevice)
        }
        return pb
    }

    override fun launch(
        args: List<String>,
        env: Map<String, String>,
        ptySlaveDevice: File?,
    ): GvxProcessHandle {
        val process = buildProcessBuilder(args, env, ptySlaveDevice).start()
        return object : GvxProcessHandle {
            override val stdoutStream: InputStream get() = process.inputStream
            override val stdinStream: OutputStream get() = process.outputStream
            override val stderrStream: InputStream get() = process.errorStream
            override val pid: Long get() = extractProcessPid(process)
            override val isAlive: Boolean get() = process.isAlive
            override fun waitForExit(): Int = process.waitFor()
            override fun destroyGracefully() {
                process.destroy()
            }
        }
    }

    private fun extractProcessPid(process: Process): Long {
        val reflected = runCatching {
            Process::class.java.getMethod("pid").invoke(process) as? Long
        }.getOrNull()
        if (reflected != null && reflected > 0L) {
            return reflected
        }
        val match = Regex("""pid=(\d+)""").find(process.toString())
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }
}

/**
 * Extracts the architecture-matched static `termvr-agent` binary from APK assets to executable-free app storage.
 */
fun interface AgentAssetExtractor {
    fun ensureExtracted(guestArch: GuestLinuxArch): File
}

class AndroidAgentAssetExtractor(
    private val context: Context,
    private val filesDir: File,
) : AgentAssetExtractor {
    override fun ensureExtracted(guestArch: GuestLinuxArch): File {
        val targetDir = File(filesDir, "agent/${guestArch.ociArch}").apply { mkdirs() }
        val targetFile = File(targetDir, "termvr-agent")
        val assetPath = "agent/${guestArch.ociArch}/termvr-agent"
        val tmpFile = File(targetDir, "termvr-agent.tmp")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(tmpFile).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        setExecutable0755(tmpFile)
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.copyTo(targetFile, overwrite = true)
            tmpFile.delete()
        }
        setExecutable0755(targetFile)
        return targetFile
    }

    private fun setExecutable0755(file: File) {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
        }
    }
}

/**
 * Samples host-side PSS memory and process count for one active sandbox child PID.
 */
fun interface HostProcessPssSampler {
    fun samplePssBytes(pid: Long): Long
}

/**
 * Coordinates sandbox process lifecycles, mux sessions, DNS/proxy bridges, checkpoints, and wake locks.
 */
class SandboxSupervisor(
    private val filesDir: File,
    private val cacheDir: File,
    private val commandBuilder: GvxCommandBuilder,
    private val proxyBridge: LoopbackProxyBridge,
    private val dnsProxy: LoopbackDnsProxy,
    private val checkpointController: CheckpointController,
    private val readinessInspector: HostReadinessInspector,
    private val processLauncher: GvxProcessLauncher,
    private val agentExtractor: AgentAssetExtractor,
    private val guestArch: GuestLinuxArch = GuestLinuxArch.ARM64,
    private val ptyBackend: PtyBackend = NativePtyBridge,
    private val hostPssSampler: HostProcessPssSampler = HostProcessPssSampler { 0L },
    private val hostPortProbe: HostPortProbe = NoOpHostPortProbe,
    private val gitWorktreeManager: GitWorktreeManager = GitWorktreeManager(filesDir),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {

    private val _runStates = MutableStateFlow<Map<String, SandboxRunState>>(emptyMap())
    val runStates: StateFlow<Map<String, SandboxRunState>> = _runStates.asStateFlow()

    private val _telemetryByProfile = MutableStateFlow<Map<String, SandboxTelemetrySnapshot>>(emptyMap())
    val telemetryByProfile: StateFlow<Map<String, SandboxTelemetrySnapshot>> = _telemetryByProfile.asStateFlow()

    private val _publishedPortsByProfile = MutableStateFlow<Map<String, List<PublishedPortMapping>>>(emptyMap())
    val publishedPortsByProfile: StateFlow<Map<String, List<PublishedPortMapping>>> = _publishedPortsByProfile.asStateFlow()

    private val _lanReconfirmationRequired = MutableStateFlow<Set<String>>(emptySet())
    val lanReconfirmationRequired: StateFlow<Set<String>> = _lanReconfirmationRequired.asStateFlow()

    private val _wakeLockRemainingSeconds = MutableStateFlow<Long?>(null)
    val wakeLockRemainingSeconds: StateFlow<Long?> = _wakeLockRemainingSeconds.asStateFlow()

    private val _notificationsDeniedBanner = MutableStateFlow(false)
    val notificationsDeniedBanner: StateFlow<Boolean> = _notificationsDeniedBanner.asStateFlow()

    private val _hostReadiness = MutableStateFlow(readinessInspector.inspect())
    val hostReadiness: StateFlow<HostReadinessReport> = _hostReadiness.asStateFlow()

    private val _activeServiceNotice = MutableStateFlow<String?>(null)
    val activeServiceNotice: StateFlow<String?> = _activeServiceNotice.asStateFlow()

    private val profileLocks = ConcurrentHashMap<String, Mutex>()
    private val sessions = ConcurrentHashMap<String, ManagedSandbox>()
    private val cachedProfiles = ConcurrentHashMap<String, SandboxProfile>()
    private val bridgeLock = Any()
    private val helperSeq = AtomicInteger(1)
    private val telemetryPollCount = AtomicLong(0L)
    private val observedChildSigkill = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var lastNetworkHandle: Long? = null

    @Volatile
    private var wakeLockExpiresAtMs: Long? = null

    private var wakeLockCountdownJob: Job? = null

    @Volatile
    var wakeLockAcquireHook: ((Int) -> Unit)? = null

    @Volatile
    var wakeLockReleaseHook: (() -> Unit)? = null

    val completedTelemetryPolls: Long
        get() = telemetryPollCount.get()

    fun refreshHostReadiness(): HostReadinessReport {
        val rawReport = readinessInspector.inspect()
        val report = if (observedChildSigkill.get() && !rawReport.phantomKillerDetected) {
            rawReport.copy(
                phantomKillerDetected = true,
                remediationKind = HostReadinessInspector.remediationForSdk(rawReport.hostSdkInt),
            )
        } else {
            rawReport
        }
        _hostReadiness.value = report
        return report
    }

    fun setNotificationsPermissionGranted(granted: Boolean) {
        _notificationsDeniedBanner.value = !granted
        if (!granted) {
            val remainingSec = _wakeLockRemainingSeconds.value
            val maxSec = MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES * 60L
            if (remainingSec != null &&
                (remainingSec <= INDEFINITE_WAKE_LOCK_SECONDS || remainingSec > maxSec)
            ) {
                acquireWakeLock(MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES)
            }
        }
    }

    fun clearNotice() {
        _activeServiceNotice.value = null
    }

    fun getMuxClient(profileId: String): MuxSessionClient? = sessions[profileId]?.muxClient

    fun getRawPtyHandle(profileId: String): HostPtyHandle? = sessions[profileId]?.rawPtyHandle

    fun resizeRawPty(
        profileId: String,
        rows: Int,
        cols: Int,
        xpixel: Int = 0,
        ypixel: Int = 0,
    ) {
        val managed = sessions[profileId] ?: return
        managed.rawPtyHandle?.resize(
            rows = rows,
            cols = cols,
            xpixel = xpixel,
            ypixel = ypixel,
            childPid = managed.process.pid,
        )
    }

    fun markProfileActivity(profileId: String) {
        sessions[profileId]?.lastActivityMillis?.set(clockMillis())
    }

    /**
     * Starts or restores a sandbox profile according to checkpoint availability and LAN confirmation.
     */
    suspend fun startOrResumeSandbox(
        profile: SandboxProfile,
        hostRpcDelegate: HostRpcDelegate? = null,
        reconfirmLan: Boolean = false,
        preserveCheckpointOnFailure: Boolean = false,
    ): SandboxRunState = withContext(Dispatchers.IO) {
        var upgradeLanRulesWhileRunning = false
        val lockResult: SandboxRunState? = profileMutex(profile.id).withLock {
            val existing = sessions[profile.id]
            if (existing != null && existing.process.isAlive) {
                cachedProfiles[profile.id] = profile
                if (reconfirmLan) {
                    _lanReconfirmationRequired.update { it - profile.id }
                    if (profile.portForwards.any { it.exposesLan } &&
                        existing.profile.portForwards.none { it.exposesLan }
                    ) {
                        upgradeLanRulesWhileRunning = true
                        return@withLock null
                    }
                }
                return@withLock _runStates.value[profile.id] ?: SandboxRunState.RUNNING
            }

            cachedProfiles[profile.id] = profile
            ensureBridgesRunning()
            proxyBridge.updatePolicy(profile.network.outboundProxy, profile.network.isolateLanEgress)
            val gitSpec = profile.gitWorktree
            if (gitSpec != null) {
                try {
                    gitWorktreeManager.provisionWorktreeForContainer(profile.id, gitSpec)
                } catch (e: Exception) {
                    setRunState(profile.id, SandboxRunState.FAILED)
                    throw e
                }
            }
            commandBuilder.ensureHostDirectories(profile, clearScratchTmp = sessions.isEmpty())
            if (profile.muxMode == MuxMode.STDIO_MUX) {
                agentExtractor.ensureExtracted(guestArch)
            }

            val needsLanGate = profile.id in _lanReconfirmationRequired.value && !reconfirmLan
            val effectivePorts = if (needsLanGate) {
                profile.portForwards.map { it.downgradeLanToLoopback() }
            } else {
                profile.portForwards
            }

            val prevMeta = checkpointController.readMetadata(profile.id)
            val activeCheckpoint = checkpointController.resolveActiveCheckpoint(profile.id)
            if (activeCheckpoint == null && prevMeta != null && !prevMeta.superseded) {
                _activeServiceNotice.value =
                    "Saved checkpoint for '${profile.displayName}' was superseded after engine update; starting cold boot."
            }

            val isRestore = activeCheckpoint != null
            val launchPorts = if (activeCheckpoint != null) {
                val checkpointPorts = if (needsLanGate) {
                    activeCheckpoint.metadata.resolvedPorts.map { it.downgradeLanToLoopback() }
                } else {
                    activeCheckpoint.metadata.resolvedPorts
                }
                resolveRestorePorts(effectivePorts, checkpointPorts)
            } else {
                effectivePorts
            }

            try {
                validatePortRulesBeforeBind(
                    profileId = profile.id,
                    displayName = profile.displayName,
                    candidateRules = launchPorts,
                )
            } catch (e: PortConflictException) {
                setRunState(profile.id, if (isRestore) SandboxRunState.FROZEN else SandboxRunState.FAILED)
                throw e
            }

            if (reconfirmLan) {
                _lanReconfirmationRequired.update { it - profile.id }
            }

            val dnsPort = dnsProxy.boundPort
            val controlSock = commandBuilder.controlSocketFile(profile.id)
            controlSock.parentFile?.mkdirs()
            if (controlSock.exists()) controlSock.delete()

            val preopenedCheckpointDir = activeCheckpoint?.slotDir
                ?: checkpointController.prepareCheckpointSlotDir(profile.id)
            setRunState(profile.id, if (isRestore) SandboxRunState.RESTORING else SandboxRunState.STARTING)

            val hostEnv = commandBuilder.buildHostEnvironment(
                trustHostCaCerts = profile.trustHostCaCerts,
                hostProxyBridge = proxyBridge.hostBridgeEnv(),
                includeProxyVars = false,
            )

            val args = if (activeCheckpoint != null) {
                commandBuilder.buildRestoreArgs(
                    profile = profile,
                    slotDir = activeCheckpoint.slotDir,
                    dnsPort = dnsPort,
                    controlSock = controlSock,
                    resolvedPorts = launchPorts,
                    guestArch = guestArch,
                )
            } else {
                commandBuilder.buildRunArgs(
                    profile = profile,
                    dnsPort = dnsPort,
                    controlSock = controlSock,
                    activePorts = launchPorts,
                    guestArch = guestArch,
                    checkpointDir = preopenedCheckpointDir,
                )
            }

            var rawPtyHandle: HostPtyHandle? = null
            var ptySlaveDevice: File? = null
            if (profile.muxMode == MuxMode.RAW_PTY) {
                rawPtyHandle = ptyBackend.openPty(rows = 24, cols = 80)
                ptySlaveDevice = File(rawPtyHandle.slavePath)
            }

            val process = try {
                processLauncher.launch(args, hostEnv, ptySlaveDevice)
            } catch (e: Exception) {
                rawPtyHandle?.close()
                val addressInUse = PortConflictDetector.isAddressInUseError(e)
                if (isRestore && (preserveCheckpointOnFailure || addressInUse)) {
                    setRunState(profile.id, SandboxRunState.FROZEN)
                } else {
                    setRunState(profile.id, SandboxRunState.FAILED)
                }
                if (addressInUse) {
                    val msg = PortConflictDetector.formatAddressInUseMessage(e.message.orEmpty(), launchPorts)
                    throw PortConflictException(
                        conflictingPort = PortConflictDetector.extractAddressInUsePort(e.message.orEmpty(), launchPorts),
                        protocol = null,
                        message = msg,
                        cause = e,
                    )
                }
                throw e
            }

            val publishedList = ArrayList<PublishedPortMapping>().apply {
                addAll(PortConflictDetector.buildFixedPublishedPortMappings(launchPorts))
            }
            val recentStderr = ArrayDeque<String>()
            val stderrThread = Thread(
                {
                    try {
                        BufferedReader(InputStreamReader(process.stderrStream, StandardCharsets.UTF_8)).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isNotBlank()) {
                                    synchronized(recentStderr) {
                                        if (recentStderr.size >= 12) {
                                            recentStderr.removeFirst()
                                        }
                                        recentStderr.addLast(line)
                                    }
                                }
                                val parsed = GvxCommandBuilder.parsePublishingLine(line)
                                if (parsed != null) {
                                    synchronized(publishedList) {
                                        val exists = publishedList.any {
                                            it.protocol == parsed.protocol &&
                                                it.hostPort == parsed.hostPort &&
                                                it.guestPort == parsed.guestPort &&
                                                it.listenHost == parsed.listenHost
                                        }
                                        if (!exists) {
                                            publishedList.add(parsed)
                                        }
                                        _publishedPortsByProfile.update { current ->
                                            current + (profile.id to publishedList.toList())
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: IOException) {
                    }
                },
                "termvr-stderr-${profile.id}",
            ).apply {
                isDaemon = true
                start()
            }

            val muxClient = if (profile.muxMode == MuxMode.STDIO_MUX) {
                MuxSessionClient(
                    inputStream = process.stdoutStream,
                    outputStream = process.stdinStream,
                    sharesDir = File(cacheDir, "shares"),
                    proxyBridge = proxyBridge,
                    hostRpcDelegate = hostRpcDelegate,
                    clipboardReadPolicyProvider = {
                        cachedProfiles[profile.id]?.guestClipboardReadPolicy ?: profile.guestClipboardReadPolicy
                    },
                ).also { client ->
                    try {
                        client.start(sendHostHello = true)
                        if (isRestore) {
                            client.resync(timeoutMs = RESTORE_RESYNC_TIMEOUT_MS)
                        } else {
                            client.awaitHello(timeoutMs = COLD_BOOT_HELLO_TIMEOUT_MS)
                        }
                    } catch (e: Exception) {
                        client.close()
                        process.destroyGracefully()
                        try {
                            stderrThread.join(250L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        _publishedPortsByProfile.update { current -> current - profile.id }
                        val stderrTail = synchronized(recentStderr) { recentStderr.joinToString(" | ") }
                        val exitCode = if (!process.isAlive) {
                            runCatching { process.waitForExit() }.getOrDefault(-1)
                        } else {
                            -1
                        }
                        val addressInUse = PortConflictDetector.isAddressInUseError(e, stderrTail)
                        val reason = e.message?.takeIf { it.isNotBlank() }
                            ?: if (e is TimeoutException) {
                                "Timed out waiting for sandbox handshake"
                            } else {
                                "Sandbox start failed"
                            }

                        if (isRestore) {
                            if (preserveCheckpointOnFailure || addressInUse) {
                                setRunState(profile.id, SandboxRunState.FROZEN)
                            } else {
                                checkpointController.markSuperseded(
                                    profileId = profile.id,
                                    reason = "Restore failed: $reason",
                                )
                                setRunState(profile.id, SandboxRunState.FAILED)
                            }
                        } else {
                            setRunState(profile.id, SandboxRunState.FAILED)
                        }

                        if (addressInUse) {
                            val combined = stderrTail.ifBlank { e.message.orEmpty() }
                            val msg = PortConflictDetector.formatAddressInUseMessage(combined, launchPorts)
                            throw PortConflictException(
                                conflictingPort = PortConflictDetector.extractAddressInUsePort(combined, launchPorts),
                                protocol = null,
                                message = msg,
                                cause = e,
                            )
                        }
                        if (isSigkillExitCode(exitCode)) {
                            observedChildSigkill.set(true)
                            val readiness = refreshHostReadiness()
                            val msg = formatUnexpectedExitDiagnostic(
                                containerName = profile.displayName,
                                exitCode = exitCode,
                                hostSdkInt = readiness.hostSdkInt,
                                stderrSummary = stderrTail,
                            )
                            _activeServiceNotice.value = msg
                            throw IOException(msg, e)
                        }
                        if (stderrTail.isNotBlank()) {
                            throw IOException("$reason: $stderrTail", e)
                        }
                        throw IOException(reason, e)
                    }
                }
            } else {
                null
            }

            val now = clockMillis()
            val managed = ManagedSandbox(
                profile = profile.copy(portForwards = launchPorts),
                process = process,
                muxClient = muxClient,
                rawPtyHandle = rawPtyHandle,
                controlSock = controlSock,
                preopenedCheckpointDir = preopenedCheckpointDir,
                stderrThread = stderrThread,
                recentStderr = recentStderr,
                publishedPorts = publishedList,
                lastActivityMillis = AtomicLong(now),
            )
            sessions[profile.id] = managed
            synchronized(publishedList) {
                _publishedPortsByProfile.update { current ->
                    current + (profile.id to publishedList.toList())
                }
            }
            if (isRestore) {
                // Mark the consumed snapshot superseded once the resumed sandbox begins mutating storage.
                checkpointController.markSuperseded(
                    profileId = profile.id,
                    reason = "Resumed into live session",
                )
            }
            setRunState(profile.id, SandboxRunState.RUNNING)
            refreshHostReadiness()
            SandboxRunState.RUNNING
        }
        if (upgradeLanRulesWhileRunning) {
            return@withContext applyPortForwards(
                profileId = profile.id,
                updatedRules = profile.portForwards,
                checkpointAndRestore = true,
                hostRpcDelegate = hostRpcDelegate,
            )
        }
        lockResult!!
    }

    /**
     * Checkpoints and freezes a running sandbox using dual-slot rotation.
     */
    suspend fun freezeSandbox(profileId: String): SandboxRunState = withContext(Dispatchers.IO) {
        profileMutex(profileId).withLock {
            val managed = sessions[profileId] ?: return@withLock (_runStates.value[profileId] ?: SandboxRunState.STOPPED)
            setRunState(profileId, SandboxRunState.FREEZING)
            val currentPss = _telemetryByProfile.value[profileId]?.pssBytes
                ?: hostPssSampler.samplePssBytes(managed.process.pid)
            val resolvedPorts = synchronized(managed.publishedPorts) {
                GvxCommandBuilder.resolvePortsForRestore(managed.profile.portForwards, managed.publishedPorts)
            }
            try {
                checkpointController.checkpoint(
                    profileId = profileId,
                    controlSocketFile = managed.controlSock,
                    currentPssBytes = currentPss,
                    resolvedPorts = resolvedPorts,
                    preopenedSlotDir = managed.preopenedCheckpointDir,
                    awaitProcessExit = { managed.process.waitForExit() },
                )
                cleanupSessionResources(profileId, managed)
                setRunState(profileId, SandboxRunState.FROZEN)
                releaseWakeLockIfNoRunningSandboxes()
                refreshHostReadiness()
                SandboxRunState.FROZEN
            } catch (e: Exception) {
                if (managed.process.isAlive) {
                    setRunState(profileId, SandboxRunState.RUNNING)
                } else {
                    cleanupSessionResources(profileId, managed)
                    setRunState(profileId, SandboxRunState.FAILED)
                }
                throw e
            }
        }
    }

    suspend fun stopSandbox(profileId: String): Unit = withContext(Dispatchers.IO) {
        profileMutex(profileId).withLock {
            val managed = sessions.remove(profileId)
            if (managed != null) {
                managed.process.destroyGracefully()
                cleanupSessionResources(profileId, managed)
            }
            checkpointController.markSuperseded(
                profileId = profileId,
                reason = "Sandbox stopped",
            )
            setRunState(profileId, SandboxRunState.STOPPED)
            releaseWakeLockIfNoRunningSandboxes()
            refreshHostReadiness()
        }
        Unit
    }

    suspend fun stopAllSandboxes() {
        val ids = sessions.keys.toList()
        for (id in ids) {
            stopSandbox(id)
        }
        releaseWakeLock()
    }

    /**
     * Applies updated outbound proxy settings live in under 50 ms without restarting sandboxes.
     */
    fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) {
        val existing = sessions[profileId]
        val isolate = existing?.profile?.network?.isolateLanEgress
            ?: cachedProfiles[profileId]?.network?.isolateLanEgress
            ?: false
        if (existing != null) {
            existing.profile = existing.profile.copy(
                network = existing.profile.network.copy(outboundProxy = outboundProxy),
            )
        }
        cachedProfiles[profileId]?.let { cached ->
            cachedProfiles[profileId] = cached.copy(
                network = cached.network.copy(outboundProxy = outboundProxy),
            )
        }
        proxyBridge.updatePolicy(outboundProxy, isolate)
    }

    /**
     * Applies updated port-forward rules via Checkpoint & Restore or Cold Restart.
     */
    suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
        hostRpcDelegate: HostRpcDelegate? = null,
    ): SandboxRunState {
        val current = sessions[profileId]?.profile ?: cachedProfiles[profileId]
            ?: error("Unknown profile $profileId")
        val alreadyBoundBySelf = collectBoundPortSet(current.portForwards, _publishedPortsByProfile.value[profileId].orEmpty())
        validatePortRulesBeforeBind(
            profileId = profileId,
            displayName = current.displayName,
            candidateRules = updatedRules,
            skipAlreadyBoundOnProfile = alreadyBoundBySelf,
        )

        val previousProfile = current
        val updatedProfile = current.copy(portForwards = updatedRules)
        cachedProfiles[profileId] = updatedProfile

        val state = _runStates.value[profileId] ?: SandboxRunState.STOPPED
        if (state != SandboxRunState.RUNNING) {
            return state
        }
        return if (checkpointAndRestore) {
            try {
                freezeSandbox(profileId)
            } catch (e: Exception) {
                cachedProfiles[profileId] = previousProfile
                throw e
            }
            try {
                startOrResumeSandbox(
                    profile = updatedProfile,
                    hostRpcDelegate = hostRpcDelegate,
                    reconfirmLan = true,
                    preserveCheckpointOnFailure = true,
                )
            } catch (cancelled: CancellationException) {
                cachedProfiles[profileId] = previousProfile
                throw cancelled
            } catch (restoreErr: Exception) {
                cachedProfiles[profileId] = previousProfile
                if (previousProfile.portForwards != updatedRules &&
                    checkpointController.resolveActiveCheckpoint(profileId) != null
                ) {
                    runCatching {
                        startOrResumeSandbox(
                            profile = previousProfile,
                            hostRpcDelegate = hostRpcDelegate,
                            reconfirmLan = true,
                            preserveCheckpointOnFailure = true,
                        )
                    }
                }
                throw restoreErr
            }
        } else {
            try {
                stopSandbox(profileId)
                startOrResumeSandbox(updatedProfile, hostRpcDelegate = hostRpcDelegate, reconfirmLan = true)
            } catch (err: Throwable) {
                cachedProfiles[profileId] = previousProfile
                throw err
            }
        }
    }

    /**
     * Marks LAN-exposed sandboxes for reconfirmation when Android switches network handles.
     */
    fun onNetworkHandleChanged(networkHandle: Long?) {
        if (networkHandle == null) {
            return
        }
        val prev = lastNetworkHandle
        lastNetworkHandle = networkHandle
        if (prev == null || prev == networkHandle) {
            return
        }
        val affected = HashSet<String>()
        for ((id, profile) in cachedProfiles) {
            if (profile.portForwards.any { it.exposesLan }) {
                affected.add(id)
            }
        }
        for ((id, managed) in sessions) {
            if (managed.profile.portForwards.any { it.exposesLan }) {
                affected.add(id)
            }
        }
        if (affected.isNotEmpty()) {
            _lanReconfirmationRequired.update { it + affected }
        }
    }

    /**
     * Polls guest telemetry and host PSS strictly when `isInteractive == true`.
     */
    fun pollTelemetryOnce(isInteractive: Boolean): Map<String, SandboxTelemetrySnapshot> {
        if (!isInteractive) {
            return emptyMap()
        }
        telemetryPollCount.incrementAndGet()
        val nowMs = clockMillis()
        val nowNs = nanoClock()
        val updated = LinkedHashMap<String, SandboxTelemetrySnapshot>()
        val activeIds = sessions.keys.toList()

        val liveChildCount = readinessInspector.countLiveChildProcesses()
        if (liveChildCount != _hostReadiness.value.liveChildProcessCount) {
            _hostReadiness.update { it.copy(liveChildProcessCount = liveChildCount) }
        }

        for (id in activeIds) {
            val managed = sessions[id] ?: continue
            if ((_runStates.value[id] ?: SandboxRunState.STOPPED) != SandboxRunState.RUNNING) {
                continue
            }
            if (!managed.process.isAlive) {
                if (sessions.remove(id, managed)) {
                    cleanupSessionResources(id, managed)
                    val exitCode = runCatching { managed.process.waitForExit() }.getOrDefault(-1)
                    val stderrTail = synchronized(managed.recentStderr) {
                        managed.recentStderr.joinToString(" | ")
                    }
                    if (isSigkillExitCode(exitCode)) {
                        observedChildSigkill.set(true)
                    }
                    val readiness = refreshHostReadiness()
                    if (exitCode == 0) {
                        setRunState(id, SandboxRunState.STOPPED)
                    } else {
                        setRunState(id, SandboxRunState.FAILED)
                        _activeServiceNotice.value = formatUnexpectedExitDiagnostic(
                            containerName = managed.profile.displayName,
                            exitCode = exitCode,
                            hostSdkInt = readiness.hostSdkInt,
                            stderrSummary = stderrTail,
                        )
                    }
                    releaseWakeLockIfNoRunningSandboxes()
                }
                continue
            }
            val guestSnap = runCatching {
                managed.muxClient?.requestTelemetry(timeoutMs = 1_500L)
            }.getOrNull()

            val cpuNanos = guestSnap?.cpuNanos ?: 0L
            val prevCpu = managed.lastCpuNanos
            val prevNs = managed.lastCpuSampleNs
            val cpuPercent = if (prevNs > 0L && nowNs > prevNs && cpuNanos >= prevCpu) {
                val deltaCpu = (cpuNanos - prevCpu).toDouble()
                val deltaWall = (nowNs - prevNs).toDouble()
                ((deltaCpu / deltaWall) * 100.0).coerceAtLeast(0.0)
            } else {
                0.0
            }
            managed.lastCpuNanos = cpuNanos
            managed.lastCpuSampleNs = nowNs

            val pssBytes = hostPssSampler.samplePssBytes(managed.process.pid)
                .takeIf { it > 0L }
                ?: guestSnap?.rssBytes
                ?: 0L

            val snapshot = SandboxTelemetrySnapshot(
                profileId = id,
                state = _runStates.value[id] ?: SandboxRunState.RUNNING,
                cpuPercent = cpuPercent,
                cpuNanos = cpuNanos,
                pssBytes = pssBytes,
                guestRssBytes = guestSnap?.rssBytes ?: 0L,
                hostChildProcessCount = _hostReadiness.value.liveChildProcessCount,
                guestProcessCount = guestSnap?.procCount ?: 0,
                guestListenPorts = guestSnap?.listenPorts?.filter { it in 1..65535 }.orEmpty(),
                exposesLan = managed.profile.portForwards.any { it.exposesLan },
                activeProxyLabel = formatProxyBadgeLabel(proxyBridge.currentPolicy.outboundProxy),
                sampledAtEpochMillis = nowMs,
            )
            updated[id] = snapshot
        }

        if (updated.isNotEmpty()) {
            _telemetryByProfile.update { current -> current + updated }
        }
        return updated
    }

    /**
     * Auto-checkpoints idle sandboxes (`>= idleTimeoutMinutes`) when memory pressure hits without a wake lock.
     */
    suspend fun onTrimMemory(
        level: Int,
        idleTimeoutMinutes: Int = DEFAULT_IDLE_FREEZE_MINUTES,
    ): List<String> {
        val shouldCheck = level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!shouldCheck) return emptyList()
        if (_wakeLockRemainingSeconds.value != null) return emptyList()

        val thresholdMs = idleTimeoutMinutes.coerceAtLeast(1) * 60_000L
        val now = clockMillis()
        val frozenIds = ArrayList<String>()
        for ((id, managed) in sessions.entries.toList()) {
            if (!managed.profile.autoCheckpointOnLowMemory) continue
            if (!managed.process.isAlive) continue
            if ((now - managed.lastActivityMillis.get()) >= thresholdMs) {
                runCatching {
                    freezeSandbox(id)
                    frozenIds.add(id)
                }
            }
        }
        return frozenIds
    }

    fun acquireWakeLock(requestedMinutes: Int = DEFAULT_WAKE_LOCK_MINUTES): Int {
        val requestedIndefinite = requestedMinutes <= INDEFINITE_WAKE_LOCK_MINUTES
        val effectiveMinutes = if (_notificationsDeniedBanner.value) {
            if (requestedIndefinite) {
                MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES
            } else {
                requestedMinutes.coerceIn(1, MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES)
            }
        } else if (requestedIndefinite) {
            INDEFINITE_WAKE_LOCK_MINUTES
        } else {
            requestedMinutes
        }

        wakeLockCountdownJob?.cancel()
        wakeLockCountdownJob = null

        if (effectiveMinutes <= INDEFINITE_WAKE_LOCK_MINUTES) {
            wakeLockExpiresAtMs = null
            _wakeLockRemainingSeconds.value = INDEFINITE_WAKE_LOCK_SECONDS
            wakeLockAcquireHook?.invoke(INDEFINITE_WAKE_LOCK_MINUTES)
            return INDEFINITE_WAKE_LOCK_MINUTES
        }

        val expiresAt = clockMillis() + effectiveMinutes * 60_000L
        wakeLockExpiresAtMs = expiresAt
        _wakeLockRemainingSeconds.value = effectiveMinutes * 60L
        wakeLockAcquireHook?.invoke(effectiveMinutes)

        wakeLockCountdownJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                val target = wakeLockExpiresAtMs ?: break
                val remSec = ((target - clockMillis()) / 1_000L).coerceAtLeast(0L)
                if (remSec <= 0L) {
                    releaseWakeLock()
                    break
                }
                _wakeLockRemainingSeconds.value = remSec
            }
        }
        return effectiveMinutes
    }

    fun releaseWakeLock() {
        wakeLockCountdownJob?.cancel()
        wakeLockCountdownJob = null
        wakeLockExpiresAtMs = null
        _wakeLockRemainingSeconds.value = null
        wakeLockReleaseHook?.invoke()
    }

    /**
     * Runs a short-lived helper container mounting a stopped named `ext4lite` volume for `FilesScreen`.
     */
    suspend fun <T> withStoppedVolumeHelper(
        volumeName: String,
        helperImageRef: String,
        readOnly: Boolean = false,
        isRootfsVolume: Boolean = false,
        block: suspend (MuxSessionClient, String) -> T,
    ): T = withContext(Dispatchers.IO) {
        ensureBridgesRunning()
        agentExtractor.ensureExtracted(guestArch)
        val helperId = "volhelp-${helperSeq.getAndIncrement()}"
        val controlSock = commandBuilder.controlSocketFile(helperId)
        controlSock.parentFile?.mkdirs()
        if (controlSock.exists()) controlSock.delete()

        val args = commandBuilder.buildStoppedVolumeHelperArgs(
            volumeName = volumeName,
            helperProfileId = helperId,
            imageRef = helperImageRef,
            dnsPort = dnsProxy.boundPort,
            controlSock = controlSock,
            guestArch = guestArch,
            readOnly = readOnly,
            isRootfsVolume = isRootfsVolume,
        )
        val env = commandBuilder.buildHostEnvironment(trustHostCaCerts = false)
        val proc = processLauncher.launch(args, env, null)
        val mux = MuxSessionClient(
            inputStream = proc.stdoutStream,
            outputStream = proc.stdinStream,
            sharesDir = File(cacheDir, "shares"),
            proxyBridge = proxyBridge,
        )
        try {
            mux.start(sendHostHello = true)
            mux.awaitHello()
            block(mux, if (isRootfsVolume) "/" else "/mnt/volume")
        } finally {
            mux.close()
            proc.destroyGracefully()
            controlSock.delete()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        releaseWakeLock()
        for ((id, managed) in sessions.entries.toList()) {
            managed.process.destroyGracefully()
            cleanupSessionResources(id, managed)
        }
        sessions.clear()
        dnsProxy.close()
        proxyBridge.close()
    }

    private fun ensureBridgesRunning() {
        synchronized(bridgeLock) {
            if (proxyBridge.boundPort <= 0) {
                proxyBridge.start(0)
            }
            if (dnsProxy.boundPort <= 0) {
                dnsProxy.start(0)
            }
        }
    }

    private fun releaseWakeLockIfNoRunningSandboxes() {
        val anyRunning = _runStates.value.values.any { it == SandboxRunState.RUNNING }
        if (!anyRunning) {
            releaseWakeLock()
        }
    }

    private fun cleanupSessionResources(profileId: String, managed: ManagedSandbox) {
        sessions.remove(profileId, managed)
        _publishedPortsByProfile.update { current -> current - profileId }
        _telemetryByProfile.update { current -> current - profileId }
        managed.muxClient?.close()
        managed.rawPtyHandle?.close()
        try {
            managed.stderrThread.join(250L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        managed.controlSock.delete()
    }

    private fun validatePortRulesBeforeBind(
        profileId: String,
        displayName: String,
        candidateRules: List<PortForwardRule>,
        skipAlreadyBoundOnProfile: List<SelfBoundPort> = emptyList(),
    ) {
        PortConflictDetector.detectSelfRuleConflict(candidateRules, displayName)?.let { match ->
            throw PortConflictDetector.toException(match)
        }
        val activeProfiles = LinkedHashMap<String, SandboxProfile>()
        activeProfiles.putAll(cachedProfiles)
        for ((id, managed) in sessions) {
            activeProfiles[id] = managed.profile
        }
        PortConflictDetector.detectCrossProfileConflict(
            targetProfileId = profileId,
            candidateRules = candidateRules,
            profiles = activeProfiles.values,
            runStates = _runStates.value,
            publishedPortsByProfile = _publishedPortsByProfile.value,
        )?.let { match ->
            throw PortConflictDetector.toException(match)
        }
        for (rule in candidateRules) {
            if (!rule.enabled) continue
            if (rule.protocol != ForwardProtocol.TCP && rule.protocol != ForwardProtocol.UDP) continue
            if (rule.hostPortStart <= 0) continue
            for (port in rule.hostPortStart..rule.hostPortEnd) {
                val overlapsSelf = skipAlreadyBoundOnProfile.any { bound ->
                    bound.protocol == rule.protocol &&
                        bound.port == port &&
                        (bound.listenHost == null || PortConflictDetector.listenHostsConflict(rule.listenHost, bound.listenHost))
                }
                if (overlapsSelf) continue
                try {
                    hostPortProbe.verifyAvailable(rule.listenHost, port, rule.protocol)
                } catch (e: PortConflictException) {
                    throw e
                } catch (e: IOException) {
                    if (PortConflictDetector.isAddressInUseError(e)) {
                        val msg = PortConflictDetector.formatAddressInUseMessage(
                            "port $port: ${e.message.orEmpty()}",
                            listOf(rule),
                        )
                        throw PortConflictException(
                            conflictingPort = port,
                            protocol = rule.protocol,
                            message = msg,
                            cause = e,
                        )
                    }
                    throw e
                }
            }
        }
    }

    private data class SelfBoundPort(
        val protocol: ForwardProtocol,
        val listenHost: ListenHost?,
        val port: Int,
    )

    private fun collectBoundPortSet(
        rules: List<PortForwardRule>,
        published: List<PublishedPortMapping>,
    ): List<SelfBoundPort> {
        val out = ArrayList<SelfBoundPort>()
        for (rule in rules) {
            if (!rule.enabled) continue
            if (rule.protocol != ForwardProtocol.TCP && rule.protocol != ForwardProtocol.UDP) continue
            if (rule.hostPortStart <= 0) continue
            for (port in rule.hostPortStart..rule.hostPortEnd) {
                out.add(SelfBoundPort(rule.protocol, rule.listenHost, port))
            }
        }
        for (mapping in published) {
            out.add(
                SelfBoundPort(
                    protocol = mapping.protocol,
                    listenHost = PortConflictDetector.parseListenHost(mapping.listenHost),
                    port = mapping.hostPort,
                ),
            )
        }
        return out
    }

    private fun setRunState(profileId: String, state: SandboxRunState) {
        _runStates.update { current -> current + (profileId to state) }
    }

    private fun profileMutex(profileId: String): Mutex =
        profileLocks.computeIfAbsent(profileId) { Mutex() }

    private class ManagedSandbox(
        @Volatile var profile: SandboxProfile,
        val process: GvxProcessHandle,
        val muxClient: MuxSessionClient?,
        val rawPtyHandle: HostPtyHandle?,
        val controlSock: File,
        val preopenedCheckpointDir: File,
        val stderrThread: Thread,
        val recentStderr: ArrayDeque<String>,
        val publishedPorts: MutableList<PublishedPortMapping>,
        val lastActivityMillis: AtomicLong,
        var lastCpuNanos: Long = 0L,
        var lastCpuSampleNs: Long = 0L,
    )

    companion object {
        const val INDEFINITE_WAKE_LOCK_MINUTES: Int = 0
        const val INDEFINITE_WAKE_LOCK_SECONDS: Long = 0L
        const val DEFAULT_WAKE_LOCK_MINUTES: Int = INDEFINITE_WAKE_LOCK_MINUTES
        const val MAX_NO_NOTIFICATION_WAKE_LOCK_MINUTES: Int = 30
        const val DEFAULT_IDLE_FREEZE_MINUTES: Int = 5
        const val COLD_BOOT_HELLO_TIMEOUT_MS: Long = 300_000L
        const val RESTORE_RESYNC_TIMEOUT_MS: Long = 30_000L
        const val SIGKILL_SIGNAL_NUMBER: Int = 9
        const val SIGKILL_EXIT_CODE: Int = 128 + SIGKILL_SIGNAL_NUMBER

        fun isSigkillExitCode(exitCode: Int): Boolean =
            exitCode == SIGKILL_EXIT_CODE || exitCode == -SIGKILL_SIGNAL_NUMBER

        /**
         * Formats an actionable diagnostic message when `librungv.so` terminates unexpectedly.
         */
        fun formatUnexpectedExitDiagnostic(
            containerName: String,
            exitCode: Int,
            hostSdkInt: Int,
            stderrSummary: String = "",
        ): String {
            if (isSigkillExitCode(exitCode)) {
                return if (hostSdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "Container '$containerName' stopped on SIGKILL (exit $exitCode) from Android process or memory limits. " +
                        "Enable 'Disable child process restrictions' in Developer Options, or freeze idle containers."
                } else {
                    "Container '$containerName' stopped on SIGKILL (exit $exitCode) from Android 32-process or memory limits. " +
                        "Keep at most 3 containers running, or check System Diagnostics for ADB setup."
                }
            }
            if (PortConflictDetector.isAddressInUseError(null, stderrSummary)) {
                return PortConflictDetector.formatAddressInUseMessage(stderrSummary, emptyList())
            }
            val detail = stderrSummary.trim().take(160)
            return if (detail.isNotEmpty()) {
                "Container '$containerName' exited unexpectedly (code $exitCode): $detail"
            } else {
                "Container '$containerName' exited unexpectedly (code $exitCode)."
            }
        }

        internal fun resolveRestorePorts(
            effectivePorts: List<PortForwardRule>,
            checkpointResolvedPorts: List<PortForwardRule>,
        ): List<PortForwardRule> {
            if (effectivePorts.isEmpty()) return emptyList()
            return effectivePorts.map { rule ->
                if (!rule.enabled || rule.protocol == ForwardProtocol.UNIX_STREAM || rule.hostPortStart != 0) {
                    return@map rule
                }
                if (rule.guestPortStart != rule.guestPortEnd) {
                    return@map rule
                }
                val matched = checkpointResolvedPorts.firstOrNull {
                    it.id == rule.id && it.protocol == rule.protocol && it.hostPortStart > 0
                } ?: checkpointResolvedPorts.firstOrNull {
                    it.protocol == rule.protocol &&
                        it.guestPortStart == rule.guestPortStart &&
                        it.hostPortStart > 0
                }
                if (matched != null) {
                    rule.copy(hostPortStart = matched.hostPortStart, hostPortEnd = matched.hostPortEnd)
                } else {
                    rule
                }
            }
        }

        fun formatProxyBadgeLabel(config: OutboundProxyConfig): String? {
            return when (config.mode) {
                OutboundProxyMode.DIRECT -> null
                OutboundProxyMode.ANDROID_SYSTEM -> "System"
                OutboundProxyMode.CUSTOM -> config.customEndpoint?.protocol?.name ?: "Custom"
            }
        }
    }
}
