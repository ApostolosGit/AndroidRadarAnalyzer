package gr.aircat.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** v1.0 high-contrast native Android radar; smooth sweep, alerts, trails, pan/zoom, tap target. */
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(32, 190, 76, 76) }
    // Preserve the neutral grey radar appearance, with permanent red range rings.
    private val background = Color.rgb(235, 237, 239)
    private val foreground = Color.rgb(20, 20, 20)
    private val ringColor = Color.rgb(158, 49, 49)
    private val minorRingColor = Color.rgb(215, 142, 142)
    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var filter: RadarFilter = RadarFilter()
        set(value) { field = value; invalidate() }
    var sweepAzimuthDegrees: Float = Float.NaN
        set(value) { field = value; invalidate() }
    var coordinateDivisor = 32.0
        set(value) { field = value; invalidate() }
    // 250 NM is the original reference radius. A zoom of 12.5 means 20 NM.
    var zoom = 12.5f
        set(value) { field = value.coerceIn(0.5f, 50f); invalidate() }
    var showTrails = true
        set(value) { field = value; invalidate() }
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
            zoom *= detector.scaleFactor
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

    /** Choose a visible edge intersection even when the radar centre is off-screen. */
    private fun ringLabelPoint(cx: Float, cy: Float, radius: Float): Pair<Float, Float>? {
        val margin = dp(12f)
        val candidates = ArrayList<Pair<Float, Float>>()
        for (y in listOf(margin + dp(7f), height - margin - dp(10f))) {
            val term = radius * radius - (y - cy) * (y - cy)
            if (term >= 0) {
                val dx = sqrt(term)
                candidates.add(cx + dx to y)
                candidates.add(cx - dx to y)
            }
        }
        for (x in listOf(margin, width - margin - dp(30f))) {
            val term = radius * radius - (x - cx) * (x - cx)
            if (term >= 0) {
                val dy = sqrt(term)
                candidates.add(x to cy + dy)
                candidates.add(x to cy - dy)
            }
        }
        return candidates.firstOrNull { (x,y) ->
            x > margin && x < width - dp(48f) && y > dp(19f) && y < height - dp(25f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * 0.455f, (height - dp(34f)) * 0.46f)
        val ox = cx + panX
        val oy = cy + panY
        val pixelsPerNm = (radius * zoom / 250f).coerceAtLeast(0.01f)
        val visibleRangeNm = 250.0 / zoom
        // Draw every true-range circle which can intersect the view, not just
        // the four rings surrounding the origin. Pan and zoom share one scale.
        val maxDx = maxOf(abs(ox), abs(width - ox))
        val maxDy = maxOf(abs(oy), abs(height - oy))
        val farthestNm = hypot(maxDx.toDouble(), maxDy.toDouble()) / pixelsPerNm
        val spacing = RadarRangeGrid.spacingNm(visibleRangeNm)
        val rings = RadarRangeGrid.rings(visibleRangeNm, farthestNm)
        for (nm in rings) {
            val ringRadius = pixelsPerNm * nm
            val major = nm % (spacing * 2) == 0
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (major) 1.25f else 0.75f)
            paint.color = if (major) ringColor else minorRingColor
            canvas.drawCircle(ox, oy, ringRadius, paint)
            val label = ringLabelPoint(ox, oy, ringRadius)
            if (label != null && (major || rings.size <= 7)) {
                paint.style = Paint.Style.FILL
                paint.textSize = sp(9f)
                paint.color = background
                canvas.drawRect(label.first - dp(3f), label.second - paint.textSize,
                    label.first + dp(38f), label.second + dp(3f), paint)
                paint.color = if (major) ringColor else Color.rgb(165, 84, 84)
                canvas.drawText("$nm NM", label.first, label.second, paint)
            }
        }
        // The range grid is intentionally unbounded and remains visible when
        // the radar origin lies outside the viewport.
        val cross = radius * zoom
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(.8f)
        paint.color = Color.rgb(180, 180, 180)
        canvas.drawLine(ox - cross, oy, ox + cross, oy, paint)
        canvas.drawLine(ox, oy - cross, ox, oy + cross, paint)
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = sp(12f)
        if (ox in 0f..width.toFloat() && oy-cross > dp(12f)) {
            canvas.drawText("N", ox-dp(4f), oy-cross-dp(3f), paint)
        }
        val current = state ?: return
        val bearing = if (sweepAzimuthDegrees.isNaN()) (current.sector ?: 0) * 11.25f else sweepAzimuthDegrees
        canvas.drawArc(RectF(ox-cross, oy-cross, ox+cross, oy+cross),
            bearing - 90f, 11.25f, true, sectorShade)
        val radians = Math.toRadians((bearing-90f).toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = Color.rgb(171, 65, 65)
        canvas.drawLine(ox, oy, ox + (cross*cos(radians)).toFloat(),
            oy + (cross*sin(radians)).toFloat(), paint)
        hitPoints.clear()
        val tracks = current.allTracks().filter { filter.allows(it) }
        if (showTrails && filter.showTrails) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.8f)
            paint.color = Color.argb(132, 65, 65, 65)
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
        for (track in tracks) {
            if (track.cancellation) continue
            if (hypot(track.xRaw.toDouble(), track.yRaw.toDouble()) > 250.0 * coordinateDivisor * 1.1) continue
            val (x,y) = pointFor(track,cx,cy,radius)
            if (x < -dp(10f) || x > width+dp(10f) || y < -dp(10f) || y > height+dp(10f)) continue
            val selected = track.number == selectedTrackNumber
            // Requested monochrome aircraft symbology: MSSR Δ, Combined U, PSR ○.
            paint.color = Color.BLACK
            paint.style = Paint.Style.FILL
            paint.textSize = sp(if (selected) 17f else 14f)
            when {
                track.combined -> canvas.drawText("U", x - paint.measureText("U") / 2f, y + dp(5f), paint)
                track.primary -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.8f)
                    canvas.drawCircle(x, y, dp(if (selected) 5.7f else 4.5f), paint)
                }
                else -> canvas.drawText("Δ", x - paint.measureText("Δ") / 2f, y + dp(5f), paint)
            }
            if (track.alert || selected) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.1f)
                paint.color = if (track.alert) Color.rgb(192, 47, 47) else Color.DKGRAY
                canvas.drawCircle(x, y, dp(9f), paint)
            }
            hitPoints.add(track to (x to y))
            if (filter.showLabels) {
                // A/C labels are enabled by default on EVERY visible aircraft.
                val a = track.modeA ?: "----"
                val c = track.modeC ?: "----"
                val text = buildString {
                    append("#${track.number} A:$a C:$c")
                    if (filter.showRangeLabel) append(" %.1fNM".format(track.rangeNm32))
                    if (track.alert) append(" !")
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                paint.textSize = sp(9f)
                val labelWidth = paint.measureText(text)
                val labelHeight = paint.textSize
                val distance = dp(9f)
                val placements = listOf(
                    x + distance to y - dp(4f),
                    x + distance to y + dp(17f),
                    x - distance - labelWidth to y - dp(4f),
                    x - distance - labelWidth to y + dp(17f),
                    x - labelWidth / 2f to y - dp(17f),
                    x - labelWidth / 2f to y + dp(29f)
                )
                val location = placements.firstOrNull { (lx,ly) ->
                    val area = RectF(lx,ly-labelHeight,lx+labelWidth,ly+dp(3f))
                    lx >= dp(2f) && lx + labelWidth < width - dp(2f) &&
                        ly-labelHeight > dp(2f) && ly < height-dp(10f) &&
                        labelAreas.none { RectF.intersects(it,area) }
                } ?: placements.firstOrNull { (lx,ly) ->
                    lx >= 0 && lx+labelWidth < width && ly-labelHeight > 0 && ly < height
                }
                // Avoid dropping A/C labels because nearby aircraft overlap.
                if (location != null) {
                    canvas.drawText(text, location.first, location.second, paint)
                    labelAreas.add(RectF(location.first, location.second-labelHeight,
                        location.first+labelWidth,location.second+dp(3f)))
                }
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = sp(10f)
        canvas.drawText("${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: "--"}  •  Range %.1f NM".format(visibleRangeNm),
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
