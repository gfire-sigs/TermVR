package dev.gfire.termvr.core.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Checks available bytes on the filesystem containing a target directory.
 */
fun interface FreeSpaceProvider {
    fun availableBytes(path: File): Long
}

/**
 * Android `Os.statvfs` implementation returning unprivileged available filesystem bytes.
 */
object StatvfsFreeSpaceProvider : FreeSpaceProvider {
    override fun availableBytes(path: File): Long {
        val probe = if (path.exists()) path else path.parentFile ?: path
        val stat = Os.statvfs(probe.absolutePath)
        return stat.f_bavail * stat.f_frsize
    }
}

/**
 * Sends control commands over `<filesDir>/ctl/<profileId>.sock`.
 */
fun interface ControlSocketTransport {
    fun sendCommand(
        socketFile: File,
        payloadLine: String,
        connectWriteTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String
}

/**
 * Android `LocalSocket` control client honoring 2000 ms connect/write and 60000 ms read timeouts.
 */
object AndroidLocalSocketTransport : ControlSocketTransport {
    private const val MAX_UNIX_SOCKET_PATH_BYTES = 107

    override fun sendCommand(
        socketFile: File,
        payloadLine: String,
        connectWriteTimeoutMs: Int,
        readTimeoutMs: Int,
    ): String {
        val socketPath = socketFile.absolutePath
        if (socketPath.toByteArray(StandardCharsets.UTF_8).size > MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("Control socket path exceeds Unix domain limit: $socketPath")
        }
        val socket = LocalSocket()
        val connectExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "termvr-ctl-connect").apply { isDaemon = true }
        }
        try {
            // Android LocalSocket leaves its underlying fd null until connect() creates it;
            // calling setSoTimeout before connect() throws IOException("socket not created").
            val connectTask = connectExecutor.submit(
                Callable {
                    socket.connect(
                        LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM),
                    )
                },
            )
            try {
                connectTask.get(connectWriteTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: e.message, cause)
            }

            socket.soTimeout = connectWriteTimeoutMs
            val out = socket.outputStream
            val lineBytes = if (payloadLine.endsWith("\n")) {
                payloadLine.toByteArray(StandardCharsets.UTF_8)
            } else {
                (payloadLine + "\n").toByteArray(StandardCharsets.UTF_8)
            }
            val writeTask = connectExecutor.submit(
                Callable {
                    out.write(lineBytes)
                    out.flush()
                },
            )
            try {
                writeTask.get(connectWriteTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: e.message, cause)
            }

            socket.soTimeout = readTimeoutMs
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            return reader.readLine() ?: throw IOException("Control socket closed without response")
        } finally {
            connectExecutor.shutdownNow()
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }
}

@Serializable
data class CheckpointSlotMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeSlot: String,
    val createdAtEpochMillis: Long,
    val gvxVersion: String,
    val superseded: Boolean = false,
    val supersededReason: String? = null,
    val resolvedPorts: List<PortForwardRule> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported CheckpointSlotMetadata schemaVersion: $schemaVersion"
        }
        require(activeSlot == SLOT_A || activeSlot == SLOT_B) {
            "CheckpointSlotMetadata activeSlot must be $SLOT_A or $SLOT_B, found '$activeSlot'"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val SLOT_A: String = "slot-a"
        const val SLOT_B: String = "slot-b"
    }
}

@Serializable
private data class ControlCheckpointCommand(
    val dir: String,
    @SerialName("leave_running")
    val leaveRunning: Boolean = false,
    val token: String? = null,
)

@Serializable
private data class ControlCheckpointResponse(
    val ok: Boolean,
    val error: String? = null,
)

data class ActiveCheckpoint(
    val slotDir: File,
    val metadata: CheckpointSlotMetadata,
)

class InsufficientCheckpointSpaceException(
    val requiredBytes: Long,
    val availableBytes: Long,
) : IOException(
    "Insufficient disk space for checkpoint: need > $requiredBytes bytes, available $availableBytes bytes",
)

class CheckpointFailedException(message: String) : IOException(message)

/**
 * Manages atomic dual-slot (`slot-a`/`slot-b`) sandbox checkpoints and cold-boot upgrade fallback.
 */
