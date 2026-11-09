package gr.aircat.android

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
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

/** Phase 1: real AIRCAT500 CSV replay on Android; no Wi-Fi firmware required. */
class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val readExecutor = Executors.newSingleThreadExecutor()
    private val state = RadarReplayState()
    private var capture: RadarCapture? = null
    private var cursor = 0
    private var playing = false
    private var speed = 1.0
    private var playAtEpoch = 0.0
    private var playAtElapsed = 0L
    private var monitoring = false
    private var changingSeek = false
    private var lastSectorEpoch = 0.0
    private val sectorSeconds = 3.956 / 32.0

    private lateinit var info: TextView
    private lateinit var monitor: TextView
    private lateinit var seek: SeekBar
    private lateinit var radar: RadarView
    private lateinit var playButton: Button
    private lateinit var speedButton: Button
    private lateinit var modeButton: Button
    private lateinit var progress: ProgressBar
    private val darkText = Color.rgb(17, 26, 30)
    private val neutral = Color.rgb(231, 234, 236)

    private fun label(text: String, size: Float = 13f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(darkText)
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 12f
        setTextColor(Color.BLACK)
        isAllCaps = false
        setOnClickListener { action() }
    }
    private fun row(vararg items: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (item in items) addView(item, LinearLayout.LayoutParams(0, dp(46), 1f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(44, 52, 57)
        window.navigationBarColor = Color.rgb(44, 52, 57)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(neutral)
            setPadding(dp(6), dp(7), dp(6), dp(6))
        }
        // Android 15 enforces edge-to-edge for target 35, so respect status/nav bars.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val system = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars())
            } else null
            val left = system?.left ?: insets.systemWindowInsetLeft
            val top = system?.top ?: insets.systemWindowInsetTop
            val right = system?.right ?: insets.systemWindowInsetRight
            val bottom = system?.bottom ?: insets.systemWindowInsetBottom
            root.setPadding(dp(6) + left, dp(7) + top, dp(6) + right, dp(6) + bottom)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        root.addView(label("AIRCAT500 Mobile  •  Radar Replay", 17f))
        val loadButton = button("Άνοιγμα CSV") { chooseCsv() }
        modeButton = button("Monitoring") { monitoring = !monitoring; switchMode() }
        root.addView(row(loadButton, modeButton))
        info = label("Άνοιξε ένα πραγματικό AIRCAT500 CSV για να ξεκινήσει το Replay.")
        root.addView(info)
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))

        radar = RadarView(this).apply { state = this@MainActivity.state }
        root.addView(radar, LinearLayout.LayoutParams(-1, 0, 1f))
        monitor = label("")
        val scroller = ScrollView(this).apply {
            addView(monitor)
            visibility = View.GONE
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        monitorView = scroller

        seek = SeekBar(this).apply {
            max = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, position: Int, fromUser: Boolean) {
                    if (fromUser) { changingSeek = true; info.text = "Εγγραφή ${position + 1} / ${capture?.packets?.size ?: 0}" }
                }
                override fun onStartTrackingTouch(b: SeekBar?) { changingSeek = true }
                override fun onStopTrackingTouch(b: SeekBar?) {
                    changingSeek = false
                    jumpTo(seek.progress)
                }
            })
        }
        root.addView(seek)
        playButton = button("▶ Play") { if (playing) pause() else play() }
        speedButton = button("1×") {
            speed = when (speed) { 1.0 -> 2.0; 2.0 -> 4.0; 4.0 -> 8.0; else -> 1.0 }
            speedButton.text = "${speed.toInt()}×"
            if (playing) resetClock()
        }
        val step = button("+1 Frame") { pause(); jumpTo(cursor) }
        root.addView(row(playButton, speedButton, step))
        mainHandler.post(ticker)
    }
    private lateinit var monitorView: ScrollView
    private fun switchMode() {
        radar.visibility = if (monitoring) View.GONE else View.VISIBLE
        monitorView.visibility = if (monitoring) View.VISIBLE else View.GONE
        modeButton.text = if (monitoring) "Radar" else "Monitoring"
        showFrame()
    }
    private fun chooseCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/comma-separated-values",
                "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, 70)
    }
    @Deprecated("Used for compatibility back to Android 8 without external AndroidX dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 70 || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        pause()
        info.text = "Ανάγνωση AIRCAT CSV..."
        progress.visibility = View.VISIBLE
        readExecutor.execute {
            try {
                val name = uri.lastPathSegment ?: "AIRCAT capture.csv"
                val imported = contentResolver.openInputStream(uri)?.use { stream ->
                    AircatCsv.read(InputStreamReader(stream, Charsets.UTF_8), name)
                } ?: error("Το αρχείο δεν είναι διαθέσιμο")
                runOnUiThread {
                    capture = imported
                    progress.visibility = View.GONE
                    seek.max = (imported.packets.size - 1).coerceAtLeast(0)
                    jumpTo(0)
                    // Start moving as soon as the real capture is loaded.
                    play()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    info.text = "Σφάλμα CSV: ${ex.message ?: "Άγνωστο σφάλμα"}"
                }
            }
        }
    }
    private fun pause() { playing = false; playButton.text = "▶ Play" }
    private fun resetClock() {
        val packets = capture?.packets ?: return
        if (cursor >= packets.size) return
        playAtEpoch = packets[cursor].epochSeconds
        playAtElapsed = SystemClock.elapsedRealtime()
    }
    private fun play() {
        if (capture == null) return
        if (cursor >= (capture?.packets?.size ?: 0)) jumpTo(0)
        resetClock()
        playing = true
        playButton.text = "Ⅱ Pause"
    }
    private fun jumpTo(index: Int) {
        val packets = capture?.packets ?: return
        pause()
        cursor = index.coerceIn(0, packets.lastIndex)
        state.clear()
        lastSectorEpoch = packets.first().epochSeconds
        // Rebuild only from recorded targets; do not invent packets.
        for (i in 0..cursor) {
            val packet = packets[i]
            state.apply(packet)
            if (packet.sector != null || packet.kind == 0x19) lastSectorEpoch = packet.epochSeconds
        }
        cursor++
        updateSweep(packets[cursor - 1].epochSeconds)
        showFrame()
    }
    private val ticker = object : Runnable {
        override fun run() {
            val packets = capture?.packets
            if (playing && packets != null && cursor < packets.size) {
                val targetEpoch = playAtEpoch +
                    (SystemClock.elapsedRealtime() - playAtElapsed) * speed / 1000.0
                var updates = 0
                while (cursor < packets.size && updates < 300 && packets[cursor].epochSeconds <= targetEpoch) {
                    val packet = packets[cursor++]
                    state.apply(packet)
                    if (packet.sector != null || packet.kind == 0x19) lastSectorEpoch = packet.epochSeconds
                    updates++
                }
                if (updates != 0) showFrame()
                // Animate between consecutive recorded sectors at ~30 FPS.
                updateSweep(targetEpoch)
                if (cursor >= packets.size) pause()
            }
            mainHandler.postDelayed(this, 33)
        }
    }
    private fun updateSweep(playbackEpoch: Double) {
        val sector = state.sector ?: 0
        val ageSeconds = (playbackEpoch - lastSectorEpoch).coerceIn(0.0, sectorSeconds)
        radar.sweepAzimuthDegrees = ((sector + ageSeconds / sectorSeconds) * 360.0 / 32.0).toFloat()
    }

    private fun showFrame() {
        val data = capture ?: return
        val stats = data.summary
        val frame = data.packets[(cursor - 1).coerceIn(0, data.packets.lastIndex)]
        if (!changingSeek) seek.progress = (cursor - 1).coerceAtLeast(0)
        info.text = String.format(Locale.US,
            "%s  •  %d/%d  •  North %d  •  Sector %s",
            data.name.substringAfterLast('/'), cursor, stats.packets, state.cycle,
            state.sector?.toString(16)?.uppercase()?.padStart(2, '0') ?: "--")
        val crcInfo = stats.crcFailuresDuringCsv?.toString() ?: "δεν υπάρχουν στοιχεία"
        val framingInfo = stats.framingFailuresDuringCsv?.toString() ?: "δεν υπάρχουν στοιχεία"
        val header = """
            PACKETS               ${stats.packets}
            NORTH                 ${stats.north}
            RADAR SECTORS         ${stats.sectors}
            RADAR EXTENSION       ${stats.extension}
            COMPLETE ROTATIONS    ${stats.completeRotations}
            PERFECT 32/32         ${stats.rotations32of32}
            MISSING SECTOR IDs    ${stats.missingSectorIds}
            DUPLICATE SECTORS     ${stats.duplicateSectors}
            OUT-OF-ORDER          ${stats.outOfOrder}
            TRACK RECORDS         ${stats.tracksDecoded}
            INVALID CSV ROWS      ${stats.invalidRows}
            USB CRC FAIL DELTA    $crcInfo
            USB FRAMING DELTA     $framingInfo

            Τα missing sector IDs μετρώνται μόνο μεταξύ δύο NORTH.
            Δεν αποτελούν απόδειξη απώλειας στη φυσική γραμμή.
            Τα CRC deltas δεν προκύπτουν από επανέλεγχο του CSV:
            προέρχονται από snapshots του firmware monitor.
        """.trimIndent()
        val line = frame.payload.take(80).joinToString(" ") { "%02X".format(it.toInt() and 255) }
        monitor.text = "$header\n\nLAST FRAME #${frame.frameNo}  TYPE 0x${"%02X".format(frame.kind)}\n$line"
        radar.invalidate()
    }
    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        readExecutor.shutdownNow()
        super.onDestroy()
    }
}
