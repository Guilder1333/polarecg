package com.punkunpun.polarisecg

import com.punkunpun.polarisecg.log.ConnectionLog
import com.punkunpun.polarisecg.log.Gap
import com.punkunpun.polarisecg.log.LogEvent
import com.punkunpun.polarisecg.log.LogType
import com.punkunpun.polarisecg.log.computeGaps
import com.punkunpun.polarisecg.log.parseLogLine
import com.punkunpun.polarisecg.log.toLine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionLogTest {

    private fun e(t: Long, type: LogType, d: String? = null) = LogEvent(t, type, d)

    @Test
    fun disconnectThenStreamingIsOneGap() {
        val gaps = computeGaps(
            listOf(
                e(0, LogType.APP_START), e(1, LogType.STREAMING),
                e(100, LogType.DISCONNECTED, "LINK_LOSS"), e(102, LogType.CONNECTING),
                e(500, LogType.CONNECTED), e(510, LogType.STREAMING),
            ),
        )
        assertEquals(listOf(Gap(100, 510, "Disconnected: LINK_LOSS")), gaps)
    }

    @Test
    fun repeatedFailuresDuringOneGapStayOneGap() {
        val gaps = computeGaps(
            listOf(
                e(0, LogType.STREAMING), e(10, LogType.DISCONNECTED), e(20, LogType.CONNECTING),
                e(30, LogType.DISCONNECTED), e(40, LogType.CONNECTING), e(50, LogType.STREAMING),
            ),
        )
        assertEquals(1, gaps.size)
        assertEquals(10L, gaps[0].startMs)
        assertEquals(50L, gaps[0].endMs)
    }

    @Test
    fun appRestartWhileStreamingIsAGapFromLastEvent() {
        val gaps = computeGaps(
            listOf(e(0, LogType.STREAMING), e(60, LogType.CONNECTED), e(900, LogType.APP_START), e(950, LogType.STREAMING)),
        )
        assertEquals(listOf(Gap(60, 950, "App was killed / restarted")), gaps)
    }

    @Test
    fun deliberateStopIsNotAGap() {
        val gaps = computeGaps(listOf(e(0, LogType.STREAMING), e(50, LogType.STOPPED), e(90, LogType.APP_START)))
        assertEquals(emptyList<Gap>(), gaps)
    }

    @Test
    fun ongoingGapHasNoEnd() {
        val gaps = computeGaps(listOf(e(0, LogType.STREAMING), e(50, LogType.STREAM_ERROR, "ECG")))
        assertNull(gaps.single().endMs)
    }

    @Test
    fun connectingBeforeFirstDataIsNotAGap() {
        val gaps = computeGaps(listOf(e(0, LogType.CONNECTING), e(5, LogType.DISCONNECTED), e(9, LogType.STREAMING)))
        assertEquals(emptyList<Gap>(), gaps)
    }

    @Test
    fun lineRoundTrip() {
        val ev = e(123, LogType.DISCONNECTED, "a\tb\nc")
        assertEquals(LogEvent(123, LogType.DISCONNECTED, "a b c"), parseLogLine(ev.toLine()))
        assertEquals(LogEvent(5, LogType.STOPPED, null), parseLogLine(e(5, LogType.STOPPED).toLine()))
    }

    @Test
    fun logPersistsAcrossInstances() {
        val file = File.createTempFile("connlog", ".tsv")
        try {
            ConnectionLog(file).log(LogType.APP_START, timeMs = 1)
            ConnectionLog(file).log(LogType.STREAMING, timeMs = 2)
            val reloaded = ConnectionLog(file)
            assertEquals(listOf(LogType.APP_START, LogType.STREAMING), reloaded.events.value.map { it.type })
        } finally {
            file.delete()
        }
    }
}
