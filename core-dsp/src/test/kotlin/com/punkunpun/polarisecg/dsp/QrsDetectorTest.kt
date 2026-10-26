package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.detect.QrsDetector
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrsDetectorTest {
    private val fs = 130.0

    /** A synthetic ECG: P wave, QRS, T wave per beat, optional baseline wander and noise. */
    private class Ecg(val samples: IntArray, val rPositions: List<Int>)

    private fun gauss(t: Double, centre: Double, sigma: Double) = exp(-(t - centre) * (t - centre) / (2 * sigma * sigma))

    /**
     * [beatTimesS] are the R peak times. [rAmp] is negative for an upside-down ECG.
     * [ampOf] scales single beats (index -> factor), [tAmp] is the T wave height.
     */
    private fun ecg(
        beatTimesS: List<Double>,
        seconds: Double,
        rAmp: Double = 1000.0,
        tAmp: Double = 250.0,
        noise: Double = 0.0,
        wander: Double = 0.0,
        ampOf: (Int) -> Double = { 1.0 },
    ): Ecg {
        val rnd = Random(7)
        val n = (seconds * fs).toInt()
        val x = DoubleArray(n)
        for ((i, bt) in beatTimesS.withIndex()) {
            val a = ampOf(i)
            for (j in 0 until n) {
                val t = j / fs
                x[j] += a * rAmp * gauss(t, bt, 0.011) +            // QRS
                    tAmp * gauss(t, bt + 0.25, 0.045) * (if (rAmp < 0) -1 else 1) + // T wave
                    0.1 * rAmp * gauss(t, bt - 0.16, 0.025)         // P wave
            }
        }
        for (j in 0 until n) {
            x[j] += 20_000 + wander * sin(2 * PI * 0.25 * j / fs) + noise * rnd.nextGaussian()
        }
        return Ecg(IntArray(n) { x[it].roundToInt() }, beatTimesS.map { (it * fs).roundToInt() })
    }

    private fun regular(seconds: Double, rr: Double = 0.8, first: Double = 1.0) =
        generateSequence(first) { it + rr }.takeWhile { it < seconds - 0.5 }.toList()

    private fun detect(x: IntArray, frame: Int = 70): List<Int> {
        val d = QrsDetector()
        val found = ArrayList<Int>()
        for (chunk in x.toList().chunked(frame)) found += d.process(chunk.toIntArray()).toList()
        found += d.finish().toList()
        return found
    }

    /** Beats found far from every expected one count as false, expected ones with no match as missed. */
    private fun compare(found: List<Int>, expected: List<Int>, tolerance: Int = 3): Pair<Int, Int> {
        val from = (STABLE_FROM_S * fs).toInt() // the start-up phase is not judged
        val exp = expected.filter { it > from }
        val got = found.filter { it > from }
        val missed = exp.count { e -> got.none { abs(it - e) <= tolerance } }
        val extra = got.count { f -> exp.none { abs(it - f) <= tolerance } }
        return Pair(missed, extra)
    }

    private companion object {
        const val STABLE_FROM_S = 2.5
    }

    @Test
    fun findsEveryBeatOfACleanEcgAtTheRightSample() {
        val e = ecg(regular(60.0), 60.0)
        val found = detect(e.samples)
        // nothing is reported during the first 2 s of learning, so the first beat (at 1 s) may be missing
        val (missed, extra) = compare(found, e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
        assertTrue(found.size >= e.rPositions.size - 2, "found ${found.size} of ${e.rPositions.size}")
    }

    @Test
    fun worksWithUpsideDownEcg() {
        val e = ecg(regular(40.0), 40.0, rAmp = -1000.0)
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
    }

    @Test
    fun largeTWavesAreNotCountedAsBeats() {
        // T wave 60 % of the R height, 250 ms after it: within the T window (200-360 ms)
        val e = ecg(regular(60.0), 60.0, tAmp = 600.0)
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, extra)
        assertEquals(0, missed)
    }

    @Test
    fun baselineWanderAndNoiseAreTolerated() {
        val e = ecg(regular(60.0), 60.0, noise = 25.0, wander = 1500.0)
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
    }

    @Test
    fun followsChangingHeartRate() {
        // RR slowly from 1.2 s down to 0.5 s and back
        val times = ArrayList<Double>()
        var t = 1.0
        var i = 0
        while (t < 80) {
            times += t
            t += 0.85 + 0.35 * sin(2 * PI * i / 40.0)
            i++
        }
        val e = ecg(times, 80.0)
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
    }

    @Test
    fun aWeakBeatIsFoundBySearchBackOrThreshold() {
        // every 10th beat is only 40 % as tall (16 % of the normal energy)
        val e = ecg(regular(80.0), 80.0, ampOf = { if (it % 10 == 5) 0.4 else 1.0 })
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
    }

    @Test
    fun prematureBeatKeepsItsPositionAndTheNextOneIsStillFound() {
        val times = regular(40.0).toMutableList()
        times[20] = times[19] + 0.45 // an early beat, so the following ones shift too
        for (k in 21 until times.size) times[k] = times[k - 1] + 0.8
        val e = ecg(times, 40.0)
        val (missed, extra) = compare(detect(e.samples), e.rPositions)
        assertEquals(0, missed)
        assertEquals(0, extra)
    }

    @Test
    fun flatSignalGivesNoBeats() {
        assertTrue(detect(IntArray(130 * 30) { 12345 }).isEmpty())
    }

    @Test
    fun frameSizeDoesNotChangeTheResult() {
        val e = ecg(regular(30.0), 30.0, noise = 20.0)
        assertEquals(detect(e.samples, 70), detect(e.samples, 13))
        assertEquals(detect(e.samples, 70), detect(e.samples, e.samples.size))
    }

    @Test
    fun resetStartsOver() {
        val e = ecg(regular(20.0), 20.0)
        val d = QrsDetector()
        val first = d.process(e.samples).toList() + d.finish().toList()
        d.reset()
        val second = d.process(e.samples).toList() + d.finish().toList()
        assertEquals(first, second)
    }

    @Test
    fun reportedIndicesAreStrictlyAscending() {
        val e = ecg(regular(60.0, rr = 0.6), 60.0, noise = 40.0)
        val found = detect(e.samples)
        assertTrue(found.zipWithNext().all { (a, b) -> b > a })
    }
}
