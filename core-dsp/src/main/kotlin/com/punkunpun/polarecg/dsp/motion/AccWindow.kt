package com.punkunpun.polarecg.dsp.motion

import com.punkunpun.polarecg.dsp.raw.RawRecord
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * The accelerometer next to the ECG in the beat window (owner's request, 2026-11-16): seeing whether the body or the strap moved
 * at the moment of an odd-looking beat is one of the best ways to tell a real V beat from a motion artifact.
 */

/**
 * Accelerometer samples of one window, on the same time line as the ECG and the beats. [utcMs] is ascending, one entry per sample;
 * the axes are in milli-g (gravity included, so a still strap reads about 1000 mG on the axis that points down).
 */
class AccWindow(val utcMs: LongArray, val xMg: IntArray, val yMg: IntArray, val zMg: IntArray) {
    init {
        require(xMg.size == utcMs.size && yMg.size == utcMs.size && zMg.size == utcMs.size) { "one value per axis and sample" }
    }

    val size: Int get() = utcMs.size

    /**
     * Movement per sample, in mG: how far the acceleration vector is from its average over the time around the sample
     * (± [halfWidthMs]). Gravity and a steady posture cancel out, so lying still gives a few mG (breathing, heartbeat, sensor
     * noise), while a bump of the strap, a turn in bed or a step shows as a peak at the moment it happens. It is the per-sample
     * version of the per-second value the signal quality uses ([MotionStream]: the RMS of the same deviation over one second).
     */
    fun movementMg(halfWidthMs: Long = 500): DoubleArray {
        val out = DoubleArray(size)
        // A window that slides along the samples: `hi` adds the samples that come into range, `lo` removes those that leave it,
        // so each sample costs a few additions instead of a loop over the whole second.
        var lo = 0
        var hi = 0
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in 0 until size) {
            while (hi < size && utcMs[hi] <= utcMs[i] + halfWidthMs) {
                sx += xMg[hi]; sy += yMg[hi]; sz += zMg[hi]
                hi++
            }
            while (utcMs[lo] < utcMs[i] - halfWidthMs) {
                sx -= xMg[lo]; sy -= yMg[lo]; sz -= zMg[lo]
                lo++
            }
            val n = (hi - lo).toDouble() // at least 1: sample i itself is always inside
            val dx = xMg[i] - sx / n
            val dy = yMg[i] - sy / n
            val dz = zMg[i] - sz / n
            out[i] = sqrt(dx * dx + dy * dy + dz * dz)
        }
        return out
    }
}

/** A frame whose samples land further than this from the phone time it arrived at is not from this stretch's clock: skipped. */
private const val MAX_FRAME_OFFSET_MS = 10_000L

/**
 * The ACC samples in `[fromUtcMs, toUtcMs]`, placed on the time line of an ECG window: the strap stamps both streams with the
 * same clock, so a sample at strap time `t` is at `anchorUtcMs + (t - anchorSensorNs) / 1e6` (the anchor comes from
 * `cutWindowAnchored`). This puts the ACC exactly where the ECG and the beat markers are, whatever the phone's clock did.
 * Null if no sample falls in the range.
 *
 * Inside a frame the sample spacing is taken from the strap's clock (the next frame's start time), not from the nominal rate:
 * the nominal 50 Hz need not be the real rate. [frames] in arrival order, as in the files.
 */
fun cutAccWindow(
    frames: List<RawRecord.Acc>,
    anchorUtcMs: Long,
    anchorSensorNs: Long,
    fromUtcMs: Long,
    toUtcMs: Long,
    rateHz: Int = ACC_SAMPLE_RATE_HZ,
): AccWindow? {
    val nominalNs = 1e9 / rateHz
    val t = ArrayList<Long>()
    val x = ArrayList<Int>()
    val y = ArrayList<Int>()
    val z = ArrayList<Int>()
    fun utcOf(sensorNs: Double) = anchorUtcMs + Math.round((sensorNs - anchorSensorNs) / 1e6)

    for ((k, f) in frames.withIndex()) {
        val n = f.xMg.size
        if (n == 0) continue
        // spacing from the next frame if it follows this one (a plausible spacing), else the nominal one
        val next = frames.getOrNull(k + 1)
        val measured = next?.let { (it.sensorTimeNs - f.sensorTimeNs).toDouble() / n }
        val periodNs = if (measured != null && measured in nominalNs * 0.8..nominalNs * 1.25) measured else nominalNs
        val firstUtc = utcOf(f.sensorTimeNs.toDouble())
        val lastUtc = utcOf(f.sensorTimeNs + (n - 1) * periodNs)
        if (lastUtc < fromUtcMs - 2_000 || firstUtc > toUtcMs + 2_000) continue // far from the window: not needed
        if (abs(lastUtc - f.phoneTimeMs) > MAX_FRAME_OFFSET_MS) continue // another clock (the strap was reset): cannot be placed
        for (i in 0 until n) {
            val u = utcOf(f.sensorTimeNs + i * periodNs)
            if (u < fromUtcMs || u > toUtcMs) continue
            t += u; x += f.xMg[i]; y += f.yMg[i]; z += f.zMg[i]
        }
    }
    if (t.isEmpty()) return null
    val order = t.indices.sortedBy { t[it] } // frames arrive in order, but a resumed session may repeat or reorder some
    return AccWindow(
        LongArray(order.size) { t[order[it]] },
        IntArray(order.size) { x[order[it]] },
        IntArray(order.size) { y[order[it]] },
        IntArray(order.size) { z[order[it]] },
    )
}
