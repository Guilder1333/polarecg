package com.punkunpun.polarecg.dsp.annotations

/*
 * The owner's labels on the phone (plan §8, P5.7): the same rules as the PC tool's `labels.csv` (see OwnerLabels.kt and
 * docs/LABELING_TOOL.md): append-only history, the NEWEST row per beat wins, "?" = unsure (no class), "" = undone.
 */

/** The values a label row can hold. A label row never changes `beat.cls`; it only says what the owner thinks. */
object OwnerLabel {
    const val NORMAL = "N"
    const val PREMATURE = "S"
    const val VENTRICULAR = "V"
    /** Noise, motion, a broken-looking signal: "cannot tell" (the PC tool's Q). */
    const val UNCLASSIFIABLE = "Q"
    /** Skipped on purpose: "don't guess". Leaves the automatic class alone. */
    const val UNSURE = "?"
    /** Take back an earlier label (a new row; the old one stays in the history). */
    const val UNDONE = ""

    /** The labels the owner can choose from the screen, in the order the buttons show them. */
    val CHOICES = listOf(NORMAL, PREMATURE, VENTRICULAR, UNCLASSIFIABLE, UNSURE, UNDONE)

    /** The labels that count as a class (what the reports compare with the automatic class). */
    val CLASSES = setOf(NORMAL, PREMATURE, VENTRICULAR, UNCLASSIFIABLE)

    fun isValid(label: String) = label in CHOICES
}

/** One row of the label history, in the order it was written (oldest first). */
data class LabelRecord(val beatUtcMs: Long, val label: String, val note: String, val createdAtUtcMs: Long)

/**
 * The current label of every beat: beat time -> its newest row's label. [history] is in the order the rows were written
 * (the table's id order), so a later row wins even if the phone clock stepped back. Includes "?" and undone ("") as
 * they are, so a screen can tell "never labelled" (absent) from "labelled unsure" / "undone".
 */
fun currentLabelRows(history: List<LabelRecord>): Map<Long, LabelRecord> {
    val newest = LinkedHashMap<Long, LabelRecord>()
    for (row in history) newest[row.beatUtcMs] = row
    return newest
}

/** Only the beats whose current label is a class (N, S, V, Q): beat time -> label. Same result as `parseOwnerLabels`. */
fun currentClassLabels(history: List<LabelRecord>): Map<Long, String> =
    currentLabelRows(history).mapValues { it.value.label }.filterValues { it in OwnerLabel.CLASSES }

private val LABEL_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

/** The header of `labels.csv`, as written by the PC tool (`analysis/label_beats.py`). */
const val LABELS_CSV_HEADER = "item,utcMs,kind,label,labeledAtUtc,note,bumpUtcMs"

/** A CSV field: in quotes (inner quotes doubled) only if it needs them; the reader is `splitCsvLine`. */
private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/**
 * The phone's label history as the PC tool's `labels.csv`, so `analyze --labels` and `eval_labels.py` read it unchanged
 * (docs/LABELING_TOOL.md): one row per label press in [history]'s order (the order of writing), `item` = "B" + beat time,
 * `kind` always BEAT, the last row per item wins. Lines end with "\n"; the first line is the header.
 */
fun labelsCsv(history: List<LabelRecord>): String {
    val sb = StringBuilder(LABELS_CSV_HEADER).append('\n')
    for (r in history) {
        sb.append("B${r.beatUtcMs},${r.beatUtcMs},BEAT,${csvField(r.label)},${LABEL_TIME.format(java.time.Instant.ofEpochMilli(r.createdAtUtcMs))},${csvField(r.note)},\n")
    }
    return sb.toString()
}

/** The `note` of a label row written by "Accept": the owner looked at the beat and left the automatic class as it is. */
const val ACCEPTED_NOTE = "accepted"

/**
 * What "Accept" on a reviewed strip saves (P5.7). [staged] are the marks the owner chose (beat time -> label, "" = undo);
 * every other beat of [beats] that has **no label yet** (never labelled, or undone) is confirmed with its own automatic class,
 * note [ACCEPTED_NOTE]: the owner marks only what is off, so "not marked" means "as expected". The rows follow the PC tool's
 * rules, so `analyze --labels` counts a confirmed beat like a hand label that agrees with the automatic class.
 *
 * Left alone: a beat that already has a label (also "?"), a staged mark equal to the current one (nothing changed), a staged
 * undo of a beat that has no label, a staged label that is not valid, and a beat whose class is not N, S, V or Q.
 * [current] is the newest row of each beat, as in [currentLabelRows].
 */
fun labelsToSave(
    beats: List<com.punkunpun.polarecg.dsp.event.ClipBeat>,
    current: Map<Long, LabelRecord>,
    staged: Map<Long, String>,
    nowMs: Long,
    /** If given, only these beats are confirmed automatically (staged marks are saved whatever this says); null = every beat of [beats]. */
    confirm: Set<Long>? = null,
): List<LabelRecord> {
    val rows = ArrayList<LabelRecord>()
    for (b in beats) {
        val now = current[b.utcMs]?.label ?: ""
        val mark = staged[b.utcMs]
        if (mark != null) {
            if (OwnerLabel.isValid(mark) && mark != now) rows += LabelRecord(b.utcMs, mark, "", nowMs)
        } else if (now.isEmpty() && b.cls in OwnerLabel.CLASSES && (confirm == null || b.utcMs in confirm)) {
            rows += LabelRecord(b.utcMs, b.cls, ACCEPTED_NOTE, nowMs)
        }
    }
    return rows
}
