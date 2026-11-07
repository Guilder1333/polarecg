package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.summary.niceTicks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartScaleTest {
    @Test fun heartRateScaleGetsRoundNumbers() {
        assertEquals(listOf(60.0, 80.0, 100.0, 120.0), niceTicks(50.0, 120.0)) // rough step 17.5 -> 20, lines at multiples of it
        assertEquals(listOf(40.0, 60.0, 80.0, 100.0), niceTicks(40.0, 100.0))
    }

    @Test fun smallRangesGetFinerSteps() {
        assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0), niceTicks(0.0, 2.0))
    }

    @Test fun ticksStayInsideTheRangeAndAreFew() {
        for ((lo, hi) in listOf(0.0 to 1.0, 0.0 to 37.3, 42.0 to 133.0, 0.0 to 0.04, 0.0 to 1234.0)) {
            val t = niceTicks(lo, hi)
            assertTrue(t.size in 2..6, "$lo..$hi gave $t")
            assertTrue(t.all { it >= lo - 1e-9 && it <= hi + 1e-9 }, "$lo..$hi gave $t")
        }
    }

    @Test fun anEmptyRangeHasNoTicks() {
        assertEquals(emptyList(), niceTicks(5.0, 5.0))
    }
}
