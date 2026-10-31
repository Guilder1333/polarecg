package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.summary.HrvPoint
import com.punkunpun.polarisecg.dsp.summary.MinuteSummary
import com.punkunpun.polarisecg.dsp.summary.summarizeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RangeSummaryTest {
    private val h0 = 1_793_300_400_000L // a whole UTC hour

    private fun minute(i: Int, n: Int = 70, s: Int = 0, v: Int = 0, hr: Double? = 70.0, min: Double? = 60.0, max: Double? = 80.0, coverage: Double = 1.0) =
        MinuteSummary(h0 + i * 60_000L, n + s + v, n, s, v, 0, 0, hr, min, max, coverage, null)

    @Test fun totalsBurdenHeartRateAndCoverage() {
        // 30 minutes: minutes 0-9 have 2 S and 1 V each, the rest are clean; one minute has no HR
        val minutes = (0 until 30).map { i -> if (i < 10) minute(i, s = 2, v = 1) else minute(i) }
        val r = summarizeRange(minutes, emptyList(), h0, h0 + 30 * 60_000L)
        assertEquals(30 * 70 + 10 * 3, r.beats)
        assertEquals(20, r.s)
        assertEquals(10, r.v)
        assertEquals(100.0 * 30 / (r.n + r.s + r.v), r.burdenPercent!!, 1e-9)
        assertEquals(60.0, r.hrMinBpm!!, 1e-9)
        assertEquals(80.0, r.hrMaxBpm!!, 1e-9)
        assertEquals(70.0, r.hrMeanBpm!!, 1e-9)
        assertEquals(1.0, r.coverage, 1e-9)
    }

    @Test fun coverageIsTheRecordedShareOfTheRange() {
        // recorded 10 of 60 minutes, one of them only half
        val minutes = (0 until 10).map { i -> minute(i, coverage = if (i == 0) 0.5 else 1.0) }
        val r = summarizeRange(minutes, emptyList(), h0, h0 + 60 * 60_000L)
        assertEquals(9.5 / 60, r.coverage, 1e-9)
    }

    @Test fun hoursAreBucketedAndRowsOutsideTheRangeAreIgnored() {
        val minutes = (-5 until 125).map { i -> minute(i, s = if (i >= 60) 1 else 0) }
        val r = summarizeRange(minutes, emptyList(), h0, h0 + 120 * 60_000L)
        assertEquals(2, r.hours.size)
        assertEquals(h0, r.hours[0].startUtcMs)
        assertEquals(0, r.hours[0].s)
        assertEquals(60, r.hours[1].s)
        assertTrue(r.hours[1].burdenPercent!! > 1.0)
        assertEquals(120 * 70 + 60, r.beats)
    }

    @Test fun hrvIsTheMedianOfTheValidWindowsOnly() {
        val hrv = listOf(
            HrvPoint(h0, true, 20.0, 40.0), HrvPoint(h0 + 300_000, true, 30.0, 50.0), HrvPoint(h0 + 600_000, true, 40.0, 60.0),
            HrvPoint(h0 + 900_000, false, 500.0, 500.0), // not valid: left out
        )
        val r = summarizeRange(listOf(minute(0)), hrv, h0, h0 + 3_600_000L)
        assertEquals(3, r.hrvWindows)
        assertEquals(30.0, r.rmssdMedianMs!!, 1e-9)
        assertEquals(50.0, r.sdnnMedianMs!!, 1e-9)
        assertEquals(30.0, r.hours.single().rmssdMs!!, 1e-9)
    }

    @Test fun anEmptyRangeHasNoFigures() {
        val r = summarizeRange(emptyList(), emptyList(), h0, h0 + 3_600_000L)
        assertEquals(0, r.beats)
        assertNull(r.burdenPercent)
        assertNull(r.hrMeanBpm)
        assertNull(r.rmssdMedianMs)
        assertEquals(0.0, r.coverage, 1e-9)
    }
}
