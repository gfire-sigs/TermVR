package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.model.LiveResourceUpdate
import java.io.DataInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal data class RecordedControlCall(
    val method: String,
    val params: JsonElement?,
    val hasFdsFlag: Boolean,
    val fdCount: Int,
)

/**
 * In-memory `gvx-ctrl/2` server speaking the exact binary framing and `SCM_RIGHTS` sentinel protocol.
 */
internal class FakeGvxControlServer(
    private val helloReplyJson: String = """{"ok":true,"version":2,"sandbox_id":"devbox","max_frame_bytes":262144,"features":["zstd","scm_rights"],"capabilities":["system.ping","sandbox.inspect","sandbox.stats","lifecycle.pause","lifecycle.stop","checkpoint.local","resource.memory","resource.cpu","resource.pids","resource.network","port.list","port.remove","port.dynamic_loopback","mount.list","mount.tmpfs","mount.remount","mount.remove","mount.volume_attach","proc.list","proc.signal","kernel.log","kernel.log_clear"]}""",
    private val sendPingBeforeResponse: Boolean = false,
    private val sendGoAway: String? = null,
    private val sendGoAwayBeforeResponse: String? = null,
    private val sendUnexpectedZstdFlag: Boolean = false,
    private val handler: (RecordedControlCall) -> String,
) {
    val calls = CopyOnWriteArrayList<RecordedControlCall>()
    val sawPongPayloads = CopyOnWriteArrayList<String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = Executors.newCachedThreadPool()

    val transport: ControlStreamTransport = ControlStreamTransport { _, _, _ ->
        val clientToServerOut = PipedOutputStream()
        val clientToServerIn = PipedInputStream(clientToServerOut, 65536)
        val serverToClientOut = PipedOutputStream()
        val serverToClientIn = PipedInputStream(serverToClientOut, 65536)
        val sentFds = CopyOnWriteArrayList<FileDescriptor>()

        executor.execute {
            runServerLoop(
                input = DataInputStream(clientToServerIn),
                output = serverToClientOut,
                sentFds = sentFds,
            )
        }

        object : ControlSocketConnection {
            override val inputStream: InputStream = serverToClientIn
            override val outputStream: OutputStream = clientToServerOut
            override fun setReadTimeoutMs(timeoutMs: Int) = Unit
            override fun sendFileDescriptorsWithSentinel(fds: List<FileDescriptor>) {
                sentFds.addAll(fds)
                outputStream.write(0)
                outputStream.flush()
            }

            override fun close() {
                runCatching { clientToServerOut.close() }
                runCatching { serverToClientIn.close() }
            }
        }
    }

    private fun runServerLoop(
        input: DataInputStream,
        output: OutputStream,
        sentFds: CopyOnWriteArrayList<FileDescriptor>,
    ) {
        try {
            val preface = ByteArray(4)
            input.readFully(preface)
            check(preface.toString(StandardCharsets.US_ASCII) == "GVX2") {
                "Invalid preface: ${preface.toString(StandardCharsets.US_ASCII)}"
            }

            val helloFrame = GvxControlClient.readFrame(input, GvxControlClient.HANDSHAKE_MAX_FRAME_BYTES)
            check(helloFrame.type == GvxControlClient.FRAME_HELLO && helloFrame.streamId == 0)
            GvxControlClient.writeFrame(
                out = output,
                type = GvxControlClient.FRAME_HELLO,
                flags = 0,
                channel = 0,
                streamId = 0,
                payload = helloReplyJson.toByteArray(StandardCharsets.UTF_8),
            )
            output.flush()

            while (true) {
                val frame = GvxControlClient.readFrame(input, GvxControlClient.DEFAULT_MAX_FRAME_BYTES)
                if (frame.type == GvxControlClient.FRAME_PONG) {
                    sawPongPayloads.add(frame.payload.toString(StandardCharsets.UTF_8))
                    continue
                }
                if (frame.type != GvxControlClient.FRAME_REQUEST) continue

                val hasFds = (frame.flags and GvxControlClient.FLAG_HAS_FDS) != 0
                val fdCount = if (hasFds) {
                    val sentinel = input.readByte().toInt()
                    check(sentinel == 0) { "Expected 0x00 SCM_RIGHTS sentinel byte, found $sentinel" }
                    val count = sentFds.size
                    sentFds.clear()
                    count
                } else {
                    0
                }

                val env = json.decodeFromString<WireRequestEnvelope>(frame.payload.toString(StandardCharsets.UTF_8))
                val recorded = RecordedControlCall(
                    method = env.method,
                    params = env.params,
                    hasFdsFlag = hasFds,
                    fdCount = fdCount,
                )
                calls.add(recorded)

                if (sendGoAway != null) {
                    GvxControlClient.writeFrame(
                        out = output,
                        type = GvxControlClient.FRAME_GO_AWAY,
                        flags = 0,
                        channel = 0,
                        streamId = 0,
                        payload = sendGoAway.toByteArray(StandardCharsets.UTF_8),
                    )
                    output.flush()
                    return
                }

                if (sendGoAwayBeforeResponse != null) {
                    GvxControlClient.writeFrame(
                        out = output,
                        type = GvxControlClient.FRAME_GO_AWAY,
                        flags = 0,
                        channel = 0,
                        streamId = 0,
                        payload = sendGoAwayBeforeResponse.toByteArray(StandardCharsets.UTF_8),
                    )
                    output.flush()
                }

                if (sendPingBeforeResponse) {
                    GvxControlClient.writeFrame(
                        out = output,
                        type = GvxControlClient.FRAME_PING,
                        flags = 0,
                        channel = 0,
                        streamId = 0,
                        payload = "hb-42".toByteArray(StandardCharsets.UTF_8),
                    )
                    output.flush()
                    val pong = GvxControlClient.readFrame(input, GvxControlClient.DEFAULT_MAX_FRAME_BYTES)
                    check(pong.type == GvxControlClient.FRAME_PONG)
                    sawPongPayloads.add(pong.payload.toString(StandardCharsets.UTF_8))
                }

                val responseJson = handler(recorded)
                val respFlags = if (sendUnexpectedZstdFlag) {
                    GvxControlClient.FLAG_COMPRESSED_ZSTD
                } else {
                    GvxControlClient.FLAG_END_STREAM
                }
                GvxControlClient.writeFrame(
                    out = output,
                    type = GvxControlClient.FRAME_RESPONSE,
                    flags = respFlags,
                    channel = 0,
                    streamId = frame.streamId,
                    payload = responseJson.toByteArray(StandardCharsets.UTF_8),
                )
                output.flush()
            }
        } catch (_: IOException) {
            // Client closed stream cleanly.
        } finally {
            runCatching { output.close() }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}

class GvxControlClientTest {

    @Test
    fun `round-trips lifecycle, resource, port, mount, proc, and kernel log RPCs over GVX2 binary frames`(
        @TempDir tempDir: File,
    ) {
        val sock = File(tempDir, "devbox.sock")
        val volFile = File(tempDir, "data.ext4").apply { writeBytes(ByteArray(4096)) }

        val server = FakeGvxControlServer(sendPingBeforeResponse = true) { call ->
            when (call.method) {
                GvxControlClient.METHOD_PING ->
                    """{"ok":true,"result":{"version":2,"sandbox_id":"devbox","state":"running","uptime_ns":1000000000}}"""
                GvxControlClient.METHOD_INSPECT ->
                    """{"ok":true,"result":{"id":"devbox","name":"devbox","state":"running","platform":"systrap","architecture":"arm64","syscall_isolation":"seccomp","lockdown_applied":true,"network_mode":"nat","eth0_ip":"10.88.0.2","eth0_mtu":1500,"resources":{"memory_bytes":536870912,"cpu_millis":2000,"guest_cpus":2,"pids_limit":128,"isolate_lan":true},"memory_used_bytes":67108864,"ports":[{"proto":"tcp","host_ip":"127.0.0.1","host_port":8080,"guest_ip":"10.88.0.2","guest_port":80,"source":"dynamic"}],"mounts":[{"target":"/","type":"overlay","source":"rootfs","read_only":false,"mutable":true},{"target":"/data","type":"ext4lite","source":"data","read_only":false,"mutable":true}],"active_exec_sessions":1,"pid_count":4,"capabilities":["lifecycle.pause"],"uptime_ns":9000000000}}"""
                GvxControlClient.METHOD_STATS ->
                    """{"ok":true,"result":{"cpu":{"user_ns":150000000,"sys_ns":50000000,"throttled":3,"throttled_ns":12000000},"memory":{"total_bytes":67108864,"anon_bytes":33554432,"file_bytes":16777216,"tmpfs_bytes":8388608,"limit_bytes":536870912},"network":[{"interface":"eth0","rx_bytes":1200,"tx_bytes":3400}],"block_io":[{"disk":"vda","read_ops":12,"write_ops":5,"read_sectors":64,"write_sectors":16}],"pids":5,"pids_limit":128}}"""
                GvxControlClient.METHOD_PAUSE -> """{"ok":true,"result":{"state":"paused"}}"""
                GvxControlClient.METHOD_UNPAUSE -> """{"ok":true,"result":{"state":"running"}}"""
                GvxControlClient.METHOD_STOP -> """{"ok":true,"result":{"signaled":"SIGTERM","killed":false}}"""
                GvxControlClient.METHOD_CHECKPOINT -> """{"ok":true,"result":{"ok":true}}"""
                GvxControlClient.METHOD_RESOURCE_UPDATE ->
                    """{"ok":true,"result":{"memory_bytes":1073741824,"cpu_millis":1500,"guest_cpus":2,"pids_limit":256,"isolate_lan":true,"net_tx_bps":25000000,"net_rx_bps":50000000}}"""
                GvxControlClient.METHOD_PORT_LIST ->
                    """{"ok":true,"result":{"ports":[{"proto":"tcp","host_ip":"127.0.0.1","host_port":8080,"guest_port":80,"source":"dynamic"}]}}"""
                GvxControlClient.METHOD_PORT_ADD ->
                    """{"ok":true,"result":{"ports":[{"proto":"tcp","host_ip":"127.0.0.1","host_port":9090,"guest_port":90,"source":"dynamic"}]}}"""
                GvxControlClient.METHOD_PORT_REMOVE ->
                    """{"ok":true,"result":{"removed":[{"proto":"tcp","host_ip":"127.0.0.1","host_port":9090,"guest_port":90,"source":"dynamic"}]}}"""
                GvxControlClient.METHOD_MOUNT_LIST ->
                    """{"ok":true,"result":{"mounts":[{"target":"/data","type":"ext4lite","source":"data","read_only":false,"mutable":true}]}}"""
                GvxControlClient.METHOD_MOUNT_ADD -> {
                    val p = call.params!!.jsonObject
                    val kind = p["type"]!!.jsonPrimitive.content
                    if (kind == "volume") {
                        assertTrue(call.hasFdsFlag)
                        assertEquals(1, call.fdCount)
                    }
                    """{"ok":true,"result":{"target":"${p["target"]!!.jsonPrimitive.content}","type":"${if (kind == "volume") "ext4lite" else "tmpfs"}","source":"${p["source"]?.jsonPrimitive?.content ?: "tmpfs"}","read_only":${p["read_only"]?.jsonPrimitive?.boolean ?: false},"mutable":true}}"""
                }
                GvxControlClient.METHOD_MOUNT_REMOUNT -> {
                    val p = call.params!!.jsonObject
                    """{"ok":true,"result":{"target":"${p["target"]!!.jsonPrimitive.content}","read_only":${p["read_only"]!!.jsonPrimitive.boolean}}}"""
                }
                GvxControlClient.METHOD_MOUNT_REMOVE ->
                    """{"ok":true,"result":{"removed":"/data"}}"""
                GvxControlClient.METHOD_PROC_LIST ->
                    """{"ok":true,"result":{"processes":[{"pid":1,"ppid":0,"state":"S","threads":2,"cpu_ns":4200,"rss_bytes":8192,"comm":"init","cmdline":["/sbin/init"]},{"pid":12,"ppid":1,"state":"R","threads":1,"cpu_ns":1000,"rss_bytes":4096,"comm":"sleep","cmdline":["sleep","60"]}]}}"""
                GvxControlClient.METHOD_PROC_SIGNAL ->
                    """{"ok":true,"result":{"signal":"SIGTERM","pid":12}}"""
                GvxControlClient.METHOD_KERNEL_LOG ->
                    """{"ok":true,"result":{"records":[{"seq":1,"level":6,"usec":1500000,"text":"gvisor booted"}]}}"""
                else -> error("Unexpected method ${call.method}")
            }
        }

        try {
            val client = GvxControlClient(transport = server.transport)

            assertTrue(client.ping(sock))
            assertTrue(server.sawPongPayloads.contains("hb-42"))

            val inspect = client.inspect(sock)
            assertEquals("devbox", inspect.id)
            assertEquals("10.88.0.2", inspect.eth0Ip)
            assertTrue(inspect.lockdownApplied)
            assertEquals(128L, inspect.resources.pidsLimit)
            assertEquals(2, inspect.mounts.size)
            assertEquals(1, inspect.ports.size)

            val stats = client.stats(sock)
            assertEquals(200_000_000L, stats.totalCpuNanos)
            assertEquals(3L, stats.cpuThrottledPeriods)
            assertEquals(67_108_864L, stats.memoryTotalBytes)
            assertEquals(1200L, stats.netRxBytes)
            assertEquals(3400L, stats.netTxBytes)
            assertEquals(1, stats.blockIo.size)
            assertEquals("vda", stats.blockIo.single().disk)
            assertEquals(12L, stats.blockReadOps)
            assertEquals(5L, stats.blockWriteOps)
            assertEquals(64L * 512L, stats.blockReadBytes)
            assertEquals(16L * 512L, stats.blockWriteBytes)
            assertEquals(5, stats.pids)

            client.pause(sock, quiesceNet = true)
            client.unpause(sock)
            assertFalse(client.stop(sock, signal = "SIGTERM", timeoutMs = 1500L))
            client.checkpoint(sock, dir = "/tmp/slot-a", leaveRunning = false)

            val updatedLimits = client.updateResources(
                sock,
                LiveResourceUpdate(
                    memoryMiB = 1024,
                    cpuCores = 1.5,
                    pidsLimit = 256,
                    isolateLan = true,
                    netTxBps = 25_000_000L,
                    netRxBps = 50_000_000L,
                ),
            )
            assertEquals(1_073_741_824L, updatedLimits.memoryBytes)
            assertEquals(1500L, updatedLimits.cpuMillis)
            assertEquals(256L, updatedLimits.pidsLimit)
            assertTrue(updatedLimits.isolateLan)
            assertEquals(25_000_000L, updatedLimits.netTxBps)
            assertEquals(50_000_000L, updatedLimits.netRxBps)
            val resCall = server.calls.first { it.method == GvxControlClient.METHOD_RESOURCE_UPDATE }
            assertEquals(1024L * 1024L * 1024L, resCall.params!!.jsonObject["memory_bytes"]!!.jsonPrimitive.long)
            assertEquals(1500L, resCall.params.jsonObject["cpu_millis"]!!.jsonPrimitive.long)
            assertEquals(256L, resCall.params.jsonObject["pids_limit"]!!.jsonPrimitive.long)
            assertEquals(25_000_000L, resCall.params.jsonObject["net_tx_bps"]!!.jsonPrimitive.long)
            assertEquals(50_000_000L, resCall.params.jsonObject["net_rx_bps"]!!.jsonPrimitive.long)

            assertEquals(1, client.listPorts(sock).size)
            val addedPorts = client.addPorts(sock, listOf("127.0.0.1:9090:90/tcp"))
            assertEquals(9090, addedPorts.single().hostPort)
            val removedPorts = client.removePorts(sock, specs = listOf("127.0.0.1:9090:90/tcp"), force = true)
            assertEquals(9090, removedPorts.single().hostPort)

            assertEquals(1, client.listMounts(sock).size)
            val tmpfsMount = client.addTmpfsMount(sock, target = "/scratch", sizeBytes = 16L * 1024L * 1024L)
            assertEquals("/scratch", tmpfsMount.target)
            val volMount = client.attachVolumeMount(
                socketFile = sock,
                volumeName = "data",
                volumeFile = volFile,
                target = "/data",
                readOnly = false,
            )
            assertEquals("ext4lite", volMount.type)
            assertEquals("/data", volMount.target)

            assertTrue(client.remount(sock, target = "/data", readOnly = true))
            client.removeMount(sock, target = "/data")
            val rmCall = server.calls.first { it.method == GvxControlClient.METHOD_MOUNT_REMOVE }
            assertEquals(null, rmCall.params!!.jsonObject["detach"])

            val procs = client.listProcesses(sock)
            assertEquals(2, procs.size)
            assertEquals("sleep 60", procs[1].displayCommand)
            client.signalProcess(sock, pid = 12, signal = "SIGTERM")

            val dmesg = client.kernelLog(sock, tail = 50, clear = true)
            assertEquals(1, dmesg.size)
            assertEquals("gvisor booted", dmesg.single().text)

            // Verify gvx-ctrl/2 v0.21 wire method/capability/feature constants match gvx/pkg/gvx/control/types.go
            assertEquals("sandbox.events", GvxControlClient.METHOD_EVENTS)
            assertEquals("net.policy_get", GvxControlClient.METHOD_NET_POLICY_GET)
            assertEquals("net.policy_set", GvxControlClient.METHOD_NET_POLICY_SET)
            assertEquals("net.unix_forward", GvxControlClient.METHOD_UNIX_FORWARD)
            assertEquals("net.unix_forward", GvxControlClient.CAP_UNIX_FORWARD)
            assertEquals("mount.bind_attach", GvxControlClient.CAP_MOUNT_BIND_ATTACH)
            assertEquals("net_qos", GvxControlClient.FEATURE_NET_QOS)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `propagates structured errors, hello rejections, go-away, and protocol guards`(
        @TempDir tempDir: File,
    ) {
        val sock = File(tempDir, "err.sock")

        // 1. Handshake rejection
        val authServer = FakeGvxControlServer(
            helloReplyJson = """{"ok":false,"version":2,"error":{"code":"UNAUTHORIZED","message":"bad token"}}""",
        ) { """{"ok":true}""" }
        try {
            val ex = assertThrows(GvxControlException::class.java) {
                GvxControlClient(transport = authServer.transport).ping(sock)
            }
            assertEquals(GvxControlClient.CODE_UNAUTHORIZED, ex.code)
        } finally {
            authServer.shutdown()
        }

        // 2. Structured RPC error with errno & retryable
        val errServer = FakeGvxControlServer {
            """{"ok":false,"error":{"code":"LIMIT_EXCEEDED","message":"below usage","errno":16,"retryable":true}}"""
        }
        try {
            val ex = assertThrows(GvxControlException::class.java) {
                GvxControlClient(transport = errServer.transport).updateResources(
                    sock,
                    LiveResourceUpdate(memoryMiB = 64),
                )
            }
            assertEquals(GvxControlClient.CODE_LIMIT_EXCEEDED, ex.code)
            assertEquals(16, ex.errno)
            assertTrue(ex.retryable)
        } finally {
            errServer.shutdown()
        }

        // 3. GO_AWAY frame with JSON ProtoError followed by EOF throws decoded error
        val goAwayServer = FakeGvxControlServer(
            sendGoAway = """{"code":"SANDBOX_EXITING","message":"shutting down"}""",
        ) { """{"ok":true}""" }
        try {
            val ex = assertThrows(GvxControlException::class.java) {
                GvxControlClient(transport = goAwayServer.transport).ping(sock)
            }
            assertEquals(GvxControlClient.CODE_SANDBOX_EXITING, ex.code)
            assertEquals("shutting down", ex.message)
        } finally {
            goAwayServer.shutdown()
        }

        // 3b. GO_AWAY arriving before in-flight checkpoint/stop FRAME_RESPONSE drains cleanly and succeeds!
        val drainServer = FakeGvxControlServer(
            sendGoAwayBeforeResponse = """{"code":"SANDBOX_EXITING","message":"sandbox exiting"}""",
        ) { """{"ok":true,"result":{"ok":true}}""" }
        try {
            GvxControlClient(transport = drainServer.transport).checkpoint(sock, dir = "/tmp/slot-a")
        } finally {
            drainServer.shutdown()
        }

        // 3c. Go nil-slice JSON payloads ({"records":null}, {"ports":null}, {"mounts":null}) decode as empty lists!
        val nilSliceServer = FakeGvxControlServer { call ->
            when (call.method) {
                GvxControlClient.METHOD_KERNEL_LOG -> """{"ok":true,"result":{"records":null}}"""
                GvxControlClient.METHOD_PORT_LIST -> """{"ok":true,"result":{"ports":null}}"""
                GvxControlClient.METHOD_MOUNT_LIST -> """{"ok":true,"result":{"mounts":null}}"""
                GvxControlClient.METHOD_PROC_LIST -> """{"ok":true,"result":{"processes":null}}"""
                else -> """{"ok":true,"result":{}}"""
            }
        }
        try {
            val nilClient = GvxControlClient(transport = nilSliceServer.transport)
            assertTrue(nilClient.kernelLog(sock).isEmpty())
            assertTrue(nilClient.listPorts(sock).isEmpty())
            assertTrue(nilClient.listMounts(sock).isEmpty())
            assertTrue(nilClient.listProcesses(sock).isEmpty())
        } finally {
            nilSliceServer.shutdown()
        }

        // 3d. attachVolumeMount checks negotiated scm_rights feature before sending FLAG_HAS_FDS
        val noScmServer = FakeGvxControlServer(
            helloReplyJson = """{"ok":true,"version":2,"sandbox_id":"devbox","max_frame_bytes":262144,"features":["zstd"],"capabilities":["mount.list"]}""",
        ) { """{"ok":true}""" }
        val dummyVol = File(tempDir, "vol.ext4").apply { writeBytes(ByteArray(1024)) }
        try {
            val ex = assertThrows(GvxControlException::class.java) {
                GvxControlClient(transport = noScmServer.transport).attachVolumeMount(sock, "vol", dummyVol, "/data")
            }
            assertEquals(GvxControlClient.CODE_LOCKDOWN_DENIED, ex.code)
            assertTrue(noScmServer.calls.isEmpty())
        } finally {
            noScmServer.shutdown()
        }

        // 3e. signalProcess rejects pid <= 0 unless all=true
        assertThrows(IllegalArgumentException::class.java) {
            GvxControlClient(transport = errServer.transport).signalProcess(sock, pid = 0, signal = "SIGTERM", all = false)
        }

        // 4. Rejects unexpected zstd-compressed response frame
        val zstdServer = FakeGvxControlServer(sendUnexpectedZstdFlag = true) { """{"ok":true}""" }
        try {
            val ex = assertThrows(IOException::class.java) {
                GvxControlClient(transport = zstdServer.transport).ping(sock)
            }
            assertTrue(ex.message.orEmpty().contains("zstd"))
        } finally {
            zstdServer.shutdown()
        }

        // 5. Missing volume image file rejected before opening socket
        val client = GvxControlClient(transport = errServer.transport)
        assertThrows(IOException::class.java) {
            client.attachVolumeMount(sock, "missing", File(tempDir, "nope.ext4"), "/mnt/nope")
        }

        // 6. Rejects Unix socket paths > 107 bytes
        val longSock = File("/tmp/" + "c".repeat(120) + ".sock")
        assertThrows(IOException::class.java) {
            AndroidLocalControlStreamTransport.open(longSock, 1000, 1000)
        }
    }
}
