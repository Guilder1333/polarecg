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

/** A stretch of ECG without holes. Sample i was taken at [startUtcMs] + i * 1000 / 130. */
class EcgSegment(val index: Int, val startUtcMs: Long, val samplesUv: IntArray) {
    val durationMs: Long get() = samplesUv.size * 1000L / ECG_SAMPLE_RATE_HZ
    val endUtcMs: Long get() = startUtcMs + durationMs

    fun sampleUtcMs(i: Int): Long = startUtcMs + i * 1000L / ECG_SAMPLE_RATE_HZ

    /** Same for a fractional sample position (a refined peak), rounded to whole milliseconds. */
    fun sampleUtcMs(position: Double): Long = startUtcMs + Math.round(position * 1000.0 / ECG_SAMPLE_RATE_HZ)
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
    var expectedNextNs: Long? = null
    var lastSensorNs = Long.MIN_VALUE

    fun closeSegment() {
        if (chunks.isEmpty()) return
        val all = IntArray(chunks.sumOf { it.size })
        var pos = 0
        for (c in chunks) { c.copyInto(all, pos); pos += c.size }
        result += EcgSegment(result.size, segmentStartUtc, all)
        chunks.clear()
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
        expectedNextNs = f.sensorTimeNs + n * 1_000_000_000L / ECG_SAMPLE_RATE_HZ
    }
    closeSegment()
    return result
}
