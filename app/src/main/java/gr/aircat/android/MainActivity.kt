package gr.aircat.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors

/** AIRCAT500 Mobile v1.0 — Android port of the desktop v9.16 analysis/replay features. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val replay = RadarReplayState()
    private var capture: RadarCapture? = null
    private var diagnostics: CaptureDiagnostics? = null
    private var record = 0
    private var playing = false
    private var speed = 1.0
    private var replayDirection = 1 // +1 forward, -1 backward
    private var northIndexes = IntArray(0)
    private var startTime = 0.0
    private var clockStart = 0L
    private var sectorEpoch = 0.0
    private var sectorSeconds = 0.123625 // Default 3.956 s per scan / 32; updated from NORTH median
    private var changingSeek = false
    private var tab = 0
    private var chartMode = 0
    private var currentFilter = RadarFilter()
    private var pendingReport: String? = null

    private lateinit var info: TextView
    private lateinit var monitor: TextView
    private lateinit var errors: TextView
    private lateinit var alerts: TextView
    private lateinit var radar: RadarView
    private lateinit var chart: ChartView
    private lateinit var progress: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var rate: Button
    private lateinit var reverseButton: Button
    private lateinit var forwardButton: Button
    private lateinit var timelineLabel: TextView
    private lateinit var chartModeButton: Button
    private lateinit var showViews: Array<View>
    private lateinit var tabButtons: Array<Button>

    private val fg = Color.rgb(16, 20, 22)
    private val bg = Color.rgb(235, 237, 239)
    private fun dp(x: Int) = (x * resources.displayMetrics.density + .5f).toInt()
    private fun title(text: String, size: Float = 12f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        maxLines = 3
    }
    private fun button(text: String, size: Float = 11f, onTap: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = size
        isAllCaps = false
        setTextColor(Color.BLACK)
        setPadding(dp(1), dp(1), dp(1), dp(1))
        setOnClickListener { onTap() }
    }
    private fun row(vararg views: View, height: Int = 43) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (view in views) addView(view, LinearLayout.LayoutParams(0, dp(height), 1f))
    }
    private fun scrollText(output: TextView): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Color.rgb(248, 249, 250))
        addView(output)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(49, 56, 60)
        window.navigationBarColor = Color.rgb(49, 56, 60)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        // Android 15 / target 35: keep buttons clear of status & navigation bars.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()) else null
            val left = bars?.left ?: insets.systemWindowInsetLeft
            val top = bars?.top ?: insets.systemWindowInsetTop
            val right = bars?.right ?: insets.systemWindowInsetRight
            val bottom = bars?.bottom ?: insets.systemWindowInsetBottom
            root.setPadding(dp(5) + left, dp(5) + top, dp(5) + right, dp(5) + bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()

        root.addView(title("AIRCAT500 Mobile  v1.0.2", 17f))
        val open = button("Άνοιγμα", 11f) { chooseFile() }
        val filter = button("Φίλτρα", 11f) { chooseFilters() }
        val export = button("Report", 11f) { exportReport() }
        root.addView(row(open, filter, export))
        info = title("Άνοιξε αρχείο CSV, BIN, PCAP ή PCAPNG για Offline Replay.", 12f)
        root.addView(info)
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))

        tabButtons = arrayOf(
            button("Radar", 10f) { showTab(0) },
            button("Monitor", 10f) { showTab(1) },
            button("Errors", 10f) { showTab(2) },
            button("Alerts", 10f) { showTab(3) },
            button("Charts", 10f) { showTab(4) }
        )
        root.addView(row(*tabButtons, height = 42))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        radar = RadarView(this).apply {
            state = replay
            this.filter = currentFilter
            onTrackTap = { track -> showTrack(track) }
        }
        monitor = title("Δεν έχει φορτωθεί αρχείο.")
        errors = title("Δεν υπάρχουν στοιχεία σφαλμάτων.")
        alerts = title("Δεν υπάρχουν στοιχεία alerts.")
        chart = ChartView(this)
        val views: Array<View> = arrayOf(radar, scrollText(monitor), scrollText(errors), scrollText(alerts), chart)
        showViews = views
        views.forEach { body.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        chartModeButton = button("SECTOR PACKETS  ▾", 11f) {
            chartMode = (chartMode + 1) % 4
            chart.chartMode = chartMode
            chartModeButton.text = listOf("SECTOR PACKETS  ▾", "MISSING SECTORS  ▾", "AIRCAT TYPES  ▾", "ALERTS  ▾")[chartMode]
        }
        root.addView(chartModeButton, LinearLayout.LayoutParams(-1, dp(35)))
        timelineLabel = title("Χρόνος Replay  00:00 / 00:00", 11f)
        root.addView(timelineLabel)
        seek = SeekBar(this).apply {
            max = ReplayTimeline.STEPS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                    if (fromUser) {
                        changingSeek = true
                        val packets = capture?.packets ?: return
                        val epoch = ReplayTimeline.epoch(position, packets.first().epochSeconds, packets.last().epochSeconds)
                        updateTimelineLabel(epoch)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    changingSeek = true
                    pause() // Freeze displayed targets while moving the time cursor.
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val packets = capture?.packets ?: return
                    val epoch = ReplayTimeline.epoch(seek.progress, packets.first().epochSeconds, packets.last().epochSeconds)
                    changingSeek = false
                    jumpTo(ReplayTimeline.indexAtOrBefore(packets, epoch))
                }
            })
        }
        root.addView(seek)
        reverseButton = button("<<", 11f) { replayDirection = -1; start() }
        play = button("▶ Play") { if (playing) pause() else start() }
        forwardButton = button(">>", 11f) { replayDirection = 1; start() }
        rate = button("1×") {
            speed = when (speed) {
                1.0 -> 2.0
                2.0 -> 4.0
                4.0 -> 8.0
                8.0 -> 16.0
                else -> 1.0
            }
            rate.text = "${speed.toInt()}×"
            if (playing) resetClock()
        }
        root.addView(row(reverseButton, play, forwardButton, rate,
            button("+1") { jumpTo(record) }, height = 45))
        showTab(0)
        ui.post(ticker)
    }

    private fun showTab(index: Int) {
        tab = index
        if (!::showViews.isInitialized) return
        for ((i, view) in showViews.withIndex()) view.visibility = if (i == index) View.VISIBLE else View.GONE
        for ((i, btn) in tabButtons.withIndex()) btn.alpha = if (i == index) 1f else .69f
        chartModeButton.visibility = if (index == 4) View.VISIBLE else View.GONE
        if (index == 1 || index == 2 || index == 3) refreshAnalysisPages()
    }

    private fun chooseFile() {
        // CSV MIME types differ between Android devices and file providers.
        // A provider-side EXTRA_MIME_TYPES whitelist can disable valid CSVs.
        // Open all documents and dispatch by the selected file extension
        // (CSV / BIN / PCAP / PCAPNG) in the existing onActivityResult handler.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 77)
    }

    @Deprecated("Document picker compatibility on Android API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 78) {
            val text = pendingReport ?: return
            worker.execute {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Το αρχείο δεν ανοίγει για εγγραφή")
                    runOnUiThread { info.text = "Το report αποθηκεύτηκε." }
                } catch (ex: Exception) {
                    runOnUiThread { info.text = "Σφάλμα αποθήκευσης: ${ex.message}" }
                }
            }
            return
        }
        if (requestCode != 77) return
        pause()
        progress.visibility = View.VISIBLE
        info.text = "Ανάγνωση AIRCAT500 αρχείου..."
        worker.execute {
            try {
                val rawName = android.provider.OpenableColumns.DISPLAY_NAME
                var filename = uri.lastPathSegment?.substringAfterLast('/') ?: "capture.dat"
                contentResolver.query(uri, arrayOf(rawName), null, null, null)?.use {
                    if (it.moveToFirst()) filename = it.getString(0) ?: filename
                }
                val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val loaded: RadarCapture
                val sourceNote: String
                when (ext) {
                    "bin" -> {
                        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Δεν μπορεί να διαβαστεί το BIN")
                        require(raw.size <= 60 * 1024 * 1024) { "BIN υπερβαίνει το όριο 60 MB" }
                        val result = AircatBsc.import(raw, filename)
                        loaded = result.capture
                        sourceNote = "BSC CRC: ${result.status.valid} OK / ${result.status.crcFailed} FAIL"
                    }
                    "pcap", "pcapng" -> {
                        val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Δεν μπορεί να διαβαστεί το PCAP")
                        require(raw.size <= 60 * 1024 * 1024) { "PCAP υπερβαίνει το όριο 60 MB" }
                        val result = AircatPcap.import(raw, filename)
                        loaded = result.capture
                        sourceNote = "UDP: ${result.udpPackets} / ${result.inspectedPackets} frames"
                    }
                    else -> {
                        loaded = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                            AircatCsv.read(it, filename)
                        } ?: error("Το CSV δεν είναι διαθέσιμο")
                        sourceNote = "CSV CRC-valid payloads"
                    }
                }
                val analysis = AircatAnalysis.analyze(loaded)
                runOnUiThread {
                    capture = loaded
                    diagnostics = analysis
                    sectorSeconds = ((analysis.medianPeriod ?: 3.956) / 32.0).coerceIn(.02, 5.0)
                    seek.max = ReplayTimeline.STEPS
                    northIndexes = loaded.packets.indices.filter { loaded.packets[it].kind == 0x19 }.toIntArray()
                    replayDirection = 1
                    chart.diagnostics = analysis
                    refreshAnalysisPages()
                    progress.visibility = View.GONE
                    showTab(0)
                    jumpTo(0)
                    start() // Keep v0.2's successful autoplay.
                    android.widget.Toast.makeText(this, "$sourceNote • ${loaded.summary.packets} frames", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    info.text = "Σφάλμα εισαγωγής: ${ex.message ?: "μη έγκυρο αρχείο"}"
                }
            }
        }
    }

    private fun pause() {
        playing = false
        play.text = "▶ Play"
        // Deliberately leave radar targets, trails and current sweep untouched.
    }

    private fun resetClock() {
        val packets = capture?.packets ?: return
        if (packets.isEmpty()) return
        val index = if (replayDirection < 0) (record - 1).coerceIn(0, packets.lastIndex)
                    else record.coerceIn(0, packets.lastIndex)
        startTime = packets[index].epochSeconds
        clockStart = SystemClock.elapsedRealtime()
    }

    private fun start() {
        val packets = capture?.packets ?: return
        if (packets.isEmpty()) return
        if (replayDirection < 0 && record <= 1) jumpTo(packets.lastIndex)
        if (replayDirection > 0 && record >= packets.size) jumpTo(0)
        resetClock()
        playing = true
        play.text = "Ⅱ Pause"
        reverseButton.alpha = if (replayDirection < 0) 1f else .65f
        forwardButton.alpha = if (replayDirection > 0) 1f else .65f
    }

    private fun jumpTo(frame: Int, stop: Boolean = true) {
        val packets = capture?.packets ?: return
        if (packets.isEmpty()) return
        if (stop) pause()
        val target = frame.coerceIn(0, packets.lastIndex)
        // A bounded recent-NORTH rebuild preserves up to 16+ track history
        // points without reprocessing an entire multi-hour recording per tick.
        val match = northIndexes.binarySearch(target)
        val lastNorth = if (match >= 0) match else -match - 2
        val firstNorth = (lastNorth - 8).coerceAtLeast(0)
        val fromIndex = if (lastNorth >= 0) northIndexes[firstNorth] else 0
        replay.clear()
        replay.cycle = if (lastNorth >= 0) firstNorth else 0
        sectorEpoch = packets[fromIndex].epochSeconds
        for (i in fromIndex..target) {
            val packet = packets[i]
            replay.apply(packet)
            if (packet.sector != null || packet.kind == 0x19) sectorEpoch = packet.epochSeconds
        }
        record = target + 1
        updateSweep(packets[target].epochSeconds)
        refreshFrame()
    }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                val packets = capture?.packets
                if (playing && packets != null && packets.isNotEmpty()) {
                    val targetEpoch = startTime +
                        (SystemClock.elapsedRealtime() - clockStart) * speed * replayDirection / 1000.0
                    if (replayDirection > 0) {
                        var updates = 0
                        while (record < packets.size && updates < 400 &&
                            packets[record].epochSeconds <= targetEpoch) {
                            val packet = packets[record++]
                            replay.apply(packet)
                            if (packet.sector != null || packet.kind == 0x19) sectorEpoch = packet.epochSeconds
                            updates++
                        }
                        if (updates > 0) refreshFrame()
                        updateSweep(targetEpoch)
                        if (record >= packets.size) pause()
                    } else {
                        val at = ReplayTimeline.indexAtOrBefore(packets, targetEpoch)
                        if (at < record - 1) jumpTo(at, stop = false)
                        updateSweep(targetEpoch)
                        if (targetEpoch <= packets.first().epochSeconds) pause()
                    }
                }
            } finally {
                ui.postDelayed(this, 33)
            }
        }
    }
    private fun updateSweep(epoch: Double) {
        val sec = replay.sector ?: 0
        val elapsed = (epoch - sectorEpoch).coerceIn(0.0, sectorSeconds)
        radar.sweepAzimuthDegrees = ((sec + elapsed / sectorSeconds) * 11.25).toFloat()
    }
    private fun elapsedText(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val sec = total % 60L
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec)
               else "%02d:%02d".format(Locale.US, m, sec)
    }

    private fun updateTimelineLabel(epoch: Double) {
        val packets = capture?.packets ?: return
        if (packets.isEmpty()) return
        val first = packets.first().epochSeconds
        val last = packets.last().epochSeconds
        timelineLabel.text = "Χρόνος Replay  ${elapsedText(epoch - first)} / ${elapsedText(last - first)}" +
            "   ${if (replayDirection < 0) "<<" else ">>"}  ${speed.toInt()}×"
    }

    private fun refreshFrame() {
        val data = capture ?: return
        val packet = data.packets[(record - 1).coerceIn(0, data.packets.lastIndex)]
        if (!changingSeek) {
            seek.progress = ReplayTimeline.position(packet.epochSeconds,
                data.packets.first().epochSeconds, data.packets.last().epochSeconds)
            updateTimelineLabel(packet.epochSeconds)
        }
        info.text = "${data.name} • $record/${data.summary.packets} • NORTH ${replay.cycle} • Sector ${replay.sector?.toString(16)?.uppercase(Locale.US)?.padStart(2, '0') ?: "--"}"
        if (tab == 1) {
            val hex = packet.payload.take(120).joinToString(" ") { "%02X".format(it.toInt() and 255) }
            val head = diagnostics?.let {
                "NORTH ${data.summary.north} • Sector ${data.summary.sectors} • Extension ${data.summary.extension}\n" +
                    "Cycles ${it.completedCycles} • Valid ${it.validCycles} • Missing IDs ${it.missingTotal}\n" +
                    "CRC delta ${data.summary.crcFailuresDuringCsv ?: "n/a"} • Framing delta ${data.summary.framingFailuresDuringCsv ?: "n/a"}"
            } ?: ""
            monitor.text = "$head\n\nFrame #${packet.frameNo}\nTYPE 0x${"%02X".format(packet.kind)}\n$hex"
        }
        radar.invalidate()
    }

    private fun refreshAnalysisPages() {
        val data = capture ?: return
        val stats = diagnostics ?: return
        val head = "${data.name}\n${data.summary.packets} frames • ${stats.completedCycles} complete cycles • ${data.summary.tracksDecoded} track elements"
        monitor.text = "$head\n\n${AircatReport.create(data, stats)}"
        errors.text = AircatReport.errors(stats)
        alerts.text = AircatReport.alerts(stats)
        chart.diagnostics = stats
    }

    private fun chooseFilters() {
        val current = currentFilter
        val items = arrayOf("PSR (Primary)", "MSSR / SSR", "Low quality", "Cancelled tracks",
            "Ετικέτες στόχων", "Ίχνη τροχιάς", "Μόνο alerts", "Κρύψε invalid Mode A/C")
        val values = booleanArrayOf(current.showPSR, current.showMSSR, current.showLowQuality,
            current.showCancelled, current.showLabels, current.showTrails, current.showOnlyAlerts,
            current.hideInvalidMode)
        AlertDialog.Builder(this).setTitle("Radar Filters")
            .setMultiChoiceItems(items, values) { _, index, chosen -> values[index] = chosen }
            .setPositiveButton("Εφαρμογή") { _, _ ->
                currentFilter = RadarFilter(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7])
                radar.filter = currentFilter
            }.setNegativeButton("Άκυρο", null).show()
    }

    private fun showTrack(track: RadarTrack) {
        val details = buildString {
            appendLine("Track #${track.number} • ${track.kindLabel}")
            appendLine("X/Y: ${track.xRaw} / ${track.yRaw}  •  Range: %.1f NM".format(Locale.US,track.rangeNm32))
            appendLine("Mode A: ${track.modeA ?: "n/a"}  •  Mode C: ${track.modeC ?: "n/a"}")
            appendLine("Quality: ${track.quality ?: "n/a"}  •  Speed: ${track.speedKt ?: "n/a"} kt")
            appendLine("SPI: ${track.spi}  Emergency: ${track.emergency}")
            appendLine("Mode A invalid: ${track.modeAInvalid}  Mode C invalid: ${track.modeCInvalid}")
            appendLine("Time from NORTH: ${track.timeSinceNorthSec ?: "n/a"} seconds")
            appendLine("Raw: ${track.rawHex}")
        }
        AlertDialog.Builder(this).setTitle("Στοιχεία στόχου").setMessage(details)
            .setPositiveButton("OK", null).show()
    }

    private fun exportReport() {
        val data = capture ?: run { info.text = "Άνοιξε πρώτα μια καταγραφή."; return }
        val stats = diagnostics ?: return
        pendingReport = AircatReport.create(data,stats) + "\n\n" + AircatReport.errors(stats) + "\n\n" + AircatReport.alerts(stats)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "AIRCAT500_Mobile_v1_report.txt")
        }
        startActivityForResult(intent, 78)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        worker.shutdownNow()
        super.onDestroy()
    }
}
