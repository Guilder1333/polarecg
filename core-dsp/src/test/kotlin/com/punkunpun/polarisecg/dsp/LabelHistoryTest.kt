package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.annotations.LabelRecord
import com.punkunpun.polarisecg.dsp.annotations.OwnerLabel
import com.punkunpun.polarisecg.dsp.annotations.currentClassLabels
import com.punkunpun.polarisecg.dsp.annotations.currentLabelRows
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
}
