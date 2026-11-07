package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.annotations.LabelRecord
import com.punkunpun.polarisecg.dsp.annotations.OwnerLabel
import com.punkunpun.polarisecg.dsp.annotations.currentClassLabels
import com.punkunpun.polarisecg.dsp.annotations.currentLabelRows
import com.punkunpun.polarisecg.dsp.annotations.labelsCsv
import com.punkunpun.polarisecg.dsp.annotations.parseOwnerLabels
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
        val lines = labelsCsv(history).trimEnd('
').split('
')
        assertEquals("item,utcMs,kind,label,labeledAtUtc,note,bumpUtcMs", lines.first())
        assertEquals("B1792615494444,1792615494444,BEAT,N,2026-10-26T07:33:20.123Z,,", lines[1])
        assertEquals(history.size + 1, lines.size) // every press is a row, nothing is merged
        assertEquals(currentClassLabels(history), parseOwnerLabels(lines))
        assertEquals(mapOf(1_792_615_494_444L to "V"), parseOwnerLabels(lines))
    }
}
