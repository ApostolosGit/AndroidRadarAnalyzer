package gr.aircat.android

import java.io.ByteArrayOutputStream

data class LiveBscCounters(
    val bytes: Long = 0, val sync: Long = 0, val framed: Long = 0,
    val valid: Long = 0, val crcErrors: Long = 0, val framingErrors: Long = 0,
    val north: Long = 0, val sectors: Long = 0, val extensions: Long = 0,
    val completed: Long = 0, val perfect: Long = 0,
    val missingClean: Long = 0, val missingUncertain: Long = 0,
    val duplicates: Long = 0, val orderErrors: Long = 0,
    val currentUnique: Int? = null, val lastError: String = ""
)

/** Stateful version of the desktop BSCObserver. UDP boundaries have no meaning. */
class AircatBscStream {
    private var stats = LiveBscCounters()
    private var rolling = 0L
    private var seenBytes = 0
    private var state = 0 // search, payload, CRC1, CRC2
    private var afterDle = false
    private val frame = ByteArrayOutputStream()
    private var rotation: Rotation? = null
    private data class Rotation(val seen: MutableSet<Int> = HashSet(), var last: Int? = null,
                                var duplicates: Int = 0, var order: Int = 0, var errors: Int = 0)
    fun snapshot() = stats.copy(currentUnique = rotation?.seen?.size)
    private fun resetFrame() { state=0; afterDle=false; frame.reset() }
    fun transportGap() { resetFrame(); rolling=0; seenBytes=0; rotation=null }

    fun feed(data: ByteArray, epochSeconds: Double): List<RadarPacket> {
        val packets = ArrayList<RadarPacket>()
        for (value in data) {
            val byte = value.toInt() and 255
            stats = stats.copy(bytes=stats.bytes+1)
            rolling = (rolling shl 8) or byte.toLong()
            seenBytes = (seenBytes+1).coerceAtMost(8)
            if (seenBytes>=4 && rolling and 0xffffffffL == 0x32323201L)
                stats=stats.copy(sync=stats.sync+1)
            val type = ((rolling ushr 24) and 255).toInt()
            if (seenBytes==8 && rolling ushr 32 == 0x32323201L &&
                type in setOf(0x0c,0x0d,0x19,0x1c,0x2a,0x0e) && rolling and 0xffffffL == 0x401002L) {
                if (state!=0) {
                    stats=stats.copy(framingErrors=stats.framingErrors+1,lastError="Νέο BSC header πριν ολοκληρωθεί το προηγούμενο frame")
                    rotation=null
                }
                frame.reset()
                frame.write(byteArrayOf(0x32,0x32,0x32,0x01,type.toByte(),0x40,0x10,0x02))
                state=1;afterDle=false
                continue
            }
            if (state==0) continue
            frame.write(byte)
            if (frame.size()>8192) {
                stats=stats.copy(framingErrors=stats.framingErrors+1,lastError="BSC frame πάνω από 8192 bytes")
                rotation=null;resetFrame();continue
            }
            if (state==2) { state=3;continue }
            if (state==3) { finish(epochSeconds)?.let(packets::add);resetFrame();continue }
            if (afterDle) { afterDle=false;if(byte==3)state=2 }
            else if (byte==0x10) afterDle=true
        }
        return packets
    }

    private fun finish(epochSeconds: Double): RadarPacket? {
        val raw=frame.toByteArray()
        val text=ByteArrayOutputStream()
        var wellFormed=true
        var at=8
        val end=raw.size-4
        while(at<end) {
            val b=raw[at].toInt() and 255
            if(b!=0x10) { text.write(b);at++;continue }
            if(at+1>=end) { wellFormed=false;text.write(b);break }
            val next=raw[at+1].toInt() and 255
            when(next) {
                0x10 -> text.write(0x10)
                0x32 -> Unit // Inserted transparent SYN is excluded from CRC.
                else -> { wellFormed=false;text.write(0x10);text.write(next) }
            }
            at+=2
        }
        val inner=text.toByteArray()
        val calculated=AircatBsc.crc16Kermit(raw.copyOfRange(4,8)+inner+byteArrayOf(3))
        val received=(raw[raw.size-2].toInt() and 255) or ((raw.last().toInt() and 255) shl 8)
        val good=calculated==received && wellFormed
        stats=stats.copy(framed=stats.framed+1,
            crcErrors=stats.crcErrors+if(calculated!=received)1 else 0,
            framingErrors=stats.framingErrors+if(!wellFormed)1 else 0)
        val type=raw[4].toInt() and 255
        if(!good) {
            rotation?.let { it.errors++ }
            if(type==0x19)rotation=null
            stats=stats.copy(lastError="${if(calculated!=received)"CRC FAIL" else "FRAMING"} TYPE ${"%02X".format(type)} RX ${"%04X".format(received)} CALC ${"%04X".format(calculated)}")
            return null
        }
        val payload=byteArrayOf(raw[4],raw[5])+inner
        val sector=if(type==0x0c && inner.size>=2 && inner[0].toInt() and 255 == 0xfd &&
            inner[1].toInt() and 255 in 0..31)inner[1].toInt() and 255 else null
        stats=stats.copy(valid=stats.valid+1,north=stats.north+if(type==0x19)1 else 0,
            sectors=stats.sectors+if(type==0x0c)1 else 0,extensions=stats.extensions+if(type==0x0d)1 else 0)
        if(type==0x19 && inner.contentEquals(byteArrayOf(0xfd.toByte(),0,0,0))) {
            rotation?.let { r ->
                val missing=32-r.seen.size
                stats=stats.copy(completed=stats.completed+1,
                    perfect=stats.perfect+if(r.errors==0 && missing==0 && r.duplicates==0 && r.order==0)1 else 0,
                    missingClean=stats.missingClean+if(r.errors==0)missing else 0,
                    missingUncertain=stats.missingUncertain+if(r.errors>0)missing else 0)
            }
            rotation=Rotation()
        } else if(sector!=null) rotation?.let { r ->
            if(!r.seen.add(sector)) { r.duplicates++;stats=stats.copy(duplicates=stats.duplicates+1) }
            if(r.last!=null && sector!=r.last!!+1) { r.order++;stats=stats.copy(orderErrors=stats.orderErrors+1) }
            r.last=sector
        }
        return RadarPacket(stats.valid,epochSeconds,payload,type,sector,AircatCsv.tracks(payload),stats.crcErrors,stats.framingErrors)
    }
}
