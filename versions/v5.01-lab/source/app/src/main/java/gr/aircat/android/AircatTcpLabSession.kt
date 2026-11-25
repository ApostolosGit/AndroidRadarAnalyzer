package gr.aircat.android
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Synthetic TCP laboratory recording; no production source. */
class LabRecorder(private val onFinished:(Long,String?)->Unit={_,_->},private val open:()->OutputStream){
    private val queue=LinkedBlockingQueue<String>(2048)
    private val lock=Any()
    @Volatile private var accepting=true
    @Volatile var finished=false;private set
    @Volatile var rows=0L;private set
    @Volatile var error:String?=null;private set
    private var overflow=false
    private fun enqueue(row:String)=synchronized(lock){
        if(accepting&&!queue.offer(row)){overflow=true;error="CSV queue overflow";accepting=false}
    }
    internal fun frame(seg:Long,r:LabRecord,p:RadarPacket)=enqueue(listOf("FRAME",seg.toString(),r.us.toString(),
        r.seq.toString(),p.payload.joinToString(""){"%02X".format(it.toInt() and 255)},"LAB TCP v5","").joinToString(","))
    fun gap(seg:Long,reason:String)=enqueue("GAP,$seg,,,,LAB TCP v5,\""+reason.replace("\"","\"\"")+"\"")
    fun stop()=synchronized(lock){accepting=false}
    fun isAccepting()=accepting
    init{thread(name="TCP LAB CSV",isDaemon=true){
        try{
            BufferedWriter(OutputStreamWriter(open(),Charsets.UTF_8)).use{w->
                w.write("\uFEFFcapture_event,capture_segment,source_us,frame_no,udp_payload_hex,capture_connection,reason\n")
                while(accepting||queue.isNotEmpty()){
                    val row=queue.poll(100,TimeUnit.MILLISECONDS)
                    if(row!=null){w.write(row+"\n");rows++;w.flush()}
                }
                if(overflow)w.write("GAP,,,,,LAB TCP v5,CSV queue overflow\n")
                w.flush()
            }
        }catch(ex:Exception){error=ex.message}
        finally{accepting=false;try{onFinished(rows,error)}finally{finished=true}}
    }}
}
class AircatTcpLabSession(private val bindSocket:(Socket)->Unit,private val pin:String="1234"):AutoCloseable{
    val frames=WifiFrameBuffer()
    @Volatile var snapshot=WifiSessionSnapshot(message="TCP LAB: connecting");private set
    @Volatile var recorder:LabRecorder?=null;private set
    @Volatile private var stopped=false
    @Volatile private var sock:Socket?=null
    private val continuity=LabContinuity()
    private val parser=AircatBscStream()
    private val timing=LiveNorthTiming()
    private fun mono()=System.nanoTime()/1000000
    @Synchronized fun start(){check(!stopped && sock==null);thread(name="TCP LAB",isDaemon=true){receive()}}
    @Synchronized fun record(onFinished:(Long,String?)->Unit={_,_->},open:()->OutputStream):LabRecorder{
        check(!stopped);check(recorder?.finished!=false);return LabRecorder(onFinished,open).also{recorder=it}
    }
    private fun gap(reason:String){parser.transportGap();timing.gap();recorder?.gap(continuity.gaps,reason)}
    private fun receive(){
        var counters=WifiCounters(firmwareVersion="50.0-LAB",firmwareMode="TCP")
        var lastData:Long?=null
        var anchor:Double?=null
        var ack=false
        try{
            val s=Socket()
            synchronized(this){if(stopped){s.close();return};sock=s}
            s.receiveBufferSize=262144;s.tcpNoDelay=true;bindSocket(s)
            s.connect(InetSocketAddress("192.168.4.1",5005),5000);s.soTimeout=250
            val input=s.getInputStream();val output=s.getOutputStream()
            output.write(("AIRCAT_TCP_V5 "+pin+"\n").toByteArray());output.flush()
            var rx=mono();var ping=rx
            val decoder=LabDecoder();val buf=ByteArray(8192)
            while(!stopped){
                val now=mono()
                if(now-rx>=10000)error("TCP LAB inactivity")
                if(now-ping>=2000){output.write("P\n".toByteArray());output.flush();ping=now}
                val n=try{input.read(buf)}catch(_:SocketTimeoutException){0}
                if(n<0)error("TCP LAB EOF")
                if(n>0)for(r in decoder.feed(buf.copyOf(n))){
                    rx=mono();ack=true
                    if(continuity.observe(r)){gap("Continuity uncertain");anchor=null}
                    if(r.kind==1){
                        if(anchor==null)anchor=System.currentTimeMillis()/1000.0-r.us/1000000.0
                        val packets=parser.feed(r.bytes,anchor!!+r.us/1000000.0)
                        if(packets.size!=1){continuity.invalidate();gap("BSC frame invalid")}
                        else{
                            val p=packets.single()
                            if(p.payload.contentEquals(byteArrayOf(0x19,0x40,0xfd.toByte(),0,0,0)))timing.add(p.epochSeconds)
                            recorder?.frame(continuity.gaps,r,p);frames.add(p);lastData=mono()
                        }
                        counters=counters.copy(received=counters.received+1)
                    }
                }
                counters=counters.copy(acknowledged=ack,lost=continuity.gaps,linkResets=continuity.gaps)
                snapshot=WifiSessionSnapshot(counters,parser.snapshot(),"TCP LAB · synthetic",true,
                    lastData?.let{mono()-it},timing.snapshot(),if(ack)mono()-rx else null)
            }
        }catch(ex:Exception){
            if(!stopped)snapshot=WifiSessionSnapshot(counters,parser.snapshot(),"Χάθηκε TCP LAB: "+ex.message,false,timing=timing.snapshot())
        }finally{close();if(snapshot.running)snapshot=snapshot.copy(running=false)}
    }
    @Synchronized override fun close(){
        if(stopped)return;stopped=true
        try{sock?.close()}catch(_:Exception){}
        recorder?.gap(continuity.gaps+1,"TCP session ended");recorder?.stop()
    }
}
