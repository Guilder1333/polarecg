package com.punkunpun.polarisecg.dsp.analysis

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.detect.QrsDetector
import com.punkunpun.polarisecg.dsp.raw.RawRecord

/*
 * Offline analysis of a recording: frames -> segments -> beats, 10 s windows and events.
 * This is what `:tools:cli analyze` writes to beats.csv, windows.csv and events.csv.
 * Beats come from the streaming QrsDetector (P3.2); classes come in P4, quality in P3.4.
 */

/**
 * One detected beat. [cls] is "U" (unclassified) until the classifier exists (P4).
 * [rrMs] is the interval to the previous beat of the same segment, null for the first one.
 */
class Beat(val utcMs: Long, val segment: Int, val rrMs: Long?, val cls: String)

/** One 10 s window aligned to UTC. [coverage] = samples received / samples expected (0..1). */
class Window(
    val startUtcMs: Long,
    val durationMs: Long,
    val samples: Int,
    val coverage: Double,
    val beats: Int,
    val meanHrBpm: Double?,
)

/** [type] is GAP (no signal for [durationMs]) for now; arrhythmia events arrive in P4. */
class Event(val utcMs: Long, val type: String, val durationMs: Long, val detail: String)

class AnalysisResult(
    val segments: List<EcgSegment>,
    val beats: List<Beat>,
    val windows: List<Window>,
    val events: List<Event>,
)

const val WINDOW_MS = 10_000L

/** RR intervals outside this range are not used for the heart rate of a window. */
private const val RR_MIN_MS = 250L
private const val RR_MAX_MS = 3_000L

fun analyzeEcg(frames: List<RawRecord.Ecg>): AnalysisResult {
    val segments = buildSegments(frames)

    val beats = ArrayList<Beat>()
    for (seg in segments) {
        var previous: Long? = null
        // One detector per gap-free segment: it never runs across a gap.
        val detector = QrsDetector()
        val indices = detector.process(seg.samplesUv) + detector.finish() // `+` joins two IntArrays
        for (idx in indices) {
            val t = seg.sampleUtcMs(idx)
            beats += Beat(t, seg.index, previous?.let { t - it }, "U")
            previous = t
        }
    }

    val events = ArrayList<Event>()
    for (i in 1 until segments.size) {
        val gapStart = segments[i - 1].endUtcMs
        val gapMs = segments[i].startUtcMs - gapStart
        events += Event(gapStart, "GAP", gapMs, "segment ${i - 1} -> $i")
    }

    return AnalysisResult(segments, beats, buildWindows(segments, beats), events)
}

private fun buildWindows(segments: List<EcgSegment>, beats: List<Beat>): List<Window> {
    if (segments.isEmpty()) return emptyList()
    val first = Math.floorDiv(segments.first().startUtcMs, WINDOW_MS)
    val last = Math.floorDiv(segments.last().endUtcMs - 1, WINDOW_MS)
    val count = (last - first + 1).toInt()
    val samples = IntArray(count)
    for (seg in segments) {
        for (i in seg.samplesUv.indices) {
            val w = (Math.floorDiv(seg.sampleUtcMs(i), WINDOW_MS) - first).toInt()
            if (w in 0 until count) samples[w]++
        }
    }
    val beatCount = IntArray(count)
    val rrSum = LongArray(count)
    val rrN = IntArray(count)
    for (b in beats) {
        val w = (Math.floorDiv(b.utcMs, WINDOW_MS) - first).toInt()
        if (w !in 0 until count) continue
        beatCount[w]++
        val rr = b.rrMs
        if (rr != null && rr in RR_MIN_MS..RR_MAX_MS) { rrSum[w] += rr; rrN[w]++ }
    }
    val expected = WINDOW_MS * ECG_SAMPLE_RATE_HZ / 1000.0
    return List(count) { w ->
        Window(
            startUtcMs = (first + w) * WINDOW_MS,
            durationMs = WINDOW_MS,
            samples = samples[w],
            coverage = (samples[w] / expected).coerceAtMost(1.0),
            beats = beatCount[w],
            meanHrBpm = if (rrN[w] > 0) 60_000.0 * rrN[w] / rrSum[w] else null,
        )
    }
}
