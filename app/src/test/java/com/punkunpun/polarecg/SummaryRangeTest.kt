package com.punkunpun.polarecg

import com.punkunpun.polarecg.summary.CompareMode
import com.punkunpun.polarecg.summary.SummaryRange
import com.punkunpun.polarecg.summary.compareBounds
import com.punkunpun.polarecg.summary.rangeBounds
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryRangeTest {
    private val jst = ZoneId.of("Asia/Tokyo")
    private fun ms(text: String) = ZonedDateTime.parse("$text[Asia/Tokyo]").toInstant().toEpochMilli()

    @Test fun todayStartsAtLocalMidnightAndLast24hIsADay() {
        val now = ms("2026-10-31T14:30:00+09:00")
        assertEquals(ms("2026-10-31T00:00:00+09:00") to now, rangeBounds(SummaryRange.TODAY, now, jst))
        assertEquals((now - 86_400_000L) to now, rangeBounds(SummaryRange.LAST_24H, now, jst))
    }

    @Test fun lastNightIsTenToEightAndStopsAtNowWhileItIsRunning() {
        val morning = ms("2026-10-31T09:15:00+09:00")
        assertEquals(ms("2026-10-30T22:00:00+09:00") to ms("2026-10-31T08:00:00+09:00"), rangeBounds(SummaryRange.LAST_NIGHT, morning, jst))
        val inTheNight = ms("2026-10-31T03:00:00+09:00")
        assertEquals(ms("2026-10-30T22:00:00+09:00") to inTheNight, rangeBounds(SummaryRange.LAST_NIGHT, inTheNight, jst))
    }

    @Test fun todayIsComparedWithYesterdayUpToTheSameTimeOfDay() {
        val now = ms("2026-10-31T14:30:00+09:00")
        val b = compareBounds(CompareMode.TODAY_YESTERDAY, now, jst)
        assertEquals(ms("2026-10-31T00:00:00+09:00") to now, b.current)
        assertEquals(ms("2026-10-30T00:00:00+09:00") to ms("2026-10-30T14:30:00+09:00"), b.previous)
    }

    @Test fun the24HoursAreComparedWithTheDayBeforeThem() {
        val now = ms("2026-10-31T14:30:00+09:00")
        val b = compareBounds(CompareMode.LAST_24H, now, jst)
        assertEquals((now - 86_400_000L) to now, b.current)
        assertEquals((now - 172_800_000L) to (now - 86_400_000L), b.previous)
    }
}
