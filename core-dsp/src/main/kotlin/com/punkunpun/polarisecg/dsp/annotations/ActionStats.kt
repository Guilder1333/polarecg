package com.punkunpun.polarisecg.dsp.annotations

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.USABLE_QUALITY
import com.punkunpun.polarisecg.dsp.analysis.WINDOW_MS
import com.punkunpun.polarisecg.dsp.analysis.Window

/** Name used for the time when no action was running (before the first one, or after "no action"). */
const val NO_ACTION = "(none)"

/** Time the events' "before" and "after" figures cover. */
const val EVENT_CONTEXT_MS = 5 * 60_000L

/**
 * What the heart did during one action (P5.12). Only **usable** windows (`quality` >= [USABLE_QUALITY]) count for the
 * rates, so a stretch of motion noise does not look like a calm one; [totalMs] is the whole time of the action.
 * Each 10 s window belongs to the action that runs at its middle.
 */
data class ActionSummary(
    val name: String,
    val totalMs: Long,
    val usableMs: Long,
    val meanHrBpm: Double?,
    val meanMotionMg: Double?,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
    /** Of [v], how many are questionable. */
    val vDoubt: Int,
) {
    private val hours: Double get() = usableMs / 3_600_000.0
    val vPerHour: Double? get() = if (hours > 0) v / hours else null
    val sPerHour: Double? get() = if (hours > 0) s / hours else null

    /** Share of V / S among the beats that could be judged (N + S + V), in percent. */
    val vPercent: Double? get() = (n + s + v).takeIf { it > 0 }?.let { 100.0 * v / it }
    val sPercent: Double? get() = (n + s + v).takeIf { it > 0 }?.let { 100.0 * s / it }
}

/**
 * One summary per action name (plus [NO_ACTION] if marks exist at all), biggest [ActionSummary.totalMs] first.
 * Empty if the recording has no action marks.
 */
fun summarizeActions(result: AnalysisResult, spans: List<ActionSpan>, hasMarks: Boolean): List<ActionSummary> {
    if (spans.isEmpty() && !hasMarks) return emptyList()
    val nameOf = { w: Window -> actionAt(spans, w.startUtcMs + w.durationMs / 2) ?: NO_ACTION }

    class Acc {
        var total = 0L
        var usable = 0L
        var hrSum = 0.0
        var hrN = 0
        var motionSum = 0.0
        var motionN = 0
        var n = 0
        var s = 0
        var v = 0
        var q = 0
        var vDoubt = 0
    }
    val accs = LinkedHashMap<String, Acc>()
    val usableWindow = HashMap<Long, String>() // window start -> action name, only for the usable windows
    for (w in result.windows) {
        val a = accs.getOrPut(nameOf(w)) { Acc() }
        a.total += w.durationMs
        if (w.quality >= USABLE_QUALITY) {
            a.usable += w.durationMs
            w.meanHrBpm?.let { a.hrSum += it; a.hrN++ }
            w.motionMg?.let { a.motionSum += it; a.motionN++ }
            usableWindow[w.startUtcMs] = nameOf(w)
        }
    }
    for (b in result.beats) {
        val name = usableWindow[b.utcMs - Math.floorMod(b.utcMs, WINDOW_MS)] ?: continue
        val a = accs.getValue(name)
        when (b.cls) {
            "N" -> a.n++
            "S" -> a.s++
            "V" -> { a.v++; if (b.vDoubt) a.vDoubt++ }
            "Q" -> a.q++
        }
    }
    return accs.map { (name, a) ->
        ActionSummary(
            name, a.total, a.usable,
            if (a.hrN > 0) a.hrSum / a.hrN else null,
            if (a.motionN > 0) a.motionSum / a.motionN else null,
            a.n, a.s, a.v, a.q, a.vDoubt,
        )
    }.sortedByDescending { it.totalMs }
}

/** The heart around one event: mean heart rate and number of S+V beats in the [EVENT_CONTEXT_MS] before and after it. */
data class EventContext(
    val mark: Mark,
    val hrBeforeBpm: Double?,
    val hrAfterBpm: Double?,
    val ectopicBefore: Int,
    val ectopicAfter: Int,
)

/** Context for every event mark (not for actions). Usable windows only for the heart rate; every S/V beat counts. */
fun eventContexts(result: AnalysisResult, marks: List<Mark>): List<EventContext> =
    marks.filter { !it.isAction }.map { m ->
        fun hr(from: Long, to: Long) = result.windows
            .filter { it.quality >= USABLE_QUALITY && it.startUtcMs >= from && it.startUtcMs < to }
            .mapNotNull { it.meanHrBpm }.takeIf { it.isNotEmpty() }?.average()
        fun ectopic(from: Long, to: Long) = result.beats.count { (it.cls == "S" || it.cls == "V") && it.utcMs >= from && it.utcMs < to }
        EventContext(
            m,
            hr(m.timeMs - EVENT_CONTEXT_MS, m.timeMs), hr(m.timeMs, m.timeMs + EVENT_CONTEXT_MS),
            ectopic(m.timeMs - EVENT_CONTEXT_MS, m.timeMs), ectopic(m.timeMs, m.timeMs + EVENT_CONTEXT_MS),
        )
    }

/** End of the analysed data: the end of the last 10 s window (0 if there is none). */
fun dataEndMs(result: AnalysisResult): Long = result.windows.lastOrNull()?.let { it.startUtcMs + it.durationMs } ?: 0L
