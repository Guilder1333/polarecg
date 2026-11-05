package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.SegmentClock
import com.punkunpun.polarisecg.dsp.event.ClipPolicy
import com.punkunpun.polarisecg.dsp.event.ClipPolicyConfig
import com.punkunpun.polarisecg.dsp.event.EventRecorder
import com.punkunpun.polarisecg.dsp.event.RecordedEvent
import com.punkunpun.polarisecg.dsp.live.EcgHistory
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventRecorderTest {
    private val t0 = 1_791_843_330_000L
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    /**
     * A one-segment "recording": a beat every second, [classes] gives the class of each. ECG arrives in frames of 65 samples (0.5 s);
     * the beats are handed over [lagS] seconds after their time, as the real pipeline does. Returns everything recorded.
     */
    private fun run(classes: String, policy: ClipPolicy = ClipPolicy(), lagS: Int = 3): List<RecordedEvent> {
        val history = EcgHistory()
        val clock = SegmentClock(t0)
        history.startSegment(clock)
        val recorder = EventRecorder(history, policy)
        val out = ArrayList<RecordedEvent>()
        val beats = classes.mapIndexed { i, c -> Beat(t0 + 1000L * i + 500, 0, if (i == 0) null else 1000, c.toString(), quality, true) }
        var delivered = 0
        val seconds = classes.length
        for (frame in 0 until seconds * 2) {
            val first = frame * 65
            clock.addFrame(first, first * 1_000_000_000L / 130)
            history.add(IntArray(65) { first + it })
            val nowS = (frame + 1) / 2.0
            val due = beats.filter { it.utcMs <= t0 + (nowS - lagS) * 1000 }.size
            if (due > delivered) {
                out += recorder.add(beats.subList(delivered, due))
                delivered = due
            }
            out += recorder.poll()
        }
        out += recorder.add(beats.subList(delivered, beats.size))
        out += recorder.finish()
        return out
    }

    @Test
    fun aCoupletGetsAClipWithTenSecondsBeforeAndAfter() {
        val r = run("N".repeat(40) + "VV" + "N".repeat(40))
        val e = r.single()
        assertEquals("V_COUPLET", e.event.type)
        assertEquals(2, e.beatCount)
        val clip = assertNotNull(e.clip)
        val start = e.event.utcMs
        assertTrue(abs(clip.ecg.startUtcMs - (start - 10_000)) <= 8, "starts 10 s before: ${clip.ecg.startUtcMs - start}")
        assertTrue(abs(clip.ecg.endUtcMs - (start + e.event.durationMs + 10_000)) <= 8, "ends 10 s after the event")
        assertEquals(start, clip.eventStartUtcMs)
        // the beats of the clip are marked: about 21 of them, two V
        assertTrue(clip.beats.size in 20..23, "${clip.beats.size} beats")
        assertEquals(2, clip.beats.count { it.cls == "V" })
        // sample values are their indices, so a clip is a stretch of consecutive numbers
        for (i in 1 until clip.ecg.size) assertEquals(clip.ecg.samplesUv[i - 1] + 1, clip.ecg.samplesUv[i])
    }

    @Test
    fun theClipWaitsForItsEnd() {
        val history = EcgHistory()
        val clock = SegmentClock(t0)
        history.startSegment(clock)
        val recorder = EventRecorder(history)
        // 20 s of ECG, and all beats of an early couplet known: the clip needs ECG until 10 s after the event
        for (frame in 0 until 40) {
            clock.addFrame(frame * 65, frame * 65 * 1_000_000_000L / 130)
            history.add(IntArray(65) { frame * 65 + it })
        }
        val beats = "NNNNNNVVNNNNNNNNNN".mapIndexed { i, c -> Beat(t0 + 1000L * i, 0, 1000, c.toString(), quality, true) }
        // beats up to 14 s: the couplet (6 s, 7 s) is final, its clip ends at 7 s + 10 s = 17 s, which no beat has reached yet
        assertEquals(emptyList(), recorder.add(beats.subList(0, 15)))
        assertEquals(1, recorder.add(beats.subList(15, 18)).size, "the beats passed the end of the clip")
    }

    @Test
    fun anEventTheClipLimitRefusesIsStillReported() {
        val policy = ClipPolicy(ClipPolicyConfig(firstFew = 1, spacingMs = 3_600_000L))
        val r = run(("N".repeat(10) + "VV").repeat(6) + "N".repeat(10), policy)
        assertEquals(6, r.size)
        assertEquals(1, r.count { it.clip != null })
        assertNull(r.last().clip)
    }

    @Test
    fun anEventNearTheStartHasAShorterClip() {
        val r = run("NNNN" + "VV" + "N".repeat(30))
        val clip = assertNotNull(r.single().clip)
        assertTrue(clip.ecg.startUtcMs == t0, "cut at the start of the data")
    }

    /** The marker of a beat sits on the R peak in the clip: pipeline times and clip samples share one clock. */
    @Test
    fun clipSamplesAndBeatTimesLineUp() {
        val seconds = 60
        val ecg = IntArray(130 * seconds)
        val shape = intArrayOf(200, 800, 1500, -400, -100)
        for (s in 0 until seconds) for (k in shape.indices) if (65 + 130 * s + k < ecg.size) ecg[65 + 130 * s + k] = shape[k]
        val pipeline = LivePipeline()
        val beats = ArrayList<Beat>()
        var sampleNo = 0L
        for (from in ecg.indices step 70) {
            val chunk = ecg.copyOfRange(from, minOf(from + 70, ecg.size))
            val utcMs = t0 + sampleNo * 1000 / 130
            beats += pipeline.addEcg((utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000, utcMs + 600, chunk)
            sampleNo += chunk.size
        }
        beats += pipeline.finish()
        assertTrue(beats.size > 40)
        val beat = beats[beats.size / 2]
        val clip = assertNotNull(pipeline.history.clip(beat.utcMs, beat.utcMs - 3000, beat.utcMs + 3000))
        val marker = clip.indexOf(beat.utcMs)
        // the spikes repeat every second: look for the highest sample within a third of a second around the marker
        val peak = (marker - 40..marker + 40).maxByOrNull { clip.samplesUv[it] }!!
        assertTrue(abs(peak - marker) <= 3, "R peak at sample $peak, beat marker at $marker")
    }
}
