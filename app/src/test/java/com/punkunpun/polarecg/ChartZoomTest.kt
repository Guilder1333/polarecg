package com.punkunpun.polarecg

import com.punkunpun.polarecg.summary.labelSpacingMs
import com.punkunpun.polarecg.summary.stepFor
import com.punkunpun.polarecg.summary.zoomed
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartZoomTest {
    private val h = 3_600_000L
    private val m = 60_000L
    private val total = 0L..(24 * h)

    @Test fun theBarGetsFinerAsYouZoomIn() {
        assertEquals(60 * m, stepFor(24 * h))
        assertEquals(30 * m, stepFor(12 * h))
        assertEquals(15 * m, stepFor(6 * h))
        assertEquals(10 * m, stepFor(4 * h))
        assertEquals(5 * m, stepFor(2 * h))
        assertEquals(5 * m, stepFor(30 * m))
    }

    @Test fun labelsStayFewAndRound() {
        assertEquals(3 * h, labelSpacingMs(24 * h))
        assertEquals(30 * m, labelSpacingMs(4 * h))
        assertEquals(5 * m, labelSpacingMs(30 * m))
    }

    @Test fun zoomingKeepsThePointUnderTheFingers() {
        // focus at the middle of the whole day: the middle stays at 12:00
        val (start, duration) = zoomed(0, 24 * h, total, 2f, 0.5f, 0f, 30 * m)
        assertEquals(12 * h, duration)
        assertEquals(6 * h, start)
    }

    @Test fun neverOutsideTheDataOrShorterThanTheLimit() {
        val (s1, d1) = zoomed(0, 24 * h, total, 0.5f, 0.5f, 0f, 30 * m) // zoom out of everything: stays everything
        assertEquals(0L to 24 * h, s1 to d1)
        val (s2, d2) = zoomed(0, 2 * h, total, 1000f, 0f, 0f, 30 * m)
        assertEquals(30 * m, d2)
        assertEquals(0L, s2)
        val (s3, _) = zoomed(20 * h, 4 * h, total, 1f, 0f, -1f, 30 * m) // dragged left past the end
        assertEquals(20 * h, s3)
        val (s4, _) = zoomed(h, 4 * h, total, 1f, 0f, 1f, 30 * m) // dragged right past the start
        assertEquals(0L, s4)
    }
}
