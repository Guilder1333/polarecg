package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import com.punkunpun.polarisecg.recorder.SessionFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFoldersTest {

    private val t0 = 1_791_843_330_000L // 2026-10-12T22:15:30Z
    private val dev = "A1B2C3D4"

    private fun root(): File = Files.createTempDirectory("sessions").toFile().also { it.deleteOnExit() }

    private fun meta(device: String = dev) = { start: Long ->
        JSONObject().put("formatVersion", 1).put("startUtc", SessionFolders.iso(start))
            .put("endUtc", JSONObject.NULL).put("deviceId", device)
    }

    private fun writeEcg(dir: File, vararg phoneMs: Long) {
        RawWriter(FileOutputStream(File(dir, "ecg.bin")), RawStream.ECG).use { w ->
            for (t in phoneMs) w.writeEcg(t, t, intArrayOf(1))
        }
    }

    @Test
    fun firstStartCreatesNewSessionWithoutEnd() {
        val root = root()
        val s = SessionFolders.open(root, dev, t0, meta())
        assertFalse(s.resumed)
        assertEquals("20261012T221530Z", s.dir.name)
        val saved = SessionFolders.readMeta(s.dir)!!
        assertTrue(saved.isNull("endUtc"))
        assertEquals(0, saved.getJSONArray("interruptions").length())
    }

    @Test
    fun killedSessionIsResumedInTheSameFolder() {
        val root = root()
        val first = SessionFolders.open(root, dev, t0, meta())
        writeEcg(first.dir, t0 + 1_000, t0 + 90_000) // process dies here: ecg.bin left behind

        val second = SessionFolders.open(root, dev, t0 + 600_000, meta())
        assertTrue(second.resumed)
        assertEquals(first.dir, second.dir)
        assertEquals(t0, second.startMs)
        assertFalse(File(first.dir, "ecg.bin").exists())
        assertTrue(File(first.dir, "ecg_20261012T221531Z.bin").exists())

        val saved = SessionFolders.readMeta(first.dir)!!
        assertTrue(saved.isNull("endUtc"))
        val interruption = saved.getJSONArray("interruptions").getJSONObject(0)
        assertEquals(SessionFolders.iso(t0 + 90_000), interruption.getString("lastDataUtc"))
        assertEquals(SessionFolders.iso(t0 + 600_000), interruption.getString("resumedUtc"))
    }

    @Test
    fun properStopEndsTheSessionAndNextStartIsNew() {
        val root = root()
        val first = SessionFolders.open(root, dev, t0, meta())
        SessionFolders.end(first, t0 + 3_600_000)

        val second = SessionFolders.open(root, dev, t0 + 4_000_000, meta())
        assertFalse(second.resumed)
        assertNotEquals(first.dir, second.dir)
        assertEquals(SessionFolders.iso(t0 + 3_600_000), SessionFolders.readMeta(first.dir)!!.getString("endUtc"))
        assertFalse(SessionFolders.readMeta(first.dir)!!.has("recovered"))
    }

    @Test
    fun differentStrapStartsNewSessionAndClosesOldOneAsRecovered() {
        val root = root()
        val first = SessionFolders.open(root, dev, t0, meta())
        writeEcg(first.dir, t0 + 5_000)

        val second = SessionFolders.open(root, "OTHER000", t0 + 60_000, meta("OTHER000"))
        assertFalse(second.resumed)
        val old = SessionFolders.readMeta(first.dir)!!
        assertEquals(SessionFolders.iso(t0 + 5_000), old.getString("endUtc"))
        assertTrue(old.getBoolean("recovered"))
        assertFalse(File(first.dir, "ecg.bin").exists())
    }

    @Test
    fun damagedSessionJsonIsRecoveredFromFolderNameAndData() {
        val root = root()
        val dir = File(root, "20261011T230000Z").also { it.mkdirs() }
        File(dir, "session.json").writeText("{\"formatVer") // killed mid-write (old non-atomic writer)
        writeEcg(dir, t0 - 3_000)

        val s = SessionFolders.open(root, dev, t0, meta())
        assertFalse(s.resumed)
        val recovered = SessionFolders.readMeta(dir)!!
        assertEquals("2026-10-11T23:00:00Z", recovered.getString("startUtc"))
        assertEquals(SessionFolders.iso(t0 - 3_000), recovered.getString("endUtc"))
        assertTrue(recovered.getBoolean("recovered"))
    }

    @Test
    fun sessionFromOlderLayoutIsNotResumed() {
        val root = root()
        val dir = File(root, "20261011T230000Z").also { it.mkdirs() }
        // Old layout: no "layout" field, endUtc null because the app was killed.
        SessionFolders.writeMeta(dir, meta()(t0 - 100_000))

        val s = SessionFolders.open(root, dev, t0, meta())
        assertFalse(s.resumed)
        assertTrue(SessionFolders.readMeta(dir)!!.getBoolean("recovered"))
    }

    @Test
    fun timeMapConnectionNumbersContinueAfterResume() {
        val root = root()
        val first = SessionFolders.open(root, dev, t0, meta())
        File(first.dir, "timemap.csv").writeText(
            "connection,phoneUtcMs,sensorNs,offsetMs,kind\n0,1,2,3,start\n1,4,5,6,start\n1,7,8,9,periodic\n",
        )
        val second = SessionFolders.open(root, dev, t0 + 1000, meta())
        assertEquals(2, second.timeMapConnectionBase)
        assertEquals(0, first.timeMapConnectionBase)
    }

    @Test
    fun resumeIsIdempotentWhenRepeated() {
        val root = root()
        val first = SessionFolders.open(root, dev, t0, meta())
        writeEcg(first.dir, t0)
        SessionFolders.open(root, dev, t0 + 1000, meta())
        val third = SessionFolders.open(root, dev, t0 + 2000, meta()) // killed again before any data
        assertTrue(third.resumed)
        assertEquals(2, SessionFolders.readMeta(first.dir)!!.getJSONArray("interruptions").length())
        assertNull(first.dir.list()!!.firstOrNull { it == "ecg.bin" })
    }

    @Test
    fun unfinishedShowsWhatTheNextStartWouldResumeAndChangesNothing() {
        val root = root()
        assertNull(SessionFolders.unfinished(root, dev)) // nothing yet: the next start is a new session
        val s = SessionFolders.open(root, dev, t0, meta())
        val before = s.dir.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first }
        val info = SessionFolders.unfinished(root, dev)!!
        assertEquals(s.dir, info.dir)
        assertEquals(t0, info.startMs)
        assertEquals(before, s.dir.listFiles()!!.map { it.name to it.length() }.sortedBy { it.first }) // read-only
        assertNull(SessionFolders.unfinished(root, "OTHER")) // another strap: a new session would start

        SessionFolders.end(s, t0 + 60_000)
        assertNull(SessionFolders.unfinished(root, dev)) // stopped by the user: the next start is new
    }
}
