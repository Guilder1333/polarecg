package com.punkunpun.polarecg.dsp.event

/**
 * How many ECG clips may be saved (P5.4). Every event goes into the event list, but a clip costs about 10 KB, and a night with
 * a couplet every minute should not save 480 of the same picture. Rule, **per event type** (so a flood of S couplets cannot
 * use up the room for a V run): a clip is saved if
 * - fewer than [firstFew] clips of this type were saved in the last [windowMs] (the first few of an hour are always kept), or
 * - the last clip of this type is at least [spacingMs] away.
 * And a hard cap of [maxPerDay] clips for each UTC day, whatever the type.
 */
data class ClipPolicyConfig(
    val firstFew: Int = 3,
    val windowMs: Long = 3_600_000L,
    val spacingMs: Long = 10 * 60_000L,
    val maxPerDay: Int = 200,
)

class ClipPolicy(private val config: ClipPolicyConfig = ClipPolicyConfig()) {
    private val savedByType = HashMap<String, MutableList<Long>>() // event times of the clips saved, per type
    private val savedByDay = HashMap<Long, Int>() // UTC day number -> clips saved

    /** Decides for an event of [type] at [utcMs]; when the answer is yes the clip counts as saved from now on. */
    fun allow(type: String, utcMs: Long): Boolean {
        val day = Math.floorDiv(utcMs, DAY_MS)
        if ((savedByDay[day] ?: 0) >= config.maxPerDay) return false
        val times = savedByType.getOrPut(type) { ArrayList() }
        val inWindow = times.count { it > utcMs - config.windowMs && it <= utcMs }
        val nearest = times.minOfOrNull { Math.abs(it - utcMs) }
        val ok = inWindow < config.firstFew || (nearest ?: Long.MAX_VALUE) >= config.spacingMs
        if (!ok) return false
        times += utcMs
        if (times.size > 200) times.removeAll { it < utcMs - 2 * config.windowMs } // forget the old ones, they cannot matter
        savedByDay[day] = (savedByDay[day] ?: 0) + 1
        if (savedByDay.size > 3) savedByDay.keys.sorted().dropLast(2).forEach { savedByDay.remove(it) }
        return true
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
