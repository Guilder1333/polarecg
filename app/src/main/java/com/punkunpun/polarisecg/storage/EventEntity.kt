package com.punkunpun.polarisecg.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.punkunpun.polarisecg.dsp.event.RecordedEvent

/**
 * One row of the `event` table (plan §8): a couplet, run, bigeminy, pause... that the live pipeline found, with the name of
 * its ECG clip (a file in `files/clips/`) if one was saved. Kept "forever"; the clip file may be pruned later, then [clipFile]
 * stays and the file is simply missing.
 *
 * `autoGenerate = true` lets SQLite number the rows (1, 2, 3...); pass 0 and it picks the next one. The `Index` makes
 * "events between two times" (the event list, the summary) fast without reading the whole table.
 */
@Entity(tableName = "event", indices = [Index("startUtcMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startUtcMs: Long,
    val endUtcMs: Long,
    /** `PatternType` of `:core-dsp`: V_COUPLET, S_RUN, V_BIGEMINY, PAUSE... */
    val type: String,
    val beatCount: Int,
    /** Text as in `events.csv` of `analyze` ("4 beats, 118 bpm", "2 cycles, ...", "... 2 questionable"). */
    val detail: String,
    /** File name inside `files/clips/`, null if no clip was saved (rate limit, or the ECG was gone). */
    val clipFile: String?,
)

fun RecordedEvent.toEntity() = EventEntity(
    startUtcMs = event.utcMs,
    endUtcMs = endUtcMs,
    type = event.type,
    beatCount = beatCount,
    detail = event.detail,
    clipFile = null, // set after the file is written, see EventWriter
)
