package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.quality.EcgBeatStream
import com.punkunpun.polarisecg.dsp.quality.ScoredBeat
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EcgBeatStreamTest {
    private val fs = 130.0

    private fun gauss(t: Double, c: Double, s: Double) = exp(-(t - c) * (t - c) / (2 * s * s))

    /** Regular synthetic ECG; between [noisyFromS] and [noisyToS] white noise of [noiseUv] RMS is added. */
    private fun ecg(
        seconds: Double,
        rr: Double = 0.8,
        noiseUv: Double = 0.0,
        noisyFromS: Double = 0.0,
        noisyToS: Double = 0.0,
        flatFromS: Double = Double.MAX_VALUE,
        flatToS: Double = Double.MAX_VALUE,
    ): IntArray {
        val rnd = Random(5)
        val beats = generateSequence(1.0) { it + rr }.takeWhile { it < seconds - 0.5 }.toList()
        return IntArray((seconds * fs).toInt()) { j ->
            val t = j / fs
            var v = 20_000.0
            if (t < flatFromS || t >= flatToS) {
                for (b in beats) if (kotlin.math.abs(t - b) < 0.6) {
                    v += 1000 * gauss(t, b, 0.011) + 250 * gauss(t, b + 0.25, 0.045) + 100 * gauss(t, b - 0.16, 0.025)
                }
            }
            if (t in noisyFromS..noisyToS) v += noiseUv * rnd.nextGaussian()
            v.roundToInt()
        }
    }

    private fun run(x: IntArray, frame: Int): List<ScoredBeat> {
        val s = EcgBeatStream()
        val out = ArrayList<ScoredBeat>()
        var i = 0
        while (i < x.size) {
            out += s.process(x.copyOfRange(i, minOf(x.size, i + frame)))
            i += frame
        }
        out += s.finish()
        return out
    }

    @Test
    fun cleanEcgIsAlmostAllGood() {
        val beats = run(ecg(120.0), 70)
        assertTrue(beats.size > 140)
        assertTrue(beats.count { it.good } >= beats.size - 3, "good ${beats.count { it.good }} of ${beats.size}")
        assertTrue(beats.drop(3).all { it.quality.agreed })
    }

    @Test
    fun heavyNoiseMakesBeatsBadAndOnlyThere() {
        // 60 s clean, 40 s of strong noise (RMS 40 % of the beat height), 60 s clean
        val beats = run(ecg(160.0, noiseUv = 400.0, noisyFromS = 60.0, noisyToS = 100.0), 70)
        val inNoise = beats.filter { it.position / fs in 62.0..98.0 }
        val clean = beats.filter { it.position / fs in 10.0..55.0 || it.position / fs in 108.0..155.0 }
        assertTrue(inNoise.count { !it.good } >= 0.9 * inNoise.size, "bad in noise: ${inNoise.count { !it.good }}/${inNoise.size}")
        assertTrue(clean.count { it.good } >= 0.97 * clean.size, "good in clean: ${clean.count { it.good }}/${clean.size}")
    }

    @Test
    fun mildNoiseStillCountsAsGood() {
        val beats = run(ecg(100.0, noiseUv = 15.0, noisyFromS = 0.0, noisyToS = 100.0), 70)
        assertTrue(beats.count { it.good } >= 0.97 * beats.size)
    }

    @Test
    fun resultDoesNotDependOnFrameSize() {
        // includes a single call with the whole signal: more than the ring buffer holds
        val x = ecg(90.0, noiseUv = 120.0, noisyFromS = 30.0, noisyToS = 50.0)
        val a = run(x, 70)
        val b = run(x, x.size)
        val c = run(x, 13)
        assertEquals(a.map { it.position }, b.map { it.position })
        assertEquals(a.map { it.quality.sqi }, b.map { it.quality.sqi })
        assertEquals(a.map { it.quality.sqi }, c.map { it.quality.sqi })
    }

    @Test
    fun resetStartsOver() {
        val x = ecg(40.0, noiseUv = 100.0, noisyFromS = 10.0, noisyToS = 20.0)
        val s = EcgBeatStream()
        val first = s.process(x) + s.finish()
        s.reset()
        val second = s.process(x) + s.finish()
        assertEquals(first.map { it.quality.sqi }, second.map { it.quality.sqi })
    }

    @Test
    fun beatsComeOutInOrder() {
        val beats = run(ecg(60.0, rr = 0.55, noiseUv = 150.0, noisyFromS = 0.0, noisyToS = 60.0), 70)
        assertTrue(beats.zipWithNext().all { (a, b) -> b.position > a.position })
    }
}
