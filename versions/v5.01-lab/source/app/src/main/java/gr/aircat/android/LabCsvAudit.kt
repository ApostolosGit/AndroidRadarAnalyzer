package gr.aircat.android

import java.io.BufferedReader
import java.io.Reader

/** Offline-only LAB audit. No connection, device operations or legacy radar inference. */
internal class LabCsvAudit {
    var frames=0L;private set
    var gaps=0L;private set
    var invalidRows=0L;private set
    var interrupted=0L;private set
    var ambiguous=0L;private set
    var rotations=0L;private set
    var perfect=0L;private set
    var observedMiss=0L;private set
    var duplicates=0L;private set
    private var active=false
    private val seen=HashSet<Int>()
    private var sector:Int?=null
    private var north:Long?=null
    private var segment:Long?=null
    private var tick:Long?=null
    private var seq:Long?=null
    private val missBySector=LongArray(32)
    private val intervals=ArrayList<Long>()

    private fun resetRotation(){active=false;seen.clear();sector=null;north=null}
    private fun gap(invalid:Boolean=false){
        gaps++
        if(invalid)invalidRows++
        if(active)interrupted++
        resetRotation();segment=null;tick=null;seq=null
    }
    fun accept(event:String,segmentValue:String,tickValue:String,seqValue:String,hex:String,source:String){
        if(event=="GAP"){gap();return}
        val s=segmentValue.toLongOrNull()
        val t=tickValue.toLongOrNull()
        val q=seqValue.toLongOrNull()
        val raw=AircatCsv.hexBytes(hex)
        if(event!="FRAME"||source!="LAB TCP v5"||s==null||s<0||t==null||t<0||
            q==null||q !in 0L..0xffffffffL||raw==null||raw.size<2||raw[1].toInt()!=0x40){
            gap(true);return
        }
        val kind=raw[0].toInt() and 255
        val isNorth=raw.contentEquals(byteArrayOf(0x19,0x40,0xfd.toByte(),0,0,0))
        val id=if(kind==0x0c&&raw.size>=4&&raw[2]==0xfd.toByte())raw[3].toInt() and 255 else null
        if((kind==0x19&&!isNorth)||(kind==0x0c&&(id==null||id>31))){gap(true);return}
        if(segment!=null&&(segment!=s||t<tick!!||((q-seq!!) and 0xffffffffL) !in 1L..0x7fffffffL))gap()
        segment=s;tick=t;seq=q;frames++
        if(isNorth){
            if(active){
                rotations++
                val missing=32-seen.size
                if(missing==0)perfect++
                observedMiss+=missing.toLong()
                for(i in 0..31)if(i !in seen)missBySector[i]++
                north?.let{if(t>it)intervals.add(t-it)}
            }
            active=true;seen.clear();sector=null;north=t
        }else if(id!=null&&active){
            if(id in seen||sector?.let{id<=it}==true){
                if(id in seen)duplicates++
                ambiguous++;resetRotation();return
            }
            seen.add(id);sector=id
        }
    }
    fun report():String=buildString{
        appendLine("TCP LAB — Offline continuity audit")
        appendLine("Frames: $frames")
        appendLine("Continuity gaps: $gaps")
        appendLine("Invalid CSV rows: $invalidRows")
        appendLine("Interrupted rotations: $interrupted")
        appendLine("Ambiguous rotations: $ambiguous")
        appendLine("Evaluated rotations: $rotations")
        appendLine("Perfect rotations: $perfect")
        appendLine("Observed missing sectors: $observedMiss")
        appendLine("Duplicate sectors: $duplicates")
        appendLine("Trailing partial rotation: "+if(active)"1" else "0")
        appendLine("Source intervals measured: "+intervals.size)
        val sorted=intervals.sorted()
        if(sorted.isNotEmpty()){
            val median=if(sorted.size%2==1)sorted[sorted.size/2].toDouble()
                else (sorted[sorted.size/2-1].toDouble()+sorted[sorted.size/2].toDouble())/2
            appendLine("Source median period (us): $median")
            if(sorted.size>=10&&median>0)appendLine("Source timing outliers: "+sorted.count{kotlin.math.abs(it/median-1)>0.05})
            else appendLine("Source timing outliers: insufficient sample")
        }
        appendLine()
        for(i in 0..31)if(missBySector[i]>0)appendLine("Sector %02X: %d".format(i,missBySector[i]))
        appendLine("Observed missing IDs in recorded LAB intervals without detected gaps are not proof of physical radar failure.")
        appendLine("CSV omits heartbeats: an unmarked deleted row is not always detectable. Arrival timing is not used.")
    }
    companion object{
        private val required=listOf("capture_event","capture_segment","source_us","frame_no","udp_payload_hex","capture_connection","reason")
        fun isLabHeader(line:String)=AircatCsv.columns(line.removePrefix("\uFEFF")).contains("capture_event")
        fun read(reader:Reader):LabCsvAudit{
            val r=reader.buffered()
            val keys=AircatCsv.columns(r.readLine()?.removePrefix("\uFEFF")?:error("Empty CSV"))
            require(required.all{it in keys}){"Not a continuity-aware LAB CSV"}
            val indexes=required.map{keys.indexOf(it)}
            val result=LabCsvAudit()
            r.forEachLine{line->
                if(line.isNotBlank()){
                    val c=AircatCsv.columns(line)
                    val f=indexes.map{c.getOrNull(it)?:""}
                    result.accept(f[0],f[1],f[2],f[3],f[4],f[5])
                }
            }
            return result
        }
    }
}
