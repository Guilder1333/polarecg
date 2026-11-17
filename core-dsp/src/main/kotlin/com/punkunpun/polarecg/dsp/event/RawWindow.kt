package com.punkunpun.polarecg.dsp.event

import com.punkunpun.polarecg.dsp.analysis.EcgSegment
import com.punkunpun.polarecg.dsp.analysis.buildSegments
import com.punkunpun.polarecg.dsp.live.EcgClip
import com.punkunpun.polarecg.dsp.motion.cutAccWindow
import com.punkunpun.polarecg.dsp.raw.RawRecord
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * P5.7, "beats near a time": an ECG window around any moment, cut from the raw recording files (event clips only
 * exist for events). Pure JVM, so the rules are tested on the PC.
 */

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

/**
 * Start time (UTC ms) from the name of a finished ECG file, `ecg_20261105T201459Z.bin` (a counter or `_empty` suffix is
 * allowed); null for anything else, including the current file `ecg.bin`, which has no stamp.
 */
fun ecgFileStampMs(name: String): Long? = fileStampMs(name, "ecg")

/** Like [ecgFileStampMs] for the files of any stream: `<prefix>_<stamp>.bin`. */
fun fileStampMs(name: String, prefix: String): Long? {
    val start = prefix.length + 1
    if (!name.startsWith("${prefix}_") || !name.endsWith(".bin") || name.length < start + 16 + 4) return null
    return runCatching { LocalDateTime.parse(name.substring(start, start + 16), STAMP).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

/**
 * The ACC files of one session that can hold samples of `[fromUtcMs, toUtcMs]`, oldest first: the last finished file that starts
 * at or before [fromUtcMs], every finished file that starts inside the range, and the current `acc.bin` when no finished file starts
 * after the range (then it may hold the end of it). Unlike the ECG ([ecgFilesFor]) the ACC needs no reading from the session's start:
 * its samples are put on the ECG's time line through the strap's clock ([cutAccWindow]). Callers widen the range by a few seconds,
 * because a file's stamp is the phone time of its first frame, which arrives a little after its samples.
 */
fun accFilesFor(files: List<File>, fromUtcMs: Long, toUtcMs: Long): List<File> {
    val finished = files.mapNotNull { f -> fileStampMs(f.name, "acc")?.let { it to f } }.sortedBy { it.first }
    val firstIndex = finished.indexOfLast { it.first <= fromUtcMs }.coerceAtLeast(0)
    val chosen = finished.drop(firstIndex).filter { it.first <= toUtcMs }.map { it.second }
    val current = files.firstOrNull { it.name == "acc.bin" }
    return if (current != null && finished.none { it.first > toUtcMs }) chosen + current else chosen
}

/**
 * Which ECG files of one session are needed for the window `center +- halfWidthMs`: **every** finished file that starts before the
 * window's end, and the current `ecg.bin` when no finished file starts after the center (then it is the newest data). Oldest first,
 * as [buildSegments] wants the frames in arrival order.
 *
 * Why not only the file around the center: the time of a sample is the start time of its gap-free stretch plus the sensor clock
 * since then, and the beats of the phone were timed that way from the stretch's very first frame, up to many hours earlier. The
 * strap's clock drifts against the phone's (about 25 ppm, 1.6 s in 18 hours), so a stretch started at a later file would put the
 * ECG seconds away from the saved beat markers. Reading from the first file reproduces the beats' clock exactly.
 */
fun ecgFilesFor(files: List<File>, centerUtcMs: Long, halfWidthMs: Long): List<File> {
    val finished = files.mapNotNull { f -> ecgFileStampMs(f.name)?.let { it to f } }.sortedBy { it.first }
    val before = finished.filter { it.first <= centerUtcMs + halfWidthMs }.map { it.second }
    val current = files.firstOrNull { it.name == "ecg.bin" }
    val laterFinished = finished.any { it.first > centerUtcMs }
    return if (current != null && !laterFinished) before + current else before
}

/**
 * The samples of `center ± halfWidthMs` from the gap-free stretch of [frames] that contains [centerUtcMs]; null if the center
 * is in no stretch (a hole in the recording, or no data) or the piece is shorter than [MIN_WINDOW_SAMPLES]. Never spans a hole.
 */
fun cutWindow(frames: List<RawRecord.Ecg>, centerUtcMs: Long, halfWidthMs: Long): EcgClip? =
    cutWindowAnchored(frames, centerUtcMs, halfWidthMs)?.clip

/**
 * An ECG window ([clip]) and the point that ties its time line to the strap's clock: the strap time [anchorSensorNs] is at
 * [anchorUtcMs] (the first sample of the stretch the window was cut from). The accelerometer has the same clock, so its
 * samples can be placed on exactly the same time line as the ECG and the beats ([cutAccWindow]).
 */
class RawEcgWindow(val clip: EcgClip, val anchorUtcMs: Long, val anchorSensorNs: Long)

/** [cutWindow], also giving the clock anchor of the stretch (see [RawEcgWindow]). */
fun cutWindowAnchored(frames: List<RawRecord.Ecg>, centerUtcMs: Long, halfWidthMs: Long): RawEcgWindow? {
    val seg = buildSegments(frames).firstOrNull { centerUtcMs >= it.startUtcMs && centerUtcMs <= it.endUtcMs } ?: return null
    val last = seg.samplesUv.size - 1
    val i0 = firstAtOrAfter(seg, centerUtcMs - halfWidthMs, last)
    val i1 = lastAtOrBefore(seg, centerUtcMs + halfWidthMs, last)
    if (i1 - i0 + 1 < MIN_WINDOW_SAMPLES) return null
    val out = seg.samplesUv.copyOfRange(i0, i1 + 1)
    val span = (seg.sampleUtcMs(i1) - seg.sampleUtcMs(i0)).toDouble()
    val clip = EcgClip(seg.sampleUtcMs(i0), if (out.size > 1) span / (out.size - 1) else 0.0, out)
    return RawEcgWindow(clip, seg.startUtcMs, seg.firstSensorNs)
}

const val MIN_WINDOW_SAMPLES = 130

private fun firstAtOrAfter(s: EcgSegment, utcMs: Long, last: Int): Int {
    var lo = 0
    var hi = last
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (s.sampleUtcMs(mid) >= utcMs) hi = mid else lo = mid + 1
    }
    return lo
}

private fun lastAtOrBefore(s: EcgSegment, utcMs: Long, last: Int): Int {
    var lo = 0
    var hi = last
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (s.sampleUtcMs(mid) <= utcMs) lo = mid else hi = mid - 1
    }
    return lo
}
