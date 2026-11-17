package dev.gfire.termvr.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.IBinder
import dev.gfire.termvr.core.data.CatalogRepository
import dev.gfire.termvr.core.data.GitWorktreeManager
import dev.gfire.termvr.core.data.ProfileRepository
import dev.gfire.termvr.core.data.RungvCommandExecutor
import dev.gfire.termvr.core.data.RungvCommandResult
import dev.gfire.termvr.core.data.RungvVolumeLsEntry
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import dev.gfire.termvr.core.runtime.AndroidNetworkDnsBackend
import dev.gfire.termvr.core.runtime.AndroidSystemProxyResolver
import dev.gfire.termvr.core.runtime.CheckpointController
import dev.gfire.termvr.core.runtime.GvxCommandBuilder
import dev.gfire.termvr.core.runtime.HostPtyHandle
import dev.gfire.termvr.core.runtime.HostReadinessInspector
import dev.gfire.termvr.core.runtime.LoopbackDnsProxy
import dev.gfire.termvr.core.runtime.LoopbackProxyBridge
import dev.gfire.termvr.core.runtime.MuxSessionClient
import dev.gfire.termvr.core.runtime.ProxyTestResult
import dev.gfire.termvr.core.runtime.PublishedPortMapping
import dev.gfire.termvr.core.runtime.SandboxForegroundService
import dev.gfire.termvr.core.runtime.SandboxSupervisor
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Decouples `:app` ViewModels from Android `Service` bindings, `ProcessBuilder`, and `SandboxSupervisor`.
 */
interface SandboxRuntimeGateway {
    val runStates: StateFlow<Map<String, SandboxRunState>>
    val telemetryByProfile: StateFlow<Map<String, SandboxTelemetrySnapshot>>
    val publishedPortsByProfile: StateFlow<Map<String, List<PublishedPortMapping>>>
    val lanReconfirmationRequired: StateFlow<Set<String>>
    val wakeLockRemainingSeconds: StateFlow<Long?>
    val notificationsDeniedBanner: StateFlow<Boolean>
    val hostReadiness: StateFlow<HostReadinessReport>
    val activeServiceNotice: StateFlow<String?>
    val clipboardReadPrompt: StateFlow<ClipboardReadPrompt?>
    val dnsProxyPort: Int

    fun refreshHostReadiness()
    fun setNotificationsPermissionGranted(granted: Boolean)
    fun clearNotice()
    suspend fun startOrResumeSandbox(profile: SandboxProfile, reconfirmLan: Boolean = false): SandboxRunState
    suspend fun freezeSandbox(profileId: String): SandboxRunState
    suspend fun stopSandbox(profileId: String)
    suspend fun stopAllSandboxes()
    suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig)
    suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState

    suspend fun acquireWakeLock(minutes: Int): Int
    suspend fun releaseWakeLock()
    suspend fun discardCheckpoint(profileId: String): Boolean
    suspend fun hasActiveCheckpoint(profileId: String): Boolean
    suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult
    suspend fun listVolumes(): List<RungvVolumeLsEntry>
    suspend fun createVolume(name: String)
    suspend fun removeVolume(name: String, force: Boolean = false)
    suspend fun compactVolume(name: String): Result<Unit> = Result.success(Unit)
    suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean = profile.persistentRootfs && profile.rootfsVolumeName == volumeName,
        block: suspend (MuxSessionClient, String) -> T,
    ): T

    fun getMuxClient(profileId: String): MuxSessionClient?
    fun getRawPtyHandle(profileId: String): HostPtyHandle?
    fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0)
    fun markProfileActivity(profileId: String)
    fun resolveClipboardPrompt(allow: Boolean)
    fun buildRungvHostEnv(trustHostCaCerts: Boolean = false, includeProxyVars: Boolean = true): Map<String, String>
    fun workspaceDir(profileId: String): File
    fun gitWorktreeManager(): GitWorktreeManager {
        val baseFilesDir = workspaceDir("probe-default").parentFile?.parentFile ?: File(".")
        return GitWorktreeManager(baseFilesDir)
    }

    fun effectiveWorkspaceDir(profile: SandboxProfile): File {
        val spec = profile.gitWorktree
        return if (spec != null) {
            gitWorktreeManager().containerWorktreeDir(spec.projectId, profile.id)
        } else {
            workspaceDir(profile.id)
        }
    }
    fun activeWifiIpv4(): String?
    fun openUrl(url: String)
    fun shareText(text: String)
    fun shareNamedFile(fileName: String, content: ByteArray)
    fun startForegroundAction(action: String)
}

/**
 * Production runtime gateway backed by a bound `SandboxForegroundService` and local proxy bridges.
 */
