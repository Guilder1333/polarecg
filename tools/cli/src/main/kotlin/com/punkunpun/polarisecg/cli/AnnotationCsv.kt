package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.annotations.ActionSpan
import com.punkunpun.polarisecg.dsp.annotations.Mark
import com.punkunpun.polarisecg.dsp.annotations.actionSpans
import com.punkunpun.polarisecg.dsp.annotations.dataEndMs
import com.punkunpun.polarisecg.dsp.annotations.eventContexts
import com.punkunpun.polarisecg.dsp.annotations.parseMarks
import com.punkunpun.polarisecg.dsp.annotations.summarizeActions
import java.io.File
import java.time.Instant
import java.util.Locale

/*
 * The owner's actions and events next to the analysis (P5.12): `activities.csv` and `action_summary.csv`, plus the
 * `action` column of windows.csv. Columns are documented in docs/ANALYSIS_FORMAT.md.
 */

private const val ANNOTATIONS_FILE = "annotations.jsonl"
private val OUTPUT_FILES = listOf("activities.csv", "action_summary.csv")

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
private fun num(v: Double?, digits: Int) = if (v == null) "" else String.format(Locale.ROOT, "%.${digits}f", v)
private fun field(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** The marks of a session folder (empty if it has no `annotations.jsonl`, e.g. a reference record). */
fun readMarks(folder: File): List<Mark> =
    File(folder, ANNOTATIONS_FILE).takeIf { it.isFile }?.let { parseMarks(it.readLines()) }.orEmpty()

/** The action spans for [result]: the last action lasts to the end of the analysed data. */
fun spansFor(result: AnalysisResult, marks: List<Mark>): List<ActionSpan> = actionSpans(marks, dataEndMs(result))

/** Writes the two files, or removes them from [dir] when there are no marks (an older run may have left them). */
fun writeAnnotationCsvs(result: AnalysisResult, marks: List<Mark>, spans: List<ActionSpan>, dir: File) {
    if (marks.isEmpty()) {
        OUTPUT_FILES.forEach { File(dir, it).delete() }
        return
    }
    File(dir, "activities.csv").bufferedWriter().use { w ->
        w.write("type,name,startUtcMs,startUtc,endUtcMs,endUtc,durationMs,note,hrBefore5m,hrAfter5m,ectopicBefore5m,ectopicAfter5m\n")
        val rows = spans.map { it.startMs to "action,${field(it.name)},${it.startMs},${iso(it.startMs)},${it.endMs},${iso(it.endMs)},${it.durationMs},,,,,\n" } +
            eventContexts(result, marks).map { c ->
                val m = c.mark
                m.timeMs to "event,${field(m.name ?: "")},${m.timeMs},${iso(m.timeMs)},,,,${field(m.note)}," +
                    "${num(c.hrBeforeBpm, 1)},${num(c.hrAfterBpm, 1)},${c.ectopicBefore},${c.ectopicAfter}\n"
            }
        for ((_, line) in rows.sortedBy { it.first }) w.write(line)
    }
    File(dir, "action_summary.csv").bufferedWriter().use { w ->
        w.write("action,totalMin,usableMin,meanHrBpm,meanMotionMg,n,s,v,q,vDoubt,vPerHour,sPerHour,vPercent,sPercent\n")
        for (a in summarizeActions(result, spans, hasMarks = true)) {
            w.write(
                "${field(a.name)},${num(a.totalMs / 60_000.0, 1)},${num(a.usableMs / 60_000.0, 1)},${num(a.meanHrBpm, 1)},${num(a.meanMotionMg, 0)}," +
                    "${a.n},${a.s},${a.v},${a.q},${a.vDoubt},${num(a.vPerHour, 1)},${num(a.sPerHour, 1)},${num(a.vPercent, 2)},${num(a.sPercent, 2)}\n",
            )
        }
    }
}

/** A short table for the console. */
fun printActionSummary(result: AnalysisResult, marks: List<Mark>, spans: List<ActionSpan>) {
    if (marks.isEmpty()) return
    println("actions (usable time only; rates per usable hour):")
    for (a in summarizeActions(result, spans, hasMarks = true)) {
        println(
            "  %-18s %6.0f min (%5.0f usable)  HR %s  V %d (%s/h)  S %d (%s/h)".format(
                a.name, a.totalMs / 60_000.0, a.usableMs / 60_000.0, num(a.meanHrBpm, 0).ifEmpty { "-" },
                a.v, num(a.vPerHour, 1).ifEmpty { "-" }, a.s, num(a.sPerHour, 1).ifEmpty { "-" },
            ),
        )
    }
    println("  ${marks.count { !it.isAction }} event(s), see activities.csv")
}
