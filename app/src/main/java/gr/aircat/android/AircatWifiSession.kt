package gr.aircat.android

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The render queue is bounded independently from recording and recent history. */
class WifiFrameBuffer(private val historyLimit: Int=4096, private val displayLimit: Int=1024) {
    private val history=ArrayDeque<RadarPacket>()
    private val display=ArrayDeque<RadarPacket>()
    @Volatile var displayDropped=0L;private set
    @Synchronized fun add(packet: RadarPacket) {
        history.addLast(packet);while(history.size>historyLimit)history.removeFirst()
        if(display.size>=displayLimit){display.removeFirst();displayDropped++}
        display.addLast(packet)
    }
    @Synchronized fun drain(limit: Int=256): List<RadarPacket> {
        val out=ArrayList<RadarPacket>()
        repeat(minOf(limit,display.size)){out.add(display.removeFirst())}
        return out
    }
    @Synchronized fun recent(): List<RadarPacket> = history.toList()
}

data class WifiRecordRow(val packet: RadarPacket,val wifi: WifiCounters)

/** Same accepted-frame CSV contract as Python. Never writes from the UI thread. */
class WifiCsvRecorder(private val open: ()->OutputStream) {
    private val queue=LinkedBlockingQueue<WifiRecordRow>(2048)
    private val lock=Any()
    @Volatile private var accepting=true
    @Volatile var finished=false;private set
    @Volatile var rows=0L;private set
    @Volatile var error: String?=null;private set
    init { thread(name="AIRCAT WiFi CSV",isDaemon=true){writeLoop()} }
    fun offer(row: WifiRecordRow) = synchronized(lock) {
        if(accepting && !queue.offer(row)) {
            error="Η ουρά εγγραφής γέμισε. Η εγγραφή σταμάτησε· έλεγξε τον χώρο αποθήκευσης."
            accepting=false
        }
    }
    fun stop()=synchronized(lock){accepting=false}
    fun isAccepting()=accepting
    private fun writeLoop() {
        try {
            BufferedWriter(OutputStreamWriter(open(),Charsets.UTF_8),64*1024).use { writer ->
                writer.write("frame_no,time_epoch,capture_time,udp_payload_hex,capture_device,capture_connection,usb_crc_fail_total,usb_framing_error_total,firmware_version,wifi_udp_lost_total,wifi_fw_overflow_total\n")
                var flushAt=System.nanoTime()
                while(accepting || queue.isNotEmpty()) {
                    val row=queue.poll(100,TimeUnit.MILLISECONDS)
                    if(row!=null) {
                        val p=row.packet
                        val stamp=String.format(Locale.US,"%.6f",p.epochSeconds)
                        val hex=p.payload.joinToString(""){"%02X".format(it.toInt() and 255)}
                        writer.write("${p.frameNo},$stamp,${Instant.ofEpochMilli((p.epochSeconds*1000).toLong())},$hex,SYNC2WIFI,Wi-Fi / UDP,${p.crcFailTotal ?: 0},${p.framingFailTotal ?: 0},${row.wifi.firmwareVersion},${row.wifi.lost},${row.wifi.firmwareOverflow}\n")
                        rows++
                    }
                    if(System.nanoTime()-flushAt>=1_000_000_000L){writer.flush();flushAt=System.nanoTime()}
                }
                writer.flush()
            }
        } catch(ex: Exception){error="Αποτυχία εγγραφής CSV: ${ex.message}"}
        finally { accepting=false;finished=true }
    }
}

data class WifiSessionSnapshot(val transport: WifiCounters=WifiCounters(),
    val bsc: LiveBscCounters=LiveBscCounters(),val message: String="Αναμονή HELLO/ACK...",
    val running: Boolean=true,val lastDataAgoMs: Long?=null)

