package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.event.accFilesFor
import com.punkunpun.polarecg.dsp.event.cutWindowAnchored
import com.punkunpun.polarecg.dsp.event.fileStampMs
import com.punkunpun.polarecg.dsp.motion.AccWindow
import com.punkunpun.polarecg.dsp.motion.cutAccWindow
import com.punkunpun.polarecg.dsp.raw.RawRecord
import com.punkunpun.polarecg.dsp.time.POLAR_EPOCH_UNIX_MS
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccWindowTest {
    private val t0 = 1_800_000_000_000L

    private fun sensorNsAt(utcMs: Double) = ((utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000).toLong()

    /** 60 s of ECG at 130 Hz, frames of 73 samples, arriving [delayMs] after their last sample. Values = sample index. */
    private fun ecgFrames(seconds: Int = 60, delayMs: Long = 100): List<RawRecord.Ecg> {
        val total = seconds * 130
        val out = ArrayList<RawRecord.Ecg>()
        var i = 0
        while (i < total) {
            val n = minOf(73, total - i)
            val firstMs = t0 + i * 1000.0 / 130
            out += RawRecord.Ecg((firstMs + n * 1000.0 / 130).toLong() + delayMs, sensorNsAt(firstMs), IntArray(n) { i + it })
            i += n
        }
        return out
    }

    /**
     * ACC frames of 36 samples at [realHz] (strap clock), still (z = 1000 mG) except for a bump of [bumpMg] on x at strap time
     * [bumpUtcMs]. They arrive [delayMs] after their last sample: a different latency than the ECG, as on the phone.
     */
    private fun accFrames(seconds: Int = 60, realHz: Double = 50.0, bumpUtcMs: Long = t0 + 30_000, bumpMg: Int = 400, delayMs: Long = 700, clockShiftMs: Long = 0): List<RawRecord.Acc> {
        val total = (seconds * realHz).toInt()
        val out = ArrayList<RawRecord.Acc>()
        var i = 0
        while (i < total) {
            val n = minOf(36, total - i)
            val firstMs = t0 + i * 1000.0 / realHz
            val x = IntArray(n) { k -> if (abs(firstMs + k * 1000.0 / realHz - bumpUtcMs) < 10) bumpMg else 0 }
            out += RawRecord.Acc(
                (firstMs + n * 1000.0 / realHz).toLong() + delayMs,
                sensorNsAt(firstMs + clockShiftMs),
                x, IntArray(n), IntArray(n) { 1000 },
            )
            i += n
        }
        return out
    }

    /** The UTC the ECG window gives to the ECG sample taken at strap time [utcMs] (its value is its index at 130 Hz). */
    private fun ecgUtcOfStrapTime(window: com.punkunpun.polarecg.dsp.event.RawEcgWindow, utcMs: Long): Long {
        val index = Math.round((utcMs - t0) * 130 / 1000.0).toInt()
        val pos = window.clip.samplesUv.indexOf(index)
        assertTrue(pos >= 0, "sample $index is in the window")
        return window.clip.utcMsOf(pos.toDouble())
    }

    @Test fun theAccLandsWhereTheEcgIs() {
        // the bump happens at strap time 30 s; the ECG sample taken at that strap time must be at the same UTC as the bump
        val w = assertNotNull(cutWindowAnchored(ecgFrames(), t0 + 30_000, 10_000))
        val acc = assertNotNull(cutAccWindow(accFrames(), w.anchorUtcMs, w.anchorSensorNs, w.clip.startUtcMs, w.clip.endUtcMs))
        val bumpAt = acc.utcMs[acc.xMg.indices.maxBy { acc.xMg[it] }]
        assertTrue(abs(bumpAt - ecgUtcOfStrapTime(w, t0 + 30_000)) <= 10, "bump ${bumpAt - t0}, ECG ${ecgUtcOfStrapTime(w, t0 + 30_000) - t0}")
        // the whole window is covered at 50 Hz, and nothing outside it
        assertTrue(acc.size in 995..1005, "size ${acc.size}")
        assertTrue(acc.utcMs.first() >= w.clip.startUtcMs && acc.utcMs.last() <= w.clip.endUtcMs)
    }

    @Test fun theStrapClockGivesTheSpacingNotTheNominalRate() {
        // the strap really samples at 52 Hz while the setting says 50: the times must follow the strap's clock anyway
        val w = assertNotNull(cutWindowAnchored(ecgFrames(), t0 + 30_000, 10_000))
        val acc = assertNotNull(cutAccWindow(accFrames(realHz = 52.0, bumpUtcMs = t0 + 35_000), w.anchorUtcMs, w.anchorSensorNs, w.clip.startUtcMs, w.clip.endUtcMs))
        val bumpAt = acc.utcMs[acc.xMg.indices.maxBy { acc.xMg[it] }]
        assertTrue(abs(bumpAt - ecgUtcOfStrapTime(w, t0 + 35_000)) <= 10, "bump ${bumpAt - t0}")
        assertTrue(acc.size in 1035..1045, "size ${acc.size}")
        assertTrue((1 until acc.size).all { acc.utcMs[it] >= acc.utcMs[it - 1] }, "in time order")
    }

    @Test fun framesOnAnotherClockAreSkipped() {
        val w = assertNotNull(cutWindowAnchored(ecgFrames(), t0 + 30_000, 10_000))
        // the strap's clock jumped a day: the samples would land a day away from their arrival time
        assertNull(cutAccWindow(accFrames(clockShiftMs = 86_400_000), w.anchorUtcMs, w.anchorSensorNs, w.clip.startUtcMs, w.clip.endUtcMs))
        assertNull(cutAccWindow(emptyList(), w.anchorUtcMs, w.anchorSensorNs, w.clip.startUtcMs, w.clip.endUtcMs))
    }

    @Test fun movementIsFlatWhenStillAndPeaksAtABump() {
        val n = 200
        val times = LongArray(n) { t0 + it * 20L }
        val still = AccWindow(times, IntArray(n), IntArray(n), IntArray(n) { 1000 }).movementMg()
        assertTrue(still.all { it < 1e-9 }, "gravity alone is no movement")
        val x = IntArray(n) { if (it == 100) 300 else 0 }
        val moved = AccWindow(times, x, IntArray(n), IntArray(n) { 1000 }).movementMg()
        assertEquals(100, moved.indices.maxBy { moved[it] })
        assertTrue(moved[100] > 280, "the bump stands out: ${moved[100]}")
        assertTrue(moved[0] < 1e-9 && moved[n - 1] < 1e-9, "far from the bump it stays flat")
    }

    @Test fun theRightAccFilesAreChosen() {
        val b = fileStampMs("acc_20261105T110000Z.bin", "acc")!!
        val c = fileStampMs("acc_20261105T120000Z.bin", "acc")!!
        assertEquals(fileStampMs("ecg_20261105T110000Z.bin", "ecg"), b)
        val files = listOf("acc_20261105T120000Z.bin", "acc_20261105T100000Z.bin", "acc_20261105T110000Z.bin", "ecg_20261105T100000Z.bin", "acc.bin").map(::File)
        fun names(from: Long, to: Long) = accFilesFor(files, from, to).map { it.name }
        // inside the second file: only that one (the ACC needs no reading from the start)
        assertEquals(listOf("acc_20261105T110000Z.bin"), names(b + 1_000_000, b + 1_020_000))
        // across the hour change: both files, and the current one (no finished file starts after the window, so it may hold part of it)
        assertEquals(listOf("acc_20261105T110000Z.bin", "acc_20261105T120000Z.bin", "acc.bin"), names(c - 10_000, c + 10_000))
        // across an earlier hour change: no current file, a later finished file exists
        assertEquals(listOf("acc_20261105T100000Z.bin", "acc_20261105T110000Z.bin"), names(b - 10_000, b + 10_000))
        // after the last finished file: it and the current file
        assertEquals(listOf("acc_20261105T120000Z.bin", "acc.bin"), names(c + 1_000_000, c + 1_020_000))
        // before every file: the first one (it may still start a little after its first samples)
        assertEquals(listOf("acc_20261105T100000Z.bin"), names(b - 3_700_000, b - 3_590_000))
    }
}
