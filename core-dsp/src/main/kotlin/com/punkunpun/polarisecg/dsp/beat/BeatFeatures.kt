package com.punkunpun.polarisecg.dsp.beat

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import kotlin.math.abs

/**
 * Settings of the beat feature extraction (plan §7.5, P4.1). Starting points; tune on data.
 */
data class BeatFeatureConfig(
    val sampleRateHz: Double = ECG_SAMPLE_RATE_HZ.toDouble(),
    val template: BeatTemplateConfig = BeatTemplateConfig(),
    /** Number of recent normal RR intervals whose median is the reference RR. */
    val rrHistory: Int = 9,
    /** The reference RR needs at least this many intervals. */
    val rrMinHistory: Int = 3,
    /** An interval joins the reference only if it is within this fraction of the current reference... */
    val rrAcceptLow: Double = 0.7,
    val rrAcceptHigh: Double = 1.3,
    /** ...unless this many in a row were refused: then the rhythm really changed, start over. */
    val rrRestartAfter: Int = 4,
    /** A beat counts as normal-timed (may teach the template) if its prematurity is in this range. */
    val normalTimedLow: Double = 0.85,
    val normalTimedHigh: Double = 1.25,
    /** The upside-down beat must correlate at least this much (and more than the upright one) for a polarity flip. */
    val flipCorr: Double = 0.6,
    /** Number of recent beats whose median alignment shift is the "usual" one. */
    val shiftHistory: Int = 9,
    /**
     * A beat whose shift differs from the usual one by at least this many samples was marked on
     * another feature of the QRS than its neighbours (R instead of S, P3.8): [BeatFeatures.alignMs]
     * corrects it. Smaller differences are alignment noise (about +-0.5 sample) and are left alone.
     */
    val alignFromSamples: Double = 2.0,
)

/** One beat as the extractor needs it: time in ms, the snippet of [BeatShape] (null if incomplete) and quality. */
class BeatInput(val timeMs: Double, val snippet: DoubleArray?, val good: Boolean)

/**
 * Features of one beat (plan §7.5). Everything that needs something that is not there is null:
 * no neighbour, no reference RR yet, no template yet, no complete snippet.
 *
 * - [rrPreMs], [rrPostMs]: intervals before and after the beat (null at the start / end of a segment).
 * - [rrRefMs]: median of recent normal intervals; [rrSpread] = how irregular they are (median distance from
 *   the reference, as a fraction of it: 0.02 = steady sinus rhythm, above ~0.1 = irregular, where "early" means
 *   little); [prematurity] = rrPre / rrRef (below 1 = early).
 * - [compensation] = (rrPre + rrPost) / (2 rrRef): about 1 after a PVC (full compensatory pause),
 *   clearly below 1 for many PACs.
 * - [corr]: correlation of the beat (-250..+400 ms) with the normal template at the best slide;
 *   [shift] = that slide in samples, fractional: the beat's R feature lies that far after the marked position,
 *   so `timeMs + shift * 1000 / 130` is the beat time aligned to the template (P3.8).
 * - [qrsWidthMs]: coarse (+-8 ms at 130 Hz); [widthRatio] = against the template's width (a wearer with a bundle
 *   branch block has wide normal beats, so the absolute width alone says little). [ampRatio], [areaRatio]: QRS height and area against
 *   the template. [polarityFlip]: the beat looks like the template upside down.
 * - [alignMs]: add it to [timeMs] to get the beat time on the same QRS feature as its neighbours; 0
 *   unless the beat was marked 2 or more samples away from where its neighbours are (P3.8). On a night
 *   of 25 000 beats this brings the intervals that differ from the strap's by more than 10 ms from
 *   36 to 12, without adding noise to the others.
 * - [normalTimed]: timing alone says "normal" (used for learning the template).
 */
class BeatFeatures(
    val timeMs: Double,
    val good: Boolean,
    val rrPreMs: Double?,
    val rrPostMs: Double?,
    val rrRefMs: Double?,
    val rrSpread: Double?,
    val prematurity: Double?,
    val compensation: Double?,
    val corr: Double?,
    val shift: Double?,
    val qrsWidthMs: Double?,
    val widthRatio: Double?,
    val ampRatio: Double?,
    val areaRatio: Double?,
    val polarityFlip: Boolean,
    val normalTimed: Boolean,
    val alignMs: Double = 0.0,
)

/**
 * Computes [BeatFeatures] for a stream of beats and keeps the normal-beat template (P4.2).
 *
 * Features of a beat need the next beat's time (rrPost), so each beat is reported when the next one
 * arrives; [finish] reports the last one. Results come out in input order, one per beat. Call
 * [finish] at the end of every gap-free segment: intervals are never computed across a gap, but
 * the template and the reference RR carry over.
 * Not thread-safe.
 */
class BeatFeatureExtractor(private val config: BeatFeatureConfig = BeatFeatureConfig()) {
    val template = BeatTemplate(config.template)

