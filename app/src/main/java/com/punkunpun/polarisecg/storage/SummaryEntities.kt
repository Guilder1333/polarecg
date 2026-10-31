package com.punkunpun.polarisecg.storage

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.punkunpun.polarisecg.dsp.hrv.HrvWindow
import com.punkunpun.polarisecg.dsp.summary.MinuteSummary

/**
 * One UTC minute (plan §8 `minute_summary`). Kept "forever": the beats may be pruned after 90 days, these rows are what
 * the summary screen reads for the long view. Same numbers as `minutes.csv` of `analyze`.
 */
@Entity(tableName = "minute_summary")
data class MinuteEntity(
    @PrimaryKey val minuteUtcMs: Long,
    val beats: Int,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
    val vDoubt: Int,
    val hrMeanBpm: Double?,
    val hrMinBpm: Double?,
    val hrMaxBpm: Double?,
    val coverage: Double,
    val motionMg: Double?,
)

/** One 5-minute HRV window (plan §8 `hrv_window`), same numbers as `hrv.csv`. */
@Entity(tableName = "hrv_window")
data class HrvEntity(
    @PrimaryKey val startUtcMs: Long,
    val nn: Int,
    val coverage: Double,
    val valid: Boolean,
    val meanHrBpm: Double?,
    val sdnnMs: Double?,
    val rmssdMs: Double?,
    val pnn50Percent: Double?,
    val lfMs2: Double?,
    val hfMs2: Double?,
    val lfHf: Double?,
)

fun MinuteSummary.toEntity() = MinuteEntity(
    startUtcMs, beats, n, s, v, q, vDoubt, hrMeanBpm, hrMinBpm, hrMaxBpm, coverage, motionMg,
)

fun HrvWindow.toEntity() = HrvEntity(
    startUtcMs, nn, coverage, valid, time.meanHrBpm, time.sdnnMs, time.rmssdMs, time.pnn50Percent,
    spectrum?.lfMs2, spectrum?.hfMs2, spectrum?.lfHf,
)
