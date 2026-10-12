package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import com.punkunpun.polarisecg.dsp.raw.SegmentStatus
import com.punkunpun.polarisecg.dsp.raw.archiveSegment
import com.punkunpun.polarisecg.dsp.raw.inspectSegment
import com.punkunpun.polarisecg.dsp.raw.readRawFile
import com.punkunpun.polarisecg.dsp.raw.recoverCurrentFiles
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawSegmentsTest {

    // 2026-10-12T22:15:30Z
    private val t0 = 1_791_843_330_000L

    private fun tempDir(): File = Files.createTempDirectory("rawseg").toFile().also { it.deleteOnExit() }

    private fun writeEcg(file: File, vararg phoneMs: Long) {
        RawWriter(FileOutputStream(file), RawStream.ECG).use { w ->
            for (t in phoneMs) w.writeEcg(t, t * 1000, intArrayOf(1, 2, 3))
        }
    }

    private fun names(dir: File) = dir.list()!!.sorted()

    @Test
    fun okFileIsNamedByFirstRecordToTheSecond() {
        val dir = tempDir()
        writeEcg(File(dir, "ecg.bin"), t0 + 250, t0 + 750, t0 + 5_000)
        val result = archiveSegment(File(dir, "ecg.bin"), "ecg")!!
        assertEquals("ecg_20261012T221530Z.bin", result.file.name)
        assertEquals(SegmentStatus.OK, result.info.status)
        assertEquals(listOf("ecg_20261012T221530Z.bin"), names(dir))
    }

    @Test
    fun occupiedNameGetsCounterSuffix() {
        val dir = tempDir()
        repeat(3) {
            writeEcg(File(dir, "ecg.bin"), t0 + 100)
            archiveSegment(File(dir, "ecg.bin"), "ecg")
        }
        assertEquals(
            listOf("ecg_20261012T221530Z.bin", "ecg_20261012T221530Z_1.bin", "ecg_20261012T221530Z_2.bin"),
            names(dir),
        )
    }

    @Test
    fun headerOnlyFileIsKeptAndMarkedEmpty() {
        val dir = tempDir()
        val f = File(dir, "acc.bin")
        RawWriter(FileOutputStream(f), RawStream.ACC).close()
        f.setLastModified(t0)
        val result = archiveSegment(f, "acc")!!
        assertEquals("acc_20261012T221530Z_empty.bin", result.file.name)
        assertEquals(SegmentStatus.EMPTY, result.info.status)
        assertTrue(result.file.exists())
    }

    @Test
    fun garbageFileIsKeptAndMarkedInvalid() {
        val dir = tempDir()
        val f = File(dir, "hr.bin")
        f.writeBytes(byteArrayOf(1, 2, 3))
        f.setLastModified(t0)
        val result = archiveSegment(f, "hr")!!
        assertEquals("hr_20261012T221530Z_invalid.bin", result.file.name)
        assertEquals(SegmentStatus.INVALID, result.info.status)
    }

    @Test
    fun emptyAndInvalidShareTheCounterLogic() {
        val dir = tempDir()
        repeat(2) {
            val f = File(dir, "hr.bin")
            f.writeBytes(byteArrayOf(9))
            f.setLastModified(t0)
            archiveSegment(f, "hr")
        }
        assertEquals(listOf("hr_20261012T221530Z_invalid.bin", "hr_20261012T221530Z_invalid_1.bin"), names(dir))
    }

    @Test
    fun truncatedLastRecordIsIgnoredButFileIsOk() {
        val dir = tempDir()
        val f = File(dir, "ecg.bin")
        writeEcg(f, t0, t0 + 500)
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 5)) // cut the second record
        val info = inspectSegment(f)
        assertEquals(SegmentStatus.OK, info.status)
        assertEquals(t0, info.lastPhoneMs)
        assertEquals(1, readRawFile(f.readBytes()).records.size)
    }

    @Test
    fun missingFileIsNotAnError() {
        assertNull(archiveSegment(File(tempDir(), "ecg.bin"), "ecg"))
    }

    @Test
    fun recoveryArchivesAllCurrentFilesAndReportsLastData() {
        val dir = tempDir()
        writeEcg(File(dir, "ecg.bin"), t0, t0 + 60_000)
        RawWriter(FileOutputStream(File(dir, "acc.bin")), RawStream.ACC).use {
            it.writeAcc(t0 + 120_000, 5, intArrayOf(1), intArrayOf(2), intArrayOf(3))
        }
        File(dir, "ecg_20261012T210000Z.bin").writeBytes(byteArrayOf(0)) // earlier rotation: untouched

        val result = recoverCurrentFiles(dir)
        assertEquals(t0 + 120_000, result.lastDataMs)
        assertEquals(2, result.archived.size)
        assertFalse(File(dir, "ecg.bin").exists())
        assertFalse(File(dir, "acc.bin").exists())
        assertTrue(File(dir, "ecg_20261012T221530Z.bin").exists())
        assertTrue(File(dir, "acc_20261012T221730Z.bin").exists())
        assertTrue(File(dir, "ecg_20261012T210000Z.bin").exists())

        // Running it again must change nothing.
        assertEquals(0, recoverCurrentFiles(dir).archived.size)
    }
}
