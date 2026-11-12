package gr.aircat.android

import java.io.StringReader
import java.util.Locale
import kotlin.math.min

/** Classic PCAP and PCAPNG offline UDP AIRCAT500 loader. No Wireshark dependency. */
data class PcapImport(val capture: RadarCapture, val inspectedPackets: Int, val udpPackets: Int)

object AircatPcap {
    private fun u(b: Byte): Int = b.toInt() and 255
    private fun uint(b: ByteArray, at: Int, little: Boolean): Long {
        if (at < 0 || at+4 > b.size) return -1
        val x = if (little) (u(b[at]).toLong() or (u(b[at+1]).toLong() shl 8) or
            (u(b[at+2]).toLong() shl 16) or (u(b[at+3]).toLong() shl 24)) else
            ((u(b[at]).toLong() shl 24) or (u(b[at+1]).toLong() shl 16) or
             (u(b[at+2]).toLong() shl 8) or u(b[at+3]).toLong())
        return x and 0xFFFFFFFFL
    }
    private fun uint16(b: ByteArray, at: Int, little: Boolean): Int {
        if (at < 0 || at+2 > b.size) return -1
        return if (little) u(b[at]) or (u(b[at+1]) shl 8) else (u(b[at]) shl 8) or u(b[at+1])
    }
    private fun ipPayload(frame: ByteArray, link: Int): ByteArray? {
        var at = when (link) {
            1 -> { // Ethernet II, optional single/stacked VLAN tags
                if (frame.size < 14) return null
                var protocol = uint16(frame,12,false)
                var offset = 14
                while (protocol == 0x8100 || protocol == 0x88A8) {
                    if (frame.size < offset+4) return null
                    protocol = uint16(frame,offset+2,false); offset += 4
                }
                if (protocol != 0x0800) return null
                offset
            }
            101, 228 -> 0 // raw IPv4
            113 -> { // Linux cooked capture v1
                if (frame.size < 16 || uint16(frame,14,false) != 0x0800) return null
                16
            }
            276 -> { // Linux cooked capture v2
                if (frame.size < 20 || uint16(frame,0,false) != 0x0800) return null
                20
            }
            else -> return null
        }
        if (at + 28 > frame.size) return null
        if (u(frame[at]) ushr 4 != 4) return null
        val ihl = (u(frame[at]) and 15) * 4
        if (ihl < 20 || at+ihl+8 > frame.size || u(frame[at+9]) != 17) return null
        // Fragmented UDP not reassembled: non-initial fragments are ignored.
        if (uint16(frame, at+6, false) and 0x1FFF != 0) return null
        val ipLen = uint16(frame, at+2, false)
        val udpStart = at + ihl
        val udpLen = uint16(frame, udpStart+4, false)
        val start = udpStart + 8
        val end = min(frame.size, min(at + ipLen, start + maxOf(0, udpLen-8)))
        if (udpLen < 8 || end <= start || start > frame.size) return null
        return frame.copyOfRange(start, end)
    }

