package com.punkunpun.polarecg.dsp.event

import com.punkunpun.polarecg.dsp.analysis.Beat
import com.punkunpun.polarecg.dsp.analysis.Event
import com.punkunpun.polarecg.dsp.live.EcgHistory

/** What the clip shows around an event: [beforeMs] before its start, then its start..end (at most [maxCoreMs]), then [afterMs]. */
data class ClipWindow(
    val beforeMs: Long = 10_000,
    val afterMs: Long = 10_000,
    val maxCoreMs: Long = 20_000,
)

/**
 * An event that is over, and its clip if one was saved. [clip] is null when the [ClipPolicy] said no (too many of this kind
 * lately) or when the ECG was no longer in memory; the event itself is always reported.
 */
class RecordedEvent(val event: Event, val beatCount: Int, val clip: EventClip?) {
    val endUtcMs: Long get() = event.utcMs + event.durationMs
}

/**
 * Events with their ECG clips (P5.4): beats in, [RecordedEvent]s out.
 *
 * Beats go through a [LiveEventDetector]; each event that is over asks the [ClipPolicy] for a clip. The clip needs ECG up to
 * [ClipWindow.afterMs] after the event, which has not arrived yet when the event is final, so it is **cut later**: when the
 * beats have passed the end of the clip (or, without beats, when the ECG has by more than [PATIENCE_MS]), or at [finish].
 *
 * Call [add] with the finished beats, [poll] after each ECG frame, [finish] at the end. One thread at a time.
 */
class EventRecorder(
    private val history: EcgHistory,
    private val policy: ClipPolicy = ClipPolicy(),
    private val detector: LiveEventDetector = LiveEventDetector(),
    private val window: ClipWindow = ClipWindow(),
) {
    private class Waiting(val event: Event, val beatCount: Int, val fromUtcMs: Long, val toUtcMs: Long)

    private val waiting = ArrayList<Waiting>()
    private val ready = ArrayList<RecordedEvent>() // decided without a clip
    private var lastBeatUtcMs = Long.MIN_VALUE

    fun add(beats: List<Beat>): List<RecordedEvent> {
        for (b in beats) {
            lastBeatUtcMs = b.utcMs
            for (e in detector.add(b)) schedule(e)
        }
        return collect(final = false)
    }

    fun poll(): List<RecordedEvent> = collect(final = false)

    fun finish(): List<RecordedEvent> {
        for (e in detector.finish()) schedule(e)
        return collect(final = true)
    }

    private fun schedule(e: Event) {
        val beatCount = detector.beatsBetween(e.utcMs, e.utcMs + e.durationMs).size
        if (!policy.allow(e.type, e.utcMs)) {
            ready += RecordedEvent(e, beatCount, null)
            return
        }
        val core = minOf(e.durationMs, window.maxCoreMs)
        waiting += Waiting(e, beatCount, e.utcMs - window.beforeMs, e.utcMs + core + window.afterMs)
    }

    private fun collect(final: Boolean): List<RecordedEvent> {
        val out = ArrayList<RecordedEvent>(ready)
        ready.clear()
        val newest = history.latestUtcMs
        val it = waiting.iterator()
        while (it.hasNext()) {
            val w = it.next()
            val due = final || lastBeatUtcMs >= w.toUtcMs || (newest != null && newest >= w.toUtcMs + PATIENCE_MS)
            if (!due) continue
            it.remove()
            out += RecordedEvent(w.event, w.beatCount, cut(w))
        }
        out.sortBy { it.event.utcMs }
        return out
    }

    private fun cut(w: Waiting): EventClip? {
        val ecg = history.clip(w.event.utcMs, w.fromUtcMs, w.toUtcMs) ?: return null
        val beats = detector.beatsBetween(ecg.startUtcMs, ecg.endUtcMs)
            .map { ClipBeat(it.utcMs, it.cls, it.good, it.vDoubt, it.rescued) }
        return EventClip(ecg, beats, w.event.utcMs, w.event.utcMs + w.event.durationMs)
    }

    private companion object {
        /** Without beats (signal lost) a clip is cut anyway once the ECG is this far past its end. */
        const val PATIENCE_MS = 8_000L
    }
}
