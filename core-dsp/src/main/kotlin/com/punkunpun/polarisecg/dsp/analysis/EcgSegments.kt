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
 * A stretch of ECG without holes. [startUtcMs] is the phone UTC of the first sample.
 *
 * The H10 does not sample at exactly 130 Hz: the sensor's own timestamps show ~130.27 Hz in
 * every recording we have (0.2 % fast), so "sample i is at i / 130 s" drifts by seconds per hour
 * and makes every RR interval 1-2 ms too long. Time inside a segment therefore follows the sensor
 * clock: [frameStartIndex] / [frameSensorNs] give, for each frame, the index of its first sample
 * and the sensor time of it, and positions between frames are interpolated linearly.
 */
class EcgSegment(
    val index: Int,
    val startUtcMs: Long,
    val samplesUv: IntArray,
    private val frameStartIndex: IntArray,
    private val frameSensorNs: LongArray,
) {
    init {
        require(frameStartIndex.isNotEmpty() && frameStartIndex.size == frameSensorNs.size) { "need at least one frame" }
    }

    /** Sensor time (ns) of sample [position] (fractional; may lie slightly outside the segment). */
    private fun sensorNs(position: Double): Double {
        // last frame whose first sample is at or before `position`
        var lo = 0
        var hi = frameStartIndex.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frameStartIndex[mid] <= position) lo = mid else hi = mid - 1
        }
        // seconds per sample from the neighbouring frames: the next frame, else the previous one, else nominal
        val nsPerSample = when {
            lo + 1 < frameStartIndex.size ->
                (frameSensorNs[lo + 1] - frameSensorNs[lo]).toDouble() / (frameStartIndex[lo + 1] - frameStartIndex[lo])
            lo > 0 ->
                (frameSensorNs[lo] - frameSensorNs[lo - 1]).toDouble() / (frameStartIndex[lo] - frameStartIndex[lo - 1])
            else -> 1e9 / ECG_SAMPLE_RATE_HZ
        }
        return frameSensorNs[lo] + (position - frameStartIndex[lo]) * nsPerSample
    }

    /** Milliseconds from the first sample to sample [position]. */
    private fun elapsedMs(position: Double): Double = (sensorNs(position) - frameSensorNs[0]) / 1e6

    val durationMs: Long get() = Math.round(elapsedMs(samplesUv.size.toDouble()))
    val endUtcMs: Long get() = startUtcMs + durationMs

    fun sampleUtcMs(i: Int): Long = startUtcMs + Math.round(elapsedMs(i.toDouble()))

    /** Same for a fractional sample position (a refined peak), rounded to whole milliseconds. */
    fun sampleUtcMs(position: Double): Long = startUtcMs + Math.round(elapsedMs(position))
}

/** Frame start times may deviate this much from "exactly after the previous frame" (ms). */
private const val CONTIGUOUS_TOLERANCE_MS = 25L

/**
 * Splits [frames] (in arrival order) into [EcgSegment]s. The start of each segment is converted
 * to phone UTC with [TimeMapper]; inside a segment time follows the sensor clock, so RR intervals
 * are as exact as the sensor's own clock (the phone clock only places the segment on the calendar).
 */
fun buildSegments(frames: List<RawRecord.Ecg>): List<EcgSegment> {
    val mapper = TimeMapper()
    val result = ArrayList<EcgSegment>()
    val chunks = ArrayList<IntArray>()
    var segmentStartUtc = 0L
    val frameStarts = ArrayList<Int>() // per frame of the open segment: index of its first sample ...
    val frameNs = ArrayList<Long>() // ... and its sensor time
    var segmentSamples = 0
    var expectedNextNs: Long? = null
    var lastSensorNs = Long.MIN_VALUE

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
        // The sensor clock stepped far back: a new connection. Its offset to the phone may differ.
        if (lastSensorNs != Long.MIN_VALUE && f.sensorTimeNs < lastSensorNs - 1_000_000_000L) {
            mapper.startConnection()
        }
        lastSensorNs = f.sensorTimeNs
        val lastSampleNs = f.sensorTimeNs + (n - 1) * 1_000_000_000L / ECG_SAMPLE_RATE_HZ
        mapper.observe(lastSampleNs, f.phoneTimeMs)

        val expected = expectedNextNs
        val contiguous = expected != null && abs(f.sensorTimeNs - expected) <= CONTIGUOUS_TOLERANCE_MS * 1_000_000L
        if (!contiguous) {
            closeSegment()
            segmentStartUtc = mapper.toUtcMs(f.sensorTimeNs) ?: f.phoneTimeMs
        }
        chunks += f.samplesUv
        frameStarts += segmentSamples
        frameNs += f.sensorTimeNs
        segmentSamples += n
        expectedNextNs = f.sensorTimeNs + n * 1_000_000_000L / ECG_SAMPLE_RATE_HZ
    }
    closeSegment()
    return result
}
