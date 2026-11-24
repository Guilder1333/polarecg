package com.punkunpun.polarecg.dsp.workout

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0

/** Great-circle distance in metres between two points given in degrees (haversine formula). */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/**
 * Adds up the distance of a ride from GPS fixes (no map: only the length). Straight lines between accepted fixes, so
 * the result is never longer than the real path.
 *
 * Three filters keep GPS noise out of the sum:
 *  - a fix that says it is less accurate than [maxAccuracyM] is ignored;
 *  - a fix that would mean going faster than [maxSpeedMps] since the last accepted one is a jump and is ignored;
 *  - a step shorter than [minStepM] (or than half of the two fixes' accuracy) is standing-still jitter: the fix is
 *    ignored and the anchor stays, so real slow movement still adds up once it is far enough away.
 *
 * Pure Kotlin on purpose, so it is tested on the PC; the phone feeds it from `LocationManager`.
 */
class DistanceTracker(
    private val maxAccuracyM: Double = 25.0,
    private val maxSpeedMps: Double = 30.0, // 108 km/h: faster than any bicycle, so only a GPS jump
    private val minStepM: Double = 3.0,
) {
    var distanceM = 0.0
        private set

    /** Fixes that were counted as movement. */
    var acceptedFixes = 0
        private set

    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorAccuracyM = 0.0
    private var anchorTimeMs = 0L
    private var hasAnchor = false

    /**
     * Feeds one fix. [timeMs] must only grow (use the phone's elapsed-realtime clock, not the wall clock, which can be
     * changed). [accuracyM] null = the phone gave none. Returns true if the fix moved the distance.
     */
    fun add(latDeg: Double, lonDeg: Double, accuracyM: Double?, timeMs: Long): Boolean {
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val acc = accuracyM ?: 0.0
        if (!hasAnchor) {
            anchorLat = latDeg; anchorLon = lonDeg; anchorAccuracyM = acc; anchorTimeMs = timeMs; hasAnchor = true
            return false
        }
        val step = haversineM(anchorLat, anchorLon, latDeg, lonDeg)
        val dtS = max(1L, timeMs - anchorTimeMs) / 1000.0
        if (step / dtS > maxSpeedMps) return false
        if (step < max(minStepM, (acc + anchorAccuracyM) / 2 / 2)) return false
        distanceM += step
        acceptedFixes++
        anchorLat = latDeg; anchorLon = lonDeg; anchorAccuracyM = acc; anchorTimeMs = timeMs
        return true
    }
}

/** Running mean and maximum of the heart rate during a workout. */
class HrStats {
    private var sum = 0L
    var count = 0
        private set
    var max = 0
        private set

    fun add(bpm: Int) {
        if (bpm <= 0) return // 0 = no reading
        sum += bpm
        count++
        if (bpm > max) max = bpm
    }

    /** Mean in bpm, or null before the first reading. */
    val mean: Double? get() = if (count == 0) null else sum.toDouble() / count
}
