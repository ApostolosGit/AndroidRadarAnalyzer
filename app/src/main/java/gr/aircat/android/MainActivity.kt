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
import android.widget.CheckBox
import android.widget.EditText
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
    private var playbackDirection = 1 // -1 backward, +1 forward
    private val checkpointStep = 128
    private var replayCheckpoints: Map<Int, RadarReplayState.Snapshot> = emptyMap()
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
    private lateinit var timelineLabel: TextView
    private lateinit var reverse: Button
    private lateinit var forward: Button
    private lateinit var play: Button
    private lateinit var rate: Button
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

        root.addView(title("AIRCAT500 Mobile  v1.1", 17f))
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
        timelineLabel = title("Replay: 00:00 / 00:00", 11f)
        root.addView(timelineLabel)
        seek = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, index: Int, fromUser: Boolean) {
                    if (fromUser) {
                        changingSeek = true
                        val packets = capture?.packets
                        if (packets != null && packets.isNotEmpty()) {
                            timelineLabel.text = formatElapsed(packets[index.coerceIn(0,packets.lastIndex)].epochSeconds -
                                packets.first().epochSeconds) + " / " +
                                formatElapsed(packets.last().epochSeconds-packets.first().epochSeconds)
                        }
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {
                    changingSeek = true
                    pause() // Seeking stops movement; displayed targets remain in memory.
                }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    changingSeek = false
                    jumpTo(seek.progress)
                }
            })
        }
        root.addView(seek)
        reverse = button("<<") { chooseDirection(-1) }
        play = button("▶ Play") { if (playing) pause() else start() }
        forward = button(">>") { chooseDirection(1) }
        rate = button("1×") {
            speed = when (speed) {
                1.0 -> 2.0
                2.0 -> 4.0
                4.0 -> 8.0
                8.0 -> 16.0
                else -> 1.0
            }
            rate.text = speed.toInt().toString() + "×"
            if (playing) resetClock()
        }
        root.addView(row(reverse, play, forward, rate, height = 45))
        root.addView(row(
            button("-1 Frame") { jumpTo((record-2).coerceAtLeast(0)) },
            button("+1 Frame") { jumpTo(record) },
            button("20 NM / Reset") { radar.resetViewport() },
            height = 38
        ))
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
                // Build sparse seek checkpoints in background, not on the UI thread.
                // Each checkpoint preserves current targets and historical trails.
                val checkpoints = HashMap<Int, RadarReplayState.Snapshot>()
                val scratch = RadarReplayState()
                for (i in loaded.packets.indices) {
                    scratch.apply(loaded.packets[i])
                    if (i % checkpointStep == 0) checkpoints[i] = scratch.snapshot()
                }
                runOnUiThread {
                    replayCheckpoints = checkpoints
                    capture = loaded
                    diagnostics = analysis
                    sectorSeconds = ((analysis.medianPeriod ?: 3.956) / 32.0).coerceIn(.02, 5.0)
                    seek.max = (loaded.packets.size - 1).coerceAtLeast(0)
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
        // A pause freezes the existing radar state and antenna angle; it does
        // not erase tracks, symbols, labels or trails.
        playing = false
        play.text = "▶ Play"
    }

    private fun resetClock() {
        val frames = capture?.packets ?: return
        if (frames.isEmpty()) return
        startTime = frames[(record-1).coerceIn(0, frames.lastIndex)].epochSeconds
        clockStart = SystemClock.elapsedRealtime()
    }

    private fun chooseDirection(next: Int) {
        playbackDirection = if (next < 0) -1 else 1
        start()
    }

    private fun start() {
        val frames = capture?.packets ?: return
        if (frames.isEmpty()) return
        // The selected direction determines the restart boundary.
        if (playbackDirection > 0 && record >= frames.size) jumpTo(0)
        if (playbackDirection < 0 && record <= 1) jumpTo(frames.lastIndex)
        resetClock()
        playing = true
        play.text = if (playbackDirection < 0) "Ⅱ ◀ Pause" else "Ⅱ Pause"
    }

    private fun jumpTo(frame: Int, preservePlayback: Boolean = false) {
        val frames = capture?.packets ?: return
        if (frames.isEmpty()) return
        if (!preservePlayback) pause()
        val desired = frame.coerceIn(0, frames.lastIndex)
        val checkpoint = (desired / checkpointStep) * checkpointStep
        val snapshot = replayCheckpoints[checkpoint]
        val firstApply: Int
        if (snapshot != null) {
            replay.restore(snapshot)
            firstApply = checkpoint + 1
        } else {
            replay.clear()
            firstApply = 0
        }
        if (firstApply <= desired) for (i in firstApply..desired) replay.apply(frames[i])
        record = desired + 1
        sectorEpoch = frames.first().epochSeconds
        for (i in desired downTo 0) {
            val packet = frames[i]
            if (packet.sector != null || packet.kind == 0x19) {
                sectorEpoch = packet.epochSeconds
                break
            }
        }
        updateSweep(frames[desired].epochSeconds)
        refreshFrame()
    }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                val frames = capture?.packets
                if (playing && frames != null && frames.isNotEmpty()) {
                    val elapsed = (SystemClock.elapsedRealtime() - clockStart) * speed / 1000.0
                    val targetTime = startTime + elapsed * playbackDirection
                    val desired = ReplayTimeline.frameAt(frames, targetTime)
                    if (desired != record-1) jumpTo(desired, preservePlayback = true)
                    updateSweep(targetTime.coerceIn(frames.first().epochSeconds, frames.last().epochSeconds))
                    if ((playbackDirection > 0 && targetTime >= frames.last().epochSeconds) ||
                        (playbackDirection < 0 && targetTime <= frames.first().epochSeconds)) pause()
                }
            } finally {
                ui.postDelayed(this, 33)
            }
        }
    }

    private fun formatElapsed(elapsed: Double): String {
        val t = maxOf(0L, elapsed.toLong())
        return String.format(Locale.US, "%02d:%02d:%02d",t/3600, (t/60)%60, t%60)
    }

    private fun updateSweep(epoch: Double) {
        val sec = replay.sector ?: 0
        val elapsed = (epoch - sectorEpoch).coerceIn(0.0, sectorSeconds)
        radar.sweepAzimuthDegrees = ((sec + elapsed / sectorSeconds) * 11.25).toFloat()
    }
    private fun refreshFrame() {
        val data = capture ?: return
        val packet = data.packets[(record - 1).coerceIn(0, data.packets.lastIndex)]
        if (!changingSeek) seek.progress = (record - 1).coerceAtLeast(0)
        info.text = data.name + " • " + record + "/" + data.summary.packets +
            " • NORTH " + replay.cycle + " • Sector " +
            (replay.sector?.toString(16)?.uppercase(Locale.US)?.padStart(2,'0') ?: "--")
        timelineLabel.text = formatElapsed(packet.epochSeconds - data.packets.first().epochSeconds) +
            " / " + formatElapsed(data.packets.last().epochSeconds - data.packets.first().epochSeconds) +
            "   " + (if (playbackDirection < 0) "<<" else ">>") + " " + speed.toInt() + "×"
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
        val outer = ScrollView(this)
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(6))
        }
        outer.addView(settings)
        fun check(label: String, enabled: Boolean): CheckBox = CheckBox(this).apply {
            text = label
            textSize = 13f
            isChecked = enabled
            setTextColor(fg)
            settings.addView(this)
        }
        val psr = check("PSR (Primary)",current.showPSR)
        val mssr = check("MSSR / SSR / Combined",current.showMSSR)
        val low = check("Low quality",current.showLowQuality)
        val cancelled = check("Cancelled tracks",current.showCancelled)
        val labels = check("Mode A/C labels (όλοι οι στόχοι)",current.showLabels)
        val trails = check("Ίχνη τροχιάς",current.showTrails)
        val onlyAlerts = check("Μόνο alerts",current.showOnlyAlerts)
        val invalid = check("Κρύψε invalid Mode A/C",current.hideInvalidMode)

        val trailLabel = title("Ιστορικό ίχνους: " + radar.trailHistoryLength + " σημεία",12f)
        settings.addView(trailLabel)
        val trailSeek = SeekBar(this).apply {
            max = 63
            progress = radar.trailHistoryLength - 1
            isEnabled = trails.isChecked
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    trailLabel.text = "Ιστορικό ίχνους: " + (value+1) + " σημεία"
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }
        settings.addView(trailSeek)
        trails.setOnCheckedChangeListener { _, active -> trailSeek.isEnabled = active }

        settings.addView(title("Φίλτρα αριθμητικών τιμών (κενό = χωρίς όριο)",12f))
        fun numeric(label: String, value: Number?, signed: Boolean = false, decimal: Boolean = false): EditText {
            settings.addView(title(label,11f))
            return EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    (if (signed) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
                    (if (decimal) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
                textSize = 13f
                maxLines = 1
                hint = "χωρίς όριο"
                value?.let { setText(it.toString()) }
                settings.addView(this)
            }
        }
        val minAlt = numeric("Ελάχιστο ύψος Mode C (ft)",current.minAltitudeFt,true)
        val maxAlt = numeric("Μέγιστο ύψος Mode C (ft)",current.maxAltitudeFt,true)
        val minSpeed = numeric("Ελάχιστη ταχύτητα (kt)",current.minSpeedKt)
        val maxSpeed = numeric("Μέγιστη ταχύτητα (kt)",current.maxSpeedKt)
        val minRange = numeric("Ελάχιστη απόσταση (NM) — προαιρετικό",current.minRangeNm,false,true)
        val maxRange = numeric("Μέγιστη απόσταση (NM) — προαιρετικό",current.maxRangeNm,false,true)
        settings.addView(title("Αν υπάρχει όριο, στόχοι χωρίς γνωστή αντίστοιχη τιμή δεν εμφανίζονται.",10f))
        fun raw(e: EditText) = e.text.toString().trim()
        AlertDialog.Builder(this).setTitle("Radar Filters")
            .setView(outer)
            .setPositiveButton("Εφαρμογή") { _, _ ->
                val fields = listOf(minAlt,maxAlt,minSpeed,maxSpeed)
                val rangeFields = listOf(minRange,maxRange)
                if (fields.any { raw(it).isNotEmpty() && raw(it).toIntOrNull() == null } ||
                    rangeFields.any { raw(it).isNotEmpty() && raw(it).toDoubleOrNull() == null }) {
                    android.widget.Toast.makeText(this,"Μη έγκυρη αριθμητική τιμή",android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                fun optionalInt(e: EditText) = raw(e).toIntOrNull()
                fun optionalDouble(e: EditText) = raw(e).toDoubleOrNull()
                val aMin=optionalInt(minAlt)
                val aMax=optionalInt(maxAlt)
                val sMin=optionalInt(minSpeed)
                val sMax=optionalInt(maxSpeed)
                val rMin=optionalDouble(minRange)
                val rMax=optionalDouble(maxRange)
                if ((aMin!=null && aMax!=null && aMin>aMax) ||
                    (sMin!=null && sMax!=null && sMin>sMax) ||
                    (rMin!=null && rMax!=null && rMin>rMax)) {
                    android.widget.Toast.makeText(this,"Το ελάχιστο πρέπει να είναι ≤ του μέγιστου",android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                currentFilter = RadarFilter(
                    showPSR=psr.isChecked, showMSSR=mssr.isChecked,
                    showLowQuality=low.isChecked,showCancelled=cancelled.isChecked,
                    showLabels=labels.isChecked,showTrails=trails.isChecked,
                    showOnlyAlerts=onlyAlerts.isChecked,hideInvalidMode=invalid.isChecked,
                    minAltitudeFt=aMin,maxAltitudeFt=aMax,
                    minSpeedKt=sMin,maxSpeedKt=sMax,
                    minRangeNm=rMin,maxRangeNm=rMax
                )
                radar.trailHistoryLength = trailSeek.progress + 1
                radar.filter = currentFilter
                radar.invalidate()
            }
            .setNegativeButton("Άκυρο",null)
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
