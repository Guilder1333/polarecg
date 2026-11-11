package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.time.POLAR_EPOCH_UNIX_MS
import com.punkunpun.polarecg.dsp.time.ClockSeed
import com.punkunpun.polarecg.dsp.time.TimeMapper
import com.punkunpun.polarecg.dsp.time.clockSeedFrom
import com.punkunpun.polarecg.dsp.time.sampleUtcMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeMapperTest {

    private val t0 = 1_760_000_000_000L // some phone UTC ms

    /** Sensor ns for a phone-UTC instant, given the sensor clock is [skewMs] behind the phone. */
    private fun sensorNs(utcMs: Long, skewMs: Long) = (utcMs - skewMs - POLAR_EPOCH_UNIX_MS) * 1_000_000

    @Test
    fun beforeFirstObserveThereIsNoMapping() {
        assertNull(TimeMapper().toUtcMs(0))
    }

    @Test
    fun estimateUsesLeastDelayedFrame() {
        val m = TimeMapper()
        val skew = 5_000L
        // Frames arrive every 500 ms with delays 300, 50, 400 ms.
        listOf(300L, 50L, 400L).forEachIndexed { i, delay ->
            val trueMs = t0 + i * 500L
            m.observe(sensorNs(trueMs, skew), trueMs + delay)
        }
        assertEquals(skew + 50, m.offsetMs())
        // A sensor instant maps to true UTC + the smallest delay (50 ms): jitter cannot be removed.
        assertEquals(t0 + 50, m.toUtcMs(sensorNs(t0, skew)))
    }

    @Test
    fun slowDriftIsFollowedAndNotReportedAsJump() {
        val m = TimeMapper()
        var jumps = 0
        // 40 ppm drift for 10 minutes = 24 ms in total.
        for (i in 0 until 1_200) {
            val trueMs = t0 + i * 500L
            val drift = (i * 500L) * 40 / 1_000_000
            if (m.observe(sensorNs(trueMs, 1_000 - drift), trueMs + 20) != null) jumps++
        }
        assertEquals(0, jumps)
        val last = t0 + 1_199 * 500L
        assertEquals(last + 20, m.toUtcMs(sensorNs(last, 1_000 - 1_199 * 500L * 40 / 1_000_000)))
    }

    @Test
    fun phoneClockStepBackIsReportedImmediately() {
        val m = TimeMapper()
        for (i in 0 until 10) m.observe(sensorNs(t0 + i * 500L, 0), t0 + i * 500L)
        val at = t0 + 10 * 500L
        val jump = m.observe(sensorNs(at, 0), at - 3_000) // NTP stepped the phone back 3 s
        assertNotNull(jump)
        assertEquals(-3_000, jump.deltaMs)
        assertEquals(-3_000L, m.offsetMs())
    }

    @Test
    fun upwardChangeNeedsToPersist() {
        val m = TimeMapper()
        for (i in 0 until 10) m.observe(sensorNs(t0 + i * 500L, 0), t0 + i * 500L)
        // Two late frames (a delayed burst), then back to normal: no jump.
        var t = t0 + 10 * 500L
        assertNull(m.observe(sensorNs(t, 0), t + 4_000))
        t += 500
        assertNull(m.observe(sensorNs(t, 0), t + 4_000))
        t += 500
        assertNull(m.observe(sensorNs(t, 0), t))
        // Now a permanent +4 s shift: reported on the 5th consecutive frame.
        var reported = 0
        repeat(6) {
            t += 500
            if (m.observe(sensorNs(t, 0), t + 4_000) != null) reported++
        }
        assertEquals(1, reported)
        assertEquals(4_000L, m.offsetMs())
    }

    @Test
    fun newConnectionStartsFreshWithoutJump() {
        val m = TimeMapper()
        for (i in 0 until 10) m.observe(sensorNs(t0 + i * 500L, 0), t0 + i * 500L)
        m.startConnection()
        assertNull(m.toUtcMs(0))
        // Sensor clock restarted with a completely different offset: not a jump, a new connection.
        assertNull(m.observe(sensorNs(t0 + 60_000, 90_000), t0 + 60_000))
        assertEquals(1, m.connection)
        assertEquals(90_000L, m.offsetMs())
    }

    @Test
    fun sampleTimesFollowTheSampleRate() {
        assertEquals(t0, sampleUtcMs(t0, 0, 130))
        assertEquals(t0 + 1000, sampleUtcMs(t0, 130, 130))
        assertEquals(t0 + 7, sampleUtcMs(t0, 1, 130)) // 7.69 ms, truncated
    }

    @Test
    fun aSeedFromTheLastRunBeatsTheFirstDelayedFrame() {
        val skew = 5_000L
        val m = TimeMapper()
        m.seed(ClockSeed(t0 - 1_500, skew + 5)) // the previous run's best estimate: 5 ms of delay
        m.observe(sensorNs(t0, skew), t0 + 56) // the first frame after the restart arrives 56 ms late
        assertEquals(skew + 5, m.offsetMs()) // without the seed it would be skew + 56
    }

    @Test
    fun aSeedDoesNotHideARealClockChange() {
        val m = TimeMapper()
        m.seed(ClockSeed(t0 - 1_500, 5_000))
        val jump = m.observe(sensorNs(t0, 1_000), t0 + 10) // the offset dropped by 4 s: reported at once
        assertNotNull(jump)
    }

    @Test
    fun theSeedIsTheLastTimemapRowMovedOnAlongTheDriftAndOnlyIfRecent() {
        val lines = listOf(
            "connection,phoneUtcMs,sensorNs,offsetMs,kind",
            "0,${t0 - 90_000},1,111,start",
            "0,${t0 - 30_000},2,222000,periodic",
            "",
        )
        // 30 s old: 30 000 ms * -25e-6 = -0.75 ms, rounded; dated now, so it stays in the window with the new frames
        assertEquals(ClockSeed(t0, 222_000 - 1), clockSeedFrom(lines, t0))
        // 10 minutes later: -15 ms
        val later = clockSeedFrom(lines, t0 - 30_000 + 600_000)!!
        assertEquals(222_000 - 15L, later.offsetMs)
        // an hour: -90 ms; just over an hour: not used
        assertEquals(222_000 - 90L, clockSeedFrom(lines, t0 - 30_000 + 3_600_000)!!.offsetMs)
        assertNull(clockSeedFrom(lines, t0 - 30_000 + 3_600_001))
        assertNull(clockSeedFrom(lines, t0 - 40_000)) // the phone clock went back
        assertNull(clockSeedFrom(lines.take(1), t0)) // header only
        assertNull(clockSeedFrom(listOf("0,abc,2,x,start"), t0)) // damaged
    }

    @Test
    fun aSeedFromAnHourAgoStillBeatsTheFirstDelayedFrame() {
        val skew = 5_000L
        val m = TimeMapper()
        // the previous run ended 40 minutes ago with offset skew + 5; the strap's clock has gained 60 ms since (-25 ppm)
        val seed = clockSeedFrom(listOf("h", "0,${t0 - 2_400_000},2,${skew + 5},periodic"), t0)!!
        m.seed(seed)
        m.observe(sensorNs(t0, skew - 60), t0 + 56) // true offset now skew - 55 (+56 ms of delay on this frame): seed skew - 55
        assertEquals(skew - 55, m.offsetMs())
    }
}
