package com.punkunpun.polarisecg.dsp.annotations

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.USABLE_QUALITY
import com.punkunpun.polarisecg.dsp.analysis.WINDOW_MS
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The day report (`report.md`): for every stretch of an action the S, V and S+V burden hour by hour since the action
 * began, then a summary per action and the statistics of the whole recording. Layout agreed with the owner in
 * docs/report_example.md; the columns are described in docs/ANALYSIS_FORMAT.md.
 */

private const val HOUR_MS = 3_600_000L

/** Counts of the beats of one stretch of time; only beats in usable windows are counted (like [summarizeActions]). */
private class Tally(var usableMs: Long = 0, var n: Int = 0, var s: Int = 0, var v: Int = 0, var hrSum: Double = 0.0, var hrN: Int = 0, var vDoubt: Int = 0) {
    /** Mean of the windows' heart rates (each window counts once). */
    val meanHr: Double? get() = if (hrN > 0) hrSum / hrN else null
    /** (S + V) among the beats that could be judged (N + S + V), in percent; null if there are none. */
    val burdenPercent: Double? get() = (n + s + v).takeIf { it > 0 }?.let { 100.0 * (s + v) / it }
}

/** One usable 10 s window with its beat counts, so that any stretch of time can be added up quickly. */
private class WinCount(val midMs: Long, val n: Int, val s: Int, val v: Int, val vDoubt: Int, val hrBpm: Double?)

private fun winCounts(result: AnalysisResult, labels: Map<Long, String>): List<WinCount> {
    val index = HashMap<Long, IntArray>() // window start -> n, s, v, questionable v
    for (w in result.windows) if (w.quality >= USABLE_QUALITY) index[w.startUtcMs] = IntArray(4)
    for (b in result.beats) {
        val c = index[b.utcMs - Math.floorMod(b.utcMs, WINDOW_MS)] ?: continue
        val label = labels[b.utcMs] // the owner's label replaces our class; a V the owner confirmed is not "questionable"
        when (label ?: b.cls) {
            "N" -> c[0]++
            "S" -> c[1]++
            "V" -> { c[2]++; if (label == null && b.vDoubt) c[3]++ }
        }
    }
    return result.windows.filter { it.quality >= USABLE_QUALITY }
        .map { w -> index.getValue(w.startUtcMs).let { WinCount(w.startUtcMs + w.durationMs / 2, it[0], it[1], it[2], it[3], w.meanHrBpm) } }
}

/** Each window belongs to the stretch that holds its middle, as in [summarizeActions]. */
private fun tally(wins: List<WinCount>, fromMs: Long, toMs: Long): Tally {
    val t = Tally()
    for (w in wins) if (w.midMs >= fromMs && w.midMs < toMs) {
        t.usableMs += WINDOW_MS
        t.n += w.n; t.s += w.s; t.v += w.v; t.vDoubt += w.vDoubt
        w.hrBpm?.let { t.hrSum += it; t.hrN++ }
    }
    return t
}

private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
private fun hr(t: Tally) = t.meanHr?.let { fmt1(it) } ?: "-"
private fun percent(t: Tally) = t.burdenPercent?.let { String.format(Locale.ROOT, "%.2f %%", it) } ?: "-"
private fun minutes(ms: Long) = if (ms <= 0) "-" else "${fmt1(ms / 60_000.0)} min"
private fun hm(ms: Long) = "%d:%02d".format(ms / HOUR_MS, ms % HOUR_MS / 60_000)

/**
 * The report as Markdown. Times are shown in [zone] (the owner's local time, default JST). Burden = (S + V) / (N + S + V),
 * counted in usable windows only, so a stretch of noise does not look calm. [title] is the heading (the session name).
 */
