package dev.gfire.termvr.core.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.gfire.termvr.core.model.LiveBlockIoSample
import dev.gfire.termvr.core.model.LiveKernelLogEntry
import dev.gfire.termvr.core.model.LiveMountInfo
import dev.gfire.termvr.core.model.LivePortInfo
import dev.gfire.termvr.core.model.LiveProcessInfo
import dev.gfire.termvr.core.model.LiveResourceLimits
import dev.gfire.termvr.core.model.LiveResourceUpdate
import dev.gfire.termvr.core.model.LiveStatsSnapshot
import dev.gfire.termvr.core.model.SandboxInspectSnapshot
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Structured error returned over the `gvx-ctrl/2` control protocol.
 */
class GvxControlException(
    val code: String,
    override val message: String,
    val errno: Int = 0,
    val retryable: Boolean = false,
) : IOException(if (message.isNotBlank()) "$code: $message" else code)

/**
 * Bidirectional stream connection for `gvx-ctrl/2` framing and optional Unix `SCM_RIGHTS` FD passing.
 */
interface ControlSocketConnection : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream
    fun setReadTimeoutMs(timeoutMs: Int)
    fun sendFileDescriptorsWithSentinel(fds: List<FileDescriptor>)
}

/**
 * Opens a stream connection to a `gvx` control socket path.
 */
fun interface ControlStreamTransport {
    fun open(socketFile: File, connectTimeoutMs: Int, readTimeoutMs: Int): ControlSocketConnection
}

/**
 * Android `LocalSocket` transport supporting timeouts and `SCM_RIGHTS` descriptor passing.
 */
object AndroidLocalControlStreamTransport : ControlStreamTransport {
    private const val MAX_UNIX_SOCKET_PATH_BYTES = 107

    override fun open(
        socketFile: File,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ControlSocketConnection {
        val socketPath = socketFile.absolutePath
        if (socketPath.toByteArray(StandardCharsets.UTF_8).size > MAX_UNIX_SOCKET_PATH_BYTES) {
            throw IOException("Control socket path exceeds Unix domain limit: $socketPath")
        }
        val socket = LocalSocket()
        val connectExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "termvr-ctrl2-connect").apply { isDaemon = true }
        }
        try {
            val connectTask = connectExecutor.submit(
                Callable {
                    socket.connect(
                        LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM),
                    )
                },
            )
            try {
                connectTask.get(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Control socket connect interrupted", e)
            } catch (e: ExecutionException) {
                val cause = e.cause
                throw if (cause is IOException) cause else IOException(cause?.message ?: e.message, cause)
            }
            socket.soTimeout = readTimeoutMs
            return object : ControlSocketConnection {
                override val inputStream: InputStream get() = socket.inputStream
                override val outputStream: OutputStream get() = socket.outputStream
                override fun setReadTimeoutMs(timeoutMs: Int) {
                    socket.soTimeout = timeoutMs
                }

                override fun sendFileDescriptorsWithSentinel(fds: List<FileDescriptor>) {
                    if (fds.isEmpty()) return
                    socket.setFileDescriptorsForSend(fds.toTypedArray())
                    try {
                        socket.outputStream.write(0)
                        socket.outputStream.flush()
                    } finally {
                        socket.setFileDescriptorsForSend(null)
                    }
                }

                override fun close() {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        } finally {
            connectExecutor.shutdownNow()
        }
    }
}

/**
 * Pure-Kotlin `gvx-ctrl/2` framed binary client for runtime sandbox control over owner-only Unix sockets.
 */
class GvxControlClient(
    private val transport: ControlStreamTransport = AndroidLocalControlStreamTransport,
    private val clientName: String = "termvr",
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val defaultReadTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        coerceInputValues = true
    }

    fun ping(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): Boolean =
        withSession(socketFile, readTimeoutMs) { session ->
            val resp = session.call(METHOD_PING, null)
            resp.ok
        }

