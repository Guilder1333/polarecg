package com.punkunpun.polarisecg.log

import java.io.File
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
            LogType.CONNECTING, LogType.CONNECTED -> Unit
        }
        lastTimeMs = e.timeMs
    }
    if (openStart >= 0) gaps += Gap(openStart, null, openReason)
    return gaps
}

fun LogEvent.toLine(): String =
    "$timeMs\t$type\t${(detail ?: "").replace('\t', ' ').replace('\n', ' ')}"

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

    @Synchronized
    fun log(type: LogType, detail: String? = null, timeMs: Long = System.currentTimeMillis()) {
        val event = LogEvent(timeMs, type, detail)
        val updated = (_events.value + event).takeLast(maxEvents)
        _events.value = updated
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
