package gr.aircat.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import java.util.Locale
import java.util.concurrent.Executors

data class WifiLiveSettings(val filter: RadarFilter,val rangeNm: Float,val ringsNm: Int,
    val labelSp: Float,val uiSp: Float,val historyDepth: Int)
private data class WifiWindowAnalysis(val capture: RadarCapture,val diagnostics: CaptureDiagnostics,
    val bars: DesktopSectorBars,val errors: String,val alerts: String,val report: String)

/** A separate live window leaves the v2.2 capture, replay cursor and render state intact. */
class WifiLiveDialog(
    private val owner: Activity,private val settings: ()->WifiLiveSettings,
    private val openFilters: ()->Unit,private val openSettings: ()->Unit,
    private val inspectTrack: (RadarTrack)->Unit,
    private val requestCsv: ()->Unit,private val saveText: (String)->Unit,
    private val recordCsv: (Uri,AircatWifiSession)->Unit
) : Dialog(owner) {
    private val ui=Handler(Looper.getMainLooper())
    private val analysisWorker=Executors.newSingleThreadExecutor()
    private val replay=RadarReplayState()
    private var session: AircatWifiSession?=null
    private var closingRecorder: WifiCsvRecorder?=null
    private var networkMessage="Επίλεξε σύνδεση στο SYNC ή χρήση του τρέχοντος Wi-Fi."
    private var pin="1234"
    private var tab=0
    private var chartMode=0
    private var closed=false
    private var generation=0
    private var analysisBusy=false
    private var reportRequested=false
    private var analysis: WifiWindowAnalysis?=null
    private var lastAnalysisMs=0L
    private var lastPacket: RadarPacket?=null
    private var previousNorth: Double?=null
    private var sectorEpoch=0.0
    private var sectorSeconds=.123625
    private var fonts=ArrayList<java.lang.ref.WeakReference<TextView>>()
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var radar: RadarView
    private lateinit var chart: ChartView
    private lateinit var monitor: TextView
    private lateinit var errors: TextView
    private lateinit var alerts: TextView
    private lateinit var panels: Array<View>
    private lateinit var chartToolbar: View
    private val connection=WifiNetworkConnection(owner,
        state={if(!closed)networkMessage=it},
        ready={network ->
            if(!closed) {
                stopSession();generation++;analysis=null;reportRequested=false;lastPacket=null;previousNorth=null
                replay.clear();radar.sweepAzimuthDegrees=Float.NaN
                chart.diagnostics=null;chart.desktopBars=null
                session=AircatWifiSession(bindSocket={network.bindSocket(it)},pin=pin).also{it.start()}
            }
        },lost={stopSession()})

    private fun dp(value: Int)=(value*owner.resources.displayMetrics.density+.5f).toInt()
    private fun label(text: String)=TextView(owner).apply {
        this.text=text;textSize=settings().uiSp;setTextColor(Color.rgb(19,57,81))
        setPadding(dp(7),dp(4),dp(7),dp(4));fonts.add(java.lang.ref.WeakReference(this))
    }
    private fun button(text: String,action: ()->Unit)=Button(owner).apply {
        this.text=text;textSize=settings().uiSp;isAllCaps=false
        minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
        setTextColor(Color.rgb(19,57,81));backgroundTintList=ColorStateList.valueOf(Color.rgb(189,230,250))
        setOnClickListener{action()};fonts.add(java.lang.ref.WeakReference(this))
    }
    private fun row(vararg children: View)=LinearLayout(owner).apply {
        orientation=LinearLayout.HORIZONTAL
        children.forEach{addView(it,LinearLayout.LayoutParams(0,dp(43),1f))}
    }
    private fun scroll(text: TextView)=ScrollView(owner).apply {addView(text);isFillViewport=true}

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=LinearLayout(owner).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(5),dp(5),dp(5),dp(5))
            setBackgroundColor(Color.rgb(224,243,253))
        }
        root.addView(label("AIRCAT500 Mobile v2.3.1 — Live Wi-Fi"))
        status=label(networkMessage).apply{maxLines=5}
        root.addView(status)
        root.addView(row(button("Σύνδεση"){credentials(false)},
            button("Τρέχον Wi-Fi"){credentials(true)},button("Stop"){disconnect()}))
        recordButton=button("CSV Record"){recordTapped()}
        root.addView(row(recordButton,button("Status"){showStatus()},button("Report"){requestAnalysis(true)}))
        root.addView(row(button("Φίλτρα"){openFilters()},button("Ρυθμίσεις"){openSettings()},
            button("Wi-Fi κινητού"){owner.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))},button("Κλείσιμο"){dismiss()}))
        root.addView(row(*arrayOf("Radar","Monitor","Errors","Alerts","Charts").mapIndexed { i,title ->
            button(title){showTab(i)}
        }.toTypedArray()))
        radar=RadarView(owner).apply {state=replay;onTrackTap=inspectTrack}
        val radarFrame=FrameLayout(owner)
        radarFrame.addView(radar,FrameLayout.LayoutParams(-1,-1))
        val zoom=LinearLayout(owner).apply {
            orientation=LinearLayout.VERTICAL
            addView(button("+"){radar.zoom*=1.25f},LinearLayout.LayoutParams(dp(48),dp(46)))
            addView(button("−"){radar.zoom/=1.25f},LinearLayout.LayoutParams(dp(48),dp(46)))
            addView(button("Reset"){radar.resetViewport()},LinearLayout.LayoutParams(dp(68),dp(46)))
        }
        radarFrame.addView(zoom,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        monitor=label("Αναμονή δεδομένων...").apply{setTextIsSelectable(true)}
        errors=label("Δεν υπάρχουν ακόμη μετρήσεις.").apply{setTextIsSelectable(true)}
        alerts=label("Δεν υπάρχουν ακόμη στόχοι.").apply{setTextIsSelectable(true)}
        chart=ChartView(owner)
        panels=arrayOf(radarFrame,scroll(monitor),scroll(errors),scroll(alerts),chart)
        val body=LinearLayout(owner)
        panels.forEach{body.addView(it,LinearLayout.LayoutParams(-1,-1))}
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        chartToolbar=row(button("Τύπος Chart"){
            chartMode=(chartMode+1)%5;chart.chartMode=chartMode
        },button("Zoom Peak"){chart.peakZoom()},button("Zoom Out"){chart.resetZoom()})
        root.addView(chartToolbar)
        root.addView(row(button("Current Scan RAW"){showRaw()}))
        setContentView(root)
        applySettings();showTab(0)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui.post(tick)
    }
    fun applySettings() {
        val s=settings()
        fonts.forEach{it.get()?.textSize=s.uiSp}
        fonts.removeAll{it.get()==null}
        radar.filter=s.filter;radar.maxRangeNm=s.rangeNm;radar.maxRingNm=s.ringsNm
        radar.labelSizeSp=s.labelSp;radar.uiFontSp=s.uiSp
        replay.trailDepth=s.historyDepth;chart.uiFontSp=s.uiSp
    }
    fun permissionResult(granted: Boolean){connection.permissionResult(granted)}

    private fun credentials(manual: Boolean) {
        val box=LinearLayout(owner).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(5),dp(16),dp(5))}
        box.addView(label(if(manual)"Σύνδεσε πρώτα το κινητό στο SYNC από τις ρυθμίσεις Wi-Fi." else "Το Android θα ζητήσει επιβεβαίωση σύνδεσης στο SYNC, χωρίς Internet."))
        val password=EditText(owner).apply {
            hint="Κωδικός Wi-Fi SYNC";textSize=settings().uiSp
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        if(!manual)box.addView(password)
        val inputPin=EditText(owner).apply {
            hint="PIN εφαρμογής";setText(pin);textSize=settings().uiSp
            inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters=arrayOf(InputFilter.LengthFilter(4))
        }
        box.addView(inputPin)
        val dialog=AlertDialog.Builder(owner).setTitle("SYNC2WIFI").setView(box)
            .setPositiveButton("Σύνδεση",null).setNegativeButton("Άκυρο",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered=inputPin.text.toString()
                if(!Regex("[0-9]{4}").matches(entered)){inputPin.error="Χρειάζονται 4 ψηφία";return@setOnClickListener}
                if(!manual && password.text.length !in 8..63){password.error="8–63 χαρακτήρες";return@setOnClickListener}
                pin=entered
                val pass=password.text.toString()
                dialog.dismiss()
                if(manual)connection.useCurrent() else connection.automatic(pass)
            }
        }
        dialog.show()
    }

    private fun stopSession() {
        session?.let{it.close();closingRecorder=it.recorder}
        session=null
    }
    private fun disconnect(){connection.close();stopSession();networkMessage="Η λήψη σταμάτησε"}
    private fun recordTapped() {
        val record=session?.recorder ?: closingRecorder
        if(record!=null && !record.finished){record.stop();return}
        if(session?.snapshot?.running!=true){showText("CSV Record","Συνδέσου πρώτα στη συσκευή.");return}
        requestCsv()
    }
    fun startRecording(uri: Uri) {
        try {
            val s=session ?: error("Η λήψη έχει σταματήσει")
            recordCsv(uri,s)
        } catch(ex: Exception){showText("CSV Record",ex.message ?: "Σφάλμα εγγραφής")}
    }

    private val tick=object : Runnable {
        override fun run() {
            if(closed || !isShowing)return
            val s=session
            if(s!=null) {
                val packets=s.frames.drain()
                for(p in packets) {
                    replay.apply(p);lastPacket=p
                    if(p.kind==0x19) {
                        previousNorth?.let { previous ->
                            val period=p.epochSeconds-previous
                            if(period in 1.0..20.0)sectorSeconds=period/32.0
                        }
                        previousNorth=p.epochSeconds
                    }
                    if(p.sector!=null || p.kind==0x19)sectorEpoch=p.epochSeconds
                }
                if(lastPacket!=null) {
                    val elapsed=(System.currentTimeMillis()/1000.0-sectorEpoch).coerceIn(0.0,sectorSeconds)
                    radar.sweepAzimuthDegrees=(((replay.sector ?: 0)+elapsed/sectorSeconds)*11.25).toFloat()
                }
                val snap=s.snapshot
                status.text="${snap.message}\n${snap.transport.firmwareLabel} · CLOCK ${snap.transport.clockHz} Hz\nCRC ${snap.bsc.crcErrors} · Framing ${snap.bsc.framingErrors} · UDP lost ${snap.transport.lost}"
                if(tab==1)monitor.text=monitorText(snap)
                if(tab==2)errors.text=errorText(snap)+(analysis?.let{"\n\nΠΡΟΣΦΑΤΟ ΠΑΡΑΘΥΡΟ\n${it.errors}"} ?: "")
                if(tab==3 && analysis!=null)alerts.text="ΠΡΟΣΦΑΤΟ ΠΑΡΑΘΥΡΟ\n${analysis!!.alerts}"
                if(tab>=2 && SystemClock.elapsedRealtime()-lastAnalysisMs>=5000 && !analysisBusy)requestAnalysis()
            } else status.text=networkMessage
            val record=s?.recorder ?: closingRecorder
            recordButton.text=when {
                record==null || record.finished -> "CSV Record"
                record.isAccepting() -> "REC ${record.rows} · Stop"
                else -> "Αποθήκευση..."
            }
            recordButton.isEnabled=record==null || record.finished || record.isAccepting()
            record?.error?.let{status.append("\n$it")}
            ui.postDelayed(this,100)
        }
    }

    private fun showTab(index: Int) {
        tab=index
        panels.forEachIndexed{i,v->v.visibility=if(i==index)View.VISIBLE else View.GONE}
        chartToolbar.visibility=if(index==4)View.VISIBLE else View.GONE
        if(index>=2)requestAnalysis()
    }
    private fun monitorText(s: WifiSessionSnapshot): String {
        val t=s.transport;val b=s.bsc
        return "${t.firmwareLabel}\nSYNC · 192.168.4.1 · CLOCK ${t.clockHz} Hz\n"+
            "UDP received ${t.received} · duplicates ${t.duplicates} · lost ${t.lost}\n"+
            "UDP invalid ${t.invalid} · CRC32 bad ${t.badCrc32}\n"+
            "FW overflow ${t.firmwareOverflow} · since connect +${t.overflowSinceConnect}\n"+
            "BSC bytes ${b.bytes} · sync ${b.sync} · valid ${b.valid}\n"+
            "NORTH ${b.north} · sectors ${b.sectors} · extensions ${b.extensions}\n"+
            "CRC errors ${b.crcErrors} · framing errors ${b.framingErrors}\n"+
            "Cycles ${b.completed} · perfect 32/32 ${b.perfect} · current ${b.currentUnique ?: "—"}/32\n"+
            "Display updates skipped ${session?.frames?.displayDropped ?: 0} (CSV writes independently)\n\n"+
            (lastPacket?.let{"Frame #${it.frameNo}\n"+it.payload.take(160).joinToString(" "){v->"%02X".format(v.toInt() and 255)}} ?: "Αναμονή πρώτου CRC-valid frame")
    }
    private fun errorText(s: WifiSessionSnapshot): String =
        "ΜΕΤΡΗΤΕΣ ΟΛΗΣ ΤΗΣ ΣΥΝΔΕΣΗΣ\nCRC ${s.bsc.crcErrors} · Framing ${s.bsc.framingErrors}\n"+
        "Missing sectors — clean cycles: ${s.bsc.missingClean}\n"+
        "Missing sectors — CRC-uncertain cycles: ${s.bsc.missingUncertain}\n"+
        "Duplicate sectors ${s.bsc.duplicates} · order errors ${s.bsc.orderErrors}\n"+
        "UDP lost ${s.transport.lost} · CRC32 bad ${s.transport.badCrc32} · invalid ${s.transport.invalid}\n"+
        "FW overflow +${s.transport.overflowSinceConnect} · link resets ${s.transport.linkResets}\n"+
        "Οι μετρητές δεν αθροίζονται ως ανεξάρτητες απώλειες.\n${s.bsc.lastError}"

    private fun showStatus() {
        val snap=session?.snapshot
        showText("Wi-Fi Status",if(snap==null)networkMessage else monitorText(snap)+"\n\n"+errorText(snap))
    }
    private fun requestAnalysis(openReport: Boolean=false) {
        if(openReport)reportRequested=true
        if(analysisBusy)return
        val packets=session?.frames?.recent().orEmpty()
        if(packets.isEmpty()) {
            if(reportRequested){reportRequested=false;showText("Wi-Fi Report","Δεν υπάρχουν ακόμη CRC-valid frames.")}
            return
        }
        analysisBusy=true;lastAnalysisMs=SystemClock.elapsedRealtime()
        val token=generation
        analysisWorker.execute {
            try {
                val capture=WifiRecentCapture.from(packets)
                val d=AircatAnalysis.analyze(capture)
                val result=WifiWindowAnalysis(capture,d,AircatDesktopSummary.sectorBars(capture,d),
                    AircatDesktopSummary.errors(capture,d),AircatDesktopSummary.alerts(capture,d),
                    AircatDesktopSummary.report(capture,d))
                ui.post {
                    analysisBusy=false
                    if(closed || token!=generation)return@post
                    analysis=result;chart.diagnostics=d;chart.desktopBars=result.bars
                    if(reportRequested){reportRequested=false;showLiveReport(result)}
                }
            } catch(ex: Exception) {
                ui.post { analysisBusy=false;if(!closed && reportRequested){reportRequested=false;showText("Wi-Fi Report","${ex.message}")} }
            }
        }
    }
    private fun showLiveReport(result: WifiWindowAnalysis) {
        val s=session?.snapshot
        val text="AIRCAT500 Mobile v2.3.1 — WiFi\n${s?.transport?.firmwareLabel ?: "Firmware: μη διαθέσιμη"}\n"+
            "Ανάλυση των τελευταίων ${result.capture.packets.size} CRC-valid frames, έως ${result.capture.packets.last().epochSeconds}s.\n"+
            "Το πλήρες ιστορικό αποθηκεύεται μόνο όταν ενεργοποιήσεις CSV Record.\n\n"+
            (s?.let{errorText(it)+"\n\n"} ?: "")+result.report.substringAfter('\n')
        val output=label(text).apply{setTextIsSelectable(true)}
        AlertDialog.Builder(owner).setTitle("Wi-Fi — πρόσφατο παράθυρο")
            .setView(scroll(output)).setPositiveButton("Κλείσιμο",null)
            .setNeutralButton("Αποθήκευση TXT"){_,_->saveText(text)}
            .setNegativeButton("Share"){_,_->share(text)}.show()
    }
    private fun showRaw() {
        val packets=session?.frames?.recent().orEmpty()
        if(packets.isEmpty()){showText("Current Scan RAW","Δεν υπάρχουν ακόμη frames.");return}
        val first=packets.indexOfLast{it.kind==0x19}.coerceAtLeast(0)
        val text=buildString {
            appendLine("CURRENT LIVE SCAN — CRC-valid payloads")
            for(p in packets.subList(first,packets.size)) {
                appendLine("Frame ${p.frameNo} TYPE ${"%02X".format(p.kind)} SECTOR ${p.sector ?: "—"}")
                appendLine(p.payload.joinToString(" "){"%02X".format(it.toInt() and 255)})
            }
        }
        showText("Current Scan RAW",text)
    }
    private fun share(text: String) {
        owner.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type="text/plain";putExtra(Intent.EXTRA_TEXT,text)
        },"Κοινοποίηση Wi-Fi report"))
    }
    private fun showText(title: String,text: String) {
        AlertDialog.Builder(owner).setTitle(title).setView(scroll(label(text).apply{setTextIsSelectable(true)}))
            .setPositiveButton("Κλείσιμο",null).show()
    }
    override fun dismiss() {
        if(!closed) {
            closed=true;generation++;ui.removeCallbacks(tick)
            connection.close();stopSession();analysisWorker.shutdownNow()
        }
        super.dismiss()
    }
}
