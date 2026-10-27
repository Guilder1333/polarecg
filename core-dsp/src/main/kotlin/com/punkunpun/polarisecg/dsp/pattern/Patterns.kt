package com.punkunpun.polarisecg.dsp.pattern

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.Event
import com.punkunpun.polarisecg.dsp.classify.BeatClass

/**
 * Settings of the pattern detector (plan §7.6, P4.4).
 */
data class PatternConfig(
    /** An interval longer than this between two good beats is a pause. */
    val pauseMs: Long = 2_000,
    /** Bigeminy / trigeminy need at least this many full cycles (N X, N N X). */
    val minCycles: Int = 3,
)

/** Event types written to `events.csv` by [detectPatterns]. `V_` = ventricular, `S_` = premature supraventricular. */
object PatternType {
    const val PAUSE = "PAUSE"
    fun couplet(cls: String) = "${cls}_COUPLET"
    fun triplet(cls: String) = "${cls}_TRIPLET"
    fun run(cls: String) = "${cls}_RUN"
    fun bigeminy(cls: String) = "${cls}_BIGEMINY"
    fun trigeminy(cls: String) = "${cls}_TRIGEMINY"
}

/**
 * Patterns in the class sequence of [beats] (they must be in time order).
 *
 * For each ectopic class X (V and S):
 * - **couplet** = exactly 2 X in a row, **triplet** = 3, **run** = 4 or more (one event per run; the
 *   plan's "run (>= 3 V)" is covered by triplet + run). The detail says the rate.
 * - **bigeminy** = N X N X N X ... (at least [PatternConfig.minCycles] cycles), **trigeminy** =
 *   N N X N N X ... Only N counts as the "normal" beat in between; a Q beat or a gap in the signal
 *   (another segment) ends a pattern, since nothing can be said about it.
 *
 * Plus **pauses**: an interval over [PatternConfig.pauseMs] between two *good* beats of one segment.
 *
 * Events are sorted by time. The duration of a pattern is from its first to its last beat.
 */
fun detectPatterns(beats: List<Beat>, config: PatternConfig = PatternConfig()): List<Event> {
    val events = ArrayList<Event>()
    for (cls in listOf(BeatClass.VENTRICULAR, BeatClass.PREMATURE)) {
        events += runsOf(beats, cls)
        events += periodic(beats, cls, period = 2, config.minCycles)
        events += periodic(beats, cls, period = 3, config.minCycles)
    }
    for (i in 1 until beats.size) {
        val a = beats[i - 1]
        val b = beats[i]
        if (a.segment == b.segment && a.good && b.good && b.utcMs - a.utcMs > config.pauseMs) {
            events += Event(a.utcMs, PatternType.PAUSE, b.utcMs - a.utcMs, "%.1f s between two beats".format((b.utcMs - a.utcMs) / 1000.0))
        }
    }
    return events.sortedBy { it.utcMs } // sortedBy returns a new, sorted list
}

private fun rateText(first: Beat, last: Beat, count: Int): String {
    val ms = last.utcMs - first.utcMs
    return if (count > 1 && ms > 0) "$count beats, %.0f bpm".format(60_000.0 * (count - 1) / ms) else "$count beats"
}

/** Consecutive beats of class [cls] (same segment): 2 = couplet, 3 = triplet, more = run. */
private fun runsOf(beats: List<Beat>, cls: String): List<Event> {
    val out = ArrayList<Event>()
    var start = -1
    fun close(endExclusive: Int) {
        val n = endExclusive - start
        if (start >= 0 && n >= 2) {
            val type = when (n) { 2 -> PatternType.couplet(cls); 3 -> PatternType.triplet(cls); else -> PatternType.run(cls) }
            val first = beats[start]
            val last = beats[endExclusive - 1]
            out += Event(first.utcMs, type, last.utcMs - first.utcMs, rateText(first, last, n))
        }
        start = -1
    }
    for (i in beats.indices) {
        val continues = beats[i].cls == cls && (start < 0 || beats[i].segment == beats[i - 1].segment)
        if (continues) {
            if (start < 0) start = i
        } else {
            close(i)
            if (beats[i].cls == cls) start = i
        }
    }
    close(beats.size)
    return out
}

/**
 * Stretches where every [period]-th beat is [cls] and all the beats in between are N, in one
 * segment, with at least [minCycles] cycles. Period 2 = bigeminy, 3 = trigeminy.
 */
private fun periodic(beats: List<Beat>, cls: String, period: Int, minCycles: Int): List<Event> {
    val out = ArrayList<Event>()
    var i = 0
    while (i < beats.size) {
        if (beats[i].cls != cls) { i++; continue }
        // grow from this ectopic beat: N..N X, N..N X, ... as far as it goes
        var end = i // index of the last X of the pattern so far
        var cycles = 1
        while (true) {
            val next = end + period
            if (next >= beats.size) break
            val ok = (end + 1 until next).all { beats[it].cls == BeatClass.NORMAL } && beats[next].cls == cls &&
                (end..next).all { beats[it].segment == beats[i].segment }
            if (!ok) break
            end = next; cycles++
        }
        if (cycles >= minCycles) {
            val first = beats[i]
            val last = beats[end]
            val type = if (period == 2) PatternType.bigeminy(cls) else PatternType.trigeminy(cls)
            out += Event(first.utcMs, type, last.utcMs - first.utcMs, "$cycles cycles, ${rateText(first, last, end - i + 1)}")
            i = end + 1
        } else {
            i++
        }
    }
    return out
}

/** How many beats of each class there were in one UTC hour (P4.4: burden). */
class HourlyBurden(
    val startUtcMs: Long,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
) {
    /** Beats that could be judged (N + S + V). */
    val judged: Int get() = n + s + v
    val vPercent: Double? get() = if (judged > 0) 100.0 * v / judged else null
    val sPercent: Double? get() = if (judged > 0) 100.0 * s / judged else null
}

const val HOUR_MS = 3_600_000L

/**
 * V and S beats per UTC hour as a share of the beats that could be judged (poor-quality beats are
 * counted separately as Q and not in the percentages). Hours without any beat are left out.
 */
fun hourlyBurden(beats: List<Beat>): List<HourlyBurden> {
    val byHour = beats.groupBy { Math.floorDiv(it.utcMs, HOUR_MS) } // Map: hour number -> its beats
    return byHour.toSortedMap().map { (hour, bs) ->
        HourlyBurden(
            hour * HOUR_MS,
            n = bs.count { it.cls == BeatClass.NORMAL },
            s = bs.count { it.cls == BeatClass.PREMATURE },
            v = bs.count { it.cls == BeatClass.VENTRICULAR },
            q = bs.count { it.cls == BeatClass.UNCLASSIFIABLE },
        )
    }
}
