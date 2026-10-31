package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.analysis.AnalysisResult
import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.Window
import com.punkunpun.polarisecg.dsp.annotations.actionSpans
import com.punkunpun.polarisecg.dsp.annotations.parseMarks
import com.punkunpun.polarisecg.dsp.annotations.parseOwnerLabels
import com.punkunpun.polarisecg.dsp.annotations.renderDayReport
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import kotlin.test.Test
import kotlin.test.assertTrue

class DayReportTest {
    private val t0 = 1_793_300_000_000L // a multiple of 10 s
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    private fun line(id: String, name: String, time: Long) =
        """{"id":"$id","kind":"action","name":"$name","pressedMs":$time,"timeMs":$time,"note":"","recordedMs":$time}"""

    private fun beat(t: Long, cls: String) = Beat(t, 0, null, cls, quality, true)

    /** 2 h of 10 s windows from t0, all usable except the ones from 90 min on (noise). */
    private fun result(): AnalysisResult {
        val windows = (0 until 720).map { i -> Window(t0 + i * 10_000L, 10_000, 1300, 1.0, 10, 70.0, 10, 10.0, if (i >= 540) 0.1 else 1.0) }
        val beats = listOf(
            beat(t0 + 5_000, "S"), beat(t0 + 3_700_000, "V"), beat(t0 + 3_710_000, "S"), beat(t0 + 3_720_000, "N"),
            beat(t0 + 5_500_000, "V"), // in an unusable window: not counted
        )
        return AnalysisResult(emptyList(), beats, windows, emptyList(), emptyList())
    }

    @Test fun hourRowsTotalsAndSummaryFollowTheAgreedLayout() {
        val res = result()
        val marks = parseMarks(listOf(line("1", "Sitting", t0), line("2", "Walking", t0 + 3_600_000)))
        val report = renderDayReport(res, marks, actionSpans(marks, t0 + 7_200_000), "test")
        assertTrue("| Sitting (1:00) | HR | RMSSD | S | V | S+V burden |" in report, report)
        assertTrue("| total | Sitting | 70.0 | - | 1 | 0 | 100.00 % |" in report, report)
        assertTrue("| total | Walking | 70.0 | - | 1 | 1 | 66.67 % |" in report, report) // S, V, and an N: the noisy V is left out
        assertTrue("## Summary per action" in report && "## Whole recording" in report, report)
        assertTrue("| Walking | 1 | 30.0 min | 70.0 | - | 1 | 1 |" in report, report)
    }

    @Test fun withoutMarksOnlyTheRecordingStatisticsAreWritten() {
        val report = renderDayReport(result(), emptyList(), emptyList(), "test")
        assertTrue("No actions were marked" in report)
        assertTrue("## Whole recording" in report)
    }

    @Test fun ownerLabelsReplaceTheAutomaticClassAndTheLastRowWins() {
        val labels = parseOwnerLabels(
            listOf(
                "item,utcMs,kind,label,labeledAtUtc,note,bumpUtcMs",
                "B${t0 + 5_000},${t0 + 5_000},BEAT,S,2026-10-30T13:24:16.607Z,\"odd, note\",",
                "B${t0 + 5_000},${t0 + 5_000},BEAT,N,2026-10-30T13:25:00.000Z,,", // changed mind: N wins
                "B${t0 + 3_700_000},${t0 + 3_700_000},BEAT,?,2026-10-30T13:26:00.000Z,,", // unsure: not a class
                "G${t0 + 9},${t0 + 9},GAP,B,2026-10-30T13:27:00.000Z,,",           // gap item: not a beat
                "B${t0 + 3_710_000},${t0 + 3_710_000},BEAT,V,2026-10-30T13:28:00.000Z,,",
                "B${t0 + 3_710_000},${t0 + 3_710_000},BEAT,,2026-10-30T13:29:00.000Z,,",   // undone
            ),
        )
        assertTrue(labels == mapOf(t0 + 5_000 to "N"), labels.toString())

        val res = result() // beats: S at 5 s, V at 3700 s, S at 3710 s, N at 3720 s
        val marks = parseMarks(listOf(line("1", "Sitting", t0), line("2", "Walking", t0 + 3_600_000)))
        val fixed = renderDayReport(res, marks, actionSpans(marks, t0 + 7_200_000), "test", labels = mapOf(t0 + 3_700_000 to "N"))
        assertTrue("| total | Walking | 70.0 | - | 1 | 0 | 33.33 % |" in fixed, fixed) // the V was a normal beat: S 1, V 0, N 2
        assertTrue("Corrected with the owner's labels" in fixed, fixed)
    }
}
