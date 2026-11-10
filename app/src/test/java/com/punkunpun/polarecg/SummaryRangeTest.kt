package com.punkunpun.polarecg

import com.punkunpun.polarecg.summary.CompareMode
import com.punkunpun.polarecg.summary.SummaryRange
import com.punkunpun.polarecg.summary.compareBounds
import com.punkunpun.polarecg.summary.compareDays
import com.punkunpun.polarecg.summary.dayBounds
import com.punkunpun.polarecg.summary.daysWithData
import com.punkunpun.polarecg.summary.pickerDate
import com.punkunpun.polarecg.summary.rangeBounds
import java.time.LocalDate
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

    @Test fun aPickedDayIsOneLocalDayAndTodayStopsAtNow() {
        val past = LocalDate.of(2026, 10, 28)
        assertEquals(ms("2026-10-28T00:00:00+09:00") to ms("2026-10-29T00:00:00+09:00"), dayBounds(past, ms("2026-10-31T14:30:00+09:00"), jst))
        val today = LocalDate.of(2026, 10, 31)
        val now = ms("2026-10-31T14:30:00+09:00")
        assertEquals(ms("2026-10-31T00:00:00+09:00") to now, dayBounds(today, now, jst))
    }

    @Test fun daysWithDataFollowTheLocalCalendarAndThePickerIsUtc() {
        // 23:50 JST on the 28th is 14:50 UTC the same day; 00:10 JST on the 29th is 15:10 UTC on the 28th
        val q = { iso: String -> ms(iso) / 900_000L }
        val days = daysWithData(listOf(q("2026-10-28T23:50:00+09:00"), q("2026-10-29T00:10:00+09:00")), jst)
        assertEquals(setOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29)), days)
        // the picker hands over UTC midnight of the chosen date
        assertEquals(LocalDate.of(2026, 10, 29), pickerDate(ZonedDateTime.parse("2026-10-29T00:00:00Z").toInstant().toEpochMilli()))
    }

    @Test fun twoPickedDaysAreWholeDaysUnlessOneIsStillRunning() {
        val now = ms("2026-10-31T14:30:00+09:00")
        val full = compareDays(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 27), now, jst)
        assertEquals(ms("2026-10-28T00:00:00+09:00") to ms("2026-10-29T00:00:00+09:00"), full.current)
        assertEquals(ms("2026-10-27T00:00:00+09:00") to ms("2026-10-28T00:00:00+09:00"), full.previous)
        // today against an older day: both stop at the same time of day
        val b = compareDays(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 25), now, jst)
        assertEquals(ms("2026-10-31T00:00:00+09:00") to now, b.current)
        assertEquals(ms("2026-10-25T00:00:00+09:00") to ms("2026-10-25T14:30:00+09:00"), b.previous)
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
