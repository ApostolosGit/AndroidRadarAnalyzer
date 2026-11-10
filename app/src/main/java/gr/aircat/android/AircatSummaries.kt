package gr.aircat.android

/** Consistent one-line summaries matching desktop Analyzer summary tables. */
data class ErrorSummaryRow(val name: String, val value: String)
data class AlertSummaryRow(val name: String, val psr: Int, val mssr: Int, val other: Int) {
    val total: Int get() = psr + mssr + other
}
object AircatSummaries {
    fun errorRows(stats: CaptureDiagnostics): List<ErrorSummaryRow> {
        val cyclesWithMissing = stats.cycles.count { it.missing.isNotEmpty() }
        val sectorsOutsideNorth = stats.sectorWithoutNorthCounts.sum()
        val outOfOrderFull = stats.cycles.filter { it.missing.isEmpty() }.sumOf { it.orderFaults }
        fun row(name: String, count: Int) = ErrorSummaryRow(name, count.toString())
        return listOf(
            row("Scan missed", stats.timingMissedScans),
            row("North jitter > 5%", stats.northJitterEvents),
            row("North missing valid sectors", cyclesWithMissing),
            row("Invalid North cycles (non-additive)", stats.invalidCycles),
            row("Sector missing συνολικά", stats.missingTotal),
            row("Συνολικά σφάλματα (χωρίς διπλομέτρηση)", stats.distinctFaults + sectorsOutsideNorth),
            row("Sector without prev North συνολικά", sectorsOutsideNorth),
            row("Out-of-order sectors σε πλήρη κύκλο", outOfOrderFull),
            row("Unknown protocol types αθροιστικά", stats.unknownProtocolTypes),
            ErrorSummaryRow("Άγνωστα prefixes packets αθροιστικά", "—"),
            ErrorSummaryRow("Άγνωστα prefixes unique", "—"),
            ErrorSummaryRow("Malformed packets", "—"),
            ErrorSummaryRow("Decoder unmapped elements", "—")
        )
    }

    fun alertRows(stats: CaptureDiagnostics): List<AlertSummaryRow> {
        val normalized = linkedMapOf(
            "Low quality" to "LOW QUALITY", "Track cancellation" to "CANCEL",
            "Maneuver" to "MANEUVER", "SPI" to "SPI",
            "Mode validation" to "VALIDATION", "EMG" to "SSR EMERGENCY",
            "Mode A 7500" to "7500", "Mode A 7600" to "7600",
            "Mode A 7601" to "7601", "Mode A 7700" to "7700"
        )
        val rows = ArrayList<AlertSummaryRow>()
        for ((title, key) in normalized) {
            val matches = stats.alerts.filter { event ->
                if (key.first().isDigit()) event.category == "MODE A" && event.detail.contains(key)
                else event.category == key
            }
            if (matches.isEmpty()) continue
            rows.add(AlertSummaryRow(title,
                matches.count { it.source == "PSR" }, matches.count { it.source == "MSSR" },
                matches.count { it.source != "PSR" && it.source != "MSSR" }))
        }
        return rows
    }
}
