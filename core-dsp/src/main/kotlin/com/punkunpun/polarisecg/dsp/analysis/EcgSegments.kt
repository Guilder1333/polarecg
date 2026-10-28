package com.punkunpun.polarisecg.dsp.analysis

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.TimeMapper
import kotlin.math.abs

/*
 * Turns the ECG frames of a recording into gap-free stretches of samples.
 *
 * Frames are contiguous when the sensor clock says so: each frame's first-sample time must be
 * one sample period after the last sample of the previous frame. If it is not (frames lost, strap
 * reconnected, app killed) a new segment starts, because filters and detectors must not run
 * across a hole in the signal.
 */

/**
 * Sample position -> UTC inside one gap-free segment, following the sensor clock.
 *
 * The H10 does not sample at exactly 130 Hz: the sensor's own timestamps show ~130.27 Hz in
 * every recording we have (0.2 % fast), so "sample i is at i / 130 s" drifts by seconds per hour
 * and makes every RR interval 1-2 ms too long. Time inside a segment therefore follows the sensor
 * clock: for each frame the index of its first sample and its sensor time are added with [addFrame],
 * and positions between frames are interpolated linearly. [startUtcMs] is the phone UTC of the first sample.
 *
 * It can grow while the segment is still being recorded (the live pipeline), and the position of a
 * sample only ever depends on the frames up to the one after it.
 */
class SegmentClock(val startUtcMs: Long) {
    private var frameStartIndex = IntArray(64)
    private var frameSensorNs = LongArray(64)
    private var frames = 0

    fun addFrame(firstSampleIndex: Int, sensorNs: Long) {
        if (frames == frameStartIndex.size) { // full: copy into arrays twice as big
            frameStartIndex = frameStartIndex.copyOf(frames * 2)
            frameSensorNs = frameSensorNs.copyOf(frames * 2)
        }
        frameStartIndex[frames] = firstSampleIndex
        frameSensorNs[frames] = sensorNs
        frames++
    }

    /** True if a frame starts after [position]: only then its time is final (it uses the rate up to that next frame). */
    fun hasFrameAfter(position: Double): Boolean = frames > 0 && frameStartIndex[frames - 1] > position

    /** Sensor time (ns) of sample [position] (fractional; may lie slightly outside the segment). */
    private fun sensorNs(position: Double): Double {
        // last frame whose first sample is at or before `position`
        var lo = 0
        var hi = frames - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frameStartIndex[mid] <= position) lo = mid else hi = mid - 1
        }
        // seconds per sample from the neighbouring frames: the next frame, else the previous one, else nominal
        val nsPerSample = when {
            lo + 1 < frames ->
                (frameSensorNs[lo + 1] - frameSensorNs[lo]).toDouble() / (frameStartIndex[lo + 1] - frameStartIndex[lo])
            lo > 0 ->
                (frameSensorNs[lo] - frameSensorNs[lo - 1]).toDouble() / (frameStartIndex[lo] - frameStartIndex[lo - 1])
            else -> 1e9 / ECG_SAMPLE_RATE_HZ
        }
        return frameSensorNs[lo] + (position - frameStartIndex[lo]) * nsPerSample
    }

    /** Milliseconds from the first sample to sample [position]. */
    fun elapsedMs(position: Double): Double = (sensorNs(position) - frameSensorNs[0]) / 1e6

    /** UTC ms of a (fractional) sample position, rounded to whole milliseconds. */
    fun sampleUtcMs(position: Double): Long = startUtcMs + Math.round(elapsedMs(position))
}

/** A stretch of ECG without holes. [startUtcMs] is the phone UTC of the first sample; time follows [SegmentClock]. */
class EcgSegment(
    val index: Int,
    val startUtcMs: Long,
    val samplesUv: IntArray,
    frameStartIndex: IntArray,
    frameSensorNs: LongArray,
) {
    private val clock = SegmentClock(startUtcMs)

    init {
        require(frameStartIndex.isNotEmpty() && frameStartIndex.size == frameSensorNs.size) { "need at least one frame" }
        for (i in frameStartIndex.indices) clock.addFrame(frameStartIndex[i], frameSensorNs[i])
    }

    val durationMs: Long get() = Math.round(clock.elapsedMs(samplesUv.size.toDouble()))
    val endUtcMs: Long get() = startUtcMs + durationMs

    fun sampleUtcMs(i: Int): Long = clock.sampleUtcMs(i.toDouble())

    /** Same for a fractional sample position (a refined peak), rounded to whole milliseconds. */
    fun sampleUtcMs(position: Double): Long = clock.sampleUtcMs(position)
}

