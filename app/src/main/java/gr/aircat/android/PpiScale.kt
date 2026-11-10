package gr.aircat.android

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.floor

/** Physical display scale from RADAR center to the PPI outer ring. */
object PpiScale {
    fun maxRange(zoom: Float): Double = 250.0 / zoom.coerceIn(1f, 12f).toDouble()
    fun formatNm(value: Double): String =
        if (abs(value - value.toLong()) < 0.01) value.toLong().toString()
        else String.format(Locale.US, "%.1f", value)

    fun rings(maxNm: Double): List<Double> {
        if (maxNm <= 0) return emptyList()
        val target = maxNm / 4.0
        val power = 10.0.pow(floor(kotlin.math.log10(target)))
        val candidates = listOf(.5, 1.0, 2.0, 2.5, 5.0, 10.0, 20.0).map { it * power }
        val step = candidates.minBy { abs(it - target) }
        val rings = ArrayList<Double>()
        var value = step
        while (value <= maxNm + 1e-7 && rings.size < 7) {
            rings.add(value)
            value += step
        }
        if (rings.none { abs(it - maxNm) < .02 } && rings.size < 7) rings.add(maxNm)
        return rings
    }
}
