package dev.gfire.termvr.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
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
import java.util.concurrent.RejectedExecutionException
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

    /**
     * Resolves a raw DNS wire query over TCP when an upstream UDP answer sets the `TC` bit.
     */
    fun rawTcpQuery(query: ByteArray, timeoutMs: Long): ByteArray? = null
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
        val cm = connectivityManager ?: return null
        val initialNetwork = cm.activeNetwork ?: return null
        val startedNs = System.nanoTime()
        val firstAttempt = executeOnNetwork(initialNetwork, query, timeoutMs)
        if (firstAttempt != null) {
            return firstAttempt
        }
        val currentNetwork = cm.activeNetwork
        if (currentNetwork != null && currentNetwork != initialNetwork) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
            val remainingMs = (timeoutMs - elapsedMs).coerceAtLeast(250L)
            return executeOnNetwork(currentNetwork, query, remainingMs)
        }
        return null
    }

    override fun rawTcpQuery(query: ByteArray, timeoutMs: Long): ByteArray? {
        if (query.size !in LoopbackDnsProxy.DNS_HEADER_BYTES..LoopbackDnsProxy.MAX_TCP_DNS_BYTES) {
            return null
        }
        val cm = connectivityManager ?: return null
        val activeNetwork = cm.activeNetwork ?: return null
        val dnsServers = cm.getLinkProperties(activeNetwork)?.dnsServers.orEmpty()
        if (dnsServers.isEmpty()) return null

        val timeoutInt = timeoutMs.coerceIn(250L, 15_000L).toInt()
        for (server in dnsServers) {
            val answer = querySingleTcpDnsServer(activeNetwork, server, query, timeoutInt)
            if (answer != null && answer.size >= LoopbackDnsProxy.DNS_HEADER_BYTES) {
                return answer
            }
        }
        return null
    }

    private fun executeOnNetwork(network: Network, query: ByteArray, timeoutMs: Long): ByteArray? {
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
        return try {
            DnsResolver.getInstance().rawQuery(
                network,
                query,
                DnsResolver.FLAG_EMPTY,
                callbackExecutor,
                signal,
                callback,
            )
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            signal.cancel()
            null
        }
    }

    private fun querySingleTcpDnsServer(
        network: Network,
        server: InetAddress,
        query: ByteArray,
        timeoutMs: Int,
    ): ByteArray? {
        return try {
            network.socketFactory.createSocket().use { sock ->
                sock.connect(InetSocketAddress(server, LoopbackDnsProxy.DEFAULT_PROXY_TCP_DNS_PORT), timeoutMs)
                sock.soTimeout = timeoutMs
                LoopbackDnsProxy.exchangeTcpDnsFrame(sock, query)
            }
        } catch (_: Exception) {
            null
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
    private val tcpFallbackBackend: NetworkDnsBackend? = null,
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

        var answer = try {
            if (shouldTunnelViaProxy) {
                queryTcpOverProxy(bridge!!, query)
            } else {
                networkDnsBackend.rawQuery(query, queryTimeoutMs)
            }
        } catch (_: Exception) {
            null
        }

        if (!shouldTunnelViaProxy && answer != null && isTruncatedResponse(answer)) {
            val tcpRetry = try {
                tcpFallbackBackend?.rawQuery(query, queryTimeoutMs)
                    ?: networkDnsBackend.rawTcpQuery(query, queryTimeoutMs)
            } catch (_: Exception) {
                null
            }
            if (tcpRetry != null && isValidResponseForQuery(query, tcpRetry)) {
                answer = tcpRetry
            }
        }

        return if (answer != null && isValidResponseForQuery(query, answer)) {
            answer
        } else {
            buildServfailResponse(query)
        }
    }

    private fun queryTcpOverProxy(bridge: LoopbackProxyBridge, query: ByteArray): ByteArray? {
        if (query.size > MAX_TCP_DNS_BYTES) return null
        val timeoutInt = queryTimeoutMs.coerceIn(250L, 30_000L).toInt()
        return try {
            bridge.dialTarget(tcpDnsUpstreamHost, tcpDnsUpstreamPort, timeoutInt).use { sock ->
                sock.soTimeout = timeoutInt
                exchangeTcpDnsFrame(sock, query)
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
                if (!running.get() || socket.isClosed) break
                continue
            }
            if (packet.length < DNS_HEADER_BYTES) {
                continue
            }
            val queryCopy = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val replyAddr = packet.socketAddress
            try {
                workerPool.execute {
                    try {
                        val fullResp = handleDnsQuery(queryCopy)
                        val udpResp = fitResponseToUdpPayload(queryCopy, fullResp)
                        if (udpResp.isNotEmpty() && !socket.isClosed) {
                            socket.send(DatagramPacket(udpResp, udpResp.size, replyAddr))
                        }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: RejectedExecutionException) {
                break
            }
        }
    }

    private fun tcpLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                if (!running.get() || server.isClosed) break
                continue
            }
            try {
                workerPool.execute {
                    handleTcpClient(client)
                }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
                break
            }
        }
    }

    private fun handleTcpClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = queryTimeoutMs.coerceIn(250L, 30_000L).toInt()
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
                    if (resp.isEmpty() || resp.size > MAX_TCP_DNS_BYTES) break
                    val framed = ByteArray(2 + resp.size)
                    framed[0] = ((resp.size ushr 8) and 0xFF).toByte()
                    framed[1] = (resp.size and 0xFF).toByte()
                    System.arraycopy(resp, 0, framed, 2, resp.size)
                    out.write(framed)
                    out.flush()
                }
            } catch (_: Exception) {
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
        internal const val DNS_HEADER_BYTES = 12
        internal const val STANDARD_UDP_DNS_BYTES = 512
        internal const val MAX_UDP_DNS_BYTES = 4096
        internal const val MAX_TCP_DNS_BYTES = 65535
        private const val MAX_QUESTION_COUNT = 16
        private const val DNS_TYPE_OPT = 41

        internal fun exchangeTcpDnsFrame(sock: Socket, query: ByteArray): ByteArray? {
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
            return resp
        }

        internal fun isTruncatedResponse(response: ByteArray): Boolean =
            response.size >= DNS_HEADER_BYTES && (response[2].toInt() and 0x02) != 0

        internal fun isValidResponseForQuery(query: ByteArray, response: ByteArray): Boolean {
            if (query.size < DNS_HEADER_BYTES || response.size < DNS_HEADER_BYTES) return false
            return response[0] == query[0] && response[1] == query[1]
        }

        /**
         * Enforces RFC 1035 / EDNS0 UDP payload ceilings by emitting a `TC = 1` stub reply when
         * an untruncated response exceeds the client's advertised UDP buffer size.
         */
        internal fun fitResponseToUdpPayload(query: ByteArray, response: ByteArray): ByteArray {
            if (response.isEmpty()) return response
            val maxUdpBytes = parseClientMaxUdpPayload(query)
            if (response.size <= maxUdpBytes) {
                return response
            }
            val questionEnd = findQuestionEnd(query)
            val stubLen = if (questionEnd <= maxUdpBytes) questionEnd else DNS_HEADER_BYTES
            val out = ByteArray(stubLen)
            out[0] = response[0]
            out[1] = response[1]
            // QR=1, preserve OPCODE/AA/RD, set TC=1 (0x02) so stub resolvers retry over TCP.
            out[2] = ((response[2].toInt() and 0x7D) or 0x82).toByte()
            // Preserve RA and RCODE (lower 4 bits), clear Z/AD/CD bits.
            out[3] = ((response[3].toInt() and 0x8F) or 0x80).toByte()
            if (stubLen > DNS_HEADER_BYTES) {
                out[4] = query[4]
                out[5] = query[5]
                System.arraycopy(query, DNS_HEADER_BYTES, out, DNS_HEADER_BYTES, stubLen - DNS_HEADER_BYTES)
            }
            return out
        }

        /**
         * Parses an EDNS0 `OPT` record (`TYPE = 41`) in the query additional section if present,
         * defaulting to 512 bytes per RFC 1035 Section 4.2.1.
         */
        internal fun parseClientMaxUdpPayload(query: ByteArray): Int {
            if (query.size < DNS_HEADER_BYTES) return STANDARD_UDP_DNS_BYTES
            val anCount = readU16(query, 6)
            val nsCount = readU16(query, 8)
            val arCount = readU16(query, 10)
            if (anCount != 0 || nsCount != 0 || arCount == 0 || arCount > 8) {
                return STANDARD_UDP_DNS_BYTES
            }
            var idx = findQuestionEnd(query)
            if (idx <= DNS_HEADER_BYTES && readU16(query, 4) > 0) {
                return STANDARD_UDP_DNS_BYTES
            }
            repeat(arCount) {
                val nameEnd = skipDnsName(query, idx) ?: return STANDARD_UDP_DNS_BYTES
                if (nameEnd + 10 > query.size) return STANDARD_UDP_DNS_BYTES
                val rrType = readU16(query, nameEnd)
                val rrClass = readU16(query, nameEnd + 2)
                val rdLen = readU16(query, nameEnd + 8)
                val nextIdx = nameEnd + 10 + rdLen
                if (nextIdx > query.size) return STANDARD_UDP_DNS_BYTES
                if (rrType == DNS_TYPE_OPT) {
                    return rrClass.coerceIn(STANDARD_UDP_DNS_BYTES, MAX_UDP_DNS_BYTES)
                }
                idx = nextIdx
            }
            return STANDARD_UDP_DNS_BYTES
        }

        /**
         * Builds a minimal RFC 1035 `SERVFAIL` (`RCODE = 2`) reply preserving transaction ID and question.
         */
        fun buildServfailResponse(query: ByteArray): ByteArray {
            if (query.size < DNS_HEADER_BYTES) return ByteArray(0)
            val questionEnd = findQuestionEnd(query)
            val out = ByteArray(questionEnd)
            out[0] = query[0]
            out[1] = query[1]
            // QR=1, preserve OPCODE and RD, clear AA/TC.
            out[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
            // RA=1, Z=0, RCODE=2 (SERVFAIL).
            out[3] = 0x82.toByte()

            if (questionEnd > DNS_HEADER_BYTES) {
                out[4] = query[4]
                out[5] = query[5]
                System.arraycopy(query, DNS_HEADER_BYTES, out, DNS_HEADER_BYTES, questionEnd - DNS_HEADER_BYTES)
            } else {
                out[4] = 0
                out[5] = 0
            }
            return out
        }

        private fun findQuestionEnd(query: ByteArray): Int {
            val qdCount = readU16(query, 4)
            if (qdCount == 0 || qdCount > MAX_QUESTION_COUNT || query.size <= DNS_HEADER_BYTES) {
                return DNS_HEADER_BYTES
            }
            var idx = DNS_HEADER_BYTES
            repeat(qdCount) {
                val nameEnd = skipDnsName(query, idx) ?: return DNS_HEADER_BYTES
                idx = nameEnd + 4 // QTYPE + QCLASS
                if (idx > query.size) return DNS_HEADER_BYTES
            }
            return idx
        }

        private fun skipDnsName(packet: ByteArray, startIdx: Int): Int? {
            var idx = startIdx
            var labelsSeen = 0
            while (idx < packet.size) {
                if (labelsSeen++ > 128) return null
                val labelLen = packet[idx].toInt() and 0xFF
                if (labelLen == 0) {
                    return idx + 1
                }
                val topBits = labelLen and 0xC0
                if (topBits == 0xC0) {
                    return if (idx + 2 <= packet.size) idx + 2 else null
                }
                if (topBits != 0) {
                    // RFC 1035 forbids 0x40 and 0x80 extended label types in standard queries.
                    return null
                }
                idx += 1 + labelLen
                if (idx > packet.size) return null
            }
            return null
        }

        private fun readU16(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }
}
