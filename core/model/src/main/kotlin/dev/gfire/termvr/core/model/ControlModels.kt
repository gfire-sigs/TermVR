package dev.gfire.termvr.core.model

import kotlinx.serialization.Serializable

/**
 * Describes one guest Linux thread group returned by `gvx-ctrl/2` `proc.list`.
 */
@Serializable
data class LiveProcessInfo(
    val pid: Int,
    val ppid: Int = 0,
    val pgid: Int = 0,
    val sid: Int = 0,
    val uid: Int = 0,
    val gid: Int = 0,
    val state: String = "",
    val threads: Int = 1,
    val cpuNanos: Long = 0L,
    val rssBytes: Long = 0L,
    val comm: String = "",
    val cmdline: List<String> = emptyList(),
) {
    val displayCommand: String
        get() = cmdline.filter { it.isNotBlank() }.joinToString(" ").ifBlank { comm }
}

/**
 * Describes one buffered guest kernel log record returned by `gvx-ctrl/2` `kernel.log`.
 */
@Serializable
data class LiveKernelLogEntry(
    val seq: Long = 0L,
    val level: Int = 6,
    val usec: Long = 0L,
    val text: String = "",
)

/**
 * Describes one active mount entry returned by `gvx-ctrl/2` `mount.list` or `mount.add`.
 */
@Serializable
data class LiveMountInfo(
    val target: String,
    val type: String,
    val source: String = "",
    val readOnly: Boolean = false,
    val mutable: Boolean = false,
)

/**
 * Describes one active host-to-guest port forward rule returned by `gvx-ctrl/2` `port.list`.
 */
@Serializable
data class LivePortInfo(
    val proto: String,
    val hostIp: String = "",
    val hostPort: Int = 0,
    val hostSocket: String = "",
    val guestIp: String = "",
    val guestPort: Int = 0,
    val source: String = "static",
)

/**
 * Captures active sandbox resource limits reported by `sandbox.inspect` or `resource.update`.
 */
@Serializable
data class LiveResourceLimits(
    val memoryBytes: Long = 0L,
    val cpuMillis: Long = 0L,
    val cpuQuotaUsec: Long = 0L,
    val cpuPeriodUsec: Long = 0L,
    val guestCpus: Int = 0,
    val pidsLimit: Long = 0L,
    val isolateLan: Boolean = false,
    val netTxBps: Long = 0L,
    val netRxBps: Long = 0L,
)

/**
 * Describes per-device block I/O counters returned by `gvx-ctrl/2` `sandbox.stats` (`block_io`).
 */
@Serializable
data class LiveBlockIoSample(
    val disk: String = "",
    val readOps: Long = 0L,
    val writeOps: Long = 0L,
    val readSectors: Long = 0L,
    val writeSectors: Long = 0L,
) {
    val readBytes: Long
        get() = readSectors.coerceAtLeast(0L) * 512L

    val writeBytes: Long
        get() = writeSectors.coerceAtLeast(0L) * 512L
}

/**
 * Captures a point-in-time `sandbox.stats` resource sample from `gvx-ctrl/2`.
 */
@Serializable
data class LiveStatsSnapshot(
    val cpuUserNanos: Long = 0L,
    val cpuSysNanos: Long = 0L,
    val cpuThrottledPeriods: Long = 0L,
    val cpuThrottledNanos: Long = 0L,
    val memoryTotalBytes: Long = 0L,
    val memoryAnonBytes: Long = 0L,
    val memoryFileBytes: Long = 0L,
    val memoryTmpfsBytes: Long = 0L,
    val memoryLimitBytes: Long = 0L,
    val netRxBytes: Long = 0L,
    val netTxBytes: Long = 0L,
    val blockIo: List<LiveBlockIoSample> = emptyList(),
    val pids: Int = 0,
    val pidsLimit: Long = 0L,
) {
    val totalCpuNanos: Long
        get() = (cpuUserNanos + cpuSysNanos).coerceAtLeast(0L)

    val blockReadBytes: Long
        get() = blockIo.sumOf { it.readBytes }

    val blockWriteBytes: Long
        get() = blockIo.sumOf { it.writeBytes }

    val blockReadOps: Long
        get() = blockIo.sumOf { it.readOps.coerceAtLeast(0L) }

    val blockWriteOps: Long
        get() = blockIo.sumOf { it.writeOps.coerceAtLeast(0L) }
}

/**
 * Captures the full live inspection snapshot returned by `gvx-ctrl/2` `sandbox.inspect`.
 */
@Serializable
data class SandboxInspectSnapshot(
    val id: String = "",
    val name: String = "",
    val state: String = "",
    val platform: String = "",
    val architecture: String = "",
    val syscallIsolation: String = "",
    val lockdownApplied: Boolean = false,
    val networkMode: String = "",
    val eth0Ip: String = "",
    val eth0Mtu: Int = 0,
    val resources: LiveResourceLimits = LiveResourceLimits(),
    val memoryUsedBytes: Long = 0L,
    val ports: List<LivePortInfo> = emptyList(),
    val mounts: List<LiveMountInfo> = emptyList(),
    val activeExecSessions: Int = 0,
    val pidCount: Int = 0,
    val capabilities: List<String> = emptyList(),
    val uptimeNanos: Long = 0L,
)

/**
 * Carries partial live resource updates dispatched through `gvx-ctrl/2` `resource.update`.
 */
data class LiveResourceUpdate(
    val memoryMiB: Int? = null,
    val forceMemory: Boolean = false,
    val cpuCores: Double? = null,
    val pidsLimit: Int? = null,
    val isolateLan: Boolean? = null,
    val netTxBps: Long? = null,
    val netRxBps: Long? = null,
) {
    val hasChanges: Boolean
        get() = memoryMiB != null ||
            cpuCores != null ||
            pidsLimit != null ||
            isolateLan != null ||
            netTxBps != null ||
            netRxBps != null
}
