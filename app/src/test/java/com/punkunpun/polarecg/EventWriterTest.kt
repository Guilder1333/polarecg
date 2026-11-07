package com.punkunpun.polarecg

import com.punkunpun.polarecg.dsp.analysis.Event
import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.event.ClipFormat
import com.punkunpun.polarecg.dsp.event.EventClip
import com.punkunpun.polarecg.dsp.event.RecordedEvent
import com.punkunpun.polarecg.dsp.live.EcgClip
import com.punkunpun.polarecg.storage.EventDao
import com.punkunpun.polarecg.storage.EventEntity
import com.punkunpun.polarecg.storage.EventWriter
import java.io.File
import java.util.Collections
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    /** An in-memory table that numbers rows 1, 2, 3... like SQLite. */
    private class FakeDao : EventDao {
        val rows: MutableMap<Long, EventEntity> = Collections.synchronizedMap(LinkedHashMap())
        private var next = 1L
        override suspend fun insert(event: EventEntity): Long { val id = next++; rows[id] = event.copy(id = id); return id }
        override suspend fun setClipFile(id: Long, clipFile: String) { rows[id] = rows.getValue(id).copy(clipFile = clipFile) }
        override suspend fun between(fromUtcMs: Long, toUtcMs: Long): List<EventEntity> = emptyList()
        override fun eventsBetween(fromUtcMs: Long, toUtcMs: Long): List<EventEntity> = emptyList()
        override fun observeCountSince(fromUtcMs: Long): Flow<Int> = emptyFlow()
        override fun observeRecent(limit: Int): Flow<List<EventEntity>> = emptyFlow()
        override suspend fun byId(id: Long): EventEntity? = rows[id]
    }

    private val t0 = 1_791_843_330_000L

    private fun recorded(type: String, atMs: Long, withClip: Boolean): RecordedEvent {
        val clip = if (withClip) EventClip(
            EcgClip(atMs - 10_000, 7.68, IntArray(2600) { it }),
            listOf(ClipBeat(atMs, "V", good = true, vDoubt = false, rescued = false)),
            atMs, atMs + 1_000,
        ) else null
        return RecordedEvent(Event(atMs, type, 1_000, "2 beats, 90 bpm"), 2, clip)
    }

    private fun waitFor(what: () -> Boolean) {
        runBlocking<Unit> { repeat(200) { if (what()) return@runBlocking; delay(25) } }
    }

    @Test
    fun savesTheRowAndTheClipFileUnderTheRowId() = runBlocking {
        val dao = FakeDao()
        val dir = File(tmp.root, "clips")
        val writer = EventWriter(dao, dir)
        val events = MutableSharedFlow<RecordedEvent>(extraBufferCapacity = 16)
        writer.start(events)
        events.emit(recorded("V_COUPLET", t0, withClip = true))
        events.emit(recorded("S_RUN", t0 + 60_000, withClip = false)) // refused by the clip limit: a row, no file
        // finish() cancels the listener: let it take both events first (otherwise the test races the writer's own thread)
        waitFor { dao.rows.size == 2 }
        writer.finish(Job().apply { complete() })
        waitFor { dao.rows.size == 2 && dao.rows[1]?.clipFile != null }

        val first = dao.rows.getValue(1)
        assertEquals("V_COUPLET", first.type)
        assertEquals(t0 + 1_000, first.endUtcMs)
        assertEquals("1.bin", first.clipFile)
        assertNull(dao.rows.getValue(2).clipFile)
        val back = ClipFormat.decode(File(dir, "1.bin").readBytes())
        assertNotNull(back)
        assertArrayEquals(IntArray(2600) { it }, back!!.ecg.samplesUv)
        assertEquals("no temporary file is left", setOf("1.bin"), dir.list()!!.toSet())
    }

    @Test
    fun neverOverwritesAnExistingClipFile() = runBlocking {
        val dao = FakeDao()
        val dir = File(tmp.root, "clips").apply { mkdirs() }
        File(dir, "1.bin").writeText("an older clip, e.g. from before the database was deleted")
        val writer = EventWriter(dao, dir)
        val events = MutableSharedFlow<RecordedEvent>(extraBufferCapacity = 16)
        writer.start(events)
        events.emit(recorded("V_TRIPLET", t0, withClip = true))
        waitFor { dao.rows.size == 1 } // the listener has the event before finish() cancels it
        writer.finish(Job().apply { complete() })
        waitFor { dao.rows[1]?.clipFile != null }

        assertEquals("1_1.bin", dao.rows.getValue(1).clipFile)
        assertEquals("an older clip, e.g. from before the database was deleted", File(dir, "1.bin").readText())
        assertTrue(File(dir, "1_1.bin").length() > 10_000)
    }
}
