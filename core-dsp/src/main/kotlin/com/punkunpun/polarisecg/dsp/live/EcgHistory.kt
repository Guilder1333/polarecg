package com.punkunpun.polarisecg.dsp.live

import com.punkunpun.polarisecg.dsp.analysis.SegmentClock

/**
 * A piece of ECG cut out of the [EcgHistory]: [samplesUv] (µV) from [startUtcMs] on, one sample every [msPerSample]
 * (about 7.68 ms: the H10 runs 0.2 % faster than 130 Hz, see [SegmentClock]). Never spans a gap in the signal.
 */
class EcgClip(val startUtcMs: Long, val msPerSample: Double, val samplesUv: IntArray) {
    val size: Int get() = samplesUv.size

    /** UTC ms of sample [index] (it may be fractional, e.g. a refined peak). */
    fun utcMsOf(index: Double): Long = startUtcMs + Math.round(index * msPerSample)

    /** Time of the last sample. */
    val endUtcMs: Long get() = if (size == 0) startUtcMs else utcMsOf((size - 1).toDouble())

    /** Index of the sample nearest to [utcMs] (may be outside 0 until size). */
    fun indexOf(utcMs: Long): Int = Math.round((utcMs - startUtcMs) / msPerSample).toInt()
}

/**
 * The last minutes of raw ECG, kept so that a clip can be cut around an event after it has been found (P5.4, plan §2 point 6).
 *
 * Samples are stored per gap-free segment together with that segment's [SegmentClock], i.e. with the very clock that gave
 * the beats their times: a beat's `utcMs` and the sample it sits on agree to the sample. Memory: 4 bytes a sample, 30 minutes
 * (the default) is about 1 MB.
 *
 * Thread-safe (the pipeline thread writes, others may read).
 */
class EcgHistory(private val retainSamples: Int = DEFAULT_RETAIN_SAMPLES) {
    private class Chunk(val firstIndex: Int, val data: IntArray)

    private class Segment(val clock: SegmentClock) {
        val chunks = ArrayDeque<Chunk>()
        var total = 0 // samples ever added; the index of the next one
        val firstKept: Int get() = chunks.firstOrNull()?.firstIndex ?: total
    }

    private val segments = ArrayDeque<Segment>()
    private var kept = 0

    init {
        require(retainSamples > 0) { "retainSamples must be positive" }
    }

    /** A new gap-free segment begins; its samples follow with [add]. [clock] is the one the pipeline uses for this segment. */
    @Synchronized
    fun startSegment(clock: SegmentClock) {
        segments += Segment(clock)
    }

    /** Appends the samples of one frame to the current segment (the frame must already be known to its clock). */
    @Synchronized
    fun add(samples: IntArray) {
        val s = segments.lastOrNull() ?: return
        if (samples.isEmpty()) return
        s.chunks += Chunk(s.total, samples.copyOf()) // a copy: the caller owns its array
        s.total += samples.size
        kept += samples.size
        // Drop whole old frames while there is more than asked for (never the newest one).
        while (kept - (segments.first().chunks.firstOrNull()?.data?.size ?: 0) >= retainSamples) {
            val oldest = segments.first()
            val c = oldest.chunks.removeFirstOrNull()
            if (c == null) { // an empty old segment: forget it
                segments.removeFirst()
                continue
            }
            kept -= c.data.size
            if (oldest.chunks.isEmpty() && oldest !== segments.last()) segments.removeFirst()
        }
    }

    /** UTC ms of the newest sample, or null if there is none. */
    @get:Synchronized
    val latestUtcMs: Long?
        get() {
            val s = segments.lastOrNull() ?: return null
            if (s.total == 0) return null
            return s.clock.sampleUtcMs((s.total - 1).toDouble())
        }

    /**
     * ECG from [fromUtcMs] to [toUtcMs] taken from the segment that holds [anchorUtcMs] (the moment the event is about),
     * cut down to what is still in memory. Null if no segment holds the anchor, or fewer than [MIN_CLIP_SAMPLES] would remain.
     */
    @Synchronized
    fun clip(anchorUtcMs: Long, fromUtcMs: Long, toUtcMs: Long): EcgClip? {
        for (s in segments.reversed()) {
            if (s.total == 0 || s.firstKept >= s.total) continue
            val first = s.firstKept
            val last = s.total - 1
            if (anchorUtcMs < s.clock.sampleUtcMs(first.toDouble()) || anchorUtcMs > s.clock.sampleUtcMs(last.toDouble())) continue
            val i0 = firstAtOrAfter(s, fromUtcMs, first, last)
            val i1 = lastAtOrBefore(s, toUtcMs, first, last)
            if (i1 - i0 + 1 < MIN_CLIP_SAMPLES) return null
            val out = IntArray(i1 - i0 + 1)
            for (c in s.chunks) {
                val from = maxOf(c.firstIndex, i0)
                val to = minOf(c.firstIndex + c.data.size - 1, i1)
                if (from <= to) c.data.copyInto(out, from - i0, from - c.firstIndex, to - c.firstIndex + 1)
            }
            val startUtc = s.clock.sampleUtcMs(i0.toDouble())
            val span = s.clock.elapsedMs(i1.toDouble()) - s.clock.elapsedMs(i0.toDouble())
            return EcgClip(startUtc, if (out.size > 1) span / (out.size - 1) else 0.0, out)
        }
        return null
    }

    /** Smallest index in [lo]..[hi] whose time is at or after [utcMs] (hi if none: the caller clamps). */
    private fun firstAtOrAfter(s: Segment, utcMs: Long, lo0: Int, hi0: Int): Int {
        var lo = lo0
        var hi = hi0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s.clock.sampleUtcMs(mid.toDouble()) >= utcMs) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Largest index in [lo]..[hi] whose time is at or before [utcMs] (lo if none). */
    private fun lastAtOrBefore(s: Segment, utcMs: Long, lo0: Int, hi0: Int): Int {
        var lo = lo0
        var hi = hi0
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (s.clock.sampleUtcMs(mid.toDouble()) <= utcMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** 30 minutes at 130 Hz. */
        const val DEFAULT_RETAIN_SAMPLES = 30 * 60 * 130

        /** A clip shorter than this (about 2 s) is not worth saving. */
        const val MIN_CLIP_SAMPLES = 260
    }
}
