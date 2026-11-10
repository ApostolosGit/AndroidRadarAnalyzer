package gr.aircat.android

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Display filters adopted from the desktop AIRCAT500 Analyzer. */
data class RadarFilter(
    val showPSR: Boolean = true,
    val showMSSR: Boolean = true,
    val showCombined: Boolean = true,
    val showLowQuality: Boolean = true,
    val showCancelled: Boolean = true,
    val showLabels: Boolean = true,
    val showTrackNumber: Boolean = true,
    val showModeA: Boolean = true,
    val showModeC: Boolean = true,
    val showSpeed: Boolean = false,
    val showRange: Boolean = false,
    val showQuality: Boolean = false,
    val showVelocityVectors: Boolean = false,
    val showTrails: Boolean = true,
    val showOnlyAlerts: Boolean = false,
    val hideInvalidMode: Boolean = false,
    val minAltitudeFt: Int? = null,
    val maxAltitudeFt: Int? = null,
    val minSpeedKt: Int? = null,
    val maxSpeedKt: Int? = null
) {
    fun allows(track: RadarTrack): Boolean {
        val kindEnabled = when {
            track.combined -> showCombined
            track.primary -> showPSR
            else -> showMSSR
        }
        if (!kindEnabled || (!showLowQuality && track.isLowQuality) ||
            (!showCancelled && track.cancelled) ||
            (showOnlyAlerts && !track.alert) ||
            (hideInvalidMode && (track.modeAInvalid || track.modeCInvalid))) return false

        // Mode C is supplied by the AIRCAT decoder as feet e.g. "17500ft".
        // An unreported/invalid Mode C must not silently pass an altitude filter.
        if (minAltitudeFt != null || maxAltitudeFt != null) {
            val altitude = altitudeFeet(track.modeC) ?: return false
            if (minAltitudeFt != null && altitude < minAltitudeFt) return false
            if (maxAltitudeFt != null && altitude > maxAltitudeFt) return false
        }
        if (minSpeedKt != null || maxSpeedKt != null) {
            val speed = track.speedKt ?: return false
            if (minSpeedKt != null && speed < minSpeedKt) return false
            if (maxSpeedKt != null && speed > maxSpeedKt) return false
        }
        return true
    }

    companion object {
        private val MODE_C_FEET = Regex("^([+-]?\\d+)ft$", RegexOption.IGNORE_CASE)
        fun altitudeFeet(value: String?): Int? {
            val text = value?.trim() ?: return null
            return MODE_C_FEET.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}

/** Independent offline diagnostics. The same sector ID is NOT counted twice because the North cycle is invalid. */
data class RadarCycleIssue(
    val northFrame: Long, val firstTime: Double, val nextTime: Double,
    val sectorsReceived: Int, val missing: List<Int>,
    val duplicateCount: Int, val orderFaults: Int, val invalid: Boolean,
    val crcSuspect: Boolean
)

data class RadarAlert(
    val frame: Long, val time: Double, val sector: Int?, val track: Int?,
    val severity: Int, val category: String, val detail: String
)

data class CaptureDiagnostics(
    val typeCounts: Map<Int, Int>,
    val sectorCounts: IntArray,
    val missingCounts: IntArray,
    val cycles: List<RadarCycleIssue>,
    val northIntervals: List<Double>,
    val timingMissedScans: Int,
    val northJitterEvents: Int,
    val protocolOnlyCycles: Int,
    val distinctFaults: Int,
    val alerts: List<RadarAlert>,
    val trackCounts: Map<String, Int>,
    val modeACounts: Map<String, Int>,
    val ignoredLeading: Int,
    val ignoredTrailing: Int,
    val crcAssociatedGaps: Int,
    val firstTimestamp: Double?, val lastTimestamp: Double?
) {
    val missingTotal: Int get() = missingCounts.sum()
    val invalidCycles: Int get() = cycles.count { it.invalid }
    val validCycles: Int get() = cycles.count { !it.invalid }
    val completedCycles: Int get() = cycles.size
    val perfectCycles: Int get() = cycles.count { !it.invalid && it.missing.isEmpty() }
    val medianPeriod: Double? get() = if (northIntervals.isEmpty()) null else northIntervals.sorted().let {
        if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2] + it[it.size / 2 - 1]) / 2.0
    }
    val rpm: Double? get() = medianPeriod?.takeIf { it > 0 }?.let { 60.0 / it }
}

