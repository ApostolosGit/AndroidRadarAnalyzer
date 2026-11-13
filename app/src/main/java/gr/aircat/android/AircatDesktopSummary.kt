package gr.aircat.android

import java.util.Locale

/**
 * Desktop v9.16-oriented, capture-backed tables for v2.
 * No synthetic sector/NORTH values are added. Where the mobile decoder does
 * not expose a desktop statistic we explicitly label it as unavailable.
 */
data class DesktopSectorBars(
    val labels: List<String>,
    val correct: List<Int>,
    val missing: List<Int>,
    val withoutNorth: List<Int>,
    val fileName: String
) {
    val totals: List<Int> get() = labels.indices.map { correct[it] + missing[it] + withoutNorth[it] }
}

object AircatDesktopSummary {
    private fun source(track: RadarTrack): String = if (track.primary) "PSR" else "MSSR"

    fun sectorBars(capture: RadarCapture, stats: CaptureDiagnostics): DesktopSectorBars {
        val labels = listOf("North", "Extension", "Weather") + (0..31).map { "%02X".format(Locale.US,it) }
        val valid = stats.validCycles
        val first = stats.cycles.firstOrNull { !it.invalid }?.firstTime
        val last = stats.cycles.lastOrNull { !it.invalid }?.nextTime
        val window = if (first != null && last != null)
            capture.packets.filter { it.epochSeconds in first..last }
        else emptyList()
        val extension = window.count { it.kind == 0x0D }
        val weather = window.count { it.kind == 0x2A }
        // A sector is "without prev North" only when no NORTH has been seen;
        // don't fabricate a count from missing-sector statistics.
        val beforeNorth = IntArray(32)
        var sawNorth = false
        for (packet in capture.packets) {
            if (packet.kind == 0x19) sawNorth = true
            else if (!sawNorth) packet.sector?.let { if (it in 0..31) beforeNorth[it]++ }
        }
        val blue = listOf(valid,extension,weather) + List(32){valid}
        val red = listOf(stats.invalidCycles,0,0) + (0..31).map { stats.missingCounts[it] }
        val pink = listOf(0,0,0) + beforeNorth.toList()
        return DesktopSectorBars(labels,blue,red,pink,capture.name)
    }

    fun errors(capture: RadarCapture, stats: CaptureDiagnostics): String = buildString {
        appendLine("AIRCAT500 — ERRORS / DESKTOP SUMMARY")
        appendLine("Αρχείο: ${capture.name}")
        appendLine("──────────────────────────────────────────")
        appendLine("%-37s %6s".format(Locale.US,"Scan missed", stats.timingMissedScans))
        appendLine("%-37s %6s".format(Locale.US,"North jitter >5%", stats.northJitterEvents))
        appendLine("%-37s %6s".format(Locale.US,"North missing valid sectors",stats.cycles.count { it.missing.isNotEmpty() }))
        appendLine("%-37s %6s".format(Locale.US,"Invalid North cycles (διαγνωστικό)",stats.invalidCycles))
        appendLine("%-37s %6s".format(Locale.US,"Sector missing συνολικά",stats.missingTotal))
        appendLine("%-37s %6s".format(Locale.US,"Συνολικά ανεξάρτητα σφάλματα",stats.distinctFaults))
        appendLine("%-37s %6s".format(Locale.US,"CRC-suspect sector gaps",stats.crcAssociatedGaps))
        appendLine("%-37s %6s".format(Locale.US,"Out-of-order σε κλειστούς κύκλους",stats.cycles.sumOf { it.orderFaults }))
        appendLine("%-37s %6s".format(Locale.US,"Αγνοημένα packets πριν πρώτο NORTH",stats.ignoredLeading))
        appendLine("%-37s %6s".format(Locale.US,"Invalid CSV rows",capture.summary.invalidRows))
        appendLine("%-37s %6s".format(Locale.US,"USB CRC FAIL delta",capture.summary.crcFailuresDuringCsv ?: 0L))
        appendLine("%-37s %6s".format(Locale.US,"USB Framing FAIL delta",capture.summary.framingFailuresDuringCsv ?: 0L))
        appendLine()
        appendLine("MISSING SECTORS (00..1F)")
        for (i in 0..31) appendLine("%02X : %d".format(Locale.US,i,stats.missingCounts[i]))
        appendLine()
        appendLine("NORTH CYCLES WITH ERRORS")
        for ((i,cycle) in stats.cycles.withIndex()) if (cycle.invalid) {
            appendLine("#${i+1} frame=${cycle.northFrame} missing=${cycle.missing.joinToString { "%02X".format(Locale.US,it) }.ifEmpty { "—" }} " +
                "duplicates=${cycle.duplicateCount} order=${cycle.orderFaults} CRC-suspect=${cycle.crcSuspect}")
        }
        if (stats.cycles.none { it.invalid }) appendLine("Δεν βρέθηκαν invalid cycles.")
        appendLine()
        appendLine("Σημείωση: Unmapped/malformed events δεν ταξινομούνται πλήρως στην Android v2.")
    }

