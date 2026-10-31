package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.hrv.HRV_WINDOW_MS
import com.punkunpun.polarisecg.dsp.hrv.Nn
import com.punkunpun.polarisecg.dsp.hrv.hrvWindows
import com.punkunpun.polarisecg.dsp.hrv.nnIntervals
import com.punkunpun.polarisecg.dsp.hrv.spectrum
import com.punkunpun.polarisecg.dsp.hrv.timeDomain
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HrvTest {
    private val t0 = 1_793_300_100_000L // a multiple of 5 min
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    private fun beat(t: Long, cls: String = "N", good: Boolean = true, segment: Int = 0) =
        Beat(t, segment, null, cls, quality, good)

    /** Beats whose intervals are [rr] in turn, from t0. */
    private fun beatsFrom(rr: List<Long>): List<Beat> {
        var t = t0
        val out = arrayListOf(beat(t))
        for (r in rr) { t += r; out += beat(t) }
        return out
    }

    // ---- which intervals count -----------------------------------------------------------------------------------

    @Test fun anIntervalNeedsTwoGoodNormalBeatsInOneSegment() {
        val beats = listOf(
            beat(t0), beat(t0 + 800), beat(t0 + 1600, "S"), beat(t0 + 2400), beat(t0 + 3200, good = false),
            beat(t0 + 4000), beat(t0 + 4800, segment = 1), beat(t0 + 5600, segment = 1),
        )
        // only 0-800 and 4800-5600: the others touch an S, a poor beat, or cross a segment
        assertEquals(listOf(t0 to t0 + 800, t0 + 4800 to t0 + 5600), nnIntervals(beats).map { it.startUtcMs to it.endUtcMs })
    }

    @Test fun impossibleIntervalsAndBigJumpsAreDropped() {
        // 800, 800, a missed beat (1600: a 100 % jump), 800 (follows a dropped one: nothing to compare with), 250 (too short), 800
        val nn = nnIntervals(beatsFrom(listOf(800, 800, 1600, 800, 250, 800)))
        assertEquals(listOf<Long>(800, 800, 800, 800), nn.map { it.ms })
        // a 15 % change is allowed, a 25 % one is not
        assertEquals(2, nnIntervals(beatsFrom(listOf(800, 920))).size)
        assertEquals(1, nnIntervals(beatsFrom(listOf(800, 1000))).size)
    }

    // ---- time domain ---------------------------------------------------------------------------------------------

    @Test fun rmssdSdnnAndPnn50OfASimpleSeries() {
        val td = timeDomain(nnIntervals(beatsFrom(listOf(800, 860, 800, 860, 800))))
        assertEquals(5, td.count)
        assertEquals(824.0, td.meanMs!!, 1e-9)
        assertEquals(60_000.0 / 824.0, td.meanHrBpm!!, 1e-9)
        assertEquals(60.0, td.rmssdMs!!, 1e-9)         // every successive difference is 60 ms
        assertEquals(100.0, td.pnn50Percent!!, 1e-9)   // all four differences are above 50 ms
        assertEquals(sqrt((3 * 24.0 * 24 + 2 * 36.0 * 36) / 4), td.sdnnMs!!, 1e-9)
    }

    @Test fun successiveDifferencesNeedARealNeighbourAndNoIntervalsGiveNoFigures() {
        val apart = listOf(Nn(0, 800), Nn(5000, 5850)) // two intervals that do not share a beat
        assertNull(timeDomain(apart).rmssdMs)
        assertNotNull(timeDomain(apart).sdnnMs)
        assertNull(timeDomain(emptyList()).meanMs)
    }

    // ---- spectrum --------------------------------------------------------------------------------------------------

    /** NN series with a sinusoidal oscillation of [amplitude] ms at [hz] around 900 ms (so the beats are unevenly spaced). */
    private fun oscillating(hz: Double, amplitude: Double, seconds: Int): List<Nn> {
        val nn = ArrayList<Nn>()
        var t = t0
        while (t < t0 + seconds * 1000L) {
            val rr = Math.round(900 + amplitude * sin(2 * PI * hz * (t - t0) / 1000.0))
            nn += Nn(t, t + rr)
            t += rr
        }
        return nn
    }

    @Test fun anOscillationInTheHighBandShowsUpAsHfPowerOfHalfTheSquaredAmplitude() {
        val s = spectrum(oscillating(0.25, 40.0, 300))!!
        assertEquals(40.0 * 40 / 2, s.hfMs2, 800.0 * 0.15) // within 15 %
        assertTrue(s.lfMs2 < s.hfMs2 * 0.05, "LF ${s.lfMs2} should be tiny next to HF ${s.hfMs2}")
    }

    @Test fun anOscillationInTheLowBandShowsUpAsLfAndTheRatioIsLarge() {
        val s = spectrum(oscillating(0.10, 30.0, 300))!!
        assertEquals(30.0 * 30 / 2, s.lfMs2, 450.0 * 0.15)
        assertTrue(s.lfHf!! > 10, "LF/HF ${s.lfHf}")
    }

    @Test fun tooFewIntervalsGiveNoSpectrum() {
        assertNull(spectrum(oscillating(0.25, 40.0, 30)))
    }

    // ---- windows -----------------------------------------------------------------------------------------------------

    @Test fun windowsAreFiveMinutesAlignedAndCoverageDecidesIfTheyAreValid() {
        // 10 minutes of 800 ms beats, but 2.5 minutes of the second window are S beats
        val beats = ArrayList<Beat>()
        var t = t0
        while (t < t0 + 600_000) {
            val inHole = t >= t0 + 300_000 + 60_000 && t < t0 + 300_000 + 210_000
            beats += beat(t, if (inHole) "S" else "N")
            t += 800
        }
        val windows = hrvWindows(nnIntervals(beats))
        assertEquals(2, windows.size)
        assertEquals(t0, windows[0].startUtcMs)
        assertEquals(t0 + HRV_WINDOW_MS, windows[1].startUtcMs)
        assertTrue(windows[0].valid && windows[0].coverage > 0.99, "coverage ${windows[0].coverage}")
        assertTrue(!windows[1].valid, "coverage ${windows[1].coverage}")
        assertNotNull(windows[0].spectrum)
        assertNull(windows[1].spectrum) // not worth a spectrum
        assertEquals(0.0, windows[0].time.rmssdMs!!, 1e-9) // a perfectly regular series has no variability
    }
}