object AircatAnalysis {
    fun analyze(capture: RadarCapture): CaptureDiagnostics {
        val packets = capture.packets
        val typeCounts = packets.groupingBy { it.kind }.eachCount().toSortedMap()
        val sectors = IntArray(32)
        val missingCounts = IntArray(32)
        for (packet in packets) packet.sector?.let { if (it in 0..31) sectors[it]++ }
        val cycleIssues = ArrayList<RadarCycleIssue>()
        val northTimes = ArrayList<Double>()
        var previousNorth: RadarPacket? = null
        var leading = 0
        var lastNorthIndex = -1
        var currentSectors = ArrayList<Int>()
        var crcSuspectGaps = 0
        var prevCrc = 0L
        var prevFrame = 0L
        var currentCrcChanged = false
        for ((i, packet) in packets.withIndex()) {
            val isNorth = packet.kind == 0x19 && packet.payload.size >= 6 &&
                (packet.payload[2].toInt() and 255) == 0xFD
            if (isNorth) {
                val previous = previousNorth
                if (previous != null) {
                    val missing = (0..31).filterNot { currentSectors.contains(it) }
                    for (sec in missing) missingCounts[sec]++
                    val dup = currentSectors.size - currentSectors.distinct().size
                    // Skipped IDs are counted as missing, not reclassified as
                    // out-of-order: only backward/repeated transitions matter.
                    val order = currentSectors.zipWithNext().count { (a, b) -> b <= a }
                    val invalid = missing.isNotEmpty() || currentSectors.size != 32 || order > 0 || dup > 0
                    val closingCrc = packet.crcFailTotal?.let { it > prevCrc } ?: false
                    val closingFrame = packet.framingFailTotal?.let { it > prevFrame } ?: false
                    if (currentCrcChanged || closingCrc || closingFrame) crcSuspectGaps += missing.size
                    cycleIssues.add(RadarCycleIssue(previous.frameNo, previous.epochSeconds,
                        packet.epochSeconds, currentSectors.size, missing, dup, order, invalid,
                        currentCrcChanged || closingCrc || closingFrame))
                }
                previousNorth = packet
                lastNorthIndex = i
                northTimes.add(packet.epochSeconds)
                currentSectors = ArrayList()
                currentCrcChanged = false
                prevCrc = packet.crcFailTotal ?: prevCrc
                prevFrame = packet.framingFailTotal ?: prevFrame
            } else if (previousNorth == null) {
                leading++
            } else {
                packet.sector?.let { if (it in 0..31) currentSectors.add(it) }
                val crc = packet.crcFailTotal
                val framing = packet.framingFailTotal
                if (crc != null && crc > prevCrc || framing != null && framing > prevFrame)
                    currentCrcChanged = true
                if (crc != null) prevCrc = crc
                if (framing != null) prevFrame = framing
            }
        }
        val trailing = if (lastNorthIndex < 0) 0 else packets.size - lastNorthIndex
        val intervals = northTimes.zipWithNext().map { (a, b) -> b - a }.filter { it > 0 }
        val median = intervals.sorted().let { if (it.isEmpty()) 0.0 else if (it.size % 2 == 0) (it[it.size / 2] + it[it.size / 2 - 1]) / 2 else it[it.size / 2] }
        var missed = 0
        var jitter = 0
        if (median > 0) for (interval in intervals) {
            val ratio = interval / median
            if (ratio > 1.9) missed++
            else if (abs(ratio - 1.0) > 0.05) jitter++
        }
        val protocolOnly = cycleIssues.count { it.invalid && it.missing.isEmpty() }
        val faultTotal = missingCounts.sum() + missed + protocolOnly

        val alertEvents = ArrayList<RadarAlert>()
        val sourceCounts = mutableMapOf<String, Int>()
        val modeCounts = mutableMapOf<String, Int>()
        for (packet in packets) {
            for (track in packet.tracks) {
                if (track.cancellation) {
                    alertEvents.add(RadarAlert(packet.frameNo, packet.epochSeconds, packet.sector,
                        track.number, 1, "CANCEL", "Track ${track.number} cancelled"))
                    continue
                }
                sourceCounts[track.kindLabel] = (sourceCounts[track.kindLabel] ?: 0) + 1
                track.modeA?.let { modeCounts[it] = (modeCounts[it] ?: 0) + 1 }
                val special = track.specialModeAlert
                if (special != null) {
                    alertEvents.add(RadarAlert(packet.frameNo, packet.epochSeconds, packet.sector,
                        track.number, 3, "MODE A", special))
                }
                if (track.emergency) alertEvents.add(RadarAlert(packet.frameNo,
                    packet.epochSeconds, packet.sector, track.number, 3, "SSR EMERGENCY", "P/F emergency bit"))
                if (track.spi) alertEvents.add(RadarAlert(packet.frameNo,
                    packet.epochSeconds, packet.sector, track.number, 2, "SPI", "Ident / SPI SSR reply"))
                if (track.modeAInvalid || track.modeCInvalid) alertEvents.add(RadarAlert(packet.frameNo,
                    packet.epochSeconds, packet.sector, track.number, 1, "VALIDATION",
                    "Mode A invalid ${track.modeAInvalid}; Mode C invalid ${track.modeCInvalid}"))
                if (track.isLowQuality) alertEvents.add(RadarAlert(packet.frameNo,
                    packet.epochSeconds, packet.sector, track.number, 1, "LOW QUALITY",
                    "Quality ${track.quality}/${if (track.primary) 7 else 127}"))
            }
        }
        return CaptureDiagnostics(typeCounts, sectors, missingCounts, cycleIssues, intervals, missed,
            jitter, protocolOnly, faultTotal, alertEvents, sourceCounts.toSortedMap(),
            modeCounts.toSortedMap(), leading, trailing, crcSuspectGaps,
            packets.firstOrNull()?.epochSeconds, packets.lastOrNull()?.epochSeconds)
    }
}

