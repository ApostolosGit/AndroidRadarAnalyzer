package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** AIRCAT500 sector histogram modeled on the desktop stacked bars (Zoom Peak on by default). */
class ChartView(context: Context) : View(context) {
    var diagnostics: CaptureDiagnostics? = null
        set(value) { field = value; invalidate() }
    var captureName: String = ""
        set(value) { field = value; invalidate() }
    var chartMode = 0
        set(value) { field = value; invalidate() }
    var zoomPeak = true
        set(value) { field = value; invalidate() }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private val navy = Color.rgb(20, 76, 122)
    private val correct = Color.rgb(31, 119, 180)
    private val missing = Color.rgb(214, 32, 40)
    private val previous = Color.rgb(236, 74, 161)
    private fun dp(n: Float) = n * resources.displayMetrics.density
    private fun sp(n: Float) = n * resources.displayMetrics.scaledDensity
    private fun text(canvas: Canvas, str: String, x: Float, y: Float, color: Int, size: Float) {
        brush.style = Paint.Style.FILL; brush.color = color; brush.textSize = sp(size)
        canvas.drawText(str, x, y, brush)
    }
    private fun background(canvas: Canvas, title: String) {
        canvas.drawColor(Color.rgb(241, 246, 251))
        text(canvas, title, dp(9f), dp(23f), navy, 12.5f)
        if (captureName.isNotEmpty()) text(canvas, captureName.take(50), dp(9f), dp(41f), navy, 10f)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stats = diagnostics ?: run { background(canvas, "Άνοιξε αρχείο RADAR"); return }
        if (chartMode != 0) { simpleChart(canvas, stats); return }
        background(canvas, "Radar Sector Messages • ${if (zoomPeak) "ZOOM PEAK" else "ZOOM OUT"}")
        // Same desktop series: valid NORTH cycles are the baseline for each sector.
        val labels = listOf("NORTH", "EXT", "WX") + (0..31).map { "%02X".format(Locale.US,it) }
        val blue = listOf(stats.validCycles,
            stats.typeCounts[0x0D] ?: 0, stats.typeCounts[0x2A] ?: 0) + List(32) { stats.validCycles }
        val red = listOf(stats.invalidCycles, 0, 0) + stats.missingCounts.toList()
        val pink = listOf(0, 0, 0) + stats.sectorWithoutNorthCounts.toList()
        val totals = blue.indices.map { blue[it] + red[it] + pink[it] }
        val left = dp(36f); val right = width-dp(14f)
        val top = dp(99f); val bottom = height-dp(79f)
        val plotHeight = (bottom-top).coerceAtLeast(5f)
        val sectorMin = (3 until totals.size).map { totals[it] }.filter { it > 0 }.minOrNull() ?: 0
        val peak = totals.maxOrNull() ?: 0
        val lower = if (zoomPeak && sectorMin > 0) max(0, (sectorMin*.95).toInt()) else 0
        val upper = max(lower + 1, if (zoomPeak) (peak*1.02).toInt()+1 else peak+1)
        val span = (upper - lower).toFloat()
        fun ypos(value: Int) = bottom - (value - lower)/span * plotHeight
        val grid = Color.rgb(199, 211, 220)
        for (n in 0..5) {
            val value = lower + (upper-lower)*n/5
            val y = ypos(value)
            brush.style=Paint.Style.STROKE; brush.strokeWidth=dp(.7f);brush.color=grid
            canvas.drawLine(left,y,right,y,brush)
            text(canvas,"$value",dp(1f),y+dp(3f),navy,9f)
        }
        // Legend retained from Python: blue correct, red missing, pink without NORTH.
        val legend = listOf("Correct" to correct,"Sector missing" to missing,"Without NORTH" to previous)
        var lx = dp(9f)
        for ((label,color) in legend) {
            brush.style=Paint.Style.FILL; brush.color=color
            canvas.drawRect(lx,dp(56f),lx+dp(10f),dp(66f),brush)
            text(canvas,label,lx+dp(13f),dp(65f),navy,9f)
            lx += dp(if (label == "Correct") 75f else 103f)
        }
        val barWidth = (right-left)/labels.size
        for (i in labels.indices) {
            val x = left+i*barWidth+dp(.8f)
            val x2 = left+(i+1)*barWidth-dp(1f)
            val v0=blue[i]; val v1=v0+red[i]; val v2=v1+pink[i]
            fun segment(y0: Int, y1: Int, color: Int) {
                if (y1 <= lower || y0 >= upper || y1 <= y0) return
                val upperY = ypos(min(y1, upper)); val lowerY = ypos(max(y0, lower))
                brush.style=Paint.Style.FILL;brush.color=color
                canvas.drawRect(x,upperY,x2,lowerY,brush)
            }
            segment(0,v0,correct);segment(v0,v1,missing);segment(v1,v2,previous)
            // Rotate values as on the desktop zoom-peak figure.
            if (v2 > 0 && x2-x > dp(12f)) {
                canvas.save();canvas.rotate(-90f,(x+x2)/2,ypos(max(lower,v2))-dp(5f))
                text(canvas,"$v2",(x+x2)/2-dp(7f),ypos(max(lower,v2))-dp(5f),navy,9f)
                canvas.restore()
            }
            canvas.save();canvas.rotate(-55f,x+dp(6f),bottom+dp(10f))
            text(canvas, labels[i], x+dp(4f),bottom+dp(10f),navy,8f)
            canvas.restore()
        }
        text(canvas, "Count in file", dp(4f), dp(88f), navy, 9f)
        text(canvas, "Σύρε οριζόντια • Zoom Peak = από 95% του ελάχιστου sector",dp(9f),height-dp(12f),navy,10f)
    }

    private fun simpleChart(canvas: Canvas, stats: CaptureDiagnostics) {
        val series = when (chartMode) {
            1 -> stats.missingCounts.mapIndexed { i,v -> "%02X".format(Locale.US,i) to v }
            2 -> stats.typeCounts.map { "0x%02X".format(Locale.US,it.key) to it.value }
            else -> AircatSummaries.alertRows(stats).map { it.name to it.total }
        }
        val title = when(chartMode) { 1->"MISSING SECTORS";2->"AIRCAT TYPES";else->"ALERT COUNTS" }
        background(canvas,title)
        if (series.isEmpty()) return
        val left=dp(35f);val right=width-dp(14f);val top=dp(66f);val bottom=height-dp(65f)
        val heightPlot=(bottom-top).coerceAtLeast(10f)
        val maxY=max(1,series.maxOf { it.second })
        val widthBar=(right-left)/series.size
        for(n in 0..5) {
            val y=bottom-heightPlot*n/5f
            brush.style=Paint.Style.STROKE;brush.color=Color.rgb(199,211,220)
            canvas.drawLine(left,y,right,y,brush)
            text(canvas,"${maxY*n/5}",dp(2f),y+dp(3f),navy,9f)
        }
        for((i,item) in series.withIndex()) {
            val bar=(item.second.toFloat()/maxY)*heightPlot
            brush.style=Paint.Style.FILL;brush.color=if(chartMode==1) missing else correct
            canvas.drawRect(left+i*widthBar+dp(.5f),bottom-bar,left+(i+1)*widthBar-dp(.5f),bottom,brush)
            if(series.size<=10 || i%2==0) {
                canvas.save();canvas.rotate(-55f,left+(i+.5f)*widthBar,bottom+dp(8f))
                text(canvas,item.first.take(12),left+(i+.5f)*widthBar,bottom+dp(8f),navy,8f)
                canvas.restore()
            }
        }
    }
}
