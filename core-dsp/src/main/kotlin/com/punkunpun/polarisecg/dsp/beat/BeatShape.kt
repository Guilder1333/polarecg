package com.punkunpun.polarisecg.dsp.beat

import com.punkunpun.polarisecg.dsp.detect.parabolicPeakOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Layout of the beat snippet cut out of the 0.5-40 Hz signal (P4.1): [PRE] samples before the R
 * position, the R sample itself, [POST] samples after it (250 ms and 400 ms at 130 Hz), and
 * [MARGIN] extra samples on both sides so the snippet can be slid a few samples against a template
 * (the reported R position can be off by a few samples, see docs/RR_DISAGREEMENTS.md).
 *
 * A *snippet* has [SNIPPET_LENGTH] samples and its R sample is at index [CENTER]. A *core* (what a
 * template holds) has [CORE_LENGTH] samples and R at [PRE].
 */
object BeatShape {
    const val PRE = 33
    const val POST = 52
    const val MARGIN = 6
    const val CORE_LENGTH = PRE + 1 + POST
    const val SNIPPET_LENGTH = CORE_LENGTH + 2 * MARGIN
    const val CENTER = MARGIN + PRE
}

/** The [BeatShape.CORE_LENGTH] samples of [snippet] that line up with a template after sliding by [shift]. */
internal fun core(snippet: DoubleArray, shift: Int): DoubleArray =
    DoubleArray(BeatShape.CORE_LENGTH) { snippet[BeatShape.MARGIN + shift + it] }

/** Pearson correlation of two equally long signals (-1..1); 0 if one of them is flat. */
internal fun pearson(a: DoubleArray, b: DoubleArray): Double {
    var ma = 0.0
    var mb = 0.0
    for (i in a.indices) { ma += a[i]; mb += b[i] }
    ma /= a.size; mb /= a.size
    var sab = 0.0
    var saa = 0.0
    var sbb = 0.0
    for (i in a.indices) {
        val x = a[i] - ma
        val y = b[i] - mb
        sab += x * y; saa += x * x; sbb += y * y
    }
    val d = sqrt(saa * sbb)
    return if (d > 1e-9) sab / d else 0.0
}

/**
 * How a beat snippet lines up with a template. [shift] is how many samples (fractional) the beat's
 * own R feature lies *after* the marked R position, relative to the template; [inverted] = best
 * correlation of the *upside-down* beat.
 */
class ShapeMatch(val corr: Double, val shift: Double, val inverted: Double) {
    /** [shift] rounded to whole samples, for cutting the snippet with [core]. */
    val shiftSamples: Int get() = shift.roundToInt()
}

/**
 * Best correlation of [snippet] with [template] (a core) over slides of -[maxShift]..+[maxShift]
 * samples; the slide is refined to a fraction of a sample by a parabola through the three
 * correlations around the best one (like the R peak refinement, P3.3).
 */
internal fun bestMatch(snippet: DoubleArray, template: DoubleArray, maxShift: Int): ShapeMatch {
    val corr = DoubleArray(2 * maxShift + 1) { pearson(core(snippet, it - maxShift), template) }
    val best = corr.indices.maxByOrNull { corr[it] }!! // !! = "cannot be null": the array is not empty
    var shift = (best - maxShift).toDouble()
    if (best > 0 && best < corr.lastIndex) shift += parabolicPeakOffset(corr[best - 1], corr[best], corr[best + 1])
    return ShapeMatch(corr[best], shift, -corr.min())
}

/** Half width (samples) of the window around R used for amplitude and area: +-92 ms. */
internal const val QRS_HALF_WINDOW = 12

/** Peak-to-peak height and summed |value| of the samples within [QRS_HALF_WINDOW] of [center] in [x]. */
internal fun qrsHeightAndArea(x: DoubleArray, center: Int): Pair<Double, Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    var area = 0.0
    for (k in center - QRS_HALF_WINDOW..center + QRS_HALF_WINDOW) {
        lo = minOf(lo, x[k]); hi = maxOf(hi, x[k]); area += abs(x[k])
    }
    return (hi - lo) to area
}

/**
 * QRS width in ms around [center] of [x]: from the steepest slope within +-150 ms, walk outwards
 * while the slope stays above [fraction] of it. A biphasic QRS has a flat moment at the R peak
 * where the slope dips to zero, so up to [gap] samples below the limit are tolerated.
 * At 130 Hz one sample is 7.7 ms, so the result is coarse.
 */
internal fun qrsWidthMs(x: DoubleArray, center: Int, sampleRateHz: Double, fraction: Double = 0.2, gap: Int = 2): Double {
    val reach = 20
    fun slope(k: Int) = abs(x[k + 1] - x[k - 1])
    var peak = center
    for (k in center - reach..center + reach) if (slope(k) > slope(peak)) peak = k
    val limit = fraction * slope(peak)
    if (limit <= 0.0) return 0.0
    fun edge(step: Int): Int {
        var edge = peak
        var below = 0
        var k = peak
        while (k + step in center - reach..center + reach) {
            k += step
            if (slope(k) >= limit) { edge = k; below = 0 } else if (++below > gap) break
        }
        return edge
    }
    return (edge(1) - edge(-1) + 1) * 1000.0 / sampleRateHz
}
