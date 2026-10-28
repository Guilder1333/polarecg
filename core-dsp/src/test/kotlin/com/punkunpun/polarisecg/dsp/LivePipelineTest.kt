package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LivePipelineTest {

    private val t0 = 1_791_843_330_000L // phone UTC of the first sample

    /** [seconds] of ECG with a QRS-like spike every second, a little uneven so that some beats are early. */
    private fun ecg(seconds: Int): IntArray {
        val x = IntArray(130 * seconds)
        val shape = intArrayOf(200, 800, 1500, -400, -100)
        for (s in 0 until seconds) {
            val c = 65 + 130 * s - (if (s % 7 == 6) 30 else 0)
            for (k in shape.indices) if (c + k < x.size) x[c + k] = shape[k]
        }
        return x
    }

    private fun ecgFrames(x: IntArray, skipFrame: Int = -1): List<RawRecord.Ecg> {
        val out = ArrayList<RawRecord.Ecg>()
        var sampleNo = 0L
        var f = 0
        for (from in x.indices step 70) {
            val chunk = x.copyOfRange(from, minOf(from + 70, x.size))
            if (f != skipFrame) {
                val utcMs = t0 + sampleNo * 1000 / 130
                out += RawRecord.Ecg(utcMs + chunk.size * 1000L / 130 + 100, (utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000, chunk)
            }
            sampleNo += chunk.size
            f++
        }
        return out
    }

    /** 50 Hz accelerometer frames of 36 samples; the second from [shakeFrom] to [shakeTo] s shakes. */
    private fun accFrames(seconds: Int, shakeFrom: Int, shakeTo: Int): List<RawRecord.Acc> {
        val out = ArrayList<RawRecord.Acc>()
        var sampleNo = 0L
        while (sampleNo < seconds * 50L) {
            val n = 36
            val x = IntArray(n); val y = IntArray(n); val z = IntArray(n)
            for (i in 0 until n) {
                val sec = (sampleNo + i) / 50
                val shake = if (sec in shakeFrom until shakeTo) 400 * (if ((sampleNo + i) % 2 == 0L) 1 else -1) else 0
                x[i] = shake; y[i] = 0; z[i] = 1000
            }
            val utcMs = t0 + sampleNo * 1000 / 50
            out += RawRecord.Acc(utcMs + n * 1000L / 50 + 100, (utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000, x, y, z)
            sampleNo += n
        }
        return out
    }

    /** Feeds both streams in the order the frames "arrive" (by phone time), like the phone does. */
    private fun runLive(ecg: List<RawRecord.Ecg>, acc: List<RawRecord.Acc>, onFrame: (Int, List<Beat>) -> Unit = { _, _ -> }): List<Beat> {
        val pipeline = LivePipeline()
        val all = ArrayList<Beat>()
        val arrivals = (ecg.map { it.phoneTimeMs to it } + acc.map { it.phoneTimeMs to it }).sortedBy { it.first }
        var ecgNo = 0
        for ((_, r) in arrivals) {
            val out = when (r) {
                is RawRecord.Ecg -> pipeline.addEcg(r.sensorTimeNs, r.phoneTimeMs, r.samplesUv).also { onFrame(ecgNo++, all + it) }
                is RawRecord.Acc -> pipeline.addAcc(r.sensorTimeNs, r.phoneTimeMs, r.xMg, r.yMg, r.zMg)
                else -> error("unexpected record")
            }
            all += out
        }
        all += pipeline.finish()
        return all
    }

    private fun assertSameBeats(expected: List<Beat>, actual: List<Beat>) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            val e = expected[i]; val a = actual[i]
            assertEquals(e.utcMs, a.utcMs, "utc of beat $i")
            assertEquals(e.segment, a.segment)
            assertEquals(e.rrMs, a.rrMs)
            assertEquals(e.cls, a.cls, "class of beat $i")
            assertEquals(e.good, a.good)
            assertEquals(e.quality.sqi, a.quality.sqi, 1e-12)
            assertEquals(e.quality.motionMg, a.quality.motionMg)
            assertEquals(e.vDoubt, a.vDoubt)
            assertEquals(e.rescued, a.rescued)
        }
    }

    @Test
    fun frameByFrameGivesTheSameBeatsAsTheWholeRecording() {
        val ecg = ecgFrames(ecg(120))
        val acc = accFrames(120, shakeFrom = 40, shakeTo = 50)
        val batch = analyzeEcg(ecg, acc).beats
        assertTrue(batch.size > 100)
        assertTrue(batch.any { (it.quality.motionMg ?: 0.0) > 100 }, "the shaking must show in some beats")
        assertSameBeats(batch, runLive(ecg, acc))
    }

    @Test
    fun aGapStartsANewSegmentAndStillMatches() {
        val ecg = ecgFrames(ecg(120), skipFrame = 100) // 70 samples lost
        val acc = accFrames(120, 0, 0)
        val live = runLive(ecg, acc)
        assertEquals(setOf(0, 1), live.map { it.segment }.toSet())
        assertSameBeats(analyzeEcg(ecg, acc).beats, live)
    }

    @Test
    fun withoutAccelerometerNothingWaitsForIt() {
        val ecg = ecgFrames(ecg(60))
        val live = runLive(ecg, emptyList())
        assertSameBeats(analyzeEcg(ecg).beats, live)
        assertTrue(live.all { it.quality.motionMg == null })
    }

    @Test
    fun beatsComeOutWhileTheSignalRunsWithAFewSecondsDelay() {
        val ecg = ecgFrames(ecg(120))
        val acc = accFrames(120, 0, 0)
        var checked = false
        runLive(ecg, acc) { frameNo, beatsSoFar ->
            if (frameNo == 100) { // ~54 s of ECG received
                checked = true
                val seconds = (ecg[frameNo].phoneTimeMs - t0) / 1000.0
                val lastBeatS = (beatsSoFar.last().utcMs - t0) / 1000.0
                assertTrue(seconds - lastBeatS < 5.0, "latest finished beat is ${seconds - lastBeatS} s old")
            }
        }
        assertTrue(checked)
    }
}