fun renderDayReport(
    result: AnalysisResult,
    marks: List<Mark>,
    spans: List<ActionSpan>,
    title: String,
    zone: ZoneId = ZoneId.of("Asia/Tokyo"),
    labels: Map<Long, String> = emptyMap(),
): String {
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
    val wins = winCounts(result, labels)
    val sb = StringBuilder()
    sb.append("# Day report $title\n\n")
    sb.append("Times are ${zone.id}. Burden = (S + V) / (N + S + V) in percent, counted in usable windows only (quality >= 0.5).\n\n")
    if (labels.isNotEmpty()) {
        val changed = result.beats.count { b -> labels[b.utcMs]?.let { it != b.cls } == true }
        sb.append("**Corrected with the owner's labels:** ${labels.size} beats carry the owner's class instead of the automatic one ($changed of them differ); all other beats keep the automatic class.\n\n")
    }

    if (spans.isNotEmpty()) {
        sb.append("## Actions, hour by hour\n\n")
        for (span in spans) {
            sb.append("| ${clock.format(Instant.ofEpochMilli(span.startMs))} | ${span.name} (${hm(span.durationMs)}) | HR | S | V | S+V burden |\n")
            sb.append("|---|---|---:|---:|---:|---:|\n")
            var from = span.startMs
            while (from < span.endMs) {
                val to = minOf(from + HOUR_MS, span.endMs)
                val t = tally(wins, from, to)
                sb.append("| +${hm(from - span.startMs)} to +${hm(to - span.startMs)} | ${minutes(t.usableMs)} usable | ${hr(t)} | ${t.s} | ${t.v} | ${percent(t)} |\n")
                from = to
            }
            val total = tally(wins, span.startMs, span.endMs)
            sb.append("| total | ${span.name} | ${hr(total)} | ${total.s} | ${total.v} | ${percent(total)} |\n\n")
        }

        sb.append("## Summary per action\n\n")
        sb.append("| action | stretches | usable | HR | S | V | S+V burden | S per h | V per h | V questionable |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        val perName = LinkedHashMap<String, Pair<Tally, Int>>()
        for (span in spans) {
            val t = tally(wins, span.startMs, span.endMs)
            val (acc, count) = perName[span.name] ?: (Tally() to 0)
            acc.usableMs += t.usableMs; acc.n += t.n; acc.s += t.s; acc.v += t.v; acc.hrSum += t.hrSum; acc.hrN += t.hrN; acc.vDoubt += t.vDoubt
            perName[span.name] = acc to count + 1
        }
        for ((name, pair) in perName.entries.sortedByDescending { it.value.first.usableMs }) {
            val (t, count) = pair
            val hours = t.usableMs / HOUR_MS.toDouble()
            val sPerH = if (hours > 0) fmt1(t.s / hours) else "-"
            val vPerH = if (hours > 0) fmt1(t.v / hours) else "-"
            sb.append("| $name | $count | ${minutes(t.usableMs)} | ${hr(t)} | ${t.s} | ${t.v} | ${percent(t)} | $sPerH | $vPerH | ${t.vDoubt} |\n")
        }
        sb.append('\n')
    } else {
        sb.append("No actions were marked in this recording (`annotations.jsonl`), so there are no per-action tables.\n\n")
    }

    // ---- the whole recording ----
    val total = tally(wins, Long.MIN_VALUE, Long.MAX_VALUE)
    val first = result.windows.firstOrNull()?.startUtcMs
    val end = dataEndMs(result)
    val usableWindows = result.windows.filter { it.quality >= USABLE_QUALITY }
    val meanHr = usableWindows.mapNotNull { it.meanHrBpm }.takeIf { it.isNotEmpty() }?.average()
    val classes = result.beats.groupingBy { labels[it.utcMs] ?: it.cls }.eachCount()
    sb.append("## Whole recording\n\n")
    if (first != null) {
        sb.append("- Recorded: ${clock.format(Instant.ofEpochMilli(first))} to ${clock.format(Instant.ofEpochMilli(end))} " +
            "(${fmt1((end - first) / HOUR_MS.toDouble())} h span, ${fmt1(result.windows.sumOf { it.durationMs } / HOUR_MS.toDouble())} h of ECG)\n")
    }
    sb.append("- Usable: ${fmt1(total.usableMs / HOUR_MS.toDouble())} h (${result.windows.size.takeIf { it > 0 }?.let { fmt1(100.0 * usableWindows.size / it) } ?: "-"} % of the windows)\n")
    sb.append("- Beats: ${result.beats.size} (N ${classes["N"] ?: 0}, S ${classes["S"] ?: 0}, V ${classes["V"] ?: 0}, Q ${classes["Q"] ?: 0})\n")
    val hours = total.usableMs / HOUR_MS.toDouble()
    sb.append("- In usable windows: S ${total.s}, V ${total.v} (questionable: ${total.vDoubt}), burden ${percent(total)}" +
        (if (hours > 0) ", S ${fmt1(total.s / hours)} per h, V ${fmt1(total.v / hours)} per h" else "") + "\n")
    meanHr?.let { sb.append("- Mean heart rate (usable windows): ${fmt1(it)} bpm\n") }
    if (result.events.isNotEmpty()) {
        sb.append("- Events found: " + result.events.groupingBy { it.type }.eachCount().entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" } + "\n")
    }
    val eventMarks = marks.count { !it.isAction }
    if (eventMarks > 0) sb.append("- Events marked by the owner: $eventMarks (see activities.csv)\n")
    return sb.toString()
}
