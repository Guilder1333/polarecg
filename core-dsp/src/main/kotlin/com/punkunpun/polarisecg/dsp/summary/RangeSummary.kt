package com.punkunpun.polarisecg.dsp.summary

/*
 * What the summary screen shows for a stretch of time (P5.6): totals, burden per hour, heart rate, HRV and how much of the
 * time was recorded. Pure Kotlin on the rows of `minute_summary` / `hrv_window`, so the numbers are unit-tested on the PC.
 */

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/** The few HRV figures the summary needs from one 5-minute window. */
class HrvPoint(val startUtcMs: Long, val valid: Boolean, val rmssdMs: Double?, val sdnnMs: Double?)

/** One hour (aligned to UTC, which is also the local hour in a zone with whole-hour offsets such as JST). */
class HourBucket(
    val startUtcMs: Long,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
    /** Of the [v] beats, how many are questionable. */
    val vDoubt: Int,
    /** Share of recorded time in this hour (0..1). */
    val coverage: Double,
    /** Median RMSSD of the valid HRV windows that start in this hour, null if there are none. */
    val rmssdMs: Double?,
) {
    private val judged: Int get() = n + s + v
    val sPercent: Double? get() = if (judged > 0) 100.0 * s / judged else null
    val vPercent: Double? get() = if (judged > 0) 100.0 * v / judged else null
    val burdenPercent: Double? get() = if (judged > 0) 100.0 * (s + v) / judged else null
}

class RangeSummary(
    val fromUtcMs: Long,
    val toUtcMs: Long,
    val beats: Int,
    val n: Int,
    val s: Int,
    val v: Int,
    val q: Int,
    val vDoubt: Int,
    val hrMinBpm: Double?,
    val hrMeanBpm: Double?,
    val hrMaxBpm: Double?,
    /** Share of the range that was recorded (0..1), counting each minute by its own coverage. */
    val coverage: Double,
    val hrvWindows: Int,
    val rmssdMedianMs: Double?,
    val sdnnMedianMs: Double?,
    val hours: List<HourBucket>,
) {
    private val judged: Int get() = n + s + v
    val sPercent: Double? get() = if (judged > 0) 100.0 * s / judged else null
    val vPercent: Double? get() = if (judged > 0) 100.0 * v / judged else null
    val burdenPercent: Double? get() = if (judged > 0) 100.0 * (s + v) / judged else null
}

private fun median(values: List<Double>): Double? =
    values.takeIf { it.isNotEmpty() }?.sorted()?.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }

/**
 * Sums up [minutes] and [hrv] inside [fromUtcMs, toUtcMs). [toUtcMs] should be "now" for a range that is still running, so the
 * coverage is not diluted by the future. Burden = (S + V) / (N + S + V), counting every beat of the minute rows (the beats the
 * pipeline could judge, so noisy minutes simply contribute less).
 */
fun summarizeRange(minutes: List<MinuteSummary>, hrv: List<HrvPoint>, fromUtcMs: Long, toUtcMs: Long): RangeSummary {
    val inRange = minutes.filter { it.startUtcMs >= fromUtcMs && it.startUtcMs < toUtcMs }
    val validHrv = hrv.filter { it.valid && it.startUtcMs >= fromUtcMs && it.startUtcMs < toUtcMs }

    val rangeMinutes = ((toUtcMs - fromUtcMs) / MINUTE_MS).coerceAtLeast(1)
    val covered = inRange.sumOf { it.coverage }
    val beatsWithHr = inRange.filter { it.hrMeanBpm != null }
    val hrWeight = beatsWithHr.sumOf { it.n }.toDouble()
    val hrMean = if (hrWeight > 0) beatsWithHr.sumOf { it.hrMeanBpm!! * it.n } / hrWeight else null

    val buckets = LinkedHashMap<Long, MutableList<MinuteSummary>>()
    for (m in inRange) buckets.getOrPut(Math.floorDiv(m.startUtcMs, HOUR_MS) * HOUR_MS) { ArrayList() } += m
    val hours = buckets.map { (start, rows) ->
        HourBucket(
            start, rows.sumOf { it.n }, rows.sumOf { it.s }, rows.sumOf { it.v }, rows.sumOf { it.q }, rows.sumOf { it.vDoubt },
            (rows.sumOf { it.coverage } / 60.0).coerceAtMost(1.0),
            median(validHrv.filter { it.startUtcMs >= start && it.startUtcMs < start + HOUR_MS }.mapNotNull { it.rmssdMs }),
        )
    }.sortedBy { it.startUtcMs }

    return RangeSummary(
        fromUtcMs, toUtcMs,
        inRange.sumOf { it.beats }, inRange.sumOf { it.n }, inRange.sumOf { it.s }, inRange.sumOf { it.v }, inRange.sumOf { it.q },
        inRange.sumOf { it.vDoubt },
        inRange.mapNotNull { it.hrMinBpm }.minOrNull(), hrMean, inRange.mapNotNull { it.hrMaxBpm }.maxOrNull(),
        (covered / rangeMinutes).coerceAtMost(1.0),
        validHrv.size, median(validHrv.mapNotNull { it.rmssdMs }), median(validHrv.mapNotNull { it.sdnnMs }),
        hours,
    )
}
