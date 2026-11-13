package gr.aircat.android

import java.io.BufferedReader
import java.io.Reader
import kotlin.math.hypot

/** Pure Kotlin, no Android dependencies: shared by Android and command-line regression tests. */
/** AIRCAT500 FE/FF fields mirror the validated Windows v9.16 decoder. */
data class RadarTrack(
    val number: Int, val xRaw: Int, val yRaw: Int, val primary: Boolean, val modeA: String?,
    val modeC: String? = null, val modeAInvalid: Boolean = false,
    val modeCInvalid: Boolean = false, val modeValidationSource: String? = null,
    val quality: Int? = null, val detectionMisses: Int? = null,
    val maneuver: Boolean = false, val speedKt: Int? = null,
    val dx: Int? = null, val dy: Int? = null,
    val spi: Boolean = false, val emergency: Boolean = false,
    val combined: Boolean = false, val tpr: Int? = null,
    val logSigmaVolts: Double? = null, val timeSinceNorthSec: Double? = null,
    val trackingWindow: String? = null, val smoothingK: Int? = null,
    val cancellation: Boolean = false, val cancelled: Boolean = false,
    val rawHex: String = ""
) {
    val rangeNm32: Double get() = hypot(xRaw.toDouble(), yRaw.toDouble()) / 32.0
    val isLowQuality: Boolean get() = quality?.let { if (primary) it <= 3 else it < 95 } ?: false
    val specialModeAlert: String? get() = when (modeA) {
        "7500" -> "7500 HIJACK"
        "7600" -> "7600 RADIO FAILURE"
        "7601" -> "7601 RADIO FAILURE IFR/VMC"
        "7700" -> "7700 EMERGENCY"
        else -> null
    }
    val alert: Boolean get() = specialModeAlert != null || emergency || spi
    val kindLabel: String get() = when {
        cancellation -> "CANCEL"
        combined -> "PSR+SSR"
        primary -> "PSR"
        else -> "MSSR"
    }
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

    private fun bcd(payload: ByteArray, pos: Int): Int? {
        if (pos + 1 >= payload.size) return null
        val digits = listOf(payload.u(pos) ushr 4, payload.u(pos) and 15,
            payload.u(pos + 1) ushr 4, payload.u(pos + 1) and 15)
        return if (digits.all { it <= 9 }) digits.fold(0) { n, digit -> n * 10 + digit } else null
    }

    private fun signed8(value: Int) = if (value >= 128) value - 256 else value

    /**
     * Mirrors _parse_replay_tracks_from_payload in the supplied Python 9.16:
     * FE/FF length-prefixed tracks, SSR A/C words, optional validation byte 21,
     * service flags, quality, velocity, timing and FB cancellations.
     */
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
                    val bytes = payload.copyOfRange(pos, pos + count)
                    val number = (((bytes.u(4) shl 8) or bytes.u(5)) and 0x01FF)
                    val x = (bytes.u(6) shl 8 or bytes.u(7)).let { if (it >= 32768) it - 65536 else it }
                    val y = (bytes.u(8) shl 8 or bytes.u(9)).let { if (it >= 32768) it - 65536 else it }
                    var modeA: String? = null
                    var modeC: String? = null
                    var aC = 0
                    var cC = 0
                    if (key == 0xFF) {
                        for (at in listOf(12, 14, 16)) {
                            if (at + 1 >= count) continue
                            val word = bytes.u(at) shl 8 or bytes.u(at + 1)
                            val modeType = (word ushr 12) and 7
                            val value = word and 0x0FFF
                            if (modeType == 1 && modeA == null) {
                                modeA = "${(value ushr 9) and 7}${(value ushr 6) and 7}${(value ushr 3) and 7}${value and 7}"
                                aC = word ushr 15 and 1
                            } else if (modeType == 3 && modeC == null) {
                                val h = value ushr 8 and 15
                                val t = value ushr 4 and 15
                                val u = value and 15
                                modeC = when {
                                    h <= 9 && t <= 9 && u <= 9 -> "${(h * 100 + t * 10 + u) * 100}ft"
                                    h == 15 && t <= 9 && u <= 9 -> "-${(t * 10 + u) * 100}ft"
                                    else -> "0x${value.toString(16).uppercase().padStart(3, '0')}"
                                }
                                cC = word ushr 15 and 1
                            }
                        }
                    }
                    val validation = if (key == 0xFF && count > 20 && (bytes.u(20) and 0xE0) == 0) bytes.u(20) else null
                    val modeAInvalid = if (validation != null) validation and 0x10 != 0 else aC == 1
                    val modeCInvalid = if (validation != null) validation and 0x04 != 0 else cC == 1
                    val logRaw = if (key == 0xFF && count > 20) {
                        if (validation == null) bytes.u(20) else if (count > 21) bytes.u(21) else null
                    } else null
                    val timeAt = if (key == 0xFE) 14 else 22
                    val timing = if (count >= timeAt + 2) bytes.u(timeAt) shl 8 or bytes.u(timeAt + 1) else null
                    val window = timing?.let { listOf("OutE3", "E1", "E2", "E3")[(it ushr 11) and 3] }
                    val service = if (count > 2) bytes.u(2) else 0
                    val primary = key == 0xFE
                    val associated = if (primary) service and 0x04 != 0 else count > 4 && bytes.u(4) and 0x40 != 0
                    val rawText = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
                    found.add(RadarTrack(
                        number, x, y, primary, modeA, modeC,
                        modeAInvalid, modeCInvalid, if (validation != null) "Byte 21" else if (key == 0xFF) "C bit" else null,
                        if (count > 3) if (primary) (bytes.u(3) ushr 2) and 7 else bytes.u(3) and 127 else null,
                        if (count > 4) (bytes.u(4) ushr 4) and 3 else null,
                        count > 4 && bytes.u(4) and 0x04 != 0,
                        if (primary && count >= 14) bcd(bytes, 12) else if (!primary && count >= 20) bcd(bytes, 18) else null,
                        if (count > 11) signed8(bytes.u(10)) else null,
                        if (count > 11) signed8(bytes.u(11)) else null,
                        !primary && service and 0x04 != 0,
                        !primary && service and 0x80 == 0 && service and 0x40 != 0,
                        associated, if (service and 0x02 != 0) 2 else 1,
                        logRaw?.coerceAtMost(250)?.times(0.01),
                        timing?.let { (it and 2047) / 128.0 }, window,
                        timing?.let { (it ushr 13) and 7 }, false, false, rawText
                    ))
                    pos += count
                }
                0xFB -> {
                    if (left < 2) break
                    val count = if (left >= 4 && payload.u(pos + 3) == 0) 4 else if (left >= 3) 3 else 2
                    val bytes = payload.copyOfRange(pos, pos + count)
                    val number = if (count >= 3) (((bytes.u(1) shl 8) or bytes.u(2)) and 511) else bytes.u(1)
                    found.add(RadarTrack(number, 0, 0, false, null, cancellation = true,
                        rawHex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }))
                    pos += count
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
            val parsed = RadarPacket(frame, time, raw, kind, sector, tracks(raw),
                field(crcColumn).toLongOrNull(), field(framingColumn).toLongOrNull())
            packets.add(parsed)
        }
        if (packets.isEmpty()) throw IllegalArgumentException("Δεν βρέθηκαν έγκυρα AIRCAT payloads")
        // Python's file analysis preserves time order when timestamps are present.
        // Capture is normally monotonic, but sort out-of-order CSV rows safely.
        if (packets.size > 1 && packets.zipWithNext().any { (a,b) -> b.epochSeconds < a.epochSeconds }) {
            packets.sortWith(compareBy<RadarPacket> { it.epochSeconds }.thenBy { it.frameNo })
        }
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
        // Ignore leading/trailing partial rotations; never turn them into lost sectors.
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
    val trackTrail = HashMap<Int, ArrayDeque<RadarTrack>>()
    var trailDepth = 16
    var sector: Int? = null
    var cycle = 0
    var packetsShown = 0
    fun clear() {
        tracksBySector.clear(); trackTrail.clear(); sector = null; cycle = 0; packetsShown = 0
    }
    fun apply(packet: RadarPacket) {
        packetsShown++
        when {
            packet.kind == 0x19 -> {
                // NORTH marks a new revolution, it does not erase the plot.
                // Targets are retired only when their own azimuth sector is
                // refreshed, or on an explicit cancellation.
                cycle++
                sector = null
            }
            packet.sector != null -> {
                sector = packet.sector
                val incoming = packet.tracks
                val cancels = incoming.filter { it.cancellation }.map { it.number }.toSet()
                if (cancels.isNotEmpty()) {
                    for ((key, tracks) in tracksBySector) {
                        tracksBySector[key] = tracks.map { if (it.number in cancels) it.copy(cancelled = true) else it }
                    }
                }
                val refreshed = incoming.filterNot { it.cancellation }
                // A track that moved into another sector must not be shown
                // twice while neighbouring sectors await their next sweep.
                val movedNumbers = refreshed.map { it.number }.toSet()
                if (movedNumbers.isNotEmpty()) {
                    for ((oldSector, oldTracks) in tracksBySector.toMap()) {
                        if (oldSector != packet.sector) {
                            tracksBySector[oldSector] = oldTracks.filterNot {
                                it.number in movedNumbers
                            }
                        }
                    }
                }
                tracksBySector[packet.sector] = refreshed
                for (track in refreshed) {
                    val trail = trackTrail.getOrPut(track.number) { ArrayDeque() }
                    trail.add(track)
                    while (trail.size > maxOf(2, trailDepth)) trail.removeFirst()
                }
            }
            packet.kind == 0x0D && packet.tracks.isNotEmpty() -> {
                // Extension messages do not have their own sector number.
                // Extension updates only matching tracks; do not wipe the
                // entire current sector simply because an extension arrived.
                val sectorKey = sector ?: -1
                val extension = packet.tracks.filterNot { it.cancellation }
                val updated = extension.map { it.number }.toSet()
                for ((key,old) in tracksBySector.toMap()) {
                    tracksBySector[key] = old.filterNot { it.number in updated }
                }
                tracksBySector[sectorKey] = (tracksBySector[sectorKey] ?: emptyList()) + extension
                for (track in extension) {
                    val trail = trackTrail.getOrPut(track.number) { ArrayDeque() }
                    trail.add(track)
                    while (trail.size > maxOf(2, trailDepth)) trail.removeFirst()
                }
            }
        }
    }
    fun allTracks(): List<RadarTrack> = tracksBySector.values.flatten()
}

