package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.event.ClipBeat
import com.punkunpun.polarisecg.dsp.event.eventTitle
import com.punkunpun.polarisecg.dsp.event.nearestBeat
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
}
