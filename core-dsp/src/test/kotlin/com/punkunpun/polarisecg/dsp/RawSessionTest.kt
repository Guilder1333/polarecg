package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import com.punkunpun.polarisecg.dsp.raw.listSegmentFiles
import com.punkunpun.polarisecg.dsp.raw.readStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RawSessionTest {

    private fun tempDir(): File = Files.createTempDirectory("rawsess").toFile().also { it.deleteOnExit() }

    private fun writeEcg(file: File, vararg phoneMs: Long) {
        RawWriter(FileOutputStream(file), RawStream.ECG).use { w ->
            for (t in phoneMs) w.writeEcg(t, t * 1000, intArrayOf(1, 2, 3))
        }
    }

    @Test
    fun segmentsAreListedOldestFirstWithCurrentFileLast() {
        val dir = tempDir()
        for (name in listOf(
            "ecg.bin",
            "ecg_20261012T231504Z.bin",
            "ecg_20261012T221530Z_1.bin",
            "ecg_20261012T221530Z.bin",
            "acc_20261012T221530Z.bin",
            "session.json",
        )) File(dir, name).writeBytes(ByteArray(0))

        assertEquals(
            listOf(
                "ecg_20261012T221530Z.bin",
                "ecg_20261012T221530Z_1.bin",
                "ecg_20261012T231504Z.bin",
                "ecg.bin",
            ),
            listSegmentFiles(dir, "ecg").map { it.name },
        )
    }

    @Test
    fun readStreamConcatenatesSegmentsAndSkipsUnreadableOnes() {
        val dir = tempDir()
        writeEcg(File(dir, "ecg_20261012T221530Z.bin"), 1_000, 2_000)
        File(dir, "ecg_20261012T225000Z_invalid.bin").writeBytes(byteArrayOf(1, 2, 3))
        writeEcg(File(dir, "ecg.bin"), 3_000)

        val data = readStream(dir, "ecg")

        assertEquals(listOf(1_000L, 2_000L, 3_000L), data.records.map { it.phoneTimeMs })
        assertEquals(listOf("ecg_20261012T225000Z_invalid.bin"), data.unreadable.map { it.name })
        assertEquals(3, (data.records[0] as RawRecord.Ecg).samplesUv.size)
    }

    @Test
    fun missingStreamGivesNothing() {
        val data = readStream(tempDir(), "hr")
        assertEquals(0, data.records.size)
    }
}