    fun inspect(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): SandboxInspectSnapshot =
        withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WireInspectResponse>(METHOD_INSPECT, null)
            wire.toModel()
        }

    fun stats(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): LiveStatsSnapshot =
        withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WireStatsSample>(
                METHOD_STATS,
                json.encodeToJsonElement(WireStatsRequest(stream = false)),
            )
            wire.toModel()
        }

    fun pause(
        socketFile: File,
        quiesceNet: Boolean = true,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ) {
        withSession(socketFile, readTimeoutMs) { session ->
            session.call(
                METHOD_PAUSE,
                json.encodeToJsonElement(WirePauseRequest(quiesceNet = quiesceNet)),
            )
        }
    }

    fun unpause(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs) {
        withSession(socketFile, readTimeoutMs) { session ->
            session.call(METHOD_UNPAUSE, null)
        }
    }

    fun stop(
        socketFile: File,
        signal: String = "SIGTERM",
        timeoutMs: Long = 3_000L,
        readTimeoutMs: Int = (timeoutMs + 3_000L).toInt().coerceAtLeast(defaultReadTimeoutMs),
    ): Boolean = withSession(socketFile, readTimeoutMs) { session ->
        val wire = session.callDecoded<WireStopResponse>(
            METHOD_STOP,
            json.encodeToJsonElement(WireStopRequest(signal = signal, timeoutMs = timeoutMs)),
        )
        wire.killed
    }

    fun checkpoint(
        socketFile: File,
        dir: String,
        leaveRunning: Boolean = false,
        force: Boolean = false,
        readTimeoutMs: Int = CheckpointController.CHECKPOINT_READ_TIMEOUT_MS,
    ) {
        withSession(socketFile, readTimeoutMs) { session ->
            session.call(
                METHOD_CHECKPOINT,
                json.encodeToJsonElement(
                    WireCheckpointRequest(
                        dir = dir,
                        leaveRunning = leaveRunning,
                        force = force,
                    ),
                ),
            )
        }
    }

    fun updateResources(
        socketFile: File,
        update: LiveResourceUpdate,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): LiveResourceLimits {
        require(update.hasChanges) { "LiveResourceUpdate must specify at least one change" }
        val memBytes = update.memoryMiB?.let { mib ->
            if (mib <= 0) 0L else mib.toLong() * 1024L * 1024L
        }
        val cpuMillis = update.cpuCores?.let { cores ->
            if (cores <= 0.0) 0L else (cores * 1000.0).roundToLong().coerceAtLeast(1L)
        }
        val pidsLimit = update.pidsLimit?.let { limit ->
            if (limit <= 0) 0L else limit.toLong()
        }
        val req = WireResourceUpdateRequest(
            memoryBytes = memBytes,
            forceMemory = if (update.forceMemory) true else null,
            cpuMillis = cpuMillis,
            pidsLimit = pidsLimit,
            isolateLan = update.isolateLan,
            netTxBps = update.netTxBps?.coerceAtLeast(0L),
            netRxBps = update.netRxBps?.coerceAtLeast(0L),
        )
        return withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WireResourceSnapshot>(
                METHOD_RESOURCE_UPDATE,
                json.encodeToJsonElement(req),
            )
            wire.toModel()
        }
    }

    fun listPorts(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): List<LivePortInfo> =
        withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WirePortListResponse>(METHOD_PORT_LIST, null)
            wire.ports.map { it.toModel() }
        }

    fun addPorts(
        socketFile: File,
        specs: List<String>,
        replace: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): List<LivePortInfo> {
        require(specs.isNotEmpty()) { "At least one port spec is required" }
        return withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WirePortAddResponse>(
                METHOD_PORT_ADD,
                json.encodeToJsonElement(
                    WirePortAddRequest(
                        specs = specs,
                        replace = if (replace) true else null,
                    ),
                ),
            )
            wire.ports.map { it.toModel() }
        }
    }

    fun removePorts(
        socketFile: File,
        specs: List<String> = emptyList(),
        allDynamic: Boolean = false,
        force: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): List<LivePortInfo> {
        require(specs.isNotEmpty() || allDynamic) { "Specify port specs or allDynamic=true" }
        return withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WirePortRemoveResponse>(
                METHOD_PORT_REMOVE,
                json.encodeToJsonElement(
                    WirePortRemoveRequest(
                        specs = specs.takeIf { it.isNotEmpty() },
                        allDynamic = if (allDynamic) true else null,
                        force = if (force) true else null,
                    ),
                ),
            )
            wire.removed.map { it.toModel() }
        }
    }

    fun listMounts(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): List<LiveMountInfo> =
        withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WireMountListResponse>(METHOD_MOUNT_LIST, null)
            wire.mounts.map { it.toModel() }
        }

    fun addTmpfsMount(
        socketFile: File,
        target: String,
        sizeBytes: Long = 0L,
        readOnly: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): LiveMountInfo = withSession(socketFile, readTimeoutMs) { session ->
        val wire = session.callDecoded<WireMountInfo>(
            METHOD_MOUNT_ADD,
            json.encodeToJsonElement(
                WireMountAddRequest(
                    type = "tmpfs",
                    target = target,
                    readOnly = if (readOnly) true else null,
                    tmpfsSizeBytes = sizeBytes.takeIf { it > 0L },
                ),
            ),
        )
        wire.toModel()
    }

    fun attachVolumeMount(
        socketFile: File,
        volumeName: String,
        volumeFile: File,
        target: String,
        readOnly: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): LiveMountInfo {
        if (!volumeFile.isFile) {
            throw IOException("Volume image file not found: ${volumeFile.absolutePath}")
        }
        val mode = if (readOnly) "r" else "rw"
        return withSession(socketFile, readTimeoutMs) { session ->
            if (FEATURE_SCM_RIGHTS !in session.features || CAP_MOUNT_VOLUME_ATTACH !in session.capabilities) {
                throw GvxControlException(
                    CODE_LOCKDOWN_DENIED,
                    "Live ext4lite volume attachment is not advertised by this sandbox (requires --allow-volume-attach and SCM_RIGHTS).",
                )
            }
            RandomAccessFile(volumeFile, mode).use { raf ->
                val wire = session.callDecoded<WireMountInfo>(
                    METHOD_MOUNT_ADD,
                    json.encodeToJsonElement(
                        WireMountAddRequest(
                            type = "volume",
                            source = volumeName,
                            target = target,
                            readOnly = if (readOnly) true else null,
                        ),
                    ),
                    fds = listOf(raf.fd),
                )
                wire.toModel()
            }
        }
    }

    fun remount(
        socketFile: File,
        target: String,
        readOnly: Boolean,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): Boolean = withSession(socketFile, readTimeoutMs) { session ->
        val wire = session.callDecoded<WireMountRemountResponse>(
            METHOD_MOUNT_REMOUNT,
            json.encodeToJsonElement(WireMountRemountRequest(target = target, readOnly = readOnly)),
        )
        wire.readOnly
    }

    fun removeMount(
        socketFile: File,
        target: String,
        detach: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ) {
        withSession(socketFile, readTimeoutMs) { session ->
            session.call(
                METHOD_MOUNT_REMOVE,
                json.encodeToJsonElement(
                    WireMountRemoveRequest(
                        target = target,
                        detach = if (detach) true else null,
                    ),
                ),
            )
        }
    }

    fun listProcesses(socketFile: File, readTimeoutMs: Int = defaultReadTimeoutMs): List<LiveProcessInfo> =
        withSession(socketFile, readTimeoutMs) { session ->
            val wire = session.callDecoded<WireProcListResponse>(METHOD_PROC_LIST, null)
            wire.processes.map { it.toModel() }
        }

    fun signalProcess(
        socketFile: File,
        pid: Int,
        signal: String,
        all: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ) {
        require(all || pid > 0) { "Specify pid > 0 or all = true" }
        withSession(socketFile, readTimeoutMs) { session ->
            session.call(
                METHOD_PROC_SIGNAL,
                json.encodeToJsonElement(
                    WireProcSignalRequest(
                        signal = signal,
                        pid = pid.takeIf { it > 0 && !all },
                        all = if (all) true else null,
                    ),
                ),
            )
        }
    }

    fun kernelLog(
        socketFile: File,
        tail: Int = 200,
        clear: Boolean = false,
        readTimeoutMs: Int = defaultReadTimeoutMs,
    ): List<LiveKernelLogEntry> = withSession(socketFile, readTimeoutMs) { session ->
        val wire = session.callDecoded<WireKernelLogResponse>(
            METHOD_KERNEL_LOG,
            json.encodeToJsonElement(
                WireKernelLogRequest(
                    tail = tail.takeIf { it > 0 },
                    clear = if (clear) true else null,
                ),
            ),
        )
        wire.records.map { it.toModel() }
    }

    internal fun <T> withSession(
        socketFile: File,
        readTimeoutMs: Int,
        block: (ControlSession) -> T,
    ): T {
        val conn = transport.open(socketFile, connectTimeoutMs, readTimeoutMs)
        return conn.use { activeConn ->
            val session = handshake(activeConn)
            block(session)
        }
    }

    private fun handshake(conn: ControlSocketConnection): ControlSession {
        val out = conn.outputStream
        out.write(PREFACE_BYTES)
        val helloBytes = json.encodeToString(
            WireHelloRequest(
                version = PROTOCOL_VERSION,
                client = clientName,
                features = listOf("scm_rights"),
            ),
        ).toByteArray(StandardCharsets.UTF_8)
        writeFrame(
            out = out,
            type = FRAME_HELLO,
            flags = 0,
            channel = 0,
            streamId = 0,
            payload = helloBytes,
            maxPayload = HANDSHAKE_MAX_FRAME_BYTES,
        )
        out.flush()

        val input = DataInputStream(conn.inputStream)
        val replyFrame = readFrame(input, HANDSHAKE_MAX_FRAME_BYTES)
        if (replyFrame.type != FRAME_HELLO || replyFrame.streamId != 0) {
            throw IOException("Unexpected control HELLO frame type=0x${replyFrame.type.toString(16)}")
        }
        val helloResp = json.decodeFromString<WireHelloResponse>(
            replyFrame.payload.toString(StandardCharsets.UTF_8),
        )
        if (!helloResp.ok) {
            val err = helloResp.error
            if (err != null) {
                throw GvxControlException(err.code, err.message, err.errno, err.retryable)
            }
            throw GvxControlException(CODE_UNAUTHORIZED, "Control handshake rejected")
        }
        val maxFrame = helloResp.maxFrameBytes
            .takeIf { it in 1..ABSOLUTE_MAX_FRAME_BYTES }
            ?: DEFAULT_MAX_FRAME_BYTES
        return ControlSession(
            conn = conn,
            input = input,
            json = json,
            maxFrameBytes = maxFrame,
            capabilities = helloResp.capabilities.toSet(),
            features = helloResp.features.toSet(),
        )
    }

    internal inner class ControlSession(
        private val conn: ControlSocketConnection,
        private val input: DataInputStream,
        val json: Json,
        private val maxFrameBytes: Int,
        val capabilities: Set<String>,
        val features: Set<String>,
    ) {
        private var nextStreamId: Int = 1

        fun call(
            method: String,
            params: JsonElement?,
            fds: List<FileDescriptor> = emptyList(),
        ): WireResponseEnvelope {
            val streamId = nextStreamId
            nextStreamId += 2

            val envelopeBytes = json.encodeToString(
                WireRequestEnvelope(method = method, params = params),
            ).toByteArray(StandardCharsets.UTF_8)
            val flags = if (fds.isNotEmpty()) FLAG_HAS_FDS else 0
            writeFrame(
                out = conn.outputStream,
                type = FRAME_REQUEST,
                flags = flags,
                channel = 0,
                streamId = streamId,
                payload = envelopeBytes,
                maxPayload = maxFrameBytes,
            )
            conn.outputStream.flush()
            if (fds.isNotEmpty()) {
                conn.sendFileDescriptorsWithSentinel(fds)
            }

            var goAwayError: GvxControlException? = null
            while (true) {
                val frame = try {
                    readFrame(input, maxFrameBytes)
                } catch (eof: IOException) {
                    goAwayError?.let { throw it }
                    throw eof
                }
                when (frame.type) {
                    FRAME_PING -> {
                        writeFrame(
                            out = conn.outputStream,
                            type = FRAME_PONG,
                            flags = 0,
                            channel = 0,
                            streamId = 0,
                            payload = frame.payload,
                            maxPayload = maxFrameBytes,
                        )
                        conn.outputStream.flush()
                    }
                    FRAME_PONG, FRAME_WINDOW_UPDATE -> Unit
                    FRAME_GO_AWAY -> {
                        // Record GOAWAY but keep draining so in-flight checkpoint/stop replies arrive cleanly.
                        goAwayError = decodeFrameControlError(
                            payload = frame.payload,
                            fallbackCode = CODE_SANDBOX_EXITING,
                            fallbackMessage = "sandbox exiting",
                        )
                    }
                    FRAME_RESPONSE -> {
                        if (frame.streamId != streamId) continue
                        val resp = json.decodeFromString<WireResponseEnvelope>(
                            frame.payload.toString(StandardCharsets.UTF_8),
                        )
                        if (!resp.ok) {
                            val err = resp.error
                            if (err != null) {
                                throw GvxControlException(err.code, err.message, err.errno, err.retryable)
                            }
                            throw GvxControlException(CODE_INTERNAL, "Control RPC $method failed")
                        }
                        return resp
                    }
                    FRAME_CANCEL -> {
                        if (frame.streamId == streamId) {
                            throw decodeFrameControlError(
                                payload = frame.payload,
                                fallbackCode = CODE_INTERNAL,
                                fallbackMessage = "Control stream canceled for $method",
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        private fun decodeFrameControlError(
            payload: ByteArray,
            fallbackCode: String,
            fallbackMessage: String,
        ): GvxControlException {
            val raw = payload.toString(StandardCharsets.UTF_8).trim()
            if (raw.startsWith("{")) {
                val parsed = runCatching { json.decodeFromString<WireProtoError>(raw) }.getOrNull()
                if (parsed != null && (parsed.code.isNotBlank() || parsed.message.isNotBlank())) {
                    return GvxControlException(
                        code = parsed.code.ifBlank { fallbackCode },
                        message = parsed.message.ifBlank { fallbackMessage },
                        errno = parsed.errno,
                        retryable = parsed.retryable,
                    )
                }
            }
            return GvxControlException(fallbackCode, raw.ifBlank { fallbackMessage })
        }

        inline fun <reified T> callDecoded(
            method: String,
            params: JsonElement?,
            fds: List<FileDescriptor> = emptyList(),
        ): T {
            val resp = call(method, params, fds)
            val result = resp.result
                ?: throw IOException("Control RPC $method returned empty result")
            return json.decodeFromJsonElement<T>(result)
        }
    }

    internal data class WireFrame(
        val type: Int,
        val flags: Int,
        val channel: Int,
        val streamId: Int,
        val payload: ByteArray,
    )

    companion object {
        const val PROTOCOL_VERSION: Int = 2
        const val FRAME_HEADER_SIZE: Int = 12
        const val HANDSHAKE_MAX_FRAME_BYTES: Int = 64 * 1024
        const val DEFAULT_MAX_FRAME_BYTES: Int = 256 * 1024
        const val ABSOLUTE_MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 2_000
        const val DEFAULT_READ_TIMEOUT_MS: Int = 10_000

        const val FRAME_HELLO: Int = 0x01
        const val FRAME_REQUEST: Int = 0x02
        const val FRAME_RESPONSE: Int = 0x03
        const val FRAME_DATA: Int = 0x04
        const val FRAME_EVENT: Int = 0x05
        const val FRAME_WINDOW_UPDATE: Int = 0x06
        const val FRAME_CANCEL: Int = 0x07
        const val FRAME_PING: Int = 0x08
        const val FRAME_PONG: Int = 0x09
        const val FRAME_GO_AWAY: Int = 0x0A

        const val FLAG_END_STREAM: Int = 1 shl 0
        const val FLAG_COMPRESSED_ZSTD: Int = 1 shl 1
        const val FLAG_HAS_FDS: Int = 1 shl 2

        const val METHOD_PING: String = "system.ping"
        const val METHOD_INSPECT: String = "sandbox.inspect"
        const val METHOD_STATS: String = "sandbox.stats"
        const val METHOD_PAUSE: String = "lifecycle.pause"
        const val METHOD_UNPAUSE: String = "lifecycle.unpause"
        const val METHOD_STOP: String = "lifecycle.stop"
        const val METHOD_CHECKPOINT: String = "lifecycle.checkpoint"
        const val METHOD_RESOURCE_UPDATE: String = "resource.update"
        const val METHOD_PORT_LIST: String = "port.list"
        const val METHOD_PORT_ADD: String = "port.add"
        const val METHOD_PORT_REMOVE: String = "port.remove"
        const val METHOD_MOUNT_LIST: String = "mount.list"
        const val METHOD_MOUNT_ADD: String = "mount.add"
        const val METHOD_MOUNT_REMOUNT: String = "mount.remount"
        const val METHOD_MOUNT_REMOVE: String = "mount.remove"
        const val METHOD_PROC_LIST: String = "proc.list"
        const val METHOD_PROC_SIGNAL: String = "proc.signal"
        const val METHOD_KERNEL_LOG: String = "kernel.log"
        const val METHOD_EVENTS: String = "sandbox.events"
        const val METHOD_NET_POLICY_GET: String = "net.policy_get"
        const val METHOD_NET_POLICY_SET: String = "net.policy_set"
        const val METHOD_UNIX_FORWARD: String = "net.unix_forward"

        const val FEATURE_SCM_RIGHTS: String = "scm_rights"
        const val FEATURE_NET_QOS: String = "net_qos"
        const val CAP_MOUNT_VOLUME_ATTACH: String = "mount.volume_attach"
        const val CAP_MOUNT_BIND_ATTACH: String = "mount.bind_attach"
        const val CAP_UNIX_FORWARD: String = "net.unix_forward"

        const val CODE_UNAUTHORIZED: String = "UNAUTHORIZED"
        const val CODE_LOCKDOWN_DENIED: String = "LOCKDOWN_DENIED"
        const val CODE_ALREADY_EXISTS: String = "ALREADY_EXISTS"
        const val CODE_NOT_FOUND: String = "NOT_FOUND"
        const val CODE_ALREADY_PAUSED: String = "ALREADY_PAUSED"
        const val CODE_NOT_PAUSED: String = "NOT_PAUSED"
        const val CODE_BUSY: String = "BUSY"
        const val CODE_LIMIT_EXCEEDED: String = "LIMIT_EXCEEDED"
        const val CODE_SANDBOX_EXITING: String = "SANDBOX_EXITING"
        const val CODE_INTERNAL: String = "INTERNAL"

        private val PREFACE_BYTES: ByteArray = "GVX2".toByteArray(StandardCharsets.US_ASCII)

        internal fun writeFrame(
            out: OutputStream,
            type: Int,
            flags: Int,
            channel: Int,
            streamId: Int,
            payload: ByteArray,
            maxPayload: Int = DEFAULT_MAX_FRAME_BYTES,
        ) {
            if (payload.size > maxPayload) {
                throw IOException("Control frame payload ${payload.size} exceeds max $maxPayload")
            }
            val hdr = ByteBuffer.allocate(FRAME_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
                put(type.toByte())
                put(flags.toByte())
                putShort(channel.toShort())
                putInt(streamId)
                putInt(payload.size)
            }.array()
            out.write(hdr)
            if (payload.isNotEmpty()) {
                out.write(payload)
            }
        }

        internal fun readFrame(input: DataInputStream, maxPayload: Int = DEFAULT_MAX_FRAME_BYTES): WireFrame {
            val hdr = ByteArray(FRAME_HEADER_SIZE)
            try {
                input.readFully(hdr)
            } catch (e: EOFException) {
                throw IOException("Control socket closed unexpectedly", e)
            }
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
            val type = bb.get().toInt() and 0xFF
            val flags = bb.get().toInt() and 0xFF
            val channel = bb.short.toInt() and 0xFFFF
            val streamId = bb.int
            val length = bb.int
            if (flags and FLAG_COMPRESSED_ZSTD != 0) {
                throw IOException("Unexpected zstd-compressed control frame when zstd was not negotiated")
            }
            if (length < 0 || length > maxPayload) {
                throw IOException("Control frame length $length exceeds max $maxPayload")
            }
            val payload = ByteArray(length)
            if (length > 0) {
                try {
                    input.readFully(payload)
                } catch (e: EOFException) {
                    throw IOException("Truncated control frame payload", e)
                }
            }
            return WireFrame(type, flags, channel, streamId, payload)
        }
    }
}

@Serializable
internal data class WireProtoError(
    val code: String = "INTERNAL",
    val message: String = "",
    val errno: Int = 0,
    val retryable: Boolean = false,
)

@Serializable
internal data class WireHelloRequest(
    val version: Int,
    val token: String? = null,
    val client: String? = null,
    val features: List<String>? = null,
)

@Serializable
internal data class WireHelloResponse(
    val ok: Boolean = false,
    val version: Int = 0,
    @SerialName("sandbox_id") val sandboxId: String = "",
    @SerialName("max_frame_bytes") val maxFrameBytes: Int = 0,
    val capabilities: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val error: WireProtoError? = null,
)

@Serializable
internal data class WireRequestEnvelope(
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
internal data class WireResponseEnvelope(
    val ok: Boolean = false,
    val result: JsonElement? = null,
    val error: WireProtoError? = null,
)

@Serializable
private data class WirePauseRequest(
    @SerialName("quiesce_net") val quiesceNet: Boolean,
)

@Serializable
private data class WireStopRequest(
    val signal: String,
    @SerialName("timeout_ms") val timeoutMs: Long,
)

@Serializable
private data class WireStopResponse(
    val signaled: String = "",
    val killed: Boolean = false,
)

@Serializable
private data class WireCheckpointRequest(
    val dir: String,
    @SerialName("leave_running") val leaveRunning: Boolean = false,
    val force: Boolean = false,
)

@Serializable
private data class WireResourceUpdateRequest(
    @SerialName("memory_bytes") val memoryBytes: Long? = null,
    @SerialName("force_memory") val forceMemory: Boolean? = null,
    @SerialName("cpu_millis") val cpuMillis: Long? = null,
    @SerialName("pids_limit") val pidsLimit: Long? = null,
    @SerialName("isolate_lan") val isolateLan: Boolean? = null,
    @SerialName("net_tx_bps") val netTxBps: Long? = null,
    @SerialName("net_rx_bps") val netRxBps: Long? = null,
)

@Serializable
private data class WireResourceSnapshot(
    @SerialName("memory_bytes") val memoryBytes: Long = 0L,
    @SerialName("cpu_millis") val cpuMillis: Long = 0L,
    @SerialName("cpu_quota_usec") val cpuQuotaUsec: Long = 0L,
    @SerialName("cpu_period_usec") val cpuPeriodUsec: Long = 0L,
    @SerialName("guest_cpus") val guestCpus: Int = 0,
    @SerialName("pids_limit") val pidsLimit: Long = 0L,
    @SerialName("isolate_lan") val isolateLan: Boolean = false,
    @SerialName("net_tx_bps") val netTxBps: Long = 0L,
    @SerialName("net_rx_bps") val netRxBps: Long = 0L,
) {
    fun toModel(): LiveResourceLimits = LiveResourceLimits(
        memoryBytes = memoryBytes,
        cpuMillis = cpuMillis,
        cpuQuotaUsec = cpuQuotaUsec,
        cpuPeriodUsec = cpuPeriodUsec,
        guestCpus = guestCpus,
        pidsLimit = pidsLimit,
        isolateLan = isolateLan,
        netTxBps = netTxBps,
        netRxBps = netRxBps,
    )
}

@Serializable
private data class WirePortRuleInfo(
    val proto: String = "tcp",
    @SerialName("host_ip") val hostIp: String = "",
    @SerialName("host_port") val hostPort: Int = 0,
    @SerialName("host_socket") val hostSocket: String = "",
    @SerialName("guest_ip") val guestIp: String = "",
    @SerialName("guest_port") val guestPort: Int = 0,
    val source: String = "static",
) {
    fun toModel(): LivePortInfo = LivePortInfo(
        proto = proto,
        hostIp = hostIp,
        hostPort = hostPort,
        hostSocket = hostSocket,
        guestIp = guestIp,
        guestPort = guestPort,
        source = source,
    )
}

@Serializable
private data class WirePortListResponse(
    val ports: List<WirePortRuleInfo> = emptyList(),
)

@Serializable
private data class WirePortAddRequest(
    val specs: List<String>,
    val replace: Boolean? = null,
)

@Serializable
private data class WirePortAddResponse(
    val ports: List<WirePortRuleInfo> = emptyList(),
)

@Serializable
private data class WirePortRemoveRequest(
    val specs: List<String>? = null,
    @SerialName("all_dynamic") val allDynamic: Boolean? = null,
    val force: Boolean? = null,
)

@Serializable
private data class WirePortRemoveResponse(
    val removed: List<WirePortRuleInfo> = emptyList(),
)

@Serializable
private data class WireMountInfo(
    val target: String = "",
    val type: String = "",
    val source: String = "",
    @SerialName("read_only") val readOnly: Boolean = false,
    val mutable: Boolean = false,
) {
    fun toModel(): LiveMountInfo = LiveMountInfo(
        target = target,
        type = type,
        source = source,
        readOnly = readOnly,
        mutable = mutable,
    )
}

@Serializable
private data class WireMountListResponse(
    val mounts: List<WireMountInfo> = emptyList(),
)

@Serializable
private data class WireMountAddRequest(
    val type: String,
    val source: String? = null,
    val target: String,
    @SerialName("read_only") val readOnly: Boolean? = null,
    @SerialName("tmpfs_size_bytes") val tmpfsSizeBytes: Long? = null,
)

@Serializable
private data class WireMountRemountRequest(
    val target: String,
    @SerialName("read_only") val readOnly: Boolean,
)

@Serializable
private data class WireMountRemountResponse(
    val target: String = "",
    @SerialName("read_only") val readOnly: Boolean = false,
)

@Serializable
private data class WireMountRemoveRequest(
    val target: String,
    val detach: Boolean? = null,
)

@Serializable
private data class WireProcessInfo(
    val pid: Int = 0,
    val ppid: Int = 0,
    val pgid: Int = 0,
    val sid: Int = 0,
    val uid: Int = 0,
    val gid: Int = 0,
    val state: String = "",
    val threads: Int = 1,
    @SerialName("cpu_ns") val cpuNanos: Long = 0L,
    @SerialName("rss_bytes") val rssBytes: Long = 0L,
    val comm: String = "",
    val cmdline: List<String> = emptyList(),
) {
    fun toModel(): LiveProcessInfo = LiveProcessInfo(
        pid = pid,
        ppid = ppid,
        pgid = pgid,
        sid = sid,
        uid = uid,
        gid = gid,
        state = state,
        threads = threads,
        cpuNanos = cpuNanos,
        rssBytes = rssBytes,
        comm = comm,
        cmdline = cmdline,
    )
}

@Serializable
private data class WireProcListResponse(
    val processes: List<WireProcessInfo> = emptyList(),
)

@Serializable
private data class WireProcSignalRequest(
    val signal: String,
    val pid: Int? = null,
    val all: Boolean? = null,
)

@Serializable
private data class WireKernelLogRequest(
    val follow: Boolean? = null,
    val tail: Int? = null,
    val clear: Boolean? = null,
)

@Serializable
private data class WireKernelLogRecord(
    val seq: Long = 0L,
    val level: Int = 6,
    val usec: Long = 0L,
    val text: String = "",
) {
    fun toModel(): LiveKernelLogEntry = LiveKernelLogEntry(
        seq = seq,
        level = level,
        usec = usec,
        text = text,
    )
}

@Serializable
private data class WireKernelLogResponse(
    val records: List<WireKernelLogRecord> = emptyList(),
)

@Serializable
private data class WireStatsRequest(
    val stream: Boolean = false,
)

@Serializable
private data class WireCpuStatsSample(
    @SerialName("user_ns") val userNanos: Long = 0L,
    @SerialName("sys_ns") val sysNanos: Long = 0L,
    val throttled: Long = 0L,
    @SerialName("throttled_ns") val throttledNanos: Long = 0L,
)

@Serializable
private data class WireMemoryStatsSample(
    @SerialName("total_bytes") val totalBytes: Long = 0L,
    @SerialName("anon_bytes") val anonBytes: Long = 0L,
    @SerialName("file_bytes") val fileBytes: Long = 0L,
    @SerialName("tmpfs_bytes") val tmpfsBytes: Long = 0L,
    @SerialName("limit_bytes") val limitBytes: Long = 0L,
)

@Serializable
private data class WireNetStatsSample(
    @SerialName("interface") val iface: String = "",
    @SerialName("rx_bytes") val rxBytes: Long = 0L,
    @SerialName("tx_bytes") val txBytes: Long = 0L,
)

@Serializable
private data class WireBlockStatsSample(
    val disk: String = "",
    @SerialName("read_ops") val readOps: Long = 0L,
    @SerialName("write_ops") val writeOps: Long = 0L,
    @SerialName("read_sectors") val readSectors: Long = 0L,
    @SerialName("write_sectors") val writeSectors: Long = 0L,
) {
    fun toModel(): LiveBlockIoSample = LiveBlockIoSample(
        disk = disk,
        readOps = readOps,
        writeOps = writeOps,
        readSectors = readSectors,
        writeSectors = writeSectors,
    )
}

@Serializable
private data class WireStatsSample(
    val cpu: WireCpuStatsSample = WireCpuStatsSample(),
    val memory: WireMemoryStatsSample = WireMemoryStatsSample(),
    val network: List<WireNetStatsSample>? = null,
    @SerialName("block_io") val blockIo: List<WireBlockStatsSample>? = null,
    val pids: Int = 0,
    @SerialName("pids_limit") val pidsLimit: Long = 0L,
) {
    fun toModel(): LiveStatsSnapshot = LiveStatsSnapshot(
        cpuUserNanos = cpu.userNanos,
        cpuSysNanos = cpu.sysNanos,
        cpuThrottledPeriods = cpu.throttled,
        cpuThrottledNanos = cpu.throttledNanos,
        memoryTotalBytes = memory.totalBytes,
        memoryAnonBytes = memory.anonBytes,
        memoryFileBytes = memory.fileBytes,
        memoryTmpfsBytes = memory.tmpfsBytes,
        memoryLimitBytes = memory.limitBytes,
        netRxBytes = network.orEmpty().sumOf { it.rxBytes },
        netTxBytes = network.orEmpty().sumOf { it.txBytes },
        blockIo = blockIo.orEmpty().map { it.toModel() },
        pids = pids,
        pidsLimit = pidsLimit,
    )
}

@Serializable
private data class WireInspectResponse(
    val id: String = "",
    val name: String = "",
    val state: String = "",
    val platform: String = "",
    val architecture: String = "",
    @SerialName("syscall_isolation") val syscallIsolation: String = "",
    @SerialName("lockdown_applied") val lockdownApplied: Boolean = false,
    @SerialName("network_mode") val networkMode: String = "",
    @SerialName("eth0_ip") val eth0Ip: String = "",
    @SerialName("eth0_mtu") val eth0Mtu: Int = 0,
    val resources: WireResourceSnapshot = WireResourceSnapshot(),
    @SerialName("memory_used_bytes") val memoryUsedBytes: Long = 0L,
    val ports: List<WirePortRuleInfo> = emptyList(),
    val mounts: List<WireMountInfo> = emptyList(),
    @SerialName("active_exec_sessions") val activeExecSessions: Int = 0,
    @SerialName("pid_count") val pidCount: Int = 0,
    val capabilities: List<String> = emptyList(),
    @SerialName("uptime_ns") val uptimeNanos: Long = 0L,
) {
    fun toModel(): SandboxInspectSnapshot = SandboxInspectSnapshot(
        id = id,
        name = name,
        state = state,
        platform = platform,
        architecture = architecture,
        syscallIsolation = syscallIsolation,
        lockdownApplied = lockdownApplied,
        networkMode = networkMode,
        eth0Ip = eth0Ip,
        eth0Mtu = eth0Mtu,
        resources = resources.toModel(),
        memoryUsedBytes = memoryUsedBytes,
        ports = ports.map { it.toModel() },
        mounts = mounts.map { it.toModel() },
        activeExecSessions = activeExecSessions,
        pidCount = pidCount,
        capabilities = capabilities,
        uptimeNanos = uptimeNanos,
    )
}
