package com.punkunpun.polarisecg.storage

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.punkunpun.polarisecg.dsp.analysis.Beat

/**
 * One row of the `beat` table (plan §8): what the pipeline decided about a beat, plus the few features
 * the review screen and the tuning need. The raw ECG stays in the `.bin` files.
 *
 * `@Entity` tells Room "make a table of this class"; each constructor property becomes a column.
 * The time is the key: the strap gives one beat at a time, and if a resumed session writes the same beat
 * twice, [BeatDao.insertAll] replaces the old row instead of failing.
 */
@Entity(tableName = "beat")
data class BeatEntity(
    @PrimaryKey val utcMs: Long,
    val segment: Int,
    val rrMs: Long?,
    /** N / S / V / Q, as in `Beat.cls`. */
    val cls: String,
    val good: Boolean,
    val rescued: Boolean,
    val vDoubt: Boolean,
    val sqi: Double,
    val motionMg: Double?,
    val prematurity: Double?,
    val corr: Double?,
    val qrsWidthMs: Double?,
    val driftRatio: Double?,
)

fun Beat.toEntity() = BeatEntity(
    utcMs = utcMs,
    segment = segment,
    rrMs = rrMs,
    cls = cls,
    good = good,
    rescued = rescued,
    vDoubt = vDoubt,
    sqi = quality.sqi,
    motionMg = quality.motionMg,
    prematurity = features?.prematurity,
    corr = features?.corr,
    qrsWidthMs = features?.qrsWidthMs,
    driftRatio = features?.driftRatio,
)
