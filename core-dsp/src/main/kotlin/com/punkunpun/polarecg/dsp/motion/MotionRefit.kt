package com.punkunpun.polarecg.dsp.motion

import com.punkunpun.polarecg.dsp.quality.QualityConfig
import com.punkunpun.polarecg.dsp.quality.ramp

/*
 * Repair for beats saved while MotionStream timed the ACC samples at the nominal 50 Hz (the strap really runs at ~50.9 Hz, so
 * the per-second motion of a beat came from a second that drifted away from it). The raw ACC files are intact, so the motion of
 * every beat can be worked out again; this file is the arithmetic, the app applies it to the database.
 */

/** The motion-dependent columns of a saved beat after the repair. */
class MotionRefit(val motionMg: Double, val sqi: Double, val good: Boolean)

/**
 * New motion columns for a beat saved with [oldSqi] / [oldMotionMg], now that its motion is [newMotionMg].
 *
 * The saved `sqi` is the ECG-only score times the motion score (`BeatQuality.withMotion`), so the ECG-only part is
 * `oldSqi / oldScore` and the new `sqi` is that times the score of the new motion. Not recoverable when the old score was
 * (almost) 0, because the product lost the ECG part: then `sqi` and `good` stay as they were and only the motion is replaced.
 * The class of the beat is not touched: it was decided with the old motion, and re-deciding it would also change labelled beats.
 * Returns null when there is no new motion to apply (no ACC data around the beat).
 */
fun refitMotion(
    oldSqi: Double,
    oldMotionMg: Double?,
    oldGood: Boolean,
    newMotionMg: Double?,
    config: QualityConfig = QualityConfig(),
): MotionRefit? {
    if (newMotionMg == null) return null
    val oldScore = if (oldMotionMg == null) 1.0 else ramp(oldMotionMg, config.motionOkMg, config.motionBadMg) // no motion = not penalised
    if (oldScore < MIN_RECOVERABLE_SCORE) return MotionRefit(newMotionMg, oldSqi, oldGood)
    val sqi = (oldSqi / oldScore).coerceAtMost(1.0) * ramp(newMotionMg, config.motionOkMg, config.motionBadMg)
    return MotionRefit(newMotionMg, sqi, sqi >= config.goodBeatSqi)
}

private const val MIN_RECOVERABLE_SCORE = 0.05