/** Native text export, designed to be comparable to the supplied Python v9.16 summary. */
object AircatReport {
    private fun fmt(value: Double?, digits: Int = 2) = value?.let { String.format(Locale.US, "% .${digits}f", it).trim() } ?: "n/a"
    private fun section(text: String) = "\n${"-".repeat(54)}\n$text\n${"-".repeat(54)}\n"

    fun create(capture: RadarCapture, stats: CaptureDiagnostics): String = buildString {
        val base = capture.summary
        appendLine("AIRCAT500 MOBILE v1.0 — ΑΝΑΛΥΣΗ ΚΑΤΑΓΡΑΦΗΣ")
        appendLine("Αρχείο: ${capture.name}")
        appendLine("Οι μετρήσεις προέρχονται από πραγματικά AIRCAT payloads.")
        append(section("ΠΑΚΕΤΑ / NORTH / SECTORS"))
        appendLine("Packets: ${base.packets}")
        appendLine("NORTH: ${base.north}")
        appendLine("Radar Sector: ${base.sectors}")
        appendLine("Radar Extension: ${base.extension}")
        appendLine("Πλήρεις NORTH-to-NORTH κύκλοι: ${stats.completedCycles}")
        appendLine("Έγκυροι κύκλοι 32/32 και σειρά 00..1F: ${stats.validCycles}")
        appendLine("Invalid North cycles: ${stats.invalidCycles}")
        appendLine("Αγνοημένα πριν τον πρώτο NORTH: ${stats.ignoredLeading}")
        appendLine("Τελευταίος μερικός κύκλος: ${stats.ignoredTrailing} πακέτα")
        appendLine("North period median: ${fmt(stats.medianPeriod, 3)} sec")
        appendLine("RPM (median): ${fmt(stats.rpm)}")
        append(section("ΣΥΝΟΨΗ ΣΦΑΛΜΑΤΩΝ (ΧΩΡΙΣ ΔΙΠΛΟΜΕΤΡΗΣΗ)"))
        appendLine("Scan missed: ${stats.timingMissedScans}")
        appendLine("North missing valid sectors: ${stats.cycles.count { it.missing.isNotEmpty() }}")
        appendLine("Invalid North cycles: ${stats.invalidCycles} (διαγνωστικό, δεν προστίθεται ξανά)")
        appendLine("Sector missing συνολικά: ${stats.missingTotal}")
        appendLine("Protocol-only cycles: ${stats.protocolOnlyCycles}")
        appendLine("North jitter: ${stats.northJitterEvents}")
        appendLine("Συνολικά ανεξάρτητα σφάλματα: ${stats.distinctFaults}")
        appendLine("Missing IDs κοντά σε CRC/framing: ${stats.crcAssociatedGaps} (υποψία, όχι επιπλέον σφάλματα)")
        appendLine("USB CRC FAIL delta: ${base.crcFailuresDuringCsv?.toString() ?: "δεν υπάρχουν snapshots"}")
        appendLine("USB Framing delta: ${base.framingFailuresDuringCsv?.toString() ?: "δεν υπάρχουν snapshots"}")
        appendLine("Απορριφθέντα CRC frames δεν είναι ορατά στο CSV payload.")
        append(section("ΣΥΧΝΟΤΗΤΑ ΚΑΤΑ SECTOR"))
        for (sec in 0..31) appendLine("Sector %02X : packets=%4d   missing=%2d".format(Locale.US,sec, stats.sectorCounts[sec], stats.missingCounts[sec]))
        append(section("ΤΥΠΟΙ AIRCAT"))
        for ((type, count) in stats.typeCounts) appendLine("0x%02X   %d".format(Locale.US,type,count))
        append(section("ΣΤΟΧΟΙ / ALERTS"))
        appendLine("Track elements: ${base.tracksDecoded}")
        for ((type, count) in stats.trackCounts) appendLine("$type : $count")
        for (code in listOf("7000","7500","7600","7601","7700")) {
            appendLine("Mode A $code: ${stats.modeACounts[code] ?: 0}")
        }
        for ((type, count) in stats.alerts.groupingBy { it.category }.eachCount().toSortedMap()) appendLine("$type : $count")
        appendLine("\nΟι απώλειες sector δεν αποδεικνύουν μόνες τους φυσικό σφάλμα γραμμής.")
        appendLine("Το UDP/Wi-Fi Live θα ενεργοποιηθεί όταν διατεθεί συμβατό firmware.")
    }

