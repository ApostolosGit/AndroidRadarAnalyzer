package gr.aircat.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AircatWifiTest {
    private fun packet(data: ByteArray,seq: Long=0,overflow: Long=0,version: Int=2): ByteArray {
        val header=if(version==2)24 else 20
        val p=ByteArray(header+data.size)
        "AC50".toByteArray().copyInto(p);p[4]=version.toByte();p[6]=data.size.toByte();p[7]=(data.size shr 8).toByte()
        fun le(at: Int,n: Long){repeat(4){p[at+it]=(n ushr (8*it)).toByte()}}
        le(8,seq);le(12,overflow);le(16,19200)
        data.copyInto(p,header)
        if(version==2)le(20,CRC32().apply{update(p,0,20);update(p,24,data.size)}.value)
        return p
    }
    private fun frame(payload: ByteArray,insertSyn: Boolean=false): ByteArray {
        val inner=payload.copyOfRange(2,payload.size)
        val body=ByteArrayOutputStream()
        for((i,b) in inner.withIndex()) {
            body.write(b.toInt() and 255)
            if(b.toInt() and 255==0x10)body.write(0x10)
            if(insertSyn && i==0){body.write(0x10);body.write(0x32)}
        }
        val crc=AircatBsc.crc16Kermit(payload.copyOfRange(0,2)+byteArrayOf(0x10,2)+inner+byteArrayOf(3))
        return byteArrayOf(0x32,0x32,0x32,1,payload[0],payload[1],0x10,2)+body.toByteArray()+byteArrayOf(0x10,3,crc.toByte(),(crc shr 8).toByte())
    }
    private val north=byteArrayOf(0x19,0x40,0xfd.toByte(),0,0,0)
    private fun sector(n: Int)=byteArrayOf(0x0c,0x40,0xfd.toByte(),n.toByte())
    private fun waitUntil(timeout: Long=4000,condition: ()->Boolean) {
        val end=System.nanoTime()+timeout*1_000_000
        while(!condition() && System.nanoTime()<end)Thread.sleep(10)
        assertTrue("Timed out waiting for worker",condition())
    }
    private fun record(n: Long)=RadarPacket(n,1700000000.0+n/8.0,sector((n%32).toInt()),0x0c,(n%32).toInt(),emptyList(),0,0)

    @Test fun versionedAndLegacyHelloAndBusy() {
        val p=AircatWifiProtocol()
        assertFalse(p.acceptHello("AIRCAT_ACK_V10".toByteArray()))
        assertTrue(p.acceptHello("AIRCAT_ACK_V1 version=30.5 mode=USB_WIFI_AP".toByteArray()))
        assertEquals("30.5",p.snapshot().firmwareVersion)
        assertEquals("USB_WIFI_AP",p.snapshot().firmwareMode)
        assertTrue(p.acceptHello("AIRCAT_ACK_V1".toByteArray()))
        assertEquals("unknown",p.snapshot().firmwareVersion)
        try{p.acceptHello("AIRCAT_BUSY_V1".toByteArray());fail("BUSY accepted")}
        catch(_: WifiBusyException){}
    }
    @Test fun exactDatagramGeneratedByFirmwareV305MatchesDesktopReceiver() {
        // Golden full payload is 0..127; CRC and header are from the actual
        // compiled v30.5 firmware host harness, also accepted by Python.
        val prefix=AircatCsv.hexBytes("4143353002008000000000000000000000000000426cb768")!!
        val datagram=prefix+ByteArray(128){it.toByte()}
        val receiver=AircatWifiProtocol()
        assertArrayEquals(ByteArray(128){it.toByte()},receiver.decode(datagram,nowMs=0)!!.bytes)
        assertEquals(1L,receiver.snapshot().v2Packets)
    }
    @Test fun crcBadCopyDoesNotConsumeSequenceAndIntactDuplicateRecovers() {
        val p=AircatWifiProtocol()
        val good=packet(byteArrayOf(1,2,3))
        val bad=good.copyOf().apply{this[24]=9}
        assertNull(p.decode(bad,nowMs=0));assertEquals(1L,p.snapshot().badCrc32)
        assertArrayEquals(byteArrayOf(1,2,3),p.decode(good,nowMs=1)!!.bytes)
        assertNull(p.decode(good,nowMs=2));assertEquals(1L,p.snapshot().duplicates)
        assertEquals(1L,p.snapshot().received)
    }
    @Test fun sequenceGapsWrapDelayedCopiesAndRestart() {
        val p=AircatWifiProtocol()
        assertNotNull(p.decode(packet(byteArrayOf(1),0xfffffffeL),nowMs=0))
        assertFalse(p.decode(packet(byteArrayOf(2),0xffffffffL),nowMs=1)!!.resetStream)
        assertFalse(p.decode(packet(byteArrayOf(3),0),nowMs=2)!!.resetStream)
        assertTrue(p.decode(packet(byteArrayOf(4),2),nowMs=3)!!.resetStream)
        assertEquals(1L,p.snapshot().lost)
        assertNull(p.decode(packet(byteArrayOf(5),1),nowMs=4))
        assertTrue(p.decode(packet(byteArrayOf(6),1),nowMs=4000)!!.resetStream)
        assertTrue(p.decode(packet(byteArrayOf(7),2,10),nowMs=4001)!!.resetStream)
        assertEquals(10L,p.snapshot().overflowSinceConnect)
    }
    @Test fun legacyAndInvalidEnvelopes() {
        val p=AircatWifiProtocol()
        assertArrayEquals(byteArrayOf(5),p.decode(packet(byteArrayOf(5),version=1),nowMs=0)!!.bytes)
        assertNull(p.decode(ByteArray(10),nowMs=1))
        assertNull(p.decode(packet(ByteArray(385)),nowMs=1))
        assertNull(p.decode(packet(byteArrayOf(1)).dropLast(1).toByteArray(),nowMs=1))
        assertNull(p.decode(packet(byteArrayOf(1)).apply{this[5]=1},nowMs=1))
        assertNull(p.decode(packet(byteArrayOf(1),version=3),nowMs=1))
        assertEquals(5L,p.snapshot().invalid)
    }
    @Test fun streamHandlesEverySplitAndTransparentEscapes() {
        val payload=byteArrayOf(0x0d,0x40,0x10,0x32,0x55,0x10)
        val bytes=frame(payload,true)
        for(split in 0..bytes.size) {
            val parser=AircatBscStream()
            val result=parser.feed(bytes.copyOfRange(0,split),1.0)+parser.feed(bytes.copyOfRange(split,bytes.size),1.0)
            assertEquals("split $split",1,result.size)
            assertArrayEquals(payload,result.single().payload)
            assertEquals(0L,parser.snapshot().crcErrors)
        }
        val oneByte=AircatBscStream()
        assertEquals(1,bytes.flatMap{oneByte.feed(byteArrayOf(it),2.0)}.size)
    }
    @Test fun crcAndFramingFailuresAreNotForwarded() {
        val p=AircatBscStream()
        val bad=frame(north).apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()}
        assertTrue(p.feed(bad,0.0).isEmpty());assertEquals(1L,p.snapshot().crcErrors)
        val partial=frame(sector(0)).copyOfRange(0,10)
        assertEquals(1,(p.feed(partial,1.0)+p.feed(frame(north),2.0)).size)
        assertEquals(1L,p.snapshot().framingErrors)
    }
    @Test fun completeRotationsAndUncertainMissingRemainDistinct() {
        val p=AircatBscStream()
        p.feed(frame(north),0.0)
        for(i in 0..31)p.feed(frame(sector(i)),i/8.0)
        p.feed(frame(north),4.0)
        assertEquals(1L,p.snapshot().perfect);assertEquals(0L,p.snapshot().missingClean)
        for(i in 0..31) {
            val f=frame(sector(i))
            if(i==7)f[f.lastIndex]=(f.last().toInt() xor 1).toByte()
            p.feed(f,4.0+i/8.0)
        }
        p.feed(frame(north),8.0)
        assertEquals(1L,p.snapshot().missingUncertain)
        assertEquals(0L,p.snapshot().missingClean)
        assertEquals(2L,p.snapshot().completed)
    }
    @Test fun transportGapDiscardsPartialFrameAndRotation() {
        val p=AircatBscStream()
        p.feed(frame(north),0.0)
        val f=frame(sector(0));p.feed(f.copyOfRange(0,9),.1)
        p.transportGap()
        assertTrue(p.feed(f.copyOfRange(9,f.size),.2).isEmpty())
        assertEquals(1,p.feed(frame(north),4.0).size)
        assertEquals(0L,p.snapshot().completed)
    }
    @Test fun udpGapCannotJoinHalvesOfAnOldBscFrame() {
        val protocol=AircatWifiProtocol();val parser=AircatBscStream()
        val f=frame(sector(3))
        val first=protocol.decode(packet(f.copyOfRange(0,9),0),nowMs=0)!!
        assertTrue(parser.feed(first.bytes,0.0).isEmpty())
        val next=protocol.decode(packet(f.copyOfRange(9,f.size)+frame(north),2),nowMs=1)!!
        assertTrue(next.resetStream);parser.transportGap()
        val accepted=parser.feed(next.bytes,1.0)
        assertEquals(1,accepted.size);assertEquals(0x19,accepted.single().kind)
        assertEquals(1L,protocol.snapshot().lost)
    }
    @Test fun displayQueueAndRecentWindowAreBounded() {
        val b=WifiFrameBuffer(3,2)
        for(i in 1..10)b.add(record(i.toLong()))
        assertEquals(listOf(8L,9L,10L),b.recent().map{it.frameNo})
        assertEquals(listOf(9L,10L),b.drain().map{it.frameNo})
        assertEquals(8L,b.displayDropped)
    }
    @Test fun recordedCsvReopensInExistingOfflineDecoder() {
        val output=ByteArrayOutputStream()
        val writer=WifiCsvRecorder{output}
        val wifi=WifiCounters(firmwareVersion="30.5")
        for(i in 1..10)writer.offer(WifiRecordRow(record(i.toLong()),wifi))
        writer.stop();waitUntil{writer.finished}
        assertNull(writer.error);assertEquals(10L,writer.rows)
        val text=output.toString("UTF-8")
        val capture=AircatCsv.read(StringReader(text),"wifi.csv")
        assertEquals(10,capture.packets.size)
        assertTrue(text.contains(",30.5,"))
        assertEquals(1700000000.125,capture.packets.first().epochSeconds,.00001)
        assertEquals(0L,capture.summary.crcFailuresDuringCsv)
    }
    @Test fun recordingFailureAndQueueOverflowAreExplicit() {
        val failing=WifiCsvRecorder{throw IOException("disk unavailable")}
        waitUntil{failing.finished};assertNotNull(failing.error)
        val gate=CountDownLatch(1)
        val writer=WifiCsvRecorder{gate.await(2,TimeUnit.SECONDS);ByteArrayOutputStream()}
        try {
            repeat(2050){writer.offer(WifiRecordRow(record(it.toLong()),WifiCounters()))}
            assertFalse(writer.isAccepting());assertNotNull(writer.error)
        } finally{gate.countDown();writer.stop()}
        waitUntil{writer.finished}
    }
    @Test fun recordingCompletionRunsAfterCloseAndReportsFailure() {
        var closed=false
        val output=object : ByteArrayOutputStream() {
            override fun close(){super.close();closed=true}
        }
        val done=CountDownLatch(1)
        var completedRows=-1L
        var completedError: String?="not called"
        var completedAfterClose=false
        val writer=WifiCsvRecorder(onFinished={rows,error ->
            completedRows=rows;completedError=error;completedAfterClose=closed;done.countDown()
        }){output}
        repeat(5){writer.offer(WifiRecordRow(record(it.toLong()),WifiCounters()))}
        assertEquals(1L,done.count)
        writer.stop();assertTrue(done.await(3,TimeUnit.SECONDS))
        assertTrue(completedAfterClose);assertEquals(5L,completedRows);assertNull(completedError)
        assertEquals(5,AircatCsv.read(StringReader(output.toString("UTF-8")),"closed.csv").packets.size)
        val failed=CountDownLatch(1)
        var failure: String?=null
        val bad=WifiCsvRecorder(onFinished={_,error ->failure=error;failed.countDown()}){throw IOException("disk unavailable")}
        assertTrue(failed.await(3,TimeUnit.SECONDS));waitUntil{bad.finished}
        assertNotNull(failure)
    }
    @Test fun loopbackFirmwareHandshakeDuplicatesAndStreaming() {
        val loop=InetAddress.getByName("127.0.0.1")
        val server=DatagramSocket(0,loop).apply{soTimeout=3000}
        val dataPort=DatagramSocket(0,loop).use{it.localPort}
        val session=AircatWifiSession({},"127.0.0.1",dataPort,server.localPort)
        val done=CountDownLatch(1)
        var serverError: Throwable?=null
        val stream=frame(north)+frame(sector(0))
        thread(isDaemon=true) {
            try {
                val hello=DatagramPacket(ByteArray(128),128);server.receive(hello)
                assertEquals("AIRCAT_HELLO_V1 1234",hello.data.copyOf(hello.length).toString(Charsets.US_ASCII))
                val ack="AIRCAT_ACK_V1 version=30.5 mode=USB_WIFI_AP".toByteArray()
                server.send(DatagramPacket(ack,ack.size,loop,hello.port))
                val a=packet(stream.copyOfRange(0,11),0)
                val b=packet(stream.copyOfRange(11,stream.size),1)
                for(bytes in listOf(a,a,b))server.send(DatagramPacket(bytes,bytes.size,loop,dataPort))
            } catch(ex: Throwable){serverError=ex}
            finally{done.countDown()}
        }
        try {
            session.start();waitUntil{session.snapshot.bsc.valid==2L}
            assertEquals("30.5",session.snapshot.transport.firmwareVersion)
            assertEquals(1L,session.snapshot.transport.duplicates)
            assertEquals(2,session.frames.drain().size)
            assertEquals(19200L,session.snapshot.transport.clockHz)
            assertTrue(done.await(1,TimeUnit.SECONDS));serverError?.let{throw it}
        } finally{session.close();server.close()}
        waitUntil{!session.snapshot.running}
    }
    @Test fun missingHandshakeTimesOutWithActionableStatus() {
        val helloPort=DatagramSocket(0).use{it.localPort}
        val dataPort=DatagramSocket(0).use{it.localPort}
        val session=AircatWifiSession({},"127.0.0.1",dataPort,helloPort,handshakeTimeoutMs=400)
        try {
            session.start();waitUntil{!session.snapshot.running}
            assertTrue(session.snapshot.message.contains("ACK"))
        } finally{session.close()}
    }
}
