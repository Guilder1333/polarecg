package com.punkunpun.polarisecg.dsp.motion

import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.TimeMapper
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.sqrt

/** Sample rate of the accelerometer stream the app requests (see `session.json`, `acc.rateHz`). */
const val ACC_SAMPLE_RATE_HZ = 50

/** Length of one motion bin. */
const val MOTION_BIN_MS = 1_000L

/**
 * How much the body moved during one UTC second.
 * [motionMg] is the RMS deviation of the acceleration vector from its average over the second,
 * in milli-g: lying still it is a few mG (breathing, heartbeat, sensor noise), walking is
 * hundreds. A change of posture shows up too, because gravity then swings through the axes.
 * [samples] is how many ACC samples the second had (50 when complete).
 */
class MotionBin(val startUtcMs: Long, val motionMg: Double, val samples: Int)

/** Bins with fewer samples than this are not trusted (a gap covers most of the second). */
private const val MIN_SAMPLES_PER_BIN = ACC_SAMPLE_RATE_HZ / 2

/** Frame start times may deviate this much from "exactly after the previous frame" (ms). */
private const val CONTIGUOUS_TOLERANCE_MS = 25L

/**
 * Cuts the ACC frames into gap-free runs, puts every sample on the UTC time line with a
 * [TimeMapper] (same method as for the ECG, so both streams agree to a few ms), and returns one
 * [MotionBin] per UTC second that has enough samples, sorted by time.
 */
fun buildMotionBins(frames: List<RawRecord.Acc>, rateHz: Int = ACC_SAMPLE_RATE_HZ): List<MotionBin> {
    class Sums {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var qx = 0.0; var qy = 0.0; var qz = 0.0 // sums of squares
    }

    val bins = TreeMap<Long, Sums>()
    val mapper = TimeMapper()
    var runStartUtc = 0L
    var runIndex = 0 // samples since the start of the current gap-free run
    var expectedNextNs: Long? = null
    var lastSensorNs = Long.MIN_VALUE

    for (f in frames) {
        val n = f.xMg.size
        if (n == 0) continue
        if (lastSensorNs != Long.MIN_VALUE && f.sensorTimeNs < lastSensorNs - 1_000_000_000L) mapper.startConnection()
        lastSensorNs = f.sensorTimeNs
        mapper.observe(f.sensorTimeNs + (n - 1) * 1_000_000_000L / rateHz, f.phoneTimeMs)

        val expected = expectedNextNs
        val contiguous = expected != null && abs(f.sensorTimeNs - expected) <= CONTIGUOUS_TOLERANCE_MS * 1_000_000L
        if (!contiguous) {
            runStartUtc = mapper.toUtcMs(f.sensorTimeNs) ?: f.phoneTimeMs
            runIndex = 0
        }
        for (i in 0 until n) {
            val utc = runStartUtc + (runIndex + i) * 1000L / rateHz
            val s = bins.getOrPut(Math.floorDiv(utc, MOTION_BIN_MS)) { Sums() }
            val x = f.xMg[i].toDouble(); val y = f.yMg[i].toDouble(); val z = f.zMg[i].toDouble()
            s.n++
            s.sx += x; s.sy += y; s.sz += z
            s.qx += x * x; s.qy += y * y; s.qz += z * z
        }
        runIndex += n
        expectedNextNs = f.sensorTimeNs + n * 1_000_000_000L / rateHz
    }

    return bins.mapNotNull { (bin, s) ->
        if (s.n < MIN_SAMPLES_PER_BIN) return@mapNotNull null
        // variance of each axis = mean of squares - square of mean; the vector's RMS deviation is the root of their sum
        val v = (s.qx / s.n - (s.sx / s.n) * (s.sx / s.n)) +
            (s.qy / s.n - (s.sy / s.n) * (s.sy / s.n)) +
            (s.qz / s.n - (s.sz / s.n) * (s.sz / s.n))
        MotionBin(bin * MOTION_BIN_MS, sqrt(maxOf(v, 0.0)), s.n)
    }
}

/**
 * Looks up the motion around a moment. Built once from [buildMotionBins]; [around] returns the
 * highest motion of the seconds that overlap `[utcMs - halfWidthMs, utcMs + halfWidthMs]`, or null
 * if the accelerometer has no data there.
 */
class MotionIndex(bins: List<MotionBin>) {
    private val byBin = HashMap<Long, Double>(bins.size * 2).also { m ->
        for (b in bins) m[b.startUtcMs / MOTION_BIN_MS] = b.motionMg
    }

    val isEmpty: Boolean get() = byBin.isEmpty()

    fun around(utcMs: Long, halfWidthMs: Long = 500): Double? {
        var best: Double? = null
        for (bin in Math.floorDiv(utcMs - halfWidthMs, MOTION_BIN_MS)..Math.floorDiv(utcMs + halfWidthMs, MOTION_BIN_MS)) {
            val v = byBin[bin] ?: continue
            if (best == null || v > best) best = v
        }
        return best
    }
}
