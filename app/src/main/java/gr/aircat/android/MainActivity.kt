package gr.aircat.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.InputStreamReader
import java.io.FilterInputStream
import java.io.InputStream
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
    private var lastFullReport: String? = null
    private var lastErrorSummary: String? = null
    private var lastAlertSummary: String? = null
    private var maxRangeNm = 250f
    private var aircraftLabelSp = 9f
    private var uiTextSp = 13f
    private val scalableText = ArrayList<java.lang.ref.WeakReference<TextView>>()
    private var pauseOnErrors = false
    private var errorNorthTimes: Set<Double> = emptySet()

    private lateinit var info: TextView
    private lateinit var monitor: TextView
    private lateinit var errors: TextView
    private lateinit var alerts: TextView
    private lateinit var radar: RadarView
    private lateinit var chart: ChartView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var chartToolbar: LinearLayout
    private lateinit var monitorToolbar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var rate: Button
    private lateinit var reverseButton: Button
    private lateinit var forwardButton: Button
    private lateinit var timelineLabel: TextView
    private lateinit var chartModeButton: Button
    private lateinit var showViews: Array<View>
    private lateinit var tabButtons: Array<Button>

    private val fg = Color.rgb(19, 57, 81)
    private val bg = Color.rgb(224, 243, 253)
    private val buttonBlue = Color.rgb(189, 230, 250)
    private fun dp(x: Int) = (x * resources.displayMetrics.density + .5f).toInt()
    private fun title(text: String, size: Float = 12f) = TextView(this).apply {
        this.text = text
        textSize = uiTextSp
        scalableText.add(java.lang.ref.WeakReference(this))
        setTextColor(fg)
        setPadding(dp(7), dp(4), dp(7), dp(4))
        maxLines = 3
    }
    private fun button(text: String, size: Float = 11f, onTap: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = uiTextSp
        scalableText.add(java.lang.ref.WeakReference(this))
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(fg)
        backgroundTintList = ColorStateList.valueOf(buttonBlue)
        setPadding(0, 0, 0, 0)
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
        window.statusBarColor = Color.rgb(65, 143, 184)
        window.navigationBarColor = Color.rgb(65, 143, 184)
        val prefs = getSharedPreferences("aircat_v2_settings", MODE_PRIVATE)
        maxRangeNm = prefs.getFloat("max_range_nm", 250f)
        aircraftLabelSp = prefs.getFloat("label_size_sp", 9f).coerceIn(7f, 16f)
        uiTextSp = prefs.getFloat("all_text_size_sp", 13f).coerceIn(13f,16f)
        pauseOnErrors = prefs.getBoolean("pause_on_north_errors", false)
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

        root.addView(title("AIRCAT500 Mobile  v2.1", 13f))
        val open = button("Άνοιγμα", 11f) { chooseFile() }
        val filter = button("Φίλτρα", 11f) { chooseFilters() }
        val export = button("Report", 10f) { showReport() }
        val settings = button("Ρυθμίσεις", 9f) { showSettings() }
        root.addView(row(open, filter, export, settings, height = 41))
        info = title("Άνοιξε αρχείο CSV, BIN, PCAP ή PCAPNG για Offline Replay.", 12f)
        root.addView(info)
        progressText = title("", 11f).apply { visibility = View.GONE }
        root.addView(progressText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(29, 121, 175))
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(9)))

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
            maxRangeNm = this@MainActivity.maxRangeNm
            labelSizeSp = aircraftLabelSp
            uiFontSp = uiTextSp
            onTrackTap = { track -> showTrack(track) }
        }
        monitor = title("Δεν έχει φορτωθεί αρχείο.")
        errors = title("Δεν υπάρχουν στοιχεία σφαλμάτων.")
        alerts = title("Δεν υπάρχουν στοιχεία alerts.")
        chart = ChartView(this).apply { uiFontSp = uiTextSp }
        val views: Array<View> = arrayOf(radar, scrollText(monitor), scrollText(errors), scrollText(alerts), chart)
        showViews = views
        views.forEach { body.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        chartModeButton = button("PY 35 BARS  ▾", 10f) {
            chartMode = (chartMode + 1) % 5
            chart.chartMode = chartMode
            chartModeButton.text = listOf("PY 35 BARS ▾","SECTOR PACKETS ▾",
                "MISSING SECTORS ▾","AIRCAT TYPES ▾","ALERTS ▾")[chartMode]
        }
        chartToolbar = row(chartModeButton,
            button("Zoom Peak",10f) { chart.peakZoom() },
            button("Zoom Out",10f) { chart.resetZoom() }, height = 38)
        root.addView(chartToolbar)
        monitorToolbar = row(button("Current Scan RAW") { showCurrentScanRaw() }, height = 36)
        root.addView(monitorToolbar)
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
            button("⏮ Reset") { jumpTo(0) }, height = 43))
        root.addView(row(
            button("◀ −1 Frame") { jumpTo((record-2).coerceAtLeast(0)) },
            button("+1 Frame ▶") { jumpTo(record) }, height = 36))
        showTab(0)
        ui.post(ticker)
    }

    private fun showTab(index: Int) {
        tab = index
        if (!::showViews.isInitialized) return
        for ((i, view) in showViews.withIndex()) view.visibility = if (i == index) View.VISIBLE else View.GONE
        for ((i, btn) in tabButtons.withIndex()) btn.alpha = if (i == index) 1f else .69f
        chartToolbar.visibility = if (index == 4) View.VISIBLE else View.GONE
        monitorToolbar.visibility = if (index == 1) View.VISIBLE else View.GONE
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
        setLoadProgress(0,"Έναρξη φόρτωσης...")
        worker.execute {
            try {
                val rawName = android.provider.OpenableColumns.DISPLAY_NAME
                var filename = uri.lastPathSegment?.substringAfterLast('/') ?: "capture.dat"
                var totalBytes = -1L
                contentResolver.query(uri, arrayOf(rawName, android.provider.OpenableColumns.SIZE),
                    null, null, null)?.use {
                    if (it.moveToFirst()) {
                        filename = it.getString(0) ?: filename
                        if (!it.isNull(1)) totalBytes = it.getLong(1)
                    }
                }
                val expectedBytes = totalBytes
                fun loadInput(): InputStream {
                    val stream = contentResolver.openInputStream(uri)
                        ?: error("Δεν μπορεί να ανοιχθεί το αρχείο")
                    var seen = 0L
                    var lastPct = -1
                    return object : FilterInputStream(stream) {
                        private fun counted(n: Int) {
                            if (n <= 0 || expectedBytes <= 0) return
                            seen += n.toLong()
                            val pct = (75L * seen / expectedBytes).toInt().coerceIn(0,75)
                            if (pct != lastPct) {
                                lastPct=pct
                                setLoadProgress(pct,"Ανάγνωση AIRCAT500 αρχείου")
                            }
                        }
                        override fun read(): Int {
                            val value=super.read()
                            if (value>=0) counted(1)
                            return value
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n=super.read(b,off,len)
                            counted(n)
                            return n
                        }
                    }
                }
                val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val loaded: RadarCapture
                val sourceNote: String
                when (ext) {
                    "bin" -> {
                        val raw = loadInput().use { it.readBytes() }
                            ?: error("Δεν μπορεί να διαβαστεί το BIN")
                        require(raw.size <= 60 * 1024 * 1024) { "BIN υπερβαίνει το όριο 60 MB" }
                        val result = AircatBsc.import(raw, filename)
                        loaded = result.capture
                        sourceNote = "BSC CRC: ${result.status.valid} OK / ${result.status.crcFailed} FAIL"
                    }
                    "pcap", "pcapng" -> {
                        val raw = loadInput().use { it.readBytes() }
                            ?: error("Δεν μπορεί να διαβαστεί το PCAP")
                        require(raw.size <= 60 * 1024 * 1024) { "PCAP υπερβαίνει το όριο 60 MB" }
                        val result = AircatPcap.import(raw, filename)
                        loaded = result.capture
                        sourceNote = "UDP: ${result.udpPackets} / ${result.inspectedPackets} frames"
                    }
                    else -> {
                        loaded = loadInput().bufferedReader(Charsets.UTF_8).use {
                            AircatCsv.read(it, filename)
                        } ?: error("Το CSV δεν είναι διαθέσιμο")
                        sourceNote = "CSV CRC-valid payloads"
                    }
                }
                setLoadProgress(78,"Ανάλυση NORTH / sectors / CRC...")
                val analysis = AircatAnalysis.analyze(loaded)
                setLoadProgress(89,"Σύνοψη Errors / Alerts και Charts...")
                val bars = AircatDesktopSummary.sectorBars(loaded,analysis)
                val median = analysis.medianPeriod
                val northPackets = loaded.packets.filter { it.kind == 0x19 }
                val missedNorthTimes = if (median != null && median > 0)
                    northPackets.zipWithNext().filter { (a,b) ->
                        b.epochSeconds-a.epochSeconds > median*1.9
                    }.map { it.second.epochSeconds }
                else emptyList()
                val pauseNorthTimes = (analysis.cycles.filter { it.invalid }.map { it.nextTime } +
                    missedNorthTimes).toSet()
                val errorSummary = AircatDesktopSummary.errors(loaded,analysis)
                val alertSummary = AircatDesktopSummary.alerts(loaded,analysis)
                setLoadProgress(95,"Δημιουργία αναλυτικού Report...")
                val full = AircatDesktopSummary.report(loaded,analysis)
                setLoadProgress(100,"Ολοκληρώθηκε: 100%")
                runOnUiThread {
                    capture = loaded
                    diagnostics = analysis
                    sectorSeconds = ((analysis.medianPeriod ?: 3.956) / 32.0).coerceIn(.02, 5.0)
                    seek.max = ReplayTimeline.STEPS
                    northIndexes = loaded.packets.indices.filter { loaded.packets[it].kind == 0x19 }.toIntArray()
                    replayDirection = 1
                    chart.diagnostics = analysis
                    chart.desktopBars = bars
                    chart.chartMode = 0
                    chartMode = 0
                    chartModeButton.text = "PY 35 BARS ▾"
                    lastFullReport = full
                    errorNorthTimes = pauseNorthTimes
                    lastErrorSummary = errorSummary
                    lastAlertSummary = alertSummary
                    refreshAnalysisPages()
                    showTab(0)
                    jumpTo(0)
                    start() // Keep v0.2's successful autoplay.
                    showReport() // Python-style full analysis is available immediately.
                    ui.postDelayed({ hideLoadProgress() }, 1400)
                    android.widget.Toast.makeText(this, "$sourceNote • ${loaded.summary.packets} frames", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    hideLoadProgress()
                    info.text = "Σφάλμα εισαγωγής: ${ex.message ?: "μη έγκυρο αρχείο"}"
                }
            }
        }
    }

    private fun setLoadProgress(pct: Int, stage: String) {
        runOnUiThread {
            if (!::progress.isInitialized) return@runOnUiThread
            progress.visibility = View.VISIBLE
            progressText.visibility = View.VISIBLE
            progress.progress = pct.coerceIn(0,100)
            progressText.text = "${pct.coerceIn(0,100)}%  •  $stage"
            // Do not repeat the progress percentage in the info line.
        }
    }
    private fun hideLoadProgress() {
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
    }
    private fun applyUiFontSize() {
        for (label in scalableText) label.get()?.textSize = uiTextSp
        scalableText.removeAll { it.get() == null }
        if (::radar.isInitialized) radar.uiFontSp = uiTextSp
        if (::chart.isInitialized) chart.uiFontSp = uiTextSp
        // Android dialogs are populated using uiTextSp when opened.
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
                            if (pauseOnErrors && packet.kind == 0x19 &&
                                packet.epochSeconds in errorNorthTimes) {
                                pause()
                                break
                            }
                        }
                        if (updates > 0) refreshFrame()
                        if (!playing && pauseOnErrors) info.text =
                            "Replay pause: NORTH / sector error στο τρέχον frame"
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
        errors.text = lastErrorSummary ?: AircatDesktopSummary.errors(data, stats)
        alerts.text = lastAlertSummary ?: AircatDesktopSummary.alerts(data, stats)
        chart.diagnostics = stats
    }

    private fun chooseFilters() {
        val previous = currentFilter
        val labels = listOf("PSR (Primary)", "MSSR / SSR", "Low quality",
            "Cancelled tracks", "Ετικέτες στόχων", "Ίχνη τροχιάς",
            "Μόνο alerts", "Κρύψε invalid Mode A/C", "Range Label (NM)",
            "Mode A Label", "Mode C Label", "Track Number Label",
            "Speed Label (kt)", "Quality Q Label", "Detection Misses Label",
            "TPR Number Label", "LogSigma (SSR) Label", "Time Since North Label",
            "Tracking Window Label", "Smoothing K Label", "Velocity Vector")
        val initial = listOf(previous.showPSR, previous.showMSSR, previous.showLowQuality,
            previous.showCancelled, previous.showLabels, previous.showTrails,
            previous.showOnlyAlerts, previous.hideInvalidMode, previous.showRangeLabel,
            previous.showModeALabel, previous.showModeCLabel, previous.showTrackNumberLabel,
            previous.showSpeedLabel, previous.showQualityLabel, previous.showMissesLabel,
            previous.showTprLabel, previous.showLogSigmaLabel, previous.showNorthTimeLabel,
            previous.showTrackingWindowLabel, previous.showSmoothingLabel, previous.showVelocityVector)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val checks = labels.indices.map { i ->
            CheckBox(this).apply {
                text = labels[i]
                isChecked = initial[i]
                textSize = uiTextSp
                content.addView(this, LinearLayout.LayoutParams(-1, dp(42)))
            }
        }
        val historyText = title("Ίχνη ιστορικού ανά στόχο: ${replay.trailDepth}", 12f)
        content.addView(historyText)
        val history = SeekBar(this).apply {
            max = 64
            if (Build.VERSION.SDK_INT >= 26) min = 2
            progress = replay.trailDepth
            isEnabled = checks[5].isChecked
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    historyText.text = "Ίχνη ιστορικού ανά στόχο: ${value.coerceAtLeast(2)}"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        checks[5].setOnCheckedChangeListener { _, enabled -> history.isEnabled = enabled }
        content.addView(history)
        content.addView(title("Φίλτρα Mode C (feet) και ταχύτητας (kt)", 12f))
        fun numberInput(hint: String, existing: Int?): EditText {
            return EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                textSize = uiTextSp
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setText(existing?.toString() ?: "")
                content.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        val altMin = numberInput("Ελάχιστο ύψος ft (κενό = χωρίς όριο)", previous.minAltitudeFeet)
        val altMax = numberInput("Μέγιστο ύψος ft (κενό = χωρίς όριο)", previous.maxAltitudeFeet)
        val speedMin = numberInput("Ελάχιστη ταχύτητα kt (κενό = χωρίς όριο)", previous.minSpeedKt)
        val speedMax = numberInput("Μέγιστη ταχύτητα kt (κενό = χωρίς όριο)", previous.maxSpeedKt)
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Radar Filters")
            .setView(scroll)
            .setPositiveButton("Εφαρμογή") { _, _ ->
                fun readInt(input: EditText) = input.text.toString().trim().toIntOrNull()
                val minAlt = readInt(altMin)
                val maxAlt = readInt(altMax)
                val minSpd = readInt(speedMin)
                val maxSpd = readInt(speedMax)
                if ((minAlt != null && maxAlt != null && minAlt > maxAlt) ||
                    (minSpd != null && maxSpd != null && minSpd > maxSpd)) {
                    android.widget.Toast.makeText(this, "Ελάχιστο όριο μεγαλύτερο από μέγιστο", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    currentFilter = RadarFilter(
                        checks[0].isChecked, checks[1].isChecked, checks[2].isChecked,
                        checks[3].isChecked, checks[4].isChecked, checks[5].isChecked,
                        checks[6].isChecked, checks[7].isChecked,
                        minAlt, maxAlt, minSpd, maxSpd, checks[8].isChecked,
                        checks[9].isChecked, checks[10].isChecked, checks[11].isChecked,
                        checks[12].isChecked, checks[13].isChecked, checks[14].isChecked,
                        checks[15].isChecked, checks[16].isChecked, checks[17].isChecked,
                        checks[18].isChecked, checks[19].isChecked, checks[20].isChecked
                    )
                    radar.filter = currentFilter
                    val newDepth = history.progress.coerceIn(2, 64)
                    if (newDepth != replay.trailDepth) {
                        replay.trailDepth = newDepth
                        if (capture != null && record > 0) {
                            val wasPlaying = playing
                            jumpTo(record-1, stop = !wasPlaying)
                            if (wasPlaying) resetClock()
                        }
                    }
                }
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    private fun showTrack(track: RadarTrack) {
        val details = buildString {
            appendLine("Track #${track.number} • ${track.kindLabel}")
            appendLine("X/Y: ${track.xRaw} / ${track.yRaw}")
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

    private fun showCurrentScanRaw() {
        val data = capture ?: return
        if (data.packets.isEmpty()) return
        val last = (record-1).coerceIn(0,data.packets.lastIndex)
        var first = last
        while (first > 0 && data.packets[first].kind != 0x19) first--
        val summary = buildString {
            appendLine("CURRENT SCAN RAW — NORTH / SECTORS")
            appendLine("Αρχείο: ${data.name}")
            appendLine("Packets #${first+1}..${last+1}")
            for (i in first..last) {
                val p=data.packets[i]
                appendLine()
                appendLine("Frame ${p.frameNo}, ${p.epochSeconds}s, TYPE 0x${"%02X".format(p.kind)} SECTOR ${p.sector?.let{"%02X".format(it)} ?: "--"}")
                appendLine(p.payload.joinToString(" ") { "%02X".format(it.toInt() and 255) })
            }
        }
        val text=TextView(this).apply {
            setText(summary); textSize=uiTextSp; setTextColor(fg); setTextIsSelectable(true)
            setPadding(dp(13),dp(12),dp(13),dp(12))
        }
        AlertDialog.Builder(this).setTitle("Current Scan RAW")
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Κλείσιμο",null)
            .setNeutralButton("Share") { _,_ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type="text/plain"
                    putExtra(Intent.EXTRA_TEXT,summary)
                }
                startActivity(Intent.createChooser(send,"Κοινοποίηση Scan RAW"))
            }.show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(10), dp(19), dp(12))
        }
        val titleRange = title("Μέγιστη κλίμακα RADAR (NM) • αρχική 20 NM", 12f)
        layout.addView(titleRange)
        val range = EditText(this).apply {
            textSize = uiTextSp
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(maxRangeNm.toInt().toString())
            hint = "250"
        }
        layout.addView(range, LinearLayout.LayoutParams(-1, dp(49)))
        val currentSize = aircraftLabelSp.toInt()
        val sizeText = title("Μέγεθος γραμμάτων στόχων: ${currentSize} sp", 12f)
        layout.addView(sizeText)
        val sizes = SeekBar(this).apply {
            max = 16
            if (Build.VERSION.SDK_INT >= 26) min = 7
            progress = currentSize.coerceIn(7,16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, size: Int, fromUser: Boolean) {
                    sizeText.text = "Μέγεθος γραμμάτων στόχων: ${size.coerceIn(7,16)} sp"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        layout.addView(sizes)
        val allSizeText = title("Μέγεθος μενού / report / συνόψεων: ${uiTextSp.toInt()} sp", 13f)
        layout.addView(allSizeText)
        val allSizes = SeekBar(this).apply {
            max = 16
            if (Build.VERSION.SDK_INT >= 26) min = 13
            progress = uiTextSp.toInt().coerceIn(13,16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, size: Int, fromUser: Boolean) {
                    allSizeText.text = "Μέγεθος μενού / report / συνόψεων: ${size.coerceIn(13,16)} sp"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        layout.addView(allSizes)
        val pauseError = CheckBox(this).apply {
            text = "Pause Replay σε NORTH / sector missing"
            textSize = uiTextSp
            isChecked = pauseOnErrors
        }
        layout.addView(pauseError)
        layout.addView(title("Ανοιχτό γκρι RADAR • μπλε δακτύλιοι • αρχική κλίμακα 20 NM",11f))
        AlertDialog.Builder(this)
            .setTitle("Ρυθμίσεις AIRCAT500")
            .setView(layout)
            .setPositiveButton("Εφαρμογή") { _, _ ->
                val rangeNm = (range.text.toString().toFloatOrNull() ?: 250f).coerceIn(20f,1000f)
                maxRangeNm = rangeNm
                aircraftLabelSp = sizes.progress.toFloat().coerceIn(7f,16f)
                uiTextSp = allSizes.progress.toFloat().coerceIn(13f,16f)
                pauseOnErrors = pauseError.isChecked
                radar.maxRangeNm = rangeNm
                radar.labelSizeSp = aircraftLabelSp
                applyUiFontSize()
                getSharedPreferences("aircat_v2_settings",MODE_PRIVATE).edit()
                    .putFloat("max_range_nm",maxRangeNm)
                    .putFloat("label_size_sp",aircraftLabelSp)
                    .putFloat("all_text_size_sp",uiTextSp)
                    .putBoolean("pause_on_north_errors",pauseOnErrors).apply()
            }
            .setNeutralButton("About") { _, _ -> showAbout() }
            .setNegativeButton("Άκυρο",null)
            .show()
    }

    private fun showAbout() {
        val version = packageManager.getPackageInfo(packageName,0).versionName
        AlertDialog.Builder(this).setTitle("About AIRCAT500 Mobile")
            .setMessage("AIRCAT500 Mobile v$version\n" +
                "Android offline radar analysis & replay\n" +
                "Decoder: AIRCAT500 BSC / CSV / BIN / PCAP / PCAPNG\n" +
                "Radar: 20 NM initial, configurable max 250 NM\n" +
                "Desktop reference: Python Analyzer v9.16\n" +
                "SYNC2USB live Wi-Fi/USB: not included in this build.\n" +
                "Version v2.1 preserves the original decoder and improves scan rendering and labels.")
            .setPositiveButton("OK",null).show()
    }

    private fun showReport() {
        val report = lastFullReport ?: run {
            info.text = "Άνοιξε πρώτα αρχείο καταγραφής για πλήρη ανάλυση."
            return
        }
        val text = TextView(this).apply {
            setText(report)
            textSize=uiTextSp
            setTextColor(fg)
            setTextIsSelectable(true)
            setPadding(dp(14),dp(14),dp(14),dp(14))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(249,252,254))
            addView(text)
        }
        AlertDialog.Builder(this).setTitle("AIRCAT500 v2 — Αναλυτικό Report")
            .setView(scroll)
            .setPositiveButton("Κλείσιμο",null)
            .setNeutralButton("Αποθήκευση") { _,_ -> exportReport() }
            .setNegativeButton("Share / Viber") { _,_ -> shareReport() }
            .show()
    }

    private fun shareReport() {
        val report = lastFullReport ?: return
        // Android's sharesheet includes Viber when installed and registered.
        // Do not hard-code a package: it would fail on devices without Viber.
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT,report)
            putExtra(Intent.EXTRA_SUBJECT,"AIRCAT500 Mobile v2 report")
        }
        startActivity(Intent.createChooser(send,"Κοινοποίηση μέσω Viber ή άλλης εφαρμογής"))
    }

    private fun exportReport() {
        val data = capture ?: run { info.text = "Άνοιξε πρώτα μια καταγραφή."; return }
        pendingReport = lastFullReport ?: diagnostics?.let { AircatDesktopSummary.report(data,it) }
            ?: return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "AIRCAT500_v2_Analytical_Report.txt")
        }
        startActivityForResult(intent, 78)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        worker.shutdownNow()
        super.onDestroy()
    }
}
