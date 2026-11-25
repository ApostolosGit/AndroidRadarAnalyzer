package gr.aircat.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.round

internal object LiveCsv {
    const val HEADER = "frame_no,time_epoch,capture_time,udp_payload_hex,length_bytes,aircat_type,aircat_type_name,aircat_addr,sector,is_north,is_sector,capture_device,capture_connection,capture_port,usb_crc_fail_total,usb_framing_error_total"
    private val names = mapOf(0x0c to "Radar Sector", 0x0d to "Radar Extension", 0x19 to "North Signal", 0x1c to "Processor State", 0x2a to "Weather")
    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).withZone(ZoneId.systemDefault())
    private fun hex(n: Int) = String.format(Locale.US,"0x%02X",n)
    fun row(p: RadarPacket): String {
        val address = p.payload.getOrNull(1)?.toInt()?.and(255)
        val isNorth = p.kind == 0x19 && address == 0x40
        val isSector = p.kind == 0x0c && address == 0x40 && p.payload.size >= 4 && (p.payload[2].toInt() and 255) == 0xfd
        val sector = if(isSector) hex(p.payload[3].toInt() and 255) else ""
        return listOf(p.frameNo.toString(), String.format(Locale.US,"%.6f",p.epochSeconds),
            clock.format(Instant.ofEpochMilli((p.epochSeconds*1000).toLong())),
            p.payload.joinToString("") { String.format(Locale.US,"%02X",it.toInt() and 255) },
            p.payload.size.toString(),hex(p.kind),names[p.kind] ?: "Unknown",address?.let(::hex) ?: "",
            sector,if(isNorth)"1" else "0",if(isSector)"1" else "0","SYNC2WIFI",
            "RS232 SYNC → Wi-Fi / UDP","SSID SYNC · 192.168.4.1:5005",
            (p.crcFailTotal ?: 0).toString(),(p.framingFailTotal ?: 0).toString()).joinToString(",")
    }
}

data class LiveTimingSnapshot(val missed: Long=0,val jitter: Long=0)
internal class LiveNorthTiming {
    private val low=PriorityQueue<Double>(reverseOrder())
    private val high=PriorityQueue<Double>()
    private var prev: Double?=null
    private var counts=LiveTimingSnapshot()
    fun snapshot()=counts
    fun add(time: Double) {
        val delta=prev?.let { time-it }
        prev=time
        if(delta==null || delta<=0 || !delta.isFinite())return
        if(low.isEmpty() || delta<=low.peek())low.add(delta) else high.add(delta)
        if(low.size>high.size+1)high.add(low.remove())
        if(high.size>low.size)low.add(high.remove())
        if(low.size+high.size<10)return
        val expected=if(low.size==high.size)(low.peek()+high.peek())/2 else low.peek()
        val ratio=delta/expected
        if(ratio>1.9)counts=counts.copy(missed=counts.missed+1)
        else if(round(abs(ratio-1.0)*1000)/10>5.0)counts=counts.copy(jitter=counts.jitter+1)
    }
}
