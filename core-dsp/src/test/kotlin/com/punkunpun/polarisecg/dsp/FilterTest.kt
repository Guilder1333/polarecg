package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.filter.EcgFilters
import com.punkunpun.polarisecg.dsp.filter.FilterCascade
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilterTest {
    private val fs = 130.0

    private fun sine(freqHz: Double, seconds: Double = 20.0, amplitude: Double = 1000.0, offset: Double = 0.0) =
        DoubleArray((seconds * fs).toInt()) { offset + amplitude * sin(2 * PI * freqHz * it / fs) }

    private fun run(f: FilterCascade, x: DoubleArray) = DoubleArray(x.size) { f.process(x[it]) }

    /** Amplitude of a sine from RMS of the second half (after the filter has settled). */
    private fun amplitude(y: DoubleArray): Double {
        val tail = y.drop(y.size / 2)
        return sqrt(tail.sumOf { it * it } / tail.size) * sqrt(2.0)
    }

    @Test
    fun lowPassPassesLowAndBlocksHigh() {
        val lp = { FilterCascade.butterworthLowPass(fs, 20.0, 4) }
        assertEquals(1000.0, amplitude(run(lp(), sine(2.0))), 20.0)
        assertTrue(amplitude(run(lp(), sine(50.0))) < 20.0) // far above cutoff: < 2 %
    }

    @Test
    fun highPassBlocksLowAndPassesHigh() {
        val hp = { FilterCascade.butterworthHighPass(fs, 5.0, 2) }
        assertTrue(amplitude(run(hp(), sine(0.3))) < 30.0)
        assertEquals(1000.0, amplitude(run(hp(), sine(25.0))), 20.0)
    }

    @Test
    fun butterworthGainIsMinus3dbAtCutoff() {
        val f = FilterCascade.butterworthLowPass(fs, 20.0, 4)
        assertEquals(1 / sqrt(2.0), f.gainAt(20.0), 1e-3)
    }

    @Test
    fun gainFromFormulaMatchesMeasuredGain() {
        val f = EcgFilters().detection
        for (hz in listOf(2.0, 8.0, 12.0, 30.0)) {
            val fresh = EcgFilters().detection
            assertEquals(fresh.gainAt(hz) * 1000.0, amplitude(run(fresh, sine(hz))), 15.0, "at $hz Hz")
        }
        assertTrue(f.gainAt(10.0) > 0.7) // QRS band passes
    }

    @Test
    fun morphologyRemovesBaselineOffsetAndWander() {
        val f = EcgFilters().morphology
        // 50 mV offset plus a 0.1 Hz drift: both must be gone, a 10 Hz wave must stay
        val x = DoubleArray(20 * 130) { 50_000.0 + 3000 * sin(2 * PI * 0.1 * it / fs) + 500 * sin(2 * PI * 10 * it / fs) }
        val y = run(f, x)
        assertEquals(500.0, amplitude(y), 40.0)
    }

    @Test
    fun firstSampleDoesNotCauseStartUpSpike() {
        val f = EcgFilters().morphology
        val y = run(f, DoubleArray(130) { 80_000.0 }) // constant offset only
        assertTrue(y.all { abs(it) < 1.0 }, "max = ${y.maxOf { abs(it) }}")
    }

    @Test
    fun frameSplittingDoesNotChangeTheOutput() {
        val ints = IntArray(1300) { (1000 * sin(2 * PI * 7 * it / fs) + 30 * (it % 13)).toInt() }
        val whole = EcgFilters().detection.process(ints)
        val chunked = EcgFilters().detection.let { f ->
            ints.toList().chunked(70).flatMap { f.process(it.toIntArray()).toList() }.toDoubleArray()
        }
        assertEquals(whole.toList(), chunked.toList())
    }

    @Test
    fun resetForgetsThePast() {
        val ints = IntArray(300) { (1000 * sin(2 * PI * 7 * it / fs)).toInt() }
        val f = EcgFilters().detection
        val first = f.process(ints)
        f.reset()
        assertEquals(first.toList(), f.process(ints).toList())
    }

    @Test
    fun groupDelayMatchesDelayOfASineBurst() {
        // Group delay is the delay of an *envelope* (a pulse, like a QRS), not of the sine's phase.
        // So send a 10 Hz burst with a smooth bell-shaped envelope and compare where its energy sits.
        val hz = 10.0
        val x = DoubleArray(30 * 130) {
            val t = it / fs - 15.0
            1000 * kotlin.math.exp(-t * t / (2 * 0.4 * 0.4)) * sin(2 * PI * hz * t)
        }
        val f = EcgFilters().detection
        val y = run(f, x)
        fun centre(s: DoubleArray) = s.indices.sumOf { it * s[it] * s[it] } / s.sumOf { it * it }
        val measured = centre(y) - centre(x)
        assertEquals(f.groupDelaySamples(hz), measured, 0.3)
        assertTrue(measured in 1.0..8.0, "delay $measured samples")
    }

    @Test
    fun filtersAreStableOnLongNoisyInput() {
        val rnd = java.util.Random(1)
        val f = EcgFilters()
        var last = 0.0
        repeat(130 * 3600) { last = f.morphology.process(rnd.nextGaussian() * 500) + f.detection.process(rnd.nextGaussian() * 500) }
        assertTrue(abs(last) < 1e5)
    }

    @Test
    fun invalidDesignIsRejected() {
        assertFailsWith<IllegalArgumentException> { FilterCascade.butterworthLowPass(fs, 70.0, 2) } // above Nyquist
        assertFailsWith<IllegalArgumentException> { FilterCascade.butterworthLowPass(fs, 20.0, 3) } // odd order
        assertFailsWith<IllegalArgumentException> { FilterCascade.butterworthHighPass(fs, 0.0, 2) }
    }
}