    fun import(data: ByteArray, name: String = "capture.pcap"): PcapImport {
        if (data.size < 12) throw IllegalArgumentException("Κενό PCAP/PCAPNG")
        val csv = StringBuilder("frame_no,time_epoch,udp_payload_hex\n")
        var frames = 0
        var udp = 0
        var accepted = 0
        fun packet(payload: ByteArray, time: Double, link: Int) {
            frames++
            val result = ipPayload(payload,link) ?: return
            udp++
            if (result.size < 2 || u(result[1]) != 0x40) return
            accepted++
            csv.append(frames).append(',').append(String.format(Locale.US,"%.6f",time))
                .append(',').append(result.joinToString("") { "%02X".format(u(it)) }).append('\n')
        }
        if (u(data[0]) == 0x0A && u(data[1]) == 0x0D && u(data[2]) == 0x0D && u(data[3]) == 0x0A) {
            // PCAPNG: sections can change endianness. IDB ts-resolution defaults to microseconds.
            var at = 0
            var little = true
            val links = ArrayList<Int>()
            val resolutions = ArrayList<Double>()
            while (at+12 <= data.size) {
                val isSection = u(data[at]) == 0x0A && u(data[at+1]) == 0x0D &&
                    u(data[at+2]) == 0x0D && u(data[at+3]) == 0x0A
                if (isSection) {
                    if (at+12 > data.size) break
                    little = when ((at+8 until at+12).map { u(data[it]) }) {
                        listOf(0x4D,0x3C,0x2B,0x1A) -> true
                        listOf(0x1A,0x2B,0x3C,0x4D) -> false
                        else -> break
                    }
                    links.clear(); resolutions.clear()
                }
                val type = uint(data,at,little)
                val blockLength = uint(data,at+4,little).toInt()
                if (blockLength < 12 || blockLength > data.size-at || uint(data,at+blockLength-4,little).toInt() != blockLength) break
                when (type) {
                    1L -> if (blockLength >= 20) {
                        links.add(uint16(data,at+8,little))
                        var resolution = 1e-6
                        var optionAt = at+16
                        while (optionAt+4 <= at+blockLength-4) {
                            val code = uint16(data,optionAt,little)
                            val len = uint16(data,optionAt+2,little)
                            if (code == 0 || len < 0 || optionAt+4+len > at+blockLength-4) break
                            if (code == 9 && len >= 1) {
                                val value = u(data[optionAt+4]); val x = value and 127
                                resolution = if (value and 0x80 != 0) Math.pow(2.0,-x.toDouble()) else Math.pow(10.0,-x.toDouble())
                            }
                            optionAt += 4 + ((len+3)/4)*4
                        }
                        resolutions.add(resolution)
                    }
                    6L -> if (blockLength >= 32) {
                        val iface = uint(data,at+8,little).toInt()
                        val tsHigh = uint(data,at+12,little)
                        val tsLow = uint(data,at+16,little)
                        val incl = uint(data,at+20,little).toInt()
                        if (iface in links.indices && incl >= 0 && incl <= blockLength-32 && at+28+incl <= data.size) {
                            val timestamp = (tsHigh * 4294967296.0 + tsLow) * resolutions[iface]
                            packet(data.copyOfRange(at+28,at+28+incl),timestamp,links[iface])
                        }
                    }
                    3L -> if (blockLength >= 16 && links.isNotEmpty()) {
                        val incl = blockLength-16
                        packet(data.copyOfRange(at+12,at+12+incl),frames.toDouble(),links.first())
                    }
                }
                at += blockLength
            }
        } else {
            if (data.size < 24) throw IllegalArgumentException("Μη έγκυρο PCAP header")
            val header = (0..3).map { u(data[it]) }
            val little = header == listOf(0xD4,0xC3,0xB2,0xA1) || header == listOf(0x4D,0x3C,0xB2,0xA1)
            val nano = header == listOf(0x4D,0x3C,0xB2,0xA1) || header == listOf(0xA1,0xB2,0x3C,0x4D)
            if (header !in listOf(listOf(0xD4,0xC3,0xB2,0xA1),listOf(0xA1,0xB2,0xC3,0xD4),listOf(0x4D,0x3C,0xB2,0xA1),listOf(0xA1,0xB2,0x3C,0x4D)))
                throw IllegalArgumentException("Μη αναγνωρίσιμο PCAP format")
            val link = uint(data,20,little).toInt()
            var at=24
            while (at+16 <= data.size) {
                val seconds=uint(data,at,little)
                val fraction=uint(data,at+4,little)
                val len=uint(data,at+8,little).toInt()
                at += 16
                if (len < 0 || len > data.size-at) break
                val timestamp=seconds + fraction.toDouble() * (if (nano) 1e-9 else 1e-6)
                packet(data.copyOfRange(at,at+len),timestamp,link)
                at += len
            }
        }
        if (accepted == 0) throw IllegalArgumentException("Δεν βρέθηκαν AIRCAT500 UDP payloads (IPv4)")
        return PcapImport(AircatCsv.read(StringReader(csv.toString()),name),frames,udp)
    }
}
