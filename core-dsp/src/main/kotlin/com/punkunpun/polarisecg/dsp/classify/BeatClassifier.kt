package com.punkunpun.polarisecg.dsp.classify

import com.punkunpun.polarisecg.dsp.beat.BeatFeatures

/** The classes a beat gets (AAMI-like, plan §7.6). Beats carry them as one-letter strings. */
object BeatClass {
    const val NORMAL = "N"
    /** Premature, normal shape (supraventricular). */
    const val PREMATURE = "S"
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
    /** Shape: QRS area this many times the template's (a big, broad deflection). */
    val vAreaRatio: Double = 2.0,
    /** Timing: a beat with normal shape is premature (S) if its prematurity is below this... */
    val sPrematurity: Double = 0.85,
    /** ...and the rhythm is steady (see `BeatFeatures.rrSpread`): in an irregular rhythm "early" says little. */
    val sMaxSpread: Double = 0.05,
)

/**
 * Rules v1 (plan §7.6):
 *
 * ```
 * poor signal, or no template yet                          -> Q
 * shape abnormal (low correlation, wide, big, upside down) -> V   (early or not)
 * early (prematurity < 0.85) in a steady rhythm             -> S
 * otherwise                                                -> N
 * ```
 *
 * Stateless: everything it needs is in [BeatFeatures] (the template and the reference RR live in
 * the feature extractor). Honest limits: S needs timing alone (no P-wave at 130 Hz), so it is the
 * weakest class; a ventricular beat that looks like the normal one (e.g. wearer with a bundle
 * branch block) is not found by shape.
 */
class BeatClassifier(private val config: ClassifierConfig = ClassifierConfig()) {
    fun classify(f: BeatFeatures): String {
        val corr = f.corr
        if (!f.good || corr == null) return BeatClass.UNCLASSIFIABLE
        val wide = f.widthRatio?.let { it >= config.vWidthRatio } == true // "?.let ... == true": false if null
        val big = f.areaRatio?.let { it > config.vAreaRatio } == true
        if (f.polarityFlip || corr < config.vCorr || (wide && corr < config.vWideCorr) || big) return BeatClass.VENTRICULAR
        val early = f.prematurity?.let { it < config.sPrematurity } == true
        val steady = f.rrSpread?.let { it <= config.sMaxSpread } == true
        return if (early && steady) BeatClass.PREMATURE else BeatClass.NORMAL
    }
}
