package com.punkunpun.polarecg.storage

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

    /** The beat at exactly this time, or null (deep link to a beat). Blocking, like [beatsBetween]. */
    @Query("SELECT * FROM beat WHERE utcMs = :utcMs")
    fun beatAt(utcMs: Long): BeatEntity?

    /** Time of the newest saved beat, null if the table is empty. Blocking, like [beatsBetween]. */
    @Query("SELECT MAX(utcMs) FROM beat")
    fun latestUtcMs(): Long?

    @Query("DELETE FROM beat WHERE utcMs < :beforeUtcMs")
    fun deleteBefore(beforeUtcMs: Long): Int

    @Query("SELECT cls, COUNT(*) AS n FROM beat WHERE utcMs >= :fromUtcMs AND utcMs < :toUtcMs GROUP BY cls")
    suspend fun countByClass(fromUtcMs: Long, toUtcMs: Long): List<ClassCount>

    /** Blocking (not `suspend`): for the export, which already runs off the UI thread. */
    @Query("SELECT * FROM beat WHERE utcMs >= :fromUtcMs AND utcMs < :toUtcMs ORDER BY utcMs")
    fun beatsBetween(fromUtcMs: Long, toUtcMs: Long): List<BeatEntity>

    @Query("SELECT COUNT(*) FROM beat WHERE utcMs >= :fromUtcMs")
    fun observeCountSince(fromUtcMs: Long): Flow<Int>

    /** Beats of one automatic class in a time range, oldest first (the beat browse, P5.7). Blocking: called off the UI thread. */
    @Query("SELECT * FROM beat WHERE utcMs >= :fromUtcMs AND utcMs < :toUtcMs AND cls = :cls ORDER BY utcMs LIMIT :limit")
    fun byClass(fromUtcMs: Long, toUtcMs: Long, cls: String, limit: Int): List<BeatEntity>

    /** Normal beats with a good signal that came early (prematurity below [maxPrematurity]): the ones that may really be S. */
    @Query(
        "SELECT * FROM beat WHERE utcMs >= :fromUtcMs AND utcMs < :toUtcMs AND cls = 'N' AND good = 1 " +
            "AND prematurity IS NOT NULL AND prematurity < :maxPrematurity ORDER BY utcMs LIMIT :limit",
    )
    fun earlyNormals(fromUtcMs: Long, toUtcMs: Long, maxPrematurity: Double, limit: Int): List<BeatEntity>
}
