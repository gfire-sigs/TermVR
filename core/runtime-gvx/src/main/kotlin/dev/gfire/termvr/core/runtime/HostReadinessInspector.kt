package dev.gfire.termvr.core.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import dev.gfire.termvr.core.model.HostReadinessReport
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.StorageUsageBreakdown
import java.io.File

/**
 * Abstracts historical exit-reason retrieval for Android host readiness checks and JVM tests.
 */
fun interface HistoricalExitReasonProvider {
    fun recentExitReasons(): List<HistoricalExitRecord>
}

data class HistoricalExitRecord(
    val reason: Int,
    val status: Int,
    val importance: Int,
    val description: String = "",
)

/**
 * Default Android implementation querying `ActivityManager.getHistoricalProcessExitReasons`.
 */
class AndroidHistoricalExitReasonProvider(context: Context) : HistoricalExitReasonProvider {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun recentExitReasons(): List<HistoricalExitRecord> {
        val am = activityManager ?: return emptyList()
        return am.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_EXIT_REASONS)
            .map { info ->
                HistoricalExitRecord(
                    reason = info.reason,
                    status = info.status,
                    importance = info.importance,
                    description = info.description.orEmpty(),
                )
            }
    }

    companion object {
        private const val MAX_EXIT_REASONS = 16
    }
}

/**
 * Inspects Android phantom-process kills, live `/proc` child counts, page size, and disk usage.
 */
