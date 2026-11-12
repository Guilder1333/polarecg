package com.punkunpun.polarecg.annotations

import com.punkunpun.polarecg.dsp.annotations.parseMarks
import com.punkunpun.polarecg.recorder.SessionFolders
import java.io.File
import java.time.Instant

private val ACTION_ICONS = mapOf(
    "Sitting" to "🪑", "Standing" to "🧍", "Lying down" to "🛏️", "Walking" to "🚶",
    "Running" to "🏃", "Workout" to "🏋️", "Eating" to "🍽️", "Sleeping" to "😴",
)
private val EVENT_ICONS = mapOf(
    "Dizzy" to "😵", "Stress" to "😰", "Palpitations" to "💓", "Chest discomfort" to "😣",
    "Coffee" to "☕", "Felt tired" to "🥱",
)

/** A name the owner added gets a general icon of its kind. */
private const val OTHER_ACTION_ICON = "🔹"
private const val OTHER_EVENT_ICON = "📍"

/** The emoji for an action or event name (the presets have their own; any other name gets the icon of its kind). */
fun markIcon(kind: AnnotationKind, name: String?): String = when (kind) {
    AnnotationKind.ACTION -> ACTION_ICONS[name] ?: OTHER_ACTION_ICON
    AnnotationKind.EVENT -> EVENT_ICONS[name] ?: OTHER_EVENT_ICON
}

/** The first symbol (one emoji, with its modifiers) of [text], or null if it is blank. */
fun firstSymbol(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    return t.substring(0, it.next())
}

/** An action as it is drawn: a region of time. [dir] is the session folder its record is in, [id] its id there. */
class ActionBand(val id: String, val dir: File, val name: String, val startMs: Long, val endMs: Long)

/** An event as it is drawn: a moment. */
class EventPoint(val id: String, val dir: File, val name: String?, val timeMs: Long)

/** The actions and events that touch a stretch of time. */
class RangeMarks(val actions: List<ActionBand>, val events: List<EventPoint>)

/**
 * Reads `annotations.jsonl` of every session folder and returns the actions and events that fall in [fromMs, toMs).
 * An action runs until the next one starts; the last one of a session until the session's `endUtc`, or, for a session that
 * was not stopped, until now (the newest session, still recording) or the last time its log was written (an old one).
 * Blocking file work: call from `Dispatchers.IO`.
 */
fun loadRangeMarks(sessionsDir: File, fromMs: Long, toMs: Long, nowMs: Long): RangeMarks {
    val dirs = sessionsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
    val actions = ArrayList<ActionBand>()
    val events = ArrayList<EventPoint>()
    for ((i, dir) in dirs.withIndex()) {
        val log = File(dir, ANNOTATIONS_FILE).takeIf { it.isFile } ?: continue
        val marks = try { parseMarks(log.readLines()) } catch (e: Exception) { continue }
        val endUtc = SessionFolders.readMeta(dir)?.optString("endUtc")?.takeIf { it.isNotEmpty() && it != "null" }
        val endMs = endUtc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: if (i == dirs.lastIndex) nowMs else log.lastModified()
        // an action lasts until the next action of the session starts (same rule as actionSpans), or until the session's end
        val acts = marks.filter { it.isAction }.sortedBy { it.timeMs }
        for ((k, a) in acts.withIndex()) {
            val name = a.name ?: continue
            val end = minOf(acts.getOrNull(k + 1)?.timeMs ?: endMs, endMs)
            if (end > a.timeMs) actions += ActionBand(a.id, dir, name, a.timeMs, end)
        }
        events += marks.filter { !it.isAction }.map { EventPoint(it.id, dir, it.name, it.timeMs) }
    }
    return RangeMarks(
        actions.filter { it.endMs > fromMs && it.startMs < toMs }.sortedBy { it.startMs },
        events.filter { it.timeMs in fromMs until toMs }.sortedBy { it.timeMs },
    )
}
