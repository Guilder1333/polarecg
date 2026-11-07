package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.buildSegments
import com.punkunpun.polarisecg.dsp.event.cutWindow
import com.punkunpun.polarisecg.dsp.event.ecgFileStampMs
import com.punkunpun.polarisecg.dsp.event.ecgFilesFor
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawWindowTest {
    private val t0 = 1_800_000_000_000L
    private val rate = 130.0

    /** Frames of 73 samples whose value is the sample's index, starting at [startMs] (after [skipSamples] samples, to make a hole). */
    private fun frames(seconds: Int, startMs: Long = t0, firstIndex: Int = 0): List<RawRecord.Ecg> {
        val total = (seconds * rate).toInt()
        val out = ArrayList<RawRecord.Ecg>()
        var i = 0
        while (i < total) {
            val n = minOf(73, total - i)
            val firstMs = startMs + i * 1000.0 / rate
            val sensorNs = ((firstMs - POLAR_EPOCH_UNIX_MS) * 1_000_000).toLong()
            out += RawRecord.Ecg((firstMs + n * 1000.0 / rate + 100).toLong(), sensorNs, IntArray(n) { firstIndex + i + it })
            i += n
        }
        return out
    }

    @Test fun theWindowIsCenteredOnTheTime() {
        // the recording's own clock decides where "the sample at 30 s" is (the phone time of the first frame carries latency)
        val base = buildSegments(frames(60)).single().startUtcMs
        val clip = assertNotNull(cutWindow(frames(60), base + 30_000, 10_000))
        assertTrue(abs(clip.startUtcMs - (base + 20_000)) <= 10, "starts at ${clip.startUtcMs - base}")
        assertTrue(abs(clip.endUtcMs - (base + 40_000)) <= 10, "ends at ${clip.endUtcMs - base}")
        // sample values are their indices: the first one must be near 20 s * 130 Hz
        assertTrue(abs(clip.samplesUv.first() - 2_600) <= 3)
    }

    @Test fun theWindowIsCutShortAtTheStartOfTheRecording() {
        val base = buildSegments(frames(60)).single().startUtcMs
        val clip = assertNotNull(cutWindow(frames(60), base + 2_000, 10_000))
        assertEquals(0, clip.samplesUv.first())
        assertTrue(abs(clip.endUtcMs - (base + 12_000)) <= 10)
    }

    @Test fun aTimeOutsideTheRecordingGivesNothing() {
        assertNull(cutWindow(frames(60), t0 + 90_000, 10_000))
        assertNull(cutWindow(emptyList(), t0, 10_000))
    }

    @Test fun theWindowNeverCrossesAHole() {
        // 30 s, a 5 s hole in the sensor clock, 30 s more
        val all = frames(30) + frames(30, startMs = t0 + 35_000, firstIndex = 100_000)
        val segs = buildSegments(all)
        assertEquals(2, segs.size)
        val clip = assertNotNull(cutWindow(all, segs[0].startUtcMs + 28_000, 10_000))
        assertTrue(clip.samplesUv.all { it < 100_000 }, "the window must stay in the first stretch")
        assertNull(cutWindow(all, segs[0].endUtcMs + 2_500, 10_000)) // the hole itself
    }

    @Test fun fileNamesGiveTheirStartTime() {
        assertEquals(java.time.Instant.parse("2027-01-15T08:00:00Z").toEpochMilli(), ecgFileStampMs("ecg_20270115T080000Z.bin"))
        assertEquals(ecgFileStampMs("ecg_20261105T201459Z.bin"), ecgFileStampMs("ecg_20261105T201459Z_1.bin"))
        assertNull(ecgFileStampMs("ecg.bin"))
        assertNull(ecgFileStampMs("acc_20261105T201459Z.bin"))
        assertNull(ecgFileStampMs("ecg_garbage.bin"))
    }

    @Test fun theRightFilesAreChosen() {
        val a = ecgFileStampMs("ecg_20261105T100000Z.bin")!!
        val b = ecgFileStampMs("ecg_20261105T110000Z.bin")!!
        val c = ecgFileStampMs("ecg_20261105T120000Z.bin")!!
        val files = listOf("ecg_20261105T120000Z.bin", "ecg_20261105T100000Z.bin", "ecg_20261105T110000Z.bin", "acc_20261105T100000Z.bin", "ecg.bin").map(::File)
        fun names(center: Long) = ecgFilesFor(files, center, 10_000).map { it.name }
        // in the middle of the second file: it and the one before (the window could reach back), no current file (a later one exists)
        assertEquals(listOf("ecg_20261105T100000Z.bin", "ecg_20261105T110000Z.bin"), names(b + 1_800_000))
        // just before the third file starts: the window reaches into it, so the third file is read too
        assertEquals(listOf("ecg_20261105T110000Z.bin", "ecg_20261105T120000Z.bin"), names(c - 5_000))
        // after the last finished file: the current file holds the newest data
        assertEquals(listOf("ecg_20261105T110000Z.bin", "ecg_20261105T120000Z.bin", "ecg.bin"), names(c + 1_000_000))
        assertTrue(a < b && b < c)
    }
}
