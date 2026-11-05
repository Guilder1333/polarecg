package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.SegmentClock
import com.punkunpun.polarisecg.dsp.live.EcgHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EcgHistoryTest {
    private val t0 = 1_791_843_330_000L

    /** Feeds [seconds] of "ECG" into [h] as frames of 65 samples at exactly 130 Hz; sample i has the value i + [valueOffset]. */
    private fun feed(h: EcgHistory, clock: SegmentClock, firstSample: Int, seconds: Int, valueOffset: Int = 0, startNs: Long = 0L): Int {
        var n = firstSample
        val total = firstSample + seconds * 130
        while (n < total) {
            clock.addFrame(n, startNs + n * 1_000_000_000L / 130)
            h.add(IntArray(65) { valueOffset + n + it })
            n += 65
        }
        return n
    }

    @Test
    fun clipHoldsTheSamplesBetweenTheTimes() {
        val h = EcgHistory()
        val clock = SegmentClock(t0)
        h.startSegment(clock)
        feed(h, clock, 0, 60)
        val clip = assertNotNull(h.clip(anchorUtcMs = t0 + 30_000, fromUtcMs = t0 + 20_000, toUtcMs = t0 + 40_000))
        // 20 s at 130 Hz = 2600 samples (+1 for the closed interval); values are the sample indices
        assertTrue(clip.size in 2600..2602, "size ${clip.size}")
        assertTrue(clip.samplesUv[0] in 2598..2602, "first sample ${clip.samplesUv[0]}") // sample 2600 is at 20.0 s
        assertEquals(t0 + 20_000, clip.startUtcMs, "starts at the first sample at or after the wanted time")
        assertEquals(1000.0 / 130, clip.msPerSample, 0.01)
        for (i in 1 until clip.size) assertEquals(clip.samplesUv[i - 1] + 1, clip.samplesUv[i]) // contiguous
    }

    @Test
    fun clipIsCutDownToWhatExists() {
        val h = EcgHistory()
        val clock = SegmentClock(t0)
        h.startSegment(clock)
        feed(h, clock, 0, 30)
        val clip = assertNotNull(h.clip(t0 + 5_000, t0 - 10_000, t0 + 15_000)) // 10 s before the start do not exist
        assertEquals(0, clip.samplesUv[0])
        assertEquals(t0, clip.startUtcMs)
        assertTrue(clip.endUtcMs in (t0 + 14_990)..(t0 + 15_000))
    }

    @Test
    fun nothingWhenTheAnchorIsNotInMemoryOrTooLittleRemains() {
        val h = EcgHistory()
        assertNull(h.clip(t0, t0 - 1_000, t0 + 1_000), "empty history")
        val clock = SegmentClock(t0)
        h.startSegment(clock)
        feed(h, clock, 0, 10)
        assertNull(h.clip(t0 + 60_000, t0 + 50_000, t0 + 70_000), "anchor after the data")
        assertNull(h.clip(t0 + 9_000, t0 + 9_000, t0 + 9_500), "less than 2 s")
    }

    @Test
    fun oldSamplesAreDroppedAndTheNewestKept() {
        val h = EcgHistory(retainSamples = 130 * 20)
        val clock = SegmentClock(t0)
        h.startSegment(clock)
        feed(h, clock, 0, 60)
        assertNull(h.clip(t0 + 1_000, t0, t0 + 5_000), "the first seconds are gone")
        val newest = assertNotNull(h.latestUtcMs)
        val clip = assertNotNull(h.clip(newest - 1_000, newest - 10_000, newest))
        assertTrue(clip.size > 1000)
    }

    @Test
    fun aClipNeverSpansTwoSegments() {
        val h = EcgHistory()
        val c1 = SegmentClock(t0)
        h.startSegment(c1)
        feed(h, c1, 0, 20)
        val t1 = t0 + 25_000 // 5 s of nothing, then a second segment
        val c2 = SegmentClock(t1)
        h.startSegment(c2)
        feed(h, c2, 0, 20, valueOffset = 1_000_000, startNs = 500_000_000_000L)
        val clip = assertNotNull(h.clip(t1 + 10_000, t0 + 15_000, t1 + 20_000)) // wants to start in the old segment
        assertTrue(clip.samplesUv.all { it >= 1_000_000 }, "only samples of the segment that holds the anchor")
        assertEquals(t1, clip.startUtcMs)
    }
}
