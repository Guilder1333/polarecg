package com.punkunpun.polarecg.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The queries on the `workout` table. Rows are never deleted by the app. */
@Dao
interface WorkoutDao {
    /** Adds a workout and returns its id. */
    @Insert
    suspend fun insert(workout: WorkoutEntity): Long

    /** Saves the figures of a running workout (it stays open: `endUtcMs` is not touched). */
    @Query("UPDATE workout SET data = :data WHERE id = :id")
    suspend fun updateData(id: Long, data: String)

    /** Closes a workout: its end time and final figures. */
    @Query("UPDATE workout SET endUtcMs = :endUtcMs, data = :data WHERE id = :id")
    suspend fun finish(id: Long, endUtcMs: Long, data: String)

    /** The newest workouts first; the list on the bicycle screen updates by itself. */
    @Query("SELECT * FROM workout ORDER BY startUtcMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WorkoutEntity>>
}