class AndroidSandboxRuntimeGateway(
    context: Context,
    private val secretStore: SecretStore,
    private val profileRepository: ProfileRepository,
) : SandboxRuntimeGateway {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandBuilder = GvxCommandBuilder(appContext.filesDir, appContext.cacheDir)
    private val worktreeManager = GitWorktreeManager(appContext.filesDir)
    private val checkpointController = CheckpointController(appContext.filesDir)
    private val readinessInspector = HostReadinessInspector.create(appContext)
    private val catalogProxyBridge = LoopbackProxyBridge(
        secretStore = secretStore,
        systemProxyResolver = AndroidSystemProxyResolver(appContext),
    )
    private val catalogDnsProxy = LoopbackDnsProxy(
        networkDnsBackend = AndroidNetworkDnsBackend(appContext),
        proxyBridge = catalogProxyBridge,
    )
    private val cachedProfiles = MutableStateFlow<List<SandboxProfile>>(emptyList())

    private val _supervisor = MutableStateFlow<SandboxSupervisor?>(null)
    val supervisor: StateFlow<SandboxSupervisor?> = _supervisor.asStateFlow()

    private val _runStates = MutableStateFlow<Map<String, SandboxRunState>>(emptyMap())
    override val runStates: StateFlow<Map<String, SandboxRunState>> = _runStates.asStateFlow()

    private val _telemetryByProfile = MutableStateFlow<Map<String, SandboxTelemetrySnapshot>>(emptyMap())
    override val telemetryByProfile: StateFlow<Map<String, SandboxTelemetrySnapshot>> = _telemetryByProfile.asStateFlow()

    private val _publishedPortsByProfile = MutableStateFlow<Map<String, List<PublishedPortMapping>>>(emptyMap())
    override val publishedPortsByProfile: StateFlow<Map<String, List<PublishedPortMapping>>> =
        _publishedPortsByProfile.asStateFlow()

    private val _lanReconfirmationRequired = MutableStateFlow<Set<String>>(emptySet())
    override val lanReconfirmationRequired: StateFlow<Set<String>> = _lanReconfirmationRequired.asStateFlow()

    private val _wakeLockRemainingSeconds = MutableStateFlow<Long?>(null)
    override val wakeLockRemainingSeconds: StateFlow<Long?> = _wakeLockRemainingSeconds.asStateFlow()

    private val _notificationsDeniedBanner = MutableStateFlow(false)
    override val notificationsDeniedBanner: StateFlow<Boolean> = _notificationsDeniedBanner.asStateFlow()

    private val _hostReadiness = MutableStateFlow(readinessInspector.inspect())
    override val hostReadiness: StateFlow<HostReadinessReport> = _hostReadiness.asStateFlow()

    private val _activeServiceNotice = MutableStateFlow<String?>(null)
    override val activeServiceNotice: StateFlow<String?> = _activeServiceNotice.asStateFlow()

    val hostRpcBridge = HostRpcBridge(
        context = appContext,
        clipboardPolicyProvider = { profileId ->
            cachedProfiles.value
                .firstOrNull { it.id == profileId }
                ?.guestClipboardReadPolicy
                ?: dev.gfire.termvr.core.model.ClipboardReadPolicy.DISABLED
        },
    )

    override val clipboardReadPrompt: StateFlow<ClipboardReadPrompt?> = hostRpcBridge.clipboardPrompt

    override val dnsProxyPort: Int
        get() = catalogDnsProxy.boundPort

    @Volatile
    private var boundServiceDeferred = CompletableDeferred<SandboxForegroundService>()
    private var collectorJob: Job? = null

    @Volatile
    private var notificationsGrantedCache: Boolean = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? SandboxForegroundService.LocalBinder ?: return
            val fgService = localBinder.getService()
            val sup = fgService.supervisor
            sup.setNotificationsPermissionGranted(notificationsGrantedCache)
            _supervisor.value = sup
            if (!boundServiceDeferred.isCompleted) {
                boundServiceDeferred.complete(fgService)
            }
            attachSupervisorCollectors(sup)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            collectorJob?.cancel()
            _supervisor.value = null
            boundServiceDeferred = CompletableDeferred()
        }
    }

    init {
        scope.launch {
            profileRepository.observeProfiles().collect { cachedProfiles.value = it }
        }
        runCatching { catalogDnsProxy.start(0) }
        runCatching { catalogProxyBridge.start(0) }
        val bindIntent = Intent(appContext, SandboxForegroundService::class.java)
        appContext.bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private val volumeJson = Json { ignoreUnknownKeys = true }

    /**
     * Creates a `RungvCommandExecutor` executing `librungv.so` directly for image catalog operations.
     */
    fun createRungvCommandExecutor(): RungvCommandExecutor = RungvCommandExecutor { args, env ->
        executeRungvCommand(args, env)
    }

    private suspend fun executeRungvCommand(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
    ): RungvCommandResult = withContext(Dispatchers.IO) {
        val rungv = File(appContext.applicationInfo.nativeLibraryDir, "librungv.so")
        if (!rungv.isFile) {
            return@withContext RungvCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "librungv.so not found in ${appContext.applicationInfo.nativeLibraryDir}",
            )
        }
        val cmd = ArrayList<String>(1 + args.size).apply {
            add(rungv.absolutePath)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
        pb.environment().putAll(buildRungvHostEnv())
        pb.environment().putAll(env)
        val proc = pb.start()
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val finished = proc.waitFor(300L, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            RungvCommandResult(exitCode = 124, stdout = stdout, stderr = "rungv command timed out")
        } else {
            RungvCommandResult(exitCode = proc.exitValue(), stdout = stdout, stderr = stderr)
        }
    }

    override fun refreshHostReadiness() {
        val sup = _supervisor.value
        if (sup != null) {
            _hostReadiness.value = sup.refreshHostReadiness()
        } else {
            _hostReadiness.value = readinessInspector.inspect()
        }
    }

    override fun setNotificationsPermissionGranted(granted: Boolean) {
        notificationsGrantedCache = granted
        _supervisor.value?.setNotificationsPermissionGranted(granted)
    }

    override fun clearNotice() {
        _supervisor.value?.clearNotice()
        _activeServiceNotice.value = null
    }

    override suspend fun startOrResumeSandbox(profile: SandboxProfile, reconfirmLan: Boolean): SandboxRunState {
        startForegroundServiceAction(SandboxForegroundService.ACTION_START_FOREGROUND)
        val sup = awaitSupervisor()
        val delegate = hostRpcBridge.createDelegateForProfile(profile.id)
        return sup.startOrResumeSandbox(
            profile = profile,
            hostRpcDelegate = delegate,
            reconfirmLan = reconfirmLan,
        )
    }

    override suspend fun freezeSandbox(profileId: String): SandboxRunState {
        return awaitSupervisor().freezeSandbox(profileId)
    }

    override suspend fun stopSandbox(profileId: String) {
        awaitSupervisor().stopSandbox(profileId)
    }

    override suspend fun stopAllSandboxes() {
        awaitSupervisor().stopAllSandboxes()
    }

    override suspend fun updateOutboundProxyLive(profileId: String, outboundProxy: OutboundProxyConfig) {
        awaitSupervisor().updateOutboundProxyLive(profileId, outboundProxy)
    }

    override suspend fun applyPortForwards(
        profileId: String,
        updatedRules: List<PortForwardRule>,
        checkpointAndRestore: Boolean,
    ): SandboxRunState {
        val sup = awaitSupervisor()
        return sup.applyPortForwards(
            profileId = profileId,
            updatedRules = updatedRules,
            checkpointAndRestore = checkpointAndRestore,
            hostRpcDelegate = hostRpcBridge.createDelegateForProfile(profileId),
        )
    }

    override suspend fun acquireWakeLock(minutes: Int): Int {
        return awaitSupervisor().acquireWakeLock(minutes)
    }

    override suspend fun releaseWakeLock() {
        awaitSupervisor().releaseWakeLock()
    }

    override suspend fun discardCheckpoint(profileId: String): Boolean = withContext(Dispatchers.IO) {
        checkpointController.discardCheckpoint(profileId)
        true
    }

    override suspend fun hasActiveCheckpoint(profileId: String): Boolean = withContext(Dispatchers.IO) {
        checkpointController.readMetadata(profileId)?.let { !it.superseded } ?: false
    }

    override suspend fun testProxy(config: OutboundProxyConfig): ProxyTestResult {
        return catalogProxyBridge.testProxy(config)
    }

    override suspend fun listVolumes(): List<RungvVolumeLsEntry> {
        val result = executeRungvCommand(CatalogRepository.buildVolumeLsArgs())
        check(result.exitCode == 0) {
            result.stderr.ifBlank { "rungv volume ls failed (${result.exitCode})" }
        }
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { volumeJson.decodeFromString<RungvVolumeLsEntry>(it) }
            .toList()
    }

    override suspend fun createVolume(name: String) {
        val result = executeRungvCommand(CatalogRepository.buildVolumeCreateArgs(name))
        check(result.exitCode == 0) {
            result.stderr.ifBlank { "rungv volume create failed (${result.exitCode})" }
        }
    }

    override suspend fun removeVolume(name: String, force: Boolean) {
        val result = executeRungvCommand(CatalogRepository.buildVolumeRmArgs(name, force))
        check(result.exitCode == 0) {
            result.stderr.ifBlank { "rungv volume rm failed (${result.exitCode})" }
        }
    }

    override suspend fun compactVolume(name: String): Result<Unit> = runCatching {
        val gvxCacheDir = File(appContext.filesDir, "gvx-store/gvx")
        val result = executeRungvCommand(CatalogRepository.buildVolumeCompactArgs(name, gvxCacheDir))
        check(result.exitCode == 0) {
            result.stderr.trim().ifEmpty { "rungv volume compact failed (${result.exitCode})" }
        }
    }

    override suspend fun <T> withVolumeClient(
        profile: SandboxProfile,
        volumeName: String,
        readOnly: Boolean,
        isRootfsVolume: Boolean,
        block: suspend (MuxSessionClient, String) -> T,
    ): T {
        val sup = awaitSupervisor()
        return sup.withStoppedVolumeHelper(
            volumeName = volumeName,
            helperImageRef = profile.imageRef,
            readOnly = readOnly,
            isRootfsVolume = isRootfsVolume,
            block = block,
        )
    }

    override fun getMuxClient(profileId: String): MuxSessionClient? = _supervisor.value?.getMuxClient(profileId)

    override fun getRawPtyHandle(profileId: String): HostPtyHandle? = _supervisor.value?.getRawPtyHandle(profileId)

    override fun resizeRawPty(profileId: String, rows: Int, cols: Int, xpixel: Int, ypixel: Int) {
        _supervisor.value?.resizeRawPty(profileId, rows, cols, xpixel, ypixel)
    }

    override fun markProfileActivity(profileId: String) {
        _supervisor.value?.markProfileActivity(profileId)
    }

    override fun resolveClipboardPrompt(allow: Boolean) {
        hostRpcBridge.resolveClipboardPrompt(allow)
    }

    override fun buildRungvHostEnv(trustHostCaCerts: Boolean, includeProxyVars: Boolean): Map<String, String> {
        runCatching { catalogDnsProxy.start(0) }
        runCatching { catalogProxyBridge.start(0) }
        return commandBuilder.buildHostEnvironment(
            trustHostCaCerts = trustHostCaCerts,
            hostProxyBridge = catalogProxyBridge.hostBridgeEnv(),
            includeProxyVars = includeProxyVars,
        )
    }

    override fun workspaceDir(profileId: String): File = commandBuilder.workspaceDir(profileId)

    override fun gitWorktreeManager(): GitWorktreeManager = worktreeManager

    override fun effectiveWorkspaceDir(profile: SandboxProfile): File = commandBuilder.effectiveWorkspaceDir(profile)

    override fun activeWifiIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProps: LinkProperties = cm.getLinkProperties(active) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    override fun openUrl(url: String) {
        hostRpcBridge.openExternalUrl(url)
    }

    override fun shareText(text: String) {
        hostRpcBridge.sharePlainText(text)
    }

    override fun shareNamedFile(fileName: String, content: ByteArray) {
        hostRpcBridge.shareBytesAsFile(fileName, content)
    }

    override fun startForegroundAction(action: String) {
        startForegroundServiceAction(action)
    }

    private fun startForegroundServiceAction(action: String) {
        val intent = Intent(appContext, SandboxForegroundService::class.java).apply {
            this.action = action
        }
        runCatching {
            appContext.startForegroundService(intent)
        }
    }

    private suspend fun awaitSupervisor(): SandboxSupervisor {
        _supervisor.value?.let { return it }
        return withTimeout(10_000L) {
            boundServiceDeferred.await().supervisor
        }
    }

    private fun attachSupervisorCollectors(sup: SandboxSupervisor) {
        collectorJob?.cancel()
        collectorJob = scope.launch {
            launch { sup.runStates.collect { _runStates.value = it } }
            launch { sup.telemetryByProfile.collect { _telemetryByProfile.value = it } }
            launch { sup.publishedPortsByProfile.collect { _publishedPortsByProfile.value = it } }
            launch { sup.lanReconfirmationRequired.collect { _lanReconfirmationRequired.value = it } }
            launch { sup.wakeLockRemainingSeconds.collect { _wakeLockRemainingSeconds.value = it } }
            launch { sup.notificationsDeniedBanner.collect { _notificationsDeniedBanner.value = it } }
            launch { sup.hostReadiness.collect { _hostReadiness.value = it } }
            launch { sup.activeServiceNotice.collect { _activeServiceNotice.value = it } }
        }
    }
}
