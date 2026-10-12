package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.polar.ReconnectBackoff
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun delaysGrowThenStayAtCap() {
        val b = ReconnectBackoff()
        val seen = List(7) { b.next() }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 30_000L, 30_000L, 30_000L), seen)
    }

    @Test
    fun resetStartsOver() {
        val b = ReconnectBackoff()
        repeat(5) { b.next() }
        b.reset()
        assertEquals(2_000L, b.next())
    }
}
