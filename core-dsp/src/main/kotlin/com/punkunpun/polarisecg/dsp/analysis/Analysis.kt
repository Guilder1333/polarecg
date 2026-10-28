package com.punkunpun.polarisecg.dsp.analysis

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.dsp.motion.MotionBin
import com.punkunpun.polarisecg.dsp.pattern.HourlyBurden
import com.punkunpun.polarisecg.dsp.pattern.detectPatterns
import com.punkunpun.polarisecg.dsp.pattern.hourlyBurden
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.quality.QualityConfig
import com.punkunpun.polarisecg.dsp.raw.RawRecord

/*
 * Offline analysis of a recording: frames -> segments -> beats, 10 s windows and events.
 * This is what `:tools:cli analyze` writes to beats.csv, windows.csv and events.csv.
 * Beats come from the streaming QrsDetector (P3.2), quality from P3.4, features and the class (N/S/V/Q) from P4.
 */

/**
 * One detected beat. [cls] is N / S / V / Q (see [BeatClass]).
 * [rrMs] is the interval to the previous beat of the same segment, null for the first one.
 * [quality] says how far the beat can be trusted (P3.4).
 */
class Beat(
    val utcMs: Long,
    val segment: Int,
    val rrMs: Long?,
    var cls: String,
    val quality: BeatQuality,
    /** True if [BeatQuality.sqi] reaches the "good beat" threshold. */
    val good: Boolean,
) {
    /** Timing and shape features (P4.1), set right after the beat is created. */
    var features: BeatFeatures? = null

    /** A V that may be a normal beat on a drifting baseline (P4.7): look at it again. Only ever true for [cls] V. */
    var vDoubt: Boolean = false

    /** A beat of poor quality index that was classified anyway because it matches the normal template (P4.7). */
    var rescued: Boolean = false
}

/** One 10 s window aligned to UTC. [coverage] = samples received / samples expected (0..1). */
class Window(
    val startUtcMs: Long,
    val durationMs: Long,
    val samples: Int,
    val coverage: Double,
    val beats: Int,
    val meanHrBpm: Double?,
    /** Beats with good signal quality. */
    val goodBeats: Int,
    /** Highest motion (accelerometer, mG) of the 1 s bins in the window; null without ACC data. */
    val motionMg: Double?,
    /** (Good + rescued beats, P4.7) / beats (0 when there is no beat at all). Below [USABLE_QUALITY] the window is unusable. */
    val quality: Double,
)

/** [type] is GAP (no signal for [durationMs]), PAUSE, or a pattern of ectopic beats (see `PatternType`). */
class Event(val utcMs: Long, val type: String, val durationMs: Long, val detail: String)

class AnalysisResult(
    val segments: List<EcgSegment>,
    val beats: List<Beat>,
    val windows: List<Window>,
    val events: List<Event>,
    /** Motion per UTC second from the accelerometer; empty if the recording has no ACC data. */
    val motion: List<MotionBin>,
    /** V and S burden per UTC hour (P4.4). */
    val hourly: List<HourlyBurden> = emptyList(),
)

const val WINDOW_MS = 10_000L

/** Windows with a lower quality are shaded as poor in the viewer and left out of burden and HRV later. */
const val USABLE_QUALITY = 0.5

/** RR intervals outside this range are not used for the heart rate of a window. */
private const val RR_MIN_MS = 250L
private const val RR_MAX_MS = 3_000L

fun analyzeEcg(frames: List<RawRecord.Ecg>, accFrames: List<RawRecord.Acc> = emptyList()): AnalysisResult {
    val segments = buildSegments(frames)
    // The same pipeline as on the phone, fed with the whole recording (accelerometer first, so that no beat waits for it).
    val pipeline = LivePipeline(QualityConfig(), motionRetainMs = Long.MAX_VALUE)
    val beats = ArrayList<Beat>()
    for (a in accFrames) beats += pipeline.addAcc(a.sensorTimeNs, a.phoneTimeMs, a.xMg, a.yMg, a.zMg)
    for (f in frames) beats += pipeline.addEcg(f.sensorTimeNs, f.phoneTimeMs, f.samplesUv)
    beats += pipeline.finish()
    val motion = pipeline.motionBins()

    val events = ArrayList<Event>()
    for (i in 1 until segments.size) {
        val gapStart = segments[i - 1].endUtcMs
        val gapMs = segments[i].startUtcMs - gapStart
        events += Event(gapStart, "GAP", gapMs, "segment ${i - 1} -> $i")
    }

    events += detectPatterns(beats)
    events.sortBy { it.utcMs }
    return AnalysisResult(segments, beats, buildWindows(segments, beats, motion), events, motion, hourlyBurden(beats))
}

private fun buildWindows(segments: List<EcgSegment>, beats: List<Beat>, motion: List<MotionBin>): List<Window> {
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
    val maxMotion = arrayOfNulls<Double>(count)
    for (m in motion) {
        val w = (Math.floorDiv(m.startUtcMs, WINDOW_MS) - first).toInt()
        if (w in 0 until count && (maxMotion[w] ?: -1.0) < m.motionMg) maxMotion[w] = m.motionMg
    }
    val beatCount = IntArray(count)
    val goodCount = IntArray(count)
    val usableCount = IntArray(count) // good or rescued: a classified beat counts for the window, not for HRV
    val rrSum = LongArray(count)
    val rrN = IntArray(count)
    for (b in beats) {
        val w = (Math.floorDiv(b.utcMs, WINDOW_MS) - first).toInt()
        if (w !in 0 until count) continue
        beatCount[w]++
        if (b.good) goodCount[w]++
        if (b.good || b.rescued) usableCount[w]++
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
            motionMg = maxMotion[w],
            goodBeats = goodCount[w],
            quality = if (beatCount[w] > 0) usableCount[w].toDouble() / beatCount[w] else 0.0,
        )
    }
}
