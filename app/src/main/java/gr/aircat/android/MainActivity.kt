package gr.aircat.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
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
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors

/** Aircat500_Analyzer v1.2 — mobile PPI and desktop-equivalent summary views. */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val replay = RadarReplayState()
    private var capture: RadarCapture? = null
    private var checkpoints: ReplayCheckpoints? = null
    private var diagnostics: CaptureDiagnostics? = null
    private var record = 0
    private var playing = false
    private var speed = 1.0
    private var direction = 1 // +1 forward, -1 reverse
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
    private lateinit var errors: SummaryTableView
    private lateinit var alerts: SummaryTableView
    private lateinit var radar: RadarView
    private lateinit var chart: ChartView
    private lateinit var progress: ProgressBar
    private lateinit var seek: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var play: Button
    private lateinit var backwards: Button
    private lateinit var forwards: Button
    private lateinit var rate: Button
    private lateinit var chartModeButton: Button
    private lateinit var peakButton: Button
    private lateinit var chartControls: LinearLayout
    private lateinit var showViews: Array<View>
    private lateinit var tabButtons: Array<Button>

    private val fg = Color.rgb(16, 46, 74)
    private val bg = Color.rgb(217, 231, 242) // aviation blue surrounding PPI
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
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(24, 85, 132))
        setPadding(dp(1), dp(1), dp(1), dp(1))
        setOnClickListener { onTap() }
    }
    private fun row(vararg views: View, height: Int = 43) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (view in views) addView(view, LinearLayout.LayoutParams(0, dp(height), 1f))
    }
    private fun scrollText(output: TextView): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(Color.rgb(241, 246, 251))
        addView(output)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 65, 108)
        window.navigationBarColor = Color.rgb(15, 65, 108)
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

        root.addView(title("Aircat500_Analyzer  •  v1.2", 17f))
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
        errors = SummaryTableView(this).apply { setErrorRows(emptyList()) }
        alerts = SummaryTableView(this).apply { setAlertRows(emptyList()) }
        chart = ChartView(this)
        val chartScroller = HorizontalScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(241, 246, 251))
            addView(chart, android.widget.FrameLayout.LayoutParams(dp(1280), -1))
        }
        val views: Array<View> = arrayOf(radar, scrollText(monitor), errors, alerts, chartScroller)
        showViews = views
        views.forEach { body.addView(it, LinearLayout.LayoutParams(-1, 0, 1f)) }
        chartModeButton = button("SECTOR PACKETS  ▾", 11f) {
            chartMode = (chartMode + 1) % 4
            chart.chartMode = chartMode
            chartModeButton.text = listOf("SECTOR PACKETS  ▾", "MISSING SECTORS  ▾", "AIRCAT TYPES  ▾", "ALERTS  ▾")[chartMode]
        }
        peakButton = button("ZOOM PEAK ✓", 10f) {
            chart.zoomPeak = !chart.zoomPeak
            peakButton.text = if (chart.zoomPeak) "ZOOM PEAK ✓" else "ZOOM OUT"
        }
        chartControls = row(chartModeButton, peakButton, height = 35)
        root.addView(chartControls)
        timeLabel = title("Χρόνος: 00:00 / 00:00  •  Replay διαθέσιμο μετά το άνοιγμα", 11f)
        root.addView(timeLabel, LinearLayout.LayoutParams(-1, dp(24)))
        seek = SeekBar(this).apply {
            max = 10000 // Relative capture time, not the packet number.
            progressTintList = ColorStateList.valueOf(Color.BLACK)
            thumbTintList = ColorStateList.valueOf(Color.BLACK)
            progressBackgroundTintList = ColorStateList.valueOf(Color.LTGRAY)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, position: Int, fromUser: Boolean) {
                    if (fromUser) {
                        changingSeek = true
                        val packets = capture?.packets ?: return
                        val epoch = seekEpoch(position)
                        timeLabel.text = "Μετακίνηση: ${formatElapsed(epoch - packets.first().epochSeconds)} / ${formatElapsed(packets.last().epochSeconds - packets.first().epochSeconds)}"
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { pause(); changingSeek = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val epoch = seekEpoch(seek.progress)
                    changingSeek = false
                    val index = checkpoints?.indexAtOrBefore(epoch) ?: 0
                    jumpTo(index)
                }
            })
        }
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(39)))
        backwards = button("◀◀", 14f) { startInDirection(-1) }
        play = button("▶ Play", 11f) { if (playing) pause() else start() }
        forwards = button("▶▶", 14f) { startInDirection(1) }
        rate = button("1×", 11f) {
            speed = when (speed) { 1.0 -> 2.0; 2.0 -> 4.0; 4.0 -> 8.0; else -> 1.0 }
            rate.text = "${speed.toInt()}×"
            if (playing) resetClock()
        }
        root.addView(row(backwards, play, forwards, rate, height = 43))
        root.addView(row(
            button("◀ 1 Frame", 10f) { jumpTo(displayedIndex() - 1) },
            button("1 Frame ▶", 10f) { jumpTo(displayedIndex() + 1) },
            button("Reset Zoom", 10f) { radar.resetViewport() }, height = 35
        ))
        showTab(0)
        ui.post(ticker)
    }

    private fun showTab(index: Int) {
        tab = index
        if (!::showViews.isInitialized) return
        for ((i, view) in showViews.withIndex()) view.visibility = if (i == index) View.VISIBLE else View.GONE
        for ((i, btn) in tabButtons.withIndex()) btn.alpha = if (i == index) 1f else .69f
        chartControls.visibility = if (index == 4) View.VISIBLE else View.GONE
        if (index == 1 || index == 2 || index == 3) refreshAnalysisPages()
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // File providers report CSV through many MIME types; never hide a valid CSV.
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
                    checkpoints = ReplayCheckpoints(loaded.packets)
                    diagnostics = analysis
                    sectorSeconds = ((analysis.medianPeriod ?: 3.956) / 32.0).coerceIn(.02, 5.0)
                    seek.progress = 0
                    chart.diagnostics = analysis
                    chart.captureName = loaded.name
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

    private fun displayedIndex() = (record - 1).coerceAtLeast(0)

    private fun formatElapsed(seconds: Double): String {
        val whole = seconds.coerceAtLeast(0.0).toLong()
        val h = whole / 3600
        val m = whole / 60 % 60
        val sec = whole % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec)
            else "%02d:%02d".format(Locale.US, m, sec)
    }

    private fun seekEpoch(position: Int): Double {
        val packets = capture?.packets ?: return 0.0
        val from = packets.first().epochSeconds
        val length = (packets.last().epochSeconds - from).coerceAtLeast(0.0)
        return from + length * position.coerceIn(0, 10000) / 10000.0
    }

    private fun pause() {
        playing = false
        if (::play.isInitialized) play.text = "▶ Play"
        // No screen clear here: last observed tracks remain visible until Play.
    }

    private fun resetClock() {
        val packets = capture?.packets ?: return
        startTime = packets[displayedIndex().coerceIn(0, packets.lastIndex)].epochSeconds
        clockStart = SystemClock.elapsedRealtime()
    }

    private fun startInDirection(newDirection: Int) {
        if (capture == null) return
        if (playing) {
            // Turn around from the actual visible time, without jumping.
            direction = newDirection
            resetClock()
            play.text = if (direction < 0) "Ⅱ Pause ◀" else "Ⅱ Pause ▶"
        } else {
            direction = newDirection
            start()
        }
    }

    private fun start() {
        val packets = capture?.packets ?: return
        if (direction > 0 && displayedIndex() >= packets.lastIndex) jumpTo(0)
        if (direction < 0 && displayedIndex() <= 0) jumpTo(packets.lastIndex)
        // Requested persistence: previous presentation is cleared only on Play.
        // Replay subsequently draws real received targets and refreshes sectors.
        replay.clearVisibleTargets()
        resetClock()
        playing = true
        play.text = if (direction < 0) "Ⅱ Pause ◀" else "Ⅱ Pause ▶"
        radar.invalidate()
    }

    /** Random access through sparse source checkpoints; reverse stays responsive. */
    private fun jumpTo(frame: Int, stop: Boolean = true) {
        val packets = capture?.packets ?: return
        if (stop) pause()
        val index = frame.coerceIn(0, packets.lastIndex)
        checkpoints?.restore(replay, index) ?: run {
            replay.clear()
            for (i in 0..index) replay.apply(packets[i])
        }
        record = index + 1
        updateSweep(packets[index].epochSeconds)
        refreshFrame()
    }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                val packets = capture?.packets
                if (playing && packets != null && packets.isNotEmpty()) {
                    val first = packets.first().epochSeconds
                    val last = packets.last().epochSeconds
                    val requested = startTime + direction *
                        (SystemClock.elapsedRealtime() - clockStart) * speed / 1000.0
                    val epoch = requested.coerceIn(first, last)
                    val target = checkpoints?.indexAtOrBefore(epoch) ?: displayedIndex()
                    if (target != displayedIndex()) jumpTo(target, stop = false)
                    updateSweep(epoch)
                    if (requested <= first && direction < 0 || requested >= last && direction > 0) pause()
                }
            } finally { ui.postDelayed(this, 33) }
        }
    }

    private fun updateSweep(epoch: Double) {
        val sec = replay.sector ?: 0
        val elapsed = (epoch - replay.sectorEpoch).coerceIn(0.0, sectorSeconds)
        radar.sweepAzimuthDegrees = ((sec + elapsed / sectorSeconds) * 11.25).toFloat()
    }

    private fun refreshFrame() {
        val data = capture ?: return
        val packets = data.packets
        val packet = packets[displayedIndex().coerceIn(0, packets.lastIndex)]
        if (!changingSeek) {
            val elapsed = packet.epochSeconds - packets.first().epochSeconds
            val duration = (packets.last().epochSeconds - packets.first().epochSeconds).coerceAtLeast(0.0)
            seek.progress = if (duration <= 0.0) 0 else (elapsed / duration * 10000).toInt().coerceIn(0, 10000)
            timeLabel.text = "Χρόνος: ${formatElapsed(elapsed)} / ${formatElapsed(duration)}  •  Frame ${displayedIndex() + 1}/${packets.size}  •  ${if (direction < 0) "◀" else "▶"}"
        }
        info.text = "${data.name} • ${displayedIndex() + 1}/${data.summary.packets} • NORTH ${replay.cycle} • Sector ${replay.sector?.toString(16)?.uppercase(Locale.US)?.padStart(2, '0') ?: "--"}"
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
        errors.setErrorRows(AircatSummaries.errorRows(stats))
        alerts.setAlertRows(AircatSummaries.alertRows(stats))
        chart.diagnostics = stats
    }

    private fun chooseFilters() {
        val current = currentFilter
        val wrapper = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(5), dp(15), dp(12))
        }
        wrapper.addView(content)
        val definitions = listOf(
            "PSR μόνο (○)" to current.showPSR,
            "MSSR (Δ)" to current.showMSSR,
            "Combined (U)" to current.showCombined,
            "Low quality" to current.showLowQuality,
            "Cancelled tracks" to current.showCancelled,
            "Ετικέτες στόχων" to current.showLabels,
            "Track number" to current.showTrackNumber,
            "Mode A (default)" to current.showModeA,
            "Mode C / Altitude (default)" to current.showModeC,
            "Speed label (kt)" to current.showSpeed,
            "Range label (NM)" to current.showRange,
            "Quality label" to current.showQuality,
            "Velocity vectors" to current.showVelocityVectors,
            "Ίχνη τροχιάς" to current.showTrails,
            "Μόνο alerts" to current.showOnlyAlerts,
            "Κρύψε invalid Mode A/C" to current.hideInvalidMode
        )
        val checks = definitions.map { (label, initial) ->
            CheckBox(this).apply {
                text = label
                textSize = 13f
                isChecked = initial
                content.addView(this)
            }
        }
        content.addView(title("Φίλτρα ύψους / ταχύτητας — κενό σημαίνει χωρίς όριο", 13f))
        fun field(label: String, value: Int?): EditText {
            content.addView(title(label, 12f))
            return EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                hint = "—"
                setSingleLine(true)
                setText(value?.toString() ?: "")
                textSize = 14f
                content.addView(this, LinearLayout.LayoutParams(-1, dp(42)))
            }
        }
        val minAlt = field("Ελάχιστο ύψος (ft)", current.minAltitudeFt)
        val maxAlt = field("Μέγιστο ύψος (ft)", current.maxAltitudeFt)
        val minSpeed = field("Ελάχιστη ταχύτητα (kt)", current.minSpeedKt)
        val maxSpeed = field("Μέγιστη ταχύτητα (kt)", current.maxSpeedKt)
        val dialog = AlertDialog.Builder(this).setTitle("AIRCAT500 Radar Filters")
            .setView(wrapper)
            .setPositiveButton("Εφαρμογή", null)
            .setNeutralButton("Reset", null)
            .setNegativeButton("Άκυρο", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fun parsed(edit: EditText): Int? = edit.text.toString().trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                val entered = listOf(minAlt, maxAlt, minSpeed, maxSpeed)
                if (entered.any { it.text.toString().isNotBlank() && parsed(it) == null }) {
                    android.widget.Toast.makeText(this,"Τα όρια πρέπει να είναι ακέραιοι αριθμοί.",android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val aMin = parsed(minAlt); val aMax = parsed(maxAlt)
                val vMin = parsed(minSpeed); val vMax = parsed(maxSpeed)
                if (aMin != null && aMax != null && aMin > aMax ||
                    vMin != null && vMax != null && vMin > vMax) {
                    android.widget.Toast.makeText(this,"Το ελάχιστο δεν μπορεί να είναι μεγαλύτερο από το μέγιστο.",android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                currentFilter = RadarFilter(
                    showPSR=checks[0].isChecked, showMSSR=checks[1].isChecked,
                    showCombined=checks[2].isChecked, showLowQuality=checks[3].isChecked,
                    showCancelled=checks[4].isChecked, showLabels=checks[5].isChecked,
                    showTrackNumber=checks[6].isChecked, showModeA=checks[7].isChecked,
                    showModeC=checks[8].isChecked, showSpeed=checks[9].isChecked,
                    showRange=checks[10].isChecked, showQuality=checks[11].isChecked,
                    showVelocityVectors=checks[12].isChecked, showTrails=checks[13].isChecked,
                    showOnlyAlerts=checks[14].isChecked, hideInvalidMode=checks[15].isChecked,
                    minAltitudeFt=aMin, maxAltitudeFt=aMax,
                    minSpeedKt=vMin, maxSpeedKt=vMax
                )
                radar.filter = currentFilter
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                currentFilter = RadarFilter()
                radar.filter = currentFilter
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showTrack(track: RadarTrack) {
        val details = buildString {
            appendLine("Track #${track.number} • ${track.kindLabel}")
            appendLine("X/Y: ${track.xRaw} / ${track.yRaw}  •  Range: %.1f NM".format(Locale.US,track.rangeNm32))
            appendLine("Mode A: ${track.modeA ?: "n/a"}  •  Mode C / altitude: ${track.modeC ?: "n/a"}")
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
        AlertDialog.Builder(this)
            .setTitle("Report — τι κάνει;")
            .setMessage("Δημιουργεί αναλυτική αναφορά TXT για την τρέχουσα καταγραφή. " +
                "Περιλαμβάνει NORTH, sectors, timing, CRC (όταν υπάρχουν μετρητές), " +
                "σύνοψη σφαλμάτων χωρίς διπλομέτρηση, Mode A/C και συμβάντα Alerts.\n\n" +
                "Δεν τροποποιεί το αρχικό CSV/BIN/PCAP και δεν στέλνει δεδομένα στο Internet. " +
                "Θα επιλέξεις εσύ πού θα αποθηκευτεί το αρχείο.")
            .setPositiveButton("Αποθήκευση TXT") { _, _ ->
                pendingReport = AircatReport.create(data,stats) + "\n\n" +
                    AircatReport.errors(stats) + "\n\n" + AircatReport.alerts(stats)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "Aircat500_Analyzer_v1_2_report.txt")
                }
                startActivityForResult(intent, 78)
            }
            .setNegativeButton("Άκυρο", null)
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        worker.shutdownNow()
        super.onDestroy()
    }
}
