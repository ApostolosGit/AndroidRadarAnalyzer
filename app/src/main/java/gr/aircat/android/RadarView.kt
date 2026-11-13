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
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 63, 137, 196) }
    // Light grey radar with permanently drawn blue nautical-mile rings.
    private val background = Color.rgb(235, 237, 239)
    private val foreground = Color.rgb(20, 36, 52)
    private val ringColor = Color.rgb(36, 101, 182)
    private val minorRingColor = Color.rgb(127, 177, 225)
    private val fixedRangeRingsNm = listOf(5, 10, 15, 20, 50, 100, 150, 200)
    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var filter: RadarFilter = RadarFilter()
        set(value) { field = value; invalidate() }
    var sweepAzimuthDegrees: Float = Float.NaN
        set(value) { field = value; invalidate() }
    var coordinateDivisor = 32.0
        set(value) { field = value; invalidate() }
    // 250 NM is the original reference radius. A zoom of 12.5 means 20 NM.
    var maxRangeNm = 250f
        set(value) { field = value.coerceIn(20f, 1000f); zoom = zoom; invalidate() }
    var labelSizeSp = 9f
        set(value) { field = value.coerceIn(7f, 16f); invalidate() }
    var uiFontSp = 13f
        set(value) { field = value.coerceIn(13f, 16f); invalidate() }
    var zoom = 12.5f
        set(value) { field = value.coerceIn(250f / maxRangeNm, 50f); invalidate() }
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
    // Relative screen-pixel offset: label follows aircraft as it moves.
    private val labelOffsets = HashMap<Int, Pair<Float, Float>>()
    private val labelBounds = ArrayList<Pair<Int, RectF>>()
    private var movingLabelId: Int? = null
    private var movedLabel = false
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
        // Eight explicitly calibrated rings. Pan and zoom transform their
        // centre and radius exactly like the radar tracks.
        for (nm in fixedRangeRingsNm) {
            val ringRadius = pixelsPerNm * nm
            val major = nm in listOf(20, 50, 100, 150, 200)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (major) 1.25f else 0.8f)
            paint.color = if (major) ringColor else minorRingColor
            canvas.drawCircle(ox, oy, ringRadius, paint)
            // NM annotations lie on the X / Y axes, not on a free-floating
            // circle tangent; off-screen annotations are clipped.
            paint.style = Paint.Style.FILL
            paint.textSize = sp(labelSizeSp)
            val annotation = "$nm NM"
            val tw = paint.measureText(annotation)
            val points = listOf(
                (ox + ringRadius + dp(3f)) to (oy - dp(3f)),
                (ox - ringRadius - tw - dp(3f)) to (oy - dp(3f)),
                (ox + dp(4f)) to (oy - ringRadius - dp(3f)),
                (ox + dp(4f)) to (oy + ringRadius - dp(3f))
            )
            for ((tx,ty) in points) {
                if (tx >= dp(2f) && tx+tw < width-dp(2f) &&
                    ty > paint.textSize+dp(2f) && ty < height-dp(19f)) {
                    paint.color = background
                    canvas.drawRect(tx-dp(2f),ty-paint.textSize,
                        tx+tw+dp(2f),ty+dp(2f),paint)
                    paint.color = if (major) ringColor else Color.rgb(78,134,194)
                    canvas.drawText(annotation,tx,ty,paint)
                }
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
        paint.color = Color.rgb(38, 107, 180)
        canvas.drawLine(ox, oy, ox + (cross*cos(radians)).toFloat(),
            oy + (cross*sin(radians)).toFloat(), paint)
        hitPoints.clear()
        val tracks = current.allTracks().filter { filter.allows(it) }
        if (showTrails && filter.showTrails) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(.8f)
            paint.color = Color.argb(132, 65, 65, 65)
            val activeNumbers = tracks.map { it.number }.toSet()
            for ((number, trail) in current.trackTrail) {
                if (number !in activeNumbers) continue
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
        labelBounds.clear()
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
            if (filter.showVelocityVector && track.dx != null && track.dy != null) {
                // Relative vector cue from decoded dx/dy components. The vector
                // is not interpreted as calibrated airspeed or ground track.
                val vx = (track.dx.coerceIn(-32,32)) * dp(.7f)
                val vy = -(track.dy.coerceIn(-32,32)) * dp(.7f)
                if (vx*vx + vy*vy > dp(2f)*dp(2f)) {
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.rgb(18, 85, 128)
                    paint.strokeWidth = dp(1.2f)
                    canvas.drawLine(x,y,x+vx,y+vy,paint)
                }
            }
            hitPoints.add(track to (x to y))
            if (filter.showLabels) {
                // A/C labels are enabled by default on EVERY visible aircraft.
                val segments = ArrayList<String>()
                if (filter.showTrackNumberLabel) segments.add("#${track.number}")
                if (filter.showModeALabel) segments.add("A:${track.modeA ?: "----"}")
                if (filter.showModeCLabel) segments.add("C:${track.modeC ?: "----"}")
                if (filter.showSpeedLabel) segments.add("${track.speedKt?.let { "${it}kt" } ?: "--kt"}")
                if (filter.showQualityLabel) segments.add("Q:${track.quality ?: "--"}")
                if (filter.showMissesLabel) segments.add("Miss:${track.detectionMisses ?: "--"}")
                if (filter.showTprLabel) segments.add("TPR:${track.tpr ?: "--"}")
                if (filter.showLogSigmaLabel) segments.add("Σ:${track.logSigmaVolts ?: "--"}")
                if (filter.showNorthTimeLabel) segments.add("TN:${track.timeSinceNorthSec ?: "--"}")
                if (filter.showTrackingWindowLabel) segments.add("Win:${track.trackingWindow ?: "--"}")
                if (filter.showSmoothingLabel) segments.add("K:${track.smoothingK ?: "--"}")
                if (filter.showRangeLabel) segments.add("%.1fNM".format(track.rangeNm32))
                if (track.alert) segments.add("!")
                // Every active field has its own row, like the desktop label.
                if (segments.isEmpty()) continue
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                paint.textSize = sp(labelSizeSp)
                val lineSpacing = paint.textSize * 1.22f
                val labelWidth = segments.maxOf { paint.measureText(it) }
                val labelHeight = lineSpacing * segments.size
                val distance = dp(11f)
                val manuallyMoved = labelOffsets[track.number]
                val candidates = if (manuallyMoved != null) {
                    listOf((x+manuallyMoved.first) to (y+manuallyMoved.second))
                } else listOf(
                    (x+distance) to (y-dp(7f)),
                    (x-labelWidth-distance) to (y-dp(7f)),
                    (x+distance) to (y+labelHeight),
                    (x-labelWidth-distance) to (y+labelHeight)
                )
                val location = candidates.firstOrNull { (lx,ly) ->
                    val area = RectF(lx-dp(2f),ly-lineSpacing,
                        lx+labelWidth+dp(2f),ly+labelHeight-lineSpacing+dp(2f))
                    lx >= dp(2f) && lx+labelWidth < width-dp(2f) &&
                        area.top >= dp(2f) && area.bottom < height-dp(18f) &&
                        (manuallyMoved != null || labelAreas.none { RectF.intersects(it,area) })
                } ?: candidates.firstOrNull { (lx,ly) ->
                    lx >= 0 && lx + labelWidth < width &&
                        ly-lineSpacing >= 0 && ly+labelHeight-lineSpacing < height
                }
                if (location != null) {
                    val lx=location.first
                    val ly=location.second
                    val bounds=RectF(lx-dp(3f),ly-lineSpacing,
                        lx+labelWidth+dp(3f),ly+labelHeight-lineSpacing+dp(3f))
                    if (manuallyMoved != null) {
                        paint.color = Color.rgb(55, 119, 178)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dp(.8f)
                        canvas.drawLine(x,y,lx,ly-paint.textSize*.55f,paint)
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = background
                    canvas.drawRect(bounds,paint)
                    paint.color = Color.BLACK
                    for ((i,line) in segments.withIndex()) {
                        canvas.drawText(line,lx,ly + i*lineSpacing,paint)
                    }
                    labelAreas.add(bounds)
                    labelBounds.add(track.number to bounds)
                }
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = foreground
        paint.textSize = sp(uiFontSp)
        canvas.drawText("${tracks.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: "--"}  •  Range %.1f NM".format(visibleRangeNm),
            dp(8f), height-dp(12f), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX=event.x; touchY=event.y; dragging=false; movedLabel=false
                movingLabelId=labelBounds.asReversed().firstOrNull { (_,bounds) ->
                    bounds.contains(event.x,event.y)
                }?.first
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                movingLabelId=null
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount==1 && !pinch.isInProgress) {
                    val dx=event.x-touchX
                    val dy=event.y-touchY
                    if (abs(dx)>dp(2f) || abs(dy)>dp(2f)) dragging=true
                    val id=movingLabelId
                    if (id!=null && dragging) {
                        val currentOffset=labelOffsets[id] ?: run {
                            // The first drag starts from the actual displayed
                            // label position, so the label never jumps.
                            val label=labelBounds.firstOrNull { it.first==id }?.second
                            val track=hitPoints.firstOrNull { it.first.number==id }?.second
                            if (label!=null && track!=null)
                                (label.left+dp(3f)-track.first) to
                                    (label.top+sp(labelSizeSp)*1.22f-track.second)
                            else 0f to 0f
                        }
                        labelOffsets[id]=(currentOffset.first+dx) to (currentOffset.second+dy)
                        movedLabel=true
                        invalidate()
                    } else if (dragging) {
                        panX+=dx; panY+=dy; invalidate()
                    }
                    touchX=event.x;touchY=event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !pinch.isInProgress && !movedLabel) {
                    val closest=hitPoints.minByOrNull { (_,p) ->
                        hypot((p.first-event.x).toDouble(),(p.second-event.y).toDouble())
                    }
                    if (closest!=null && hypot((closest.second.first-event.x).toDouble(),
                        (closest.second.second-event.y).toDouble()) <= dp(20f)) {
                        selectedTrackNumber=closest.first.number
                        onTrackTap?.invoke(closest.first)
                    }
                }
                movingLabelId=null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> movingLabelId=null
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
