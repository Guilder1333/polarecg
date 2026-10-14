package com.punkunpun.polarisecg.log

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Plain Kotlin/JVM on purpose (no Android imports) so it can be unit-tested on the PC.
 *
 * The log is an append-only text file, one event per line: `timeMs<TAB>TYPE<TAB>detail`.
 * A Room table (P5.3) may replace it later; for now a file is the simplest thing that survives
 * the process being killed, which is exactly the case we want to see.
 */

enum class LogType {
    /** The app process started (also happens after Android killed it). */
    APP_START,

    /** Trying to connect to the strap. */
    CONNECTING,

    /** BLE link is up, streams not yet running. */
    CONNECTED,

    /** ECG/ACC/HR streams are running: data is flowing. */
    STREAMING,

    /** The link dropped. */
    DISCONNECTED,

    /** A data stream failed while the link was up. */
    STREAM_ERROR,

    /** The user (or the service) ended the session on purpose. */
    STOPPED,

    /** Sensor clock and phone clock suddenly disagree differently than before (P1.8). Not a gap. */
    CLOCK_JUMP,
}

data class LogEvent(val timeMs: Long, val type: LogType, val detail: String? = null)

/** A period without data. [endMs] is null while the gap is still ongoing. */
data class Gap(val startMs: Long, val endMs: Long?, val reason: String) {
    fun durationMs(nowMs: Long): Long = (endMs ?: nowMs) - startMs
}

/**
 * Derives the data gaps from the event list: a gap starts when data was flowing (STREAMING) and
 * then the link or a stream failed, and ends at the next STREAMING. If the app is restarted while
 * data was flowing, the process was killed: the gap starts at the last event we saw before that
 * (a lower bound; the real loss may have begun a little later).
 * A deliberate STOPPED closes an open gap and starts no new one.
 */
fun computeGaps(events: List<LogEvent>): List<Gap> {
    val gaps = mutableListOf<Gap>()
    var streaming = false
    var lastTimeMs = 0L
    var openStart = -1L
    var openReason = ""

    fun open(startMs: Long, reason: String) {
        if (openStart < 0) {
            openStart = startMs
            openReason = reason
        }
    }

    fun close(endMs: Long) {
        if (openStart >= 0) gaps += Gap(openStart, endMs, openReason)
        openStart = -1
    }

    for (e in events) {
        when (e.type) {
            LogType.STREAMING -> {
                close(e.timeMs)
                streaming = true
            }
            LogType.DISCONNECTED -> {
                if (streaming) open(e.timeMs, "Disconnected" + (e.detail?.let { ": $it" } ?: ""))
                streaming = false
            }
            LogType.STREAM_ERROR -> {
                if (streaming) open(e.timeMs, "Stream error" + (e.detail?.let { ": $it" } ?: ""))
                streaming = false
            }
            LogType.APP_START -> {
                if (streaming) open(lastTimeMs, "App was killed / restarted")
                streaming = false
            }
            LogType.STOPPED -> {
                close(e.timeMs)
                streaming = false
            }
            LogType.CONNECTING, LogType.CONNECTED, LogType.CLOCK_JUMP -> Unit
        }
        lastTimeMs = e.timeMs
    }
    if (openStart >= 0) gaps += Gap(openStart, null, openReason)
    return gaps
}

/** Detail text of the CONNECTING event that PolarConnection.connect() logs: the start of a session. */
const val SESSION_START_DETAIL = "connect"

/**
 * The events of the most recent session (from its first CONNECTING on). The log file holds the
 * whole history; screens that talk about "now" should not count gaps from earlier days.
 * If no session start is found (old log), everything is returned.
 */
fun eventsOfLatestSession(events: List<LogEvent>): List<LogEvent> {
    val start = events.indexOfLast { it.type == LogType.CONNECTING && it.detail == SESSION_START_DETAIL }
    return if (start < 0) events else events.subList(start, events.size)
}

fun LogEvent.toLine(): String =
    "$timeMs\t$type\t${(detail ?: "").replace('\t', ' ').replace('\n', ' ')}"

/** All human-readable times are Japan Standard Time (UTC+9, no daylight saving). */
val JST: ZoneId = ZoneId.of("Asia/Tokyo")

private val JST_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(JST)

fun formatJst(timeMs: Long): String = JST_FORMAT.format(Instant.ofEpochMilli(timeMs))

/** `2026-10-14 21:05:33 JST<TAB>timeMs<TAB>TYPE<TAB>detail` - readable first, epoch ms kept exact. */
fun LogEvent.toSessionLine(): String = "${formatJst(timeMs)} JST\t${toLine()}"

private fun parseSessionLine(line: String): LogEvent? = parseLogLine(line.substringAfter('\t', ""))

fun parseLogLine(line: String): LogEvent? {
    val parts = line.split('\t', limit = 3)
    if (parts.size < 2) return null
    val time = parts[0].toLongOrNull() ?: return null
    val type = LogType.entries.firstOrNull { it.name == parts[1] } ?: return null
    return LogEvent(time, type, parts.getOrNull(2)?.ifEmpty { null })
}

/**
 * Thread-safe event log backed by [file]. [events] holds the newest [maxEvents] events for the UI.
 * Writing never throws: failing to log must not crash the recording.
 */
class ConnectionLog(private val file: File, private val maxEvents: Int = 2000) {

    private val _events = MutableStateFlow(load())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    // Copy of the events for the running session, in the session folder (see attachSession).
    private var sessionFile: File? = null

    /**
     * Starts mirroring events into [file] (`connection.log` in the session folder), so the log
     * can be matched to that session. Events logged before the recorder started (CONNECTING,
     * APP_START after a kill) are copied in first: for a new file the latest session's events,
     * for an existing file (resume) everything newer than its last line.
     */
    @Synchronized
    fun attachSession(file: File) {
        sessionFile = file
        try {
            val lastMs = if (file.exists()) {
                file.readLines().mapNotNull(::parseSessionLine).maxOfOrNull { it.timeMs } ?: 0L
            } else {
                -1L
            }
            val missed = if (lastMs < 0) eventsOfLatestSession(_events.value) else _events.value.filter { it.timeMs > lastMs }
            if (missed.isNotEmpty()) file.appendText(missed.joinToString("") { it.toSessionLine() + "\n" })
        } catch (_: Exception) {
            // Session log is a convenience copy; never fail the recording because of it.
        }
    }

    @Synchronized
    fun detachSession() {
        sessionFile = null
    }

    @Synchronized
    fun log(type: LogType, detail: String? = null, timeMs: Long = System.currentTimeMillis()) {
        val event = LogEvent(timeMs, type, detail)
        val updated = (_events.value + event).takeLast(maxEvents)
        _events.value = updated
        try {
            sessionFile?.appendText(event.toSessionLine() + "\n")
        } catch (_: Exception) {
        }
        try {
            if (file.length() > MAX_FILE_BYTES) {
                // Keep the file bounded: rewrite it with just the events we hold in memory.
                file.writeText(updated.joinToString("\n", postfix = "\n") { it.toLine() })
            } else {
                file.appendText(event.toLine() + "\n")
            }
        } catch (_: Exception) {
            // Disk full / storage error: keep going, the in-memory list is still correct.
        }
    }

    private fun load(): List<LogEvent> = try {
        if (file.exists()) file.readLines().mapNotNull(::parseLogLine).takeLast(maxEvents) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
