package com.punkunpun.polarecg

import com.punkunpun.polarecg.annotations.AnnotationKind
import com.punkunpun.polarecg.annotations.markIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkIconsTest {
    @Test fun presetsHaveTheirOwnIconAndOtherNamesTheIconOfTheirKind() {
        assertEquals("🚶", markIcon(AnnotationKind.ACTION, "Walking"))
        assertEquals("☕", markIcon(AnnotationKind.EVENT, "Coffee"))
        assertEquals(markIcon(AnnotationKind.ACTION, "Gardening"), markIcon(AnnotationKind.ACTION, "Cooking"))
        assertNotEquals(markIcon(AnnotationKind.ACTION, "Gardening"), markIcon(AnnotationKind.EVENT, "Gardening"))
    }
}
