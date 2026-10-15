package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import java.io.File
import java.time.Instant
import java.util.Locale

/*
 * CSV output of `analyze`. UTF-8, "," separated, "." decimals, header row, times as both epoch ms
 * (for code) and ISO-8601 UTC (for eyes). Column meaning is documented in docs/ANALYSIS_FORMAT.md.
 */

fun runAnalysis(frames: List<RawRecord.Ecg>): AnalysisResult = analyzeEcg(frames)

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
private fun num(v: Double?, digits: Int) = if (v == null) "" else String.format(Locale.ROOT, "%.${digits}f", v)

/** Quotes a field if it contains a comma, quote or line break. */
private fun field(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

fun writeCsvs(result: AnalysisResult, dir: File) {
    File(dir, "beats.csv").bufferedWriter().use { w ->
        w.write("utcMs,utc,segment,rrMs,class\n")
        for (b in result.beats) {
            w.write("${b.utcMs},${iso(b.utcMs)},${b.segment},${b.rrMs ?: ""},${b.cls}\n")
        }
    }
    File(dir, "windows.csv").bufferedWriter().use { w ->
        w.write("startUtcMs,startUtc,durationMs,samples,coverage,beats,meanHrBpm\n")
        for (x in result.windows) {
            w.write(
                "${x.startUtcMs},${iso(x.startUtcMs)},${x.durationMs},${x.samples}," +
                    "${num(x.coverage, 3)},${x.beats},${num(x.meanHrBpm, 1)}\n",
            )
        }
    }
    File(dir, "events.csv").bufferedWriter().use { w ->
        w.write("utcMs,utc,type,durationMs,detail\n")
        for (e in result.events) {
            w.write("${e.utcMs},${iso(e.utcMs)},${e.type},${e.durationMs},${field(e.detail)}\n")
        }
    }
}
