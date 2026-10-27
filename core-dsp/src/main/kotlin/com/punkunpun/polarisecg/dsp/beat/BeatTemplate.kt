package com.punkunpun.polarisecg.dsp.beat

/**
 * Settings of the normal beat template (plan §7.5-7.6, P4.2). Starting points; tune on data.
 */
data class BeatTemplateConfig(
    /** Good, normal-timed beats collected before the first template is built. */
    val learnBeats: Int = 8,
    /** While learning, beats must resemble the most typical one at least this much to count. */
    val learnCorr: Double = 0.85,
    /** A beat updates the template only if it correlates at least this much with it. */
    val updateCorr: Double = 0.9,
    /** Weight of a new beat in the running average (0.05 = the template forgets in ~20 beats). */
    val updateRate: Double = 0.05,
    /** Largest slide, in samples, tried when lining a beat up with the template. */
    val maxShift: Int = 5,
    /**
     * Re-learning (the template may be outdated: posture, electrode, or it was learned from the
     * wrong beats): among the last [relearnWindow] good beats at least [relearnShare] fit a
     * second, "candidate" shape (each other, not the template) and more beats fit it than the
     * template. A bigeminy (half the beats of the other shape) or a short PVC run does not
     * qualify; at least [relearnMinBeats] beats must be in the window.
     */
    val relearnWindow: Int = 40,
    val relearnMinBeats: Int = 20,
    val relearnShare: Double = 0.6,
    /** Correlation needed to count as the candidate shape. */
    val relearnCorr: Double = 0.9,
)

/**
 * The running template of the normal beat of the wearer: an average of clean, normal-timed beats
 * (0.5-40 Hz signal, [BeatShape.CORE_LENGTH] samples, R at [BeatShape.PRE]).
 *
 * Feed every beat to [match] (to compare it) and, if it is good, to [offer] (to learn from it).
 * Only beats the caller calls normal-timed change the template, so premature beats never do.
 * Not thread-safe.
 */
class BeatTemplate(private val config: BeatTemplateConfig = BeatTemplateConfig()) {
    private var shape: DoubleArray? = null
    private val learning = ArrayList<DoubleArray>() // snippets collected before the first template
    private var candidate: Candidate? = null
    private val outcomes = ArrayDeque<Fit>() // which shape each of the latest good beats fitted

    private enum class Fit { TEMPLATE, CANDIDATE, NEITHER }

    /** Times the template was replaced because the beat shape really changed. */
    var relearned = 0
        private set

    /** A copy of the template, or null while it is still being learned. */
    val current: DoubleArray? get() = shape?.copyOf()

    val ready: Boolean get() = shape != null

    /** How [snippet] compares with the template; null while there is none. */
    fun match(snippet: DoubleArray): ShapeMatch? = shape?.let { bestMatch(snippet, it, config.maxShift) }

    /** Learns from a good beat; [normalTimed] beats may update the template, others only count towards re-learning. */
    fun offer(snippet: DoubleArray, normalTimed: Boolean) {
        val t = shape
        if (t == null) {
            if (normalTimed) learn(snippet)
            return
        }
        val m = bestMatch(snippet, t, config.maxShift)
        val cand = candidate
        val fit = when {
            m.corr >= config.updateCorr -> Fit.TEMPLATE
            cand != null && cand.add(snippet, config) -> Fit.CANDIDATE
            else -> Fit.NEITHER
        }
        if (fit == Fit.TEMPLATE && normalTimed) {
            val c = core(snippet, m.shiftSamples)
            for (i in t.indices) t[i] += config.updateRate * (c[i] - t[i])
        }
        outcomes.addLast(fit)
        if (outcomes.size > config.relearnWindow) outcomes.removeFirst()
        // a beat that fits nothing starts a new candidate if the old one found no support lately
        if (fit == Fit.NEITHER && (cand == null || Fit.CANDIDATE !in outcomes)) candidate = Candidate(core(snippet, 0))

        val withCandidate = outcomes.count { it == Fit.CANDIDATE }
        val withTemplate = outcomes.count { it == Fit.TEMPLATE }
        if (candidate != null && outcomes.size >= config.relearnMinBeats &&
            withCandidate >= config.relearnShare * outcomes.size && withCandidate > withTemplate
        ) {
            shape = candidate?.mean()
            candidate = null
            outcomes.clear()
            relearned++
        }
    }

    fun reset() {
        shape = null
        learning.clear()
        candidate = null
        outcomes.clear()
        relearned = 0
    }

    private fun learn(snippet: DoubleArray) {
        learning += snippet
        if (learning.size > config.learnBeats) learning.removeAt(0)
        if (learning.size < config.learnBeats) return
        // the most typical beat = the one with the highest total correlation with the others
        val cores = learning.map { core(it, 0) }
        val typical = cores.indices.maxByOrNull { i ->
            cores.indices.sumOf { j -> if (i == j) 0.0 else pearson(cores[i], cores[j]) }
        } ?: return // "?:" = if the left side is null, do this instead
        val reference = cores[typical]
        val members = learning.map { s -> s to bestMatch(s, reference, config.maxShift) }
            .filter { (_, m) -> m.corr >= config.learnCorr }
        if (members.size < config.learnBeats / 2) return // too mixed; the oldest beat drops out, try again
        val mean = DoubleArray(BeatShape.CORE_LENGTH)
        for ((s, m) in members) {
            val c = core(s, m.shiftSamples)
            for (i in mean.indices) mean[i] += c[i] / members.size
        }
        shape = mean
        learning.clear()
    }

    /** Beats that fit the template badly but each other well; becomes the template if there are enough. */
    private class Candidate(first: DoubleArray) {
        private val sum = first.copyOf()
        var count = 1
            private set

        fun mean() = DoubleArray(sum.size) { sum[it] / count }

        /** Adds [snippet] if it resembles the candidate; false if it does not. */
        fun add(snippet: DoubleArray, config: BeatTemplateConfig): Boolean {
            val m = bestMatch(snippet, mean(), config.maxShift)
            if (m.corr < config.relearnCorr) return false
            val c = core(snippet, m.shiftSamples)
            for (i in sum.indices) sum[i] += c[i]
            count++
            return true
        }
    }
}
