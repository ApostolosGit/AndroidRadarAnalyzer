package gr.aircat.android
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
internal data class LabRecord(val kind:Int,val seq:Long,val sid:Long,val us:Long,val bytes:ByteArray)
internal class LabDecoder {
    private var buf=ByteArray(0)
    fun feed(data:ByteArray):List<LabRecord>{
        buf+=data
        val result=ArrayList<LabRecord>()
        var pos=0
        while(buf.size-pos>=28){
            val h=ByteBuffer.wrap(buf,pos,28).order(ByteOrder.LITTLE_ENDIAN)
            val magic=ByteArray(4);h.get(magic)
            val v=h.get().toInt() and 255;val kind=h.get().toInt() and 255
            val n=h.short.toInt() and 65535
            val seq=h.int.toLong() and 0xffffffffL;val sid=h.int.toLong() and 0xffffffffL
            val us=h.long;val crc=h.int.toLong() and 0xffffffffL
            require(magic.contentEquals("AC5T".toByteArray())&&v==5&&kind in 0..2&&n<=8192&&(kind==1||n==0)&&us>=0){"TCP LAB header"}
            if(buf.size-pos<28+n)break
            val bytes=buf.copyOfRange(pos+28,pos+28+n)
            require(CRC32().apply{update(buf,pos,24);update(bytes)}.value==crc){"TCP LAB CRC32"}
            result.add(LabRecord(kind,seq,sid,us,bytes));pos+=28+n
        }
        if(pos>0)buf=buf.copyOfRange(pos,buf.size)
        return result
    }
}
internal class LabContinuity{
    private var prev:LabRecord?=null
    private var tick:Long?=null
    var gaps=0L;private set
    fun observe(r:LabRecord):Boolean{
        val gap=prev?.let{it.sid!=r.sid||((it.seq+1) and 0xffffffffL)!=r.seq}==true ||
            r.kind==2 || (r.kind==1 && tick?.let{r.us<it}==true)
        prev=r;if(gap){gaps++;tick=null};if(r.kind==1)tick=r.us
        return gap
    }
    fun invalidate(){gaps++;tick=null}
}
