package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.event.ClipFormat
import com.punkunpun.polarisecg.dsp.event.EventRecorder
import com.punkunpun.polarisecg.dsp.event.RecordedEvent
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.readStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/**
 * `clips <session> [--out <dir>]`: what the phone saves as events and clips (P5.4), on the PC. Feeds the recording through
 * the live pipeline and the [EventRecorder] in arrival order, writes `events.csv` and one `<time>_<type>.bin` per saved clip
 * (the phone's clip format, `docs/RAW_FORMAT.md`) to the out folder, and compares the events with those of `analyze`.
 */
internal fun clipsCommand(folder: File, outDir: File): Int {
    val ecg = readStream(folder, "ecg").records.filterIsInstance<RawRecord.Ecg>()
    val acc = readStream(folder, "acc").records.filterIsInstance<RawRecord.Acc>()
    if (ecg.isEmpty()) { System.err.println("No ECG records found in $folder"); return 1 }

    val pipeline = LivePipeline()
    val recorder = EventRecorder(pipeline.history)
    val recorded = ArrayList<RecordedEvent>()
    fun take(beats: List<Beat>) { recorded += recorder.add(beats) }
    for (r in (ecg + acc).sortedBy { it.phoneTimeMs }) { // sortedBy is stable
        when (r) {
            is RawRecord.Ecg -> { take(pipeline.addEcg(r.sensorTimeNs, r.phoneTimeMs, r.samplesUv)); recorded += recorder.poll() }
            is RawRecord.Acc -> take(pipeline.addAcc(r.sensorTimeNs, r.phoneTimeMs, r.xMg, r.yMg, r.zMg))
            else -> {}
        }
    }
    take(pipeline.finish())
    recorded += recorder.finish()
    recorded.sortBy { it.event.utcMs }

    outDir.mkdirs()
    val lines = ArrayList<String>()
    lines += "startUtc,type,durationMs,beats,detail,clipFile,clipBytes"
    var bytes = 0L
    var clips = 0
    for (r in recorded) {
        val name = r.clip?.let { "${FILE_TIME.format(Instant.ofEpochMilli(r.event.utcMs))}_${r.event.type}.bin" }
        val size = r.clip?.let { c ->
            val data = ClipFormat.encode(c)
            File(outDir, name!!).writeBytes(data)
            clips++
            bytes += data.size
            data.size
        }
        lines += listOf(
            Instant.ofEpochMilli(r.event.utcMs), r.event.type, r.event.durationMs, r.beatCount,
            "\"${r.event.detail.replace("\"", "'")}\"", name ?: "", size ?: "",
        ).joinToString(",")
    }
    File(outDir, "events.csv").writeText(lines.joinToString("\n") + "\n")

    val batch = analyzeEcg(ecg, acc).events.filter { it.type != "GAP" }
    val liveKeys = recorded.map { "${it.event.type}@${it.event.utcMs}" }.toSet()
    val batchKeys = batch.map { "${it.type}@${it.utcMs}" }.toSet()
    println("events: ${recorded.size} (analyze: ${batch.size}), clips saved: $clips, %.1f MB".format(bytes / 1e6))
    println("by type:")
    for ((type, list) in recorded.groupBy { it.event.type }.toSortedMap()) {
        println("  %-14s %5d events, %4d clips".format(type, list.size, list.count { it.clip != null }))
    }
    println("events only live: ${(liveKeys - batchKeys).size}, only analyze: ${(batchKeys - liveKeys).size}")
    println("written to $outDir")
    return 0
}
