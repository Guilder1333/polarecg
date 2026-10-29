package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.annotations.ActionSpan
import com.punkunpun.polarisecg.dsp.annotations.actionAt
import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import java.io.File
import java.time.Instant
import java.util.Locale

/*
 * CSV output of `analyze`. UTF-8, "," separated, "." decimals, header row, times as both epoch ms
 * (for code) and ISO-8601 UTC (for eyes). Column meaning is documented in docs/ANALYSIS_FORMAT.md.
 */

fun runAnalysis(frames: List<RawRecord.Ecg>, accFrames: List<RawRecord.Acc> = emptyList()): AnalysisResult =
    analyzeEcg(frames, accFrames)

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
private fun num(v: Double?, digits: Int) = if (v == null) "" else String.format(Locale.ROOT, "%.${digits}f", v)

/** Quotes a field if it contains a comma, quote or line break. */
private fun field(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** The P4.1 columns of one beat; all empty without features. */
private fun featureColumns(f: BeatFeatures?): String {
    if (f == null) return ",,,,,,,,,,,,,"
    return "${num(f.rrRefMs, 0)},${num(f.rrSpread, 3)},${num(f.prematurity, 3)},${num(f.shortening, 3)},${num(f.compensation, 3)},${num(f.corr, 3)}," +
        "${num(f.shift, 2)},${num(f.qrsWidthMs, 0)},${num(f.widthRatio, 2)},${num(f.ampRatio, 2)},${num(f.areaRatio, 2)},${if (f.polarityFlip) 1 else 0},${num(f.alignMs, 1)},${num(f.driftRatio, 3)}"
}

fun writeCsvs(result: AnalysisResult, dir: File, spans: List<ActionSpan> = emptyList()) {
    File(dir, "beats.csv").bufferedWriter().use { w ->
        w.write("utcMs,utc,segment,rrMs,class,sqi,good,amplitudeUv,slopeRatio,hfRatio,agreed,motionMg," +
            "rrRefMs,rrSpread,prematurity,shortening,compensation,corr,shift,qrsWidthMs,widthRatio,ampRatio,areaRatio,flip,alignMs,driftRatio,vDoubt,qRescue\n")
        for (b in result.beats) {
            val q = b.quality
            w.write(
                "${b.utcMs},${iso(b.utcMs)},${b.segment},${b.rrMs ?: ""},${b.cls}," +
                    "${num(q.sqi, 3)},${if (b.good) 1 else 0},${num(q.amplitudeUv, 0)}," +
                    "${num(q.slopeRatio.takeIf { it.isFinite() }, 3)},${num(q.hfRatio.takeIf { it.isFinite() }, 3)}," +
                    "${if (q.agreed) 1 else 0},${num(q.motionMg, 1)}," +
                    featureColumns(b.features) + ",${if (b.vDoubt) 1 else 0},${if (b.rescued) 1 else 0}\n",
            )
        }
    }
    File(dir, "windows.csv").bufferedWriter().use { w ->
        w.write("startUtcMs,startUtc,durationMs,samples,coverage,beats,meanHrBpm,motionMg,goodBeats,quality,action\n")
        for (x in result.windows) {
            w.write(
                "${x.startUtcMs},${iso(x.startUtcMs)},${x.durationMs},${x.samples}," +
                    "${num(x.coverage, 3)},${x.beats},${num(x.meanHrBpm, 1)}," +
                    "${num(x.motionMg, 1)},${x.goodBeats},${num(x.quality, 3)},${field(actionAt(spans, x.startUtcMs + x.durationMs / 2) ?: "")}\n",
            )
        }
    }
    if (result.motion.isNotEmpty()) {
        File(dir, "motion.csv").bufferedWriter().use { w ->
            w.write("startUtcMs,startUtc,motionMg,samples\n")
            for (m in result.motion) w.write("${m.startUtcMs},${iso(m.startUtcMs)},${num(m.motionMg, 1)},${m.samples}\n")
        }
    }
    File(dir, "burden.csv").bufferedWriter().use { w ->
        w.write("startUtcMs,startUtc,n,s,v,q,vPercent,sPercent,vDoubt\n")
        for (h in result.hourly) {
            w.write("${h.startUtcMs},${iso(h.startUtcMs)},${h.n},${h.s},${h.v},${h.q},${num(h.vPercent, 2)},${num(h.sPercent, 2)},${h.vDoubt}\n")
        }
    }
    File(dir, "events.csv").bufferedWriter().use { w ->
        w.write("utcMs,utc,type,durationMs,detail\n")
        for (e in result.events) {
            w.write("${e.utcMs},${iso(e.utcMs)},${e.type},${e.durationMs},${field(e.detail)}\n")
        }
    }
}
