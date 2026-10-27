package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.pattern.PatternType
import com.punkunpun.polarisecg.dsp.pattern.detectPatterns
import com.punkunpun.polarisecg.dsp.pattern.hourlyBurden
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatternsTest {
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    /** Beats from a string like "NNVVN": one letter per beat, [rr] ms apart, `|` starts a new segment. */
    private fun beats(classes: String, rr: Long = 800, start: Long = 1_000_000L, good: (Int) -> Boolean = { true }): List<Beat> {
        var t = start
        var segment = 0
        val out = ArrayList<Beat>()
        for (c in classes) {
            if (c == '|') { segment++; t += 10_000; continue }
            out += Beat(t, segment, null, c.toString(), quality, good(out.size))
            t += rr
        }
        return out
    }

    private fun types(b: List<Beat>) = detectPatterns(b).map { it.type }

    @Test fun ordinaryRhythmHasNoEvents() = assertEquals(emptyList(), types(beats("NNNNNNNNVNNNNSNNN")))

    @Test fun coupletTripletAndRunAreOneEventEach() {
        assertEquals(listOf("V_COUPLET"), types(beats("NNVVNN")))
        assertEquals(listOf("V_TRIPLET"), types(beats("NNVVVNN")))
        assertEquals(listOf("V_RUN"), types(beats("NNVVVVVNN")))
        assertEquals(listOf("S_COUPLET"), types(beats("NNSSNN")))
    }

    @Test fun runDetailSaysTheRate() {
        val e = detectPatterns(beats("NNVVVVNN", rr = 500)).single()
        assertEquals("V_RUN", e.type)
        assertEquals(1_500L, e.durationMs)
        assertTrue("4 beats" in e.detail && "120 bpm" in e.detail, e.detail)
    }

    @Test fun bigeminyNeedsThreeCycles() {
        assertEquals(listOf("V_BIGEMINY"), types(beats("NNNVNVNVNN")))
        assertEquals(emptyList(), types(beats("NNNVNVNN")))
        val e = detectPatterns(beats("NVNVNVNVN")).single()
        assertEquals("V_BIGEMINY", e.type)
        assertTrue("4 cycles" in e.detail, e.detail)
    }

    @Test fun trigeminyNeedsThreeCycles() {
        assertEquals(listOf("V_TRIGEMINY"), types(beats("NNVNNVNNVNN")))
        assertEquals(emptyList(), types(beats("NNVNNVNN")))
        assertEquals(listOf("S_TRIGEMINY"), types(beats("NNSNNSNNSN")))
    }

    @Test fun aQBeatOrAGapEndsAPattern() {
        assertEquals(emptyList(), types(beats("NVNVQVNN")))
        assertEquals(emptyList(), types(beats("NVNV|NVNN"))) // 2 cycles + 1 in another segment
    }

    @Test fun ectopicBeatsOnBothSidesOfAGapAreNotACouplet() {
        assertEquals(emptyList(), types(beats("NNV|VNN")))
    }

    @Test fun pauseNeedsTwoGoodBeatsAndMoreThanTwoSeconds() {
        val b = beats("NNN")
        val longer = listOf(b[0], b[1], Beat(b[1].utcMs + 2_500, 0, null, "N", quality, true))
        val e = detectPatterns(longer).single()
        assertEquals(PatternType.PAUSE, e.type)
        assertEquals(2_500L, e.durationMs)
        assertEquals(b[1].utcMs, e.utcMs)
        val exactly = listOf(b[0], Beat(b[0].utcMs + 2_000, 0, null, "N", quality, true))
        assertEquals(emptyList(), detectPatterns(exactly).map { it.type })
        val poor = listOf(b[0], Beat(b[0].utcMs + 3_000, 0, null, "N", quality, false))
        assertEquals(emptyList(), detectPatterns(poor).map { it.type }) // might just be a missed beat in noise
        val gap = listOf(b[0], Beat(b[0].utcMs + 3_000, 1, null, "N", quality, true))
        assertEquals(emptyList(), detectPatterns(gap).map { it.type }) // a gap in the signal is not a pause
    }

    @Test fun eventsAreSortedByTime() {
        val e = detectPatterns(beats("NVNVNVNNNNVVNN"))
        assertEquals(e.map { it.utcMs }, e.map { it.utcMs }.sorted())
        assertEquals(listOf("V_BIGEMINY", "V_COUPLET"), e.map { it.type })
    }

    @Test fun burdenIsPerUtcHourAndLeavesPoorBeatsOut() {
        val hour = 3_600_000L
        val a = beats("NNNNNNNNVS", start = 5 * hour + 1_000)
        val b = beats("NNQQ", start = 6 * hour + 1_000)
        val h = hourlyBurden(a + b)
        assertEquals(2, h.size)
        assertEquals(5 * hour, h[0].startUtcMs)
        assertEquals(8, h[0].n); assertEquals(1, h[0].v); assertEquals(1, h[0].s)
        assertEquals(10.0, h[0].vPercent!!, 1e-9)
        assertEquals(2, h[1].q)
        assertEquals(0.0, h[1].vPercent!!, 1e-9) // 2 judged beats, none ventricular
    }

    @Test fun questionableVsAreCountedWithinTheVs() {
        val b = beats("NNVVVN")
        b[2].vDoubt = true
        b[3].vDoubt = true
        b[0].vDoubt = true // an N is never a questionable V
        val h = hourlyBurden(b).single()
        assertEquals(3, h.v)
        assertEquals(2, h.vDoubt)
    }

    @Test fun anHourWithOnlyPoorBeatsHasNoPercentage() {
        val h = hourlyBurden(beats("QQQ")).single()
        assertEquals(null, h.vPercent)
    }
}
