package com.punkunpun.polarisecg.dsp.detect

/**
 * Sub-sample position of a peak from its highest sample and the two neighbours (plan §7.2, step 6).
 *
 * A parabola `y = a*t^2 + b*t + c` through the points at t = -1, 0, +1 has its top at
 * `t = (left - right) / (2 * (left - 2*mid + right))`. The result is the offset in samples from
 * the middle sample, between -0.5 and +0.5 when [mid] really is the highest of the three
 * (otherwise it is clamped to that range). At 130 Hz this cuts the timing error from up to
 * +-3.8 ms (half a sample) to roughly 1-2 ms on clean beats.
 *
 * Works for a valley too if you pass the negated values.
 */
fun parabolicPeakOffset(left: Double, mid: Double, right: Double): Double {
    val denominator = left - 2 * mid + right // curvature; negative for a peak
    if (denominator >= 0.0) return 0.0 // flat or a valley: no sensible vertex
    return ((left - right) / (2 * denominator)).coerceIn(-0.5, 0.5)
}
