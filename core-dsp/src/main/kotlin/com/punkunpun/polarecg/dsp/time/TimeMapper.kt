package com.punkunpun.polarecg.dsp.time

/*
 * Maps the H10's own clock to phone UTC (pitfall 7 in the plan).
 *
 * Every frame gives us a pair: when the sensor says its last sample was taken, and when the phone
 * received the frame. Their difference is the "offset". The offset is not constant:
 *  - the phone receives the frame a little AFTER it was measured (BLE batching, scheduling), so
 *    each single offset is too big by an unknown, never negative, delay;
 *  - the two clocks drift apart slowly (tens of ppm, i.e. a few ms per minute).
 * So the best estimate of the true offset is the SMALLEST offset seen recently: that is the
 * frame that was delayed least. The window is short enough to follow drift.
 *
 * One mapper instance is used for the whole session; call [startConnection] after every
 * reconnect, because the sensor clock may be different after a new connection.
 *
 * Pure Kotlin, no Android: the PC replays recordings through the same class.
 */

/** Sensor timestamps count nanoseconds from 2000-01-01 00:00 UTC; this is that moment in Unix ms. */
const val POLAR_EPOCH_UNIX_MS = 946_684_800_000L

/** The offset between the two clocks moved suddenly by [deltaMs] (positive = phone-sensor grew). */
data class ClockJump(val connection: Int, val phoneTimeMs: Long, val deltaMs: Long)

/** The clock relation a previous run ended with: phone UTC [phoneMs] at which the offset (phone - sensor, ms) was [offsetMs]. */
data class ClockSeed(val phoneMs: Long, val offsetMs: Long)

/**
 * How fast the offset (phone - sensor) changes, ms per ms: -25 ppm, i.e. the strap's clock gains about 90 ms an hour on the phone's.
 * Measured on two long connections (-25.1 ppm both; the first hour of a connection -30 ppm, the strap warming up).
 */
const val STRAP_DRIFT_MS_PER_MS = -25e-6

/** A seed older than this is not used: the drift is only known to a few ppm, which is 10 ms an hour, but not to a few ten ms. */
const val SEED_MAX_AGE_MS = 3_600_000L

/**
 * The seed from the last row of `timemap.csv` ([lines] include the header: `connection,phoneUtcMs,sensorNs,offsetMs,kind`), moved on
 * to [nowMs] along the drift ([STRAP_DRIFT_MS_PER_MS]); null if there is no row, it is damaged, or older than [maxAgeMs].
 * The seed is dated [nowMs] so that it stays in the mapper's window next to the first frames of the new run.
 * (Assumes the strap kept its clock between the runs; if not, the mapper catches the difference as a jump.)
 */
fun clockSeedFrom(lines: List<String>, nowMs: Long, maxAgeMs: Long = SEED_MAX_AGE_MS): ClockSeed? {
    val f = lines.asReversed().firstOrNull { it.isNotBlank() }?.split(',') ?: return null
    val phoneMs = f.getOrNull(1)?.toLongOrNull() ?: return null
    val offsetMs = f.getOrNull(3)?.toLongOrNull() ?: return null
    val age = nowMs - phoneMs
    if (age !in 0..maxAgeMs) return null
    return ClockSeed(nowMs, offsetMs + Math.round(age * STRAP_DRIFT_MS_PER_MS))
}

class TimeMapper(
    /** Offset changes bigger than this are reported as jumps. Normal jitter is a few hundred ms. */
    private val jumpThresholdMs: Long = 1_000,
    /** How much history the minimum is taken over. */
    private val windowMs: Long = 60_000,
    /** How many consecutive frames must agree before an UPWARD change counts as a jump. */
    private val confirmFrames: Int = 5,
) {
    private class Sample(val phoneMs: Long, val offsetMs: Long)

    private val window = ArrayDeque<Sample>()
    private var pendingUp = 0
    private var estimate: Long? = null

    /** Number of the current connection, starting at 0 for the first [startConnection]. */
    @get:Synchronized
    var connection: Int = -1
        private set

    /** Forget the previous clock relation. Call when the stream (re)starts. */
    @Synchronized
    fun startConnection() {
        connection++
        window.clear()
        pendingUp = 0
        estimate = null
    }

    /**
     * Feed one pair: [sensorNs] is the sensor clock at the END of the frame (time of its last
     * sample), [phoneMs] the phone UTC ms when the frame arrived. Returns a [ClockJump] if the
     * relation between the clocks just changed abruptly, otherwise null.
     */
    @Synchronized
    fun observe(sensorNs: Long, phoneMs: Long): ClockJump? {
        if (connection < 0) startConnection()
        val offset = phoneMs - (sensorNs / 1_000_000 + POLAR_EPOCH_UNIX_MS)
        val current = estimate
        if (current == null) {
            accept(phoneMs, offset)
            return null
        }
        val deviation = offset - current
        return when {
            // Latency can only make the offset bigger, so a drop is never "just jitter":
            // either the phone clock stepped back or the sensor clock jumped ahead.
            deviation < -jumpThresholdMs -> jump(phoneMs, offset, deviation)
            // A rise may be a delayed burst of frames; believe it only if it persists.
            deviation > jumpThresholdMs -> {
                pendingUp++
                if (pendingUp >= confirmFrames) jump(phoneMs, offset, deviation) else null
            }
            else -> {
                pendingUp = 0
                accept(phoneMs, offset)
                null
            }
        }
    }

    /**
     * Re-anchor after the app was killed and restarted while the strap kept running: start from the offset the previous run had
     * ([ClockSeed]) instead of from the first frame, which arrives late by an unknown delay (52 ms on the night of 2026-11-08, and the whole
     * stretch inherited it). A whole-recording replay never loses its state, so this makes live times equal those of `analyze`. The seed is
     * an ordinary sample of the window: a real clock change is still caught as a jump, and it expires after [windowMs].
     */
    @Synchronized
    fun seed(seed: ClockSeed) {
        if (connection < 0) startConnection()
        if (estimate == null) accept(seed.phoneMs, seed.offsetMs)
    }

    /** Converts a sensor timestamp to phone UTC ms, or null before the first [observe]. */
    @Synchronized
    fun toUtcMs(sensorNs: Long): Long? =
        estimate?.let { sensorNs / 1_000_000 + POLAR_EPOCH_UNIX_MS + it }

    /** Current best estimate of (phone UTC - sensor UTC) in ms, or null before the first [observe]. */
    @Synchronized
    fun offsetMs(): Long? = estimate

    private fun accept(phoneMs: Long, offset: Long) {
        window.addLast(Sample(phoneMs, offset))
        while (window.first().phoneMs < phoneMs - windowMs) window.removeFirst()
        estimate = window.minOf { it.offsetMs }
    }

    private fun jump(phoneMs: Long, offset: Long, deviation: Long): ClockJump {
        window.clear()
        pendingUp = 0
        accept(phoneMs, offset)
        return ClockJump(connection, phoneMs, deviation)
    }
}

/** UTC ms of sample [index] in a frame whose first sample is at [firstSampleUtcMs]. */
fun sampleUtcMs(firstSampleUtcMs: Long, index: Int, rateHz: Int): Long =
    firstSampleUtcMs + index * 1000L / rateHz
