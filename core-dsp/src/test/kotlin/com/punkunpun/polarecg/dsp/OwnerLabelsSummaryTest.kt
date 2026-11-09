package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.summary.LabelledBeat
import com.punkunpun.polarecg.dsp.summary.MinuteSummary
import com.punkunpun.polarecg.dsp.summary.applyOwnerLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class OwnerLabelsSummaryTest {
    private val t0 = 1_793_300_400_000L
    private fun minute(i: Int) = MinuteSummary(t0 + i * 60_000L, 72, 68, 2, 2, 0, 1, 70.0, 60.0, 80.0, 1.0, null)

    @Test fun relabelMovesTheBeatBetweenClassesInItsMinute() {
        val minutes = listOf(minute(0), minute(1))
        val r = applyOwnerLabels(
            minutes,
            listOf(
                LabelledBeat(t0 + 61_000, "V", true, "N"), // doubtful V is really N
                LabelledBeat(t0 + 62_000, "N", false, "S"),
            ),
        )
        assertEquals(listOf(68, 2, 2, 0, 1), listOf(r[0].n, r[0].s, r[0].v, r[0].q, r[0].vDoubt)) // first minute untouched
        assertEquals(listOf(68, 3, 1, 0, 0), listOf(r[1].n, r[1].s, r[1].v, r[1].q, r[1].vDoubt)) // N: -1+1, S +1, V -1
        assertEquals(72, r[1].beats)
        assertEquals(70.0, r[1].hrMeanBpm)
    }

    @Test fun notABeatLeavesTheCountsAndTheBeatTotal() {
        val m = MinuteSummary(t0, 72, 67, 2, 2, 1, 1, 70.0, 60.0, 80.0, 1.0, null) // 1 Q, 2 V of which 1 doubtful
        val r = applyOwnerLabels(listOf(m), listOf(LabelledBeat(t0 + 1_000, "Q", false, "X"), LabelledBeat(t0 + 2_000, "V", true, "X")))[0]
        assertEquals(listOf(70, 67, 2, 1, 0, 0), listOf(r.beats, r.n, r.s, r.v, r.q, r.vDoubt))
    }

    @Test fun unsureUndoneSameClassAndUnknownMinuteChangeNothing() {
        val minutes = listOf(minute(0))
        val labelled = listOf(
            LabelledBeat(t0 + 1_000, "V", false, "?"),
            LabelledBeat(t0 + 2_000, "V", false, ""),
            LabelledBeat(t0 + 3_000, "N", false, "N"),
            LabelledBeat(t0 + 99 * 60_000L, "N", false, "V"),
        )
        assertSame(minutes[0].n, applyOwnerLabels(minutes, labelled)[0].n)
        assertEquals(2, applyOwnerLabels(minutes, labelled)[0].v)
    }
}
