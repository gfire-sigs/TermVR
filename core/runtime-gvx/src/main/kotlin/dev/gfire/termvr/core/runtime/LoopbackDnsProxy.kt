package dev.gfire.termvr.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.os.CancellationSignal
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes raw RFC 1035 wire DNS queries against the active Android network.
 */
fun interface NetworkDnsBackend {
    /**
     * Resolves a raw DNS wire query within `timeoutMs`, or returns `null` when offline or failed.
     */
    fun rawQuery(query: ByteArray, timeoutMs: Long): ByteArray?
}

/**
 * Android `DnsResolver.getInstance().rawQuery` implementation bound to `ConnectivityManager.activeNetwork`.
 */
class AndroidNetworkDnsBackend(context: Context) : NetworkDnsBackend {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "termvr-dnsresolver-cb").apply { isDaemon = true }
    }

    override fun rawQuery(query: ByteArray, timeoutMs: Long): ByteArray? {
        val activeNetwork = connectivityManager?.activeNetwork ?: return null
        val future = CompletableFuture<ByteArray?>()
        val signal = CancellationSignal()
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                future.complete(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                future.complete(null)
            }
        }
        try {
            DnsResolver.getInstance().rawQuery(
                activeNetwork,
                query,
                DnsResolver.FLAG_EMPTY,
                callbackExecutor,
                signal,
                callback,
            )
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            signal.cancel()
            return null
        }
    }
}

/**
 * Serves UDP and TCP DNS queries on `127.0.0.1` via Android `DnsResolver` or proxied TCP DNS.
 */