    private var previous: BeatInput? = null // the beat before [pending], same segment
    private var pending: BeatInput? = null // waits for its successor

    private val recentRr = ArrayDeque<Double>() // normal intervals: the reference
    private val lastRr = ArrayDeque<Double>() // the latest intervals whatever they look like (to restart from)
    private var refused = 0
    private val recentShifts = ArrayDeque<Double>()

    /** Adds the next beat; returns the features of the beat before it (empty for the first one of a segment). */
    fun add(beat: BeatInput): List<BeatFeatures> {
        val out = ArrayList<BeatFeatures>(2)
        drain(out, next = beat)
        previous = pending
        pending = beat
        return out
    }

    /** End of a segment (or of the recording): reports the last beat. The template is kept for the next segment. */
    fun finish(): List<BeatFeatures> {
        val out = ArrayList<BeatFeatures>(1)
        drain(out, next = null)
        previous = null
        pending = null
        return out
    }

    private fun drain(out: MutableList<BeatFeatures>, next: BeatInput?) {
        val cur = pending ?: return
        out += compute(previous, cur, next)
    }

    private fun compute(prev: BeatInput?, cur: BeatInput, next: BeatInput?): BeatFeatures {
        val rrPre = prev?.let { cur.timeMs - it.timeMs }
        val rrPost = next?.let { it.timeMs - cur.timeMs }
        val rrRef = if (recentRr.size >= config.rrMinHistory) median(recentRr) else null
        val rrSpread = if (rrRef != null && rrRef > 0) median(recentRr.map { abs(it - rrRef) }) / rrRef else null
        val prematurity = if (rrPre != null && rrRef != null) rrPre / rrRef else null
        val compensation = if (rrPre != null && rrPost != null && rrRef != null) (rrPre + rrPost) / (2 * rrRef) else null
        val normalTimed = prematurity != null && prematurity in config.normalTimedLow..config.normalTimedHigh

        val snippet = cur.snippet
        val match = snippet?.let { template.match(it) }
        var width: Double? = null
        var ampRatio: Double? = null
        var areaRatio: Double? = null
        var widthRatio: Double? = null
        if (snippet != null) {
            val own = core(snippet, 0) // without baseline wander
            width = qrsWidthMs(own, BeatShape.PRE, config.sampleRateHz)
            val t = template.current
            if (t != null) {
                val (height, area) = qrsHeightAndArea(own, BeatShape.PRE)
                val (tHeight, tArea) = qrsHeightAndArea(t, BeatShape.PRE)
                if (tHeight > 0) ampRatio = height / tHeight
                if (tArea > 0) areaRatio = area / tArea
                val tWidth = qrsWidthMs(t, BeatShape.PRE, config.sampleRateHz)
                if (tWidth > 0) widthRatio = width / tWidth
            }
        }
        val flip = match != null && match.inverted >= config.flipCorr && match.inverted > match.corr

        // marked on another point of the QRS than the neighbours? (the shift is relative to the template)
        var alignMs = 0.0
        if (match != null && cur.good) {
            val usual = if (recentShifts.size >= 3) median(recentShifts) else match.shift
            if (abs(match.shift - usual) >= config.alignFromSamples) alignMs = (match.shift - usual) * 1000.0 / config.sampleRateHz
            recentShifts.addLast(match.shift)
            if (recentShifts.size > config.shiftHistory) recentShifts.removeFirst()
        }

        // learning: only good beats with a snippet; the template sees them after the features were taken
        if (cur.good && snippet != null) template.offer(snippet, normalTimed)
        updateReference(rrPre, cur.good, prev?.good == true, rrRef)

        return BeatFeatures(
            cur.timeMs, cur.good, rrPre, rrPost, rrRef, rrSpread, prematurity, compensation,
            match?.corr, match?.shift, width, widthRatio, ampRatio, areaRatio, flip, normalTimed, alignMs,
        )
    }

    /** Adds [rrPre] to the recent normal intervals if both of its beats are good and it looks normal. */
    private fun updateReference(rrPre: Double?, curGood: Boolean, prevGood: Boolean, rrRef: Double?) {
        if (rrPre == null || !curGood || !prevGood) return
        lastRr.addLast(rrPre)
        if (lastRr.size > config.rrHistory) lastRr.removeFirst()
        val plausible = rrRef == null || rrPre in config.rrAcceptLow * rrRef..config.rrAcceptHigh * rrRef
        if (plausible) {
            refused = 0
        } else if (++refused >= config.rrRestartAfter) {
            // the rhythm changed for good: the reference is the latest intervals, as they are
            recentRr.clear()
            recentRr.addAll(lastRr)
            refused = 0
            return
        } else {
            return
        }
        recentRr.addLast(rrPre)
        if (recentRr.size > config.rrHistory) recentRr.removeFirst()
    }

    private fun median(x: Collection<Double>): Double = x.sorted().let { it[it.size / 2] }
}
