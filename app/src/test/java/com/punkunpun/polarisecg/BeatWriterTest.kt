package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.storage.BeatDao
import com.punkunpun.polarisecg.storage.BeatEntity
import com.punkunpun.polarisecg.storage.BeatWriter
import com.punkunpun.polarisecg.storage.ClassCount
import com.punkunpun.polarisecg.storage.HrvEntity
import com.punkunpun.polarisecg.storage.MinuteEntity
import com.punkunpun.polarisecg.storage.SummaryDao
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatWriterTest {
    /** Keeps what it is given; the rest of the DAO is not needed here. */
    private class FakeDao : BeatDao {
        val saved: MutableSet<Long> = Collections.synchronizedSet(HashSet())
        override suspend fun insertAll(beats: List<BeatEntity>) { beats.forEach { saved += it.utcMs } }
        override fun latestUtcMs(): Long? = saved.maxOrNull()
        override fun deleteBefore(beforeUtcMs: Long): Int = 0
        override suspend fun countByClass(fromUtcMs: Long, toUtcMs: Long): List<ClassCount> = emptyList()
        override fun beatsBetween(fromUtcMs: Long, toUtcMs: Long): List<BeatEntity> = emptyList()
        override fun observeCountSince(fromUtcMs: Long): Flow<Int> = emptyFlow()
    }

    private class FakeSummaryDao : SummaryDao {
        val minutes = Collections.synchronizedMap(HashMap<Long, MinuteEntity>())
        val windows = Collections.synchronizedMap(HashMap<Long, HrvEntity>())
        override suspend fun insertMinutes(rows: List<MinuteEntity>) { rows.forEach { minutes[it.minuteUtcMs] = it } }
        override suspend fun insertHrv(rows: List<HrvEntity>) { rows.forEach { windows[it.startUtcMs] = it } }
        override suspend fun minuteAt(minuteUtcMs: Long): MinuteEntity? = minutes[minuteUtcMs]
        override suspend fun hrvAt(startUtcMs: Long): HrvEntity? = windows[startUtcMs]
        override suspend fun minutesBetween(fromUtcMs: Long, toUtcMs: Long): List<MinuteEntity> = emptyList()
        override suspend fun hrvBetween(fromUtcMs: Long, toUtcMs: Long): List<HrvEntity> = emptyList()
        override fun observeMinuteCount(): Flow<Int> = emptyFlow()
    }

    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    @Test
    fun noBeatIsLostWhenOneArrivesAsABatchTimesOut() = runBlocking {
        // Real life: a beat every 0.65 s and a flush every 5 s; here everything is a thousand times faster, so the
        // moment a batch times out and the moment a beat arrives meet often (they once did about twice an hour).
        val dao = FakeDao()
        val summaryDao = FakeSummaryDao()
        val writer = BeatWriter(dao, summaryDao, flushAfterMs = 1)
        val beats = MutableSharedFlow<Beat>(extraBufferCapacity = 1024)
        writer.start(beats)
        val total = 60_000
        withContext(Dispatchers.Default) {
            for (i in 0 until total) {
                beats.emit(Beat(1_000_000L + i, 0, null, "N", quality, true))
                if (i % 7 == 0) delay(1)
            }
        }
        writer.finish(Job().apply { complete() })
        for (wait in 0 until 200) { if (dao.saved.size == total) break; delay(50) }
        assertEquals(total, dao.saved.size)
        // the minute rows add up to the same beats, the last partial minute included (a normal stop keeps it)
        for (wait in 0 until 100) { if (summaryDao.minutes.values.sumOf { it.beats } == total) break; delay(50) }
        assertEquals(total, summaryDao.minutes.values.sumOf { it.beats })
    }
}
