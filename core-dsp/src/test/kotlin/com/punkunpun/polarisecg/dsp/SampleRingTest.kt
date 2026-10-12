package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.live.SampleRing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SampleRingTest {
    @Test
    fun partiallyFilledKeepsOrder() {
        val r = SampleRing(5)
        r.add(intArrayOf(1, 2, 3))
        assertContentEquals(intArrayOf(1, 2, 3), r.snapshot())
    }

    @Test
    fun overflowDropsOldest() {
        val r = SampleRing(4)
        r.add(intArrayOf(1, 2, 3))
        r.add(intArrayOf(4, 5, 6))
        assertContentEquals(intArrayOf(3, 4, 5, 6), r.snapshot())
        assertEquals(4, r.size)
    }

    @Test
    fun batchLargerThanCapacity() {
        val r = SampleRing(3)
        r.add(intArrayOf(1, 2, 3, 4, 5, 6, 7))
        assertContentEquals(intArrayOf(5, 6, 7), r.snapshot())
    }

    @Test
    fun clearEmpties() {
        val r = SampleRing(3)
        r.add(intArrayOf(1, 2))
        r.clear()
        assertEquals(0, r.snapshot().size)
    }
}
