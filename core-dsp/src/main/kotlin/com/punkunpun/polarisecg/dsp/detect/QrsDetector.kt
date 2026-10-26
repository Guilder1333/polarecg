package com.punkunpun.polarisecg.dsp.detect

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.filter.EcgFilterConfig
import com.punkunpun.polarisecg.dsp.filter.EcgFilters
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Settings of the QRS detector (plan §7.2). Starting points, to be tuned on data (P3.5).
 * Times are in milliseconds, converted to samples with [sampleRateHz].
 */
data class QrsConfig(
    val sampleRateHz: Double = ECG_SAMPLE_RATE_HZ.toDouble(),
    val filters: EcgFilterConfig = EcgFilterConfig(sampleRateHz = sampleRateHz),
    /** Window of the moving integral over the squared slope; about the width of a QRS complex. */
    val integrationMs: Double = 150.0,
    /** A peak of the integral must be the highest within this distance on both sides. */
    val peakHalfWidthMs: Double = 60.0,
    /** After a beat, nothing counts as a new beat for this long. */
    val refractoryMs: Double = 200.0,
    /** Candidates this soon after a beat must be steep enough to not be a T wave. */
    val tWindowMs: Double = 360.0,
    /** A candidate in the T window needs this fraction of the previous beat's steepness. */
    val tWaveSlopeRatio: Double = 0.5,
    /** No beat for this many average RR intervals: search again with a lower threshold. */
    val searchBackFactor: Double = 1.66,
    /** The lower threshold, as a fraction of the normal one. */
    val searchBackThresholdRatio: Double = 0.5,
    /** Seconds at the start used to set the first thresholds; no beat is reported before. */
    val learnSeconds: Double = 2.0,
    /** Number of recent RR intervals whose median is the "average RR". */
    val rrHistory: Int = 8,
    /** How slowly the learned polarity changes: 0.97 forgets in about 30 beats. */
    val polarityDecay: Double = 0.97,
    /** The opposite-polarity extreme wins only if it is this many times bigger. */
    val polarityOverride: Double = 2.0,
)

/**
 * Streaming QRS detector in the Pan-Tompkins style. Feed it ECG frames in order with [process];
 * it returns the sample index of each R peak it has newly found. Use one instance per gap-free
 * stretch of signal and call [finish] at its end.
 *
 * Steps per sample: band-pass 5-20 Hz -> derivative -> square -> moving average over ~150 ms
 * (the "integral", one smooth bump per QRS) -> pick bumps that are the highest nearby ->
 * decide beat or noise with two running estimates, SPKI (typical beat bump) and NPKI (typical
 * noise bump): threshold = NPKI + 0.25 * (SPKI - NPKI). Extra rules: refractory period, a
 * T-wave check, and search-back for missed beats.
 *
 * Reported indices count samples since the first sample given to this instance, and point at the
 * R peak (largest deflection) in the *original* signal, not in the delayed filtered one, so no
 * delay correction is needed. A beat is reported about 60 ms after it happened (it takes that
 * long to see that the bump has peaked); at the very start nothing is reported for
 * [QrsConfig.learnSeconds].
 *
 * Not thread-safe.
 */
class QrsDetector(private val config: QrsConfig = QrsConfig()) {
    private val fs = config.sampleRateHz
    private val filter = EcgFilters(config.filters).detection

    private val integrationLen = (config.integrationMs * fs / 1000).roundToInt().coerceAtLeast(2)
    private val halfWidth = (config.peakHalfWidthMs * fs / 1000).roundToInt().coerceAtLeast(1)
    private val refractory = (config.refractoryMs * fs / 1000).roundToInt()
    private val tWindow = (config.tWindowMs * fs / 1000).roundToInt()
    private val learnSamples = (config.learnSeconds * fs).roundToInt()

    // Ring buffers: the last RING samples of each signal, addressed by (sample index and MASK).
    // RING must exceed the longest look-back: a search-back over ~1.66 * 2 s of signal.
    private val rawBuf = DoubleArray(RING)
    private val filtBuf = DoubleArray(RING)
    private val slopeBuf = DoubleArray(RING) // |derivative|
    private val sqBuf = DoubleArray(RING)
    private val integralBuf = DoubleArray(RING)

