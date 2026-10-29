package com.punkunpun.polarisecg.dsp.annotations

/*
 * The owner's actions and events (P5.10, P5.12): `annotations.jsonl` in a session folder, read on the PC.
 * The format is described in docs/RAW_FORMAT.md; the phone writes it (app/annotations/), this file reads it.
 */

/**
 * One action or event. [name] is null for "no action" (an action that only ends the previous one).
 * [timeMs] is when it happened (a correction may have moved it away from [pressedMs]).
 */
data class Mark(
    val id: String,
    val isAction: Boolean,
    val name: String?,
    val pressedMs: Long,
    val timeMs: Long,
    val note: String,
)

/** Reads the lines of `annotations.jsonl`: the newest line of every id wins; damaged lines are skipped; sorted by [Mark.timeMs]. */
fun parseMarks(lines: List<String>): List<Mark> {
    val byId = LinkedHashMap<String, Mark>()
    for (line in lines) parseMark(line)?.let { byId[it.id] = it }
    return byId.values.sortedBy { it.timeMs }
}

private fun parseMark(line: String): Mark? {
    val o = parseFlatJson(line) ?: return null
    val id = o["id"] as? String ?: return null
    val isAction = when (o["kind"]) {
        "action" -> true
        "event" -> false
        else -> return null
    }
    val pressed = (o["pressedMs"] as? Double)?.toLong() ?: return null
    val time = (o["timeMs"] as? Double)?.toLong() ?: return null
    return Mark(id, isAction, o["name"] as? String, pressed, time, o["note"] as? String ?: "")
}

/**
 * A minimal JSON reader for ONE flat object per line (strings, numbers, true/false/null; no nesting), which is all the
 * annotation log contains. Returns null for anything else, including a line cut off by a kill. Numbers come as Double
 * (exact for epoch ms, which stay far below 2^53).
 */
internal fun parseFlatJson(text: String): Map<String, Any?>? {
    var i = 0
    fun skip() { while (i < text.length && text[i].isWhitespace()) i++ }
    fun string(): String? {
        if (i >= text.length || text[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= text.length) return null
                    when (val e = text[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (i + 4 > text.length) return null
                            sb.append(text.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                            i += 4
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
        return null // no closing quote
    }

    skip()
    if (i >= text.length || text[i] != '{') return null
    i++
    val result = LinkedHashMap<String, Any?>()
    skip()
    if (i < text.length && text[i] == '}') return result
    while (true) {
        skip()
        val key = string() ?: return null
        skip()
        if (i >= text.length || text[i] != ':') return null
        i++
        skip()
        if (i >= text.length) return null
        val value: Any? = when {
            text[i] == '"' -> string() ?: return null
            text.startsWith("null", i) -> { i += 4; null }
            text.startsWith("true", i) -> { i += 4; true }
            text.startsWith("false", i) -> { i += 5; false }
            else -> {
                val start = i
                while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
                text.substring(start, i).toDoubleOrNull() ?: return null
            }
        }
        result[key] = value
        skip()
        if (i >= text.length) return null
        when (text[i++]) {
            ',' -> continue
            '}' -> return result
            else -> return null
        }
    }
}

/** An action that ran from [startMs] to [endMs] (the next action's start, or the end of the recording). */
data class ActionSpan(val name: String, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * The actions as time spans: each lasts until the next action (named or "no action") starts; the last one until [endMs].
 * Stretches with no action running are not spans. Spans that would start at or after [endMs] are dropped.
 */
fun actionSpans(marks: List<Mark>, endMs: Long): List<ActionSpan> {
    val actions = marks.filter { it.isAction }.sortedBy { it.timeMs }
    val spans = ArrayList<ActionSpan>()
    for ((i, a) in actions.withIndex()) {
        val name = a.name ?: continue
        val end = minOf(actions.getOrNull(i + 1)?.timeMs ?: endMs, endMs)
        if (end > a.timeMs) spans += ActionSpan(name, a.timeMs, end)
    }
    return spans
}

/** The name of the action running at [timeMs], or null if none. [spans] must be sorted and not overlapping, as [actionSpans] gives them. */
fun actionAt(spans: List<ActionSpan>, timeMs: Long): String? {
    var lo = 0
    var hi = spans.size - 1
    while (lo <= hi) { // binary search for the last span that starts at or before timeMs
        val mid = (lo + hi) ushr 1
        if (spans[mid].startMs <= timeMs) lo = mid + 1 else hi = mid - 1
    }
    return spans.getOrNull(hi)?.takeIf { timeMs < it.endMs }?.name
}
