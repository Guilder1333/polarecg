package com.punkunpun.polarisecg.polar

/*
 * Typed data frames coming out of PolarConnection. They are plain Kotlin classes with no Polar SDK
 * types in them, so the rest of the app (recorder, DSP) does not depend on the SDK.
 *
 * Two clocks are stored side by side on purpose (see pitfall 7 in the plan):
 *  - sensorTimeNs: the H10's own clock, in nanoseconds since 2000-01-01 00:00 UTC (Polar epoch).
 *  - phoneTimeMs:  when the phone received the frame, UTC milliseconds since 1970 (System.currentTimeMillis()).
 * Task P1.8 maps one to the other.
 */

/**
 * One batch of ECG samples (~70 samples, ~0.5 s at 130 Hz).
 * [sensorTimeNs] is the sensor timestamp of the FIRST sample; the rest follow at 1/130 s each.
 * [samplesUv] are microvolts.
 */
class EcgFrame(
    val sensorTimeNs: Long,
    val phoneTimeMs: Long,
    val samplesUv: IntArray,
) {
    override fun toString() = "EcgFrame(sensorTimeNs=$sensorTimeNs, n=${samplesUv.size})"
}

/**
 * One batch of accelerometer samples. Axes are separate arrays of equal length, in milli-g.
 * [sensorTimeNs] is the sensor timestamp of the first sample.
 */
class AccFrame(
    val sensorTimeNs: Long,
    val phoneTimeMs: Long,
    val xMg: IntArray,
    val yMg: IntArray,
    val zMg: IntArray,
) {
    val size: Int get() = xMg.size
    override fun toString() = "AccFrame(sensorTimeNs=$sensorTimeNs, n=$size)"
}

/**
 * One heart-rate notification (about one per second) from the standard BLE Heart Rate service.
 * [rrMs] holds the beat-to-beat intervals that finished since the previous notification, already
 * converted from 1/1024 s to milliseconds. It can be empty. These have no per-beat timestamps.
 */
class HrSample(
    val phoneTimeMs: Long,
    val heartRateBpm: Int,
    val rrMs: IntArray,
    /** Electrode skin contact as reported by the strap, or null if the strap does not report it. */
    val contactDetected: Boolean?,
)

/** Where the link to the strap currently is. */
sealed interface ConnectionState {
    /** Not connected. [reason] is why, if we know (null before the first connect or on user request). */
    data class Disconnected(val reason: String? = null) : ConnectionState

    /** Connecting (first time or a retry); PolarConnection retries with back-off until disconnect(). */
    data object Connecting : ConnectionState

    /** BLE link is up, streams are being started. */
    data object Connected : ConnectionState

    /** ECG, ACC and HR streams are running. */
    data object Streaming : ConnectionState
}
