package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.buildSegments
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The H10 really samples at ~130.27 Hz (measured from its timestamps); time must follow the sensor clock. */
class SegmentTimingTest {
    private val t0 = 1_791_843_330_000L
    private val realRate = 130.27

    /** [seconds] of signal in 73-sample frames (as the H10 sends) whose sensor times follow [rate]. */
    private fun frames(seconds: Int, rate: Double = realRate): List<RawRecord.Ecg> {
        val total = (seconds * rate).toInt()
        val out = ArrayList<RawRecord.Ecg>()
        var i = 0
        while (i < total) {
            val n = minOf(73, total - i)
            val firstMs = t0 + i * 1000.0 / rate
            val sensorNs = ((firstMs - POLAR_EPOCH_UNIX_MS) * 1_000_000).toLong()
            out += RawRecord.Ecg((firstMs + n * 1000.0 / rate + 100).toLong(), sensorNs, IntArray(n) { it })
            i += n
        }
        return out
    }

    @Test
    fun sampleTimesFollowTheSensorClockNotTheNominalRate() {
        val seg = buildSegments(frames(600)).single()
        for (i in listOf(0, 1_000, 40_000, 78_000)) {
            val truth = i * 1000.0 / realRate
            val got = (seg.sampleUtcMs(i) - seg.startUtcMs).toDouble()
            assertTrue(abs(got - truth) <= 1.0, "sample $i: expected ${truth.toLong()} ms, got ${got.toLong()}")
        }
        // with the nominal 130 Hz the last of these would be off by 1.2 s
        val nominal = 78_000 * 1000.0 / 130
        assertTrue(abs((seg.sampleUtcMs(78_000) - seg.startUtcMs) - nominal) > 1000)
    }

    @Test
    fun durationAndEndUseTheMeasuredRate() {
        val seg = buildSegments(frames(600)).single()
        assertTrue(abs(seg.durationMs - 600_000) <= 600, "duration ${seg.durationMs}")
        assertEquals(seg.startUtcMs + seg.durationMs, seg.endUtcMs)
    }

    @Test
    fun fractionalPositionsInterpolateBetweenSamples() {
        val seg = buildSegments(frames(60)).single()
        val a = seg.sampleUtcMs(1000)
        val b = seg.sampleUtcMs(1001)
        val mid = seg.sampleUtcMs(1000.5)
        assertTrue(mid in a..b)
        assertTrue(abs(seg.sampleUtcMs(1000.0) - a) <= 1)
    }

    @Test
    fun nominalRateStillWorksForSyntheticData() {
        val seg = buildSegments(frames(60, rate = 130.0)).single()
        assertTrue(abs((seg.sampleUtcMs(6500) - seg.startUtcMs) - 50_000) <= 1)
    }
}
