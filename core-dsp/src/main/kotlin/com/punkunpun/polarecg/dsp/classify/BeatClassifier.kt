package com.punkunpun.polarecg.dsp.classify

import com.punkunpun.polarecg.dsp.beat.BeatFeatures
import com.punkunpun.polarecg.dsp.quality.BeatQuality

/** The classes a beat gets (AAMI-like, plan §7.6). Beats carry them as one-letter strings. */
object BeatClass {
    const val NORMAL = "N"
    /** Premature, normal shape (supraventricular). */
    const val PREMATURE = "S"
    /**
     * Long interval before a normal-shaped beat: most likely a beat the detector (or the heart) suppressed. Counted with S
     * in every total (see [isPremature]); kept apart so it can be told from an early beat and labelled on its own.
     */
    const val SUPPRESSED = "SS"
    /** True for S and SS: the classes that add up to the premature-beat counts. */
    fun isPremature(cls: String) = cls == PREMATURE || cls == SUPPRESSED
    /** Ventricular-looking. */
    const val VENTRICULAR = "V"
    /** Cannot be judged: poor signal or no template yet. */
    const val UNCLASSIFIABLE = "Q"
    /** Not classified at all (before the classifier ran). */
    const val UNKNOWN = "U"
}

/**
 * All thresholds of the rule-based classifier (plan §7.6, P4.3) in one place. Chosen on the MIT-BIH
 * DS1 records and checked on DS2 (see docs/DEVELOPMENT_PLAN.md, P4.3); change them here only.
 */
data class ClassifierConfig(
    /** Shape: correlation with the template below this means a different beat. */
    val vCorr: Double = 0.5,
    /** Shape: a QRS this many times as wide as the template's, together with a correlation below [vWideCorr]. */
    val vWidthRatio: Double = 1.5,
    val vWideCorr: Double = 0.9,
    /** Shape: QRS area this many times the template's (a big, broad deflection), together with a correlation below [vWideCorr]:
     * the same shape only larger (a change of amplitude, a stale template) is not a different beat (P4.7). */
    val vAreaRatio: Double = 2.0,
    /**
     * Shape + timing: a beat that is early (below [sPrematurity]) and correlates less than this with the template looks
     * different AND comes early: V, not S. On two labelled nights 36 of 50 such beats on a flat baseline were V for the
     * owner, 13 S, 4 N (P4.7). On time, a correlation between [vCorr] and this is left alone (normal beat on drift, mostly).
     */
    val vGreyCorr: Double = 0.72,
    /**
     * Rescue (P4.7): a beat whose quality index is below the "good" limit is still judged (N/S/V instead of Q) if it matches
     * the normal-beat template this well, both detectors saw it and neither noise measure is high. Noise does not
     * correlate with a template; motion and drift lower the quality index of clean beats. On two labelled nights 52 % of the
     * Q beats that were normal were recovered, 8 % of the real Q came with them; on MIT-BIH, INCART and NSTDB noise detections
     * were caught as before.
     */
    val rescueCorr: Double = 0.75,
    val rescueMaxSlope: Double = 0.15,
    val rescueMaxHf: Double = 0.05,
    /** Timing: a beat with normal shape is premature (S) if its prematurity is below this... */
    val sPrematurity: Double = 0.85,
    /**
     * ...and the rhythm is steady (see `BeatFeatures.rrSpread`): in an irregular rhythm "early" says little. Loosened 0.10 -> 0.15
     * together with [sNextRatio] (P4.8): the next-interval test now carries part of what the steadiness gate did.
     */
    val sMaxSpread: Double = 0.15,
    /** ...and it is this much shorter than the interval before it: a gradual speed-up of the rate is not premature (shortening ~0.95 per beat). 0.9 -> 0.85 (P4.8). */
    val sShortening: Double = 0.85,
    /**
     * ...and it is this much shorter than the interval AFTER it (rrPre / rrPost): a premature beat is followed by a pause, a
     * beat that is only a bit early in a fast stretch is not (P4.8). Measured on 6 labelled nights: S median 0.49, N 1.00; on INCART 0.47 / 1.01.
     * Needs the next beat: without it (end of a segment) the beat is N.
     */
    val sNextRatio: Double = 0.75,
    /**
     * SS (a beat that follows a skipped one): a beat of normal shape, steady rhythm, whose interval before it is
     * - at least [ssLateness] times the reference (a skipped beat makes about 2.0), AND
     * - at least [ssLengthening] times the interval before that (`BeatFeatures.shortening`: the mirror of [sShortening]), AND
     * - followed by an interval of at most [ssMaxNextRatio] times the reference (the rhythm is back: the mirror of the pause test of S).
     * The last two tell a skipped beat from a heart rate that slowed down (reference lags behind). Measured on the 32 gap items the
     * owner labelled "blocked early beat" and 646 labelled "nothing" (10 nights, weighted): the ratio alone gives precision 45 % at 1.7;
     * the three together 97 % / recall 99 % (tuned on those same labels, so a little optimistic).
     */
    val ssLateness: Double = 1.6,
    val ssLengthening: Double = 1.5,
    val ssMaxNextRatio: Double = 1.3,
    /**
     * ...and the interval after it is not a LONG one, i.e. a skipped beat: the mirror of the short-interval test. A premature beat
     * is followed by a pause that makes up for it (compensation = (rrPre + rrPost) / (2 rrRef) is about 1, lower for many PACs);
     * when the detector missed the next beat, rrPost is about two normal intervals and the compensation is 1.4 or more. Then the
     * "pause" is a hole in the detection, not a pause after an early beat, and the beat is N. Without the compensation the test is skipped.
     */
    val sMaxCompensation: Double = 1.35,
    /**
     * A V that rests only on the drift-sensitive shape rules (correlation, area) is "questionable" when
     * the baseline wanders this much around it (`BeatFeatures.driftRatio`, P4.7). Still a V; the flag only
     * says "look at this one". Measured on one night: 32 of 37 normal beats called V were above 0.15, 2 of 24 real V.
     */
    val vDoubtDrift: Double = 0.15,
)

