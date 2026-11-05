package com.punkunpun.polarisecg.dsp.event

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.Event
import com.punkunpun.polarisecg.dsp.pattern.PatternConfig
import com.punkunpun.polarisecg.dsp.pattern.detectPatterns

/**
 * Finds the events (couplets, runs, bigeminy, pauses... see `PatternType`) in a live stream of beats (P5.4).
 *
 * It does not duplicate the rules: it keeps the most recent beats and runs the very same [detectPatterns] on them, so the
 * phone and `analyze` cannot disagree about what an event is. Two things are added for the live case:
 * - an event is **reported once, when it is over**: a pattern is final when at least [settleBeats] beats follow its last beat
 *   (a bigeminy is over two beats after its last V, a trigeminy three; a couplet may still grow into a triplet until then);
 * - the memory is bounded: past [maxBeats] the oldest third is dropped, and events that start in it are reported at once,
 *   even if still going (a bigeminy of an hour is reported in pieces rather than never).
 *
 * Call [add] with the finished beats in time order, and [finish] at the end of the recording. Not thread-safe.
 */
class LiveEventDetector(
    private val config: PatternConfig = PatternConfig(),
    private val maxBeats: Int = 1_200,
    private val settleBeats: Int = 4,
) {
    private class Span(val type: String, val startUtcMs: Long, val endUtcMs: Long)

    private val window = ArrayList<Beat>()
    private val reported = ArrayList<Span>() // what was reported lately, to recognise the cut-off remains of the same pattern

    init {
        require(maxBeats >= 30 && settleBeats >= 1) { "window too small" }
    }

    /** Adds the next finished beat; returns the events that became final with it (usually none). */
    fun add(beat: Beat): List<Event> {
        window += beat
        val out = ArrayList<Event>()
        if (window.size > maxBeats) {
            val cut = maxBeats / 3
            val cutUtcMs = window[cut].utcMs
            // Events that start in the part to be dropped can no longer grow into a view of their own: report them now.
            for (e in detectPatterns(window)) if (e.utcMs < cutUtcMs) take(e, out)
            window.subList(0, cut).clear() // subList is a view: clearing it removes those elements from `window`
        }
        for (e in detectPatterns(window)) if (isFinal(e)) take(e, out)
        out.sortBy { it.utcMs }
        pruneReported(beat.utcMs)
        return out
    }

    /** End of the recording: everything not reported yet, final or not. */
    fun finish(): List<Event> {
        val out = ArrayList<Event>()
        for (e in detectPatterns(window)) take(e, out)
        out.sortBy { it.utcMs }
        window.clear()
        reported.clear()
        return out
    }

    /** The beats in the window whose time is within [fromUtcMs]..[toUtcMs] (for the markers on a clip). */
    fun beatsBetween(fromUtcMs: Long, toUtcMs: Long): List<Beat> = window.filter { it.utcMs in fromUtcMs..toUtcMs }

    private fun isFinal(e: Event): Boolean {
        val end = e.utcMs + e.durationMs
        var after = 0
        for (i in window.indices.reversed()) { // from the newest beat backwards, until we are at the event's end
            if (window[i].utcMs <= end) break
            after++
            if (after >= settleBeats) return true
        }
        return false
    }

    /** Adds [e] to [out] unless the same kind of pattern was already reported over (part of) this stretch of time. */
    private fun take(e: Event, out: MutableList<Event>) {
        val end = e.utcMs + e.durationMs
        if (reported.any { it.type == e.type && e.utcMs <= it.endUtcMs && end >= it.startUtcMs }) return
        reported += Span(e.type, e.utcMs, end)
        out += e
    }

    private fun pruneReported(nowUtcMs: Long) {
        if (reported.size > 64) reported.removeAll { it.endUtcMs < nowUtcMs - 2 * 3_600_000L }
    }
}
