package dev.gfire.termvr.core.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.gfire.termvr.core.data.AndroidKeystoreSecretStore
import dev.gfire.termvr.core.model.GuestLinuxArch
import dev.gfire.termvr.core.model.SandboxRunState
import dev.gfire.termvr.core.model.SandboxTelemetrySnapshot
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs interactive Linux sandboxes in a `specialUse` foreground service with telemetry, wake lock, and idle freeze.
 */
class SandboxForegroundService : LifecycleService() {

    inner class LocalBinder : Binder() {
        fun getService(): SandboxForegroundService = this@SandboxForegroundService
    }

    private val binder = LocalBinder()

    private lateinit var notificationManager: NotificationManager
    private lateinit var powerManager: PowerManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var supervisorInstance: SandboxSupervisor

    private var wakeLock: PowerManager.WakeLock? = null
    private var telemetryLoopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiverRegistered = false

    val supervisor: SandboxSupervisor
        get() = supervisorInstance

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopTelemetryPolling()
                Intent.ACTION_SCREEN_ON -> startTelemetryPollingIfInteractive()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        ensureNotificationChannels()
        supervisorInstance = createSupervisor(applicationContext)
        supervisorInstance.wakeLockAcquireHook = { minutes ->
            acquireOsWakeLock(minutes)
            updateNotifications()
        }
        supervisorInstance.wakeLockReleaseHook = {
            releaseOsWakeLock()
            updateNotifications()
        }
        supervisorInstance.setNotificationsPermissionGranted(hasPostNotificationsPermission())

        registerScreenStateReceiver()
        registerNetworkCallback()

