package com.punkunpun.polarisecg.dsp.summary

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.hrv.HRV_WINDOW_MS
import com.punkunpun.polarisecg.dsp.hrv.HrvWindow
import com.punkunpun.polarisecg.dsp.hrv.NN_MAX_MS
import com.punkunpun.polarisecg.dsp.hrv.Nn
import com.punkunpun.polarisecg.dsp.hrv.NnTracker
import com.punkunpun.polarisecg.dsp.hrv.hrvWindows

/*
 * The long-term record (P5.6, plan §8 tables `minute_summary` and `hrv_window`): one row per UTC minute and one per
 * 5-minute window, built from the beats as they arrive. The beats themselves may be pruned after a while; these rows
 * stay, so the summary screen can show weeks. The same code runs on the phone (live) and in `analyze` (`minutes.csv`),
 * so the two can be compared.
 */

private const val MINUTE_MS = 60_000L

/**
 * What happened in one UTC minute. Heart rate comes from the NN intervals only (two normal beats with a good signal),
 * so an early beat does not make a spike. [coverage] is the share of the minute covered by intervals between
 * consecutive detected beats (up to 2 s long, within one gap-free segment): a minute with a hole in the signal has less.
 */
class MinuteSummary(
    val startUtcMs: Long,
    val beats: Int,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
    /** Of the [v] beats, how many are questionable. */
    val vDoubt: Int,
    val hrMeanBpm: Double?,
    val hrMinBpm: Double?,
    val hrMaxBpm: Double?,
    val coverage: Double,
    /** Mean accelerometer motion (mG) of the beats; null without ACC data. */
    val motionMg: Double?,
)

/** What a [SummaryBuilder] hands back when minutes or windows are complete. */
class Closed(val minutes: List<MinuteSummary>, val hrv: List<HrvWindow>) {
    val isEmpty: Boolean get() = minutes.isEmpty() && hrv.isEmpty()
}

private val NOTHING = Closed(emptyList(), emptyList())

/**
 * Feed it the beats in time order with [add]; it returns the minutes and 5-minute windows that are complete once a beat
 * of a later minute / window arrives. [finish] returns what is still open (a partial minute at the end of a recording).
 */
class SummaryBuilder {
    private val nnTracker = NnTracker()
    private var previousBeat: Beat? = null

    // the open minute
    private var minute = Long.MIN_VALUE
    private var beats = 0
    private var n = 0
    private var s = 0
    private var v = 0
    private var q = 0
    private var vDoubt = 0
    private var coveredMs = 0L
    private var motionSum = 0.0
    private var motionN = 0
    private val nnInMinute = ArrayList<Nn>()

    // the open 5-minute window
    private var window = Long.MIN_VALUE
    private val nnInWindow = ArrayList<Nn>()

    fun add(b: Beat): Closed {
        val nn = nnTracker.add(b)
        val interval = previousBeat?.takeIf { it.segment == b.segment }?.let { b.utcMs - it.utcMs }
        previousBeat = b

        val minuteOfBeat = Math.floorDiv(b.utcMs, MINUTE_MS) * MINUTE_MS
        val windowOfBeat = Math.floorDiv(b.utcMs, HRV_WINDOW_MS) * HRV_WINDOW_MS
        val minutes = ArrayList<MinuteSummary>(1)
        val hrv = ArrayList<HrvWindow>(1)
        if (minute != Long.MIN_VALUE && minuteOfBeat != minute) closeMinute()?.let { minutes += it }
        if (window != Long.MIN_VALUE && windowOfBeat != window) closeWindow()?.let { hrv += it }
        minute = minuteOfBeat
        window = windowOfBeat

        beats++
        when (b.cls) {
            "N" -> n++
            "S" -> s++
            "V" -> { v++; if (b.vDoubt) vDoubt++ }
            "Q" -> q++
        }
        b.quality.motionMg?.let { motionSum += it; motionN++ }
        if (interval != null && interval in 1..NN_MAX_MS) coveredMs += interval
        if (nn != null) { nnInMinute += nn; nnInWindow += nn }
        return if (minutes.isEmpty() && hrv.isEmpty()) NOTHING else Closed(minutes, hrv)
    }

    /** The minute and the window that are still open (call at the end of a recording). */
    fun finish(): Closed {
        val minutes = listOfNotNull(if (minute != Long.MIN_VALUE) closeMinute() else null)
        val hrv = listOfNotNull(if (window != Long.MIN_VALUE) closeWindow() else null)
        minute = Long.MIN_VALUE
        window = Long.MIN_VALUE
        return Closed(minutes, hrv)
    }

    private fun closeMinute(): MinuteSummary? {
        if (beats == 0) return null
        val hr = nnInMinute.map { 60_000.0 / it.ms }
        val mean = nnInMinute.takeIf { it.isNotEmpty() }?.let { 60_000.0 / (it.sumOf { nn -> nn.ms.toDouble() } / it.size) }
        val result = MinuteSummary(
            minute, beats, n, s, v, q, vDoubt, mean, hr.minOrNull(), hr.maxOrNull(),
            (coveredMs.toDouble() / MINUTE_MS).coerceAtMost(1.0),
            if (motionN > 0) motionSum / motionN else null,
        )
        beats = 0; n = 0; s = 0; v = 0; q = 0; vDoubt = 0; coveredMs = 0; motionSum = 0.0; motionN = 0
        nnInMinute.clear()
        return result
    }

    private fun closeWindow(): HrvWindow? {
        val result = hrvWindows(nnInWindow).firstOrNull() // the NN of one window, so at most one result
        nnInWindow.clear()
        return result
    }
}

/** The same summaries for a whole recording at once (`analyze`). */
fun summarize(beats: List<Beat>): Closed {
    val builder = SummaryBuilder()
    val minutes = ArrayList<MinuteSummary>()
    val hrv = ArrayList<HrvWindow>()
    for (b in beats) builder.add(b).let { minutes += it.minutes; hrv += it.hrv }
    builder.finish().let { minutes += it.minutes; hrv += it.hrv }
    return Closed(minutes, hrv)
}
