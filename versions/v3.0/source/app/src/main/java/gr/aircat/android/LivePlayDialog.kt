package gr.aircat.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small, additive LivePlay-only window. All v2.2 file/replay UI stays untouched. */
data class LivePlaySettings(
    val filter: RadarFilter,
    val rangeNm: Float,
    val ringsNm: Int,
    val labelSp: Float,
    val uiSp: Float
)

/** Only two live pages: the original radar view and the serial line monitor. */
class LivePlayDialog(
    private val owner: Activity,
    private val settings: () -> LivePlaySettings,
    private val inspectTrack: (RadarTrack) -> Unit,
    private val initialScreen: Int
) : Dialog(owner) {
    private val ui = Handler(Looper.getMainLooper())
    private val replay = RadarReplayState()
    private var session: AircatWifiSession? = null
    private var networkMessage = "Σύνδεσε το κινητό στο Wi-Fi SYNC."
    private var pin = "1234"
    private var closed = false
    private var screen = initialScreen.coerceIn(0, 1)
    private var lastPacket: RadarPacket? = null
    private var lastNorthEpoch: Double? = null
    private var sectorEpoch = 0.0
    private var sectorSeconds = 0.123625
    private lateinit var status: TextView
    private lateinit var radar: RadarView
    private lateinit var monitor: TextView
    private lateinit var radarPanel: FrameLayout
    private lateinit var monitorPanel: ScrollView
    private lateinit var radarTab: Button
    private lateinit var monitorTab: Button

    private val connection = WifiNetworkConnection(
        owner,
        state = { if (!closed) networkMessage = it },
        ready = { network: Network ->
            if (!closed) {
                stopSession()
                replay.clear()
                lastPacket = null
                lastNorthEpoch = null
                sectorEpoch = 0.0
                radar.sweepAzimuthDegrees = Float.NaN
                session = AircatWifiSession(bindSocket = { network.bindSocket(it) }, pin = pin)
                    .also { it.start() }
            }
        },
        lost = { stopSession() }
    )

    private fun dp(value: Int) = (value * owner.resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String) = TextView(owner).apply {
        this.text = text
        textSize = settings().uiSp
        setTextColor(Color.rgb(19, 57, 81))
        setPadding(dp(7), dp(4), dp(7), dp(4))
    }
    private fun button(text: String, action: () -> Unit) = Button(owner).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(2), dp(2), dp(2), dp(2))
        setTextColor(Color.rgb(19, 57, 81))
        backgroundTintList = ColorStateList.valueOf(Color.rgb(189, 230, 250))
        setOnClickListener { action() }
    }
    private fun row(vararg buttons: Button, height: Int = 44) = LinearLayout(owner).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b, LinearLayout.LayoutParams(0, dp(height), 1f))
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(224, 243, 253))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        root.addView(label("AIRCAT500 LivePlay · SYNC2WIFI"))
        status = label(networkMessage).apply { maxLines = 4 }
        root.addView(status)
        root.addView(row(
            button("Σύνδεση SYNC") { askCredentials(manual = false) },
            button("Τρέχον Wi-Fi") { askCredentials(manual = true) },
            button("Stop") { disconnect() }
        ))
        root.addView(row(
            button("Wi-Fi κινητού") { owner.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            button("Κλείσιμο") { dismiss() }
        ))
        radarTab = button("Live Radar Display") { selectScreen(0) }
        monitorTab = button("Radar Serial Line Monitoring") { selectScreen(1) }
        root.addView(row(radarTab, monitorTab, height = 57))

        radar = RadarView(owner).apply {
            state = replay
            onTrackTap = inspectTrack
            val s = settings()
            filter = s.filter
            maxRangeNm = s.rangeNm
            maxRingNm = s.ringsNm
            labelSizeSp = s.labelSp
            uiFontSp = s.uiSp
        }
        radarPanel = FrameLayout(owner)
        radarPanel.addView(radar, FrameLayout.LayoutParams(-1, -1))
        val zoom = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            addView(button("+") { radar.zoom *= 1.25f }, LinearLayout.LayoutParams(dp(54), dp(44)))
            addView(button("−") { radar.zoom /= 1.25f }, LinearLayout.LayoutParams(dp(54), dp(44)))
            addView(button("Reset") { radar.resetViewport() }, LinearLayout.LayoutParams(dp(69), dp(44)))
        }
        radarPanel.addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        monitor = label("Αναμονή AIRCAT500 UDP δεδομένων...").apply { setTextIsSelectable(true) }
        monitorPanel = ScrollView(owner).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(249, 252, 254))
            addView(monitor)
        }
        val content = FrameLayout(owner)
        content.addView(radarPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(monitorPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        selectScreen(screen)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.post(tick)
    }

    fun permissionResult(granted: Boolean) { connection.permissionResult(granted) }

    private fun selectScreen(which: Int) {
        screen = which.coerceIn(0, 1)
        radarPanel.visibility = if (screen == 0) View.VISIBLE else View.GONE
        monitorPanel.visibility = if (screen == 1) View.VISIBLE else View.GONE
        radarTab.alpha = if (screen == 0) 1f else .62f
        monitorTab.alpha = if (screen == 1) 1f else .62f
    }

    private fun askCredentials(manual: Boolean) {
        val box = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(7), dp(15), dp(7))
        }
        box.addView(label(
            if (manual) "Σύνδεσε πρώτα το κινητό στο SYNC από τις ρυθμίσεις Wi-Fi."
            else "Το Android θα ζητήσει σύνδεση στο SYNC (χωρίς Internet)."
        ))
        val password = EditText(owner).apply {
            hint = "Κωδικός Wi-Fi SYNC"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = settings().uiSp
        }
        if (!manual) box.addView(password)
        val enteredPin = EditText(owner).apply {
            hint = "PIN εφαρμογής"
            setText(pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            textSize = settings().uiSp
        }
        box.addView(enteredPin)
        val dialog = AlertDialog.Builder(owner)
            .setTitle("LivePlay — SYNC")
            .setView(box)
            .setPositiveButton("Σύνδεση", null)
            .setNegativeButton("Άκυρο", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newPin = enteredPin.text.toString()
                if (!Regex("[0-9]{4}").matches(newPin)) {
                    enteredPin.error = "Χρειάζονται 4 ψηφία"
                    return@setOnClickListener
                }
                if (!manual && password.text.length !in 8..63) {
                    password.error = "8–63 χαρακτήρες"
                    return@setOnClickListener
                }
                pin = newPin
                val pass = password.text.toString()
                dialog.dismiss()
                if (manual) connection.useCurrent() else connection.automatic(pass)
            }
        }
        dialog.show()
    }

    private fun stopSession() {
        session?.close()
        session = null
    }

    private fun disconnect() {
        connection.close()
        stopSession()
        networkMessage = "Η ζωντανή λήψη σταμάτησε."
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed || !isShowing) return
            val current = session
            if (current == null) {
                status.text = networkMessage
            } else {
                for (packet in current.frames.drain()) {
                    replay.apply(packet)
                    lastPacket = packet
                    if (packet.kind == 0x19) {
                        lastNorthEpoch?.let { prev ->
                            val period = packet.epochSeconds - prev
                            if (period in 1.0..20.0) sectorSeconds = period / 32.0
                        }
                        lastNorthEpoch = packet.epochSeconds
                    }
                    if (packet.sector != null || packet.kind == 0x19) sectorEpoch = packet.epochSeconds
                }
                if (lastPacket != null && sectorEpoch > 0.0) {
                    val elapsed = (System.currentTimeMillis() / 1000.0 - sectorEpoch)
                        .coerceIn(0.0, sectorSeconds)
                    radar.sweepAzimuthDegrees = (
                        ((replay.sector ?: 0) + elapsed / sectorSeconds) * 11.25
                    ).toFloat()
                }
                val snap = current.snapshot
                status.text = "${snap.message}\n${snap.transport.firmwareLabel} · CLOCK ${snap.transport.clockHz} Hz" +
                    "\nCRC ${snap.bsc.crcErrors} · Framing ${snap.bsc.framingErrors} · UDP lost ${snap.transport.lost}"
                if (screen == 1) monitor.text = monitorText(snap, current)
            }
            ui.postDelayed(this, 100)
        }
    }

    private fun monitorText(s: WifiSessionSnapshot, current: AircatWifiSession): String {
        val transport = s.transport
        val bsc = s.bsc
        return buildString {
            appendLine("AIRCAT500 — Radar Serial Line Monitoring")
            appendLine("Wi-Fi SYNC · ESP32-S3 192.168.4.1 · UDP DATA 5005 / HELLO 5006")
            appendLine(transport.firmwareLabel)
            appendLine("CLOCK: ${transport.clockHz} Hz · ${s.message}")
            appendLine()
            appendLine("BSC bytes: ${bsc.bytes} · SYNC: ${bsc.sync}")
            appendLine("Frames: ${bsc.framed} · CRC OK: ${bsc.valid}")
            appendLine("NORTH: ${bsc.north} · Sectors: ${bsc.sectors} · Extensions: ${bsc.extensions}")
            appendLine("CRC FAIL: ${bsc.crcErrors} · Framing: ${bsc.framingErrors}")
            appendLine("Rotations: ${bsc.completed} · Perfect 32/32: ${bsc.perfect}")
            appendLine("Missing clean: ${bsc.missingClean} · Uncertain: ${bsc.missingUncertain}")
            appendLine("Duplicates: ${bsc.duplicates} · Order errors: ${bsc.orderErrors}")
            appendLine("Current sectors: ${bsc.currentUnique ?: "—"} / 32")
            appendLine()
            appendLine("UDP received: ${transport.received} · Lost: ${transport.lost}")
            appendLine("UDP duplicates: ${transport.duplicates} · Invalid: ${transport.invalid}")
            appendLine("UDP CRC32 FAIL: ${transport.badCrc32}")
            appendLine("Firmware overflow: ${transport.firmwareOverflow} · Since connect: +${transport.overflowSinceConnect}")
            appendLine("Link resets: ${transport.linkResets} · Display dropped: ${current.frames.displayDropped}")
            appendLine()
            lastPacket?.let {
                appendLine("Frame #${it.frameNo} · TYPE 0x${"%02X".format(it.kind)} · SECTOR ${it.sector ?: "—"}")
                appendLine(it.payload.take(160).joinToString(" ") { v -> "%02X".format(v.toInt() and 255) })
            } ?: appendLine("Αναμονή πρώτου CRC-valid AIRCAT frame...")
            if (bsc.lastError.isNotBlank()) appendLine("\nΤελευταίο σφάλμα: ${bsc.lastError}")
        }
    }

    override fun dismiss() {
        if (!closed) {
            closed = true
            ui.removeCallbacks(tick)
            connection.close()
            stopSession()
        }
        super.dismiss()
    }
}
