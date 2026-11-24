package com.punkunpun.polarecg.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the `workout` table in the separate workouts database ([WorkoutDatabase], file `workouts.db`).
 *
 * The columns are only what every workout has: when it happened ([startUtcMs], [endUtcMs]) and what kind it was ([type],
 * e.g. "bicycle"). Everything else lives in [data], **free-form JSON text**, so a new kind of workout or a new figure
 * (distance, mean heart rate, later perhaps cadence or a note) needs no schema change and no migration.
 * Keys written so far for a bicycle ride: `distanceM`, `avgHr`, `maxHr`, `gps` (false = no location permission),
 * `actionMarked` (the Bicycle/Walking marks reached the session's annotations), `updatedUtcMs`.
 *
 * [endUtcMs] is null while the workout runs and stays null if the app was killed during it; [data] then still holds the
 * figures of the last save (`updatedUtcMs` is the last time the row was written).
 */
@Entity(tableName = "workout", indices = [Index("startUtcMs")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startUtcMs: Long,
    val endUtcMs: Long?,
    val type: String,
    val data: String,
)
