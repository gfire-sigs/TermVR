package dev.gfire.termvr.core.runtime

import dev.gfire.termvr.core.data.InMemorySecretStore
import dev.gfire.termvr.core.model.OutboundProxyConfig
import dev.gfire.termvr.core.model.OutboundProxyMode
import dev.gfire.termvr.core.model.ProxyEndpointSpec
import dev.gfire.termvr.core.model.ProxyProtocol
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoopbackDnsProxyTest {

    private fun buildSampleDnsQuery(txId: Int = 0x1234, qname: String = "example.com"): ByteArray {
        val labels = qname.split('.').flatMap { part ->
            val bytes = part.toByteArray()
            listOf(bytes.size.toByte()) + bytes.toList()
        } + listOf(0.toByte())
        val out = ByteArray(12 + labels.size + 4)
        out[0] = ((txId ushr 8) and 0xFF).toByte()
        out[1] = (txId and 0xFF).toByte()
        out[2] = 0x01 // RD=1
        out[5] = 0x01 // QDCOUNT=1
        labels.forEachIndexed { idx, b -> out[12 + idx] = b }
        // QTYPE=1 (A), QCLASS=1 (IN)
        out[12 + labels.size + 1] = 0x01
        out[12 + labels.size + 3] = 0x01
        return out
    }

    @Test
    fun `offline rawQuery returns immediate RFC 1035 SERVFAIL over UDP and TCP`() {
        val dns = LoopbackDnsProxy(
            networkDnsBackend = { _, _ -> null }, // offline
        )
        try {
            val port = dns.start(0)
            val query = buildSampleDnsQuery(txId = 0xABCD, qname = "offline.test")

            // UDP check
            DatagramSocket().use { udp ->
                udp.soTimeout = 2_000
                val req = DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port)
                udp.send(req)
                val respBuf = ByteArray(512)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                udp.receive(respPkt)
                val resp = respPkt.data.copyOfRange(0, respPkt.length)
                assertEquals(query[0], resp[0])
                assertEquals(query[1], resp[1])
                assertTrue((resp[2].toInt() and 0x80) != 0) // QR=1
                assertEquals(2, resp[3].toInt() and 0x0F) // RCODE=2 SERVFAIL
                assertEquals(query.size, resp.size) // question preserved
            }

            // TCP check
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
                val respLen = din.readUnsignedShort()
                val resp = ByteArray(respLen).also { din.readFully(it) }
                assertEquals(query[0], resp[0])
                assertEquals(query[1], resp[1])
                assertEquals(2, resp[3].toInt() and 0x0F)
            }
        } finally {
            dns.close()
        }
    }

    @Test
    fun `routeDnsViaProxy tunnels RFC 1035 TCP DNS through LoopbackProxyBridge without calling NetworkDnsBackend`() {
        val localBackendCalls = AtomicInteger(0)
        val tunneledQueries = AtomicInteger(0)

        // Mock SOCKS5 server that expects ATYP=0x01 for 1.1.1.1:53 and serves a synthetic DNS answer.
        val mockSocks = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        pool.execute {
            while (!mockSocks.isClosed) {
                val client = runCatching { mockSocks.accept() }.getOrNull() ?: break
                pool.execute {
                    client.use { s ->
                        val din = DataInputStream(s.getInputStream())
                        val out = s.getOutputStream()
                        assertEquals(0x05, din.readUnsignedByte())
                        val nm = din.readUnsignedByte()
                        ByteArray(nm).also { din.readFully(it) }
                        out.write(byteArrayOf(0x05, 0x00))
                        out.flush()

                        assertEquals(0x05, din.readUnsignedByte())
                        assertEquals(0x01, din.readUnsignedByte())
                        assertEquals(0x00, din.readUnsignedByte())
                        val atyp = din.readUnsignedByte()
                        assertEquals(0x01, atyp)
                        val ip = ByteArray(4).also { din.readFully(it) }
                        assertArrayEquals(byteArrayOf(1, 1, 1, 1), ip)
                        assertEquals(53, din.readUnsignedShort())
                        out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0))
                        out.flush()

                        val qLen = din.readUnsignedShort()
                        val q = ByteArray(qLen).also { din.readFully(it) }
                        tunneledQueries.incrementAndGet()
                        val ans = q.copyOf()
                        ans[2] = (ans[2].toInt() or 0x80).toByte() // QR=1, NOERROR
                        val framed = ByteBuffer.allocate(2 + ans.size)
                            .putShort(ans.size.toShort())
                            .put(ans)
                            .array()
                        out.write(framed)
                        out.flush()
                    }
                }
            }
        }

        val bridge = LoopbackProxyBridge(secretStore = InMemorySecretStore())
        bridge.updatePolicy(
            OutboundProxyConfig(
                mode = OutboundProxyMode.CUSTOM,
                customEndpoint = ProxyEndpointSpec(
                    protocol = ProxyProtocol.SOCKS5,
                    host = "127.0.0.1",
                    port = mockSocks.localPort,
                ),
                routeDnsViaProxy = true,
            ),
            isolateLanEgress = true,
        )

        val dns = LoopbackDnsProxy(
            networkDnsBackend = { q, _ ->
                localBackendCalls.incrementAndGet()
                q.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
            },
            proxyBridge = bridge,
        )

        try {
            val port = dns.start(0)
            val query = buildSampleDnsQuery(txId = 0x5678, qname = "proxied.example")

            DatagramSocket().use { udp ->
                udp.soTimeout = 3_000
                udp.send(DatagramPacket(query, query.size, InetAddress.getByName("127.0.0.1"), port))
                val respBuf = ByteArray(512)
                val pkt = DatagramPacket(respBuf, respBuf.size)
                udp.receive(pkt)
                val resp = pkt.data.copyOfRange(0, pkt.length)
                assertEquals(0x56.toByte(), resp[0])
                assertEquals(0x78.toByte(), resp[1])
                assertEquals(0, resp[3].toInt() and 0x0F) // NOERROR
            }
            assertEquals(1, tunneledQueries.get())
            assertEquals(0, localBackendCalls.get())

            // When routeDnsViaProxy switches false, dispatch switches to NetworkDnsBackend.
            bridge.updatePolicy(
                bridge.currentPolicy.outboundProxy.copy(routeDnsViaProxy = false),
                isolateLanEgress = true,
            )
            val directReply = dns.handleDnsQuery(query)
            assertEquals(1, localBackendCalls.get())
            assertTrue((directReply[2].toInt() and 0x80) != 0)
        } finally {
            dns.close()
            bridge.close()
            mockSocks.close()
            pool.shutdownNow()
        }
    }
}
