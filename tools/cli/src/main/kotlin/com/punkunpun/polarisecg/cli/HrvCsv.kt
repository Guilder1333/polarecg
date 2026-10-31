package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.annotations.ActionSpan
import com.punkunpun.polarisecg.dsp.annotations.actionAt
import com.punkunpun.polarisecg.dsp.hrv.hrvWindows
import com.punkunpun.polarisecg.dsp.hrv.nnIntervals
import java.io.File
import java.time.Instant
import java.util.Locale

/* `hrv.csv`: HRV per 5-minute window (P5.5). Columns are documented in docs/ANALYSIS_FORMAT.md. */

private fun num(v: Double?, digits: Int) = if (v == null) "" else String.format(Locale.ROOT, "%.${digits}f", v)

fun writeHrvCsv(result: AnalysisResult, dir: File, spans: List<ActionSpan>) {
    File(dir, "hrv.csv").bufferedWriter().use { w ->
        w.write("startUtcMs,startUtc,nn,coverage,valid,meanHrBpm,sdnnMs,rmssdMs,pnn50Percent,lfMs2,hfMs2,lfHf,action\n")
        for (h in hrvWindows(nnIntervals(result.beats))) {
            val t = h.time
            val action = actionAt(spans, h.startUtcMs + h.durationMs / 2).orEmpty()
            w.write(
                "${h.startUtcMs},${Instant.ofEpochMilli(h.startUtcMs)},${h.nn},${num(h.coverage, 3)},${if (h.valid) 1 else 0}," +
                    "${num(t.meanHrBpm, 1)},${num(t.sdnnMs, 1)},${num(t.rmssdMs, 1)},${num(t.pnn50Percent, 1)}," +
                    "${num(h.spectrum?.lfMs2, 1)},${num(h.spectrum?.hfMs2, 1)},${num(h.spectrum?.lfHf, 2)},${if (action.contains(',')) "\"$action\"" else action}\n",
            )
        }
    }
}
