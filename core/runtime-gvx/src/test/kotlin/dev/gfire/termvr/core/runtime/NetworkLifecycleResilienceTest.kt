package dev.gfire.termvr.core.runtime

import android.os.Build
import dev.gfire.termvr.core.data.InMemorySecretStore
import dev.gfire.termvr.core.model.ForwardProtocol
import dev.gfire.termvr.core.model.ListenHost
import dev.gfire.termvr.core.model.PhantomProcessRemediation
import dev.gfire.termvr.core.model.PortForwardRule
import dev.gfire.termvr.core.model.SandboxProfile
import dev.gfire.termvr.core.model.SandboxRunState
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NetworkLifecycleResilienceTest {

    @Test
    fun `cross-profile host port conflict rejects overlapping LOOPBACK_V4 and LAN_ALL_V4 binds`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val harness = newSupervisorHarness(tempDir, pool)
        try {
            val dockerProfile = SandboxProfile(
                id = "docker-dind",
                displayName = "Docker Engine",
                imageRef = "docker:28-dind",
                portForwards = listOf(
                    PortForwardRule(
                        id = "web-lan",
                        protocol = ForwardProtocol.TCP,
                        listenHost = ListenHost.LAN_ALL_V4,
                        hostPortStart = 8080,
                        hostPortEnd = 8080,
                        guestPortStart = 80,
                        guestPortEnd = 80,
                    ),
                ),
            )
            val appProfile = SandboxProfile(
                id = "web-app",
                displayName = "Web App",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(
                    PortForwardRule(
                        id = "app-loop",
                        protocol = ForwardProtocol.TCP,
                        listenHost = ListenHost.LOOPBACK_V4,
                        hostPortStart = 8080,
                        hostPortEnd = 8080,
                        guestPortStart = 3000,
                        guestPortEnd = 3000,
                    ),
                ),
            )

            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(dockerProfile))

            val conflictErr = assertThrows(IOException::class.java) {
                runBlocking {
                    harness.supervisor.startOrResumeSandbox(appProfile)
                }
            }
            val msg = conflictErr.message.orEmpty()
            assertTrue(msg.contains("8080"), "Expected conflicting port 8080 in error: $msg")
            assertTrue(msg.contains("Docker Engine"), "Expected conflicting container name in error: $msg")
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.runStates.value["docker-dind"])
            assertNotEqualsRunning(harness.supervisor.runStates.value["web-app"])

            // Distinct protocol (UDP 8080 vs TCP 8080) and distinct address family ([::1]:8080 vs 0.0.0.0:8080) do not conflict.
            val nonConflicting = SandboxProfile(
                id = "udp-v6-app",
                displayName = "UDP and IPv6 App",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(
                    PortForwardRule("udp8080", ForwardProtocol.UDP, ListenHost.LOOPBACK_V4, 8080, 8080, 8080, 8080),
                    PortForwardRule("v6loop", ForwardProtocol.TCP, ListenHost.LOOPBACK_V6, 8080, 8080, 8080, 8080),
                ),
            )
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(nonConflicting))

            // Dual-stack wildcard [::]:8080 conflicts with both IPv4 and IPv6 listeners.
            val v6WildcardConflict = SandboxProfile(
                id = "v6-wild",
                displayName = "V6 Wildcard",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(
                    PortForwardRule("v6all", ForwardProtocol.TCP, ListenHost.LAN_ALL_V6, 8080, 8080, 80, 80),
                ),
            )
            assertThrows(PortConflictException::class.java) {
                runBlocking {
                    harness.supervisor.startOrResumeSandbox(v6WildcardConflict)
                }
            }
        } finally {
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `applyPortForwards via checkpoint preserves open streams, applies added rules when prior rules existed, and publishes ports immediately`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val harness = newSupervisorHarness(
            tempDir = tempDir,
            pool = pool,
            resyncOpenStreamsJson = """[{"id":1,"rows":30,"cols":100}]""",
        )
        try {
            val initialRule = PortForwardRule("r1", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 3000, 3000, 3000, 3000)
            val addedRule = PortForwardRule("r2", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 18080, 18080, 80, 80)
            val profile = SandboxProfile(
                id = "live-edit",
                displayName = "Live Edit",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(initialRule),
            )

            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(profile))
            assertEquals(
                listOf(3000),
                harness.supervisor.publishedPortsByProfile.value["live-edit"].orEmpty().map { it.hostPort },
            )

            val updatedRules = listOf(initialRule, addedRule)
            val resultState = harness.supervisor.applyPortForwards(
                profileId = "live-edit",
                updatedRules = updatedRules,
                checkpointAndRestore = true,
            )
            assertEquals(SandboxRunState.RUNNING, resultState)

            val restoreCmd = harness.launchedCommands.last()
            assertEquals("restore", restoreCmd.first())
            assertTrue(restoreCmd.contains("127.0.0.1:3000:3000/tcp"), "Expected existing rule in restore args: $restoreCmd")
            assertTrue(restoreCmd.contains("127.0.0.1:18080:80/tcp"), "Expected newly added rule in restore args: $restoreCmd")

            val mux = harness.supervisor.getMuxClient("live-edit")
            assertNotNull(mux)
            assertTrue(mux!!.isStreamOpen(1L))

            val publishedAfter = harness.supervisor.publishedPortsByProfile.value["live-edit"].orEmpty()
            assertEquals(setOf(3000, 18080), publishedAfter.map { it.hostPort }.toSet())

            assertEquals(
                SandboxRunState.RUNNING,
                harness.supervisor.applyPortForwards("live-edit", emptyList(), checkpointAndRestore = true),
            )
            assertTrue(harness.supervisor.publishedPortsByProfile.value["live-edit"].orEmpty().isEmpty())
        } finally {
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `EADDRINUSE on restore surfaces actionable port error, recovers applyPortForwards, and preserves valid checkpoint`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val failPort9090 = AtomicBoolean(false)
        val harness = newSupervisorHarness(
            tempDir = tempDir,
            pool = pool,
            resyncOpenStreamsJson = """[{"id":1,"rows":24,"cols":80}]""",
            customLauncher = { args ->
                val binds9090 = args.any { it.contains(":9090:") }
                if (failPort9090.get() && binds9090) {
                    TestMuxProcessHandle(
                        executor = pool,
                        stderrText = "rungv: listen tcp 127.0.0.1:9090: bind: address already in use\n",
                        failImmediatelyWithExitCode = 1,
                    )
                } else {
                    TestMuxProcessHandle(
                        executor = pool,
                        resyncOpenStreamsJson = """[{"id":1,"rows":24,"cols":80}]""",
                    )
                }
            },
        )

        try {
            val rule3000 = PortForwardRule("r3000", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 3000, 3000, 3000, 3000)
            val rule9090 = PortForwardRule("r9090", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, 9090, 9090, 80, 80)
            val profile = SandboxProfile(
                id = "ckpt-safe",
                displayName = "Checkpoint Safe",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(rule3000),
            )

            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(profile))

            // 1. Live applyPortForwards(checkpointAndRestore = true) where restore fails on port 9090:
            // throws PortConflictException AND automatically recovers container back to RUNNING on rule3000!
            failPort9090.set(true)
            val liveApplyErr = assertThrows(PortConflictException::class.java) {
                runBlocking {
                    harness.supervisor.applyPortForwards(
                        profileId = profile.id,
                        updatedRules = listOf(rule3000, rule9090),
                        checkpointAndRestore = true,
                    )
                }
            }
            assertTrue(liveApplyErr.message.orEmpty().contains("9090"))
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.runStates.value[profile.id])
            assertTrue(harness.supervisor.getMuxClient(profile.id)!!.isStreamOpen(1L))
            assertEquals(
                listOf(3000),
                harness.supervisor.publishedPortsByProfile.value[profile.id].orEmpty().map { it.hostPort },
            )

            // 2. Direct resume of a FROZEN container hitting EADDRINUSE keeps checkpoint valid (not superseded) and state FROZEN.
            assertEquals(SandboxRunState.FROZEN, harness.supervisor.freezeSandbox(profile.id))
            val err = assertThrows(IOException::class.java) {
                runBlocking {
                    harness.supervisor.startOrResumeSandbox(profile.copy(portForwards = listOf(rule9090)))
                }
            }
            val msg = err.message.orEmpty()
            assertTrue(msg.contains("9090"), "Expected port 9090 in error: $msg")
            assertTrue(msg.lowercase().contains("already in use"), "Expected already-in-use explanation: $msg")

            val meta = harness.checkpointController.readMetadata(profile.id)
            assertNotNull(meta)
            assertFalse(meta!!.superseded, "Valid checkpoint must not be marked superseded on EADDRINUSE")
            assertNotNull(harness.checkpointController.resolveActiveCheckpoint(profile.id))
            assertEquals(SandboxRunState.FROZEN, harness.supervisor.runStates.value[profile.id])

            failPort9090.set(false)
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(profile.copy(portForwards = listOf(rule9090))))
            assertEquals("restore", harness.launchedCommands.last().first())
            assertTrue(harness.supervisor.getMuxClient(profile.id)!!.isStreamOpen(1L))
        } finally {
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `SocketBindHostPortProbe rejects bound host TCP port before freezeSandbox runs`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val occupiedTcp = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }
        val busyPort = occupiedTcp.localPort
        val harness = newSupervisorHarness(
            tempDir = tempDir,
            pool = pool,
            hostPortProbe = SocketBindHostPortProbe,
        )
        try {
            val profile = SandboxProfile(
                id = "probe-box",
                displayName = "Probe Box",
                imageRef = "debian:trixie-slim",
            )
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(profile))
            val launchesBefore = harness.launchedCommands.size

            val conflict = assertThrows(PortConflictException::class.java) {
                runBlocking {
                    harness.supervisor.applyPortForwards(
                        profileId = profile.id,
                        updatedRules = listOf(
                            PortForwardRule("busy", ForwardProtocol.TCP, ListenHost.LOOPBACK_V4, busyPort, busyPort, 80, 80),
                        ),
                        checkpointAndRestore = true,
                    )
                }
            }
            assertEquals(busyPort, conflict.conflictingPort)
            // Container never froze or respawned because pre-bind probe caught the host conflict up front.
            assertEquals(launchesBefore, harness.launchedCommands.size)
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.runStates.value[profile.id])
        } finally {
            occupiedTcp.close()
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `DNS forwarder handles oversized 512B UDP TC-bit fallback, upstream TC retry, EDNS0, malformed packets, timeouts, and network switches`() {
        val currentMode = AtomicReference("ONLINE_LARGE")
        val tcpFallbackCalls = AtomicInteger(0)
        val largePayloadSize = 900
        val dns = LoopbackDnsProxy(
            queryTimeoutMs = 300L,
            networkDnsBackend = { query, _ ->
                when (currentMode.get()) {
                    "OFFLINE" -> null
                    "TIMEOUT" -> throw SocketTimeoutException("Simulated upstream DNS timeout")
                    "UPSTREAM_TC" -> {
                        val truncated = query.copyOf()
                        truncated[2] = ((truncated[2].toInt() and 0x79) or 0x82).toByte() // QR=1, TC=1
                        truncated[3] = 0x80.toByte()
                        truncated
                    }
                    "ONLINE_LARGE" -> {
                        val big = ByteArray(largePayloadSize)
                        System.arraycopy(query, 0, big, 0, query.size)
                        big[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // QR=1, TC=0
                        big[3] = 0x80.toByte() // RA=1, NOERROR
                        big[7] = 0x04 // ANCOUNT=4
                        for (i in query.size until big.size) {
                            big[i] = (i and 0xFF).toByte()
                        }
                        big
                    }
                    else -> {
                        val small = query.copyOf()
                        small[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
                        small[3] = 0x80.toByte()
                        small
                    }
                }
            },
            tcpFallbackBackend = { query, _ ->
                tcpFallbackCalls.incrementAndGet()
                val full = ByteArray(720)
                System.arraycopy(query, 0, full, 0, query.size)
                full[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // QR=1, TC=0
                full[3] = 0x80.toByte()
                full[7] = 0x03
                full
            },
        )

        try {
            val port = dns.start(0)
            val query = buildDnsQuery(txId = 0x4242, qname = "registry-1.docker.io")

            // 1. Standard RFC 1035 UDP query (no EDNS0) for 900B response sets TC=1 within 512 bytes.
            DatagramSocket().use { udp ->
                udp.soTimeout = 2_000
                udp.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
                val buf = ByteArray(1500)
                val pkt = DatagramPacket(buf, buf.size)
                udp.receive(pkt)
                assertTrue(pkt.length <= 512, "Non-EDNS0 UDP reply must fit in 512 bytes, got ${pkt.length}")
                val udpResp = pkt.data.copyOfRange(0, pkt.length)
                assertEquals(query[0], udpResp[0])
                assertEquals(query[1], udpResp[1])
                assertTrue((udpResp[2].toInt() and 0x02) != 0, "Expected TC bit set on 900B UDP response")
            }

            // 2. EDNS0 OPT query advertising 2048-byte UDP payload receives the full 900B UDP answer directly.
            val ednsQuery = buildEdnsDnsQuery(txId = 0x4343, qname = "registry-1.docker.io", udpPayloadSize = 2048)
            DatagramSocket().use { udp ->
                udp.soTimeout = 2_000
                udp.send(DatagramPacket(ednsQuery, ednsQuery.size, InetAddress.getByName("127.0.0.1"), port))
                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                udp.receive(pkt)
                assertEquals(largePayloadSize, pkt.length)
                assertEquals(0, pkt.data[2].toInt() and 0x02)
            }

            // 3. Stub resolver TCP retry to the same port receives the full 900-byte untruncated response.
            Socket("127.0.0.1", port).use { tcp ->
                tcp.soTimeout = 2_000
                val out = tcp.getOutputStream()
                val framed = ByteBuffer.allocate(2 + query.size)
                    .putShort(query.size.toShort())
                    .put(query)
                    .array()
                out.write(framed)
                out.flush()
                val din = DataInputStream(tcp.getInputStream())
                val len = din.readUnsignedShort()
                assertEquals(largePayloadSize, len)
                val fullResp = ByteArray(len).also { din.readFully(it) }
                assertEquals(0, fullResp[2].toInt() and 0x02, "TCP response must not have TC bit set")
                assertEquals(4, fullResp[7].toInt() and 0xFF)
            }

            // 4. When upstream UDP sets TC=1, LoopbackDnsProxy retries upstream over TCP automatically.
            currentMode.set("UPSTREAM_TC")
            Socket("127.0.0.1", port).use { tcp ->
                tcp.soTimeout = 2_000
                val out = tcp.getOutputStream()
                val framed = ByteBuffer.allocate(2 + query.size)
                    .putShort(query.size.toShort())
                    .put(query)
                    .array()
                out.write(framed)
                out.flush()
                val din = DataInputStream(tcp.getInputStream())
                val len = din.readUnsignedShort()
                assertEquals(720, len)
                val fullResp = ByteArray(len).also { din.readFully(it) }
                assertEquals(0, fullResp[2].toInt() and 0x02)
                assertEquals(3, fullResp[7].toInt() and 0xFF)
            }
            assertEquals(1, tcpFallbackCalls.get())

            // 5. Malformed packets (short <12B, reserved 0x80 label bits, unterminated QNAME) do not kill UDP loop.
            DatagramSocket().use { udp ->
                udp.soTimeout = 2_000
                udp.send(DatagramPacket(byteArrayOf(1, 2, 3, 4, 5), 5, InetAddress.getByName("127.0.0.1"), port))

                currentMode.set("TIMEOUT")
                val badLabelQuery = byteArrayOf(
                    0x99.toByte(), 0x11, 0x01, 0x00,
                    0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x80.toByte(), 0x41, 0x00, 0x00, 0x01, 0x00, 0x01,
                )
                udp.send(DatagramPacket(badLabelQuery, badLabelQuery.size, InetAddress.getByName("127.0.0.1"), port))
                val errBuf = ByteArray(512)
                val errPkt = DatagramPacket(errBuf, errBuf.size)
                udp.receive(errPkt)
                assertEquals(12, errPkt.length, "Malformed question must be stripped to 12-byte header on SERVFAIL")
                assertEquals(2, errPkt.data[3].toInt() and 0x0F)

                // 6. Transitioning from TIMEOUT to OFFLINE to ONLINE_SMALL recovers cleanly on the same socket.
                currentMode.set("OFFLINE")
                udp.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
                val offPkt = DatagramPacket(ByteArray(512), 512)
                udp.receive(offPkt)
                assertEquals(2, offPkt.data[3].toInt() and 0x0F)

                currentMode.set("ONLINE_SMALL")
                udp.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
                val okPkt = DatagramPacket(ByteArray(512), 512)
                udp.receive(okPkt)
                assertEquals(0, okPkt.data[3].toInt() and 0x0F)
            }
        } finally {
            dns.close()
        }
    }

    @Test
    fun `Wi-Fi disconnect then reconnect to new network triggers LAN reconfirmation and running reconfirm clears it`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val harness = newSupervisorHarness(tempDir, pool)
        try {
            val lanProfile = SandboxProfile(
                id = "lan-box",
                displayName = "LAN Box",
                imageRef = "debian:trixie-slim",
                portForwards = listOf(
                    PortForwardRule("http", ForwardProtocol.TCP, ListenHost.LAN_ALL_V4, 8000, 8000, 8000, 8000),
                ),
            )
            harness.supervisor.onNetworkHandleChanged(1001L)
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(lanProfile))
            assertFalse("lan-box" in harness.supervisor.lanReconfirmationRequired.value)

            harness.supervisor.onNetworkHandleChanged(null)
            harness.supervisor.onNetworkHandleChanged(2002L)
            assertTrue(
                "lan-box" in harness.supervisor.lanReconfirmationRequired.value,
                "Expected lanReconfirmationRequired after disconnect and reconnect to new Wi-Fi handle",
            )

            assertEquals(
                SandboxRunState.RUNNING,
                harness.supervisor.startOrResumeSandbox(lanProfile, reconfirmLan = true),
            )
            assertFalse(
                "lan-box" in harness.supervisor.lanReconfirmationRequired.value,
                "Expected reconfirmLan=true on running container to clear lanReconfirmationRequired",
            )
        } finally {
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `unexpected SIGKILL 137 and signal -9 exits surface Android 12-13 and 14+ phantom-process diagnostics`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val pool = Executors.newCachedThreadPool()
        val handles = CopyOnWriteArrayList<TestMuxProcessHandle>()
        val harness = newSupervisorHarness(
            tempDir = tempDir,
            pool = pool,
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            customLauncher = { _ ->
                TestMuxProcessHandle(pool).also { handles += it }
            },
        )
        try {
            val profile = SandboxProfile(
                id = "heavy-build",
                displayName = "Heavy Build",
                imageRef = "ubuntu:24.04",
            )
            assertEquals(SandboxRunState.RUNNING, harness.supervisor.startOrResumeSandbox(profile))

            handles.single().terminateWithCode(137)
            harness.supervisor.pollTelemetryOnce(isInteractive = true)

            assertEquals(SandboxRunState.FAILED, harness.supervisor.runStates.value[profile.id])
            val notice = harness.supervisor.activeServiceNotice.value
            assertNotNull(notice, "Expected actionable diagnostic notice when librungv.so dies with SIGKILL (137)")
            assertTrue(notice!!.contains("SIGKILL") || notice.contains("137"), "Notice should mention SIGKILL/137: $notice")
            assertTrue(
                notice.contains("Disable child process restrictions"),
                "Android 14+ SIGKILL notice should mention 'Disable child process restrictions': $notice",
            )

            val readiness = harness.supervisor.hostReadiness.value
            assertTrue(readiness.phantomKillerDetected)
            assertEquals(PhantomProcessRemediation.ANDROID_14_DEV_TOGGLE, readiness.remediationKind)

            // Verify Android 12-13 (SDK 32) and signal -9 formatting as well.
            val sdk32Msg = SandboxSupervisor.formatUnexpectedExitDiagnostic(
                containerName = "Worker",
                exitCode = -9,
                hostSdkInt = Build.VERSION_CODES.S_V2,
            )
            assertTrue(sdk32Msg.contains("SIGKILL"))
            assertTrue(sdk32Msg.contains("32-process"))
            assertTrue(sdk32Msg.contains("System Diagnostics"))
        } finally {
            harness.supervisor.close()
            pool.shutdownNow()
        }
    }

    private fun assertNotEqualsRunning(state: SandboxRunState?) {
        assertFalse(state == SandboxRunState.RUNNING, "Expected state not to be RUNNING, got $state")
    }

    private fun buildDnsQuery(txId: Int, qname: String): ByteArray {
        val labels = qname.split('.').flatMap { part ->
            val bytes = part.toByteArray(StandardCharsets.US_ASCII)
            listOf(bytes.size.toByte()) + bytes.toList()
        } + listOf(0.toByte())
        val out = ByteArray(12 + labels.size + 4)
        out[0] = ((txId ushr 8) and 0xFF).toByte()
        out[1] = (txId and 0xFF).toByte()
        out[2] = 0x01
        out[5] = 0x01
        labels.forEachIndexed { idx, b -> out[12 + idx] = b }
        out[12 + labels.size + 1] = 0x01
        out[12 + labels.size + 3] = 0x01
        return out
    }

    private fun buildEdnsDnsQuery(txId: Int, qname: String, udpPayloadSize: Int): ByteArray {
        val base = buildDnsQuery(txId, qname)
        // OPT RR: root name (0x00) + TYPE=41 (0x00, 0x29) + CLASS=udpPayloadSize + TTL=0 (4B) + RDLEN=0 (2B) = 11 bytes.
        val out = ByteArray(base.size + 11)
        System.arraycopy(base, 0, out, 0, base.size)
        out[11] = 0x01 // ARCOUNT = 1
        var idx = base.size
        out[idx++] = 0x00
        out[idx++] = 0x00
        out[idx++] = 0x29
        out[idx++] = ((udpPayloadSize ushr 8) and 0xFF).toByte()
        out[idx++] = (udpPayloadSize and 0xFF).toByte()
        return out
    }

    private class SupervisorHarness(
        val supervisor: SandboxSupervisor,
        val checkpointController: CheckpointController,
        val launchedCommands: CopyOnWriteArrayList<List<String>>,
    )

    private fun newSupervisorHarness(
        tempDir: File,
        pool: java.util.concurrent.ExecutorService,
        sdkInt: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        resyncOpenStreamsJson: String = "[]",
        hostPortProbe: HostPortProbe = NoOpHostPortProbe,
        customLauncher: ((List<String>) -> TestMuxProcessHandle)? = null,
    ): SupervisorHarness {
        val filesDir = File(tempDir, "files").apply { mkdirs() }
        val cacheDir = File(tempDir, "cache").apply { mkdirs() }
        val launchedCommands = CopyOnWriteArrayList<List<String>>()
        val handlesById = java.util.concurrent.ConcurrentHashMap<String, TestMuxProcessHandle>()

        val commandBuilder = GvxCommandBuilder(filesDir, cacheDir, caExporter = { pem, dir ->
            pem.parentFile?.mkdirs()
            pem.writeText("PEM")
            dir.mkdirs()
        })
        val checkpointController = CheckpointController(
            filesDir = filesDir,
            freeSpaceProvider = { 512L * 1024L * 1024L },
            controlTransport = { sockFile, payloadLine, _, _ ->
                val dirMarker = "\"dir\":\""
                val start = payloadLine.indexOf(dirMarker) + dirMarker.length
                val end = payloadLine.indexOf('"', start)
                val slotDir = File(payloadLine.substring(start, end))
                slotDir.mkdirs()
                File(slotDir, "state.img").writeText("saved")
                val id = sockFile.nameWithoutExtension
                handlesById[id]?.terminateWithCode(0)
                """{"ok":true}"""
            },
        )
        val readinessInspector = HostReadinessInspector(
            filesDir = filesDir,
            procRoot = File(tempDir, "proc"),
            sdkIntProvider = { sdkInt },
            hostAbiProvider = { "arm64-v8a" },
            pageSizeProvider = { 16384 },
            totalHostRamMiBProvider = { 8192 },
            freeDiskMiBProvider = { 4096 },
            selfPidProvider = { 100 },
        )
        val supervisor = SandboxSupervisor(
            filesDir = filesDir,
            cacheDir = cacheDir,
            commandBuilder = commandBuilder,
            proxyBridge = LoopbackProxyBridge(InMemorySecretStore()),
            dnsProxy = LoopbackDnsProxy(NetworkDnsBackend { _, _ -> ByteArray(0) }),
            checkpointController = checkpointController,
            readinessInspector = readinessInspector,
            processLauncher = { args, _, _ ->
                launchedCommands += args
                val ctlIdx = args.indexOf("-control-socket")
                val id = if (ctlIdx >= 0) File(args[ctlIdx + 1]).nameWithoutExtension else "unknown"
                val handle = customLauncher?.invoke(args)
                    ?: TestMuxProcessHandle(pool, resyncOpenStreamsJson = resyncOpenStreamsJson)
                handlesById[id] = handle
                handle
            },
            agentExtractor = { arch ->
                File(filesDir, "agent/${arch.ociArch}/termvr-agent").apply {
                    parentFile?.mkdirs()
                    writeText("stub")
                }
            },
            hostPortProbe = hostPortProbe,
        )
        return SupervisorHarness(supervisor, checkpointController, launchedCommands)
    }

    private class TestMuxProcessHandle(
        executor: java.util.concurrent.ExecutorService,
        stderrText: String = "",
        private val resyncOpenStreamsJson: String = "[]",
        failImmediatelyWithExitCode: Int? = null,
    ) : GvxProcessHandle {
        private val agentToHostOut = PipedOutputStream()
        override val stdoutStream: InputStream = PipedInputStream(agentToHostOut, 65536)

        private val hostToAgentIn = PipedInputStream(65536)
        override val stdinStream: OutputStream = PipedOutputStream(hostToAgentIn)

        override val stderrStream: InputStream = ByteArrayInputStream(stderrText.toByteArray(StandardCharsets.UTF_8))
        override val pid: Long = 5150L

        private val alive = AtomicBoolean(true)
        private val exitCode = AtomicInteger(0)
        private val exited = CountDownLatch(1)

        override val isAlive: Boolean get() = alive.get()

        init {
            if (failImmediatelyWithExitCode != null) {
                terminateWithCode(failImmediatelyWithExitCode)
            } else {
                executor.execute {
                    try {
                        while (alive.get()) {
                            val frame = MuxCodec.readFrame(hostToAgentIn) ?: break
                            when (frame.type) {
                                MuxFrameType.MUX_HELLO -> {
                                    val ack = MuxCodec.json.encodeToString(
                                        MuxHelloPayload.serializer(),
                                        MuxHelloPayload(protocol = 1, agentVersion = "test", initDaemon = false),
                                    )
                                    synchronized(agentToHostOut) {
                                        MuxCodec.writeFrame(
                                            agentToHostOut,
                                            MuxFrame(MuxFrameType.MUX_HELLO, 0L, ack.toByteArray(StandardCharsets.UTF_8)),
                                        )
                                    }
                                }

                                MuxFrameType.MUX_RESYNC -> {
                                    synchronized(agentToHostOut) {
                                        MuxCodec.writeFrame(
                                            agentToHostOut,
                                            MuxFrame(
                                                MuxFrameType.MUX_RESYNC_ACK,
                                                0L,
                                                """{"openStreams":$resyncOpenStreamsJson}""".toByteArray(StandardCharsets.UTF_8),
                                            ),
                                        )
                                    }
                                }

                                MuxFrameType.TELEMETRY_REQ -> {
                                    val json = """{"cpuNanos":10000000,"rssBytes":16777216,"procCount":2,"listenPorts":[]}"""
                                    synchronized(agentToHostOut) {
                                        MuxCodec.writeFrame(
                                            agentToHostOut,
                                            MuxFrame(MuxFrameType.TELEMETRY_RESP, 0L, json.toByteArray(StandardCharsets.UTF_8)),
                                        )
                                    }
                                }

                                else -> Unit
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        fun terminateWithCode(code: Int) {
            exitCode.set(code)
            alive.set(false)
            exited.countDown()
            runCatching { agentToHostOut.close() }
            runCatching { hostToAgentIn.close() }
        }

        override fun waitForExit(): Int {
            exited.await()
            return exitCode.get()
        }

        override fun destroyGracefully() {
            terminateWithCode(exitCode.get())
        }
    }
}
