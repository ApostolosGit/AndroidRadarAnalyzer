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
import android.os.SystemClock
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.method.PasswordTransformationMethod
import android.widget.CheckBox
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
    val uiSp: Float,
    val trailDepth: Int
)

/** Only two live pages: the original radar view and the serial line monitor. */
class LivePlayDialog(
    private val owner: Activity,
    private val settings: () -> LivePlaySettings,
    private val inspectTrack: (RadarTrack) -> Unit,
    private val openFilters: () -> Unit,
    private val openSettings: () -> Unit,
    private val initialScreen: Int
) : Dialog(owner) {
    private val ui = Handler(Looper.getMainLooper())
    private val replay = RadarReplayState()
    private var session: AircatWifiSession? = null
    private var networkMessage = "Αναμονή σύνδεσης SYNC..."
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
    private lateinit var connectButton: Button
    private lateinit var recordButton: Button
    private lateinit var troubleshooting: TextView
    private lateinit var radarCounters: TextView
    private val credentials = SyncCredentials(owner)
    private var connecting = false
    private var attempted = false
    private var recorder: WifiCsvRecorder? = null
    private var recordPending: AircatWifiSession? = null
    private var recordMessage = ""
    private var login: SyncLogin? = null
    private var retryCount = 0
    private var failureMessage = ""
    private var recordStartedMs = 0L
    private lateinit var recordStatus: TextView
    private var appliedSettings: LivePlaySettings? = null
    private val scalableLabels=ArrayList<TextView>()
    private val scalableButtons=ArrayList<Button>()
    private val retry = Runnable {
        if (!closed && connecting) login?.let { begin(it, retrying=true) }
    }

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
        failed = { reason -> if (!closed) handleFailure(reason) }
    )

    private fun dp(value: Int) = (value * owner.resources.displayMetrics.density + .5f).toInt()
    private fun label(text: String) = TextView(owner).apply {
        this.text = text
        textSize = settings().uiSp
        scalableLabels.add(this)
        setTextColor(Color.rgb(19, 57, 81))
        setPadding(dp(7), dp(4), dp(7), dp(4))
    }
    private fun button(text: String, action: () -> Unit) = Button(owner).apply {
        this.text = text
        textSize = settings().uiSp.coerceIn(12f,16f)
        scalableButtons.add(this)
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
        root.addView(label(if (screen == 0) "Live Radar Display · SYNC2WIFI" else "Radar Serial Line Monitor · SYNC2WIFI"))
        status = label(networkMessage).apply { textSize = 13f }
        root.addView(status)
        connectButton = button("Σύνδεση SYNC") {
            if (connecting || session?.snapshot?.running == true) disconnect() else connect(false)
        }
        recordButton = button("Record") { recordTapped() }.apply { isEnabled = false }
        root.addView(row(button("Φίλτρα") { openFilters() }, button("Ρυθμίσεις") { openSettings() }))
        root.addView(if (screen == 0) row(connectButton, recordButton, button("Κλείσιμο") { dismiss() })
            else row(connectButton, button("Κλείσιμο") { dismiss() }))
        troubleshooting = label("").apply { textSize = 12f }
        root.addView(troubleshooting)
        recordStatus = label("").apply { textSize = 12f; visibility = View.GONE }
        if (screen == 0) root.addView(recordStatus)
        radarCounters = label("").apply { textSize = 12f }
        if (screen == 0) root.addView(radarCounters)

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
        applyLiveSettings()
        radarPanel.visibility = if (screen == 0) View.VISIBLE else View.GONE
        monitorPanel.visibility = if (screen == 1) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.removeCallbacks(tick)
        ui.post(tick)
        if (!attempted) { attempted = true; ui.post { if (!closed) connect(true) } }
    }

    fun permissionResult(granted: Boolean) { connection.permissionResult(granted) }
    fun wifiPanelResult() { connection.wifiPanelResult() }

    private fun applyLiveSettings() {
        val value=settings()
        if(value==appliedSettings)return
        appliedSettings=value
        radar.filter=value.filter
        radar.maxRangeNm=value.rangeNm
        radar.maxRingNm=value.ringsNm
        radar.labelSizeSp=value.labelSp
        radar.uiFontSp=value.uiSp
        replay.trailDepth=value.trailDepth
        scalableLabels.forEach { it.textSize=value.uiSp }
        scalableButtons.forEach { it.textSize=value.uiSp.coerceIn(12f,16f) }
    }

    private fun handleFailure(reason: String) {
        connection.close()
        stopSession()
        failureMessage=reason
        connecting=false
        networkMessage=reason
        // A dropped link or failed HELLO gets one clean, delayed retry. Do not
        // loop on denied permissions, a disabled radio or association timeout.
        if(retryCount==0 && login!=null && connection.radioEnabled() &&
            (reason.contains("Χάθηκε") || reason.contains("HELLO sent="))){
            retryCount++
            connecting=true
            networkMessage="Επανασύνδεση σε 8s μετά την αποδέσμευση του SYNC..."
            ui.removeCallbacks(retry)
            ui.postDelayed(retry,8000)
        }
    }

    private fun connect(auto: Boolean) {
        val saved = login ?: credentials.load()
        if (saved != null && auto) begin(saved) else askCredentials(saved)
    }

    private fun begin(value: SyncLogin, retrying: Boolean=false) {
        ui.removeCallbacks(retry)
        if(!retrying){retryCount=0;failureMessage=""}
        stopSession()
        login = value
        pin = value.pin
        connecting = true
        networkMessage = "Αναμονή σύνδεσης SYNC..."
        connection.connect(value.password,forceNew=retrying)
    }

    private fun askCredentials(saved: SyncLogin?) {
        val box = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(7), dp(15), dp(7))
        }
        box.addView(label("Wi-Fi SYNC · χωρίς Internet"))
        val password = EditText(owner).apply {
            hint = "Κωδικός Wi-Fi SYNC"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(saved?.password ?: "")
            textSize = settings().uiSp
        }
        val enteredPin = EditText(owner).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setText(saved?.pin ?: "1234")
            textSize = settings().uiSp
        }
        box.addView(password)
        box.addView(enteredPin)
        box.addView(CheckBox(owner).apply {
            text = "Εμφάνιση κωδικού / PIN"
            setOnCheckedChangeListener { _, show ->
                for (field in listOf(password, enteredPin)) {
                    field.transformationMethod = if (show) null else PasswordTransformationMethod.getInstance()
                    field.setSelection(field.text.length)
                }
            }
        })
        val dialog = AlertDialog.Builder(owner).setTitle("SYNC2WIFI")
            .setView(box).setPositiveButton("Σύνδεση", null)
            .setNegativeButton("Άκυρο", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pass = password.text.toString()
                val newPin = enteredPin.text.toString()
                if (pass.length !in 8..63) { password.error = "8–63 χαρακτήρες"; return@setOnClickListener }
                if (!Regex("[0-9]{4}").matches(newPin)) { enteredPin.error = "4 ψηφία"; return@setOnClickListener }
                val value = SyncLogin(pass, newPin)
                val stored = credentials.save(value)
                dialog.dismiss()
                begin(value)
                if (!stored) android.widget.Toast.makeText(owner, "Δεν αποθηκεύτηκαν τα στοιχεία στο Android Keystore.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        dialog.show()
    }

    private fun recordTapped() {
        recorder?.let { if (!it.finished) { it.stop(); return } }
        val current = session ?: return
        if (!current.snapshot.running || current.snapshot.lastAckAgoMs == null) return
        recordPending = current
        try {
            owner.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, "AIRCAT500_SYNC2WIFI_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv")
            }, 2304)
        } catch (ex: Exception) {
            recordPending = null
            recordMessage = "Αδυναμία επιλογής αρχείου: " + ex.message
        }
    }

    fun recordDestination(uri: Uri?) {
        val current = recordPending
        recordPending = null
        if (uri == null || closed || current == null || current !== session) return
        try {
            recordMessage = ""
            recordStartedMs = SystemClock.elapsedRealtime()
            recorder = current.record(onFinished = { rows, error ->
                ui.post {
                    recordMessage = error ?: if (rows > 0) "CSV αποθηκεύτηκε: $rows frames" else "CSV χωρίς δεδομένα (0 frames)"
                    if (closed) android.widget.Toast.makeText(owner, recordMessage, android.widget.Toast.LENGTH_LONG).show()
                }
            }) { owner.contentResolver.openOutputStream(uri, "wt") ?: error("Αδυναμία ανοίγματος CSV") }
        } catch (ex: Exception) { recordMessage = "Record: " + ex.message }
    }

    private fun stopSession() {
        session?.close()
        session = null
        recordPending = null
        replay.clear()
        lastPacket = null
        sectorEpoch = 0.0
        if (::radar.isInitialized) radar.sweepAzimuthDegrees = Float.NaN
    }

    private fun disconnect() {
        ui.removeCallbacks(retry)
        failureMessage=""
        connecting = false
        connection.close()
        stopSession()
        networkMessage = "Η σύνδεση διακόπηκε."
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed || !isShowing) return
            applyLiveSettings()
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
                if (!snap.running) {
                    handleFailure(snap.message+"\n"+connection.diagnostics())
                } else {
                    if (screen == 1) monitor.text = monitorText(snap, current)
                    if (screen == 0) radarCounters.text = radarSummary(snap)
                }
            }
            val snap = session?.snapshot
            val linked = snap?.running == true && snap.lastAckAgoMs != null
            val active = connecting || snap?.running == true
            if(linked){connecting=false;failureMessage="";retryCount=0}
            connectButton.text = if (active) "Διακοπή Σύνδεσης" else "Σύνδεση SYNC"
            status.visibility = if(linked) View.GONE else View.VISIBLE
            troubleshooting.visibility = if(linked || failureMessage.isEmpty()) View.GONE else View.VISIBLE
            val spinner=charArrayOf('|','/','—','\\')[(SystemClock.elapsedRealtime()/150%4).toInt()]
            status.text=when {
                linked -> ""
                active -> "$spinner "+(snap?.message ?: networkMessage)
                else -> if(failureMessage.isEmpty())networkMessage else "Αποτυχία σύνδεσης SYNC"
            }
            troubleshooting.text=failureMessage
            val rec = recorder
            val recording=rec?.isAccepting()==true
            recordButton.text = when {
                rec == null || rec.finished -> "Record"
                recording -> "REC "+formatRecordDuration((SystemClock.elapsedRealtime()-recordStartedMs)/1000)
                else -> "Αποθήκευση..."
            }
            recordButton.backgroundTintList=ColorStateList.valueOf(if(recording) Color.rgb(190,25,25) else Color.rgb(189,230,250))
            recordButton.setTextColor(if(recording) Color.WHITE else Color.rgb(19,57,81))
            recordButton.isEnabled=recordPending==null && (recording || ((rec==null || rec.finished) && linked))
            recordStatus.text=recordMessage
            recordStatus.visibility=if(recordMessage.isEmpty())View.GONE else View.VISIBLE
            if(snap==null && screen==0)radarCounters.text=""
            if(snap==null && screen==1)monitor.text="Αναμονή AIRCAT500 UDP δεδομένων..."
            ui.postDelayed(this, 100)
        }
    }

    private fun monitorText(s: WifiSessionSnapshot, current: AircatWifiSession): CharSequence {
        val transport = s.transport
        val bsc = s.bsc
        val plain = buildString {
            appendLine("AIRCAT500 — Radar Serial Line Monitor")
            appendLine("CLOCK: ${transport.clockHz} Hz")
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
            appendLine("Scan missed: "+s.timing.missed+" · North jitter: "+s.timing.jitter+" (arrival timing)")
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
        return colored(plain, listOf("CRC FAIL", "Framing", "Missing clean", "Uncertain", "Duplicates",
            "Order errors", "Lost", "Invalid", "UDP CRC32 FAIL", "Firmware overflow", "Since connect",
            "Link resets", "Display dropped", "Scan missed", "North jitter"))
    }

    private fun colored(value: String, labels: List<String>): CharSequence {
        val out = SpannableStringBuilder(value)
        for (name in labels) {
            val re = Regex(Regex.escape(name) + "[: ]+\\s*[+]?\\d+")
            for (match in re.findAll(value)) {
                out.setSpan(ForegroundColorSpan(Color.RED), match.range.first,
                    match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    private fun radarSummary(s: WifiSessionSnapshot): CharSequence {
        val b = s.bsc
        val text = "packet #" + (lastPacket?.frameNo ?: 0) + " · tracks " + replay.tracksBySector.values.sumOf { it.size } +
            " · NORTH " + b.north + " · Sector " + (replay.sector ?: "—") +
            "\nsector missing: " + (b.missingClean + b.missingUncertain) + " · scan missed: " + s.timing.missed +
            " · north jitter: " + s.timing.jitter +
            "\nCRC FAIL: " + b.crcErrors + " · Framing: " + b.framingErrors + " · UDP lost: " + s.transport.lost
        return colored(text, listOf("sector missing", "scan missed", "north jitter", "CRC FAIL", "Framing", "UDP lost"))
    }

    override fun dismiss() {
        if (!closed) {
            closed = true
            ui.removeCallbacks(tick)
            ui.removeCallbacks(retry)
            connection.close()
            stopSession()
        }
        super.dismiss()
    }
}
