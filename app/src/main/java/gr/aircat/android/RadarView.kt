package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Lightweight native Canvas renderer. Coordinates reproduce the Windows sign convention. */
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 88, 211, 142) }
    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var coordinateDivisor = 32.0
        set(value) { field = value; invalidate() }
    private fun color(value: String) = Color.parseColor(value)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(color("#101820"))
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width.toFloat() * .45f, height.toFloat() * .45f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f
        paint.color = color("#35515A")
        for (ring in 1..4) canvas.drawCircle(cx, cy, radius * ring / 4f, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = color("#9AB6C0")
        paint.textSize = 13f * resources.displayMetrics.scaledDensity
        canvas.drawText("N", cx - 5f, cy - radius - 11f, paint)
        canvas.drawText("E", cx + radius + 7f, cy + 4f, paint)
        canvas.drawText("250 NM", cx + radius * .53f, cy - 7f, paint)

        val current = state ?: return
        current.sector?.let { sector ->
            val disk = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(disk, -90f + sector * (360f / 32), 360f / 32, true, sectorShade)
        }
        val tracks = current.allTracks()
        val clipRange = 250.0 * coordinateDivisor
        for ((index, track) in tracks.withIndex()) {
            if (kotlin.math.hypot(track.xRaw.toDouble(), track.yRaw.toDouble()) > clipRange) continue
            val x = cx + (track.xRaw / clipRange * radius).toFloat()
            val y = cy - (track.yRaw / clipRange * radius).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (track.primary) color("#F8CD63") else color("#63EEA8")
            canvas.drawCircle(x, y, 3.2f * resources.displayMetrics.density, paint)
            if (tracks.size < 30 || index % 7 == 0) {
                paint.textSize = 10f * resources.displayMetrics.scaledDensity
                val label = if (track.modeA != null) "${track.number}/${track.modeA}" else "${track.number}"
                canvas.drawText(label, x + 6f, y - 4f, paint)
            }
        }
        paint.color = color("#9AB6C0")
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        canvas.drawText("${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2, '0') ?: "--"}",
            13f, height - 16f, paint)
    }
}
