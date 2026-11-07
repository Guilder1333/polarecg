package com.punkunpun.polarisecg.dsp.event

import kotlin.math.abs

/*
 * Small rules of the review screen (P5.7), kept here so they are tested on the PC.
 */

/** The beat of [beats] nearest to [utcMs], if it is no further than [maxDistMs] away (a tap on the trace); otherwise null. */
fun nearestBeat(beats: List<ClipBeat>, utcMs: Long, maxDistMs: Long): ClipBeat? {
    val best = beats.minByOrNull { abs(it.utcMs - utcMs) } ?: return null
    return if (abs(best.utcMs - utcMs) <= maxDistMs) best else null
}

/**
 * Readable name of an event type: "V_COUPLET" -> "V couplet", "S_BIGEMINY" -> "S bigeminy", "PAUSE" -> "Pause".
 * Anything unknown is shown as it is (a newer app version may write types this one does not know).
 */
fun eventTitle(type: String): String {
    val parts = type.split('_')
    if (parts.size == 2 && parts[0].length == 1 && parts[0][0].isUpperCase()) return "${parts[0]} ${parts[1].lowercase()}"
    if (parts.size == 1 && type.isNotEmpty()) return type.lowercase().replaceFirstChar { it.uppercase() }
    return type
}
