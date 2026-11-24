package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.workout.DistanceTracker
import com.punkunpun.polarecg.dsp.workout.HrStats
import com.punkunpun.polarecg.dsp.workout.haversineM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutStatsTest {
    // 0.001 degrees of latitude is about 111.2 m everywhere
    private val dLat = 0.001

    @Test
    fun haversineOneThousandthDegreeOfLatitude() {
        assertEquals(111.19, haversineM(35.0, 139.0, 35.0 + dLat, 139.0), 0.1)
        assertEquals(0.0, haversineM(35.0, 139.0, 35.0, 139.0), 1e-9)
    }

    @Test
    fun ridingNorthAddsUpTheSteps() {
        val t = DistanceTracker()
        // first fix is only the starting point
        assertFalse(t.add(35.0, 139.0, 5.0, 0))
        for (i in 1..10) assertTrue(t.add(35.0 + i * dLat, 139.0, 5.0, i * 10_000L)) // 111 m in 10 s = 11 m/s
        assertEquals(1111.9, t.distanceM, 1.0)
        assertEquals(10, t.acceptedFixes)
    }

    @Test
    fun standingStillWithJitterAddsNothing() {
        val t = DistanceTracker()
        t.add(35.0, 139.0, 8.0, 0)
        // wobble of about 1-2 m (0.00001 degree = 1.1 m) every 2 s
        for (i in 1..50) t.add(35.0 + (if (i % 2 == 0) 0.00001 else -0.00001), 139.0, 8.0, i * 2_000L)
        assertEquals(0.0, t.distanceM, 1e-9)
    }

    @Test
    fun slowWalkingAccumulatesAgainstTheAnchor() {
        val t = DistanceTracker()
        t.add(35.0, 139.0, 3.0, 0)
        // 1 m per 2 s: each step alone is under the 3 m limit, but the anchor stays until 3 m are covered
        for (i in 1..30) t.add(35.0 + i * 0.0000090, 139.0, 3.0, i * 2_000L)
        assertTrue(t.distanceM in 25.0..31.0, "was ${t.distanceM}")
    }

    @Test
    fun poorFixesAndJumpsAreIgnored() {
        val t = DistanceTracker()
        t.add(35.0, 139.0, 5.0, 0)
        assertFalse(t.add(35.01, 139.0, 60.0, 5_000)) // accuracy 60 m
        assertFalse(t.add(35.01, 139.0, 5.0, 5_000)) // 1.1 km in 5 s = 222 m/s
        assertEquals(0.0, t.distanceM, 1e-9)
        assertTrue(t.add(35.0 + dLat, 139.0, 5.0, 10_000)) // the next real fix still counts from the old anchor
        assertEquals(111.2, t.distanceM, 0.5)
    }

    @Test
    fun hrStatsMeanAndMax() {
        val h = HrStats()
        assertNull(h.mean)
        listOf(100, 120, 0, 140).forEach { h.add(it) }
        assertEquals(120.0, h.mean!!, 1e-9)
        assertEquals(140, h.max)
        assertEquals(3, h.count)
    }
}
