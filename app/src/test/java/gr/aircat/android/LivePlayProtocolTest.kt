package gr.aircat.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.concurrent.thread

/** LivePlay-only checks against v40.0's AC50 v2 packet + HELLO handshake. */
class LivePlayProtocolTest {
    private fun datagram(data: ByteArray, seq: Long = 0): ByteArray {
        val packet = ByteArray(24 + data.size)
        "AC50".toByteArray(Charsets.US_ASCII).copyInto(packet)
        packet[4] = 2
        packet[6] = data.size.toByte()
        packet[7] = (data.size shr 8).toByte()
        fun le(at: Int, value: Long) {
            repeat(4) { i -> packet[at + i] = (value ushr (8 * i)).toByte() }
        }
        le(8, seq)
        le(12, 0)
        le(16, 19200)
        data.copyInto(packet, 24)
        le(20, CRC32().apply { update(packet, 0, 20); update(packet, 24, data.size) }.value)
        return packet
    }

    private fun frame(payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (b in payload.copyOfRange(2, payload.size)) {
            body.write(b.toInt() and 255)
            if (b.toInt() and 255 == 0x10) body.write(0x10)
        }
        val crc = AircatBsc.crc16Kermit(
            payload.copyOfRange(0, 2) + byteArrayOf(0x10, 0x02) +
                payload.copyOfRange(2, payload.size) + byteArrayOf(0x03)
        )
        return byteArrayOf(0x32, 0x32, 0x32, 0x01, payload[0], payload[1], 0x10, 0x02) +
            body.toByteArray() + byteArrayOf(0x10, 0x03, crc.toByte(), (crc shr 8).toByte())
    }

    @Test fun firmwareV40UsesPlainHelloAckAndVersionRemainsUnknown() {
        val protocol = AircatWifiProtocol()
        assertTrue(protocol.acceptHello("AIRCAT_ACK_V1".toByteArray(Charsets.US_ASCII)))
        assertTrue(protocol.snapshot().acknowledged)
        assertEquals("unknown", protocol.snapshot().firmwareVersion)
        assertTrue(protocol.acceptHello("AIRCAT_ACK_V1 version=40.0 mode=USB_WIFI_AP".toByteArray()))
        assertEquals("40.0", protocol.snapshot().firmwareVersion)
    }

    @Test fun firmwareV40Ac50V2CrcAndDuplicateCopy() {
        val p = AircatWifiProtocol()
        val data = byteArrayOf(0x32, 0x32, 0x32, 0x01)
        val good = datagram(data)
        val bad = good.copyOf().apply { this[24] = 0 }
        assertNull(p.decode(bad, nowMs = 100))
        assertEquals(1L, p.snapshot().badCrc32)
        assertArrayEquals(data, p.decode(good, nowMs = 101)!!.bytes)
        assertNull(p.decode(good, nowMs = 102))
        assertEquals(1L, p.snapshot().duplicates)
        assertEquals(19200L, p.snapshot().clockHz)
        assertTrue(p.decode(datagram(byteArrayOf(0x10), seq = 2), nowMs = 103)!!.resetStream)
        assertEquals(1L, p.snapshot().lost)
    }

    @Test fun streamingBscParserAcceptsAllSplitPositionsAndRejectsBadCrc() {
        val north = frame(byteArrayOf(0x19, 0x40, 0xfd.toByte(), 0, 0, 0))
        for (split in north.indices) {
            val parser = AircatBscStream()
            val accepted = parser.feed(north.copyOfRange(0, split), 1000.0) +
                parser.feed(north.copyOfRange(split, north.size), 1000.0)
            assertEquals("split $split", 1, accepted.size)
            assertEquals(0L, parser.snapshot().crcErrors)
            assertEquals(0x19, accepted.single().kind)
        }
        val broken = north.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val parser = AircatBscStream()
        assertTrue(parser.feed(broken, 1000.0).isEmpty())
        assertEquals(1L, parser.snapshot().crcErrors)
    }

