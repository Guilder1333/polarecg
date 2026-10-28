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
 * Motion per UTC second, built frame by frame. Cuts the ACC frames into gap-free runs, puts every
 * sample on the UTC time line with a [TimeMapper] (same method as for the ECG, so both streams agree
 * to a few ms) and sums them into bins of one UTC second. Not thread-safe.
 *
 * [retainMs] limits how far back bins are kept (a live pipeline needs only the last seconds); the
 * default keeps everything, for [buildMotionBins].
 */
class MotionStream(private val rateHz: Int = ACC_SAMPLE_RATE_HZ, private val retainMs: Long = Long.MAX_VALUE) {
    private class Sums {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var qx = 0.0; var qy = 0.0; var qz = 0.0 // sums of squares
    }

    private val bins = TreeMap<Long, Sums>()
    private val mapper = TimeMapper()
    private var runStartUtc = 0L
    private var runIndex = 0 // samples since the start of the current gap-free run
    private var expectedNextNs: Long? = null
    private var lastSensorNs = Long.MIN_VALUE

    /** UTC of the latest sample added; bins before the second it lies in are complete. Null before the first frame. */
    var watermarkUtcMs: Long? = null
        private set

    fun add(sensorTimeNs: Long, phoneTimeMs: Long, xMg: IntArray, yMg: IntArray, zMg: IntArray) {
        val n = xMg.size
        if (n == 0) return
        if (lastSensorNs != Long.MIN_VALUE && sensorTimeNs < lastSensorNs - 1_000_000_000L) mapper.startConnection()
        lastSensorNs = sensorTimeNs
        mapper.observe(sensorTimeNs + (n - 1) * 1_000_000_000L / rateHz, phoneTimeMs)

        val expected = expectedNextNs
        val contiguous = expected != null && abs(sensorTimeNs - expected) <= CONTIGUOUS_TOLERANCE_MS * 1_000_000L
        if (!contiguous) {
            runStartUtc = mapper.toUtcMs(sensorTimeNs) ?: phoneTimeMs
            runIndex = 0
        }
        for (i in 0 until n) {
            val utc = runStartUtc + (runIndex + i) * 1000L / rateHz
            val s = bins.getOrPut(Math.floorDiv(utc, MOTION_BIN_MS)) { Sums() }
            val x = xMg[i].toDouble(); val y = yMg[i].toDouble(); val z = zMg[i].toDouble()
            s.n++
            s.sx += x; s.sy += y; s.sz += z
            s.qx += x * x; s.qy += y * y; s.qz += z * z
        }
        watermarkUtcMs = runStartUtc + (runIndex + n - 1) * 1000L / rateHz
        runIndex += n
        expectedNextNs = sensorTimeNs + n * 1_000_000_000L / rateHz
        if (retainMs != Long.MAX_VALUE) bins.headMap(Math.floorDiv(watermarkUtcMs!! - retainMs, MOTION_BIN_MS)).clear()
    }

    /** Motion of one bin (a UTC second number), or null if it has too few samples. */
    private fun motionOf(bin: Long, s: Sums): Double? {
        if (s.n < MIN_SAMPLES_PER_BIN) return null
        // variance of each axis = mean of squares - square of mean; the vector's RMS deviation is the root of their sum
        val v = (s.qx / s.n - (s.sx / s.n) * (s.sx / s.n)) +
            (s.qy / s.n - (s.sy / s.n) * (s.sy / s.n)) +
            (s.qz / s.n - (s.sz / s.n) * (s.sz / s.n))
        return sqrt(maxOf(v, 0.0))
    }

    /** All bins with enough samples, sorted by time (those still kept, see [retainMs]). */
    fun bins(): List<MotionBin> = bins.mapNotNull { (bin, s) -> motionOf(bin, s)?.let { MotionBin(bin * MOTION_BIN_MS, it, s.n) } }

    /** Highest motion of the seconds that overlap `[utcMs - halfWidthMs, utcMs + halfWidthMs]`, or null without data there. */
    fun around(utcMs: Long, halfWidthMs: Long = 500): Double? {
        var best: Double? = null
        for (bin in Math.floorDiv(utcMs - halfWidthMs, MOTION_BIN_MS)..Math.floorDiv(utcMs + halfWidthMs, MOTION_BIN_MS)) {
            val v = bins[bin]?.let { motionOf(bin, it) } ?: continue
            if (best == null || v > best) best = v
        }
        return best
    }
}

/** See [MotionStream]: all bins of a whole recording. */
fun buildMotionBins(frames: List<RawRecord.Acc>, rateHz: Int = ACC_SAMPLE_RATE_HZ): List<MotionBin> {
    val stream = MotionStream(rateHz)
    for (f in frames) stream.add(f.sensorTimeNs, f.phoneTimeMs, f.xMg, f.yMg, f.zMg)
    return stream.bins()
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