    fun errors(stats: CaptureDiagnostics): String = buildString {
        appendLine("ΣΦΑΛΜΑΤΑ NORTH / SECTOR — AIRCAT500")
        appendLine("Missing sectors: ${stats.missingTotal}; Invalid cycles: ${stats.invalidCycles}; UNIQUE faults: ${stats.distinctFaults}")
        appendLine("Scan missed: ${stats.timingMissedScans}; Jitter: ${stats.northJitterEvents}")
        appendLine("\nΛΙΣΤΑ INVALID ΚΥΚΛΩΝ:")
        for ((i, cycle) in stats.cycles.withIndex()) if (cycle.invalid) {
            appendLine("${i+1}. NORTH frame ${cycle.northFrame} sectors=${cycle.sectorsReceived} missing=${cycle.missing.joinToString { "%02X".format(it) }.ifEmpty { "—" }} duplicates=${cycle.duplicateCount} order=${cycle.orderFaults}${if (cycle.crcSuspect) " CRC-suspect" else ""}")
        }
        if (stats.invalidCycles == 0) appendLine("Δεν βρέθηκαν invalid κύκλοι")
    }

    fun alerts(stats: CaptureDiagnostics, onlyCritical: Boolean = false): String = buildString {
        val alerts = if (onlyCritical) stats.alerts.filter { it.severity >= 2 } else stats.alerts
        appendLine("AIRCAT500 ALERTS — ${alerts.size} events")
        for (event in alerts.take(1500)) {
            appendLine("Frame ${event.frame}  Sector ${event.sector?.let { "%02X".format(it) } ?: "—"}  Track ${event.track ?: "—"}  [${event.category}] ${event.detail}")
        }
        if (alerts.size > 1500) appendLine("...εμφάνιση μόνο πρώτων 1500 events, αποθήκευσε report για σύνοψη.")
        if (alerts.isEmpty()) appendLine("Δεν βρέθηκαν events του επιλεγμένου τύπου.")
    }
}
