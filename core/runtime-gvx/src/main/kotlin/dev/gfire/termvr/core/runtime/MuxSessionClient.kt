package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.ClipboardReadPolicy
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Enumerates the 16 binary multiplexer opcodes defined in Design 0001 Section 15.4.
 */
enum class MuxFrameType(val opcode: Int) {
    MUX_HELLO(0x01),
    OPEN_PTY(0x02),
    OPEN_PTY_ACK(0x03),
    PTY_DATA(0x04),
    PTY_RESIZE(0x05),
    PTY_WINDOW_UPDATE(0x06),
    PTY_SIGNAL(0x07),
    PTY_EXIT(0x08),
    TELEMETRY_REQ(0x09),
    TELEMETRY_RESP(0x0A),
    HOST_RPC_REQ(0x0B),
    HOST_RPC_RESP(0x0C),
    MUX_RESYNC(0x0D),
    MUX_RESYNC_ACK(0x0E),
    PROXY_CONNECT(0x0F),
    PROXY_CONNECT_ACK(0x10),
    ;

    companion object {
        fun fromOpcode(opcode: Int): MuxFrameType? = entries.firstOrNull { it.opcode == opcode }
    }
}

/**
 * Represents one 12-byte big-endian framed message on the `termvr-agent` stdio wire.
 */
data class MuxFrame(
    val type: MuxFrameType,
    val streamId: Long,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        require(streamId in 0L..0xFFFFFFFFL) { "streamId out of uint32 range: $streamId" }
        require(payload.size <= MuxCodec.MAX_PAYLOAD_LENGTH) {
            "Mux payload exceeds ${MuxCodec.MAX_PAYLOAD_LENGTH} bytes: ${payload.size}"
        }
        MuxCodec.validateTypeAndStream(type, streamId)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MuxFrame) return false
        return type == other.type && streamId == other.streamId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + streamId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

class MuxProtocolException(message: String) : IOException(message)

class MuxPtyOpenException(val status: Int, message: String) : IOException(message)

class MuxHostRpcException(val status: Int, message: String) : IOException(message)

@Serializable
data class MuxHelloPayload(
    val protocol: Int = 1,
    val agentVersion: String = "",
    val initDaemon: Boolean = false,
)

@Serializable
data class OpenPtyJsonSpec(
    val argv: List<String> = emptyList(),
    val cwd: String = "/workspace",
    val env: Map<String, String> = emptyMap(),
)

@Serializable
data class GuestTelemetryPayload(
    val cpuNanos: Long = 0L,
    val rssBytes: Long = 0L,
    val procCount: Int = 0,
    val listenPorts: List<Int> = emptyList(),
)

@Serializable
data class ResyncOpenStreamInfo(
    val id: Long,
    val rows: Int,
    val cols: Int,
)

@Serializable
data class MuxResyncAckPayload(
    val openStreams: List<ResyncOpenStreamInfo> = emptyList(),
)

@Serializable
data class ShareFileHeader(
    val name: String,
    val size: Long = -1L,
)

@Serializable
data class VolumeFsRequest(
    val op: String,
    val root: String,
    val path: String,
    val offset: Long = 0L,
    val length: Long = 0L,
    val truncate: Boolean = false,
    val mode: Int = 0,
    val recursive: Boolean = false,
)

@Serializable
data class VolumeDirEntry(
    val name: String,
    val isDir: Boolean = false,
    val isSymlink: Boolean = false,
    val size: Long = 0L,
    val mode: Int = 0,
    val modTimeUnixMs: Long = 0L,
)

@Serializable
data class VolumeReadDirResult(
    val entries: List<VolumeDirEntry> = emptyList(),
)

@Serializable
data class VolumeStatResult(
    val exists: Boolean,
    val name: String = "",
    val isDir: Boolean = false,
    val isSymlink: Boolean = false,
    val size: Long = 0L,
    val mode: Int = 0,
    val modTimeUnixMs: Long = 0L,
)

@Serializable
data class VolumeWriteResult(
    val bytesWritten: Long = 0L,
)

/**
 * Low-level 12-byte big-endian binary codec for `termvr-agent` mux frames.
 */
object MuxCodec {
    const val FRAME_MAGIC: Int = 0x5456
    const val PROTOCOL_VERSION: Int = 0x01
    const val HEADER_LENGTH: Int = 12
    const val MAX_PAYLOAD_LENGTH: Int = 65_536
    const val INITIAL_STREAM_WINDOW: Int = 256 * 1024
    const val MAX_STREAM_WINDOW: Long = 64L * 1024L * 1024L
    const val MAX_RPC_CHUNK_BYTES: Int = 32 * 1024
    const val RPC_FRAME_HEADER_LENGTH: Int = 6
    const val RPC_FLAG_FIN: Int = 0x01

    const val RPC_OP_OPEN_URL: Int = 1
    const val RPC_OP_CLIPBOARD_WRITE: Int = 2
    const val RPC_OP_CLIPBOARD_READ: Int = 3
    const val RPC_OP_SHARE_FILE: Int = 4
    const val RPC_OP_VOLUME_FS_OP: Int = 5

    const val RPC_STATUS_OK: Int = 0x00
    const val RPC_STATUS_BAD_REQUEST: Int = 0x01
    const val RPC_STATUS_DENIED: Int = 0x02
    const val RPC_STATUS_NOT_FOUND: Int = 0x03
    const val RPC_STATUS_ERROR: Int = 0x04

    const val MAX_CLIPBOARD_BYTES: Int = 1024 * 1024
    const val MAX_SHARE_FILE_BYTES: Long = 256L * 1024L * 1024L

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun validateTypeAndStream(type: MuxFrameType, streamId: Long) {
        when (type) {
            MuxFrameType.MUX_HELLO,
            MuxFrameType.TELEMETRY_REQ,
            MuxFrameType.TELEMETRY_RESP,
            MuxFrameType.HOST_RPC_REQ,
            MuxFrameType.HOST_RPC_RESP,
            MuxFrameType.MUX_RESYNC,
            MuxFrameType.MUX_RESYNC_ACK,
            -> {
                if (streamId != 0L) {
                    throw MuxProtocolException("${type.name} requires streamId 0, got $streamId")
                }
            }

            MuxFrameType.OPEN_PTY,
            MuxFrameType.OPEN_PTY_ACK,
            MuxFrameType.PTY_RESIZE,
            MuxFrameType.PTY_SIGNAL,
            MuxFrameType.PROXY_CONNECT,
            MuxFrameType.PROXY_CONNECT_ACK,
            -> {
                if (streamId < 1L) {
                    throw MuxProtocolException("${type.name} requires streamId >= 1, got $streamId")
                }
            }

            MuxFrameType.PTY_DATA,
            MuxFrameType.PTY_WINDOW_UPDATE,
            MuxFrameType.PTY_EXIT,
            -> {
                // Valid on stream 0 and streams >= 1.
            }
        }
    }

