package gr.aircat.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.ArrayDeque
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
    @Volatile private var stopped=false
    @Volatile private var dataSocket: DatagramSocket?=null
    @Volatile private var helloSocket: DatagramSocket?=null
    @Volatile private var started=false
    @Synchronized fun start() {
        check(!started){"Session already started"};started=true
        require(Regex("[0-9]{4}").matches(pin)){"Το PIN πρέπει να έχει 4 ψηφία"}
        thread(name="AIRCAT WiFi receiver",isDaemon=true){receiveLoop()}
    }
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
            close()
            if(snapshot.running)snapshot=snapshot.copy(message="Η λήψη σταμάτησε",running=false)
        }
    }
    @Synchronized override fun close() {
        stopped=true;dataSocket?.close();helloSocket?.close()
    }
}

