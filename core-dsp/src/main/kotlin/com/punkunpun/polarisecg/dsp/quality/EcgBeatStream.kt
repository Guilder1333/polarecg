package com.punkunpun.polarisecg.dsp.quality

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.beat.BeatShape
import com.punkunpun.polarisecg.dsp.detect.QrsConfig
import com.punkunpun.polarisecg.dsp.detect.QrsDetector
import com.punkunpun.polarisecg.dsp.filter.EcgFilterConfig
import com.punkunpun.polarisecg.dsp.filter.EcgFilters
import com.punkunpun.polarisecg.dsp.filter.FilterCascade
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Settings of the signal quality index (plan §7.4). Starting points; tune on data (P3.5-P3.7).
 */
data class QualityConfig(
    val sampleRateHz: Double = ECG_SAMPLE_RATE_HZ.toDouble(),
    val qrs: QrsConfig = QrsConfig(sampleRateHz = sampleRateHz),
    /** Noise is measured this far before and after the beat. */
    val contextMs: Double = 400.0,
    /** ... but not within this distance of the beat itself (the QRS has some high-frequency energy). */
    val qrsExcludeMs: Double = 80.0,
    /**
     * Slope noise: typical (median) change from one sample to the next outside the QRS, scaled to
     * an RMS, divided by the beat height. Electrode and motion noise (NSTDB) raises it; clean
     * beats are far below. Score 1 at or below `slopeOkRatio`, 0 at or above `slopeBadRatio`,
     * linear in between. Calibrated on MIT-BIH (clean) and NSTDB (noisy): the "good" cut-off of
     * 0.6 falls at ratio 0.04. Both noise ratios use the beat height, but never more than the usual
     * height, so a tall artifact cannot look clean (P3.7). With that and a 200-beat usual height:
     * 3.5 % of true beats lost on MIT-BIH, 93 % of its false detections flagged; on NSTDB (noisy
     * records) 19 % of the true ones lost and 90 % of the false ones flagged.
     */
    val slopeOkRatio: Double = 0.02,
    val slopeBadRatio: Double = 0.07,
    /** Muscle noise lives above this frequency (Nyquist at 130 Hz is 65 Hz). */
    val hfHighPassHz: Double = 40.0,
    /** RMS of the >40 Hz signal outside the QRS relative to the beat height; clean beats are below 0.03. */
    val hfOkRatio: Double = 0.03,
    val hfBadRatio: Double = 0.10,
    /** Number of recent beat heights whose median is the "usual" height. */
    val amplitudeHistory: Int = 200,
    /** Beats lower than this (µV, peak to peak) are flat line / lost contact. */
    val minAmplitudeUv: Double = 20.0,
    /** Height relative to the usual one: score 0 at or below `lowAmplitudeRatio`, 1 from `okAmplitudeRatio`. */
    val lowAmplitudeRatio: Double = 0.15,
    val okAmplitudeRatio: Double = 0.5,
    /** Taller than this many times the usual height: a spike, not a beat. */
    val spikeAmplitudeRatio: Double = 6.0,
    /** Taller than this many times the usual height: the score falls linearly to 0 at [spikeAmplitudeRatio]. */
    val tallAmplitudeRatio: Double = 3.0,
    /** The two detectors agree if their beats are this close. */
    val agreementMs: Double = 120.0,
    /** Score factor for a beat only one detector saw. */
    val disagreementFactor: Double = 0.4,
    /** A beat with a score at or above this counts as good. */
    val goodBeatSqi: Double = 0.6,
    /**
     * Motion (accelerometer RMS in mG, see `Motion.kt`) in the second around the beat: score 1 at or
     * below `motionOkMg`, 0 at or above `motionBadMg`, linear in between. At rest it is 3-5 mG,
     * a day of normal activity reaches 10-50 mG typically and a few hundred when walking briskly.
     * Calibrated on one night and one day (3 h): the ECG itself stays mostly clean in strong motion
     * (slope noise only doubles) and the strap's RR intervals still agree with our beat counts, so
     * motion is only a mild extra: the cut-off 0.6 falls at 300 mG and discards 1.4 % of the day's beats.
     */
    val motionOkMg: Double = 100.0,
    val motionBadMg: Double = 600.0,
)

/**
 * Why a beat got its score. All scores are 0..1, [sqi] is their combination.
 * [amplitudeUv] is the peak-to-peak height in the 0.5-40 Hz signal; [slopeRatio] and [hfRatio]
 * are the two noise measures near the beat divided by that height (see [QualityConfig]).
 */