class CheckpointController(
    private val filesDir: File,
    private val gvxVersion: String = DEFAULT_GVX_VERSION,
    private val freeSpaceProvider: FreeSpaceProvider = StatvfsFreeSpaceProvider,
    private val controlTransport: ControlSocketTransport = AndroidLocalSocketTransport,
    private val controlClient: GvxControlClient? = null,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val lineJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun profileCheckpointRoot(profileId: String): File {
        require(SandboxProfile.isValidProfileId(profileId)) {
            "Invalid profileId for checkpoint: '$profileId'"
        }
        return File(filesDir, "checkpoints/$profileId")
    }

    fun activeSlotFile(profileId: String): File =
        File(profileCheckpointRoot(profileId), ACTIVE_SLOT_FILE)

    fun readMetadata(profileId: String): CheckpointSlotMetadata? {
        val metaFile = activeSlotFile(profileId)
        if (!metaFile.isFile) return null
        return try {
            json.decodeFromString<CheckpointSlotMetadata>(metaFile.readText(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the active usable checkpoint slot, or marks an older engine snapshot superseded.
     */
    fun resolveActiveCheckpoint(profileId: String): ActiveCheckpoint? {
        val meta = readMetadata(profileId) ?: return null
        if (meta.superseded) return null
        if (meta.gvxVersion != gvxVersion) {
            markSuperseded(
                profileId = profileId,
                reason = "Checkpoint created by gvx ${meta.gvxVersion} is superseded by $gvxVersion",
            )
            return null
        }
        val slotDir = File(profileCheckpointRoot(profileId), meta.activeSlot)
        if (!slotDir.isDirectory || slotDir.listFiles().isNullOrEmpty()) {
            return null
        }
        return ActiveCheckpoint(slotDir = slotDir, metadata = meta)
    }

    /**
     * Prepares the inactive checkpoint slot directory before launching `rungv run` so `gvx` can
     * pre-open bundle files before `hostlockdown` restricts filesystem creation.
     */
    fun prepareCheckpointSlotDir(profileId: String): File {
        val rootDir = profileCheckpointRoot(profileId)
        rootDir.mkdirs()
        val currentMeta = readMetadata(profileId)
        val nextSlotName = if (currentMeta?.activeSlot == CheckpointSlotMetadata.SLOT_A) {
            CheckpointSlotMetadata.SLOT_B
        } else {
            CheckpointSlotMetadata.SLOT_A
        }
        val slotDir = File(rootDir, nextSlotName)
        if (slotDir.exists()) {
            slotDir.deleteRecursively()
        }
        if (!slotDir.mkdirs()) {
            throw CheckpointFailedException("Failed to create checkpoint slot directory: ${slotDir.absolutePath}")
        }
        return slotDir
    }

    /**
     * Executes atomic dual-slot checkpoint rotation over `<filesDir>/ctl/<profileId>.sock`.
     */
    fun checkpoint(
        profileId: String,
        controlSocketFile: File,
        currentPssBytes: Long,
        resolvedPorts: List<PortForwardRule> = emptyList(),
        preopenedSlotDir: File? = null,
        awaitProcessExit: () -> Int,
    ): CheckpointSlotMetadata {
        val rootDir = profileCheckpointRoot(profileId)
        rootDir.mkdirs()

        val requiredBytes = ceil(currentPssBytes.coerceAtLeast(0L) * FREE_SPACE_SAFETY_MULTIPLIER).toLong()
        val availableBytes = freeSpaceProvider.availableBytes(rootDir)
        if (availableBytes <= requiredBytes) {
            throw InsufficientCheckpointSpaceException(requiredBytes = requiredBytes, availableBytes = availableBytes)
        }

        val currentMeta = readMetadata(profileId)
        val previousSlotName = currentMeta?.activeSlot
        val nextSlotName: String
        val nextSlotDir: File
        if (preopenedSlotDir != null) {
            require(
                preopenedSlotDir.name == CheckpointSlotMetadata.SLOT_A ||
                    preopenedSlotDir.name == CheckpointSlotMetadata.SLOT_B,
            ) {
                "Pre-opened checkpoint dir must be slot-a or slot-b: ${preopenedSlotDir.absolutePath}"
            }
            nextSlotName = preopenedSlotDir.name
            nextSlotDir = preopenedSlotDir
            if (!nextSlotDir.exists() && !nextSlotDir.mkdirs()) {
                throw CheckpointFailedException("Failed to create checkpoint slot directory: ${nextSlotDir.absolutePath}")
            }
        } else {
            nextSlotName = if (previousSlotName == CheckpointSlotMetadata.SLOT_A) {
                CheckpointSlotMetadata.SLOT_B
            } else {
                CheckpointSlotMetadata.SLOT_A
            }
            nextSlotDir = File(rootDir, nextSlotName)
            if (nextSlotDir.exists()) {
                nextSlotDir.deleteRecursively()
            }
            if (!nextSlotDir.mkdirs()) {
                throw CheckpointFailedException("Failed to create checkpoint slot directory: ${nextSlotDir.absolutePath}")
            }
        }

        if (controlClient != null) {
            try {
                controlClient.checkpoint(
                    socketFile = controlSocketFile,
                    dir = nextSlotDir.absolutePath,
                    leaveRunning = false,
                    force = false,
                    readTimeoutMs = CHECKPOINT_READ_TIMEOUT_MS,
                )
            } catch (e: Exception) {
                if (preopenedSlotDir == null) {
                    nextSlotDir.deleteRecursively()
                }
                throw CheckpointFailedException("Checkpoint rejected by gvx: ${e.message}")
            }
        } else {
            val cmdLine = lineJson.encodeToString(
                ControlCheckpointCommand(dir = nextSlotDir.absolutePath, leaveRunning = false),
            )
            val reply = try {
                controlTransport.sendCommand(
                    socketFile = controlSocketFile,
                    payloadLine = cmdLine,
                    connectWriteTimeoutMs = CONNECT_WRITE_TIMEOUT_MS,
                    readTimeoutMs = CHECKPOINT_READ_TIMEOUT_MS,
                ).trim()
            } catch (e: Exception) {
                if (preopenedSlotDir == null) {
                    nextSlotDir.deleteRecursively()
                }
                throw CheckpointFailedException("Control socket checkpoint command failed: ${e.message}")
            }

            val response = try {
                lineJson.decodeFromString<ControlCheckpointResponse>(reply)
            } catch (_: SerializationException) {
                if (preopenedSlotDir == null) {
                    nextSlotDir.deleteRecursively()
                }
                throw CheckpointFailedException("Unexpected checkpoint reply from gvx: '$reply'")
            }
            if (!response.ok) {
                if (preopenedSlotDir == null) {
                    nextSlotDir.deleteRecursively()
                }
                val detail = response.error?.takeIf { it.isNotBlank() } ?: "gvx reported checkpoint failure"
                throw CheckpointFailedException("Checkpoint rejected by gvx: $detail")
            }
        }

        val exitCode = awaitProcessExit()
        if (exitCode != 0) {
            if (preopenedSlotDir == null) {
                nextSlotDir.deleteRecursively()
            }
            throw CheckpointFailedException("gvx exited with non-zero status $exitCode after checkpoint")
        }

        val newMeta = CheckpointSlotMetadata(
            activeSlot = nextSlotName,
            createdAtEpochMillis = clockMillis(),
            gvxVersion = gvxVersion,
            superseded = false,
            supersededReason = null,
            resolvedPorts = resolvedPorts,
        )
        writeMetadataAtomically(profileId, newMeta)

        if (previousSlotName != null && previousSlotName != nextSlotName) {
            File(rootDir, previousSlotName).deleteRecursively()
        }
        return newMeta
    }

    /**
     * Marks the saved snapshot superseded without deleting persistent volumes.
     */
    fun markSuperseded(profileId: String, reason: String) {
        val current = readMetadata(profileId) ?: return
        if (current.superseded && current.supersededReason == reason) return
        val updated = current.copy(superseded = true, supersededReason = reason)
        writeMetadataAtomically(profileId, updated)
    }

    /**
     * Removes saved checkpoint slots and metadata so a locked `/workspace` can be edited offline.
     */
    fun discardCheckpoint(profileId: String) {
        val rootDir = profileCheckpointRoot(profileId)
        if (!rootDir.exists()) return
        File(rootDir, CheckpointSlotMetadata.SLOT_A).deleteRecursively()
        File(rootDir, CheckpointSlotMetadata.SLOT_B).deleteRecursively()
        File(rootDir, ACTIVE_SLOT_TMP_FILE).delete()
        File(rootDir, ACTIVE_SLOT_FILE).delete()
    }

    private fun writeMetadataAtomically(profileId: String, metadata: CheckpointSlotMetadata) {
        val rootDir = profileCheckpointRoot(profileId)
        rootDir.mkdirs()
        val tmpFile = File(rootDir, ACTIVE_SLOT_TMP_FILE)
        val targetFile = File(rootDir, ACTIVE_SLOT_FILE)
        val encoded = json.encodeToString(metadata) + "\n"
        FileOutputStream(tmpFile).use { fos ->
            fos.write(encoded.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(
                tmpFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_GVX_VERSION: String = "v0.25.10002026112300"
        const val CONNECT_WRITE_TIMEOUT_MS: Int = 2_000
        const val CHECKPOINT_READ_TIMEOUT_MS: Int = 60_000
        const val FREE_SPACE_SAFETY_MULTIPLIER: Double = 1.2
        private const val ACTIVE_SLOT_FILE = "active-slot.json"
        private const val ACTIVE_SLOT_TMP_FILE = "active-slot.json.tmp"
    }
}
