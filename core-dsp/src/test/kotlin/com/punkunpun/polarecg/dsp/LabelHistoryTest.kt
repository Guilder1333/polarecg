package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.annotations.ACCEPTED_NOTE
import com.punkunpun.polarecg.dsp.annotations.LabelRecord
import com.punkunpun.polarecg.dsp.annotations.labelsToSave
import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.annotations.OwnerLabel
import com.punkunpun.polarecg.dsp.annotations.currentClassLabels
import com.punkunpun.polarecg.dsp.annotations.currentLabelRows
import com.punkunpun.polarecg.dsp.annotations.labelsCsv
import com.punkunpun.polarecg.dsp.annotations.parseOwnerLabels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabelHistoryTest {
    private fun row(beat: Long, label: String, at: Long = 0) = LabelRecord(beat, label, "", at)

    @Test fun newestRowWinsAndOlderOnesStayInTheHistory() {
        val history = listOf(row(100, "N"), row(200, "V"), row(100, "S"))
        assertEquals(mapOf(100L to "S", 200L to "V"), currentClassLabels(history))
        assertEquals(3, history.size) // nothing was removed: a relabel only adds a row
    }

    @Test fun laterRowWinsEvenIfTheClockWentBack() {
        // order of writing counts, not createdAt
        val history = listOf(row(100, "N", at = 5_000), row(100, "S", at = 1_000))
        assertEquals("S", currentLabelRows(history).getValue(100).label)
    }

    @Test fun unsureAndUndoneAreNotClassesButAreKnown() {
        val history = listOf(row(1, "S"), row(1, OwnerLabel.UNDONE), row(2, OwnerLabel.UNSURE), row(3, "Q"))
        assertEquals(mapOf(3L to "Q"), currentClassLabels(history))
        assertEquals(setOf(1L, 2L, 3L), currentLabelRows(history).keys) // "undone" differs from "never labelled"
    }

    @Test fun choicesAreValidAndOthersAreNot() {
        assertTrue(OwnerLabel.CHOICES.all(OwnerLabel::isValid))
        assertFalse(OwnerLabel.isValid("X"))
        assertFalse(OwnerLabel.isValid("s")) // upper case only, as in labels.csv
    }

    @Test fun sameResultAsTheLabelsCsvOfThePcTool() {
        val csv = listOf(
            "item,utcMs,kind,label,labeledAtUtc,note,bumpUtcMs",
            "B100,100,BEAT,N,t,,", "B200,200,BEAT,V,t,,", "B100,100,BEAT,S,t,,", "B300,300,BEAT,?,t,,", "B200,200,BEAT,,t,,",
        )
        val history = listOf(row(100, "N"), row(200, "V"), row(100, "S"), row(300, "?"), row(200, ""))
        assertEquals(parseOwnerLabels(csv), currentClassLabels(history))
    }

    @Test fun theCsvIsInThePcToolFormatAndTheReaderGivesTheSameLabels() {
        val history = listOf(
            LabelRecord(1_792_615_494_444, "N", "", 1_793_000_000_123),
            LabelRecord(1_792_618_194_790, "S", "looks, early \"P\"", 1_793_000_001_000),
            LabelRecord(1_792_615_494_444, "V", "", 1_793_000_002_000),
            LabelRecord(1_792_618_194_790, OwnerLabel.UNDONE, "", 1_793_000_003_000),
            LabelRecord(5, OwnerLabel.UNSURE, "", 1_793_000_004_000),
        )
        val lines = labelsCsv(history).trimEnd('\n').split('\n')
        assertEquals("item,utcMs,kind,label,labeledAtUtc,note,bumpUtcMs", lines.first())
        assertEquals("B1792615494444,1792615494444,BEAT,N,2026-10-26T07:33:20.123Z,,", lines[1])
        assertEquals(history.size + 1, lines.size) // every press is a row, nothing is merged
        assertEquals(currentClassLabels(history), parseOwnerLabels(lines))
        assertEquals(mapOf(1_792_615_494_444L to "V"), parseOwnerLabels(lines))
    }

    private fun beat(t: Long, cls: String) = ClipBeat(t, cls, good = true, vDoubt = false, rescued = false)

    @Test fun acceptSavesTheMarksAndConfirmsTheRest() {
        val beats = listOf(beat(1, "N"), beat(2, "S"), beat(3, "N"), beat(4, "V"))
        val rows = labelsToSave(beats, current = emptyMap(), staged = mapOf(3L to "S"), nowMs = 9)
        // the marked beat gets the owner's label; the others are confirmed with their own class and the note
        assertEquals(
            listOf(
                LabelRecord(1, "N", ACCEPTED_NOTE, 9), LabelRecord(2, "S", ACCEPTED_NOTE, 9),
                LabelRecord(3, "S", "", 9), LabelRecord(4, "V", ACCEPTED_NOTE, 9),
            ),
            rows,
        )
    }

    @Test fun acceptNeverTouchesBeatsThatAlreadyHaveALabel() {
        val beats = listOf(beat(1, "N"), beat(2, "N"), beat(3, "N"))
        val current = currentLabelRows(listOf(row(1, "V"), row(2, OwnerLabel.UNSURE), row(3, OwnerLabel.UNDONE)))
        val rows = labelsToSave(beats, current, staged = emptyMap(), nowMs = 9)
        // 1 (V) and 2 (unsure) stay as the owner left them; 3 was undone, so it has no label and is confirmed
        assertEquals(listOf(LabelRecord(3, "N", ACCEPTED_NOTE, 9)), rows)
    }

    @Test fun anUnchangedOrEmptyMarkWritesNothingAndIsNotConfirmed() {
        val beats = listOf(beat(1, "N"), beat(2, "N"), beat(3, "N"))
        val current = currentLabelRows(listOf(row(1, "S")))
        // 1: staged S is the label it already has; 2: undo of a beat without label; 3: an invalid label
        val rows = labelsToSave(beats, current, staged = mapOf(1L to "S", 2L to "", 3L to "X"), nowMs = 9)
        assertEquals(emptyList(), rows)
    }

    @Test fun aStagedUndoOfARealLabelIsSaved() {
        val current = currentLabelRows(listOf(row(1, "S")))
        assertEquals(listOf(LabelRecord(1, "", "", 9)), labelsToSave(listOf(beat(1, "N")), current, mapOf(1L to ""), 9))
    }

    @Test fun beatsWithoutAClassAreNotConfirmed() {
        assertEquals(emptyList(), labelsToSave(listOf(beat(1, "?")), emptyMap(), emptyMap(), 9))
    }

    @Test fun onlyTheGivenBeatsAreConfirmedButStagedMarksAlwaysSave() {
        val beats = listOf(beat(1, "N"), beat(2, "V"), beat(3, "N"), beat(4, "N"))
        val rows = labelsToSave(beats, emptyMap(), mapOf(4L to "S"), 9, confirm = setOf(2L, 3L))
        assertEquals(listOf(LabelRecord(2, "V", ACCEPTED_NOTE, 9), LabelRecord(3, "N", ACCEPTED_NOTE, 9), LabelRecord(4, "S", "", 9)), rows)
    }
}