    fun writeFrame(output: OutputStream, frame: MuxFrame) {
        val hdr = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.BIG_ENDIAN)
        hdr.putShort(FRAME_MAGIC.toShort())
        hdr.put(PROTOCOL_VERSION.toByte())
        hdr.put(frame.type.opcode.toByte())
        hdr.putInt(frame.streamId.toInt())
        hdr.putInt(frame.payload.size)
        output.write(hdr.array())
        if (frame.payload.isNotEmpty()) {
            output.write(frame.payload)
        }
        output.flush()
    }

    fun readFrame(input: InputStream): MuxFrame? {
        val hdr = ByteArray(HEADER_LENGTH)
        val firstRead = readAtLeastOrEof(input, hdr)
        if (!firstRead) return null

        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.BIG_ENDIAN)
        val magic = bb.short.toInt() and 0xFFFF
        if (magic != FRAME_MAGIC) {
            throw MuxProtocolException("Invalid mux magic: 0x%04x".format(magic))
        }
        val version = bb.get().toInt() and 0xFF
        if (version != PROTOCOL_VERSION) {
            throw MuxProtocolException("Unsupported mux version: $version")
        }
        val opcode = bb.get().toInt() and 0xFF
        val type = MuxFrameType.fromOpcode(opcode)
            ?: throw MuxProtocolException("Unknown mux opcode: 0x%02x".format(opcode))
        val streamId = bb.int.toLong() and 0xFFFFFFFFL
        val lengthLong = bb.int.toLong() and 0xFFFFFFFFL
        if (lengthLong > MAX_PAYLOAD_LENGTH) {
            throw MuxProtocolException("Mux payload exceeds $MAX_PAYLOAD_LENGTH bytes: $lengthLong")
        }
        validateTypeAndStream(type, streamId)
        val length = lengthLong.toInt()
        val payload = if (length > 0) {
            val buf = ByteArray(length)
            readFullyOrThrow(input, buf)
            buf
        } else {
            ByteArray(0)
        }
        return MuxFrame(type = type, streamId = streamId, payload = payload)
    }

    fun encodeWinSize(rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        bb.putShort(rows.coerceIn(0, 65535).toShort())
        bb.putShort(cols.coerceIn(0, 65535).toShort())
        bb.putShort(xpixel.coerceIn(0, 65535).toShort())
        bb.putShort(ypixel.coerceIn(0, 65535).toShort())
        return bb.array()
    }

    fun encodeOpenPtyPayload(
        rows: Int,
        cols: Int,
        xpixel: Int,
        ypixel: Int,
        spec: OpenPtyJsonSpec,
    ): ByteArray {
        val winBytes = encodeWinSize(rows, cols, xpixel, ypixel)
        val jsonBytes = json.encodeToString(spec).toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(winBytes.size + jsonBytes.size)
        System.arraycopy(winBytes, 0, out, 0, winBytes.size)
        System.arraycopy(jsonBytes, 0, out, winBytes.size, jsonBytes.size)
        return out
    }

    fun encodeWindowUpdate(deltaBytes: Int): ByteArray {
        require(deltaBytes > 0) { "Window update delta must be positive" }
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(deltaBytes).array()
    }

    fun decodeWindowUpdate(payload: ByteArray): Int {
        if (payload.size != 4) {
            throw MuxProtocolException("PTY_WINDOW_UPDATE length must be 4, got ${payload.size}")
        }
        val delta = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
        if (delta <= 0) {
            throw MuxProtocolException("PTY_WINDOW_UPDATE delta must be positive, got $delta")
        }
        return delta
    }

    fun encodeRpcChunk(reqId: Long, code: Int, flags: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= MAX_RPC_CHUNK_BYTES) {
            "RPC chunk exceeds $MAX_RPC_CHUNK_BYTES bytes: ${data.size}"
        }
        val out = ByteArray(RPC_FRAME_HEADER_LENGTH + data.size)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(reqId.toInt())
        bb.put(code.toByte())
        bb.put(flags.toByte())
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, out, RPC_FRAME_HEADER_LENGTH, data.size)
        }
        return out
    }

    fun decodeRpcChunk(payload: ByteArray): RpcChunk {
        if (payload.size < RPC_FRAME_HEADER_LENGTH) {
            throw MuxProtocolException("RPC chunk shorter than $RPC_FRAME_HEADER_LENGTH bytes")
        }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val reqId = bb.int.toLong() and 0xFFFFFFFFL
        val code = bb.get().toInt() and 0xFF
        val flags = bb.get().toInt() and 0xFF
        val dataLen = payload.size - RPC_FRAME_HEADER_LENGTH
        if (dataLen > MAX_RPC_CHUNK_BYTES) {
            throw MuxProtocolException("RPC chunk payload exceeds $MAX_RPC_CHUNK_BYTES bytes: $dataLen")
        }
        val data = payload.copyOfRange(RPC_FRAME_HEADER_LENGTH, payload.size)
        return RpcChunk(reqId = reqId, code = code, flags = flags, data = data)
    }

    private fun readAtLeastOrEof(input: InputStream, dest: ByteArray): Boolean {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            if (n < 0) {
                if (offset == 0) return false
                throw EOFException("Truncated mux frame header")
            }
            offset += n
        }
        return true
    }

    private fun readFullyOrThrow(input: InputStream, dest: ByteArray) {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            if (n < 0) {
                throw EOFException("Truncated mux frame payload")
            }
            offset += n
        }
    }

    data class RpcChunk(
        val reqId: Long,
        val code: Int,
        val flags: Int,
        val data: ByteArray,
    )
}

/**
 * Handles gated Guest-to-Host `HOST_RPC` operations (`OPEN_URL`, `CLIPBOARD_*`, `SHARE_FILE`).
 */
interface HostRpcDelegate {
    fun openUrl(url: String)
    fun writeClipboard(text: String)
    fun readClipboard(): String?
    fun confirmClipboardRead(): Boolean
    fun shareFile(sharedFile: File, displayName: String)
}

/**
 * Receives PTY or Stream-0 byte streams and exit notifications from `MuxSessionClient`.
 */
interface MuxStreamListener {
    fun onData(streamId: Long, bytes: ByteArray)
    fun onExit(streamId: Long, exitCode: Int)
}

