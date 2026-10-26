package com.punkunpun.polarisecg.dsp.quality

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A second, deliberately simple and differently built beat detector, used only to cross-check
 * the main [com.punkunpun.polarisecg.dsp.detect.QrsDetector] (plan §7.4, "bSQI"): a beat that
 * both detectors see is trustworthy; noise tends to fool them in different ways.
 *
 * Differences to the main one: it works on the 0.5-40 Hz signal (no narrow band-pass), uses the
 * absolute slope over 2 samples smoothed over 5 samples, and its threshold is a fraction of the
 * *median* of recent beat heights (not running signal/noise estimates).
 *
 * Feed it one filtered sample at a time with [add]; found peaks are appended to `out` about
 * 40 ms after they happened. It reports nothing during the first [learnSeconds]; the peaks of
 * that period arrive together when learning ends. The index is where the slope is steepest, a
 * few samples before the R peak: only compare it with other positions using a tolerance.
 */
internal class SlopeBeatDetector(
    sampleRateHz: Double,
    private val thresholdRatio: Double = 0.4,
    learnSeconds: Double = 2.0,
    private val historySize: Int = 8,
    /** A peak counts at most this many times the median height when it is added to the history. */
    private val maxGrowth: Double = 4.0,
    /** No peak accepted for this long: the remembered heights are probably too big (artifact). */
    stallSeconds: Double = 3.0,
) {
    private val half = 5 // a peak must be the highest within +-5 samples (~40 ms)
    private val refractory = (0.2 * sampleRateHz).roundToInt()
    private val learnSamples = (learnSeconds * sampleRateHz).roundToInt()
    private val stallSamples = (stallSeconds * sampleRateHz).roundToInt()

    private val signal = DoubleArray(RING) // input
    private val absSlope = DoubleArray(RING) // |x[n] - x[n-2]|
    private val smooth = DoubleArray(RING) // mean of the last 5 absSlope values
    private var count = 0

    private var learnMax = 0.0
    private val pending = ArrayList<Int>() // peak indices found while learning
    private var initialized = false
    private val heights = ArrayDeque<Double>() // slope heights of recent accepted peaks
    private var last = -1_000_000

    fun add(x: Double, out: MutableList<Int>) {
        val n = count
        signal[n and MASK] = x
        absSlope[n and MASK] = if (n < 2) 0.0 else abs(x - signal[(n - 2) and MASK])
        var sum = 0.0
        for (j in 0..4) if (n - j >= 0) sum += absSlope[(n - j) and MASK]
        smooth[n and MASK] = sum / 5
        count = n + 1

        if (!initialized) learnMax = max(learnMax, smooth[n and MASK])
        val k = n - half
        if (k >= half && isPeak(k, n)) {
            if (initialized) consider(k, out) else pending += k
        }
        if (!initialized && count >= learnSamples && pending.isNotEmpty()) {
            initialized = true
            heights.addLast(learnMax) // start from the biggest bump; accepted peaks then correct the median
            last = (pending.firstOrNull() ?: count) - refractory // start of the stall check, before the first peak
            val replay = ArrayList(pending)
            pending.clear()
            for (p in replay) consider(p, out)
        }
    }

    private fun isPeak(k: Int, last: Int): Boolean {
        val v = smooth[k and MASK]
        if (v <= 0.0) return false
        for (j in k - half until k) if (smooth[j and MASK] >= v) return false
        for (j in k + 1..last) if (smooth[j and MASK] > v) return false
        return true
    }

    private fun consider(k: Int, out: MutableList<Int>) {
        if (k - last < refractory) return
        val v = smooth[k and MASK]
        if (k - last > stallSamples) { // far too long without a peak: halve the remembered heights
            for (i in heights.indices) heights[i] = heights[i] * 0.5
        }
        val sorted = heights.sorted()
        val median = sorted[sorted.size / 2]
        if (v < thresholdRatio * median) return
        last = k
        heights.addLast(minOf(v, maxGrowth * median))
        if (heights.size > historySize) heights.removeFirst()
        out += k
    }

    private companion object {
        const val RING = 512
        const val MASK = RING - 1
    }
}
