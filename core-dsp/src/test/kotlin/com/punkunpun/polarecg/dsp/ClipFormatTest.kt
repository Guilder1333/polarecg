package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.event.ClipFormat
import com.punkunpun.polarecg.dsp.event.EventClip
import com.punkunpun.polarecg.dsp.live.EcgClip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipFormatTest {
    private val t0 = 1_791_843_330_123L

    private fun clip() = EventClip(
        EcgClip(t0, 7.6745, IntArray(2600) { (it * 37) % 4001 - 2000 }),
        listOf(
            ClipBeat(t0 + 500, "N", good = true, vDoubt = false, rescued = false),
            ClipBeat(t0 + 1400, "V", good = true, vDoubt = true, rescued = false),
            ClipBeat(t0 + 2100, "Q", good = false, vDoubt = false, rescued = true),
        ),
        eventStartUtcMs = t0 + 10_000,
        eventEndUtcMs = t0 + 11_500,
    )

    @Test
    fun roundTrip() {
        val c = clip()
        val back = assertNotNull(ClipFormat.decode(ClipFormat.encode(c)))
        assertEquals(c.ecg.startUtcMs, back.ecg.startUtcMs)
        assertEquals(c.ecg.msPerSample, back.ecg.msPerSample)
        assertContentEquals(c.ecg.samplesUv, back.ecg.samplesUv)
        assertEquals(c.beats, back.beats)
        assertEquals(c.eventStartUtcMs, back.eventStartUtcMs)
        assertEquals(c.eventEndUtcMs, back.eventEndUtcMs)
    }

    @Test
    fun twentySecondsAreAboutTenKilobytes() {
        assertEquals(48 + 4 * 2600 + 8 * 3, ClipFormat.encode(clip()).size)
    }

    @Test
    fun garbageAndCutOffFilesAreNotClips() {
        val bytes = ClipFormat.encode(clip())
        assertNull(ClipFormat.decode(bytes.copyOf(bytes.size - 1)), "cut short")
        assertNull(ClipFormat.decode(ByteArray(100)), "wrong magic")
        assertNull(ClipFormat.decode(ByteArray(10)), "too small")
    }
}
