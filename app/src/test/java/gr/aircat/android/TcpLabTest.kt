package gr.aircat.android
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
class TcpLabTest{
    private fun rec(seq:Int):ByteArray{
        val b=ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        b.put("AC5T".toByteArray());b.put(5.toByte());b.put(0.toByte());b.putShort(0.toShort())
        b.putInt(seq);b.putInt(1);b.putLong(seq.toLong());b.putInt(0)
        val a=b.array();b.putInt(24,CRC32().apply{update(a,0,24)}.value.toInt());return a
    }
    @Test fun fragments(){val d=LabDecoder();assertEquals(2,(rec(0)+rec(1)).flatMap{d.feed(byteArrayOf(it))}.size)}
    @Test fun gap(){val d=LabDecoder();val c=LabContinuity();assertFalse(c.observe(d.feed(rec(0)).single()));assertTrue(c.observe(d.feed(rec(2)).single()))}
    @Test fun timingGap(){val t=LiveNorthTiming();for(i in 0..12)t.add(i*4.0);t.gap();t.add(1000.0);t.add(1004.0);assertEquals(0L,t.snapshot().missed)}
}
