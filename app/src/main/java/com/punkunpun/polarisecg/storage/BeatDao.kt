package com.punkunpun.polarisecg.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A class and how many beats have it, from a `GROUP BY` query. */
data class ClassCount(val cls: String, val n: Int)

/**
 * The queries on the `beat` table. Room writes the code behind this interface at build time and checks the
 * SQL then, so a wrong column name is a build error. `suspend` functions run off the UI thread; a function
 * returning a [Flow] sends a new value every time the table changes.
 */
@Dao
interface BeatDao {
    /** One transaction for the whole list (Room does this for a list): much cheaper than one insert per beat. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(beats: List<BeatEntity>)

    @Query("DELETE FROM beat WHERE utcMs < :beforeUtcMs")
    suspend fun deleteBefore(beforeUtcMs: Long): Int

    @Query("SELECT cls, COUNT(*) AS n FROM beat WHERE utcMs >= :fromUtcMs AND utcMs < :toUtcMs GROUP BY cls")
    suspend fun countByClass(fromUtcMs: Long, toUtcMs: Long): List<ClassCount>

    @Query("SELECT COUNT(*) FROM beat WHERE utcMs >= :fromUtcMs")
    fun observeCountSince(fromUtcMs: Long): Flow<Int>
}