    private var count = 0 // samples received so far
    private var sqSum = 0.0

    // thresholds
    private var initialized = false
    private var spki = 0.0
    private var npki = 0.0
    private var learnMax = 0.0
    private var learnSum = 0.0
    private val pending = ArrayList<Candidate>() // candidates found while learning
    private val missed = ArrayList<Candidate>() // noise-like candidates since the last beat

    // last beat
    private var lastBeat = -1 // index of its integral peak
    private var lastBeatSlope = 0.0
    private var lastR = -1 // reported R index
    private val rrs = ArrayDeque<Int>() // recent RR intervals, in samples
    private var polarity = 0.0 // > 0: R waves point up; < 0: down; learned from the beats

    private val out = ArrayList<Int>()

    private class Candidate(val index: Int, val value: Double)

    /** Feeds one frame (µV). Returns the R peak indices found, in ascending order. */
    fun process(samples: IntArray): IntArray {
        out.clear()
        for (s in samples) {
            step(s.toDouble())
            checkPeak(count - 1 - halfWidth, count - 1)
            if (!initialized && count >= learnSamples) tryInitialize()
        }
        return out.toIntArray()
    }

    /** Call at the end of a stretch: decides about the last, not yet confirmed bumps. */
    fun finish(): IntArray {
        out.clear()
        for (k in (count - halfWidth).coerceAtLeast(0) until count) checkPeak(k, count - 1)
        if (!initialized) tryInitialize()
        return out.toIntArray()
    }

    /** Forget everything, e.g. after a gap in the data. Indices start again at 0. */
    fun reset() {
        filter.reset()
        rawBuf.fill(0.0); filtBuf.fill(0.0); slopeBuf.fill(0.0); sqBuf.fill(0.0); integralBuf.fill(0.0)
        count = 0; sqSum = 0.0
        initialized = false; spki = 0.0; npki = 0.0; learnMax = 0.0; learnSum = 0.0
        pending.clear(); missed.clear()
        lastBeat = -1; lastBeatSlope = 0.0; lastR = -1; rrs.clear(); polarity = 0.0
    }

    // ---- signal chain -------------------------------------------------------------------

    private fun step(x: Double) {
        val n = count
        rawBuf[n and MASK] = x
        val f = filter.process(x)
        filtBuf[n and MASK] = f
        // 5-point derivative (Pan-Tompkins): (2 f[n] + f[n-1] - f[n-3] - 2 f[n-4]) / 8
        val d = if (n < 4) 0.0 else
            (2 * f + filtBuf[(n - 1) and MASK] - filtBuf[(n - 3) and MASK] - 2 * filtBuf[(n - 4) and MASK]) / 8
        slopeBuf[n and MASK] = abs(d)
        val sq = d * d
        sqBuf[n and MASK] = sq
        sqSum += sq
        if (n >= integrationLen) sqSum -= sqBuf[(n - integrationLen) and MASK]
        if (sqSum < 0) sqSum = 0.0 // rounding
        val integral = sqSum / integrationLen
        integralBuf[n and MASK] = integral
        if (!initialized) {
            learnMax = max(learnMax, integral)
            learnSum += integral
        }
        count = n + 1
    }

    /** Is the integral at [k] a peak, i.e. the highest within [halfWidth] on both sides? */
    private fun checkPeak(k: Int, last: Int) {
        if (k < halfWidth) return
        val v = integralBuf[k and MASK]
        if (v <= 0.0) return
        for (j in k - halfWidth until k) if (integralBuf[j and MASK] >= v) return // earlier ties win
        for (j in k + 1..last) if (integralBuf[j and MASK] > v) return
        val c = Candidate(k, v)
        if (!initialized) pending += c else decide(c)
    }

    // ---- decisions ----------------------------------------------------------------------

    private fun tryInitialize() {
        if (pending.isEmpty()) return
        // Pan-Tompkins start values: signal peak = 1/4 of the biggest bump, noise = half the mean.
        spki = 0.25 * learnMax
        npki = 0.5 * learnSum / count
        initialized = true
        val replay = ArrayList(pending)
        pending.clear()
        for (c in replay) decide(c)
    }

