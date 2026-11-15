package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.data.InMemorySecretStore
import dev.gfire.termvr.core.model.ClipboardReadPolicy
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MuxSessionClientTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `codec rejects invalid magic, version, opcode, oversized payload, and stream ID violations`() {
        // Invalid magic
        val badMagic = byteArrayOf(0x12, 0x34, 0x01, 0x01, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThrows(MuxProtocolException::class.java) {
            MuxCodec.readFrame(ByteArrayInputStream(badMagic))
        }

        // Unsupported version
        val badVer = byteArrayOf(0x54, 0x56, 0x02, 0x01, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThrows(MuxProtocolException::class.java) {
            MuxCodec.readFrame(ByteArrayInputStream(badVer))
        }

        // Unknown opcode
        val badOp = byteArrayOf(0x54, 0x56, 0x01, 0x7F, 0, 0, 0, 0, 0, 0, 0, 0)
        assertThrows(MuxProtocolException::class.java) {
            MuxCodec.readFrame(ByteArrayInputStream(badOp))
        }

        // Payload > 65536
        val bigLen = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x5456)
            .put(0x01)
            .put(0x04)
            .putInt(1)
            .putInt(65_537)
            .array()
        assertThrows(MuxProtocolException::class.java) {
            MuxCodec.readFrame(ByteArrayInputStream(bigLen))
        }

        // Stream-0-only opcode on stream 1
        assertThrows(MuxProtocolException::class.java) {
            MuxFrame(MuxFrameType.TELEMETRY_REQ, streamId = 1L)
        }

        // Stream >= 1 opcode on stream 0
        assertThrows(MuxProtocolException::class.java) {
            MuxFrame(MuxFrameType.OPEN_PTY, streamId = 0L)
        }
    }

    @Test
    fun `PTY open, 256 KiB sliding-window flow control, telemetry, resync, and exit work end-to-end`() {
        val hostToGuestOut = PipedOutputStream()
        val guestReads = PipedInputStream(hostToGuestOut, 1024 * 1024)
        val guestToHostOut = PipedOutputStream()
        val hostReads = PipedInputStream(guestToHostOut, 1024 * 1024)

        val receivedByGuest = ByteArrayOutputStream()
        val resyncCalled = AtomicBoolean(false)
        val pool = Executors.newSingleThreadExecutor()
        val guestDone = CountDownLatch(1)

        pool.execute {
            try {
                // Expect host MUX_HELLO and reply with guest MUX_HELLO.
                val hostHello = MuxCodec.readFrame(guestReads)!!
                assertEquals(MuxFrameType.MUX_HELLO, hostHello.type)
                val guestHello = MuxCodec.json.encodeToString(
                    MuxHelloPayload(protocol = 1, agentVersion = "0.1.0", initDaemon = true),
                ).toByteArray(StandardCharsets.UTF_8)
                MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.MUX_HELLO, 0L, guestHello))

                while (true) {
                    val f = MuxCodec.readFrame(guestReads) ?: break
                    when (f.type) {
                        MuxFrameType.OPEN_PTY -> {
                            MuxCodec.writeFrame(
                                guestToHostOut,
                                MuxFrame(MuxFrameType.OPEN_PTY_ACK, f.streamId, byteArrayOf(0x00)),
                            )
                        }

                        MuxFrameType.PTY_DATA -> {
                            receivedByGuest.write(f.payload)
                            // Grant credit after consuming 256 KiB.
                            if (receivedByGuest.size() == MuxCodec.INITIAL_STREAM_WINDOW) {
                                MuxCodec.writeFrame(
                                    guestToHostOut,
                                    MuxFrame(
                                        MuxFrameType.PTY_WINDOW_UPDATE,
                                        f.streamId,
                                        MuxCodec.encodeWindowUpdate(MuxCodec.INITIAL_STREAM_WINDOW),
                                    ),
                                )
                            }
                        }

                        MuxFrameType.TELEMETRY_REQ -> {
                            val snap = MuxCodec.json.encodeToString(
                                GuestTelemetryPayload(
                                    cpuNanos = 123_456_789L,
                                    rssBytes = 42L * 1024L * 1024L,
                                    procCount = 7,
                                    listenPorts = listOf(8080, 6443),
                                ),
                            ).toByteArray(StandardCharsets.UTF_8)
                            MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.TELEMETRY_RESP, 0L, snap))
                        }

                        MuxFrameType.MUX_RESYNC -> {
                            resyncCalled.set(true)
                            val ack = MuxCodec.json.encodeToString(
                                MuxResyncAckPayload(listOf(ResyncOpenStreamInfo(id = 1L, rows = 40, cols = 120))),
                            ).toByteArray(StandardCharsets.UTF_8)
                            MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.MUX_RESYNC_ACK, 0L, ack))
                        }

                        MuxFrameType.PTY_SIGNAL -> {
                            MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.PTY_EXIT, f.streamId, byteArrayOf(0x00)))
                            break
                        }

                        else -> Unit
                    }
                }
            } finally {
                guestDone.countDown()
            }
        }

        val exits = CopyOnWriteArrayList<Int>()
        val client = MuxSessionClient(
            inputStream = hostReads,
            outputStream = hostToGuestOut,
            sharesDir = File(tempDir, "shares"),
        )
        try {
            client.start(sendHostHello = true)
            val hello = client.awaitHello()
            assertEquals("0.1.0", hello.agentVersion)
            assertTrue(hello.initDaemon)

            val pty = client.openPty(
                rows = 40,
                cols = 120,
                listener = object : MuxStreamListener {
                    override fun onData(streamId: Long, bytes: ByteArray) {}
                    override fun onExit(streamId: Long, exitCode: Int) {
                        exits.add(exitCode)
                    }
                },
            )

            // Write 300 KiB (> 256 KiB window) to prove sliding-window update unblocks remaining bytes.
            val payload300k = ByteArray(300 * 1024) { (it % 251).toByte() }
            pty.writeInput(payload300k)

            val telemetry = client.requestTelemetry()
            assertEquals(123_456_789L, telemetry.cpuNanos)
            assertEquals(7, telemetry.procCount)
            assertEquals(listOf(8080, 6443), telemetry.listenPorts)

            val resyncAck = client.resync()
            assertTrue(resyncCalled.get())
            assertEquals(1, resyncAck.openStreams.size)
            assertEquals(40, resyncAck.openStreams[0].rows)

            pty.signal(15)
            assertTrue(guestDone.await(5, TimeUnit.SECONDS))
            assertArrayEquals(payload300k, receivedByGuest.toByteArray())
        } finally {
            client.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `resync advances nextPtyStreamId past restored streams and attachPtyHandle reattaches stream`() {
        val hostToGuestOut = PipedOutputStream()
        val guestReads = PipedInputStream(hostToGuestOut, 1024 * 1024)
        val guestToHostOut = PipedOutputStream()
        val hostReads = PipedInputStream(guestToHostOut, 1024 * 1024)

        val openedIds = CopyOnWriteArrayList<Long>()
        val attachedData = CopyOnWriteArrayList<String>()
        val pool = Executors.newSingleThreadExecutor()
        val guestDone = CountDownLatch(1)

        pool.execute {
            try {
                while (true) {
                    val frame = MuxCodec.readFrame(guestReads) ?: break
                    when (frame.type) {
                        MuxFrameType.MUX_RESYNC -> {
                            val ackJson = """{"openStreams":[{"id":1,"rows":24,"cols":80},{"id":4,"rows":30,"cols":100}]}"""
                            MuxCodec.writeFrame(
                                guestToHostOut,
                                MuxFrame(MuxFrameType.MUX_RESYNC_ACK, 0L, ackJson.toByteArray(StandardCharsets.UTF_8)),
                            )
                        }

                        MuxFrameType.OPEN_PTY -> {
                            openedIds.add(frame.streamId)
                            MuxCodec.writeFrame(
                                guestToHostOut,
                                MuxFrame(MuxFrameType.OPEN_PTY_ACK, frame.streamId, byteArrayOf(0)),
                            )
                            break
                        }

                        else -> Unit
                    }
                }
            } finally {
                guestDone.countDown()
            }
        }

        val client = MuxSessionClient(
            inputStream = hostReads,
            outputStream = hostToGuestOut,
            sharesDir = File(tempDir, "shares"),
        )
        try {
            client.start(sendHostHello = false)
            val ack = client.resync()
            assertEquals(listOf(1L, 4L), ack.openStreams.map { it.id })

            val reattached = client.attachPtyHandle(
                streamId = 4L,
                listener = object : MuxStreamListener {
                    override fun onData(streamId: Long, bytes: ByteArray) {
                        attachedData.add("$streamId:${String(bytes, StandardCharsets.UTF_8)}")
                    }
                    override fun onExit(streamId: Long, exitCode: Int) {}
                },
            )
            assertEquals(4L, reattached.streamId)

            val freshPty = client.openPty()
            assertEquals(5L, freshPty.streamId)
            assertTrue(guestDone.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(5L), openedIds)

            MuxCodec.writeFrame(
                guestToHostOut,
                MuxFrame(MuxFrameType.PTY_DATA, 4L, "resumed".toByteArray(StandardCharsets.UTF_8)),
            )
            Thread.sleep(50L)
            assertEquals(listOf("4:resumed"), attachedData)
        } finally {
            client.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `HOST_RPC ops 1 to 5 enforce scheme, size, policy, share confinement, and chunked volume ops`() {
        val hostToGuestOut = PipedOutputStream()
        val guestReads = PipedInputStream(hostToGuestOut, 1024 * 1024)
        val guestToHostOut = PipedOutputStream()
        val hostReads = PipedInputStream(guestToHostOut, 1024 * 1024)

        val openedUrls = CopyOnWriteArrayList<String>()
        val clipboardBox = CopyOnWriteArrayList<String>()
        val sharedFiles = CopyOnWriteArrayList<Pair<File, String>>()
        var policy = ClipboardReadPolicy.DISABLED
        var askAllowed = false

        val rpcDelegate = object : HostRpcDelegate {
            override fun openUrl(url: String) {
                openedUrls.add(url)
            }

            override fun writeClipboard(text: String) {
                clipboardBox.clear()
                clipboardBox.add(text)
            }

            override fun readClipboard(): String? = clipboardBox.firstOrNull()

            override fun confirmClipboardRead(): Boolean = askAllowed

            override fun shareFile(sharedFile: File, displayName: String) {
                sharedFiles.add(sharedFile to displayName)
            }
        }

        val client = MuxSessionClient(
            inputStream = hostReads,
            outputStream = hostToGuestOut,
            sharesDir = File(tempDir, "shares"),
            hostRpcDelegate = rpcDelegate,
            clipboardReadPolicyProvider = { policy },
        )

        val pool = Executors.newSingleThreadExecutor()
        val guestRpcDone = CountDownLatch(1)
        val testDone = CountDownLatch(1)
        val guestErrors = CopyOnWriteArrayList<String>()

        pool.execute {
            try {
                // Consume host hello
                MuxCodec.readFrame(guestReads)

                fun callGuestToHostRpc(reqId: Long, op: Int, chunks: List<ByteArray>): Pair<Int, ByteArray> {
                    chunks.forEachIndexed { idx, data ->
                        val flags = if (idx == chunks.lastIndex) MuxCodec.RPC_FLAG_FIN else 0
                        MuxCodec.writeFrame(
                            guestToHostOut,
                            MuxFrame(
                                MuxFrameType.HOST_RPC_REQ,
                                0L,
                                MuxCodec.encodeRpcChunk(reqId, op, flags, data),
                            ),
                        )
                    }
                    val respBuf = ByteArrayOutputStream()
                    var lastStatus = -1
                    while (true) {
                        val f = MuxCodec.readFrame(guestReads)!!
                        if (f.type != MuxFrameType.HOST_RPC_RESP) continue
                        val c = MuxCodec.decodeRpcChunk(f.payload)
                        if (c.reqId != reqId) continue
                        lastStatus = c.code
                        respBuf.write(c.data)
                        if ((c.flags and MuxCodec.RPC_FLAG_FIN) != 0) break
                    }
                    return lastStatus to respBuf.toByteArray()
                }

                // Op 1: OPEN_URL
                val (s1Bad, _) = callGuestToHostRpc(1L, MuxCodec.RPC_OP_OPEN_URL, listOf("file:///etc/passwd".toByteArray()))
                if (s1Bad != MuxCodec.RPC_STATUS_DENIED) guestErrors.add("expected denied for file://, got $s1Bad")

                val (s1Ok, _) = callGuestToHostRpc(2L, MuxCodec.RPC_OP_OPEN_URL, listOf("https://termvr.dev/docs".toByteArray()))
                if (s1Ok != MuxCodec.RPC_STATUS_OK) guestErrors.add("expected ok for https, got $s1Ok")

                // Op 2: CLIPBOARD_WRITE (> 1 MiB rejected, <= 1 MiB accepted)
                val oversize = ByteArray(MuxCodec.MAX_CLIPBOARD_BYTES + 1) { 'a'.code.toByte() }
                val oversizedChunks = oversize.toList().chunked(MuxCodec.MAX_RPC_CHUNK_BYTES).map { it.toByteArray() }
                val (s2Bad, _) = callGuestToHostRpc(3L, MuxCodec.RPC_OP_CLIPBOARD_WRITE, oversizedChunks)
                if (s2Bad != MuxCodec.RPC_STATUS_BAD_REQUEST) guestErrors.add("expected bad req for >1MiB clip, got $s2Bad")

                val (s2Ok, _) = callGuestToHostRpc(4L, MuxCodec.RPC_OP_CLIPBOARD_WRITE, listOf("hello clip".toByteArray()))
                if (s2Ok != MuxCodec.RPC_STATUS_OK) guestErrors.add("expected ok for clip write, got $s2Ok")

                // Op 3: CLIPBOARD_READ policy enforcement
                policy = ClipboardReadPolicy.DISABLED
                val (s3Dis, _) = callGuestToHostRpc(5L, MuxCodec.RPC_OP_CLIPBOARD_READ, listOf(ByteArray(0)))
                if (s3Dis != MuxCodec.RPC_STATUS_DENIED) guestErrors.add("expected denied for DISABLED, got $s3Dis")

                policy = ClipboardReadPolicy.ASK_EACH_TIME
                askAllowed = false
                val (s3AskNo, _) = callGuestToHostRpc(6L, MuxCodec.RPC_OP_CLIPBOARD_READ, listOf(ByteArray(0)))
                if (s3AskNo != MuxCodec.RPC_STATUS_DENIED) guestErrors.add("expected denied for ask=false")

                askAllowed = true
                val (s3AskYes, clipData) = callGuestToHostRpc(7L, MuxCodec.RPC_OP_CLIPBOARD_READ, listOf(ByteArray(0)))
                if (s3AskYes != MuxCodec.RPC_STATUS_OK || String(clipData) != "hello clip") {
                    guestErrors.add("expected hello clip on ask=true")
                }

                // Op 4: SHARE_FILE strips path traversal and streams multi-chunk file
                val hdr = "{\"name\":\"../../escape/report.txt\",\"size\":11}\n".toByteArray()
                val (s4Ok, _) = callGuestToHostRpc(8L, MuxCodec.RPC_OP_SHARE_FILE, listOf(hdr, "hello ".toByteArray(), "world".toByteArray()))
                if (s4Ok != MuxCodec.RPC_STATUS_OK) guestErrors.add("expected ok for SHARE_FILE, got $s4Ok")
                guestRpcDone.countDown()

                // Now serve Op 5 VOLUME_FS_OP from Host -> Guest (write > 32 KiB + readdir)
                val writeChunks = ByteArrayOutputStream()
                var writeReqId = 0L
                while (true) {
                    val f = MuxCodec.readFrame(guestReads)!!
                    if (f.type != MuxFrameType.HOST_RPC_REQ) continue
                    val c = MuxCodec.decodeRpcChunk(f.payload)
                    writeReqId = c.reqId
                    writeChunks.write(c.data)
                    if ((c.flags and MuxCodec.RPC_FLAG_FIN) != 0) break
                }
                val rawWrite = writeChunks.toByteArray()
                val nl = rawWrite.indexOf('\n'.code.toByte())
                val reqObj = MuxCodec.json.decodeFromString<VolumeFsRequest>(String(rawWrite, 0, nl, StandardCharsets.UTF_8))
                val writtenLen = (rawWrite.size - nl - 1).toLong()
                if (reqObj.op != "write" || writtenLen != 40_000L) {
                    guestErrors.add("unexpected volume write len $writtenLen")
                }
                val writeResp = MuxCodec.json.encodeToString(VolumeWriteResult(bytesWritten = writtenLen)).toByteArray()
                MuxCodec.writeFrame(
                    guestToHostOut,
                    MuxFrame(
                        MuxFrameType.HOST_RPC_RESP,
                        0L,
                        MuxCodec.encodeRpcChunk(writeReqId, MuxCodec.RPC_STATUS_OK, MuxCodec.RPC_FLAG_FIN, writeResp),
                    ),
                )

                // Next Host -> Guest readdir
                val dirReqFrame = MuxCodec.readFrame(guestReads)!!
                val dirChunk = MuxCodec.decodeRpcChunk(dirReqFrame.payload)
                val dirResp = MuxCodec.json.encodeToString(
                    VolumeReadDirResult(listOf(VolumeDirEntry(name = "big.bin", size = 40_000L))),
                ).toByteArray()
                MuxCodec.writeFrame(
                    guestToHostOut,
                    MuxFrame(
                        MuxFrameType.HOST_RPC_RESP,
                        0L,
                        MuxCodec.encodeRpcChunk(dirChunk.reqId, MuxCodec.RPC_STATUS_OK, MuxCodec.RPC_FLAG_FIN, dirResp),
                    ),
                )
            } catch (e: Exception) {
                guestErrors.add(e.toString())
            } finally {
                testDone.countDown()
            }
        }

        try {
            client.start(sendHostHello = true)
            assertTrue(guestRpcDone.await(5, TimeUnit.SECONDS))
            val data40k = ByteArray(40_000) { (it % 127).toByte() }
            val written = client.volumeWriteBytes("/mnt/volume", "big.bin", data40k)
            assertEquals(40_000L, written)

            val entries = client.volumeReadDir("/mnt/volume", ".")
            assertEquals(1, entries.size)
            assertEquals("big.bin", entries[0].name)

            assertTrue(testDone.await(5, TimeUnit.SECONDS))
            assertTrue(guestErrors.isEmpty(), "Guest RPC errors: $guestErrors")
            assertEquals(listOf("https://termvr.dev/docs"), openedUrls)
            assertEquals(1, sharedFiles.size)
            assertEquals("report.txt", sharedFiles[0].second)
            assertTrue(sharedFiles[0].first.canonicalPath.startsWith(File(tempDir, "shares").canonicalPath))
            assertEquals("hello world", sharedFiles[0].first.readText())
        } finally {
            client.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun `PROXY_CONNECT enforces isolateLanEgress and relays TCP bytes on valid target`() {
        val targetServer = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            runCatching {
                targetServer.accept().use { s ->
                    val buf = ByteArray(16)
                    val n = s.getInputStream().read(buf)
                    if (n > 0) {
                        s.getOutputStream().write("OK:".toByteArray() + buf.copyOfRange(0, n))
                        s.getOutputStream().flush()
                    }
                }
            }
        }

        val bridge = LoopbackProxyBridge(
            secretStore = InMemorySecretStore(),
            dnsLookup = { host ->
                if (host == "127.0.0.1" || host == "192.168.1.1") {
                    arrayOf(InetAddress.getByName(host))
                } else {
                    arrayOf(InetAddress.getByName("93.184.216.34"))
                }
            },
        )
        bridge.updatePolicy(OutboundProxyConfig(mode = OutboundProxyMode.DIRECT), isolateLanEgress = true)

        val hostToGuestOut = PipedOutputStream()
        val guestReads = PipedInputStream(hostToGuestOut, 1024 * 1024)
        val guestToHostOut = PipedOutputStream()
        val hostReads = PipedInputStream(guestToHostOut, 1024 * 1024)
        val client = MuxSessionClient(
            inputStream = hostReads,
            outputStream = hostToGuestOut,
            sharesDir = File(tempDir, "shares"),
            proxyBridge = bridge,
        )

        try {
            client.start(sendHostHello = false)
            // Guest asks to connect to 192.168.1.1:80 while isolateLanEgress=true
            val hostBytes = "192.168.1.1".toByteArray()
            val pConnect = ByteBuffer.allocate(2 + hostBytes.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(80)
                .put(hostBytes)
                .array()
            val streamId = 0x40000001L
            MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.PROXY_CONNECT, streamId, pConnect))

            val ackFrame = MuxCodec.readFrame(guestReads)!!
            assertEquals(MuxFrameType.PROXY_CONNECT_ACK, ackFrame.type)
            assertEquals(streamId, ackFrame.streamId)
            assertEquals(LoopbackProxyBridge.STATUS_NOT_ALLOWED, ackFrame.payload[0].toInt() and 0xFF)

            // Disable LAN isolation and connect to local test echo server expecting 0x00 and relayed TCP bytes.
            bridge.updatePolicy(OutboundProxyConfig(mode = OutboundProxyMode.DIRECT), isolateLanEgress = false)
            val loopBytes = "127.0.0.1".toByteArray()
            val pConnectOk = ByteBuffer.allocate(2 + loopBytes.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(targetServer.localPort.toShort())
                .put(loopBytes)
                .array()
            val streamId2 = 0x40000002L
            MuxCodec.writeFrame(guestToHostOut, MuxFrame(MuxFrameType.PROXY_CONNECT, streamId2, pConnectOk))
            val ack2 = MuxCodec.readFrame(guestReads)!!
            assertEquals(MuxFrameType.PROXY_CONNECT_ACK, ack2.type)
            assertEquals(MuxSessionClient.PROXY_STATUS_OK, ack2.payload[0].toInt() and 0xFF)

            MuxCodec.writeFrame(
                guestToHostOut,
                MuxFrame(MuxFrameType.PTY_DATA, streamId2, "hello".toByteArray(StandardCharsets.UTF_8)),
            )
            var relayed = ""
            while (true) {
                val dataFrame = MuxCodec.readFrame(guestReads)!!
                if (dataFrame.type == MuxFrameType.PTY_DATA && dataFrame.streamId == streamId2) {
                    relayed += String(dataFrame.payload, StandardCharsets.UTF_8)
                    if (relayed == "OK:hello") break
                }
            }
            assertEquals("OK:hello", relayed)
        } finally {
            client.close()
            bridge.close()
            targetServer.close()
            pool.shutdownNow()
        }
    }
}