/** Socket binding is injected so the transport is also JVM-testable. */
class AircatWifiSession(
    private val bindSocket: (DatagramSocket)->Unit,
    private val host: String="192.168.4.1",private val dataPort: Int=5005,private val helloPort: Int=5006,
    private val pin: String="1234",private val handshakeTimeoutMs: Long=22000
) : AutoCloseable {
    val frames=WifiFrameBuffer()
    @Volatile var snapshot=WifiSessionSnapshot();private set
    @Volatile var recorder: WifiCsvRecorder?=null;private set
    @Volatile private var stopped=false
    @Volatile private var dataSocket: DatagramSocket?=null
    @Volatile private var helloSocket: DatagramSocket?=null
    @Volatile private var started=false
    @Synchronized fun start() {
        check(!started){"Session already started"};started=true
        require(Regex("[0-9]{4}").matches(pin)){"Το PIN πρέπει να έχει 4 ψηφία"}
        thread(name="AIRCAT WiFi receiver",isDaemon=true){receiveLoop()}
    }
    @Synchronized fun record(open: ()->OutputStream) {
        check(!stopped && started){"Δεν υπάρχει ενεργή λήψη"}
        check(recorder?.finished!=false){"Η προηγούμενη εγγραφή δεν έχει ολοκληρωθεί"}
        recorder=WifiCsvRecorder(open)
    }
    fun stopRecording(){recorder?.stop()}
    private fun monotonicMs()=System.nanoTime()/1_000_000L
    private fun receiveLoop() {
        val protocol=AircatWifiProtocol()
        val parser=AircatBscStream()
        val startMono=monotonicMs()
        val startEpoch=System.currentTimeMillis()/1000.0
        var lastHello=startMono-2000
        var lastData: Long?=null
        var lastNetworkError=""
        try {
            val address=InetAddress.getByName(host)
            val data=DatagramSocket(null)
            dataSocket=data
            data.receiveBufferSize=262144
            data.reuseAddress=false
            data.bind(InetSocketAddress(dataPort))
            data.soTimeout=250
            bindSocket(data)
            val hello=DatagramSocket(0)
            helloSocket=hello;hello.soTimeout=100;bindSocket(hello)
            val bytes=ByteArray(2048)
            while(!stopped) {
                var now=monotonicMs()
                if(now-lastHello>=2000) {
                    lastHello=now
                    try {
                        val request="AIRCAT_HELLO_V1 $pin".toByteArray(Charsets.US_ASCII)
                        hello.send(DatagramPacket(request,request.size,address,helloPort))
                        val ack=DatagramPacket(ByteArray(256),256)
                        hello.receive(ack)
                        if(ack.address==address && protocol.acceptHello(ack.data.copyOf(ack.length)))lastNetworkError=""
                    } catch(ex: WifiBusyException){throw ex}
                    catch(_: SocketTimeoutException){}
                    catch(ex: java.io.IOException){if(stopped)break;lastNetworkError=ex.message ?: "Σφάλμα διαδρομής Wi-Fi"}
                }
                val status=protocol.snapshot()
                if(!status.acknowledged && status.received==0L && now-startMono>=handshakeTimeoutMs)
                    error("Δεν έφτασε ACK από $host:$helloPort. Έλεγξε SYNC, κωδικό Wi-Fi, PIN και firmware. $lastNetworkError")
                try {
                    val datagram=DatagramPacket(bytes,bytes.size)
                    data.receive(datagram)
                    now=monotonicMs()
                    if(datagram.address!=address)protocol.rejectSource()
                    else protocol.decode(bytes,datagram.length,now)?.let { chunk ->
                        if(chunk.resetStream)parser.transportGap()
                        val epoch=startEpoch+(now-startMono)/1000.0
                        val packets=parser.feed(chunk.bytes,epoch)
                        for(packet in packets) {
                            recorder?.offer(WifiRecordRow(packet,protocol.snapshot()))
                            frames.add(packet)
                        }
                        lastData=now
                    }
                } catch(_: SocketTimeoutException){}
                catch(ex: java.net.SocketException){if(stopped)break;throw ex}
                val s=protocol.snapshot()
                val age=lastData?.let{(monotonicMs()-it).coerceAtLeast(0)}
                val message=when {
                    !s.acknowledged -> "Αναμονή HELLO/ACK από τη συσκευή..."
                    age==null -> "ACK OK · αναμονή DATA/CLOCK"
                    age>3000 -> "Wi-Fi ACK · αναμονή νέων δεδομένων (${age/1000}s)"
                    else -> "Ζωντανή λήψη SYNC2WIFI"
                }
                snapshot=WifiSessionSnapshot(s,parser.snapshot(),message,true,age)
            }
        } catch(ex: Exception) {
            if(!stopped)snapshot=WifiSessionSnapshot(protocol.snapshot(),parser.snapshot(),"Σφάλμα Wi-Fi: ${ex.message}",false)
        } finally {
            stopped=true
            dataSocket?.close();helloSocket?.close();recorder?.stop()
            if(snapshot.running)snapshot=snapshot.copy(message="Η λήψη σταμάτησε",running=false)
        }
    }
    override fun close() {
        stopped=true;dataSocket?.close();helloSocket?.close();recorder?.stop()
    }
}

/** Reuse the existing CSV/analysis contract on an explicitly bounded live window. */
object WifiRecentCapture {
    fun from(packets: List<RadarPacket>): RadarCapture {
        require(packets.isNotEmpty()){"Δεν υπάρχουν ακόμη CRC-valid frames"}
        val csv=buildString {
            appendLine("frame_no,time_epoch,udp_payload_hex,usb_crc_fail_total,usb_framing_error_total")
            for(p in packets)appendLine("${p.frameNo},${String.format(Locale.US,"%.6f",p.epochSeconds)},${p.payload.joinToString(""){"%02X".format(it.toInt() and 255)}},${p.crcFailTotal ?: 0},${p.framingFailTotal ?: 0}")
        }
        return AircatCsv.read(StringReader(csv),"WiFi — πρόσφατο παράθυρο ${packets.size} frames")
    }
}
