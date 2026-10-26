package com.punkunpun.polarisecg.dsp.filter

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR filter section ("biquad"), processing one sample at a time.
 *
 * Difference equation: `y = b0*x + z1`, then `z1 = b1*x - a1*y + z2`, `z2 = b2*x - a2*y`
 * (the "transposed direct form II": two numbers of memory, `z1` and `z2`, carry the past).
 * Coefficients are already divided by `a0`. This class keeps state, so use one instance per
 * signal and feed it the samples in order; it is not thread-safe.
 *
 * `class Biquad(private val b0: Double, ...)`: the values in the parentheses become fields
 * (a "primary constructor"); `private val` keeps them hidden from outside.
 */
class Biquad(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    /**
     * Sets the memory as if the input had been the constant [x] forever, and returns the output
     * that constant gives. Without this a filter starts from zero, and a signal with an offset
     * (ECG baseline) makes a large start-up spike.
     */
    fun settle(x: Double): Double {
        val y = x * (b0 + b1 + b2) / (1.0 + a1 + a2) // gain at 0 Hz
        z2 = b2 * x - a2 * y
        z1 = b1 * x - a1 * y + z2
        return y
    }

    /** Frequency response at angular frequency [w] (radians per sample): H(e^jw). */
    internal fun response(w: Double): Complex {
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        // z^-1 = e^-jw = cos w - j sin w, z^-2 = cos 2w - j sin 2w
        val num = Complex(b0 + b1 * c1 + b2 * c2, -(b1 * s1 + b2 * s2))
        val den = Complex(1.0 + a1 * c1 + a2 * c2, -(a1 * s1 + a2 * s2))
        return num / den
    }

    companion object { // like "static" members in Java: Biquad.lowPass(...) without an instance
        // Coefficient formulas from the "RBJ Audio EQ Cookbook" (bilinear transform of an
        // analog 2nd-order filter). q is the quality factor; q = 1/sqrt(2) is Butterworth (flattest).

        fun lowPass(sampleRateHz: Double, cutoffHz: Double, q: Double): Biquad {
            val (cosW, alpha) = prepare(sampleRateHz, cutoffHz, q)
            val a0 = 1 + alpha
            return Biquad(
                (1 - cosW) / 2 / a0, (1 - cosW) / a0, (1 - cosW) / 2 / a0,
                -2 * cosW / a0, (1 - alpha) / a0,
            )
        }

        fun highPass(sampleRateHz: Double, cutoffHz: Double, q: Double): Biquad {
            val (cosW, alpha) = prepare(sampleRateHz, cutoffHz, q)
            val a0 = 1 + alpha
            return Biquad(
                (1 + cosW) / 2 / a0, -(1 + cosW) / a0, (1 + cosW) / 2 / a0,
                -2 * cosW / a0, (1 - alpha) / a0,
            )
        }

        private fun prepare(sampleRateHz: Double, cutoffHz: Double, q: Double): Pair<Double, Double> {
            require(sampleRateHz > 0 && q > 0) { "sample rate and q must be positive" }
            require(cutoffHz > 0 && cutoffHz < sampleRateHz / 2) { "cutoff must be between 0 and Nyquist" }
            val w = 2 * PI * cutoffHz / sampleRateHz
            return Pair(cos(w), sin(w) / (2 * q))
        }
    }
}

/** Minimal complex number, only what the frequency-response code needs. */
internal data class Complex(val re: Double, val im: Double) {
    operator fun times(o: Complex) = Complex(re * o.re - im * o.im, re * o.im + im * o.re)
    operator fun div(o: Complex): Complex {
        val d = o.re * o.re + o.im * o.im
        return Complex((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
    }

    fun conj() = Complex(re, -im)
    fun abs() = sqrt(re * re + im * im)
    fun arg() = atan2(im, re)
}
