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
import kotlin.math.sin

/** Light-gray radar, black MSSR(Δ) / combined(U) / PSR(○) symbols; desktop AIRCAT labels. */
class RadarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgColor = Color.rgb(200, 207, 214) // deeper PPI gray
    private val black = Color.rgb(12, 12, 12)
    private val gridColor = Color.rgb(131, 147, 160)
    private val lightGray = Color.rgb(50, 98, 138)
    private val sectorShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(24, 18, 83, 139) }
    private val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(242, 227, 233, 238) }
    private val triangle = Path()
    private val uPath = Path()

    var state: RadarReplayState? = null
        set(value) { field = value; invalidate() }
    var filter: RadarFilter = RadarFilter()
        set(value) { field = value; invalidate() }
    var sweepAzimuthDegrees: Float = Float.NaN
        set(value) { field = value; invalidate() }
    var coordinateDivisor = 32.0
        set(value) { field = value; invalidate() }
    var zoom = 1f
        set(value) { field = value.coerceIn(1f, 12f); invalidate() }
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
    val maxVisibleRangeNm: Double get() = PpiScale.maxRange(zoom)
    private fun dp(n: Float) = n * resources.displayMetrics.density
    private fun sp(n: Float) = n * resources.displayMetrics.scaledDensity

    fun resetViewport() { panX = 0f; panY = 0f; zoom = 1f; invalidate() }
    private fun pointFor(track: RadarTrack, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val range = 250.0 * coordinateDivisor
        return (cx + panX + (track.xRaw / range * radius * zoom).toFloat()) to
            (cy + panY - (track.yRaw / range * radius * zoom).toFloat())
    }

    private fun drawTarget(canvas: Canvas, track: RadarTrack, x: Float, y: Float, selected: Boolean) {
        val size = dp(if (selected) 6.8f else 5.5f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (selected) 2.0f else 1.5f)
        paint.color = black
        when {
            track.combined -> {
                // Explicit U shape for associated / combined PSR+SSR returns.
                uPath.reset()
                uPath.moveTo(x - size, y - size)
                uPath.lineTo(x - size, y + size * .20f)
                uPath.cubicTo(x - size, y + size * 1.15f,
                    x + size, y + size * 1.15f,
                    x + size, y + size * .20f)
                uPath.lineTo(x + size, y - size)
                canvas.drawPath(uPath, paint)
            }
            track.primary -> canvas.drawCircle(x, y, size * .7f, paint) // Open ○ for PSR only.
            else -> {
                // Outlined Δ for MSSR / SSR.
                triangle.reset()
                triangle.moveTo(x, y - size)
                triangle.lineTo(x - size, y + size * .65f)
                triangle.lineTo(x + size, y + size * .65f)
                triangle.close()
                canvas.drawPath(triangle, paint)
            }
        }
        if (selected) canvas.drawCircle(x, y, size + dp(3f), paint)
        if (track.alert) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(160, 0, 0)
            paint.textSize = sp(13f)
            paint.isFakeBoldText = true
            canvas.drawText("!", x + size + dp(3f), y - size, paint)
            paint.isFakeBoldText = false
        }
    }

    private fun labelLines(track: RadarTrack): List<String> = buildList {
        if (filter.showTrackNumber) add("T:${track.number}")
        // Mode A and Mode C enabled by default for every target, including PSR-only.
        if (filter.showModeA) add("A:${track.modeA ?: "----"}${if (track.modeAInvalid) " !" else ""}")
        if (filter.showModeC) add("C:${track.modeC ?: "----"}${if (track.modeCInvalid) " !" else ""}")
        if (filter.showSpeed && track.speedKt != null) add("V:${track.speedKt} kt")
        if (filter.showRange) add("R:%.1f NM".format(java.util.Locale.US, track.rangeNm32))
        if (filter.showQuality && track.quality != null) add("Q:${track.quality}")
    }

    private fun drawLabel(
        canvas: Canvas, track: RadarTrack, x: Float, y: Float, areaUsed: MutableList<RectF>
    ) {
        val lines = labelLines(track)
        if (lines.isEmpty()) return
        paint.style = Paint.Style.FILL
        paint.color = black
        paint.textSize = sp(10f)
        val lineHeight = paint.fontSpacing.coerceAtLeast(dp(11f))
        val labelWidth = lines.maxOf { paint.measureText(it) } + dp(6f)
        val labelHeight = lines.size * lineHeight + dp(4f)
        val offset = dp(10f)
        val xRight = x + offset
        val xLeft = x - offset - labelWidth
        val yTop = y - offset - labelHeight
        val yBottom = y + offset
        val options = listOf(
            xRight to yTop, xRight to yBottom, xLeft to yTop, xLeft to yBottom,
            xRight to (y - labelHeight / 2), xLeft to (y - labelHeight / 2)
        )
        val chosen = options.firstOrNull { (candidateX, candidateY) ->
            val rect = RectF(candidateX, candidateY, candidateX + labelWidth, candidateY + labelHeight)
            rect.left >= dp(1f) && rect.top >= dp(1f) &&
                rect.right <= width - dp(1f) && rect.bottom <= height - dp(2f) &&
                areaUsed.none { RectF.intersects(rect, it) }
        } ?: options.first()
        val lx = chosen.first.coerceIn(dp(1f), (width - labelWidth - dp(1f)).coerceAtLeast(dp(1f)))
        val ly = chosen.second.coerceIn(dp(1f), (height - labelHeight - dp(2f)).coerceAtLeast(dp(1f)))
        val box = RectF(lx, ly, lx + labelWidth, ly + labelHeight)
        // Never discard a label merely because the screen is crowded.
        canvas.drawRoundRect(box, dp(2f), dp(2f), labelBackground)
        paint.color = black
        for ((n, line) in lines.withIndex()) {
            canvas.drawText(line, lx + dp(3f), ly + dp(2f) - paint.fontMetrics.ascent + n * lineHeight, paint)
        }
        areaUsed.add(box)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width * .455f, (height - dp(50f)) * .45f).coerceAtLeast(1f)
        val ox = cx + panX
        val oy = cy + panY
        // Range rings express physical distance in NM, not arbitrary zoom factors.
        // The outer PPI rim corresponds to 250 NM / zoom.
        val maxNm = maxVisibleRangeNm
        val nmRings = PpiScale.rings(maxNm)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(.85f)
        paint.color = gridColor
        canvas.drawCircle(ox, oy, radius, paint)
        canvas.drawLine(ox - radius, oy, ox + radius, oy, paint)
        canvas.drawLine(ox, oy - radius, ox, oy + radius, paint)
        paint.textSize = sp(10f)
        paint.strokeWidth = dp(1.25f)
        paint.color = Color.rgb(196, 16, 29) // unmistakably red range rings
        for (nm in nmRings) {
            val rr = (radius * nm / maxNm).toFloat()
            canvas.drawCircle(ox, oy, rr, paint)
        }
        paint.style = Paint.Style.FILL
        paint.textSize = sp(9f)
        for (nm in nmRings) {
            val rr = (radius * nm / maxNm).toFloat()
            val label = PpiScale.formatNm(nm) + " NM"
            val measured = paint.measureText(label)
            val lx = if (ox + rr + dp(4f) + measured <= width - dp(3f))
                ox + rr + dp(4f) else ox - rr - measured - dp(4f)
            if (lx >= dp(2f) && lx + measured <= width - dp(2f))
                canvas.drawText(label, lx, oy - dp(3f), paint)
        }
        paint.color = black
        paint.textSize = sp(11f)
        canvas.drawText("N", ox - dp(4f), oy - radius - dp(8f), paint)
        canvas.drawText("E", ox + radius + dp(4f), oy + dp(4f), paint)
        paint.textSize = sp(9f)
        canvas.drawText("Δ MSSR    U COMBINED    ○ PSR", dp(6f), dp(16f), paint)
        val current = state ?: return
        val bearing = if (sweepAzimuthDegrees.isNaN()) (current.sector ?: 0) * 11.25f else sweepAzimuthDegrees
        canvas.drawArc(RectF(ox-radius, oy-radius, ox+radius, oy+radius), bearing - 90f, 11.25f, true, sectorShade)
        val radians = Math.toRadians((bearing-90f).toDouble())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.1f)
        paint.color = lightGray
        canvas.drawLine(ox, oy, ox + (radius*cos(radians)).toFloat(),
            oy + (radius*sin(radians)).toFloat(), paint)

        hitPoints.clear()
        val tracks = current.allTracks().filter { filter.allows(it) }.sortedWith(compareBy({ it.number },{ it.primary }))
        if (showTrails && filter.showTrails) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.65f)
            paint.color = Color.rgb(26, 77, 121) // strong aviation-blue trails on gray PPI
            for ((_, trail) in current.trackTrail) {
                val visible = trail.takeLast(current.trailDepth).filter { filter.allows(it) }
                if (visible.size < 2) continue
                var last = pointFor(visible.first(), cx, cy, radius)
                for (track in visible.drop(1)) {
                    val point = pointFor(track, cx, cy, radius)
                    canvas.drawLine(last.first, last.second, point.first, point.second, paint)
                    last = point
                }
                paint.style = Paint.Style.FILL
                for (historic in visible.dropLast(1)) {
                    val (tx, ty) = pointFor(historic, cx, cy, radius)
                    canvas.drawCircle(tx, ty, dp(1.7f), paint)
                }
                paint.style = Paint.Style.STROKE
            }
        }
        val visibleTargets = ArrayList<Triple<RadarTrack, Float, Float>>()
        for (track in tracks) {
            if (hypot(track.xRaw.toDouble(), track.yRaw.toDouble()) > 250.0 * coordinateDivisor * 1.1) continue
            val (x,y) = pointFor(track,cx,cy,radius)
            if (x < -dp(8f) || x > width + dp(8f) || y < -dp(8f) || y > height + dp(8f)) continue
            drawTarget(canvas,track,x,y,track.number == selectedTrackNumber)
            hitPoints.add(track to (x to y))
            visibleTargets.add(Triple(track,x,y))
            if (filter.showVelocityVectors && track.dx != null && track.dy != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(.8f)
                paint.color = lightGray
                canvas.drawLine(x,y,x+track.dx.coerceIn(-30,30)*dp(.5f),
                    y-track.dy.coerceIn(-30,30)*dp(.5f),paint)
            }
        }
        if (filter.showLabels) {
            val used = ArrayList<RectF>()
            for ((track,x,y) in visibleTargets) drawLabel(canvas,track,x,y,used)
        }
        paint.style = Paint.Style.FILL
        paint.color = black
        paint.textSize = sp(10f)
        canvas.drawText("${visibleTargets.size} targets  •  Scan ${current.cycle}  •  Sector ${current.sector?.toString(16)?.uppercase()?.padStart(2,'0') ?: "--"}  •  Range 0–${PpiScale.formatNm(maxNm)} NM  •  Trail ${current.trailDepth}",
            dp(8f),height-dp(10f),paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touchX=event.x; touchY=event.y; dragging=false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !pinch.isInProgress) {
                    val dx=event.x-touchX; val dy=event.y-touchY
                    if (abs(dx)>dp(3f) || abs(dy)>dp(3f)) dragging=true
                    if (dragging) { panX+=dx; panY+=dy; invalidate() }
                    touchX=event.x; touchY=event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && !pinch.isInProgress) {
                    val closest=hitPoints.minByOrNull { (_,p) ->
                        hypot((p.first-event.x).toDouble(),(p.second-event.y).toDouble()) }
                    if (closest != null && hypot((closest.second.first-event.x).toDouble(),
                            (closest.second.second-event.y).toDouble()) <= dp(20f)) {
                        selectedTrackNumber=closest.first.number
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