    private fun decide(c: Candidate) {
        if (lastBeat >= 0 && c.index - lastBeat > config.searchBackFactor * averageRr()) searchBack(c.index)
        if (lastBeat >= 0 && c.index - lastBeat < refractory) return // still the same QRS

        var isBeat = c.value > threshold()
        if (isBeat && lastBeat >= 0 && c.index - lastBeat < tWindow &&
            slopeAround(c.index) < config.tWaveSlopeRatio * lastBeatSlope
        ) {
            isBeat = false // early and gentle: a T wave
        }
        if (isBeat) {
            spki = 0.125 * c.value + 0.875 * spki
            accept(c)
        } else {
            npki = 0.125 * c.value + 0.875 * npki
            missed += c
        }
    }

    private fun threshold() = npki + 0.25 * (spki - npki)

    /** No beat for too long: take the biggest earlier candidate above the lower threshold. */
    private fun searchBack(currentIndex: Int) {
        val low = config.searchBackThresholdRatio * threshold()
        val best = missed
            .filter { it.index - lastBeat >= refractory && currentIndex - it.index >= refractory && it.value > low }
            .maxByOrNull { it.value } ?: return
        spki = 0.25 * best.value + 0.75 * spki
        accept(best)
    }

    private fun accept(c: Candidate) {
        val r = localizeR(c.index)
        if (lastBeat >= 0) {
            rrs.addLast(c.index - lastBeat)
            if (rrs.size > config.rrHistory) rrs.removeFirst()
        }
        lastBeat = c.index
        lastBeatSlope = slopeAround(c.index)
        missed.removeAll { it.index <= c.index }
        if (r > lastR) { // always true in practice; guards against two beats mapped to one R
            lastR = r
            out += r
        }
    }

    /** Median of the recent RR intervals in samples (1 s until two are known). */
    private fun averageRr(): Double {
        if (rrs.size < 2) return fs
        val sorted = rrs.sorted()
        return sorted[sorted.size / 2].toDouble().coerceIn(0.3 * fs, 2.0 * fs)
    }

    /** Steepest slope of the filtered signal in the integration window ending at [k]. */
    private fun slopeAround(k: Int): Double {
        var m = 0.0
        for (j in (k - integrationLen).coerceAtLeast(0)..k) m = max(m, slopeBuf[j and MASK])
        return m
    }

    /**
     * Finds the R peak in the *original* signal for the integral bump at [k]. The QRS lies in the
     * integration window before the bump's peak, plus a few samples for the filter delay. A
     * straight line between the window ends is subtracted so a sloping baseline does not
     * matter. The tallest deflection in the learned polarity wins, unless the opposite one is
     * much bigger (polarityOverride).
     */
    private fun localizeR(k: Int): Int {
        val from = (k - integrationLen - 6).coerceAtLeast(count - RING + 1).coerceAtLeast(0)
        val to = k
        if (to - from < 4) return k
        val a = (rawBuf[from and MASK] + rawBuf[(from + 1) and MASK]) / 2
        val b = (rawBuf[to and MASK] + rawBuf[(to - 1) and MASK]) / 2
        var up = from; var upDev = Double.NEGATIVE_INFINITY
        var down = from; var downDev = Double.NEGATIVE_INFINITY // downDev = size of the downward dip
        for (j in from..to) {
            val dev = rawBuf[j and MASK] - (a + (b - a) * (j - from) / (to - from))
            if (dev > upDev) { upDev = dev; up = j }
            if (-dev > downDev) { downDev = -dev; down = j }
        }
        val pickUp = when {
            polarity > 0 -> !(downDev > config.polarityOverride * upDev)
            polarity < 0 -> upDev > config.polarityOverride * downDev
            else -> upDev >= downDev
        }
        polarity = config.polarityDecay * polarity + if (pickUp) 1 else -1
        return if (pickUp) up else down
    }

    private companion object {
        const val RING = 2048 // 15.7 s at 130 Hz; a power of two so "and MASK" wraps the index
        const val MASK = RING - 1
    }
}
