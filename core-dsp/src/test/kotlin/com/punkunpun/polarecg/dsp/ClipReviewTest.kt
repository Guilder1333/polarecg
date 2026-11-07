package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.event.eventTitle
import com.punkunpun.polarecg.dsp.event.nearestBeat
import com.punkunpun.polarecg.dsp.event.neighbourIntervals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipReviewTest {
    private fun beat(t: Long) = ClipBeat(t, "N", good = true, vDoubt = false, rescued = false)
    private val beats = listOf(beat(1_000), beat(1_800), beat(2_600))

    @Test fun aTapPicksTheNearestBeat() {
        assertEquals(1_800L, nearestBeat(beats, 1_700, 300)?.utcMs)
        assertEquals(2_600L, nearestBeat(beats, 2_300, 400)?.utcMs)
    }

    @Test fun aTapFarFromEveryBeatPicksNone() {
        assertNull(nearestBeat(beats, 5_000, 300))
        assertNull(nearestBeat(emptyList(), 1_000, 300))
    }

    @Test fun eventNamesAreReadable() {
        assertEquals("V couplet", eventTitle("V_COUPLET"))
        assertEquals("S bigeminy", eventTitle("S_BIGEMINY"))
        assertEquals("Pause", eventTitle("PAUSE"))
        assertEquals("SOMETHING_ELSE_NEW", eventTitle("SOMETHING_ELSE_NEW"))
    }

    @Test fun intervalsAroundABeatAreReadFromTheNeighbours() {
        assertEquals(800L to 800L, neighbourIntervals(beats, 1))
        assertEquals(null to 800L, neighbourIntervals(beats, 0))
        assertEquals(800L to null, neighbourIntervals(beats, 2))
        assertEquals(null to null, neighbourIntervals(listOf(beat(5)), 0))
    }
}