/** Frame start times may deviate this much from "exactly after the previous frame" (ms). */
private const val CONTIGUOUS_TOLERANCE_MS = 25L

/**
 * Decides, frame by frame, where gap-free segments begin, and where a new one starts on the calendar.
 * A frame continues the segment if the sensor clock says it follows the previous frame (within
 * [CONTIGUOUS_TOLERANCE_MS]); otherwise (frames lost, strap reconnected, app killed) a new segment starts,
 * because filters and detectors must not run across a hole in the signal. The start of a segment is
 * converted to phone UTC with a [TimeMapper]; inside a segment the sensor clock is used (see [SegmentClock]),
 * so the phone clock only places the segment on the calendar. Used by [buildSegments] (a whole recording)
 * and by the live pipeline (frame by frame): the same decisions.
 */
class SegmentTracker {
    private val mapper = TimeMapper()
    private var expectedNextNs: Long? = null
    private var lastSensorNs = Long.MIN_VALUE

    /** Phone UTC of the first sample of the segment the latest frame belongs to. */
    var segmentStartUtcMs = 0L
        private set

    /** Adds the next frame (not an empty one); true if it starts a new segment. */
    fun add(sensorTimeNs: Long, phoneTimeMs: Long, sampleCount: Int): Boolean {
        // The sensor clock stepped far back: a new connection. Its offset to the phone may differ.
        if (lastSensorNs != Long.MIN_VALUE && sensorTimeNs < lastSensorNs - 1_000_000_000L) {
            mapper.startConnection()
        }
        lastSensorNs = sensorTimeNs
        val lastSampleNs = sensorTimeNs + (sampleCount - 1) * 1_000_000_000L / ECG_SAMPLE_RATE_HZ
        mapper.observe(lastSampleNs, phoneTimeMs)

        val expected = expectedNextNs
        val contiguous = expected != null && abs(sensorTimeNs - expected) <= CONTIGUOUS_TOLERANCE_MS * 1_000_000L
        if (!contiguous) segmentStartUtcMs = mapper.toUtcMs(sensorTimeNs) ?: phoneTimeMs
        expectedNextNs = sensorTimeNs + sampleCount * 1_000_000_000L / ECG_SAMPLE_RATE_HZ
        return !contiguous
    }
}

/** Splits [frames] (in arrival order) into [EcgSegment]s (see [SegmentTracker]). */
fun buildSegments(frames: List<RawRecord.Ecg>): List<EcgSegment> {
    val tracker = SegmentTracker()
    val result = ArrayList<EcgSegment>()
    val chunks = ArrayList<IntArray>()
    var segmentStartUtc = 0L
    val frameStarts = ArrayList<Int>() // per frame of the open segment: index of its first sample ...
    val frameNs = ArrayList<Long>() // ... and its sensor time
    var segmentSamples = 0

    fun closeSegment() {
        if (chunks.isEmpty()) return
        val all = IntArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(all, pos); pos += c.size }
        result += EcgSegment(result.size, segmentStartUtc, all, frameStarts.toIntArray(), frameNs.toLongArray())
        chunks.clear()
        frameStarts.clear()
        frameNs.clear()
        segmentSamples = 0
    }

    for (f in frames) {
        val n = f.samplesUv.size
        if (n == 0) continue
        if (tracker.add(f.sensorTimeNs, f.phoneTimeMs, n)) {
            closeSegment()
            segmentStartUtc = tracker.segmentStartUtcMs
        }
        chunks += f.samplesUv
        frameStarts += segmentSamples
        frameNs += f.sensorTimeNs
        segmentSamples += n
    }
    closeSegment()
    return result
}
