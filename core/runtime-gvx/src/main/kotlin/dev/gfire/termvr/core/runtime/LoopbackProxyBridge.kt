package dev.gfire.termvr.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import dev.gfire.termvr.core.data.SecretStore
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Represents Android's active system proxy endpoint and bypass list.
 */
data class SystemProxyInfo(
    val host: String,
    val port: Int,
    val exclusionList: List<String> = emptyList(),
)

/**
 * Resolves the host Android system HTTP proxy dynamically.
 */
fun interface SystemProxyResolver {
    fun resolveDefaultProxy(): SystemProxyInfo?
}

/**
 * Android implementation querying `ConnectivityManager.getDefaultProxy()`.
 */
class AndroidSystemProxyResolver(context: Context) : SystemProxyResolver {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun resolveDefaultProxy(): SystemProxyInfo? {
        val info = connectivityManager?.defaultProxy ?: return null
        val host = info.host?.trim().orEmpty()
        if (host.isEmpty() || info.port !in 1..65535) {
            return null
        }
        return SystemProxyInfo(
            host = host,
            port = info.port,
            exclusionList = info.exclusionList?.toList().orEmpty(),
        )
    }
}

/**
 * Summarizes the active proxy configuration and LAN isolation policy for `LoopbackProxyBridge`.
 */
data class ProxyBridgePolicy(
    val outboundProxy: OutboundProxyConfig = OutboundProxyConfig(),
    val isolateLanEgress: Boolean = false,
)

/**
 * Result of `LoopbackProxyBridge.testProxy` connectivity and handshake measurement.
 */
data class ProxyTestResult(
    val success: Boolean,
    val latencyMillis: Long,
    val effectiveMode: OutboundProxyMode,
    val detail: String,
)

/**
 * Outcome of dialing a guest `PROXY_CONNECT` stream through `LoopbackProxyBridge`.
 */
sealed interface ProxyDialOutcome {
    data class Connected(val socket: Socket) : ProxyDialOutcome
    data class DirectBypass(val reason: String) : ProxyDialOutcome
    data class Rejected(val statusCode: Int, val message: String) : ProxyDialOutcome
}

/**
 * Signals that `isolateLanEgress` blocked a loopback, RFC1918, link-local, or ULA destination.
 */
class LanIsolationBlockedException(message: String) : IOException(message)

/**
 * Hosts an authenticated host HTTP/CONNECT proxy on `127.0.0.1` and dials upstream proxies for `PROXY_CONNECT`.
 */
