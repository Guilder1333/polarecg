package com.punkunpun.polarisecg.dsp.hrv

import com.punkunpun.polarisecg.dsp.analysis.Beat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Heart rate variability (P5.5, plan §7.7) from the beats the pipeline found. Everything is computed from NN intervals:
 * the time between two consecutive NORMAL beats with a good signal. A premature beat, a noisy beat or a missed beat
 * never enters an interval, so ectopic beats do not inflate the variability.
 */

/** The window HRV is computed over; aligned to UTC like the 10 s windows. */
const val HRV_WINDOW_MS = 5 * 60_000L

/** Plausible NN intervals (ms); anything outside is a missed or double detection. */
const val NN_MIN_MS = 300L
const val NN_MAX_MS = 2000L

/** An NN interval may differ from the NN before it (when that one ends where this one starts) by this fraction at most. */
const val NN_MAX_JUMP = 0.20

/** A window is "valid" (fit to show) if valid NN intervals cover at least this fraction of it. */
const val HRV_MIN_COVERAGE = 0.70

/** The spectrum needs enough intervals to mean anything. */
private const val MIN_NN_FOR_SPECTRUM = 60

/** An NN interval: from the beat at [startUtcMs] to the beat at [endUtcMs]. */
data class Nn(val startUtcMs: Long, val endUtcMs: Long) {
    val ms: Long get() = endUtcMs - startUtcMs
}

/**
 * The NN series of a recording, in time order. An interval counts if both beats are class N with a good signal
 * (rescued beats are not `good`, so their less certain R positions stay out), in the same gap-free segment, 300-2000 ms
 * long, and within 20 % of the NN right before it when that one ends where this one starts.
 */
fun nnIntervals(beats: List<Beat>): List<Nn> {
    val out = ArrayList<Nn>()
    for (i in 1 until beats.size) {
        val a = beats[i - 1]
        val b = beats[i]
        if (a.segment != b.segment || a.cls != "N" || b.cls != "N" || !a.good || !b.good) continue
        val ms = b.utcMs - a.utcMs
        if (ms < NN_MIN_MS || ms > NN_MAX_MS) continue
        val previous = out.lastOrNull()
        if (previous != null && previous.endUtcMs == a.utcMs && abs(ms - previous.ms) > NN_MAX_JUMP * previous.ms) continue
        out += Nn(a.utcMs, b.utcMs)
    }
    return out
}

/** The time-domain figures of a set of NN intervals; successive differences only between intervals that share a beat. */
class TimeDomain(
    val count: Int,
    val meanMs: Double?,
    val sdnnMs: Double?,
    val rmssdMs: Double?,
    val pnn50Percent: Double?,
) {
    val meanHrBpm: Double? get() = meanMs?.let { 60_000.0 / it }
}

fun timeDomain(nn: List<Nn>): TimeDomain {
    if (nn.isEmpty()) return TimeDomain(0, null, null, null, null)
    val mean = nn.sumOf { it.ms.toDouble() } / nn.size
    val sdnn = if (nn.size > 1) sqrt(nn.sumOf { (it.ms - mean) * (it.ms - mean) } / (nn.size - 1)) else null
    var pairs = 0
    var squares = 0.0
    var over50 = 0
    for (i in 1 until nn.size) {
        if (nn[i].startUtcMs != nn[i - 1].endUtcMs) continue
        val d = (nn[i].ms - nn[i - 1].ms).toDouble()
        pairs++
        squares += d * d
        if (abs(d) > 50) over50++
    }
    return TimeDomain(
        nn.size, mean, sdnn,
        if (pairs > 0) sqrt(squares / pairs) else null,
        if (pairs > 0) 100.0 * over50 / pairs else null,
    )
}

/** Power (ms²) in the low (0.04-0.15 Hz) and high (0.15-0.40 Hz) band. */
class Spectrum(val lfMs2: Double, val hfMs2: Double) {
    val lfHf: Double? get() = if (hfMs2 > 0) lfMs2 / hfMs2 else null
}

/**
 * Lomb-Scargle periodogram of the NN series (values against the time of the beat that ends each interval), which
 * works on unevenly spaced samples, so no resampling is needed. Scaled to a one-sided power spectral density
 * (ms²/Hz) and added up over the bands, so for a pure oscillation of amplitude A the band holds A²/2 ms².
 * Null if there are too few intervals.
 */
fun spectrum(nn: List<Nn>): Spectrum? {
    if (nn.size < MIN_NN_FOR_SPECTRUM) return null
    val t0 = nn.first().endUtcMs
    val t = DoubleArray(nn.size) { (nn[it].endUtcMs - t0) / 1000.0 }
    val mean = nn.sumOf { it.ms.toDouble() } / nn.size
    val y = DoubleArray(nn.size) { nn[it].ms - mean }
    val meanSpacing = (t.last() - t.first()) / (nn.size - 1)
    if (meanSpacing <= 0) return null

    val df = 0.001
    var lf = 0.0
    var hf = 0.0
    var f = 0.04
    while (f < 0.40 - 1e-9) {
        val w = 2 * PI * (f + df / 2) // the middle of the step
        var s2 = 0.0
        var c2 = 0.0
        for (x in t) { s2 += sin(2 * w * x); c2 += cos(2 * w * x) }
        val tau = atan2(s2, c2) / (2 * w)
        var yc = 0.0
        var ys = 0.0
        var cc = 0.0
        var ss = 0.0
        for (i in t.indices) {
            val c = cos(w * (t[i] - tau))
            val s = sin(w * (t[i] - tau))
            yc += y[i] * c; ys += y[i] * s; cc += c * c; ss += s * s
        }
        val p = 0.5 * ((if (cc > 0) yc * yc / cc else 0.0) + (if (ss > 0) ys * ys / ss else 0.0))
        val density = 2 * meanSpacing * p // ms²/Hz
        if (f < 0.15 - 1e-9) lf += density * df else hf += density * df
        f += df
    }
    return Spectrum(lf, hf)
}

/** HRV of one 5-minute window. [valid] = enough coverage; the figures are there either way, so greying out is the reader's choice. */
class HrvWindow(
    val startUtcMs: Long,
    val durationMs: Long,
    val nn: Int,
    /** Share of the window covered by valid NN intervals (0..1). */
    val coverage: Double,
    val time: TimeDomain,
    /** Only for valid windows with enough intervals. */
    val spectrum: Spectrum?,
) {
    val valid: Boolean get() = coverage >= HRV_MIN_COVERAGE
}

/** HRV per [HRV_WINDOW_MS] window that has at least one NN interval; an interval belongs to the window of the beat that ends it. */
fun hrvWindows(nn: List<Nn>): List<HrvWindow> {
    val out = ArrayList<HrvWindow>()
    var i = 0
    while (i < nn.size) {
        val start = nn[i].endUtcMs - Math.floorMod(nn[i].endUtcMs, HRV_WINDOW_MS)
        var j = i
        while (j < nn.size && nn[j].endUtcMs < start + HRV_WINDOW_MS) j++
        val part = nn.subList(i, j)
        val covered = part.sumOf { it.ms }.toDouble() / HRV_WINDOW_MS
        out += HrvWindow(start, HRV_WINDOW_MS, part.size, covered.coerceAtMost(1.0), timeDomain(part), if (covered >= HRV_MIN_COVERAGE) spectrum(part) else null)
        i = j
    }
    return out
}