/**
 * Rules v1 (plan §7.6):
 *
 * ```
 * poor signal, or no template yet                          -> Q
 * shape abnormal (low correlation, wide, big, upside down) -> V   (early or not)
 * early and clearly different (correlation < 0.72)         -> V   (P4.7)
 * early (prematurity < 0.85), shorter than the interval before AND after it (<= 0.85 / <= 0.75), steady rhythm (spread <= 0.15) -> S
 * otherwise                                                -> N
 * ```
 *
 * Stateless: everything it needs is in [BeatFeatures] (the template and the reference RR live in
 * the feature extractor). Honest limits: S needs timing alone (no P-wave at 130 Hz), so it is the
 * weakest class; a ventricular beat that looks like the normal one (e.g. wearer with a bundle
 * branch block) is not found by shape.
 */
class BeatClassifier(private val config: ClassifierConfig = ClassifierConfig()) {
    /**
     * True for a V that may be a normal beat, so it is to be reviewed: the baseline drifts around it, or it comes
     * on time (prematurity at least [ClassifierConfig.sPrematurity]) and only the correlation/area rules call it V.
     * On two labelled nights 47 of 49 such on-time V were normal beats for the owner. Early shapes that drift cannot
     * cause (upside down, wide and dissimilar) are never questionable.
     */
    fun vNeedsReview(f: BeatFeatures, quality: BeatQuality? = null): Boolean {
        if (classify(f, quality) != BeatClass.VENTRICULAR || f.polarityFlip) return false
        val corr = f.corr ?: return false
        val onTime = f.prematurity?.let { it >= config.sPrematurity } == true
        if (onTime) return true // also a wide, dissimilar one: 0 of 15 such beats were V for the owner (3 nights)
        val wide = f.widthRatio?.let { it >= config.vWidthRatio } == true
        if (wide && corr < config.vWideCorr) return false
        return (f.driftRatio ?: 0.0) > config.vDoubtDrift
    }

    /** True if [f], a beat of poor quality index, is judged anyway (see [ClassifierConfig.rescueCorr]). */
    fun rescues(f: BeatFeatures, quality: BeatQuality): Boolean {
        val corr = f.corr ?: return false
        return !f.good && quality.agreed && corr >= config.rescueCorr &&
            !(quality.slopeRatio > config.rescueMaxSlope) && !(quality.hfRatio > config.rescueMaxHf) // NaN passes: not measured
    }

    /** [quality] is only needed for the rescue; without it a poor beat is always Q. */
    fun classify(f: BeatFeatures, quality: BeatQuality? = null): String {
        val corr = f.corr
        if (corr == null || (!f.good && !(quality != null && rescues(f, quality)))) return BeatClass.UNCLASSIFIABLE
        val wide = f.widthRatio?.let { it >= config.vWidthRatio } == true // "?.let ... == true": false if null
        val big = f.areaRatio?.let { it > config.vAreaRatio } == true && corr < config.vWideCorr // same shape, only larger: not V
        val early = f.prematurity?.let { it < config.sPrematurity } == true
        if (f.polarityFlip || corr < config.vCorr || (wide && corr < config.vWideCorr) || big || (early && corr < config.vGreyCorr)) {
            return BeatClass.VENTRICULAR
        }
        val steady = f.rrSpread?.let { it <= config.sMaxSpread } == true
        val abrupt = f.shortening?.let { it <= config.sShortening } == true
        // Followed by a pause: rrPre / rrPost (both exist or the beat cannot be called S).
        val pause = f.rrPreMs != null && f.rrPostMs != null && f.rrPostMs > 0 && f.rrPreMs / f.rrPostMs <= config.sNextRatio
        val skippedNext = isLongInterval(f)
        if (early && steady && abrupt && pause && !skippedNext) return BeatClass.PREMATURE
        val late = f.prematurity?.let { it >= config.ssLateness } == true
        val lengthened = f.shortening?.let { it >= config.ssLengthening } == true
        val rhythmBack = f.rrPostMs != null && f.rrRefMs != null && f.rrRefMs > 0 && f.rrPostMs / f.rrRefMs <= config.ssMaxNextRatio
        return if (late && lengthened && rhythmBack && steady) BeatClass.SUPPRESSED else BeatClass.NORMAL
    }

    /**
     * True if the interval after the beat is so long that the detector most likely missed a beat in it (compensation above
     * [ClassifierConfig.sMaxCompensation]): the long counterpart of an early beat. Used by the S rule; the pipeline can also use it to
     * mark the interval as unreliable.
     */
    fun isLongInterval(f: BeatFeatures): Boolean = f.compensation?.let { it > config.sMaxCompensation } == true
}
