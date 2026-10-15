package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.analysis.buildSegments
import com.punkunpun.polarisecg.dsp.analysis.detectBeatsPlaceholder
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisTest {

    private val t0 = 1_791_843_330_000L // phone UTC of the first sample

    /** [seconds] of flat signal with a sharp 5-sample spike every second, starting at sample 65. */
    private fun spikes(seconds: Int): IntArray {
        val x = IntArray(130 * seconds)
        for (s in 0 until seconds) {
            val c = 65 + 130 * s
            val shape = intArrayOf(200, 800, 1500, -400, -100)
            for (k in shape.indices) if (c + k < x.size) x[c + k] = shape[k]
        }
        return x
    }

    /** Cuts [x] into 70-sample frames, as the H10 sends them. [startSample] shifts the sensor clock. */
    private fun frames(x: IntArray, startSample: Long = 0, gapAfterFrame: Int = -1, gapSamples: Int = 0): List<RawRecord.Ecg> {
        val out = ArrayList<RawRecord.Ecg>()
        var sampleNo = startSample
        var f = 0
        for (from in x.indices step 70) {
            val chunk = x.copyOfRange(from, minOf(from + 70, x.size))
            if (f == gapAfterFrame + 1 && gapAfterFrame >= 0) sampleNo += gapSamples
            val utcMs = t0 + sampleNo * 1000 / 130
            val sensorNs = (utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000
            // the phone gets a frame ~100 ms after its last sample was taken
            out += RawRecord.Ecg(utcMs + chunk.size * 1000L / 130 + 100, sensorNs, chunk)
            sampleNo += chunk.size
            f++
        }
        return out
    }

    @Test
    fun contiguousFramesFormOneSegment() {
        val seg = buildSegments(frames(spikes(10)))
        assertEquals(1, seg.size)
        assertEquals(1300, seg[0].samplesUv.size)
        // start time = phone time of the first sample, within the 100 ms delivery latency
        assertTrue(abs(seg[0].startUtcMs - t0) <= 150, "start ${seg[0].startUtcMs - t0}")
    }

    @Test
    fun missingFramesSplitSegmentsAndMakeAGapEvent() {
        // 10 s of signal, then 5 s missing (650 samples) after frame 9 (630 samples in)
        val result = analyzeEcg(frames(spikes(10), gapAfterFrame = 9, gapSamples = 650))
        assertEquals(2, result.segments.size)
        assertEquals(1, result.events.size)
        assertEquals("GAP", result.events[0].type)
        assertTrue(abs(result.events[0].durationMs - 5000) <= 100, "gap ${result.events[0].durationMs}")
    }

    @Test
    fun placeholderDetectorFindsOneBeatPerSpike() {
        val beats = detectBeatsPlaceholder(spikes(20), 130)
        assertEquals(20, beats.size)
        for ((s, idx) in beats.withIndex()) {
            assertTrue(abs(idx - (65 + 130 * s + 2)) <= 2, "beat $s at $idx")
        }
    }

    @Test
    fun windowsReportCoverageAndHeartRate() {
        val result = analyzeEcg(frames(spikes(60)))
        assertTrue(result.windows.size in 6..7)
        val full = result.windows.filter { it.coverage > 0.99 }
        assertTrue(full.isNotEmpty())
        for (w in full) {
            assertTrue(abs((w.meanHrBpm ?: 0.0) - 60.0) < 1.0, "hr ${w.meanHrBpm}")
        }
    }
}
