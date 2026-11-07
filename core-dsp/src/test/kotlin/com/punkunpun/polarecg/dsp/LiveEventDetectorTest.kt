package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.analysis.Beat
import com.punkunpun.polarecg.dsp.analysis.Event
import com.punkunpun.polarecg.dsp.event.LiveEventDetector
import com.punkunpun.polarecg.dsp.pattern.detectPatterns
import com.punkunpun.polarecg.dsp.quality.BeatQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveEventDetectorTest {
    private val quality = BeatQuality(1.0, 1000.0, 1.0, 0.01, 1.0, 0.01, 1.0, true)

    /** Beats from a string like "NNVVN" (one letter per beat, [rr] ms apart; `|` = a new segment). */
    private fun beats(classes: String, rr: Long = 800, start: Long = 1_000_000L): List<Beat> {
        var t = start
        var segment = 0
        val out = ArrayList<Beat>()
        for (c in classes) {
            if (c == '|') { segment++; t += 10_000; continue }
            out += Beat(t, segment, null, c.toString(), quality, true)
            t += rr
        }
        return out
    }

    private fun key(e: Event) = "${e.type}@${e.utcMs}+${e.durationMs}"

    private fun live(b: List<Beat>, detector: LiveEventDetector = LiveEventDetector()): List<Event> =
        b.flatMap { detector.add(it) } + detector.finish()

    @Test
    fun givesTheSameEventsAsTheWholeRecordingAnalysis() {
        val seq = "NNNNVVNNNNNNSNNNNNNVNVNVNVNNNNNNNNNNSSSNNNNNNNNNVVVVVNNNNNNNNNNNNNN|NNNNVVNNNNNN"
        val b = beats(seq)
        assertEquals(detectPatterns(b).map(::key).sorted(), live(b).map(::key).sorted())
    }

    @Test
    fun anEventIsReportedOnlyWhenItIsOverAndOnlyOnce() {
        val d = LiveEventDetector()
        val b = beats("NNNNVVNNNNNNNNNN")
        val firstSeen = b.indexOfFirst { d.add(it).isNotEmpty() }
        // the couplet ends with beat 5; four more beats must follow before it is final (it could still become a triplet)
        assertEquals(5 + 4, firstSeen)
        assertEquals(emptyList(), d.finish(), "reported once, not again at the end")
    }

    @Test
    fun aCoupletThatGrowsIsOneTripletNotTwoEvents() {
        val b = beats("NNNNNNVVVNNNNNNN")
        assertEquals(listOf("V_TRIPLET"), live(b).map { it.type })
    }

    @Test
    fun finishReportsWhatWasStillOpen() {
        val d = LiveEventDetector()
        beats("NNNNVV").forEach { assertEquals(emptyList(), d.add(it)) }
        assertEquals(listOf("V_COUPLET"), d.finish().map { it.type })
    }

    @Test
    fun aBigeminyTooLongForTheWindowComesOutInPiecesWithoutDoubling() {
        val b = beats("NNNN" + "VN".repeat(150) + "NNNNNNNN")
        val events = live(b, LiveEventDetector(maxBeats = 60)).filter { it.type == "V_BIGEMINY" }
        assertTrue(events.size >= 2, "reported in pieces: ${events.size}")
        val sorted = events.sortedBy { it.utcMs }
        for (i in 1 until sorted.size) assertTrue(sorted[i].utcMs > sorted[i - 1].utcMs + sorted[i - 1].durationMs, "no overlap")
        val covered = sorted.sumOf { it.durationMs }
        assertTrue(covered > 150 * 1600 * 0.6, "most of the 4 minutes is covered: $covered ms")
    }

    @Test
    fun beatsBetweenGivesTheMarkersOfAClip() {
        val d = LiveEventDetector()
        val b = beats("NNNNNNNNNN")
        b.forEach { d.add(it) }
        assertEquals(3, d.beatsBetween(b[2].utcMs, b[4].utcMs).size)
    }
}
