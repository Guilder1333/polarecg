package com.punkunpun.polarisecg.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The queries on the `label` table (see [LabelEntity]). There is **no update and no delete on purpose**: labels are the one
 * thing that cannot be rebuilt from the raw files, so the history only grows.
 */
@Dao
interface LabelDao {
    @Insert
    suspend fun insert(label: LabelEntity): Long

    /**
     * The current (newest) row of every beat labelled between the two times. The inner query picks, per beat, the highest id;
     * the outer one reads those rows. Includes "?" and "" (undone) rows: the caller decides what they mean.
     */
    @Query(
        "SELECT * FROM label WHERE id IN (SELECT MAX(id) FROM label WHERE beatUtcMs >= :fromUtcMs AND beatUtcMs < :toUtcMs " +
            "GROUP BY beatUtcMs) ORDER BY beatUtcMs",
    )
    suspend fun currentBetween(fromUtcMs: Long, toUtcMs: Long): List<LabelEntity>

    /** Same, as a Flow: the review screen updates when a label is added. */
    @Query(
        "SELECT * FROM label WHERE id IN (SELECT MAX(id) FROM label WHERE beatUtcMs >= :fromUtcMs AND beatUtcMs < :toUtcMs " +
            "GROUP BY beatUtcMs) ORDER BY beatUtcMs",
    )
    fun observeCurrentBetween(fromUtcMs: Long, toUtcMs: Long): Flow<List<LabelEntity>>

    /** Every row of one beat, oldest first (the history shown on the review screen). */
    @Query("SELECT * FROM label WHERE beatUtcMs = :beatUtcMs ORDER BY id")
    suspend fun historyOf(beatUtcMs: Long): List<LabelEntity>

    /** All rows between two times in the order they were written; blocking, for the export (already off the UI thread). */
    @Query("SELECT * FROM label WHERE beatUtcMs >= :fromUtcMs AND beatUtcMs < :toUtcMs ORDER BY id")
    fun historyBetween(fromUtcMs: Long, toUtcMs: Long): List<LabelEntity>
}
