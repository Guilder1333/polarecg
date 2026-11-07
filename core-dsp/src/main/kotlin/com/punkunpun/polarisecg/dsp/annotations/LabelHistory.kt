package com.punkunpun.polarisecg.dsp.annotations

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
