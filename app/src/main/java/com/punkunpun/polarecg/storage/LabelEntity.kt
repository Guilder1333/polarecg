package com.punkunpun.polarecg.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.punkunpun.polarecg.dsp.annotations.LabelRecord

/**
 * One row of the `label` table (plan §8, P5.7): what the owner says a beat is. **Append-only history**: a changed mind or a
 * corrected mistake adds a new row, nothing is overwritten or deleted, and the current label of a beat is its newest row
 * (highest [id], see [LabelDao.currentBetween]). The automatic class in `beat.cls` is never changed by a label.
 *
 * [beatUtcMs] is the beat's time, the same number as `beat.utcMs`, but there is deliberately **no foreign key** to the `beat`
 * table: beats are deleted after 90 days (retention) and labels must stay "forever".
 *
 * [label] is one of `OwnerLabel.CHOICES` of `:core-dsp` ("N", "S", "V", "Q", "?" = unsure, "" = undone).
 * `autoGenerate = true` numbers the rows 1, 2, 3...; the order of the ids is the order of writing, which is why "newest" is
 * decided by [id] and not by [createdAtUtcMs] (the phone clock can be changed).
 */
@Entity(tableName = "label", indices = [Index("beatUtcMs")])
data class LabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val beatUtcMs: Long,
    val label: String,
    val note: String,
    val createdAtUtcMs: Long,
)

fun LabelEntity.toRecord() = LabelRecord(beatUtcMs, label, note, createdAtUtcMs)
