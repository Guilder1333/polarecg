package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.hrv.hrvWindows
import com.punkunpun.polarisecg.dsp.hrv.nnIntervals
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.summary.SummaryBuilder
import com.punkunpun.polarisecg.dsp.summary.summarize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SummaryTest {
    private val t0 = 1_793_300_100_000L // a multiple of 5 min (and of 1 min)

    private fun quality(motion: Double? = null) = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true, motion)

    private fun beat(t: Long, cls: String = "N", segment: Int = 0, motion: Double? = null, doubt: Boolean = false) =
        Beat(t, segment, null, cls, quality(motion), true).also { it.vDoubt = doubt }

    /** 800 ms beats from t0 for [minutes] minutes; [clsAt] may change a beat's class by its index. */
    private fun steady(minutes: Int, clsAt: (Int) -> String = { "N" }): List<Beat> {
        val out = ArrayList<Beat>()
        var i = 0
        var t = t0
        while (t < t0 + minutes * 60_000L) { out += beat(t, clsAt(i)); i++; t += 800 }
        return out
    }

    @Test fun oneRowPerMinuteWithCountsAndHeartRateFromNormalIntervals() {
        // beat 10 is S and beat 20 is V (questionable): both are in the first minute
        val beats = steady(3) { i -> when (i) { 10 -> "S"; 20 -> "V"; else -> "N" } }
            .mapIndexed { i, b -> if (i == 20) beat(b.utcMs, "V", doubt = true) else b }
        val minutes = summarize(beats).minutes
        assertEquals(3, minutes.size)
        assertEquals(listOf(t0, t0 + 60_000, t0 + 120_000), minutes.map { it.startUtcMs })
        val first = minutes[0]
        assertEquals(75, first.beats)          // 60 s / 0.8 s
        assertEquals(1, first.s)
        assertEquals(1, first.v)
        assertEquals(1, first.vDoubt)
        assertEquals(73, first.n)
        assertEquals(75.0, first.hrMeanBpm!!, 1e-9) // 800 ms intervals only; the ones next to the S and the V are left out
        assertEquals(75.0, first.hrMinBpm!!, 1e-9)
        assertEquals(75.0, first.hrMaxBpm!!, 1e-9)
        assertTrue(first.coverage in 0.98..1.0, "coverage ${first.coverage}") // the very first beat has no interval before it
        assertEquals(1.0, minutes[1].coverage, 1e-9)
        assertNull(first.motionMg)
    }

    @Test fun aHoleInTheSignalLowersTheCoverageOfThatMinute() {
        // beats for 30 s, nothing for 20 s, beats again: a gap-free segment break; the long interval does not count as coverage
        val beats = ArrayList<Beat>()
        var t = t0
        while (t < t0 + 30_000) { beats += beat(t); t += 800 }
        t = t0 + 50_000
        while (t < t0 + 60_000) { beats += beat(t, segment = 1); t += 800 }
        val m = summarize(beats).minutes.single()
        assertTrue(m.coverage in 0.55..0.70, "coverage ${m.coverage}") // about 30 s + 10 s of 60
    }

    @Test fun motionIsTheMeanOfTheBeats() {
        val beats = listOf(beat(t0, motion = 10.0), beat(t0 + 800, motion = 30.0), beat(t0 + 1600))
        assertEquals(20.0, summarize(beats).minutes.single().motionMg!!, 1e-9)
    }

    @Test fun streamingGivesTheSameAsAllAtOnceAndEmitsMinutesWhenTheNextOneStarts() {
        val beats = steady(12)
        val builder = SummaryBuilder()
        val minutes = ArrayList<Long>()
        val windows = ArrayList<Long>()
        for (b in beats) builder.add(b).let { c -> minutes += c.minutes.map { it.startUtcMs }; windows += c.hrv.map { it.startUtcMs } }
        // before finish(): 11 of the 12 minutes and 2 of the 3 windows are complete
        assertEquals(11, minutes.size)
        assertEquals(listOf(t0, t0 + 300_000), windows)
        val rest = builder.finish()
        assertEquals(listOf(t0 + 11 * 60_000), rest.minutes.map { it.startUtcMs })
        assertEquals(listOf(t0 + 600_000), rest.hrv.map { it.startUtcMs })

        val all = summarize(beats)
        assertEquals(12, all.minutes.size)
        // the windows are those of hrv.csv
        val direct = hrvWindows(nnIntervals(beats))
        assertEquals(direct.map { it.startUtcMs }, all.hrv.map { it.startUtcMs })
        assertEquals(direct.map { it.nn }, all.hrv.map { it.nn })
        assertNotNull(all.hrv[0].spectrum)
    }
}