class HostReadinessInspector(
    private val filesDir: File,
    private val procRoot: File = File("/proc"),
    private val exitReasonProvider: HistoricalExitReasonProvider = HistoricalExitReasonProvider { emptyList() },
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val hostAbiProvider: () -> String = {
        (Build.SUPPORTED_ABIS as Array<String>?)?.firstOrNull().orEmpty().ifEmpty { "arm64-v8a" }
    },
    private val pageSizeProvider: () -> Int = {
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE).toInt() }
            .getOrDefault(DEFAULT_PAGE_SIZE_BYTES)
            .takeIf { it > 0 }
            ?: DEFAULT_PAGE_SIZE_BYTES
    },
    private val totalHostRamMiBProvider: () -> Int = { 0 },
    private val freeDiskMiBProvider: () -> Int = { computeFreeDiskMiB(filesDir) },
    private val selfPidProvider: () -> Int = { Process.myPid() },
) {

    fun inspect(): HostReadinessReport {
        val sdkInt = sdkIntProvider().coerceAtLeast(HostReadinessReport.MIN_SUPPORTED_SDK_INT)
        val hostAbi = hostAbiProvider()
        val pageSize = pageSizeProvider()
        val exitRecords = exitReasonProvider.recentExitReasons()
        val killedRecord = detectPhantomOrResourceKill(exitRecords)
        val remediation = if (killedRecord == null) {
            PhantomProcessRemediation.NONE
        } else {
            remediationForSdk(sdkInt)
        }

        return HostReadinessReport(
            hostSdkInt = sdkInt,
            hostAbi = hostAbi,
            kernelPageSizeBytes = pageSize,
            totalHostRamMiB = totalHostRamMiBProvider().coerceAtLeast(0),
            freeDiskMiB = freeDiskMiBProvider().coerceAtLeast(0),
            liveChildProcessCount = countLiveChildProcesses(),
            phantomKillerDetected = killedRecord != null,
            remediationKind = remediation,
            lastKilledExitReasonCode = killedRecord?.reason,
            diskUsage = computeStorageBreakdown(),
        )
    }

    /**
     * Counts live `/proc` descendant processes of this app without scanning storage directories.
     */
    fun countLiveChildProcesses(): Int = countDescendantProcesses(procRoot, selfPidProvider())

    fun computeStorageBreakdown(): StorageUsageBreakdown {
        val gvxRoot = File(filesDir, "gvx-store/gvx")
        return StorageUsageBreakdown(
            layersBytes = directorySizeBytes(File(gvxRoot, "layers")),
            volumesBytes = directorySizeBytes(File(gvxRoot, "volumes")),
            workspacesBytes = directorySizeBytes(File(filesDir, "workspaces")) +
                directorySizeBytes(File(filesDir, "git-projects")),
            checkpointsBytes = directorySizeBytes(File(filesDir, "checkpoints")),
        )
    }

    companion object {
        private const val SIGKILL_NUMBER = 9
        private const val DEFAULT_PAGE_SIZE_BYTES = 4096

        fun create(context: Context): HostReadinessInspector {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            return HostReadinessInspector(
                filesDir = appCtx.filesDir,
                exitReasonProvider = AndroidHistoricalExitReasonProvider(appCtx),
                totalHostRamMiBProvider = {
                    if (am == null) {
                        0
                    } else {
                        val memInfo = ActivityManager.MemoryInfo()
                        am.getMemoryInfo(memInfo)
                        (memInfo.totalMem / (1024L * 1024L)).toInt()
                    }
                },
            )
        }

        fun remediationForSdk(sdkInt: Int): PhantomProcessRemediation =
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE
            } else {
                PhantomProcessRemediation.ANDROID_12_13_ADB_COMMAND
            }

        internal fun detectPhantomOrResourceKill(records: List<HistoricalExitRecord>): HistoricalExitRecord? {
            return records.firstOrNull { rec ->
                rec.reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ||
                    (rec.reason == ApplicationExitInfo.REASON_SIGNALED && rec.status == SIGKILL_NUMBER) ||
                    rec.status == SIGKILL_NUMBER
            }
        }

        /**
         * Counts all live descendant PIDs of `rootPid` using `/proc/<pid>/stat` PPID links.
         */
        fun countDescendantProcesses(procRoot: File, rootPid: Int): Int {
            val childrenByParent = HashMap<Int, MutableList<Int>>()
            val entries = procRoot.listFiles() ?: return 0
            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid <= 0) continue
                val statFile = File(entry, "stat")
                val statText = try {
                    statFile.readText()
                } catch (_: Exception) {
                    continue
                }
                val ppid = parsePpidFromProcStat(statText) ?: continue
                childrenByParent.getOrPut(ppid) { ArrayList(4) }.add(pid)
            }

            // Also inspect /proc/<pid>/task/*/children when accessible for faster direct link capture.
            val visited = HashSet<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(rootPid)
            visited.add(rootPid)

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val direct = childrenByParent[current].orEmpty()
                for (child in direct) {
                    if (visited.add(child)) {
                        queue.add(child)
                    }
                }
                val taskDir = File(procRoot, "$current/task")
                val threads = taskDir.listFiles() ?: continue
                for (tidDir in threads) {
                    val childrenFile = File(tidDir, "children")
                    val text = try {
                        childrenFile.readText()
                    } catch (_: Exception) {
                        continue
                    }
                    for (token in text.trim().split(Regex("\\s+"))) {
                        val childPid = token.toIntOrNull() ?: continue
                        if (childPid > 0 && visited.add(childPid)) {
                            queue.add(childPid)
                        }
                    }
                }
            }
            return (visited.size - 1).coerceAtLeast(0)
        }

        internal fun parsePpidFromProcStat(statText: String): Int? {
            val closeParen = statText.lastIndexOf(')')
            if (closeParen < 0 || closeParen + 1 >= statText.length) return null
            val fields = statText.substring(closeParen + 1).trim().split(Regex("\\s+"))
            // fields[0] = state, fields[1] = ppid
            return fields.getOrNull(1)?.toIntOrNull()
        }

        internal fun directorySizeBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            if (dir.isFile) return dir.length()
            var total = 0L
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    total += f.length()
                }
            }
            return total
        }

        private fun computeFreeDiskMiB(filesDir: File): Int {
            return try {
                val target = if (filesDir.exists()) filesDir else filesDir.parentFile ?: filesDir
                val stat = Os.statvfs(target.absolutePath)
                ((stat.f_bavail * stat.f_frsize) / (1024L * 1024L)).toInt().coerceAtLeast(0)
            } catch (_: Exception) {
                0
            }
        }
    }
}