data class BeatQuality(
    val sqi: Double,
    val amplitudeUv: Double,
    val amplitudeScore: Double,
    val slopeRatio: Double,
    val slopeScore: Double,
    val hfRatio: Double,
    val hfScore: Double,
    val agreed: Boolean,
    /** Accelerometer motion around the beat in mG; null if there is no ACC data (not penalised). */
    val motionMg: Double? = null,
    val motionScore: Double = 1.0,
) {
    /**
     * This quality with the accelerometer added (P3.4 step 2): [sqi] is multiplied by the motion
     * score. The ECG-only [EcgBeatStream] cannot know the motion, which is on the UTC time line
     * (see `Motion.kt`), so the caller adds it. No ACC data ([motionMg] = null) changes nothing.
     */
    fun withMotion(motionMg: Double?, config: QualityConfig = QualityConfig()): BeatQuality {
        if (motionMg == null) return this
        val score = ramp(motionMg, config.motionOkMg, config.motionBadMg)
        return copy(sqi = sqi * score, motionMg = motionMg, motionScore = score)
    }
}

/** 1 at or below [ok], 0 at or above [bad], straight line in between. */
internal fun ramp(value: Double, ok: Double, bad: Double) = (1 - (value - ok) / (bad - ok)).coerceIn(0.0, 1.0)

/**
 * A beat from the main detector with its quality. [position] is in samples since the start.
 * [snippet] is the 0.5-40 Hz signal around the beat in the layout of [BeatShape] (R at
 * [BeatShape.CENTER]); null if the segment starts or ends too close to the beat.
 */
class ScoredBeat(val position: Double, val quality: BeatQuality, val good: Boolean, val snippet: DoubleArray? = null)

/**
 * The beat pipeline of one gap-free stretch of ECG: main QRS detector, a second detector to
 * cross-check it, and a quality score per beat. Feed frames to [process], call [finish] at the
 * end of the stretch. Beats come out in order, a little later than from [QrsDetector] alone,
 * because noise is judged 0.4 s *after* the beat too.
 *
 * Score of a beat = amplitude score * slope score * hf score * (1 if both detectors saw it, else 0.4):
 * - amplitude: ~0 for a flat line or tiny beat, ~0 for a huge spike, relative to recent beats
 * - noise: slope noise (electrode and motion artifact) and >40 Hz noise (muscle) near the beat,
 *   relative to its height
 * Comparison with a normal-beat template is added in P4.2 (it needs the classifier's template, and a
 * low correlation alone cannot tell noise from a real PVC).
 *
 * Not thread-safe.
 */
class EcgBeatStream(private val config: QualityConfig = QualityConfig()) {
    private val fs = config.sampleRateHz
    private val qrs = QrsDetector(config.qrs)
    private var second = SlopeBeatDetector(fs)
    private val filters = EcgFilters(EcgFilterConfig(sampleRateHz = fs))
    private val morphology = filters.morphology
    private val noiseFilter = FilterCascade.butterworthHighPass(fs, config.hfHighPassHz, 2)

    private val context = (config.contextMs * fs / 1000).roundToInt()
    private val qrsExclude = (config.qrsExcludeMs * fs / 1000).roundToInt()
    private val agreement = (config.agreementMs * fs / 1000).roundToInt()
    private val morphDelay = ceil(filters.morphologyDelayMs * fs / 1000).toInt()
    private val shapeNeed = BeatShape.POST + BeatShape.MARGIN + morphDelay // samples after R the snippet needs

    private val morphBuf = DoubleArray(RING)
    private val noiseSqBuf = DoubleArray(RING)
    private var count = 0

    private val waiting = ArrayDeque<Double>() // beats whose context is not complete yet
    private val secondPeaks = ArrayDeque<Int>()
    private val heights = ArrayDeque<Double>()
    private val secondOut = ArrayList<Int>()
    private val result = ArrayList<ScoredBeat>()

    /** Feeds one frame (µV). Returns the beats whose score is now known. */
    fun process(samples: IntArray): List<ScoredBeat> {
        result.clear()
        // Work in pieces of at most CHUNK samples: the ring buffers keep only the last RING
        // samples, so beats must be scored before much more than their context has passed.
        var start = 0
        while (start < samples.size) {
            val end = minOf(samples.size, start + CHUNK)
            secondOut.clear()
            for (i in start until end) {
                val x = samples[i].toDouble()
                val m = morphology.process(x)
                val h = noiseFilter.process(x)
                morphBuf[count and MASK] = m
                noiseSqBuf[count and MASK] = h * h
                second.add(m, secondOut)
                count++
            }
            secondPeaks += secondOut
            for (p in qrs.process(samples.copyOfRange(start, end))) waiting.addLast(p)
            emit(complete = false)
            start = end
        }
        return ArrayList(result)
    }

    /** End of the stretch: scores the remaining beats with whatever context exists. */
    fun finish(): List<ScoredBeat> {
        result.clear()
        for (p in qrs.finish()) waiting.addLast(p)
        emit(complete = true)
        return ArrayList(result)
    }

    fun reset() {
        qrs.reset()
        morphology.reset()
        noiseFilter.reset()
        second = SlopeBeatDetector(fs)
        morphBuf.fill(0.0); noiseSqBuf.fill(0.0)
        count = 0
        waiting.clear(); secondPeaks.clear(); heights.clear()
    }

