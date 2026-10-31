package com.punkunpun.polarisecg.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The long-term rows (see [MinuteEntity], [HrvEntity]). */
@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinutes(rows: List<MinuteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHrv(rows: List<HrvEntity>)

    @Query("SELECT * FROM minute_summary WHERE minuteUtcMs = :minuteUtcMs")
    suspend fun minuteAt(minuteUtcMs: Long): MinuteEntity?

    @Query("SELECT * FROM hrv_window WHERE startUtcMs = :startUtcMs")
    suspend fun hrvAt(startUtcMs: Long): HrvEntity?

    @Query("SELECT * FROM minute_summary WHERE minuteUtcMs >= :fromUtcMs AND minuteUtcMs < :toUtcMs ORDER BY minuteUtcMs")
    suspend fun minutesBetween(fromUtcMs: Long, toUtcMs: Long): List<MinuteEntity>

    @Query("SELECT * FROM hrv_window WHERE startUtcMs >= :fromUtcMs AND startUtcMs < :toUtcMs ORDER BY startUtcMs")
    suspend fun hrvBetween(fromUtcMs: Long, toUtcMs: Long): List<HrvEntity>

    /** Redraws the summary screen when new rows arrive. */
    @Query("SELECT COUNT(*) FROM minute_summary")
    fun observeMinuteCount(): Flow<Int>
}