class LoopbackDnsProxy(
    private val networkDnsBackend: NetworkDnsBackend,
    private val proxyBridge: LoopbackProxyBridge? = null,
    private val tcpDnsUpstreamHost: String = DEFAULT_PROXY_TCP_DNS_HOST,
    private val tcpDnsUpstreamPort: Int = DEFAULT_PROXY_TCP_DNS_PORT,
    private val queryTimeoutMs: Long = DEFAULT_QUERY_TIMEOUT_MS,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var udpSocket: DatagramSocket? = null

    @Volatile
    private var tcpSocket: ServerSocket? = null

    private val workerPool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "termvr-dns-worker").apply { isDaemon = true }
    }

    val boundPort: Int
        get() = tcpSocket?.localPort ?: -1

    /**
     * Binds both TCP and UDP listeners on `127.0.0.1:<requestedPort>` (or one shared ephemeral port).
     */
    fun start(requestedPort: Int = 0): Int {
        check(!closed.get()) { "LoopbackDnsProxy has been closed" }
        if (running.compareAndSet(false, true)) {
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val (tcp, udp) = bindDualLoopbackSockets(loopback, requestedPort)
            tcpSocket = tcp
            udpSocket = udp

            Thread({ udpLoop(udp) }, "termvr-dns-udp").apply {
                isDaemon = true
                start()
            }
            Thread({ tcpLoop(tcp) }, "termvr-dns-tcp").apply {
                isDaemon = true
                start()
            }
        }
        return boundPort
    }

    /**
     * Dispatches one raw RFC 1035 query and returns a valid wire response or `SERVFAIL`.
     */
    fun handleDnsQuery(query: ByteArray): ByteArray {
        if (query.size < DNS_HEADER_BYTES) {
            return ByteArray(0)
        }
        val bridge = proxyBridge
        val shouldTunnelViaProxy = bridge != null &&
            bridge.isUsingNonDirectUpstream &&
            bridge.currentPolicy.outboundProxy.routeDnsViaProxy

        val answer = if (shouldTunnelViaProxy) {
            queryTcpOverProxy(bridge!!, query)
        } else {
            networkDnsBackend.rawQuery(query, queryTimeoutMs)
        }
        return if (answer != null && answer.size >= DNS_HEADER_BYTES) {
            answer
        } else {
            buildServfailResponse(query)
        }
    }

    private fun queryTcpOverProxy(bridge: LoopbackProxyBridge, query: ByteArray): ByteArray? {
        if (query.size > 65535) return null
        return try {
            bridge.dialTarget(tcpDnsUpstreamHost, tcpDnsUpstreamPort, queryTimeoutMs.toInt()).use { sock ->
                sock.soTimeout = queryTimeoutMs.toInt()
                val out = sock.getOutputStream()
                val framed = ByteArray(2 + query.size)
                framed[0] = ((query.size ushr 8) and 0xFF).toByte()
                framed[1] = (query.size and 0xFF).toByte()
                System.arraycopy(query, 0, framed, 2, query.size)
                out.write(framed)
                out.flush()

                val dataIn = DataInputStream(sock.getInputStream())
                val respLen = dataIn.readUnsignedShort()
                if (respLen < DNS_HEADER_BYTES || respLen > MAX_TCP_DNS_BYTES) {
                    return null
                }
                val resp = ByteArray(respLen)
                dataIn.readFully(resp)
                resp
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun udpLoop(socket: DatagramSocket) {
        val buf = ByteArray(MAX_UDP_DNS_BYTES)
        while (running.get() && !socket.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                break
            }
            val queryCopy = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val replyAddr = packet.socketAddress
            workerPool.execute {
                val resp = handleDnsQuery(queryCopy)
                if (resp.isNotEmpty() && !socket.isClosed) {
                    try {
                        val outPacket = DatagramPacket(resp, resp.size, replyAddr)
                        socket.send(outPacket)
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun tcpLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            workerPool.execute {
                handleTcpClient(client)
            }
        }
    }

    private fun handleTcpClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = queryTimeoutMs.toInt()
                val dataIn = DataInputStream(sock.getInputStream())
                val out = sock.getOutputStream()
                while (running.get() && !sock.isClosed) {
                    val len = try {
                        dataIn.readUnsignedShort()
                    } catch (_: EOFException) {
                        break
                    }
                    if (len < DNS_HEADER_BYTES || len > MAX_TCP_DNS_BYTES) {
                        break
                    }
                    val query = ByteArray(len)
                    dataIn.readFully(query)
                    val resp = handleDnsQuery(query)
                    if (resp.isEmpty()) break
                    val framed = ByteArray(2 + resp.size)
                    framed[0] = ((resp.size ushr 8) and 0xFF).toByte()
                    framed[1] = (resp.size and 0xFF).toByte()
                    System.arraycopy(resp, 0, framed, 2, resp.size)
                    out.write(framed)
                    out.flush()
                }
            } catch (_: IOException) {
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        try {
            tcpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        tcpSocket = null
        workerPool.shutdownNow()
    }

    private fun bindDualLoopbackSockets(
        loopback: InetAddress,
        requestedPort: Int,
    ): Pair<ServerSocket, DatagramSocket> {
        if (requestedPort != 0) {
            val tcp = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, requestedPort))
            }
            try {
                val udp = DatagramSocket(InetSocketAddress(loopback, requestedPort))
                return tcp to udp
            } catch (e: IOException) {
                tcp.close()
                throw e
            }
        }

        var lastError: IOException? = null
        repeat(16) {
            val tcp = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(loopback, 0))
            }
            val port = tcp.localPort
            try {
                val udp = DatagramSocket(InetSocketAddress(loopback, port))
                return tcp to udp
            } catch (e: IOException) {
                lastError = e
                tcp.close()
            }
        }
        throw lastError ?: IOException("Failed to bind matching TCP and UDP loopback ports")
    }

    companion object {
        const val DEFAULT_QUERY_TIMEOUT_MS: Long = 3_500L
        const val DEFAULT_PROXY_TCP_DNS_HOST: String = "1.1.1.1"
        const val DEFAULT_PROXY_TCP_DNS_PORT: Int = 53
        private const val DNS_HEADER_BYTES = 12
        private const val MAX_UDP_DNS_BYTES = 4096
        private const val MAX_TCP_DNS_BYTES = 65535

        /**
         * Builds a minimal RFC 1035 `SERVFAIL` (`RCODE = 2`) reply preserving transaction ID and question.
         */
        fun buildServfailResponse(query: ByteArray): ByteArray {
            if (query.size < DNS_HEADER_BYTES) return ByteArray(0)
            val questionEnd = findQuestionEnd(query)
            val out = ByteArray(questionEnd)
            // Copy ID
            out[0] = query[0]
            out[1] = query[1]
            // QR=1, preserve OPCODE and RD, clear AA/TC
            out[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
            // RA=1, Z=0, RCODE=2 (SERVFAIL)
            out[3] = 0x82.toByte()

            if (questionEnd > DNS_HEADER_BYTES) {
                // Copy QDCOUNT = 1 (or original QDCOUNT)
                out[4] = query[4]
                out[5] = query[5]
                System.arraycopy(query, DNS_HEADER_BYTES, out, DNS_HEADER_BYTES, questionEnd - DNS_HEADER_BYTES)
            } else {
                out[4] = 0
                out[5] = 0
            }
            // ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0 (already 0)
            return out
        }

        private fun findQuestionEnd(query: ByteArray): Int {
            val qdCount = ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF)
            if (qdCount == 0 || query.size == DNS_HEADER_BYTES) {
                return DNS_HEADER_BYTES
            }
            var idx = DNS_HEADER_BYTES
            repeat(qdCount) {
                while (idx < query.size) {
                    val labelLen = query[idx].toInt() and 0xFF
                    if (labelLen == 0) {
                        idx += 1
                        break
                    }
                    if ((labelLen and 0xC0) == 0xC0) {
                        idx += 2
                        break
                    }
                    idx += 1 + labelLen
                    if (idx > query.size) return DNS_HEADER_BYTES
                }
                idx += 4 // QTYPE + QCLASS
                if (idx > query.size) return DNS_HEADER_BYTES
            }
            return idx
        }
    }
}
