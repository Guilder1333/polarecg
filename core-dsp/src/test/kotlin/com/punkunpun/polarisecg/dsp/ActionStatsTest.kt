package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.Window
import com.punkunpun.polarisecg.dsp.annotations.ActionSpan
import com.punkunpun.polarisecg.dsp.annotations.NO_ACTION
import com.punkunpun.polarisecg.dsp.annotations.actionAt
import com.punkunpun.polarisecg.dsp.annotations.actionSpans
import com.punkunpun.polarisecg.dsp.annotations.eventContexts
import com.punkunpun.polarisecg.dsp.annotations.parseMarks
import com.punkunpun.polarisecg.dsp.annotations.summarizeActions
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionStatsTest {
    private val t0 = 1_793_300_000_000L // a multiple of 10 s
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    private fun line(id: String, kind: String, name: String?, time: Long, note: String = "", pressed: Long = time) =
        """{"id":"$id","kind":"$kind","name":${if (name == null) "null" else "\"$name\""},"pressedMs":$pressed,"timeMs":$time,"note":"$note","recordedMs":$time}"""

    // ---- reading the log -------------------------------------------------------------------------------------------

    @Test fun readsALineWrittenByThePhone() {
        // org.json escapes "/" as "\/" and writes non-ASCII as is; both must come through.
        val marks = parseMarks(listOf("""{"id":"a","kind":"event","name":"Dizzy \"a bit\"","pressedMs":1793300001000,"timeMs":1793300001000,"note":"stood\nup \/ café é","recordedMs":1793300001050}"""))
        assertEquals(1, marks.size)
        assertEquals("Dizzy \"a bit\"", marks[0].name)
        assertEquals("stood\nup / café é", marks[0].note)
        assertEquals(1_793_300_001_000L, marks[0].timeMs)
        assertEquals(false, marks[0].isAction)
    }

    @Test fun nullNameMeansNoActionAndDamagedLinesAreSkipped() {
        val marks = parseMarks(listOf(line("a", "action", null, t0), """{"id":"b","kind":"act""", "", "not json", """{"id":"c"}"""))
        assertEquals(1, marks.size)
        assertNull(marks[0].name)
    }

    @Test fun theNewestLineOfAnIdWinsAndMarksAreSortedByTime() {
        val marks = parseMarks(
            listOf(line("a", "action", "Walking", t0 + 1000, pressed = t0 + 1000), line("b", "action", "Sitting", t0), line("a", "action", "Walking", t0 - 5000, pressed = t0 + 1000)),
        )
        assertEquals(listOf("a", "b"), marks.map { it.id })
        assertEquals(t0 - 5000, marks[0].timeMs)
        assertEquals(t0 + 1000, marks[0].pressedMs)
    }

    // ---- spans -------------------------------------------------------------------------------------------------------

    @Test fun anActionLastsUntilTheNextOneAndTheLastUntilTheEnd() {
        val marks = parseMarks(listOf(line("1", "action", "Sitting", t0), line("2", "action", "Walking", t0 + 600_000), line("3", "event", "Dizzy", t0 + 700_000)))
        val spans = actionSpans(marks, t0 + 1_800_000)
        assertEquals(listOf(ActionSpan("Sitting", t0, t0 + 600_000), ActionSpan("Walking", t0 + 600_000, t0 + 1_800_000)), spans)
        assertEquals("Sitting", actionAt(spans, t0))
        assertEquals("Sitting", actionAt(spans, t0 + 599_999))
        assertEquals("Walking", actionAt(spans, t0 + 600_000))
        assertNull(actionAt(spans, t0 - 1))
        assertNull(actionAt(spans, t0 + 1_800_000))
    }

    @Test fun noActionEndsTheRunningOneAndLeavesAGap() {
        val marks = parseMarks(listOf(line("1", "action", "Walking", t0), line("2", "action", null, t0 + 60_000), line("3", "action", "Eating", t0 + 120_000)))
        val spans = actionSpans(marks, t0 + 240_000)
        assertEquals(2, spans.size)
        assertNull(actionAt(spans, t0 + 90_000))
        assertEquals("Eating", actionAt(spans, t0 + 200_000))
    }

    @Test fun actionsAfterTheEndOfTheDataAreDropped() {
        val spans = actionSpans(parseMarks(listOf(line("1", "action", "Walking", t0 + 500_000))), t0 + 100_000)
        assertTrue(spans.isEmpty())
    }

    // ---- statistics -------------------------------------------------------------------------------------------------

    /** 10 s windows from [t0]: one per entry of [qualities], each with 10 beats of 600 ms RR-ish at [hr] bpm. */
    private fun result(qualities: List<Double>, hr: Double, beats: List<Beat> = emptyList()): AnalysisResult {
        val windows = qualities.mapIndexed { i, q ->
            Window(t0 + i * 10_000L, 10_000, 1300, 1.0, 10, hr, 10, 20.0, q)
        }
        return AnalysisResult(emptyList(), beats, windows, emptyList(), emptyList())
    }

    private fun beat(t: Long, cls: String, doubt: Boolean = false) =
        Beat(t, 0, null, cls, quality, true).also { it.vDoubt = doubt }

    @Test fun usableTimeAndRatesPerAction() {
        // 6 windows of 10 s = 1 min: the first 3 are Sitting, the last 3 Walking; the 2nd and the 5th are unusable.
        val beats = listOf(
            beat(t0 + 1000, "N"), beat(t0 + 2000, "V"),          // window 0 (Sitting, usable)
            beat(t0 + 11_000, "V"),                               // window 1 (Sitting, UNUSABLE): not counted
            beat(t0 + 31_000, "S"), beat(t0 + 32_000, "N"),       // window 3 (Walking, usable)
            beat(t0 + 51_000, "V", doubt = true), beat(t0 + 52_000, "Q"), // window 5 (Walking, usable)
        )
        val res = result(listOf(1.0, 0.2, 1.0, 1.0, 0.1, 1.0), hr = 70.0, beats = beats)
        val spans = actionSpans(parseMarks(listOf(line("1", "action", "Sitting", t0), line("2", "action", "Walking", t0 + 30_000))), t0 + 60_000)
        val byName = summarizeActions(res, spans, hasMarks = true).associateBy { it.name }

        val sitting = byName.getValue("Sitting")
        assertEquals(30_000, sitting.totalMs)
        assertEquals(20_000, sitting.usableMs)
        assertEquals(1, sitting.v) // the V in the unusable window is left out
        assertEquals(1, sitting.n)
        assertEquals(1 / (20_000 / 3_600_000.0), sitting.vPerHour!!, 1e-6)
        assertEquals(70.0, sitting.meanHrBpm!!, 1e-9)

        val walking = byName.getValue("Walking")
        assertEquals(20_000, walking.usableMs)
        assertEquals(1, walking.s)
        assertEquals(1, walking.v)
        assertEquals(1, walking.vDoubt)
        assertEquals(1, walking.q)
        assertEquals(100.0 / 3, walking.vPercent!!, 1e-9) // 1 V among 3 judged beats (N, S, V; the Q is not judged)
    }

    @Test fun timeOutsideAnyActionIsCountedAsNone() {
        val res = result(listOf(1.0, 1.0, 1.0, 1.0), hr = 60.0)
        val spans = actionSpans(parseMarks(listOf(line("1", "action", "Walking", t0 + 20_000))), t0 + 40_000)
        val byName = summarizeActions(res, spans, hasMarks = true).associateBy { it.name }
        assertEquals(20_000, byName.getValue(NO_ACTION).totalMs)
        assertEquals(20_000, byName.getValue("Walking").totalMs)
    }

    @Test fun withoutAnyMarksThereIsNoSummary() {
        assertTrue(summarizeActions(result(listOf(1.0), 60.0), emptyList(), hasMarks = false).isEmpty())
    }

    @Test fun anEventGetsTheHeartRateAndEctopicBeatsBeforeAndAfter() {
        // 60 windows = 10 min; the event is in the middle, at t0 + 300 s.
        val beats = listOf(beat(t0 + 100_000, "V"), beat(t0 + 200_000, "S"), beat(t0 + 250_000, "N"), beat(t0 + 400_000, "V"))
        val windows = (0 until 60).map { i -> Window(t0 + i * 10_000L, 10_000, 1300, 1.0, 10, if (i < 30) 60.0 else 90.0, 10, 10.0, 1.0) }
        val res = AnalysisResult(emptyList(), beats, windows, emptyList(), emptyList())
        val ctx = eventContexts(res, parseMarks(listOf(line("e", "event", "Dizzy", t0 + 300_000), line("a", "action", "Walking", t0)))).single()
        assertEquals(60.0, ctx.hrBeforeBpm!!, 1e-9)
        assertEquals(90.0, ctx.hrAfterBpm!!, 1e-9)
        assertEquals(2, ctx.ectopicBefore) // V at 100 s and S at 200 s; the N does not count
        assertEquals(1, ctx.ectopicAfter)
    }
}