    private fun emit(complete: Boolean) {
        while (waiting.isNotEmpty()) {
            val pos = waiting.first()
            if (!complete && pos + max(context, shapeNeed) > count - 1) break // 0.4 s after the beat is not here yet
            waiting.removeFirst()
            val q = score(pos)
            result += ScoredBeat(pos, q, q.sqi >= config.goodBeatSqi, snippetAt(pos))
        }
        // Forget second-detector peaks that no beat can match any more. The main detector reports a
        // beat ~0.2 s late, a search-back beat up to ~3 s late, so peaks are kept for 4 s.
        val oldest = min(waiting.firstOrNull() ?: count.toDouble(), count - 4 * fs) - agreement - 1
        while (secondPeaks.isNotEmpty() && secondPeaks.first() < oldest) secondPeaks.removeFirst()
    }

    /** The beat snippet (P4.1), or null if part of it lies outside the signal we have. */
    private fun snippetAt(pos: Double): DoubleArray? {
        val center = pos.roundToInt() + morphDelay // the filtered signal lags by morphDelay
        val from = center - BeatShape.CENTER
        val to = from + BeatShape.SNIPPET_LENGTH - 1
        if (from < 0 || to > count - 1 || count - 1 - from >= RING) return null
        return DoubleArray(BeatShape.SNIPPET_LENGTH) { morphBuf[(from + it) and MASK] }
    }

    private fun score(pos: Double): BeatQuality {
        val c = pos.roundToInt()
        val last = count - 1

        // beat height: peak to peak of the 0.5-40 Hz signal around the QRS (it lags by morphDelay)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (j in max(0, c - 15)..min(last, c + 15 + morphDelay)) {
            val v = morphBuf[j and MASK]
            lo = min(lo, v); hi = max(hi, v)
        }
        val amplitude = if (hi >= lo) hi - lo else 0.0

        // usual height = median of the recent beats (including this one)
        heights.addLast(amplitude)
        if (heights.size > config.amplitudeHistory) heights.removeFirst()
        val usual = heights.sorted().let { it[it.size / 2] }
        val ratio = if (usual > 0) amplitude / usual else 0.0
        val amplitudeScore = when {
            amplitude < config.minAmplitudeUv -> 0.0
            ratio > config.spikeAmplitudeRatio -> 0.0
            ratio > config.tallAmplitudeRatio -> ramp(ratio, config.tallAmplitudeRatio, config.spikeAmplitudeRatio)
            else -> ((ratio - config.lowAmplitudeRatio) / (config.okAmplitudeRatio - config.lowAmplitudeRatio)).coerceIn(0.0, 1.0)
        }

        // Noise near the beat, not counting the QRS itself: sample-to-sample change of the
        // 0.5-40 Hz signal (slope) and power of the >40 Hz signal (hf), both relative to the beat height.
        // The slope noise is the *median* step (scaled to the RMS of Gaussian noise), not the RMS: a
        // neighbouring QRS inside the window (premature beats are close to their neighbours) would
        // otherwise count as noise, while the median ignores up to ~40 % of such samples.
        var hfSq = 0.0
        var n = 0
        val steps = DoubleArray(2 * context + 1)
        for (j in max(1, c - context)..min(last, c + context)) {
            if (abs(j - c) <= qrsExclude) continue
            steps[n] = abs(morphBuf[j and MASK] - morphBuf[(j - 1) and MASK])
            hfSq += noiseSqBuf[j and MASK]
            n++
        }
        val medianStep = if (n > 0) steps.copyOf(n).also { it.sort() }.let { it[n / 2] } else 0.0
        // Noise is compared with the beat's height, but never with more than the usual height: an
        // artifact many times taller than the real beats would otherwise look clean (P3.7).
        val reference = if (usual > 0) min(amplitude, usual) else amplitude
        val slopeRatio = if (n > 0 && reference > 0) medianStep / MEDIAN_TO_RMS / reference else if (n > 0) Double.POSITIVE_INFINITY else 0.0
        val hfRatio = if (n > 0 && reference > 0) sqrt(hfSq / n) / reference else if (n > 0) Double.POSITIVE_INFINITY else 0.0
        val slopeScore = ramp(slopeRatio, config.slopeOkRatio, config.slopeBadRatio)
        val hfScore = ramp(hfRatio, config.hfOkRatio, config.hfBadRatio)

        // does the second detector have a beat nearby? (its index is a few samples before the R peak)
        val agreed = secondPeaks.any { abs(it - pos) <= agreement }

        val sqi = amplitudeScore * slopeScore * hfScore * (if (agreed) 1.0 else config.disagreementFactor)
        return BeatQuality(sqi, amplitude, amplitudeScore, slopeRatio, slopeScore, hfRatio, hfScore, agreed)
    }


    private companion object {
        const val RING = 2048
        const val MEDIAN_TO_RMS = 0.6745 // median of |x| for Gaussian noise with RMS 1
        const val CHUNK = 256 // must stay well below RING minus the context and detector latency
        const val MASK = RING - 1
    }
}