        lifecycleScope.launch {
            supervisorInstance.runStates.collectLatest { states ->
                val anyLive = states.values.any { it.isLiveInMemory }
                if (anyLive) {
                    startTelemetryPollingIfInteractive()
                } else {
                    stopTelemetryPolling()
                }
                updateNotifications()
            }
        }
        lifecycleScope.launch {
            supervisorInstance.telemetryByProfile.collectLatest {
                updateNotifications()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        supervisorInstance.setNotificationsPermissionGranted(hasPostNotificationsPermission())

        when (intent?.action) {
            ACTION_TOGGLE_WAKE_LOCK -> {
                if (supervisorInstance.wakeLockRemainingSeconds.value != null) {
                    supervisorInstance.releaseWakeLock()
                } else {
                    val requestedMin = intent.getIntExtra(
                        EXTRA_WAKE_LOCK_MINUTES,
                        SandboxSupervisor.DEFAULT_WAKE_LOCK_MINUTES,
                    )
                    supervisorInstance.acquireWakeLock(requestedMin)
                }
            }

            ACTION_FREEZE_ALL -> {
                lifecycleScope.launch {
                    val runningIds = supervisorInstance.runStates.value
                        .filterValues { it.isLiveInMemory }
                        .keys
                    for (id in runningIds) {
                        runCatching { supervisorInstance.freezeSandbox(id) }
                    }
                    updateNotifications()
                }
            }

            ACTION_STOP_ALL -> {
                lifecycleScope.launch {
                    supervisorInstance.stopAllSandboxes()
                    notificationManager.cancel(NOTIFICATION_STATUS_ID)
                    notificationManager.cancel(NOTIFICATION_FROZEN_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_RESUME_FROZEN -> {
                promoteToForeground(buildActiveNotification())
                val targetId = intent.getStringExtra(EXTRA_PROFILE_ID)
                if (!targetId.isNullOrBlank()) {
                    // Forwarded to UI or handled via callback registered by :app.
                    resumeActionHandler?.invoke(targetId)
                }
            }
        }

        val activeCount = supervisorInstance.runStates.value.values.count(::isForegroundActiveState)
        if (activeCount > 0 || intent?.action == ACTION_START_FOREGROUND) {
            promoteToForeground(buildActiveNotification())
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        lifecycleScope.launch {
            val frozen = supervisorInstance.onTrimMemory(level)
            if (frozen.isNotEmpty()) {
                updateNotifications()
            }
        }
    }

    override fun onDestroy() {
        stopTelemetryPolling()
        unregisterScreenStateReceiver()
        unregisterNetworkCallback()
        releaseOsWakeLock()
        supervisorInstance.close()
        super.onDestroy()
    }

    private fun startTelemetryPollingIfInteractive() {
        if (!powerManager.isInteractive) return
        val anyLive = supervisorInstance.runStates.value.values.any { it.isLiveInMemory }
        if (!anyLive) return
        if (telemetryLoopJob?.isActive == true) return

        telemetryLoopJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!powerManager.isInteractive) {
                    break
                }
                supervisorInstance.pollTelemetryOnce(isInteractive = powerManager.isInteractive)
                delay(TELEMETRY_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopTelemetryPolling() {
        telemetryLoopJob?.cancel()
        telemetryLoopJob = null
    }

    private fun updateNotifications() {
        val states = supervisorInstance.runStates.value
        val activeCount = states.values.count(::isForegroundActiveState)
        val frozenIds = states.filterValues { it == SandboxRunState.FROZEN }.keys.toList()

        if (activeCount > 0) {
            notificationManager.cancel(NOTIFICATION_FROZEN_ID)
            promoteToForeground(buildActiveNotification())
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.cancel(NOTIFICATION_STATUS_ID)
            if (frozenIds.isNotEmpty() && hasPostNotificationsPermission()) {
                notificationManager.notify(NOTIFICATION_FROZEN_ID, buildFrozenNotification(frozenIds))
            } else {
                notificationManager.cancel(NOTIFICATION_FROZEN_ID)
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_STATUS_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_STATUS_ID, notification)
            }
        }
        if (promoted.isFailure) {
            val err = promoted.exceptionOrNull()
            if (err is IllegalStateException ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    err is android.app.ForegroundServiceStartNotAllowedException)
            ) {
                if (hasPostNotificationsPermission()) {
                    runCatching { notificationManager.notify(NOTIFICATION_STATUS_ID, notification) }
                }
            } else if (err != null) {
                throw err
            }
        }
    }

    private fun buildActiveNotification(): Notification {
        val states = supervisorInstance.runStates.value
        val snapshots = supervisorInstance.telemetryByProfile.value
        val activeIds = states.filterValues(::isForegroundActiveState).keys
        val runningSnapshots = snapshots.filterKeys { it in activeIds }.values
        val activeCount = activeIds.size.coerceAtLeast(1)

        val title = formatActiveNotificationTitle(activeCount, activeIds)
        val summaryText = formatStatusNotificationText(
            activeCount = activeCount,
            snapshots = runningSnapshots,
            wakeLockRemainingSeconds = supervisorInstance.wakeLockRemainingSeconds.value,
        )

        val wakeAction = NotificationCompat.Action(
            0,
            if (supervisorInstance.wakeLockRemainingSeconds.value != null) "Release Lock" else "Wake Lock",
            serviceActionPendingIntent(ACTION_TOGGLE_WAKE_LOCK, REQUEST_CODE_WAKE_LOCK),
        )
        val freezeAction = NotificationCompat.Action(
            0,
            "Freeze",
            serviceActionPendingIntent(ACTION_FREEZE_ALL, REQUEST_CODE_FREEZE),
        )
        val stopAction = NotificationCompat.Action(
            0,
            "Stop All",
            serviceActionPendingIntent(ACTION_STOP_ALL, REQUEST_CODE_STOP_ALL),
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(summaryText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(wakeAction)
            .addAction(freezeAction)
            .addAction(stopAction)
            .build()
    }

    private fun buildFrozenNotification(frozenIds: List<String>): Notification {
        val firstId = frozenIds.first()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = ACTION_RESUME_FROZEN
            putExtra(EXTRA_PROFILE_ID, firstId)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val resumePending = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                REQUEST_CODE_RESUME,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            val resumeIntent = Intent(this, SandboxForegroundService::class.java).apply {
                action = ACTION_RESUME_FROZEN
                putExtra(EXTRA_PROFILE_ID, firstId)
            }
            PendingIntent.getForegroundService(
                this,
                REQUEST_CODE_RESUME,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val text = if (frozenIds.size == 1) {
            "1 container frozen ($firstId)"
        } else {
            "${frozenIds.size} containers frozen"
        }
        return NotificationCompat.Builder(this, CHANNEL_FROZEN_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Frozen Containers")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(resumePending)
            .addAction(NotificationCompat.Action(0, "Resume", resumePending))
            .build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SandboxForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS_ID,
            "Sandbox Status",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active TermVR sandbox CPU, memory, proxy, and wake-lock telemetry"
            setShowBadge(false)
        }
        val frozenChannel = NotificationChannel(
            CHANNEL_FROZEN_ID,
            "Frozen Sandboxes",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Saved TermVR sandbox checkpoints ready to resume"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(statusChannel)
        notificationManager.createNotificationChannel(frozenChannel)
    }

    private fun hasPostNotificationsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireOsWakeLock(durationMinutes: Int) {
        releaseOsWakeLock()
        val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermVR:SandboxWakeLock")
        lock.setReferenceCounted(false)
        if (durationMinutes <= SandboxSupervisor.INDEFINITE_WAKE_LOCK_MINUTES) {
            lock.acquire()
        } else {
            lock.acquire(durationMinutes * 60_000L)
        }
        wakeLock = lock
    }

    private fun releaseOsWakeLock() {
        val existing = wakeLock
        wakeLock = null
        if (existing != null && existing.isHeld) {
            runCatching { existing.release() }
        }
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        screenReceiverRegistered = false
    }

    private fun registerNetworkCallback() {
        runCatching {
            val active = connectivityManager.activeNetwork
            val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
            if (active != null && caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                supervisorInstance.onNetworkHandleChanged(active.networkHandle)
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                supervisorInstance.onNetworkHandleChanged(network.networkHandle)
            }

            override fun onLost(network: Network) {
                supervisorInstance.onNetworkHandleChanged(null)
            }
        }
        networkCallback = cb
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(req, cb) }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(cb) }
    }

    private fun createSupervisor(context: Context): SandboxSupervisor {
        val appCtx = context.applicationContext
        val files = appCtx.filesDir
        val cache = appCtx.cacheDir
        val nativeLibDir = File(appCtx.applicationInfo.nativeLibraryDir)
        val rungvFile = File(nativeLibDir, "librungvx.so")

        val secretStore = AndroidKeystoreSecretStore(File(files, "secrets"))
        val proxyBridge = LoopbackProxyBridge(
            secretStore = secretStore,
            systemProxyResolver = AndroidSystemProxyResolver(appCtx),
        )
        val dnsProxy = LoopbackDnsProxy(
            networkDnsBackend = AndroidNetworkDnsBackend(appCtx),
            proxyBridge = proxyBridge,
        )
        val commandBuilder = GvxCommandBuilder(filesDir = files, cacheDir = cache)
        val controlClient = GvxControlClient()
        val checkpointController = CheckpointController(filesDir = files, controlClient = controlClient)
        val readinessInspector = HostReadinessInspector.create(appCtx)
        val hostAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val arch = GuestLinuxArch.fromAndroidAbi(hostAbi) ?: GuestLinuxArch.ARM64
        val activityManager = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        val pssSampler = HostProcessPssSampler { pid ->
            if (pid <= 0 || activityManager == null) {
                0L
            } else {
                val infos: Array<Debug.MemoryInfo>? = activityManager.getProcessMemoryInfo(intArrayOf(pid.toInt()))
                val totalKb = infos?.firstOrNull()?.totalPss ?: 0
                totalKb.toLong() * 1024L
            }
        }

        return SandboxSupervisor(
            filesDir = files,
            cacheDir = cache,
            commandBuilder = commandBuilder,
            proxyBridge = proxyBridge,
            dnsProxy = dnsProxy,
            checkpointController = checkpointController,
            readinessInspector = readinessInspector,
            processLauncher = AndroidRungvProcessLauncher(rungvFile),
            agentExtractor = AndroidAgentAssetExtractor(appCtx, files),
            guestArch = arch,
            hostPssSampler = pssSampler,
            hostPortProbe = SocketBindHostPortProbe,
            controlClient = controlClient,
        )
    }

    companion object {
        const val CHANNEL_STATUS_ID: String = "sandbox_status"
        const val CHANNEL_FROZEN_ID: String = "sandbox_frozen"
        const val NOTIFICATION_STATUS_ID: Int = 1001
        const val NOTIFICATION_FROZEN_ID: Int = 1002
        const val TELEMETRY_POLL_INTERVAL_MS: Long = 2_000L

        const val ACTION_START_FOREGROUND: String = "dev.gfire.termvr.action.START_FOREGROUND"
        const val ACTION_TOGGLE_WAKE_LOCK: String = "dev.gfire.termvr.action.TOGGLE_WAKE_LOCK"
        const val ACTION_FREEZE_ALL: String = "dev.gfire.termvr.action.FREEZE_ALL"
        const val ACTION_STOP_ALL: String = "dev.gfire.termvr.action.STOP_ALL"
        const val ACTION_RESUME_FROZEN: String = "dev.gfire.termvr.action.RESUME_FROZEN"
        const val EXTRA_WAKE_LOCK_MINUTES: String = "wake_lock_minutes"
        const val EXTRA_PROFILE_ID: String = "profile_id"

        private const val REQUEST_CODE_WAKE_LOCK = 101
        private const val REQUEST_CODE_FREEZE = 102
        private const val REQUEST_CODE_STOP_ALL = 103
        private const val REQUEST_CODE_RESUME = 104

        @Volatile
        var resumeActionHandler: ((String) -> Unit)? = null

        /**
         * Returns true when [state] represents an active or transitioning sandbox requiring foreground protection.
         */
        fun isForegroundActiveState(state: SandboxRunState): Boolean = when (state) {
            SandboxRunState.STARTING,
            SandboxRunState.RUNNING,
            SandboxRunState.PAUSED,
            SandboxRunState.RESTORING,
            SandboxRunState.FREEZING -> true
            SandboxRunState.STOPPED,
            SandboxRunState.FROZEN,
            SandboxRunState.FAILED -> false
        }

        /**
         * Formats the `sandbox_status` notification title for single or multiple active containers.
         */
        fun formatActiveNotificationTitle(
            activeCount: Int,
            activeProfileIds: Collection<String> = emptyList(),
        ): String {
            return if (activeCount > 1) {
                "$activeCount containers running"
            } else if (activeProfileIds.size == 1) {
                "TermVR (${activeProfileIds.first()})"
            } else {
                "TermVR Sandbox Runtime"
            }
        }

        /**
         * Formats the `sandbox_status` notification line and `[Wake Lock]`, `[Proxy]`, `[LAN 0.0.0.0]` badges.
         */
        fun formatStatusNotificationText(
            activeCount: Int,
            snapshots: Collection<SandboxTelemetrySnapshot>,
            wakeLockRemainingSeconds: Long?,
        ): String {
            val totalCpuPct = snapshots.sumOf { it.cpuPercent }.roundToInt()
            val totalPssMiB = snapshots.sumOf { it.pssMiB }
            val containerLabel = if (activeCount == 1) "1 active container" else "$activeCount active containers"
            val base = "$containerLabel \u2022 CPU $totalCpuPct% \u2022 $totalPssMiB MiB PSS"

            val badges = ArrayList<String>(3)
            if (wakeLockRemainingSeconds != null) {
                if (wakeLockRemainingSeconds <= SandboxSupervisor.INDEFINITE_WAKE_LOCK_SECONDS) {
                    badges.add("[Wake Lock]")
                } else {
                    val mins = ((wakeLockRemainingSeconds + 59L) / 60L).coerceAtLeast(1L)
                    badges.add("[Wake Lock ${mins}m]")
                }
            }
            val proxyLabel = snapshots.firstNotNullOfOrNull { it.activeProxyLabel }
            if (proxyLabel != null) {
                badges.add("[Proxy: $proxyLabel]")
            }
            if (snapshots.any { it.exposesLan }) {
                badges.add("[LAN 0.0.0.0]")
            }
            return if (badges.isEmpty()) base else "$base ${badges.joinToString(" ")}"
        }
    }
}
