package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

/** v1.0 high-contrast native Android radar; smooth sweep, alerts, trails, pan/zoom, tap target. */
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(33, 155, 57, 61) }
    private val background = Color.rgb(235, 237, 239)
    private val foreground = Color.rgb(39, 43, 47)
    private val ringColor = Color.rgb(162, 45, 51)
    private val ringMinor = Color.rgb(211, 148, 152)
    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var filter: RadarFilter = RadarFilter()
        set(value) { field = value; invalidate() }
    var sweepAzimuthDegrees: Float = Float.NaN
        set(value) { field = value; invalidate() }
    var coordinateDivisor = 32.0
        set(value) { field = value; invalidate() }
    // At 12.5x the reference radar radius shows 20 NM.
    var zoom = 12.5f
        set(value) { field = value.coerceIn(.2f, 125f); invalidate() }
    var trailHistoryLength = 16
        set(value) { field = value.coerceIn(1, 64); invalidate() }
    var onTrackTap: ((RadarTrack) -> Unit)? = null
    var selectedTrackNumber: Int? = null
        set(value) { field = value; invalidate() }
    private var panX = 0f
    private var panY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var dragging = false
    private val hitPoints = ArrayList<Pair<RadarTrack, Pair<Float, Float>>>()
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val before = zoom
            zoom *= detector.scaleFactor
            val factor = zoom / before
            panX = detector.focusX - width / 2f - (detector.focusX - width / 2f - panX) * factor
            panY = detector.focusY - height / 2f - (detector.focusY - height / 2f - panY) * factor
            invalidate()
            return true
        }
    })
    private fun dp(n: Float) = n * resources.displayMetrics.density
    private fun sp(n: Float) = n * resources.displayMetrics.scaledDensity
    private fun color(hex: String) = Color.parseColor(hex)

    fun resetViewport() { panX = 0f; panY = 0f; zoom = 12.5f; invalidate() }
    private fun pointFor(track: RadarTrack, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val range = 250.0 * coordinateDivisor
        return Pair(cx + panX + ((track.xRaw.toDouble() / range) * radius * zoom).toFloat(),
            cy + panY - ((track.yRaw.toDouble() / range) * radius * zoom).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * 0.455f, (height - dp(34f)) * 0.46f)
        val ox = cx + panX
        val oy = cy + panY
        val pxPerNm = radius * zoom / 250f
        val visibleRangeNm = 250f / zoom
        val ringStep = when {
            visibleRangeNm <= 30f -> 5
            visibleRangeNm <= 70f -> 10
            visibleRangeNm <= 150f -> 20
            visibleRangeNm <= 400f -> 25
            else -> 50
        }
        val majorEvery = when {
            ringStep <= 5 -> 10
            ringStep <= 10 -> 20
            ringStep <= 25 -> 50
            else -> 100
        }
        // Use one world scale for targets and rings. Draw beyond 250NM if a ring
        // intersects the viewport after pan/zoom; never crop at the centre disc.
        val farCorner = max(
            max(hypot(ox.toDouble(), oy.toDouble()), hypot((width-ox).toDouble(), oy.toDouble())),
            max(hypot(ox.toDouble(), (height-oy).toDouble()),
                hypot((width-ox).toDouble(), (height-oy).toDouble()))
        )
        val ringLimit = min(500, (farCorner / pxPerNm / ringStep).toInt() + 1)
        for (ringIndex in 1..ringLimit) {
            val nm = ringIndex * ringStep
            val isMajor = nm % majorEvery == 0
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (isMajor) 1.3f else 0.75f)
            paint.color = if (isMajor) ringColor else ringMinor
            val rr = pxPerNm * nm
            canvas.drawCircle(ox, oy, rr, paint)
            if (isMajor) {
                // Pick a label position on the visible arc, even with origin offscreen.
                var bestScore = Float.POSITIVE_INFINITY
                var bestX = 0f
                var bestY = 0f
                for (sample in 0..95) {
                    val angle = sample * (Math.PI * 2.0 / 96.0)
                    val x = ox + (rr * cos(angle)).toFloat()
                    val y = oy + (rr * sin(angle)).toFloat()
                    if (x < dp(15f) || x > width-dp(55f) ||
                        y < dp(12f) || y > height-dp(18f)) continue
                    val score = abs(x-width*.72f) + abs(y-height*.38f)
                    if (score < bestScore) { bestScore=score; bestX=x; bestY=y }
                }
                if (bestScore.isFinite()) {
                    paint.style = Paint.Style.FILL
                    paint.textSize = sp(9f)
                    paint.color = ringColor
                    canvas.drawText(nm.toString()+" NM", bestX+dp(2f), bestY-dp(2f), paint)
                }
            }
        }
        // Axes extend across the screen, rather than stopping at the 250NM ring.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(.6f)
        paint.color = Color.rgb(156, 159, 161)
        if (oy >= 0f && oy <= height) canvas.drawLine(0f,oy,width.toFloat(),oy,paint)
        if (ox >= 0f && ox <= width) canvas.drawLine(ox,0f,ox,height.toFloat(),paint)
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = sp(11f)
        if (ox in 0f..width.toFloat()) canvas.drawText("N", ox+dp(3f), dp(14f), paint)
        val current = state ?: return
        val bearing = if (sweepAzimuthDegrees.isNaN()) (current.sector ?: 0) * 11.25f else sweepAzimuthDegrees
        canvas.drawArc(RectF(ox-cross, oy-cross, ox+cross, oy+cross),
            bearing - 90f, 11.25f, true, sectorShade)
        val radians = Math.toRadians((bearing-90f).toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = color("#70F0AB")
        canvas.drawLine(ox, oy, ox + (cross*cos(radians)).toFloat(),
            oy + (cross*sin(radians)).toFloat(), paint)
        hitPoints.clear()
        val tracks = current.allTracks().filter { filter.allows(it) }
        if (showTrails && filter.showTrails) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.8f)
            paint.color = Color.argb(95, 108, 240, 170)
            for ((_, trail) in current.trackTrail) {
                val visibleTrail = trail.filter { filter.allows(it) }
                if (visibleTrail.size < 2) continue
                var previous = pointFor(visibleTrail.first(), cx, cy, radius)
                for (track in visibleTrail.drop(1)) {
                    val point = pointFor(track,cx,cy,radius)
                    canvas.drawLine(previous.first, previous.second, point.first,point.second,paint)
                    previous = point
                }
            }
        }
        val labelAreas = ArrayList<RectF>()
        for ((index, track) in tracks.withIndex()) {
            if (hypot(track.xRaw.toDouble(),track.yRaw.toDouble()) > 250.0*coordinateDivisor*1.1) continue
            val (x,y) = pointFor(track,cx,cy,radius)
            if (x < -dp(5f) || x > width+dp(5f) || y < -dp(5f) || y > height+dp(5f)) continue
            paint.style = Paint.Style.FILL
            val selected = track.number == selectedTrackNumber
            paint.color = when {
                track.alert -> color("#FF6C6C")
                selected -> Color.WHITE
                track.cancelled -> color("#A0A0A0")
                track.isLowQuality -> color("#91AE9F")
                track.combined -> color("#6FD8FF")
                track.primary -> color("#FFD17B")
                else -> color("#70EFAB")
            }
            canvas.drawCircle(x,y,dp(if(selected || track.alert) 4.6f else 3.1f),paint)
            hitPoints.add(track to (x to y))
            if (filter.showLabels && (tracks.size <= 35 || selected || track.alert || index % 6 == 0)) {
                paint.textSize = sp(10f)
                val label = buildString {
                    append(track.number)
                    if (track.modeA != null) append("/"+track.modeA)
                    if (track.alert) append(" !")
                }
                val w = paint.measureText(label)
                val lx = if (x+w+dp(9f) < width) x+dp(6f) else x-w-dp(7f)
                val ly = y - dp(5f)
                val area = RectF(lx,ly-paint.textSize,lx+w,ly+dp(4f))
                if (lx >= 0 && ly > paint.textSize && (selected || labelAreas.none { RectF.intersects(it,area) })) {
                    canvas.drawText(label,lx,ly,paint)
                    labelAreas.add(area)
                }
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = sp(10f)
        canvas.drawText("${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: "--"}   ${if (zoom > 1f) "Zoom %.1f×".format(zoom) else "250 NM"}",
            dp(8f), height-dp(12f), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX = event.x; touchY = event.y; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !pinch.isInProgress) {
                    val dx = event.x - touchX
                    val dy = event.y - touchY
                    if (abs(dx)>dp(3f) || abs(dy)>dp(3f)) dragging = true
                    if (dragging) { panX += dx; panY += dy; invalidate() }
                    touchX=event.x; touchY=event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !pinch.isInProgress) {
                    val closest=hitPoints.minByOrNull { (_,p) -> hypot((p.first-event.x).toDouble(),(p.second-event.y).toDouble()) }
                    if (closest != null && hypot((closest.second.first-event.x).toDouble(),
                        (closest.second.second-event.y).toDouble()) <= dp(20f)) {
                        selectedTrackNumber = closest.first.number
                        onTrackTap?.invoke(closest.first)
                    }
                }
                performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