/**
 * Tracks a single active multiplexed PTY stream on the host.
 */
class MuxPtyHandle internal constructor(
    val streamId: Long,
    private val client: MuxSessionClient,
) : Closeable {
    fun writeInput(data: ByteArray) {
        client.sendStreamData(streamId, data)
    }

    fun resize(rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0) {
        client.resizePty(streamId, rows, cols, xpixel, ypixel)
    }

    fun signal(posixSignal: Int) {
        client.signalPty(streamId, posixSignal)
    }

    override fun close() {
        client.detachPtyListener(streamId)
    }
}

/**
 * Host-side binary frame multiplexer communicating with `termvr-agent` over stdio pipes.
 */
class MuxSessionClient(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    private val sharesDir: File,
    private val proxyBridge: LoopbackProxyBridge? = null,
    private val hostRpcDelegate: HostRpcDelegate? = null,
    private val clipboardReadPolicyProvider: () -> ClipboardReadPolicy = { ClipboardReadPolicy.DISABLED },
) : Closeable {

    private val writeLock = ReentrantLock()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val nextPtyStreamId = AtomicLong(1L)
    private val nextRpcReqId = AtomicLong(1L)

    private val helloFuture = CompletableFuture<MuxHelloPayload>()
    private val pendingOpenPty = ConcurrentHashMap<Long, CompletableFuture<Pair<Int, String>>>()
    private val pendingTelemetry = ConcurrentHashMap<Long, CompletableFuture<GuestTelemetryPayload>>()
    private val telemetrySeq = AtomicInteger(0)
    private val pendingResync = ConcurrentHashMap<Int, CompletableFuture<MuxResyncAckPayload>>()
    private val resyncSeq = AtomicInteger(0)

    private val stream0SendWindow = SlidingSendWindow(MuxCodec.INITIAL_STREAM_WINDOW)
    private val streamSendWindows = ConcurrentHashMap<Long, SlidingSendWindow>()
    private val knownOpenPtyStreams = ConcurrentHashMap.newKeySet<Long>()
    private val streamRecvAvailable = ConcurrentHashMap<Long, AtomicInteger>()
    private val streamListeners = ConcurrentHashMap<Long, MuxStreamListener>()

    val isClosed: Boolean
        get() = closed.get()

    fun isStreamOpen(streamId: Long): Boolean = !closed.get() && streamId in knownOpenPtyStreams

    fun openStreamIds(): Set<Long> = if (closed.get()) emptySet() else knownOpenPtyStreams.toSet()

    private val inboundGuestRpcBuffers = ConcurrentHashMap<Long, InboundGuestRpcState>()
    private val outboundHostRpcWaiters = ConcurrentHashMap<Long, OutboundHostRpcCollector>()
    private val activeProxySockets = ConcurrentHashMap<Long, ProxyRelayState>()

    private val workerPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "termvr-mux-worker").apply { isDaemon = true }
    }

    @Volatile
    private var readerThread: Thread? = null

    /**
     * Starts the background frame reader loop and sends optional host `MUX_HELLO`.
     */
    fun start(sendHostHello: Boolean = true) {
        check(!closed.get()) { "MuxSessionClient is closed" }
        if (!started.compareAndSet(false, true)) return

        if (sendHostHello) {
            val helloBytes = MuxCodec.json.encodeToString(
                MuxHelloPayload(protocol = 1, agentVersion = "host-1.0", initDaemon = false),
            ).toByteArray(StandardCharsets.UTF_8)
            writeFrame(MuxFrame(MuxFrameType.MUX_HELLO, 0L, helloBytes))
        }

        readerThread = Thread({ readLoop() }, "termvr-mux-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun awaitHello(timeoutMs: Long = 10_000L): MuxHelloPayload =
        helloFuture.get(timeoutMs, TimeUnit.MILLISECONDS)

    fun setStreamListener(streamId: Long, listener: MuxStreamListener?) {
        if (listener == null) {
            streamListeners.remove(streamId)
        } else {
            streamListeners[streamId] = listener
            streamRecvAvailable.putIfAbsent(streamId, AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW))
        }
    }

    fun attachPtyHandle(streamId: Long, listener: MuxStreamListener? = null): MuxPtyHandle {
        require(streamId >= 1L) { "attachPtyHandle requires streamId >= 1" }
        if (closed.get()) throw IOException("MuxSessionClient is closed")
        knownOpenPtyStreams.add(streamId)
        streamSendWindows.putIfAbsent(streamId, SlidingSendWindow(MuxCodec.INITIAL_STREAM_WINDOW))
        streamRecvAvailable.putIfAbsent(streamId, AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW))
        if (listener != null) {
            setStreamListener(streamId, listener)
        }
        return MuxPtyHandle(streamId, this)
    }

    fun detachPtyListener(streamId: Long) {
        streamListeners.remove(streamId)
    }

    fun writeStream0Stdin(data: ByteArray) {
        sendStreamData(0L, data)
    }

    fun openPty(
        argv: List<String> = emptyList(),
        cwd: String = "/workspace",
        env: Map<String, String> = emptyMap(),
        rows: Int = 24,
        cols: Int = 80,
        xpixel: Int = 0,
        ypixel: Int = 0,
        listener: MuxStreamListener? = null,
        timeoutMs: Long = 10_000L,
    ): MuxPtyHandle {
        val streamId = nextPtyStreamId.getAndIncrement()
        streamSendWindows[streamId] = SlidingSendWindow(MuxCodec.INITIAL_STREAM_WINDOW)
        streamRecvAvailable[streamId] = AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW)
        if (listener != null) {
            streamListeners[streamId] = listener
        }

        val ackFuture = CompletableFuture<Pair<Int, String>>()
        pendingOpenPty[streamId] = ackFuture

        val payload = MuxCodec.encodeOpenPtyPayload(
            rows = rows,
            cols = cols,
            xpixel = xpixel,
            ypixel = ypixel,
            spec = OpenPtyJsonSpec(argv = argv, cwd = cwd, env = env),
        )
        try {
            writeFrame(MuxFrame(MuxFrameType.OPEN_PTY, streamId, payload))
            val (status, errorText) = ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (status != 0) {
                streamSendWindows.remove(streamId)
                streamRecvAvailable.remove(streamId)
                streamListeners.remove(streamId)
                throw MuxPtyOpenException(status, errorText.ifEmpty { "OPEN_PTY failed with status $status" })
            }
            knownOpenPtyStreams.add(streamId)
            return MuxPtyHandle(streamId, this)
        } catch (e: Exception) {
            pendingOpenPty.remove(streamId)
            if (e is MuxPtyOpenException) throw e
            streamSendWindows.remove(streamId)
            streamRecvAvailable.remove(streamId)
            streamListeners.remove(streamId)
            throw (e as? IOException ?: IOException("OPEN_PTY failed: ${e.message}", e))
        }
    }

    fun sendStreamData(streamId: Long, data: ByteArray) {
        if (data.isEmpty()) return
        val window = if (streamId == 0L) {
            stream0SendWindow
        } else {
            streamSendWindows[streamId] ?: throw IOException("Stream $streamId is not open")
        }
        var offset = 0
        while (offset < data.size) {
            val take = window.acquire(data.size - offset)
            val slice = data.copyOfRange(offset, offset + take)
            writeFrame(MuxFrame(MuxFrameType.PTY_DATA, streamId, slice))
            offset += take
        }
    }

    fun resizePty(streamId: Long, rows: Int, cols: Int, xpixel: Int = 0, ypixel: Int = 0) {
        require(streamId >= 1L) { "PTY_RESIZE requires streamId >= 1" }
        writeFrame(
            MuxFrame(
                type = MuxFrameType.PTY_RESIZE,
                streamId = streamId,
                payload = MuxCodec.encodeWinSize(rows, cols, xpixel, ypixel),
            ),
        )
    }

    fun signalPty(streamId: Long, posixSignal: Int) {
        require(streamId >= 1L) { "PTY_SIGNAL requires streamId >= 1" }
        writeFrame(
            MuxFrame(
                type = MuxFrameType.PTY_SIGNAL,
                streamId = streamId,
                payload = byteArrayOf((posixSignal and 0xFF).toByte()),
            ),
        )
    }

    fun requestTelemetry(timeoutMs: Long = 5_000L): GuestTelemetryPayload {
        val key = telemetrySeq.incrementAndGet().toLong()
        val future = CompletableFuture<GuestTelemetryPayload>()
        pendingTelemetry[key] = future
        try {
            writeFrame(MuxFrame(MuxFrameType.TELEMETRY_REQ, 0L))
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingTelemetry.remove(key)
        }
    }

    fun resync(timeoutMs: Long = 10_000L): MuxResyncAckPayload {
        val key = resyncSeq.incrementAndGet()
        val future = CompletableFuture<MuxResyncAckPayload>()
        pendingResync[key] = future
        try {
            writeFrame(MuxFrame(MuxFrameType.MUX_RESYNC, 0L))
            val ack = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            stream0SendWindow.reset(MuxCodec.INITIAL_STREAM_WINDOW)
            for (win in streamSendWindows.values) {
                win.reset(MuxCodec.INITIAL_STREAM_WINDOW)
            }
            for (recv in streamRecvAvailable.values) {
                recv.set(MuxCodec.INITIAL_STREAM_WINDOW)
            }
            var maxRestoredStreamId = 0L
            knownOpenPtyStreams.clear()
            for (s in ack.openStreams) {
                if (s.id >= 1L) {
                    knownOpenPtyStreams.add(s.id)
                }
                streamSendWindows.putIfAbsent(s.id, SlidingSendWindow(MuxCodec.INITIAL_STREAM_WINDOW))
                streamRecvAvailable.putIfAbsent(s.id, AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW))
                if (s.id > maxRestoredStreamId) {
                    maxRestoredStreamId = s.id
                }
            }
            if (maxRestoredStreamId > 0L) {
                val floorNext = maxRestoredStreamId + 1L
                nextPtyStreamId.updateAndGet { current -> maxOf(current, floorNext) }
            }
            return ack
        } finally {
            pendingResync.remove(key)
        }
    }

    fun volumeReadDir(root: String, path: String, timeoutMs: Long = 15_000L): List<VolumeDirEntry> {
        val reqJson = MuxCodec.json.encodeToString(VolumeFsRequest(op = "readdir", root = root, path = path))
        val resp = callHostToGuestVolumeOp(reqJson, null, timeoutMs)
        return MuxCodec.json.decodeFromString<VolumeReadDirResult>(resp.toString(StandardCharsets.UTF_8)).entries
    }

    fun volumeStat(root: String, path: String, timeoutMs: Long = 15_000L): VolumeStatResult {
        val reqJson = MuxCodec.json.encodeToString(VolumeFsRequest(op = "stat", root = root, path = path))
        val resp = callHostToGuestVolumeOp(reqJson, null, timeoutMs)
        return MuxCodec.json.decodeFromString(resp.toString(StandardCharsets.UTF_8))
    }

    fun volumeReadBytes(
        root: String,
        path: String,
        offset: Long = 0L,
        length: Long = 0L,
        timeoutMs: Long = 30_000L,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        volumeReadFile(root, path, offset, length, out, timeoutMs)
        return out.toByteArray()
    }

    fun volumeReadFile(
        root: String,
        path: String,
        offset: Long = 0L,
        length: Long = 0L,
        output: OutputStream,
        timeoutMs: Long = 60_000L,
    ): Long {
        val reqJson = MuxCodec.json.encodeToString(
            VolumeFsRequest(op = "read", root = root, path = path, offset = offset, length = length),
        )
        return callHostToGuestVolumeOpStreaming(reqJson, null, output, timeoutMs)
    }

    fun volumeWriteBytes(
        root: String,
        path: String,
        data: ByteArray,
        offset: Long = 0L,
        truncate: Boolean = true,
        mode: Int = 420, // 0644
        timeoutMs: Long = 30_000L,
    ): Long = volumeWriteFile(root, path, ByteArrayInputStream(data), offset, truncate, mode, timeoutMs)

    fun volumeWriteFile(
        root: String,
        path: String,
        input: InputStream,
        offset: Long = 0L,
        truncate: Boolean = true,
        mode: Int = 420,
        timeoutMs: Long = 60_000L,
    ): Long {
        val reqJson = MuxCodec.json.encodeToString(
            VolumeFsRequest(
                op = "write",
                root = root,
                path = path,
                offset = offset,
                truncate = truncate,
                mode = mode,
            ),
        )
        val resp = callHostToGuestVolumeOp(reqJson, input, timeoutMs)
        return MuxCodec.json.decodeFromString<VolumeWriteResult>(resp.toString(StandardCharsets.UTF_8)).bytesWritten
    }

    fun volumeMkdir(
        root: String,
        path: String,
        mode: Int = 493, // 0755
        timeoutMs: Long = 15_000L,
    ) {
        val reqJson = MuxCodec.json.encodeToString(
            VolumeFsRequest(op = "mkdir", root = root, path = path, mode = mode),
        )
        callHostToGuestVolumeOp(reqJson, null, timeoutMs)
    }

    fun volumeUnlink(
        root: String,
        path: String,
        recursive: Boolean = false,
        timeoutMs: Long = 15_000L,
    ) {
        val reqJson = MuxCodec.json.encodeToString(
            VolumeFsRequest(op = "unlink", root = root, path = path, recursive = recursive),
        )
        callHostToGuestVolumeOp(reqJson, null, timeoutMs)
    }

    private fun callHostToGuestVolumeOp(
        headerJson: String,
        bodyInput: InputStream?,
        timeoutMs: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        callHostToGuestVolumeOpStreaming(headerJson, bodyInput, out, timeoutMs)
        return out.toByteArray()
    }

    private fun callHostToGuestVolumeOpStreaming(
        headerJson: String,
        bodyInput: InputStream?,
        responseOut: OutputStream,
        timeoutMs: Long,
    ): Long {
        val reqId = nextRpcReqId.getAndIncrement()
        val collector = OutboundHostRpcCollector(responseOut)
        outboundHostRpcWaiters[reqId] = collector
        try {
            val headerLine = (headerJson + "\n").toByteArray(StandardCharsets.UTF_8)
            val hasBody = bodyInput != null
            sendRpcRequestChunks(reqId, MuxCodec.RPC_OP_VOLUME_FS_OP, headerLine, fin = !hasBody)
            if (bodyInput != null) {
                val buf = ByteArray(MuxCodec.MAX_RPC_CHUNK_BYTES)
                while (true) {
                    val n = bodyInput.read(buf)
                    if (n < 0) {
                        sendRpcRequestChunks(reqId, MuxCodec.RPC_OP_VOLUME_FS_OP, ByteArray(0), fin = true)
                        break
                    }
                    if (n > 0) {
                        sendRpcRequestChunks(reqId, MuxCodec.RPC_OP_VOLUME_FS_OP, buf.copyOfRange(0, n), fin = false)
                    }
                }
            }
            val result = collector.future.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (result.status != MuxCodec.RPC_STATUS_OK) {
                throw MuxHostRpcException(result.status, result.errorMessage)
            }
            return result.bytesReceived
        } finally {
            outboundHostRpcWaiters.remove(reqId)
        }
    }

    private fun sendRpcRequestChunks(reqId: Long, op: Int, data: ByteArray, fin: Boolean) {
        if (data.isEmpty()) {
            val flags = if (fin) MuxCodec.RPC_FLAG_FIN else 0
            val payload = MuxCodec.encodeRpcChunk(reqId, op, flags, ByteArray(0))
            writeFrame(MuxFrame(MuxFrameType.HOST_RPC_REQ, 0L, payload))
            return
        }
        var offset = 0
        while (offset < data.size) {
            val take = minOf(MuxCodec.MAX_RPC_CHUNK_BYTES, data.size - offset)
            val isLast = fin && (offset + take >= data.size)
            val flags = if (isLast) MuxCodec.RPC_FLAG_FIN else 0
            val slice = data.copyOfRange(offset, offset + take)
            val payload = MuxCodec.encodeRpcChunk(reqId, op, flags, slice)
            writeFrame(MuxFrame(MuxFrameType.HOST_RPC_REQ, 0L, payload))
            offset += take
        }
    }

    private fun sendRpcResponseChunks(reqId: Long, status: Int, data: ByteArray, fin: Boolean = true) {
        if (data.isEmpty()) {
            val flags = if (fin) MuxCodec.RPC_FLAG_FIN else 0
            val payload = MuxCodec.encodeRpcChunk(reqId, status, flags, ByteArray(0))
            writeFrame(MuxFrame(MuxFrameType.HOST_RPC_RESP, 0L, payload))
            return
        }
        var offset = 0
        while (offset < data.size) {
            val take = minOf(MuxCodec.MAX_RPC_CHUNK_BYTES, data.size - offset)
            val isLast = fin && (offset + take >= data.size)
            val flags = if (isLast) MuxCodec.RPC_FLAG_FIN else 0
            val slice = data.copyOfRange(offset, offset + take)
            val payload = MuxCodec.encodeRpcChunk(reqId, status, flags, slice)
            writeFrame(MuxFrame(MuxFrameType.HOST_RPC_RESP, 0L, payload))
            offset += take
        }
    }

    private fun writeFrame(frame: MuxFrame) {
        if (closed.get()) throw IOException("MuxSessionClient is closed")
        writeLock.withLock {
            if (closed.get()) throw IOException("MuxSessionClient is closed")
            MuxCodec.writeFrame(outputStream, frame)
        }
    }

    private fun readLoop() {
        try {
            val buffered = BufferedInputStream(inputStream, 64 * 1024)
            while (!closed.get()) {
                val frame = MuxCodec.readFrame(buffered) ?: break
                dispatchFrame(frame)
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                helloFuture.completeExceptionally(e)
            }
        } finally {
            close()
        }
    }

    private fun dispatchFrame(frame: MuxFrame) {
        when (frame.type) {
            MuxFrameType.MUX_HELLO -> {
                val raw = frame.payload.toString(StandardCharsets.UTF_8)
                val hello = MuxCodec.json.decodeFromString<MuxHelloPayload>(raw)
                if (hello.protocol != MuxCodec.PROTOCOL_VERSION) {
                    throw MuxProtocolException("Unsupported guest protocol version: ${hello.protocol}")
                }
                helloFuture.complete(hello)
            }

            MuxFrameType.OPEN_PTY_ACK -> {
                if (frame.payload.isEmpty()) {
                    throw MuxProtocolException("Empty OPEN_PTY_ACK")
                }
                val status = frame.payload[0].toInt() and 0xFF
                val err = if (frame.payload.size > 1) {
                    frame.payload.copyOfRange(1, frame.payload.size).toString(StandardCharsets.UTF_8)
                } else {
                    ""
                }
                pendingOpenPty.remove(frame.streamId)?.complete(status to err)
            }

            MuxFrameType.PTY_DATA -> handleInboundData(frame.streamId, frame.payload)

            MuxFrameType.PTY_WINDOW_UPDATE -> {
                val delta = MuxCodec.decodeWindowUpdate(frame.payload)
                val win = if (frame.streamId == 0L) stream0SendWindow else streamSendWindows[frame.streamId]
                win?.grant(delta)
            }

            MuxFrameType.PTY_EXIT -> {
                if (frame.payload.size != 1) {
                    throw MuxProtocolException("PTY_EXIT length must be 1, got ${frame.payload.size}")
                }
                val code = frame.payload[0].toInt() and 0xFF
                knownOpenPtyStreams.remove(frame.streamId)
                activeProxySockets.remove(frame.streamId)?.close()
                streamSendWindows.remove(frame.streamId)?.close()
                streamRecvAvailable.remove(frame.streamId)
                streamListeners.remove(frame.streamId)?.onExit(frame.streamId, code)
            }

            MuxFrameType.TELEMETRY_RESP -> {
                val raw = frame.payload.toString(StandardCharsets.UTF_8)
                val snap = MuxCodec.json.decodeFromString<GuestTelemetryPayload>(raw)
                val entry = pendingTelemetry.entries.firstOrNull()
                if (entry != null) {
                    pendingTelemetry.remove(entry.key)?.complete(snap)
                }
            }

            MuxFrameType.MUX_RESYNC_ACK -> {
                val raw = frame.payload.toString(StandardCharsets.UTF_8)
                val ack = MuxCodec.json.decodeFromString<MuxResyncAckPayload>(raw)
                val entry = pendingResync.entries.firstOrNull()
                if (entry != null) {
                    pendingResync.remove(entry.key)?.complete(ack)
                }
            }

            MuxFrameType.HOST_RPC_REQ -> {
                val chunk = MuxCodec.decodeRpcChunk(frame.payload)
                handleInboundGuestRpcChunk(chunk)
            }

            MuxFrameType.HOST_RPC_RESP -> {
                val chunk = MuxCodec.decodeRpcChunk(frame.payload)
                handleOutboundHostRpcResp(chunk)
            }

            MuxFrameType.PROXY_CONNECT -> handleInboundProxyConnect(frame.streamId, frame.payload)

            else -> {
                // Host does not expect guest-initiated OPEN_PTY, PTY_RESIZE, PTY_SIGNAL, etc.
            }
        }
    }

    private fun handleInboundData(streamId: Long, payload: ByteArray) {
        if (payload.isEmpty()) return
        val proxyRelay = activeProxySockets[streamId]
        if (proxyRelay != null) {
            val avail = proxyRelay.recvAvailable
            val current = avail.addAndGet(-payload.size)
            if (current < 0) {
                throw MuxProtocolException("Proxy stream $streamId exceeded sliding window")
            }
            try {
                proxyRelay.socketOut.write(payload)
                proxyRelay.socketOut.flush()
                avail.addAndGet(payload.size)
                writeFrame(
                    MuxFrame(
                        MuxFrameType.PTY_WINDOW_UPDATE,
                        streamId,
                        MuxCodec.encodeWindowUpdate(payload.size),
                    ),
                )
            } catch (_: IOException) {
                proxyRelay.close()
            }
            return
        }

        val recvCounter = streamRecvAvailable.computeIfAbsent(streamId) {
            AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW)
        }
        val remaining = recvCounter.addAndGet(-payload.size)
        if (remaining < 0) {
            throw MuxProtocolException("Stream $streamId exceeded 256 KiB receive window")
        }
        streamListeners[streamId]?.onData(streamId, payload)
        recvCounter.addAndGet(payload.size)
        try {
            writeFrame(
                MuxFrame(
                    MuxFrameType.PTY_WINDOW_UPDATE,
                    streamId,
                    MuxCodec.encodeWindowUpdate(payload.size),
                ),
            )
        } catch (_: IOException) {
        }
    }

    private fun handleInboundProxyConnect(streamId: Long, payload: ByteArray) {
        if (payload.size < 3) {
            sendProxyConnectAck(streamId, LoopbackProxyBridge.STATUS_BAD_REQUEST, "PROXY_CONNECT too short")
            return
        }
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val dstPort = bb.short.toInt() and 0xFFFF
        val dstHost = payload.copyOfRange(2, payload.size).toString(StandardCharsets.UTF_8).trim()
        if (dstPort !in 1..65535 || dstHost.isEmpty() || dstHost.contains('\u0000')) {
            sendProxyConnectAck(streamId, LoopbackProxyBridge.STATUS_BAD_REQUEST, "Invalid target")
            return
        }

        val bridge = proxyBridge
        if (bridge == null) {
            sendProxyConnectAck(streamId, LoopbackProxyBridge.STATUS_GENERAL_FAILURE, "No proxy bridge configured")
            return
        }

        workerPool.execute {
            when (val outcome = bridge.dialForMuxStream(dstHost, dstPort, allowDirectBypassCode = false)) {
                is ProxyDialOutcome.DirectBypass -> {
                    sendProxyConnectAck(streamId, LoopbackProxyBridge.STATUS_GENERAL_FAILURE, outcome.reason)
                }

                is ProxyDialOutcome.Rejected -> {
                    sendProxyConnectAck(streamId, outcome.statusCode, outcome.message)
                }

                is ProxyDialOutcome.Connected -> {
                    val sock = outcome.socket
                    val sendWin = SlidingSendWindow(MuxCodec.INITIAL_STREAM_WINDOW)
                    streamSendWindows[streamId] = sendWin
                    val relay = ProxyRelayState(
                        socket = sock,
                        socketOut = sock.getOutputStream(),
                        recvAvailable = AtomicInteger(MuxCodec.INITIAL_STREAM_WINDOW),
                    )
                    activeProxySockets[streamId] = relay
                    sendProxyConnectAck(streamId, PROXY_STATUS_OK, "")

                    var exitCode = 0
                    try {
                        val sockIn = sock.getInputStream()
                        val buf = ByteArray(32 * 1024)
                        while (!closed.get() && !sock.isClosed) {
                            val n = sockIn.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                sendStreamData(streamId, buf.copyOfRange(0, n))
                            }
                        }
                    } catch (_: IOException) {
                        exitCode = 1
                    } finally {
                        activeProxySockets.remove(streamId)
                        streamSendWindows.remove(streamId)?.close()
                        relay.close()
                        try {
                            writeFrame(MuxFrame(MuxFrameType.PTY_EXIT, streamId, byteArrayOf(exitCode.toByte())))
                        } catch (_: IOException) {
                        }
                    }
                }
            }
        }
    }

    private fun sendProxyConnectAck(streamId: Long, status: Int, message: String) {
        val msgBytes = message.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + minOf(msgBytes.size, MuxCodec.MAX_PAYLOAD_LENGTH - 1))
        payload[0] = status.toByte()
        if (payload.size > 1) {
            System.arraycopy(msgBytes, 0, payload, 1, payload.size - 1)
        }
        try {
            writeFrame(MuxFrame(MuxFrameType.PROXY_CONNECT_ACK, streamId, payload))
        } catch (_: IOException) {
        }
    }

    private fun handleInboundGuestRpcChunk(chunk: MuxCodec.RpcChunk) {
        val state = inboundGuestRpcBuffers.computeIfAbsent(chunk.reqId) {
            InboundGuestRpcState(op = chunk.code, sharesDir = sharesDir)
        }
        try {
            state.append(chunk.data)
        } catch (e: MuxHostRpcException) {
            if ((chunk.flags and MuxCodec.RPC_FLAG_FIN) != 0) {
                inboundGuestRpcBuffers.remove(chunk.reqId)
            } else {
                state.failedError = e
            }
            if ((chunk.flags and MuxCodec.RPC_FLAG_FIN) != 0) {
                state.cleanupOnError()
                sendRpcResponseChunks(chunk.reqId, e.status, e.message.orEmpty().toByteArray(StandardCharsets.UTF_8))
            }
            return
        }

        if ((chunk.flags and MuxCodec.RPC_FLAG_FIN) == 0) {
            return
        }
        inboundGuestRpcBuffers.remove(chunk.reqId)
        val priorErr = state.failedError
        if (priorErr != null) {
            state.cleanupOnError()
            sendRpcResponseChunks(chunk.reqId, priorErr.status, priorErr.message.orEmpty().toByteArray(StandardCharsets.UTF_8))
            return
        }

        workerPool.execute {
            executeInboundGuestRpc(chunk.reqId, state)
        }
    }

    private fun executeInboundGuestRpc(reqId: Long, state: InboundGuestRpcState) {
        val delegate = hostRpcDelegate
        try {
            when (state.op) {
                MuxCodec.RPC_OP_OPEN_URL -> {
                    val rawUrl = state.bodyBytes().toString(StandardCharsets.UTF_8).trim()
                    val uri = try {
                        URI(rawUrl)
                    } catch (_: Exception) {
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "Malformed URL")
                    }
                    val scheme = uri.scheme?.lowercase()
                    if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "Only http and https URLs are allowed")
                    }
                    if (delegate == null) {
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "No host RPC delegate configured")
                    }
                    delegate.openUrl(uri.toString())
                    sendRpcResponseChunks(reqId, MuxCodec.RPC_STATUS_OK, ByteArray(0))
                }

                MuxCodec.RPC_OP_CLIPBOARD_WRITE -> {
                    val textBytes = state.bodyBytes()
                    if (textBytes.size > MuxCodec.MAX_CLIPBOARD_BYTES) {
                        throw MuxHostRpcException(
                            MuxCodec.RPC_STATUS_BAD_REQUEST,
                            "Clipboard payload exceeds ${MuxCodec.MAX_CLIPBOARD_BYTES} bytes",
                        )
                    }
                    if (delegate == null) {
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "No host RPC delegate configured")
                    }
                    delegate.writeClipboard(textBytes.toString(StandardCharsets.UTF_8))
                    sendRpcResponseChunks(reqId, MuxCodec.RPC_STATUS_OK, ByteArray(0))
                }

                MuxCodec.RPC_OP_CLIPBOARD_READ -> {
                    val policy = clipboardReadPolicyProvider()
                    when (policy) {
                        ClipboardReadPolicy.DISABLED -> {
                            throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "Clipboard read is disabled")
                        }

                        ClipboardReadPolicy.ASK_EACH_TIME -> {
                            val allowed = delegate?.confirmClipboardRead() == true
                            if (!allowed) {
                                throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "Clipboard read denied by user")
                            }
                        }

                        ClipboardReadPolicy.SESSION_ALLOWED -> Unit
                    }
                    val text = delegate?.readClipboard().orEmpty()
                    sendRpcResponseChunks(reqId, MuxCodec.RPC_STATUS_OK, text.toByteArray(StandardCharsets.UTF_8))
                }

                MuxCodec.RPC_OP_SHARE_FILE -> {
                    val result = state.finalizeShareFile()
                    if (delegate == null) {
                        result.file.delete()
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "No host RPC delegate configured")
                    }
                    delegate.shareFile(result.file, result.displayName)
                    sendRpcResponseChunks(reqId, MuxCodec.RPC_STATUS_OK, ByteArray(0))
                }

                else -> {
                    throw MuxHostRpcException(MuxCodec.RPC_STATUS_DENIED, "Unsupported guest RPC op ${state.op}")
                }
            }
        } catch (e: MuxHostRpcException) {
            state.cleanupOnError()
            try {
                sendRpcResponseChunks(reqId, e.status, e.message.orEmpty().toByteArray(StandardCharsets.UTF_8))
            } catch (_: IOException) {
            }
        } catch (e: Exception) {
            state.cleanupOnError()
            try {
                sendRpcResponseChunks(
                    reqId,
                    MuxCodec.RPC_STATUS_ERROR,
                    (e.message ?: "Host RPC failed").toByteArray(StandardCharsets.UTF_8),
                )
            } catch (_: IOException) {
            }
        }
    }

    private fun handleOutboundHostRpcResp(chunk: MuxCodec.RpcChunk) {
        val collector = outboundHostRpcWaiters[chunk.reqId] ?: return
        collector.onChunk(chunk)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stream0SendWindow.close()
        knownOpenPtyStreams.clear()
        for (win in streamSendWindows.values) {
            win.close()
        }
        streamSendWindows.clear()
        for (relay in activeProxySockets.values) {
            relay.close()
        }
        activeProxySockets.clear()
        for (state in inboundGuestRpcBuffers.values) {
            state.cleanupOnError()
        }
        inboundGuestRpcBuffers.clear()
        for (waiter in outboundHostRpcWaiters.values) {
            waiter.future.completeExceptionally(IOException("MuxSessionClient closed"))
        }
        outboundHostRpcWaiters.clear()
        for (f in pendingOpenPty.values) {
            f.completeExceptionally(IOException("MuxSessionClient closed"))
        }
        pendingOpenPty.clear()
        for (f in pendingTelemetry.values) {
            f.completeExceptionally(IOException("MuxSessionClient closed"))
        }
        pendingTelemetry.clear()
        for (f in pendingResync.values) {
            f.completeExceptionally(IOException("MuxSessionClient closed"))
        }
        pendingResync.clear()
        helloFuture.completeExceptionally(IOException("MuxSessionClient closed"))
        try {
            inputStream.close()
        } catch (_: IOException) {
        }
        try {
            outputStream.close()
        } catch (_: IOException) {
        }
        workerPool.shutdownNow()
    }

    private class SlidingSendWindow(initialAvail: Int) {
        private val lock = ReentrantLock()
        private val cond = lock.newCondition()
        private var avail: Long = initialAvail.toLong()
        private var closed: Boolean = false

        fun acquire(wantBytes: Int): Int {
            require(wantBytes > 0)
            lock.withLock {
                while (!closed && avail <= 0L) {
                    cond.await()
                }
                if (closed) throw IOException("Mux stream send window closed")
                val take = minOf(wantBytes.toLong(), MuxCodec.MAX_PAYLOAD_LENGTH.toLong(), avail).toInt()
                avail -= take
                return take
            }
        }

        fun grant(deltaBytes: Int) {
            lock.withLock {
                val next = avail + deltaBytes.toLong()
                if (next > MuxCodec.MAX_STREAM_WINDOW) {
                    throw MuxProtocolException("Sliding window overflow: $next > ${MuxCodec.MAX_STREAM_WINDOW}")
                }
                avail = next
                cond.signalAll()
            }
        }

        fun reset(value: Int) {
            lock.withLock {
                avail = value.toLong()
                cond.signalAll()
            }
        }

        fun close() {
            lock.withLock {
                closed = true
                cond.signalAll()
            }
        }
    }

    private class ProxyRelayState(
        val socket: Socket,
        val socketOut: OutputStream,
        val recvAvailable: AtomicInteger,
    ) : Closeable {
        override fun close() {
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private class OutboundHostRpcCollector(
        private val responseOut: OutputStream,
    ) {
        val future = CompletableFuture<Result>()
        private val errBuf = ByteArrayOutputStream()

        data class Result(val status: Int, val bytesReceived: Long, val errorMessage: String)

        private var bytesReceived: Long = 0L

        @Synchronized
        fun onChunk(chunk: MuxCodec.RpcChunk) {
            if (chunk.code == MuxCodec.RPC_STATUS_OK) {
                if (chunk.data.isNotEmpty()) {
                    responseOut.write(chunk.data)
                    bytesReceived += chunk.data.size
                }
            } else if (chunk.data.isNotEmpty()) {
                errBuf.write(chunk.data)
            }
            if ((chunk.flags and MuxCodec.RPC_FLAG_FIN) != 0) {
                future.complete(
                    Result(
                        status = chunk.code,
                        bytesReceived = bytesReceived,
                        errorMessage = errBuf.toString(StandardCharsets.UTF_8.name()).trim(),
                    ),
                )
            }
        }
    }

    private class InboundGuestRpcState(
        val op: Int,
        private val sharesDir: File,
    ) {
        private val buffer = ByteArrayOutputStream()
        private var headerParsed = false
        private var shareFile: File? = null
        private var shareOut: FileOutputStream? = null
        private var shareDisplayName: String = ""
        private var expectedShareSize: Long = -1L
        private var shareWrittenBytes: Long = 0L
        var failedError: MuxHostRpcException? = null

        data class FinalizedShare(val file: File, val displayName: String)

        fun append(data: ByteArray) {
            if (failedError != null) return
            if (op == MuxCodec.RPC_OP_CLIPBOARD_WRITE && buffer.size() + data.size > MuxCodec.MAX_CLIPBOARD_BYTES) {
                throw MuxHostRpcException(
                    MuxCodec.RPC_STATUS_BAD_REQUEST,
                    "Clipboard payload exceeds ${MuxCodec.MAX_CLIPBOARD_BYTES} bytes",
                )
            }
            if (op != MuxCodec.RPC_OP_SHARE_FILE) {
                if (buffer.size() + data.size > MuxCodec.MAX_CLIPBOARD_BYTES + 4096) {
                    throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "RPC request body too large")
                }
                buffer.write(data)
                return
            }

            if (!headerParsed) {
                buffer.write(data)
                val bytes = buffer.toByteArray()
                val nlIndex = bytes.indexOf('\n'.code.toByte())
                if (nlIndex < 0) {
                    if (bytes.size > 8192) {
                        throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "SHARE_FILE header too large")
                    }
                    return
                }
                val headerLine = String(bytes, 0, nlIndex, StandardCharsets.UTF_8).trim()
                val hdr = try {
                    MuxCodec.json.decodeFromString<ShareFileHeader>(headerLine)
                } catch (_: Exception) {
                    throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "Malformed SHARE_FILE header")
                }
                val sanitizedName = sanitizeShareFileName(hdr.name)
                if (hdr.size > MuxCodec.MAX_SHARE_FILE_BYTES) {
                    throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "SHARE_FILE exceeds size limit")
                }
                sharesDir.mkdirs()
                val target = File(sharesDir, "${UUID.randomUUID()}-$sanitizedName")
                shareFile = target
                shareOut = FileOutputStream(target)
                shareDisplayName = sanitizedName
                expectedShareSize = hdr.size
                headerParsed = true
                val remainderStart = nlIndex + 1
                if (remainderStart < bytes.size) {
                    writeShareBytes(bytes, remainderStart, bytes.size - remainderStart)
                }
                buffer.reset()
            } else {
                writeShareBytes(data, 0, data.size)
            }
        }

        private fun writeShareBytes(data: ByteArray, offset: Int, len: Int) {
            if (len <= 0) return
            if (shareWrittenBytes + len > MuxCodec.MAX_SHARE_FILE_BYTES) {
                throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "SHARE_FILE exceeds size limit")
            }
            shareOut?.write(data, offset, len)
            shareWrittenBytes += len
        }

        fun bodyBytes(): ByteArray = buffer.toByteArray()

        fun finalizeShareFile(): FinalizedShare {
            if (!headerParsed || shareFile == null) {
                throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "Missing SHARE_FILE header")
            }
            shareOut?.flush()
            shareOut?.close()
            shareOut = null
            if (expectedShareSize >= 0L && shareWrittenBytes != expectedShareSize) {
                cleanupOnError()
                throw MuxHostRpcException(
                    MuxCodec.RPC_STATUS_BAD_REQUEST,
                    "SHARE_FILE size mismatch: expected $expectedShareSize, got $shareWrittenBytes",
                )
            }
            return FinalizedShare(shareFile!!, shareDisplayName)
        }

        fun cleanupOnError() {
            try {
                shareOut?.close()
            } catch (_: IOException) {
            }
            shareOut = null
            shareFile?.delete()
            shareFile = null
        }

        private fun sanitizeShareFileName(rawName: String): String {
            val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            if (base.isEmpty() || base == "." || base == ".." || base.contains('\u0000')) {
                throw MuxHostRpcException(MuxCodec.RPC_STATUS_BAD_REQUEST, "Invalid share filename")
            }
            return base
        }
    }

    companion object {
        const val PROXY_STATUS_OK: Int = 0x00
    }
}
