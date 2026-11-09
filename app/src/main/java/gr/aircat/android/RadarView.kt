package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin

/** Lightweight native Canvas renderer. Coordinates reproduce the Windows sign convention. */
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 88, 211, 142) }
    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var sweepAzimuthDegrees: Float = Float.NaN
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
        val bearing = if (sweepAzimuthDegrees.isNaN())
            (current.sector ?: 0) * (360f / 32f) else sweepAzimuthDegrees
        val disk = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        // Sector cone and sweep line move smoothly between recorded packets.
        canvas.drawArc(disk, -90f + bearing, 360f / 32f, true, sectorShade)
        val radians = Math.toRadians((bearing - 90f).toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * resources.displayMetrics.density
        paint.color = color("#63EEA8")
        canvas.drawLine(cx, cy,
            cx + (radius * cos(radians)).toFloat(),
            cy + (radius * sin(radians)).toFloat(), paint)
        val tracks = current.allTracks()
        val labelAreas = ArrayList<RectF>()
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
                val labelWidth = paint.measureText(label)
                val lx = if (x + labelWidth + 9f < width) x + 7f else x - labelWidth - 7f
                val ly = y - 5f
                val area = RectF(lx, ly - paint.textSize, lx + labelWidth, ly + 3f)
                if (lx >= 0 && ly - paint.textSize >= 0 &&
                    labelAreas.none { RectF.intersects(it, area) }) {
                    canvas.drawText(label, lx, ly, paint)
                    labelAreas.add(area)
                }
            }
        }
        paint.color = color("#9AB6C0")
        paint.textSize = 11f * resources.displayMetrics.scaledDensity
        canvas.drawText("${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2, '0') ?: "--"}",
            13f, height - 16f, paint)
    }
}
