package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.classify.BeatClassifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BeatClassifierTest {
    private val classifier = BeatClassifier()

    /** Features of a normal beat in a steady rhythm; every test changes what it needs ("named arguments"). */
    private fun features(
        good: Boolean = true,
        corr: Double? = 0.98,
        prematurity: Double? = 1.0,
        shortening: Double? = 1.0,
        rrSpread: Double? = 0.02,
        widthRatio: Double? = 1.0,
        areaRatio: Double? = 1.0,
        flip: Boolean = false,
        drift: Double? = null,
    ) = BeatFeatures(
        timeMs = 0.0, good = good, rrPreMs = 800.0, rrPostMs = 800.0, rrRefMs = 800.0, rrSpread = rrSpread,
        prematurity = prematurity, shortening = shortening, compensation = 1.0, corr = corr, shift = 0.0, qrsWidthMs = 60.0,
        widthRatio = widthRatio, ampRatio = 1.0, areaRatio = areaRatio, polarityFlip = flip, normalTimed = true, driftRatio = drift,
    )

    private fun cls(f: BeatFeatures) = classifier.classify(f)

    @Test fun normalBeatIsN() = assertEquals(BeatClass.NORMAL, cls(features()))

    @Test fun poorSignalIsQEvenIfItLooksLikeAnything() {
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(good = false)))
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(good = false, corr = 0.1, prematurity = 0.5)))
    }

    @Test fun lowCorrelationOnADriftingBaselineIsAQuestionableV() {
        val f = features(corr = 0.3, prematurity = 0.6, drift = 0.3)
        assertEquals(BeatClass.VENTRICULAR, cls(f)) // still a V...
        assertTrue(classifier.vNeedsReview(f)) // ...but to be looked at
        assertTrue(classifier.vNeedsReview(features(areaRatio = 3.0, corr = 0.8, drift = 0.2)))
    }

    @Test fun shapeThatDriftCannotCauseIsNeverQuestionable() {
        assertFalse(classifier.vNeedsReview(features(corr = 0.3, prematurity = 0.6, drift = 0.0))) // early, flat baseline: a real V
        assertFalse(classifier.vNeedsReview(features(corr = 0.3, prematurity = 0.6, drift = null))) // no measurement: not doubted
        assertFalse(classifier.vNeedsReview(features(corr = 0.3, prematurity = 0.6, drift = 0.14))) // below the limit
        assertFalse(classifier.vNeedsReview(features(flip = true, corr = 0.3, drift = 0.5)))
        assertFalse(classifier.vNeedsReview(features(widthRatio = 1.8, corr = 0.8, drift = 0.5)))
        assertFalse(classifier.vNeedsReview(features(drift = 0.5))) // a normal beat is not a V at all
        assertFalse(classifier.vNeedsReview(features(good = false, corr = 0.3, drift = 0.5))) // Q
    }

    @Test fun noTemplateYetIsQ() = assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(corr = null)))

    @Test fun differentShapeIsVWhetherEarlyOrNot() {
        assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.3)))
        assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.3, prematurity = 0.6)))
        assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.3, prematurity = 1.0))) // late or interpolated PVC
    }

    @Test fun wideAndDissimilarIsVButWideAloneIsNot() {
        assertEquals(BeatClass.VENTRICULAR, cls(features(widthRatio = 1.8, corr = 0.8)))
        assertEquals(BeatClass.NORMAL, cls(features(widthRatio = 1.8, corr = 0.97))) // same shape, only the estimate is coarse
    }

    @Test fun bigAreaOrUpsideDownIsV() {
        assertEquals(BeatClass.VENTRICULAR, cls(features(areaRatio = 2.5, corr = 0.8)))
        assertEquals(BeatClass.VENTRICULAR, cls(features(flip = true)))
    }

    @Test fun sameShapeOnlyLargerIsNotV() = assertEquals(BeatClass.NORMAL, cls(features(areaRatio = 2.5, corr = 0.96)))

    @Test fun earlyAndClearlyDifferentIsVButOnTimeIsLeftAlone() {
        assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.65, prematurity = 0.6, shortening = 0.6)))
        assertEquals(BeatClass.NORMAL, cls(features(corr = 0.65, prematurity = 1.0)))
        assertEquals(BeatClass.PREMATURE, cls(features(corr = 0.8, prematurity = 0.6, shortening = 0.6))) // different a little: still S
    }

    @Test fun anOnTimeVRestingOnShapeAloneIsQuestionable() {
        assertTrue(classifier.vNeedsReview(features(corr = 0.3, prematurity = 1.0)))
        assertFalse(classifier.vNeedsReview(features(corr = 0.3, prematurity = 0.6))) // early, flat baseline: confident
        assertFalse(classifier.vNeedsReview(features(flip = true, prematurity = 1.0)))
    }

    @Test fun earlyNormalShapeInSteadyRhythmIsS() {
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.7, shortening = 0.7)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.85)))
    }

    @Test fun aGradualSpeedUpIsNotPremature() {
        // 5 beats in a row at 0.8 of the reference, but each only 0.96 of the one before: the rate changed
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.78, shortening = 0.96)))
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.78, shortening = 0.8)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.78, shortening = null)))
    }

    @Test fun earlyBeatInIrregularRhythmIsNotS() {
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, rrSpread = 0.2)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, rrSpread = null)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = null)))
    }

    @Test fun shapeBeatsTimingWhenBothSayAbnormal() = assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.2, prematurity = 0.6)))
}
