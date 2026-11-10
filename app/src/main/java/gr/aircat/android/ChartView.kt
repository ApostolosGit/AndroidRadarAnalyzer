package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale
import kotlin.math.max

/** Mobile-friendly version of the Python error/sector histogram. */
class ChartView(context: Context) : View(context) {
    var diagnostics: CaptureDiagnostics? = null
        set(value) { field = value; invalidate() }
    var chartMode = 0
        set(value) { field = value; invalidate() }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun dp(n: Float) = n*resources.displayMetrics.density
    private fun sp(n: Float) = n*resources.displayMetrics.scaledDensity
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(235,237,239))
        val stats=diagnostics ?: return
        val series = when (chartMode) {
            1 -> stats.missingCounts.toList().mapIndexed { i,v -> "%02X".format(Locale.US,i) to v }
            2 -> stats.typeCounts.toList().map { "0x%02X".format(Locale.US,it.first) to it.second }
            3 -> stats.alerts.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }
            else -> stats.sectorCounts.toList().mapIndexed { i,v -> "%02X".format(Locale.US,i) to v }
        }
        val title = listOf("SECTOR PACKETS", "MISSING SECTORS", "AIRCAT TYPES", "ALERT EVENTS")[chartMode.coerceIn(0,3)]
        brush.color = Color.rgb(22,27,29)
        brush.textSize = sp(15f)
        brush.style = Paint.Style.FILL
        canvas.drawText(title,dp(12f),dp(29f),brush)
        if (series.isEmpty()) return
        val left=dp(14f)
        val right=width-dp(14f)
        val bottom=height-dp(52f)
        val top=dp(58f)
        val tall=(bottom-top).coerceAtLeast(15f)
        val maxY=max(1,series.maxOf { it.second })
        val step=(right-left)/series.size
        brush.strokeWidth=dp(1f)
        brush.color=Color.rgb(190,195,199)
        for (n in 0..4) {
            val y=bottom-tall*n/4f
            canvas.drawLine(left,y,right,y,brush)
            brush.color=Color.rgb(36,39,42);brush.textSize=sp(9f)
            canvas.drawText("${maxY*n/4}",left,y-dp(3f),brush)
            brush.color=Color.rgb(190,195,199)
        }
        for ((i,item) in series.withIndex()) {
            val bar=(item.second.toFloat()/maxY)*tall
            brush.color= if (chartMode==1) Color.rgb(182,87,68) else Color.rgb(53,110,119)
            brush.style=Paint.Style.FILL
            canvas.drawRect(left+i*step+dp(.3f),bottom-bar,
                left+(i+1)*step-dp(.6f),bottom,brush)
            if (series.size<=8 || i%4==0) {
                brush.color=Color.rgb(20,23,25)
                brush.textSize=sp(if(series.size<=8)9f else 8f)
                canvas.drawText(item.first.take(12),left+i*step,bottom+dp(16f),brush)
            }
        }
        brush.color=Color.rgb(20,23,25)
        brush.textSize=sp(11f)
        canvas.drawText("Σύνολο: ${series.sumOf { it.second }}",dp(12f),height-dp(10f),brush)
    }
}