    @Test fun liveDisplayQueueIsBounded() {
        val buffer = WifiFrameBuffer(historyLimit = 3, displayLimit = 2)
        repeat(10) { index ->
            val packet = RadarPacket(index.toLong(), index.toDouble(),
                byteArrayOf(0x0c, 0x40, 0xfd.toByte(), index.toByte()),
                0x0c, index % 32, emptyList(), 0, 0)
            buffer.add(packet)
        }
        assertEquals(listOf(7L, 8L, 9L), buffer.recent().map { it.frameNo })
        assertEquals(listOf(8L, 9L), buffer.drain().map { it.frameNo })
        assertEquals(8L, buffer.displayDropped)
    }

    @Test fun closeAndReopenUdpPortAfterAckLoss() {
        val loop = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loop).apply { soTimeout = 4000 }
        val port = DatagramSocket(0, loop).use { it.localPort }
        val first = AircatWifiSession({}, "127.0.0.1", port, server.localPort,
            handshakeTimeoutMs = 4000, ackTimeoutMs = 700)
        val second = AircatWifiSession({}, "127.0.0.1", port, server.localPort,
            handshakeTimeoutMs = 4000)
        fun answer() {
            val packet = DatagramPacket(ByteArray(100), 100)
            server.receive(packet)
            val ack = "AIRCAT_ACK_V1".toByteArray(Charsets.US_ASCII)
            server.send(DatagramPacket(ack, ack.size, loop, packet.port))
        }
        try {
            first.start()
            answer()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while(first.snapshot.running && System.nanoTime() < until) Thread.sleep(20)
            assertFalse(first.snapshot.running)
            assertTrue(first.snapshot.message.contains("HELLO/ACK"))
            first.close()
            second.start()
            answer()
            val again = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while(second.snapshot.ackReceived == 0L && System.nanoTime() < again) Thread.sleep(20)
            assertTrue(second.snapshot.running)
            assertEquals(1L, second.snapshot.ackReceived)
        } finally {
            first.close(); second.close(); server.close()
        }
    }

    @Test fun localUdpHandshakeAndStreamingUseV40PortsAndPin() {
        val loop = InetAddress.getByName("127.0.0.1")
        val server = DatagramSocket(0, loop).apply { soTimeout = 3000 }
        val dataPort = DatagramSocket(0, loop).use { it.localPort }
        val session = AircatWifiSession({}, "127.0.0.1", dataPort, server.localPort)
        val finished = CountDownLatch(1)
        var backgroundError: Throwable? = null
        val bsc = frame(byteArrayOf(0x19, 0x40, 0xfd.toByte(), 0, 0, 0))
        thread(isDaemon = true) {
            try {
                val hello = DatagramPacket(ByteArray(100), 100)
                server.receive(hello)
                assertEquals("AIRCAT_HELLO_V1 1234",
                    hello.data.copyOf(hello.length).toString(Charsets.US_ASCII))
                val ack = "AIRCAT_ACK_V1".toByteArray(Charsets.US_ASCII)
                server.send(DatagramPacket(ack, ack.size, loop, hello.port))
                val first = datagram(bsc.copyOfRange(0, 10), 0)
                val second = datagram(bsc.copyOfRange(10, bsc.size), 1)
                for (part in arrayOf(first, first, second))
                    server.send(DatagramPacket(part, part.size, loop, dataPort))
            } catch (ex: Throwable) { backgroundError = ex }
            finally { finished.countDown() }
        }
        try {
            session.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (session.snapshot.bsc.valid < 1 && System.nanoTime() < deadline)
                Thread.sleep(15)
            assertEquals(1L, session.snapshot.bsc.valid)
            assertEquals(1L, session.snapshot.transport.duplicates)
            assertEquals(1, session.frames.drain().size)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            backgroundError?.let { throw it }
        } finally {
            session.close()
            server.close()
        }
    }
}
