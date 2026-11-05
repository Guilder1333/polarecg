package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.event.ClipPolicy
import com.punkunpun.polarisecg.dsp.event.ClipPolicyConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipPolicyTest {
    private val t0 = 1_791_843_330_000L
    private val minute = 60_000L

    @Test
    fun theFirstFewAreAlwaysKept() {
        val p = ClipPolicy()
        assertTrue(p.allow("V_COUPLET", t0))
        assertTrue(p.allow("V_COUPLET", t0 + 1_000))
        assertTrue(p.allow("V_COUPLET", t0 + 2_000))
        assertFalse(p.allow("V_COUPLET", t0 + 3_000), "the fourth in a few seconds")
    }

    @Test
    fun thenOneEveryTenMinutes() {
        val p = ClipPolicy()
        repeat(3) { assertTrue(p.allow("S_COUPLET", t0 + it * 1_000L)) }
        var saved = 3
        for (m in 1..120) if (p.allow("S_COUPLET", t0 + m * minute)) saved++ // a couplet every minute for two hours
        // 3 at the start, then about one per 10 minutes: far from 120
        assertTrue(saved in 12..20, "saved $saved")
    }

    @Test
    fun eachTypeHasItsOwnAllowance() {
        val p = ClipPolicy()
        repeat(50) { p.allow("S_COUPLET", t0 + it * 1_000L) }
        assertTrue(p.allow("V_RUN", t0 + 60_000), "a flood of S couplets must not take the room of a V run")
    }

    @Test
    fun theHourOfTheFirstFewMovesOn() {
        val p = ClipPolicy()
        repeat(3) { assertTrue(p.allow("V_TRIPLET", t0 + it * 1_000L)) }
        assertFalse(p.allow("V_TRIPLET", t0 + 5 * minute))
        // two hours later the first few are available again
        repeat(3) { assertTrue(p.allow("V_TRIPLET", t0 + 120 * minute + it * 1_000L)) }
    }

    @Test
    fun aDayHasAHardCap() {
        val p = ClipPolicy(ClipPolicyConfig(maxPerDay = 5))
        var saved = 0
        for (i in 0 until 50) if (p.allow("T$i", t0 + i * 1_000L)) saved++ // 50 different types: each would pass on its own
        assertEquals(5, saved)
        assertTrue(p.allow("T1", t0 + 2 * 86_400_000L), "the next day starts afresh")
    }
}
