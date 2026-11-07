package com.punkunpun.polarecg.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The queries on the `event` table (see [EventEntity]). */
@Dao
interface EventDao {
    /** Returns the new row's id (needed for the clip file name). */
    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("UPDATE event SET clipFile = :clipFile WHERE id = :id")
    suspend fun setClipFile(id: Long, clipFile: String)

    @Query("SELECT * FROM event WHERE startUtcMs >= :fromUtcMs AND startUtcMs < :toUtcMs ORDER BY startUtcMs")
    suspend fun between(fromUtcMs: Long, toUtcMs: Long): List<EventEntity>

    /** Blocking, for the export, which already runs off the UI thread. */
    @Query("SELECT * FROM event WHERE startUtcMs >= :fromUtcMs AND startUtcMs < :toUtcMs ORDER BY startUtcMs")
    fun eventsBetween(fromUtcMs: Long, toUtcMs: Long): List<EventEntity>

    /** The newest events first, for the event list; the list updates when a row is added. */
    @Query("SELECT * FROM event ORDER BY startUtcMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun byId(id: Long): EventEntity?

    @Query("SELECT COUNT(*) FROM event WHERE startUtcMs >= :fromUtcMs")
    fun observeCountSince(fromUtcMs: Long): Flow<Int>
}
