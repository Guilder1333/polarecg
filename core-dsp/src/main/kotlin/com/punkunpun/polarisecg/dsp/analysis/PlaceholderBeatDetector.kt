package com.punkunpun.polarisecg.dsp.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * PLACEHOLDER beat detector so the analysis pipeline (CLI, CSV, viewer) can run end to end.
 * It is replaced by the real streaming Pan-Tompkins detector in P3.2 and must NOT be scored or
 * tuned: it has no filters, no search-back, no noise handling.
 *
 * Idea: QRS complexes are steep. Square the first difference, sum it over ~120 ms, and call a
 * beat wherever that sum has a peak above 30 % of the strongest peak nearby (+-1.5 s).
 * Two beats closer than 200 ms are one beat (the larger peak wins).
 */

/** Indices (into [x]) of the detected R peaks, ascending. */
fun detectBeatsPlaceholder(x: IntArray, rateHz: Int): IntArray {
    val n = x.size
    val win = (0.12 * rateHz).roundToInt().coerceAtLeast(2)
    if (n < 2 * win + 2) return IntArray(0)

    // y[i] = sum of squared differences over the last `win` samples
    val y = DoubleArray(n)
    var acc = 0.0
    val sq = DoubleArray(n) { i -> if (i == 0) 0.0 else (x[i] - x[i - 1]).toDouble().let { it * it } }
    for (i in 0 until n) {
        acc += sq[i]
        if (i >= win) acc -= sq[i - win]
        y[i] = acc
    }

    // Threshold: 30 % of the biggest value in the neighbourhood (blocks of 0.5 s, 3 blocks each side)
    val block = max(1, rateHz / 2)
    val blockMax = DoubleArray((n + block - 1) / block)
    for (i in 0 until n) blockMax[i / block] = max(blockMax[i / block], y[i])
    fun threshold(i: Int): Double {
        val b = i / block
        var m = 0.0
        for (k in (b - 3)..(b + 3)) if (k in blockMax.indices) m = max(m, blockMax[k])
        return 0.3 * m
    }

    val refractory = (0.2 * rateHz).roundToInt()
    val peaks = ArrayList<Int>()
    for (i in 1 until n - 1) {
        if (y[i] < y[i - 1] || y[i] <= y[i + 1] || y[i] < threshold(i) || y[i] <= 0.0) continue
        val last = peaks.lastOrNull()
        if (last != null && i - last < refractory) {
            if (y[i] > y[last]) peaks[peaks.size - 1] = i
        } else {
            peaks += i
        }
    }

    // y peaks a little after the QRS centre: take the largest excursion from the local mean in
    // the `win` samples before it.
    return IntArray(peaks.size) { k ->
        val p = peaks[k]
        val from = max(0, p - win)
        var mean = 0.0
        for (j in from..p) mean += x[j]
        mean /= (p - from + 1)
        var best = p
        var bestDev = -1.0
        for (j in from..p) {
            val dev = abs(x[j] - mean)
            if (dev > bestDev) { bestDev = dev; best = j }
        }
        best
    }
}
