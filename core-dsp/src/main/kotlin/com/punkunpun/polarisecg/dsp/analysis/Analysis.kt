package com.punkunpun.polarisecg.dsp.analysis

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.beat.BeatFeatureExtractor
import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.beat.BeatInput
import com.punkunpun.polarisecg.dsp.motion.MotionBin
import com.punkunpun.polarisecg.dsp.motion.MotionIndex
import com.punkunpun.polarisecg.dsp.motion.buildMotionBins
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.quality.EcgBeatStream
import com.punkunpun.polarisecg.dsp.quality.QualityConfig
import com.punkunpun.polarisecg.dsp.raw.RawRecord

/*
 * Offline analysis of a recording: frames -> segments -> beats, 10 s windows and events.
 * This is what `:tools:cli analyze` writes to beats.csv, windows.csv and events.csv.
 * Beats come from the streaming QrsDetector (P3.2); classes come in P4, quality in P3.4.
 */

/**
 * One detected beat. [cls] is "U" (unclassified) until the classifier exists (P4).
 * [rrMs] is the interval to the previous beat of the same segment, null for the first one.
 * [quality] says how far the beat can be trusted (P3.4).
 */
class Beat(
    val utcMs: Long,
    val segment: Int,
    val rrMs: Long?,
    val cls: String,
    val quality: BeatQuality,
    /** True if [BeatQuality.sqi] reaches the "good beat" threshold. */
    val good: Boolean,
) {
    /** Timing and shape features (P4.1), set right after the beat is created. */
    var features: BeatFeatures? = null
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
    /** Good beats / beats (0 when there is no beat at all). Below [USABLE_QUALITY] the window is unusable. */
    val quality: Double,
)

/** [type] is GAP (no signal for [durationMs]) for now; arrhythmia events arrive in P4. */
class Event(val utcMs: Long, val type: String, val durationMs: Long, val detail: String)

class AnalysisResult(
    val segments: List<EcgSegment>,
    val beats: List<Beat>,
    val windows: List<Window>,
    val events: List<Event>,
    /** Motion per UTC second from the accelerometer; empty if the recording has no ACC data. */
    val motion: List<MotionBin>,
)

const val WINDOW_MS = 10_000L

/** Windows with a lower quality are shaded as poor in the viewer and left out of burden and HRV later. */
const val USABLE_QUALITY = 0.5

/** RR intervals outside this range are not used for the heart rate of a window. */
private const val RR_MIN_MS = 250L
private const val RR_MAX_MS = 3_000L

fun analyzeEcg(frames: List<RawRecord.Ecg>, accFrames: List<RawRecord.Acc> = emptyList()): AnalysisResult {
    val segments = buildSegments(frames)
    val motion = buildMotionBins(accFrames)
    val motionIndex = MotionIndex(motion)
    val qualityConfig = QualityConfig()

    val beats = ArrayList<Beat>()
    // One extractor for the whole recording: its normal-beat template carries over gaps (P4.2).
    val extractor = BeatFeatureExtractor()
    for (seg in segments) {
        var previous: Long? = null
        // One stream per gap-free segment: detectors never run across a gap.
        val stream = EcgBeatStream()
        val scored = stream.process(seg.samplesUv) + stream.finish() // `+` joins two Lists
        val times = scored.map { seg.sampleUtcMs(it.position) }
        val features = ArrayList<BeatFeatures>(scored.size)
        for ((i, b) in scored.withIndex()) {
            val t = times[i]
            // the stream knows only the ECG: add the accelerometer here, on the UTC time line
            val quality = b.quality.withMotion(motionIndex.around(t), qualityConfig)
            val good = quality.sqi >= qualityConfig.goodBeatSqi
            features += extractor.add(BeatInput(t.toDouble(), b.snippet, good))
            beats += Beat(t, seg.index, previous?.let { t - it }, "U", quality, good)
            previous = t
        }
        features += extractor.finish()
        // features come out in input order, one per beat: attach them to the beats of this segment
        val first = beats.size - scored.size
        for ((i, f) in features.withIndex()) beats[first + i].features = f
    }

    val events = ArrayList<Event>()
    for (i in 1 until segments.size) {
        val gapStart = segments[i - 1].endUtcMs
        val gapMs = segments[i].startUtcMs - gapStart
        events += Event(gapStart, "GAP", gapMs, "segment ${i - 1} -> $i")
    }

    return AnalysisResult(segments, beats, buildWindows(segments, beats, motion), events, motion)
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
    val rrSum = LongArray(count)
    val rrN = IntArray(count)
    for (b in beats) {
        val w = (Math.floorDiv(b.utcMs, WINDOW_MS) - first).toInt()
        if (w !in 0 until count) continue
        beatCount[w]++
        if (b.good) goodCount[w]++
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
            quality = if (beatCount[w] > 0) goodCount[w].toDouble() / beatCount[w] else 0.0,
        )
    }
}