    fun alerts(capture: RadarCapture, stats: CaptureDiagnostics): String = buildString {
        appendLine("AIRCAT500 — ALERTS / DESKTOP SUMMARY")
        appendLine("Αρχείο: ${capture.name}")
        appendLine("──────────────────────────────────────────")
        appendLine("ALERT                         PSR    MSSR   OTHER   TOTAL")
        val trackSource = HashMap<Pair<Long,Int>, String>()
        for (packet in capture.packets) for (track in packet.tracks)
            trackSource[packet.frameNo to track.number] = source(track)
        for ((category, events) in stats.alerts.groupBy { it.category }.toList().sortedByDescending { it.second.size }) {
            val src = events.groupingBy { trackSource[it.frame to (it.track ?: -1)] ?: "OTHER" }.eachCount()
            appendLine("%-27s %5d %6d %7d %7d".format(Locale.US,category.take(27),
                src["PSR"] ?: 0, src["MSSR"] ?: 0, src["OTHER"] ?: 0, events.size))
        }
        if (stats.alerts.isEmpty()) appendLine("Δεν βρέθηκαν AIRCAT alerts.")
        appendLine()
        appendLine("MODE A / MODE C VALIDATION")
        val tracks = capture.packets.flatMap { it.tracks }.filterNot { it.cancellation }
        for (src in listOf("PSR","MSSR")) {
            val bySource = tracks.filter { source(it) == src }
            appendLine("$src: Mode A invalid=${bySource.count { it.modeAInvalid }} / ${bySource.count { it.modeA != null }} transmitted; " +
                "Mode C invalid=${bySource.count { it.modeCInvalid }} / ${bySource.count { it.modeC != null }} transmitted")
        }
        appendLine()
        appendLine("ALERT EVENTS")
        append(AircatReport.alerts(stats))
    }

    fun report(capture: RadarCapture, stats: CaptureDiagnostics): String = buildString {
        appendLine("AIRCAT500 Mobile v2.0 — Αναλυτική Ανάλυση Καταγραφής")
        appendLine("Desktop v9.16-oriented report (available mobile decoder fields)")
        appendLine("══════════════════════════════════════════════════════════")
        appendLine(AircatReport.create(capture,stats))
        appendLine()
        appendLine(errors(capture,stats))
        appendLine()
        appendLine(alerts(capture,stats))
        appendLine()
        appendLine("TRACKS PER SOURCE / TPR")
        val tracks = capture.packets.flatMap { it.tracks }.filterNot { it.cancellation }
        for (src in listOf("PSR","MSSR")) {
            val hits = tracks.filter { source(it) == src }
            appendLine("$src TPR1=${hits.count { it.tpr == 1 }} TPR2=${hits.count { it.tpr == 2 }} Total=${hits.size}")
        }
        appendLine()
        appendLine("SECTOR BAR CHART — CORRECT / MISSING / WITHOUT PREV NORTH")
        val chart = sectorBars(capture,stats)
        for (i in chart.labels.indices) {
            appendLine("%-10s %5d %5d %5d".format(Locale.US,chart.labels[i],
                chart.correct[i],chart.missing[i],chart.withoutNorth[i]))
        }
    }
}