class LoopbackProxyBridge(
    private val secretStore: SecretStore,
    private val systemProxyResolver: SystemProxyResolver = SystemProxyResolver { null },
    private val tlsSocketFactory: SSLSocketFactory = defaultTls13SocketFactory(),
    private val dnsLookup: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {

    private val policyRef = AtomicReference(ProxyBridgePolicy())
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val authToken: String = generate128BitHexToken()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val workerPool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "termvr-proxy-worker").apply { isDaemon = true }
    }

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    val currentPolicy: ProxyBridgePolicy
        get() = policyRef.get()

    val isUsingNonDirectUpstream: Boolean
        get() {
            val policy = policyRef.get()
            return when (policy.outboundProxy.mode) {
                OutboundProxyMode.DIRECT -> false
                OutboundProxyMode.CUSTOM -> policy.outboundProxy.customEndpoint != null
                OutboundProxyMode.ANDROID_SYSTEM -> systemProxyResolver.resolveDefaultProxy() != null
            }
        }

    /**
     * Atomically swaps outbound proxy mode, endpoint, bypass list, and LAN isolation in under 1 ms.
     */
    fun updatePolicy(outboundProxy: OutboundProxyConfig, isolateLanEgress: Boolean) {
        policyRef.set(
            ProxyBridgePolicy(
                outboundProxy = outboundProxy,
                isolateLanEgress = isolateLanEgress,
            ),
        )
    }

    fun hostBridgeEnv(): HostProxyBridgeEnv {
        val port = boundPort
        check(port in 1..65535) { "LoopbackProxyBridge is not started" }
        return HostProxyBridgeEnv(
            port = port,
            token = authToken,
            noProxyEntries = policyRef.get().outboundProxy.noProxyEntries,
        )
    }

    /**
     * Binds `127.0.0.1:<requestedPort>` and starts serving authenticated HTTP/CONNECT requests.
     */
    fun start(requestedPort: Int = 0): Int {
        check(!closed.get()) { "LoopbackProxyBridge has been closed" }
        if (running.compareAndSet(false, true)) {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), requestedPort))
            serverSocket = ss
            acceptThread = Thread({ acceptLoop(ss) }, "termvr-proxy-accept").apply {
                isDaemon = true
                start()
            }
        }
        return boundPort
    }

    /**
     * Dials an outbound TCP stream for guest `PROXY_CONNECT` frames using the live policy.
     */
    fun dialForMuxStream(
        dstHost: String,
        dstPort: Int,
        allowDirectBypassCode: Boolean = false,
    ): ProxyDialOutcome {
        val cleanHost = normalizeTargetHost(dstHost)
        if (cleanHost.isEmpty() || dstPort !in 1..65535) {
            return ProxyDialOutcome.Rejected(STATUS_BAD_REQUEST, "invalid destination $dstHost:$dstPort")
        }
        val policy = policyRef.get()
        if (policy.isolateLanEgress) {
            try {
                enforceLiteralLanIsolation(cleanHost)
            } catch (e: LanIsolationBlockedException) {
                return ProxyDialOutcome.Rejected(STATUS_NOT_ALLOWED, e.message ?: "LAN egress blocked")
            }
        }

        val route = resolveRoute(cleanHost, policy)
        if (route is ResolvedRoute.Direct && allowDirectBypassCode && !policy.isolateLanEgress) {
            return ProxyDialOutcome.DirectBypass("direct route")
        }

        return try {
            val socket = dialResolvedRoute(cleanHost, dstPort, route, policy.isolateLanEgress, connectTimeoutMs)
            ProxyDialOutcome.Connected(socket)
        } catch (e: LanIsolationBlockedException) {
            ProxyDialOutcome.Rejected(STATUS_NOT_ALLOWED, e.message ?: "LAN egress blocked")
        } catch (e: IOException) {
            ProxyDialOutcome.Rejected(STATUS_GENERAL_FAILURE, e.message ?: "proxy dial failed")
        }
    }

    /**
     * Dials a target through the currently active bridge route (direct or upstream proxy).
     */
    fun dialTarget(dstHost: String, dstPort: Int, timeoutMs: Int = connectTimeoutMs): Socket {
        val cleanHost = normalizeTargetHost(dstHost)
        require(cleanHost.isNotEmpty() && dstPort in 1..65535) {
            "Invalid target $dstHost:$dstPort"
        }
        val policy = policyRef.get()
        if (policy.isolateLanEgress) {
            enforceLiteralLanIsolation(cleanHost)
        }
        val route = resolveRoute(cleanHost, policy)
        return dialResolvedRoute(cleanHost, dstPort, route, policy.isolateLanEgress, timeoutMs)
    }

    /**
     * Measures upstream proxy handshake or direct TCP dial latency without altering live state.
     */
    suspend fun testProxy(
        config: OutboundProxyConfig,
        probeHost: String = "example.com",
        probePort: Int = 443,
        timeoutMs: Int = 5_000,
    ): ProxyTestResult = withContext(Dispatchers.IO) {
        val startedNs = System.nanoTime()
        val tempPolicy = ProxyBridgePolicy(outboundProxy = config, isolateLanEgress = false)
        val route = resolveRoute(probeHost, tempPolicy)
        try {
            dialResolvedRoute(probeHost, probePort, route, isolateLanEgress = false, timeoutMs = timeoutMs).use {
                val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L)
                val desc = when (route) {
                    is ResolvedRoute.Direct -> "Direct TCP handshake succeeded"
                    is ResolvedRoute.Upstream -> "${route.endpoint.protocol.name} proxy handshake succeeded"
                }
                ProxyTestResult(
                    success = true,
                    latencyMillis = elapsedMs,
                    effectiveMode = config.mode,
                    detail = desc,
                )
            }
        } catch (e: Exception) {
            val elapsedMs = ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(0L)
            ProxyTestResult(
                success = false,
                latencyMillis = elapsedMs,
                effectiveMode = config.mode,
                detail = e.message ?: "Proxy probe failed",
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        workerPool.shutdownNow()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: IOException) {
                break
            }
            workerPool.execute {
                handleHttpProxyClient(client)
            }
        }
    }

    private fun handleHttpProxyClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = readTimeoutMs
                val input = BufferedInputStream(sock.getInputStream(), 16 * 1024)
                val output = sock.getOutputStream()

                val requestLine = readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: return
                if (requestLine.isEmpty()) return
                val parts = requestLine.split(' ')
                if (parts.size < 3) {
                    sendHttpError(output, 400, "Bad Request")
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val targetToken = parts[1]
                val version = parts[2]

                val headers = readHttpHeaders(input)
                val proxyAuth = headers.firstOrNull {
                    it.first.equals("Proxy-Authorization", ignoreCase = true)
                }?.second
                if (!verifyBasicProxyAuth(proxyAuth)) {
                    output.write(
                        ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                            "Proxy-Authenticate: Basic realm=\"TermVR\"\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII),
                    )
                    output.flush()
                    return
                }

                if (method == "CONNECT") {
                    val (host, port) = parseHostPort(targetToken, defaultPort = -1) ?: run {
                        sendHttpError(output, 400, "Invalid CONNECT target")
                        return
                    }
                    val upstream = try {
                        dialTarget(host, port, connectTimeoutMs)
                    } catch (e: LanIsolationBlockedException) {
                        sendHttpError(output, 403, e.message ?: "LAN egress blocked")
                        return
                    } catch (e: IOException) {
                        sendHttpError(output, 502, e.message ?: "Upstream dial failed")
                        return
                    }

                    upstream.use { up ->
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        output.flush()
                        sock.soTimeout = 0
                        up.soTimeout = 0
                        relayBidirectional(input, output, up.getInputStream(), up.getOutputStream())
                    }
                } else {
                    val uri = try {
                        URI(targetToken)
                    } catch (_: Exception) {
                        sendHttpError(output, 400, "Invalid request URI")
                        return
                    }
                    val host = uri.host ?: run {
                        sendHttpError(output, 400, "Missing URI host")
                        return
                    }
                    val port = if (uri.port > 0) uri.port else 80
                    val originPath = buildString {
                        append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                        if (!uri.rawQuery.isNullOrEmpty()) {
                            append('?').append(uri.rawQuery)
                        }
                    }

                    val upstream = try {
                        dialTarget(host, port, connectTimeoutMs)
                    } catch (e: LanIsolationBlockedException) {
                        sendHttpError(output, 403, e.message ?: "LAN egress blocked")
                        return
                    } catch (e: IOException) {
                        sendHttpError(output, 502, e.message ?: "Upstream dial failed")
                        return
                    }

                    upstream.use { up ->
                        val upOut = up.getOutputStream()
                        val reqBuilder = StringBuilder()
                        reqBuilder.append(method).append(' ').append(originPath).append(' ').append(version).append("\r\n")
                        var hasHost = false
                        for ((name, value) in headers) {
                            if (name.equals("Proxy-Authorization", ignoreCase = true) ||
                                name.equals("Proxy-Connection", ignoreCase = true)
                            ) {
                                continue
                            }
                            if (name.equals("Host", ignoreCase = true)) {
                                hasHost = true
                            }
                            reqBuilder.append(name).append(": ").append(value).append("\r\n")
                        }
                        if (!hasHost) {
                            reqBuilder.append("Host: ").append(formatHostHeader(host, port, 80)).append("\r\n")
                        }
                        reqBuilder.append("\r\n")
                        upOut.write(reqBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1))
                        upOut.flush()

                        sock.soTimeout = 0
                        up.soTimeout = 0
                        relayBidirectional(input, output, up.getInputStream(), upOut)
                    }
                }
            } catch (_: IOException) {
                // Socket closed or reset during relay.
            }
        }
    }

    private fun verifyBasicProxyAuth(headerValue: String?): Boolean {
        if (headerValue.isNullOrBlank()) return false
        val trimmed = headerValue.trim()
        if (!trimmed.startsWith("Basic ", ignoreCase = true)) return false
        val b64 = trimmed.substring(6).trim()
        val decoded = try {
            Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = "termvr:$authToken".toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(decoded, expected)
    }

    private fun resolveRoute(targetHost: String, policy: ProxyBridgePolicy): ResolvedRoute {
        val outbound = policy.outboundProxy
        if (isNoProxyTarget(targetHost, outbound.noProxyEntries)) {
            return ResolvedRoute.Direct
        }
        return when (outbound.mode) {
            OutboundProxyMode.DIRECT -> ResolvedRoute.Direct
            OutboundProxyMode.ANDROID_SYSTEM -> {
                val sys = systemProxyResolver.resolveDefaultProxy()
                if (sys == null || isNoProxyTarget(targetHost, sys.exclusionList)) {
                    ResolvedRoute.Direct
                } else {
                    ResolvedRoute.Upstream(
                        ProxyEndpointSpec(
                            protocol = ProxyProtocol.HTTP,
                            host = sys.host,
                            port = sys.port,
                        ),
                    )
                }
            }

            OutboundProxyMode.CUSTOM -> {
                val endpoint = outbound.customEndpoint ?: return ResolvedRoute.Direct
                ResolvedRoute.Upstream(endpoint)
            }
        }
    }

    private fun dialResolvedRoute(
        targetHost: String,
        targetPort: Int,
        route: ResolvedRoute,
        isolateLanEgress: Boolean,
        timeoutMs: Int,
    ): Socket {
        return when (route) {
            is ResolvedRoute.Direct -> dialDirect(targetHost, targetPort, isolateLanEgress, timeoutMs)
            is ResolvedRoute.Upstream -> dialViaUpstream(route.endpoint, targetHost, targetPort, timeoutMs)
        }
    }

    private fun dialDirect(
        targetHost: String,
        targetPort: Int,
        isolateLanEgress: Boolean,
        timeoutMs: Int,
    ): Socket {
        val addrs = dnsLookup(targetHost)
        if (addrs.isEmpty()) {
            throw IOException("DNS resolution returned no addresses for $targetHost")
        }
        if (isolateLanEgress) {
            for (addr in addrs) {
                if (isBlockedLanAddress(addr)) {
                    throw LanIsolationBlockedException("Target $targetHost resolved to blocked LAN address ${addr.hostAddress}")
                }
            }
        }
        val selected = addrs.first()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(selected, targetPort), timeoutMs)
            return socket
        } catch (e: IOException) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }

    private fun dialViaUpstream(
        endpoint: ProxyEndpointSpec,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int,
    ): Socket {
        val password = endpoint.credentialKeyAlias?.let { alias ->
            secretStore.getSecret(alias)
        }
        try {
            return when (endpoint.protocol) {
                ProxyProtocol.HTTP -> dialHttpConnect(
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    password = password,
                    useTls = false,
                    timeoutMs = timeoutMs,
                )

                ProxyProtocol.HTTPS -> dialHttpConnect(
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    password = password,
                    useTls = true,
                    timeoutMs = timeoutMs,
                )

                ProxyProtocol.SOCKS5 -> dialSocks5(
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    password = password,
                    timeoutMs = timeoutMs,
                )

                ProxyProtocol.SOCKS4 -> dialSocks4(
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    socks4a = false,
                    timeoutMs = timeoutMs,
                )

                ProxyProtocol.SOCKS4A -> dialSocks4(
                    endpoint = endpoint,
                    targetHost = targetHost,
                    targetPort = targetPort,
                    socks4a = true,
                    timeoutMs = timeoutMs,
                )
            }
        } finally {
            if (password != null) {
                Arrays.fill(password, '\u0000')
            }
        }
    }

    private fun dialHttpConnect(
        endpoint: ProxyEndpointSpec,
        targetHost: String,
        targetPort: Int,
        password: CharArray?,
        useTls: Boolean,
        timeoutMs: Int,
    ): Socket {
        val rawSocket = Socket()
        var activeSocket: Socket = rawSocket
        try {
            rawSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            rawSocket.soTimeout = timeoutMs

            if (useTls) {
                val sniHost = (endpoint.tlsServerName ?: endpoint.host).trim('[', ']')
                val ssl = tlsSocketFactory.createSocket(
                    rawSocket,
                    sniHost,
                    endpoint.port,
                    true,
                ) as SSLSocket
                if (sniHost.isNotEmpty() && !isIpLiteral(sniHost)) {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHost))
                    ssl.sslParameters = params
                }
                ssl.startHandshake()
                activeSocket = ssl
            }

            val authority = formatAuthority(targetHost, targetPort)
            val req = StringBuilder()
            req.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            req.append("Host: ").append(authority).append("\r\n")
            if (endpoint.username != null || password != null) {
                val user = endpoint.username.orEmpty()
                val passStr = if (password != null) String(password) else ""
                val creds = "$user:$passStr".toByteArray(StandardCharsets.UTF_8)
                val b64 = Base64.getEncoder().encodeToString(creds)
                Arrays.fill(creds, 0.toByte())
                req.append("Proxy-Authorization: Basic ").append(b64).append("\r\n")
            }
            req.append("\r\n")

            val out = activeSocket.getOutputStream()
            out.write(req.toString().toByteArray(StandardCharsets.ISO_8859_1))
            out.flush()

            val input = activeSocket.getInputStream()
            val statusLine = readAsciiLine(input, MAX_HTTP_LINE_BYTES)
                ?: throw EOFException("Upstream proxy closed connection during CONNECT")
            val statusParts = statusLine.split(' ', limit = 3)
            val statusCode = statusParts.getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Malformed HTTP proxy status line: $statusLine")
            // Drain remaining response headers.
            while (true) {
                val line = readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: break
                if (line.isEmpty()) break
            }
            if (statusCode !in 200..299) {
                throw IOException("Upstream HTTP CONNECT rejected with status $statusCode")
            }
            activeSocket.soTimeout = 0
            return activeSocket
        } catch (e: Exception) {
            try {
                activeSocket.close()
            } catch (_: IOException) {
            }
            throw (e as? IOException ?: IOException("HTTP CONNECT failed: ${e.message}", e))
        }
    }

    private fun dialSocks5(
        endpoint: ProxyEndpointSpec,
        targetHost: String,
        targetPort: Int,
        password: CharArray?,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val hasAuth = endpoint.username != null || password != null
            val greeting = if (hasAuth) {
                byteArrayOf(0x05, 0x02, 0x00, 0x02)
            } else {
                byteArrayOf(0x05, 0x01, 0x00)
            }
            output.write(greeting)
            output.flush()

            val methodReply = readExactBytes(input, 2)
            if (methodReply[0] != 0x05.toByte()) {
                throw IOException("Invalid SOCKS5 version: ${methodReply[0]}")
            }
            val selectedMethod = methodReply[1].toInt() and 0xFF
            if (selectedMethod == 0xFF) {
                throw IOException("SOCKS5 proxy rejected all authentication methods")
            }
            if (selectedMethod == 0x02) {
                val userBytes = (endpoint.username.orEmpty()).toByteArray(StandardCharsets.UTF_8)
                val passBytes = if (password != null) String(password).toByteArray(StandardCharsets.UTF_8) else ByteArray(0)
                try {
                    if (userBytes.size > 255 || passBytes.size > 255) {
                        throw IOException("SOCKS5 username or password exceeds 255 bytes")
                    }
                    val authPacket = ByteArray(3 + userBytes.size + passBytes.size)
                    authPacket[0] = 0x01
                    authPacket[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, authPacket, 2, userBytes.size)
                    authPacket[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, authPacket, 3 + userBytes.size, passBytes.size)
                    output.write(authPacket)
                    output.flush()
                    Arrays.fill(authPacket, 0.toByte())
                } finally {
                    Arrays.fill(passBytes, 0.toByte())
                }
                val authReply = readExactBytes(input, 2)
                if (authReply[1] != 0x00.toByte()) {
                    throw IOException("SOCKS5 username/password authentication failed (status=${authReply[1]})")
                }
            } else if (selectedMethod != 0x00) {
                throw IOException("Unsupported SOCKS5 auth method: $selectedMethod")
            }

            val reqOut = ByteArrayOutputStream()
            reqOut.write(0x05)
            reqOut.write(0x01) // CONNECT
            reqOut.write(0x00) // RSV

            val literalAddr = parseIpLiteralOrNull(targetHost)
            when (literalAddr) {
                is Inet4Address -> {
                    reqOut.write(0x01)
                    reqOut.write(literalAddr.address)
                }

                is Inet6Address -> {
                    reqOut.write(0x04)
                    reqOut.write(literalAddr.address)
                }

                else -> {
                    val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
                    if (hostBytes.isEmpty() || hostBytes.size > 255) {
                        throw IOException("SOCKS5 target domain length out of range: ${hostBytes.size}")
                    }
                    reqOut.write(0x03)
                    reqOut.write(hostBytes.size)
                    reqOut.write(hostBytes)
                }
            }
            reqOut.write((targetPort ushr 8) and 0xFF)
            reqOut.write(targetPort and 0xFF)
            output.write(reqOut.toByteArray())
            output.flush()

            val respHdr = readExactBytes(input, 4)
            if (respHdr[0] != 0x05.toByte()) {
                throw IOException("Invalid SOCKS5 reply version: ${respHdr[0]}")
            }
            val atyp = respHdr[3].toInt() and 0xFF
            when (atyp) {
                0x01 -> readExactBytes(input, 4 + 2)
                0x04 -> readExactBytes(input, 16 + 2)
                0x03 -> {
                    val lenByte = readExactBytes(input, 1)[0].toInt() and 0xFF
                    readExactBytes(input, lenByte + 2)
                }

                else -> throw IOException("Invalid SOCKS5 reply ATYP: $atyp")
            }
            val rep = respHdr[1].toInt() and 0xFF
            if (rep != 0x00) {
                throw IOException("SOCKS5 CONNECT failed with reply code $rep")
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw (e as? IOException ?: IOException("SOCKS5 dial failed: ${e.message}", e))
        }
    }

    private fun dialSocks4(
        endpoint: ProxyEndpointSpec,
        targetHost: String,
        targetPort: Int,
        socks4a: Boolean,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            val literalV4 = parseIpLiteralOrNull(targetHost) as? Inet4Address
            val resolvedV4 = if (!socks4a && literalV4 == null) {
                dnsLookup(targetHost).filterIsInstance<Inet4Address>().firstOrNull()
                    ?: throw IOException("SOCKS4 requires IPv4 address for $targetHost")
            } else {
                literalV4
            }

            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val userBytes = (endpoint.username.orEmpty()).toByteArray(StandardCharsets.UTF_8)
            val req = ByteArrayOutputStream()
            req.write(0x04) // VN
            req.write(0x01) // CD = CONNECT
            req.write((targetPort ushr 8) and 0xFF)
            req.write(targetPort and 0xFF)
            if (resolvedV4 != null) {
                req.write(resolvedV4.address)
                req.write(userBytes)
                req.write(0x00)
            } else {
                // SOCKS4A sentinel IP 0.0.0.1 + NUL-terminated hostname.
                req.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                req.write(userBytes)
                req.write(0x00)
                req.write(targetHost.toByteArray(StandardCharsets.UTF_8))
                req.write(0x00)
            }
            output.write(req.toByteArray())
            output.flush()

            val reply = readExactBytes(input, 8)
            if ((reply[1].toInt() and 0xFF) != 0x5A) {
                throw IOException("SOCKS4/4A CONNECT rejected with status ${reply[1].toInt() and 0xFF}")
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
            }
            throw (e as? IOException ?: IOException("SOCKS4 dial failed: ${e.message}", e))
        }
    }

    private sealed interface ResolvedRoute {
        data object Direct : ResolvedRoute
        data class Upstream(val endpoint: ProxyEndpointSpec) : ResolvedRoute
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val DEFAULT_READ_TIMEOUT_MS = 15_000
        private const val MAX_HTTP_LINE_BYTES = 8192
        private const val MAX_HTTP_HEADERS = 128

        const val STATUS_GENERAL_FAILURE = 0x01
        const val STATUS_NOT_ALLOWED = 0x02
        const val STATUS_BAD_REQUEST = 0x08

        fun generate128BitHexToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun defaultTls13SocketFactory(): SSLSocketFactory {
            val ctx = SSLContext.getInstance("TLSv1.3")
            ctx.init(null, null, null)
            return object : SSLSocketFactory() {
                private val delegate = ctx.socketFactory
                override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
                override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
                override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                    enforceTls13(delegate.createSocket(s, host, port, autoClose))
                override fun createSocket(host: String?, port: Int): Socket =
                    enforceTls13(delegate.createSocket(host, port))
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                    enforceTls13(delegate.createSocket(host, port, localHost, localPort))
                override fun createSocket(host: InetAddress?, port: Int): Socket =
                    enforceTls13(delegate.createSocket(host, port))
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    enforceTls13(delegate.createSocket(address, port, localAddress, localPort))
                private fun enforceTls13(socket: Socket): Socket {
                    if (socket is SSLSocket) {
                        socket.enabledProtocols = arrayOf("TLSv1.3")
                    }
                    return socket
                }
            }
        }

        internal fun normalizeTargetHost(raw: String): String {
            var h = raw.trim()
            if (h.startsWith("[") && h.endsWith("]") && h.length >= 2) {
                h = h.substring(1, h.length - 1)
            }
            return h.trimEnd('.').lowercase(Locale.US)
        }

        internal fun isNoProxyTarget(rawHost: String, entries: List<String>): Boolean {
            val host = normalizeTargetHost(rawHost)
            if (host.isEmpty()) return false
            val ipLiteral = parseIpLiteralOrNull(host)

            for (rawEntry in entries) {
                val entry = rawEntry.trim().lowercase(Locale.US)
                if (entry.isEmpty()) continue
                if (entry == "*") return true

                if (entry.contains('/')) {
                    if (ipLiteral != null && matchesCidr(ipLiteral, entry)) {
                        return true
                    }
                    continue
                }

                val entryIp = parseIpLiteralOrNull(entry.trim('[', ']'))
                if (ipLiteral != null && entryIp != null) {
                    if (Arrays.equals(ipLiteral.address, entryIp.address)) {
                        return true
                    }
                    continue
                }

                val domainSuffix = entry.removePrefix("*").removePrefix(".")
                if (domainSuffix.isNotEmpty()) {
                    if (host == domainSuffix || host.endsWith(".$domainSuffix")) {
                        return true
                    }
                }
            }
            return false
        }

        internal fun matchesCidr(address: InetAddress, cidr: String): Boolean {
            val slashIdx = cidr.indexOf('/')
            if (slashIdx <= 0) return false
            val netPart = cidr.substring(0, slashIdx).trim('[', ']')
            val prefixLen = cidr.substring(slashIdx + 1).toIntOrNull() ?: return false
            val netAddr = parseIpLiteralOrNull(netPart) ?: return false
            val addrBytes = address.address
            val netBytes = netAddr.address
            if (addrBytes.size != netBytes.size) return false
            val maxPrefix = addrBytes.size * 8
            if (prefixLen !in 0..maxPrefix) return false

            val fullBytes = prefixLen / 8
            val remBits = prefixLen % 8
            for (i in 0 until fullBytes) {
                if (addrBytes[i] != netBytes[i]) return false
            }
            if (remBits > 0) {
                val mask = (0xFF shl (8 - remBits)) and 0xFF
                if ((addrBytes[fullBytes].toInt() and mask) != (netBytes[fullBytes].toInt() and mask)) {
                    return false
                }
            }
            return true
        }

        internal fun enforceLiteralLanIsolation(rawHost: String) {
            val host = normalizeTargetHost(rawHost)
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
                throw LanIsolationBlockedException("Target '$rawHost' is blocked by LAN egress isolation")
            }
            val literal = parseIpLiteralOrNull(host)
            if (literal != null && isBlockedLanAddress(literal)) {
                throw LanIsolationBlockedException("Target IP '$rawHost' is blocked by LAN egress isolation")
            }
        }

        internal fun isBlockedLanAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress ||
                addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return true
            }
            val bytes = addr.address
            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                return when {
                    b0 == 0 -> true // 0.0.0.0/8
                    b0 == 10 -> true // 10.0.0.0/8
                    b0 == 127 -> true // 127.0.0.0/8
                    b0 == 169 && b1 == 254 -> true // 169.254.0.0/16
                    b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12
                    b0 == 192 && b1 == 168 -> true // 192.168.0.0/16
                    b0 >= 224 -> true // Multicast and reserved
                    else -> false
                }
            }
            if (bytes.size == 16) {
                val first = bytes[0].toInt() and 0xFF
                val second = bytes[1].toInt() and 0xFF
                // ULA fc00::/7
                if ((first and 0xFE) == 0xFC) return true
                // Link-local fe80::/10
                if (first == 0xFE && (second and 0xC0) == 0x80) return true
                // IPv4-mapped IPv6 ::ffff:0:0/96
                val isV4Mapped = (0..9).all { bytes[it] == 0.toByte() } &&
                    bytes[10] == 0xFF.toByte() &&
                    bytes[11] == 0xFF.toByte()
                if (isV4Mapped) {
                    val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    return isBlockedLanAddress(v4)
                }
            }
            return false
        }

        internal fun isIpLiteral(raw: String): Boolean = parseIpLiteralOrNull(raw) != null

        internal fun parseIpLiteralOrNull(raw: String): InetAddress? {
            val trimmed = raw.trim().trim('[', ']')
            if (trimmed.isEmpty()) return null
            val looksLikeV4 = trimmed.all { it in '0'..'9' || it == '.' }
            val looksLikeV6 = trimmed.contains(':') && trimmed.all {
                it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
            }
            if (!looksLikeV4 && !looksLikeV6) return null
            return try {
                InetAddress.getByName(trimmed)
            } catch (_: Exception) {
                null
            }
        }

        private fun formatAuthority(host: String, port: Int): String {
            return if (host.contains(':') && !host.startsWith("[")) {
                "[$host]:$port"
            } else {
                "$host:$port"
            }
        }

        private fun formatHostHeader(host: String, port: Int, defaultPort: Int): String {
            val bracketed = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return if (port == defaultPort) bracketed else "$bracketed:$port"
        }

        private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
            val trimmed = authority.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close <= 1) return null
                val host = trimmed.substring(1, close)
                val rest = trimmed.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                    else -> return null
                }
                return if (port in 1..65535) host to port else null
            }
            val colon = trimmed.lastIndexOf(':')
            return if (colon > 0) {
                val host = trimmed.substring(0, colon)
                val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
                if (port in 1..65535) host to port else null
            } else if (defaultPort in 1..65535) {
                trimmed to defaultPort
            } else {
                null
            }
        }

        private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
            val buf = ByteArrayOutputStream(128)
            while (buf.size() < maxBytes) {
                val b = input.read()
                if (b < 0) {
                    return if (buf.size() == 0) null else buf.toString(StandardCharsets.ISO_8859_1.name())
                }
                if (b == '\n'.code) {
                    val arr = buf.toByteArray()
                    val end = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                    return String(arr, 0, end, StandardCharsets.ISO_8859_1)
                }
                buf.write(b)
            }
            throw IOException("HTTP header line exceeds $maxBytes bytes")
        }

        private fun readHttpHeaders(input: InputStream): List<Pair<String, String>> {
            val headers = ArrayList<Pair<String, String>>()
            repeat(MAX_HTTP_HEADERS) {
                val line = readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: return headers
                if (line.isEmpty()) return headers
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                }
            }
            return headers
        }

        private fun sendHttpError(output: OutputStream, code: Int, reason: String) {
            val msg = "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(msg.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }

        private fun readExactBytes(input: InputStream, count: Int): ByteArray {
            val out = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = input.read(out, off, count - off)
                if (n < 0) throw EOFException("Unexpected EOF while reading $count bytes")
                off += n
            }
            return out
        }

        private fun relayBidirectional(
            aIn: InputStream,
            aOut: OutputStream,
            bIn: InputStream,
            bOut: OutputStream,
        ) {
            val t1 = Thread({ copyStreamQuietly(aIn, bOut) }, "termvr-relay-up").apply {
                isDaemon = true
                start()
            }
            copyStreamQuietly(bIn, aOut)
            try {
                t1.join(2000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun copyStreamQuietly(src: InputStream, dst: OutputStream) {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    dst.write(buf, 0, n)
                    dst.flush()
                }
            } catch (_: IOException) {
            }
        }
    }
}
