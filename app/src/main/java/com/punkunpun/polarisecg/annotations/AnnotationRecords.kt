package com.punkunpun.polarisecg.annotations

import org.json.JSONObject
import java.util.UUID

/**
 * Actions and events the owner marks while wearing the strap (P5.10).
 *
 * - An **action** is a state with a duration (walking, sitting...): it lasts from its [Annotation.timeMs] until the next
 *   action starts, or the session ends. An action with `name == null` is "no action" and just ends the previous one.
 * - An **event** is a moment (dizzy, stress...), optionally with a note.
 *
 * Records are appended, one JSON object per line, to `annotations.jsonl` in the session folder (docs/RAW_FORMAT.md) and are
 * never changed or deleted. A correction is a new line with the same [Annotation.id]; the newest line of an id wins.
 */
enum class AnnotationKind(val key: String) {
    ACTION("action"),
    EVENT("event");

    companion object {
        fun fromKey(key: String): AnnotationKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * [pressedMs] is when the button was pressed (never changes, even in a correction); [timeMs] is when it happened
 * (the same, unless the owner corrected it: "it was 10 minutes ago"). [recordedMs] is when this line was written.
 */
data class Annotation(
    val id: String,
    val kind: AnnotationKind,
    val name: String?,
    val pressedMs: Long,
    val timeMs: Long,
    val note: String = "",
    val recordedMs: Long = timeMs,
) {
    fun toJsonLine(): String = JSONObject()
        .put("id", id)
        .put("kind", kind.key)
        .put("name", name ?: JSONObject.NULL)
        .put("pressedMs", pressedMs)
        .put("timeMs", timeMs)
        .put("note", note)
        .put("recordedMs", recordedMs)
        .toString()
}

fun newAnnotationId(): String = UUID.randomUUID().toString()

/** One line of `annotations.jsonl`, or null if it is damaged (e.g. the process was killed while writing it). */
fun parseAnnotationLine(line: String): Annotation? = try {
    val o = JSONObject(line)
    Annotation(
        id = o.getString("id"),
        kind = AnnotationKind.fromKey(o.getString("kind")) ?: throw IllegalArgumentException("kind"),
        name = if (o.isNull("name")) null else o.getString("name"),
        pressedMs = o.getLong("pressedMs"),
        timeMs = o.getLong("timeMs"),
        note = o.optString("note", ""),
        recordedMs = o.optLong("recordedMs", o.getLong("timeMs")),
    )
} catch (e: Exception) {
    null
}

/** The current state of the log: the newest line of every id, sorted by [Annotation.timeMs]. Damaged lines are skipped. */
fun foldAnnotations(lines: List<String>): List<Annotation> {
    val byId = LinkedHashMap<String, Annotation>()
    for (line in lines) parseAnnotationLine(line)?.let { byId[it.id] = it }
    return byId.values.sortedBy { it.timeMs }
}

/** The action that is running now (the last one started), or null if there is none or the last one was "no action". */
fun currentAction(annotations: List<Annotation>): Annotation? =
    annotations.lastOrNull { it.kind == AnnotationKind.ACTION }?.takeIf { it.name != null }
