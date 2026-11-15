package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.data.InMemorySecretStore
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoopbackProxyBridgeTest {

    @Test
    fun `host HTTP proxy listener enforces 128-bit Basic token and relays CONNECT`() {
        val echoServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            runCatching {
                echoServer.accept().use { s ->
                    val buf = ByteArray(64)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) {
                        s.getOutputStream().write("ECHO:".toByteArray() + buf.copyOfRange(0, n))
                        s.getOutputStream().flush()
                    }
                }
            }
        }

        val bridge = LoopbackProxyBridge(
            secretStore = InMemorySecretStore(),
            dnsLookup = { arrayOf(InetAddress.getByName("127.0.0.1")) },
        )
        try {
            val bridgePort = bridge.start(0)
            // 1. Unauthenticated request receives HTTP 407.
            Socket("127.0.0.1", bridgePort).use { client ->
                val out = client.getOutputStream()
                out.write("CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\nHost: 127.0.0.1:${echoServer.localPort}\r\n\r\n".toByteArray())
                out.flush()
                val reply = readUntilDoubleCrlf(client.getInputStream())
                assertTrue(reply.startsWith("HTTP/1.1 407"))
            }

            // 2. Wrong token receives HTTP 407.
            Socket("127.0.0.1", bridgePort).use { client ->
                val badAuth = Base64.getEncoder().encodeToString("termvr:wrongtoken".toByteArray())
                val out = client.getOutputStream()
                out.write(
                    "CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\nProxy-Authorization: Basic $badAuth\r\n\r\n".toByteArray(),
                )
                out.flush()
                val reply = readUntilDoubleCrlf(client.getInputStream())
                assertTrue(reply.startsWith("HTTP/1.1 407"))
            }

            // 3. Valid 128-bit token establishes tunnel.
            Socket("127.0.0.1", bridgePort).use { client ->
                val okAuth = Base64.getEncoder().encodeToString("termvr:${bridge.authToken}".toByteArray())
                val out = client.getOutputStream()
                out.write(
                    "CONNECT 127.0.0.1:${echoServer.localPort} HTTP/1.1\r\nProxy-Authorization: Basic $okAuth\r\n\r\n".toByteArray(),
                )
                out.flush()
                val reply = readUntilDoubleCrlf(client.getInputStream())
                assertTrue(reply.startsWith("HTTP/1.1 200"))
                out.write("ping".toByteArray())
                out.flush()
                val resp = ByteArray(16)
                val n = client.getInputStream().read(resp)
                assertEquals("ECHO:ping", String(resp, 0, n, StandardCharsets.UTF_8))
            }
        } finally {
            bridge.close()
            echoServer.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SOCKS5 RFC 1928 and 1929 auth with ATYP 0x03 uses Keystore secret and hot-swaps live`() {
        val capturedTargets = CopyOnWriteArrayList<String>()
        val socksServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            while (!socksServer.isClosed) {
                val client = runCatching { socksServer.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use { s ->
                        val din = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        val ver = din.readUnsignedByte()
                        val nMethods = din.readUnsignedByte()
                        val methods = ByteArray(nMethods)
                        din.readFully(methods)
                        assertEquals(0x05, ver)
                        assertTrue(methods.contains(0x02.toByte()))
                        out.write(byteArrayOf(0x05, 0x02))
                        out.flush()

                        // RFC 1929 subnegotiation
                        assertEquals(0x01, din.readUnsignedByte())
                        val ulen = din.readUnsignedByte()
                        val uBytes = ByteArray(ulen).also { din.readFully(it) }
                        val plen = din.readUnsignedByte()
                        val pBytes = ByteArray(plen).also { din.readFully(it) }
                        val okAuth = String(uBytes) == "alice" && String(pBytes) == "s3cr3t!"
                        out.write(byteArrayOf(0x01, if (okAuth) 0x00 else 0x01))
                        out.flush()
                        if (!okAuth) return@use

                        // Request
                        assertEquals(0x05, din.readUnsignedByte())
                        assertEquals(0x01, din.readUnsignedByte())
                        assertEquals(0x00, din.readUnsignedByte())
                        val atyp = din.readUnsignedByte()
                        assertEquals(0x03, atyp) // ATYP = domain name
                        val hostLen = din.readUnsignedByte()
                        val hostBytes = ByteArray(hostLen).also { din.readFully(it) }
                        val port = din.readUnsignedShort()
                        capturedTargets.add("${String(hostBytes)}:$port")
                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                        out.flush()
                        out.write("HELLO-SOCKS5".toByteArray())
                        out.flush()
                    }
                }
            }
        }

        val secretStore = InMemorySecretStore().apply {
            putSecret("socks-key", "s3cr3t!".toCharArray())
        }
        val bridge = LoopbackProxyBridge(
            secretStore = secretStore,
            systemProxyResolver = { null },
        )
        try {
            val startSwap = System.nanoTime()
            bridge.updatePolicy(
                OutboundProxyConfig(
                    mode = OutboundProxyMode.CUSTOM,
                    customEndpoint = ProxyEndpointSpec(
                        protocol = ProxyProtocol.SOCKS5,
                        host = "127.0.0.1",
                        port = socksServer.localPort,
                        username = "alice",
                        credentialKeyAlias = "socks-key",
                    ),
                ),
                isolateLanEgress = true,
            )
            val swapMs = (System.nanoTime() - startSwap) / 1_000_000L
            assertTrue(swapMs < 50L, "Live proxy swap took ${swapMs}ms")

            val outcome = bridge.dialForMuxStream("example.org", 443)
            val connected = assertInstanceOf(ProxyDialOutcome.Connected::class.java, outcome)
            connected.socket.use { sock ->
                val buf = sock.getInputStream().readBytes()
                assertEquals("HELLO-SOCKS5", String(buf, StandardCharsets.UTF_8))
            }
            assertEquals(listOf("example.org:443"), capturedTargets)

            // testProxy verifies handshake latency.
            val probeResult = runBlocking {
                bridge.testProxy(bridge.currentPolicy.outboundProxy, "example.org", 443, 3_000)
            }
            assertTrue(probeResult.success)
            assertTrue(probeResult.latencyMillis >= 1L)
        } finally {
            bridge.close()
            socksServer.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SOCKS4A sends 0_0_0_1 sentinel and NUL-terminated domain`() {
        val received = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            server.accept().use { s ->
                val din = DataInputStream(s.getInputStream())
                val vn = din.readUnsignedByte()
                val cd = din.readUnsignedByte()
                val port = din.readUnsignedShort()
                val ip = ByteArray(4).also { din.readFully(it) }
                val userId = readCString(din)
                val domain = readCString(din)
                assertEquals(0x04, vn)
                assertEquals(0x01, cd)
                assertTrue(ip.contentEquals(byteArrayOf(0, 0, 0, 1)))
                assertEquals("guest", userId)
                received.add("$domain:$port")
                s.getOutputStream().write(byteArrayOf(0x00, 0x5A, 0, 0, 0, 0, 0, 0))
                s.getOutputStream().flush()
            }
        }

        val bridge = LoopbackProxyBridge(secretStore = InMemorySecretStore())
        try {
            bridge.updatePolicy(
                OutboundProxyConfig(
                    mode = OutboundProxyMode.CUSTOM,
                    customEndpoint = ProxyEndpointSpec(
                        protocol = ProxyProtocol.SOCKS4A,
                        host = "127.0.0.1",
                        port = server.localPort,
                        username = "guest",
                    ),
                ),
                isolateLanEgress = false,
            )
            val outcome = bridge.dialForMuxStream("onion.example", 80)
            val conn = assertInstanceOf(ProxyDialOutcome.Connected::class.java, outcome)
            conn.socket.close()
            assertEquals(listOf("onion.example:80"), received)
        } finally {
            bridge.close()
            server.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `HTTP CONNECT upstream sends Basic auth and ANDROID_SYSTEM uses SystemProxyResolver`() {
        val seenRequests = CopyOnWriteArrayList<String>()
        val httpProxyServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            while (!httpProxyServer.isClosed) {
                val client = runCatching { httpProxyServer.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use { s ->
                        val hdr = readUntilDoubleCrlf(s.getInputStream())
                        seenRequests.add(hdr)
                        s.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        s.getOutputStream().flush()
                    }
                }
            }
        }

        val secretStore = InMemorySecretStore().apply {
            putSecret("http-pass", "hunter2".toCharArray())
        }
        val bridge = LoopbackProxyBridge(
            secretStore = secretStore,
            systemProxyResolver = {
                SystemProxyInfo("127.0.0.1", httpProxyServer.localPort, listOf("internal.local"))
            },
        )
        try {
            bridge.updatePolicy(
                OutboundProxyConfig(
                    mode = OutboundProxyMode.CUSTOM,
                    customEndpoint = ProxyEndpointSpec(
                        protocol = ProxyProtocol.HTTP,
                        host = "127.0.0.1",
                        port = httpProxyServer.localPort,
                        username = "corp",
                        credentialKeyAlias = "http-pass",
                    ),
                ),
                isolateLanEgress = false,
            )
            val conn1 = assertInstanceOf(
                ProxyDialOutcome.Connected::class.java,
                bridge.dialForMuxStream("registry-1.docker.io", 443),
            )
            conn1.socket.close()
            val expectedB64 = Base64.getEncoder().encodeToString("corp:hunter2".toByteArray())
            assertTrue(seenRequests[0].contains("CONNECT registry-1.docker.io:443 HTTP/1.1"))
            assertTrue(seenRequests[0].contains("Proxy-Authorization: Basic $expectedB64"))

            // Switch live to ANDROID_SYSTEM.
            bridge.updatePolicy(
                OutboundProxyConfig(mode = OutboundProxyMode.ANDROID_SYSTEM),
                isolateLanEgress = false,
            )
            val conn2 = assertInstanceOf(
                ProxyDialOutcome.Connected::class.java,
                bridge.dialForMuxStream("github.com", 443),
            )
            conn2.socket.close()
            assertTrue(seenRequests[1].contains("CONNECT github.com:443 HTTP/1.1"))
        } finally {
            bridge.close()
            httpProxyServer.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `isolateLanEgress blocks loopback, RFC1918, link-local, ULA, and DNS-resolved private targets`() {
        val bridge = LoopbackProxyBridge(
            secretStore = InMemorySecretStore(),
            dnsLookup = { host ->
                if (host == "rebind.example") {
                    arrayOf(InetAddress.getByName("192.168.10.20"))
                } else {
                    arrayOf(InetAddress.getByName("93.184.216.34"))
                }
            },
        )
        bridge.updatePolicy(OutboundProxyConfig(mode = OutboundProxyMode.DIRECT), isolateLanEgress = true)

        val blockedTargets = listOf(
            "127.0.0.1",
            "localhost",
            "10.0.0.5",
            "172.16.4.1",
            "192.168.1.50",
            "169.254.169.254",
            "::1",
            "[::1]",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1",
            "::ffff:192.168.1.1",
            "rebind.example",
        )
        for (target in blockedTargets) {
            val outcome = bridge.dialForMuxStream(target, 80)
            assertInstanceOf(
                ProxyDialOutcome.Rejected::class.java,
                outcome,
                "Expected LAN isolation rejection for $target",
            )
        }
        bridge.close()
    }

    @Test
    fun `noProxyEntries matches CIDR subnets and domain suffixes accurately`() {
        val entries = OutboundProxyConfig.DEFAULT_NO_PROXY
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("localhost", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("127.0.0.1", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("[::1]", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("10.42.0.15", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("172.16.254.1", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("192.168.99.2", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("kubernetes.default.svc", entries))
        assertTrue(LoopbackProxyBridge.isNoProxyTarget("coredns.kube-system.svc.cluster.local", entries))

        assertFalse(LoopbackProxyBridge.isNoProxyTarget("172.32.0.1", entries))
        assertFalse(LoopbackProxyBridge.isNoProxyTarget("8.8.8.8", entries))
        assertFalse(LoopbackProxyBridge.isNoProxyTarget("example.com", entries))
    }

    @Test
    fun `HTTPS upstream uses TLS 1_3 SSLSocket with SNI, Basic auth, and bidirectional relay`(
        @TempDir tempDir: File,
    ) {
        val keystoreFile = File(tempDir, "proxy.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val gen = ProcessBuilder(
            keytool,
            "-genkeypair",
            "-alias",
            "proxy",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "1",
            "-storetype",
            "PKCS12",
            "-keystore",
            keystoreFile.absolutePath,
            "-storepass",
            "changeit",
            "-dname",
            "CN=127.0.0.1",
            "-ext",
            "SAN=IP:127.0.0.1,DNS:localhost",
        ).redirectErrorStream(true).start()
        assertEquals(0, gen.waitFor(), "keytool failed: ${gen.inputStream.bufferedReader().readText()}")

        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, "changeit".toCharArray())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        val sslContext = SSLContext.getInstance("TLSv1.3").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }

        val serverSocket = sslContext.serverSocketFactory.createServerSocket(
            0,
            16,
            InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        serverSocket.enabledProtocols = arrayOf("TLSv1.3")

        val pool = Executors.newCachedThreadPool()
        val capturedHeaders = CopyOnWriteArrayList<String>()
        val capturedProtocols = CopyOnWriteArrayList<String>()
        pool.execute {
            runCatching {
                serverSocket.accept().use { s ->
                    val sslSock = s as javax.net.ssl.SSLSocket
                    sslSock.startHandshake()
                    capturedProtocols.add(sslSock.session.protocol)
                    val req = readUntilDoubleCrlf(sslSock.inputStream)
                    capturedHeaders.add(req)
                    val out = sslSock.outputStream
                    out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    out.flush()
                    val buf = ByteArray(16)
                    val n = sslSock.inputStream.read(buf)
                    if (n > 0) {
                        out.write("TLS:".toByteArray() + buf.copyOfRange(0, n))
                        out.flush()
                    }
                }
            }
        }

        val secretStore = InMemorySecretStore().apply {
            putSecret("tls-secret", "s3cr3t".toCharArray())
        }
        val bridge = LoopbackProxyBridge(
            secretStore = secretStore,
            tlsSocketFactory = sslContext.socketFactory,
        )
        try {
            bridge.updatePolicy(
                OutboundProxyConfig(
                    mode = OutboundProxyMode.CUSTOM,
                    customEndpoint = ProxyEndpointSpec(
                        protocol = ProxyProtocol.HTTPS,
                        host = "127.0.0.1",
                        port = serverSocket.localPort,
                        tlsServerName = "proxy.internal.example",
                        username = "alice",
                        credentialKeyAlias = "tls-secret",
                    ),
                ),
                isolateLanEgress = false,
            )

            val outcome = assertInstanceOf(
                ProxyDialOutcome.Connected::class.java,
                bridge.dialForMuxStream("ghcr.io", 443),
            )
            outcome.socket.use { sock ->
                sock.getOutputStream().write("hello".toByteArray())
                sock.getOutputStream().flush()
                val reply = ByteArray(16)
                val n = sock.getInputStream().read(reply)
                assertEquals("TLS:hello", String(reply, 0, n, StandardCharsets.UTF_8))
            }
            assertEquals(listOf("TLSv1.3"), capturedProtocols)
            val expectedAuth = Base64.getEncoder().encodeToString("alice:s3cr3t".toByteArray())
            assertTrue(capturedHeaders[0].contains("CONNECT ghcr.io:443 HTTP/1.1"))
            assertTrue(capturedHeaders[0].contains("Proxy-Authorization: Basic $expectedAuth"))
        } finally {
            bridge.close()
            serverSocket.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `host HTTP proxy listener relays plain HTTP GET requests after Basic auth check`() {
        val originServer = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val seenOriginRequests = CopyOnWriteArrayList<String>()
        pool.execute {
            runCatching {
                originServer.accept().use { s ->
                    val hdr = readUntilDoubleCrlf(s.getInputStream())
                    seenOriginRequests.add(hdr)
                    val body = "OK-ORIGIN".toByteArray(StandardCharsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }

        val bridge = LoopbackProxyBridge(
            secretStore = InMemorySecretStore(),
            dnsLookup = { arrayOf(InetAddress.getByName("127.0.0.1")) },
        )
        try {
            val port = bridge.start(0)
            val okAuth = Base64.getEncoder().encodeToString("termvr:${bridge.authToken}".toByteArray())
            Socket("127.0.0.1", port).use { client ->
                val out = client.getOutputStream()
                out.write(
                    (
                        "GET http://127.0.0.1:${originServer.localPort}/v2/ HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:${originServer.localPort}\r\n" +
                            "Proxy-Authorization: Basic $okAuth\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(),
                )
                out.flush()
                val response = client.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                assertTrue(response.contains("HTTP/1.1 200 OK"))
                assertTrue(response.endsWith("OK-ORIGIN"))
            }
            assertTrue(seenOriginRequests[0].startsWith("GET /v2/ HTTP/1.1"))
            assertFalse(seenOriginRequests[0].contains("Proxy-Authorization"))
        } finally {
            bridge.close()
            originServer.close()
            pool.shutdownNow()
        }
    }

    private fun readUntilDoubleCrlf(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        return buf.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readCString(input: DataInputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0) break
            out.write(b)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }
}
