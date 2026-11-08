package gr.aircat.android

import java.io.BufferedReader
import java.io.Reader
import kotlin.math.hypot

/** Pure Kotlin, no Android dependencies: shared by Android and command-line regression tests. */
data class RadarTrack(val number: Int, val xRaw: Int, val yRaw: Int, val primary: Boolean, val modeA: String?) {
    val rangeNm32: Double get() = hypot(xRaw.toDouble(), yRaw.toDouble()) / 32.0
}

data class RadarPacket(
    val frameNo: Long, val epochSeconds: Double, val payload: ByteArray,
    val kind: Int, val sector: Int?, val tracks: List<RadarTrack>,
    val crcFailTotal: Long?, val framingFailTotal: Long?
)

data class CaptureSummary(
    val packets: Int, val sectors: Int, val north: Int, val extension: Int,
    val completeRotations: Int, val rotations32of32: Int, val missingSectorIds: Int,
    val duplicateSectors: Int, val outOfOrder: Int, val invalidRows: Int,
    val tracksDecoded: Int, val crcFailuresDuringCsv: Long?, val framingFailuresDuringCsv: Long?
)

data class RadarCapture(val packets: List<RadarPacket>, val summary: CaptureSummary, val name: String)

object AircatCsv {
    /** CsvWriter-compatible with Python csv.writer: quoted cells, escaped quotes and BOM. */
    fun columns(line: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        var at = 0
        while (at < line.length) {
            val ch = line[at]
            when {
                ch == '"' && quoted && at + 1 < line.length && line[at + 1] == '"' -> {
                    current.append('"'); at++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { parts.add(current.toString()); current.setLength(0) }
                else -> current.append(ch)
            }
            at++
        }
        parts.add(current.toString())
        return parts
    }

    fun hexBytes(hex: String): ByteArray? {
        val compact = hex.filterNot { it.isWhitespace() }
        if (compact.length % 2 != 0 || compact.isEmpty()) return null
        val bytes = ByteArray(compact.length / 2)
        for (i in bytes.indices) {
            val high = compact[i * 2].digitToIntOrNull(16) ?: return null
            val low = compact[i * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[i] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    private fun ByteArray.u(pos: Int) = this[pos].toInt() and 255
    private fun ByteArray.s16(pos: Int): Int {
        val raw = (u(pos) shl 8) or u(pos + 1)
        return if (raw >= 32768) raw - 65536 else raw
    }

    /** Mirrors the verified Windows parser: sector header 0C 40 FD XX; elements FE/FF length N. */
    fun tracks(payload: ByteArray): List<RadarTrack> {
        val offset = when {
            payload.size >= 4 && payload.u(0) == 0x0C && payload.u(1) == 0x40 && payload.u(2) in listOf(0xFD, 0xFC) -> 4
            payload.size >= 2 && payload.u(0) == 0x0D && payload.u(1) == 0x40 -> 2
            else -> return emptyList()
        }
        val found = ArrayList<RadarTrack>()
        var pos = offset
        while (pos < payload.size) {
            val key = payload.u(pos)
            val left = payload.size - pos
            when (key) {
                0xFE, 0xFF -> {
                    if (left < 10) break
                    val count = payload.u(pos + 1)
                    if (count < 10 || count > left) break
                    val number = (((payload.u(pos + 4) shl 8) or payload.u(pos + 5)) and 0x01FF)
                    val x = payload.s16(pos + 6)
                    val y = payload.s16(pos + 8)
                    var modeA: String? = null
                    if (key == 0xFF) {
                        for (k in listOf(12, 14, 16)) {
                            if (k + 1 >= count) continue
                            val word = (payload.u(pos + k) shl 8) or payload.u(pos + k + 1)
                            if ((word shr 12) and 7 == 1) {
                                val value = word and 0x0FFF
                                modeA = "${(value shr 9) and 7}${(value shr 6) and 7}${(value shr 3) and 7}${value and 7}"
                                break
                            }
                        }
                    }
                    found.add(RadarTrack(number, x, y, key == 0xFE, modeA))
                    pos += count
                }
                0xFB -> pos += when {
                    left >= 4 && payload.u(pos + 3) == 0 -> 4
                    left >= 3 -> 3
                    left >= 2 -> 2
                    else -> break
                }
                0xFA, 0x00 -> if (left >= 4) pos += 4 else break
                else -> break
            }
        }
        return found
    }

    fun read(reader: Reader, name: String = "capture.csv"): RadarCapture {
        val input = BufferedReader(reader)
        val heading = input.readLine()?.removePrefix("\uFEFF")
            ?: throw IllegalArgumentException("Το CSV είναι κενό")
        val keys = columns(heading).map { it.trim().lowercase() }
        val hexIndex = keys.indexOf("udp_payload_hex")
        if (hexIndex == -1) throw IllegalArgumentException("Δεν βρέθηκε η στήλη udp_payload_hex")
        fun index(key: String) = keys.indexOf(key)
        val tsColumn = index("time_epoch")
        val frameColumn = index("frame_no")
        val crcColumn = index("usb_crc_fail_total")
        val framingColumn = index("usb_framing_error_total")
        val packets = ArrayList<RadarPacket>()
        var invalid = 0
        var rowNumber = 0L
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            rowNumber++
            val cells = columns(line)
            fun field(idx: Int): String = cells.getOrNull(idx)?.trim() ?: ""
            val raw = hexBytes(field(hexIndex))
            if (raw == null || raw.size < 2 || (raw[1].toInt() and 255) != 0x40) {
                invalid++; continue
            }
            val kind = raw[0].toInt() and 255
            val sector = if (kind == 0x0C && raw.size >= 4 && raw[2].toInt() and 255 == 0xFD &&
                (raw[3].toInt() and 255) in 0..31) raw[3].toInt() and 255 else null
            val time = field(tsColumn).toDoubleOrNull() ?: (packets.size / 2400.0)
            val frame = field(frameColumn).toLongOrNull() ?: rowNumber
            packets.add(RadarPacket(frame, time, raw, kind, sector, tracks(raw),
                field(crcColumn).toLongOrNull(), field(framingColumn).toLongOrNull()))
        }
        if (packets.isEmpty()) throw IllegalArgumentException("Δεν βρέθηκαν έγκυρα AIRCAT payloads")
        val north = packets.count { it.kind == 0x19 }
        val sectors = packets.count { it.sector != null }
        val extension = packets.count { it.kind == 0x0D }
        var closed = 0
        var perfect = 0
        var missing = 0
        var duplicates = 0
        var outOfOrder = 0
        var active = false
        val seen = HashSet<Int>()
        var last: Int? = null
        var rotationDuplicates = 0
        var rotationOrder = 0
        for (packet in packets) {
            if (packet.kind == 0x19 && packet.payload.size >= 6 &&
                packet.payload[2].toInt() and 255 == 0xFD) {
                if (active) {
                    closed++
                    missing += 32 - seen.size
                    if (seen.size == 32 && rotationDuplicates == 0 && rotationOrder == 0) perfect++
                }
                active = true
                seen.clear(); last = null
                rotationDuplicates = 0; rotationOrder = 0
                continue
            }
            if (active && packet.sector != null) {
                val sec = packet.sector
                if (!seen.add(sec)) { duplicates++; rotationDuplicates++ }
                if (last != null && sec != last + 1) { outOfOrder++; rotationOrder++ }
                last = sec
            }
        }
        val crc = packets.mapNotNull { it.crcFailTotal }
        val framing = packets.mapNotNull { it.framingFailTotal }
        val summary = CaptureSummary(packets.size, sectors, north, extension, closed, perfect,
            missing, duplicates, outOfOrder, invalid, packets.sumOf { it.tracks.size },
            if (crc.isNotEmpty()) (crc.last() - crc.first()).coerceAtLeast(0) else null,
            if (framing.isNotEmpty()) (framing.last() - framing.first()).coerceAtLeast(0) else null)
        return RadarCapture(packets, summary, name)
    }
}

/** In-memory replay renderer state; no synthetic targets are added. */
class RadarReplayState {
    val tracksBySector = HashMap<Int, List<RadarTrack>>()
    var sector: Int? = null
    var cycle = 0
    var packetsShown = 0
    fun clear() {
        tracksBySector.clear(); sector = null; cycle = 0; packetsShown = 0
    }
    fun apply(packet: RadarPacket) {
        packetsShown++
        when {
            packet.kind == 0x19 -> {
                cycle++
                tracksBySector.clear()
                sector = null
            }
            packet.sector != null -> {
                sector = packet.sector
                tracksBySector[packet.sector] = packet.tracks
            }
            packet.kind == 0x0D && packet.tracks.isNotEmpty() -> {
                tracksBySector[sector ?: -1] = packet.tracks
            }
        }
    }
    fun allTracks(): List<RadarTrack> = tracksBySector.values.flatten()
}
