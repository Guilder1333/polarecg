package com.punkunpun.polarecg.dsp.summary

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Round values for the grid lines of a chart that shows [lo]..[hi]: a step of 1, 2 or 5 times a power of ten, with at most about
 * [maxTicks] lines, from the first multiple of the step at or above [lo] to the last at or below [hi]. Empty if the range is empty.
 */
fun niceTicks(lo: Double, hi: Double, maxTicks: Int = 5): List<Double> {
    if (!(hi > lo) || maxTicks < 2) return emptyList()
    val rough = (hi - lo) / (maxTicks - 1)
    val magnitude = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= rough - 1e-12 }
    val first = ceil(lo / step - 1e-9)
    val last = floor(hi / step + 1e-9)
    return (first.toLong()..last.toLong()).map { it * step }
}