/** Replay positions are real capture timestamps, not an evenly spaced frame number. */
object ReplayTimeline {
    const val STEPS = 10000
    fun indexAtOrBefore(packets: List<RadarPacket>, epochSeconds: Double): Int {
        if (packets.isEmpty()) return 0
        var lo = 0
        var hi = packets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (packets[mid].epochSeconds <= epochSeconds) lo = mid + 1 else hi = mid
        }
        return (lo - 1).coerceIn(0, packets.lastIndex)
    }
    fun position(epoch: Double, first: Double, last: Double): Int {
        if (last <= first) return 0
        return (((epoch - first) / (last - first)) * STEPS).toInt().coerceIn(0, STEPS)
    }
    fun epoch(position: Int, first: Double, last: Double): Double {
        if (last <= first) return first
        return first + position.coerceIn(0, STEPS).toDouble() / STEPS * (last - first)
    }
}

/** Range-ring geometry in nautical miles; model remains correct with panned origin. */
object RadarRangeGrid {
    fun spacingNm(displayRangeNm: Double): Int = when {
        displayRangeNm <= 5.0 -> 1
        displayRangeNm <= 30.0 -> 5
        displayRangeNm <= 60.0 -> 10
        displayRangeNm <= 125.0 -> 25
        else -> 50
    }
    fun rings(displayRangeNm: Double, farthestVisibleRangeNm: Double): List<Int> {
        val step = spacingNm(displayRangeNm)
        val n = (kotlin.math.ceil(farthestVisibleRangeNm / step).toInt() + 1)
            .coerceIn(0, 200)
        return (1..n).map { it * step }
    }
}
