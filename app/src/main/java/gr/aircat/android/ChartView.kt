package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * v2 mobile chart. First page reproduces the desktop three-colour stacked
 * North/Extension/Weather/00..1F plot, including zoom peak and zoom out.
 * Horizontal dragging and pinch-zoom make 35 columns readable on a phone.
 */
class ChartView(context: Context) : View(context) {
    var diagnostics: CaptureDiagnostics? = null
        set(value) { field = value; invalidate() }
    var desktopBars: DesktopSectorBars? = null
        set(value) { field = value; scrollBars = 0f; invalidate() }
    var chartMode = 0
        set(value) { field = value; scrollBars = 0f; invalidate() }
    var zoomPeak = true
        set(value) { field = value; invalidate() }
    private var horizontalScale = 1f
    private var scrollBars = 0f
    private var dragX = 0f
    var uiFontSp = 13f
        set(value) { field = value.coerceIn(13f,16f); invalidate() }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            horizontalScale = (horizontalScale * detector.scaleFactor).coerceIn(1f, 8f)
            invalidate()
            return true
        }
    })
    private fun dp(n: Float) = n * resources.displayMetrics.density
    private fun sp(n: Float) = max(n,uiFontSp) * resources.displayMetrics.scaledDensity

    fun resetZoom() { horizontalScale=1f; scrollBars=0f; zoomPeak=false; invalidate() }
    fun peakZoom() { zoomPeak=true; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(245,250,253))
        val stats = diagnostics ?: return
        val desktop = if (chartMode==0) desktopBars else null
        val data: List<Pair<String,Int>> = when (chartMode) {
            1 -> stats.sectorCounts.toList().mapIndexed { i,v -> "%02X".format(Locale.US,i) to v }
            2 -> stats.missingCounts.toList().mapIndexed { i,v -> "%02X".format(Locale.US,i) to v }
            3 -> stats.typeCounts.toList().map { "0x%02X".format(Locale.US,it.first) to it.second }
            4 -> stats.alerts.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }
            else -> desktop?.labels?.mapIndexed { i,x -> x to desktop.totals[i] } ?: emptyList()
        }
        if (data.isEmpty()) {
            brush.color = Color.DKGRAY; brush.textSize=sp(12f)
            canvas.drawText("Δεν υπάρχουν δεδομένα για διάγραμμα.", dp(12f), dp(34f), brush)
            return
        }
        val isStacked = chartMode==0 && desktop!=null
        val maxY = max(1, data.maxOf { it.second })
        val sectorVals = if (isStacked) data.drop(3).map { it.second } else data.map { it.second }
        val minY = if (zoomPeak && sectorVals.isNotEmpty()) floor((sectorVals.minOrNull() ?: 0)*0.95).toInt().coerceAtLeast(0) else 0
        val topY = max(maxY+1, (maxY * 1.02f).toInt()+1)
        val span = (topY-minY).coerceAtLeast(1)
        val plotLeft = dp(43f)
        val plotRight = width - dp(12f)
        val plotTop = dp(if (isStacked) 104f else 62f)
        val plotBottom = (height-dp(73f)).coerceAtLeast(plotTop+dp(40f))
        val plotWidth = (plotRight-plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom-plotTop).coerceAtLeast(1f)
        fun toY(value: Int): Float = plotBottom - ((value-minY).toFloat()/span)*plotHeight
        brush.style = Paint.Style.FILL
        brush.color=Color.rgb(21,54,79)
        brush.textSize=sp(12f)
        canvas.drawText(if (isStacked) "Counts from first valid North to last valid North"
                        else listOf("","SECTOR PACKETS","MISSING SECTORS","AIRCAT TYPES","ALERT EVENTS")[chartMode],
            dp(7f),dp(22f),brush)
        if (isStacked) {
            brush.textSize=sp(9f)
            val filename = desktop!!.fileName.takeLast(47)
            canvas.drawText(filename, dp(7f), dp(39f), brush)
            val names=listOf("Correct" to Color.rgb(31,119,180),
                "Sector missing" to Color.rgb(214,39,40),
                "Sector without prev North" to Color.rgb(255,105,180))
            var lx=dp(8f); var ly=dp(57f)
            for ((label,color) in names) {
                if (lx+dp(label.length*5f+31f) > width) { lx=dp(8f);ly+=dp(18f) }
                brush.color=color
                canvas.drawRect(lx,ly-dp(8f),lx+dp(13f),ly+dp(2f),brush)
                brush.color=Color.rgb(30,43,53);brush.textSize=sp(9f)
                canvas.drawText(label,lx+dp(17f),ly,brush)
                lx+=dp(label.length*5f+33f)
            }
        }
        brush.textSize=sp(9f)
        for (n in 0..5) {
            val level=minY+span*n/5
            val y=toY(level)
            brush.color=Color.rgb(205,217,224);brush.strokeWidth=dp(.7f)
            canvas.drawLine(plotLeft,y,plotRight,y,brush)
            brush.color=Color.rgb(47,65,76)
            canvas.drawText(level.toString(),dp(4f),y+dp(3f),brush)
        }
        val visible = (data.size / horizontalScale).coerceAtLeast(3.5f)
        scrollBars=scrollBars.coerceIn(0f,(data.size-visible).coerceAtLeast(0f))
        val unit = plotWidth/visible
        canvas.save()
        canvas.clipRect(plotLeft,plotTop,plotRight,plotBottom+dp(45f))
        for (i in data.indices) {
            val bx=plotLeft+(i-scrollBars)*unit
            if (bx+unit<plotLeft || bx>plotRight) continue
            val w=(unit*.75f).coerceAtLeast(dp(2f))
            val center=bx+unit/2f
            if (isStacked) {
                val blue=desktop!!.correct[i];val red=desktop.missing[i];val pink=desktop.withoutNorth[i]
                var heightSum=0
                for ((v,color) in listOf(blue to Color.rgb(31,119,180),red to Color.rgb(214,39,40),pink to Color.rgb(255,105,180))) {
                    val next=heightSum+v
                    val low=max(heightSum,minY)
                    val high=min(next,topY)
                    if (high>low) {
                        brush.style=Paint.Style.FILL;brush.color=color
                        canvas.drawRect(center-w/2f,toY(high),center+w/2f,toY(low),brush)
                    }
                    heightSum=next
                }
            } else {
                brush.style=Paint.Style.FILL
                brush.color=if (chartMode==2) Color.rgb(214,39,40) else Color.rgb(31,119,180)
                val value=data[i].second
                if (value>minY) canvas.drawRect(center-w/2f,toY(value),center+w/2f,plotBottom,brush)
            }
            brush.color=Color.rgb(20,45,60)
            brush.textSize=sp(if (unit<dp(19f)) 8f else 9f)
            // Rotate short sector names to keep all 32 categories recognizable.
            canvas.save()
            canvas.rotate(-55f,center,plotBottom+dp(13f))
            canvas.drawText(data[i].first.take(14),center,plotBottom+dp(13f),brush)
            canvas.restore()
            if (unit>=dp(18f) && data[i].second>0) {
                brush.color=Color.rgb(24,39,55);brush.textSize=sp(8f)
                canvas.drawText(data[i].second.toString(),center-w/2f,toY(data[i].second)-dp(3f),brush)
            }
        }
        canvas.restore()
        brush.color=Color.rgb(21,54,79);brush.textSize=sp(10f)
        canvas.drawText("Radar Sector Messages   •   σύρε οριζόντια / pinch zoom",
            dp(7f),height-dp(8f),brush)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragX=event.x; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount==1 && !pinch.isInProgress) {
                    val n=if (chartMode==0) 35 else diagnostics?.sectorCounts?.size ?: 32
                    val visible=(n/horizontalScale).coerceAtLeast(3.5f)
                    val unit=(width-dp(55f))/visible
                    if (abs(event.x-dragX)>dp(2f)) {
                        scrollBars=((scrollBars+(dragX-event.x)/unit)).coerceIn(0f,(n-visible).coerceAtLeast(0f))
                        invalidate()
                    }
                    dragX=event.x
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
