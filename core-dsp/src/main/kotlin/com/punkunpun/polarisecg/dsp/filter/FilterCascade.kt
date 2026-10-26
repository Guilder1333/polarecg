package com.punkunpun.polarisecg.dsp.filter

import kotlin.math.PI
import kotlin.math.sin

/**
 * Several [Biquad] sections one after another: together they make a higher-order filter
 * (a 4th-order filter = two sections). Streaming: call [process] with each new sample, or with
 * each incoming ECG frame; the result does not depend on how the stream is cut into frames.
 */
class FilterCascade(private val sections: List<Biquad>, val sampleRateHz: Double) {
    private var primed = false

    /** One sample through all sections. The very first sample first [Biquad.settle]s the filter. */
    fun process(x: Double): Double {
        var v = x
        if (!primed) {
            primed = true
            for (s in sections) v = s.settle(v)
            return v
        }
        for (s in sections) v = s.process(v)
        return v
    }

    /** A whole frame (µV as received from the sensor); returns the filtered samples. */
    fun process(samples: IntArray): DoubleArray = DoubleArray(samples.size) { process(samples[it].toDouble()) }

    /** Forget the past, e.g. after a gap in the data. The next sample settles the filter again. */
    fun reset() {
        primed = false
        sections.forEach { it.reset() }
    }

    /** Gain (output amplitude / input amplitude) for a sine of [freqHz]. */
    fun gainAt(freqHz: Double): Double = response(2 * PI * freqHz / sampleRateHz).abs()

    /**
     * Delay in samples a sine of [freqHz] gets from this filter ("group delay"). Detected beat
     * times from a filtered signal are late by about this much and must be corrected.
     * Multiply by 1000 / sampleRateHz for milliseconds.
     */
    fun groupDelaySamples(freqHz: Double): Double {
        val w = 2 * PI * freqHz / sampleRateHz
        val dw = 1e-4
        // phase change over a tiny frequency step = arg(H(w+dw) / H(w)); no unwrapping needed
        val dPhase = (response(w + dw) * response(w).conj()).arg()
        return -dPhase / dw
    }

    private fun response(w: Double): Complex = sections.fold(Complex(1.0, 0.0)) { acc, s -> acc * s.response(w) }

    companion object {
        /** Butterworth low-pass of the given even [order] (flattest passband). */
        fun butterworthLowPass(sampleRateHz: Double, cutoffHz: Double, order: Int): FilterCascade =
            FilterCascade(butterworthQs(order).map { Biquad.lowPass(sampleRateHz, cutoffHz, it) }, sampleRateHz)

        /** Butterworth high-pass of the given even [order]. */
        fun butterworthHighPass(sampleRateHz: Double, cutoffHz: Double, order: Int): FilterCascade =
            FilterCascade(butterworthQs(order).map { Biquad.highPass(sampleRateHz, cutoffHz, it) }, sampleRateHz)

        /** Filters joined in series: the output of each is the input of the next. */
        fun chain(first: FilterCascade, vararg more: FilterCascade): FilterCascade {
            val all = listOf(first) + more
            require(all.all { it.sampleRateHz == first.sampleRateHz }) { "sample rates differ" }
            return FilterCascade(all.flatMap { it.sections }, first.sampleRateHz)
        }

        /** Q of each 2nd-order section of a Butterworth filter: 1 / (2 sin((2k-1)π / 2n)). */
        private fun butterworthQs(order: Int): List<Double> {
            require(order >= 2 && order % 2 == 0) { "order must be even and at least 2" }
            return (1..order / 2).map { k -> 1.0 / (2 * sin((2 * k - 1) * PI / (2 * order))) }
        }
    }
}
