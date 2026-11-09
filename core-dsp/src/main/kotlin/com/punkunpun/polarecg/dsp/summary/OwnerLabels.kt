package com.punkunpun.polarecg.dsp.summary

import com.punkunpun.polarecg.dsp.annotations.OwnerLabel

/** A beat the owner labelled: its time, the class the detector gave it, whether that V was questionable, and the owner's current label. */
class LabelledBeat(val utcMs: Long, val autoCls: String, val autoVDoubt: Boolean, val label: String)

private const val MINUTE_MS = 60_000L

/**
 * The minute rows with the owner's labels applied: where the owner's current label of a beat is a class (N, S, V, Q) that differs
 * from the detector's, the beat moves from the automatic class to the owner's in the counts of its minute. "Not a beat" ("X")
 * takes the beat out of the counts altogether (also out of `beats`), so noise the detector took for a beat counts for nothing.
 * "?" and undone ("") change nothing, and a label with no minute row in [minutes] is ignored. The owner's V is certain, so it
 * adds no `vDoubt`, and a relabelled doubtful V takes its doubt away. Only the counts change; heart rate, coverage and the rest
 * stay as they were. (Beats pruned from the database are not in [labelled]: those minutes keep the automatic counts.)
 */
fun applyOwnerLabels(minutes: List<MinuteSummary>, labelled: List<LabelledBeat>): List<MinuteSummary> {
    if (labelled.isEmpty()) return minutes
    val byMinute = labelled
        .filter { it.label in OwnerLabel.CLASSES && it.label != it.autoCls }
        .groupBy { Math.floorDiv(it.utcMs, MINUTE_MS) * MINUTE_MS }
    if (byMinute.isEmpty()) return minutes
    return minutes.map { m ->
        val changes = byMinute[m.startUtcMs] ?: return@map m
        val count = mutableMapOf("N" to m.n, "S" to m.s, "V" to m.v, "Q" to m.q)
        var doubt = m.vDoubt
        var beats = m.beats
        for (c in changes) {
            if (c.autoCls !in count) continue
            count[c.autoCls] = count.getValue(c.autoCls) - 1
            if (c.label == OwnerLabel.NOT_A_BEAT) beats-- else count[c.label] = count.getValue(c.label) + 1
            if (c.autoCls == "V" && c.autoVDoubt) doubt--
        }
        MinuteSummary(
            m.startUtcMs, beats.coerceAtLeast(0), count.getValue("N").coerceAtLeast(0), count.getValue("S").coerceAtLeast(0),
            count.getValue("V").coerceAtLeast(0), count.getValue("Q").coerceAtLeast(0), doubt.coerceIn(0, count.getValue("V")),
            m.hrMeanBpm, m.hrMinBpm, m.hrMaxBpm, m.coverage, m.motionMg,
        )
    }
}
