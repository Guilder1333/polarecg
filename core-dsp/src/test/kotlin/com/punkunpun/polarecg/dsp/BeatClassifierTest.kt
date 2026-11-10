package com.punkunpun.polarecg.dsp

import com.punkunpun.polarecg.dsp.beat.BeatFeatures
import com.punkunpun.polarecg.dsp.classify.BeatClass
import com.punkunpun.polarecg.dsp.classify.BeatClassifier
import com.punkunpun.polarecg.dsp.quality.BeatQuality
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
        rrPost: Double? = 800.0, // the interval after the beat; a premature beat is followed by a pause (rrPre 800 / 1400 = 0.57)
        compensation: Double? = 1.0, // (rrPre + rrPost) / (2 rrRef); above 1.35 the next beat was probably missed
    ) = BeatFeatures(
        timeMs = 0.0, good = good, rrPreMs = 800.0, rrPostMs = rrPost, rrRefMs = 800.0, rrSpread = rrSpread,
        prematurity = prematurity, shortening = shortening, compensation = compensation, corr = corr, shift = 0.0, qrsWidthMs = 60.0,
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
        assertFalse(classifier.vNeedsReview(features(widthRatio = 1.8, corr = 0.8, prematurity = 0.6, drift = 0.5)))
        assertFalse(classifier.vNeedsReview(features(drift = 0.5))) // a normal beat is not a V at all
        assertFalse(classifier.vNeedsReview(features(good = false, corr = 0.3, drift = 0.5))) // Q
    }

    private fun quality(agreed: Boolean = true, slope: Double = 0.05, hf: Double = 0.01) = BeatQuality(
        sqi = 0.3, amplitudeUv = 800.0, amplitudeScore = 1.0, slopeRatio = slope, slopeScore = 0.4, hfRatio = hf, hfScore = 1.0, agreed = agreed,
    )

    @Test fun aPoorBeatThatMatchesTheTemplateIsRescued() {
        val poor = features(good = false, corr = 0.95)
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(poor)) // without quality: always Q
        assertEquals(BeatClass.NORMAL, classifier.classify(poor, quality()))
        assertEquals(BeatClass.PREMATURE, classifier.classify(features(good = false, corr = 0.95, prematurity = 0.6, shortening = 0.6, rrPost = 1400.0), quality()))
        assertTrue(classifier.rescues(poor, quality()))
        assertFalse(classifier.rescues(features(corr = 0.95), quality())) // a good beat is not "rescued"
    }

    @Test fun noRescueWithoutAgreementTemplateMatchOrWithNoise() {
        assertEquals(BeatClass.UNCLASSIFIABLE, classifier.classify(features(good = false, corr = 0.95), quality(agreed = false)))
        assertEquals(BeatClass.UNCLASSIFIABLE, classifier.classify(features(good = false, corr = 0.7), quality()))
        assertEquals(BeatClass.UNCLASSIFIABLE, classifier.classify(features(good = false, corr = 0.95), quality(slope = 0.2)))
        assertEquals(BeatClass.UNCLASSIFIABLE, classifier.classify(features(good = false, corr = 0.95), quality(hf = 0.08)))
        assertEquals(BeatClass.UNCLASSIFIABLE, classifier.classify(features(good = false, corr = null), quality()))
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
        assertEquals(BeatClass.PREMATURE, cls(features(corr = 0.8, prematurity = 0.6, shortening = 0.6, rrPost = 1400.0))) // different a little: still S
    }

    @Test fun anOnTimeVRestingOnShapeAloneIsQuestionable() {
        assertTrue(classifier.vNeedsReview(features(corr = 0.3, prematurity = 1.0)))
        assertFalse(classifier.vNeedsReview(features(corr = 0.3, prematurity = 0.6))) // early, flat baseline: confident
        assertTrue(classifier.vNeedsReview(features(widthRatio = 1.8, corr = 0.6, prematurity = 1.0))) // on time: wide and dissimilar is still doubted
        assertFalse(classifier.vNeedsReview(features(flip = true, prematurity = 1.0)))
    }

    @Test fun earlyNormalShapeInSteadyRhythmIsS() {
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 1400.0)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.85)))
    }

    @Test fun aPauseAfterTheBeatIsRequired() {
        // early and abrupt, but the next interval is as short: not a premature beat followed by a pause (P4.8)
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 800.0)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 1000.0))) // 0.8
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 1100.0))) // 0.73
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = null))) // end of a segment
    }

    @Test fun aLongIntervalAfterTheBeatIsAMissedBeatNotAPause() {
        // rrPre 800 * 0.8 = 640, rrPost 1600 (two intervals): compensation 1.4 -> the detector skipped a beat
        val missed = features(prematurity = 0.8, shortening = 0.8, rrPost = 1600.0, compensation = 1.4)
        assertTrue(classifier.isLongInterval(missed))
        assertEquals(BeatClass.NORMAL, cls(missed))
        // a real pause that makes up for the early beat: compensation about 1
        val pause = features(prematurity = 0.6, shortening = 0.6, rrPost = 1400.0, compensation = 1.0)
        assertFalse(classifier.isLongInterval(pause))
        assertEquals(BeatClass.PREMATURE, cls(pause))
        assertFalse(classifier.isLongInterval(features(compensation = null)))
    }

    @Test fun aLongIntervalBeforeANormalBeatIsSS() {
        assertEquals(BeatClass.SUPPRESSED, cls(features(prematurity = 2.0, shortening = 2.0)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 2.0, shortening = 1.1))) // the rate slowed down, the interval before was long too
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 2.0, shortening = 2.0, rrPost = 1400.0))) // next one still long: slower rhythm
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 2.0, shortening = 2.0, rrPost = null))) // no next beat yet
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 1.3))) // a slow beat, not a skipped one
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 2.0, rrSpread = 0.3))) // irregular rhythm: says little
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(prematurity = 2.0, good = false)))
        assertEquals(BeatClass.VENTRICULAR, cls(features(prematurity = 2.0, corr = 0.3))) // shape rules first
        assertTrue(BeatClass.isPremature("S") && BeatClass.isPremature("SS") && !BeatClass.isPremature("N"))
    }

    @Test fun aLongIntervalAfterAShortOneIsAPauseNotASkip() {
        // S (0.66) then its pause: prematurity 1.6, shortening 2.4 -> the interval before was 0.67 of the reference
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 1.6, shortening = 2.4)))
        assertEquals(BeatClass.SUPPRESSED, cls(features(prematurity = 1.9, shortening = 1.9))) // before: a normal interval
        // a skip right after an S: interval before 0.67, this one 2.0 -> together 1.33 of two intervals, more than a pause makes up
        assertEquals(BeatClass.SUPPRESSED, cls(features(prematurity = 2.0, shortening = 3.0)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 3.0, shortening = 7.5))) // before: 0.4, a false beat
    }

    @Test fun aGradualSpeedUpIsNotPremature() {
        // 5 beats in a row at 0.8 of the reference, but each only 0.96 of the one before: the rate changed
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.78, shortening = 0.96)))
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.78, shortening = 0.8, rrPost = 1400.0)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.78, shortening = null)))
    }

    @Test fun earlyBeatInIrregularRhythmIsNotS() {
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 1400.0, rrSpread = 0.2)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, shortening = 0.7, rrPost = 1400.0, rrSpread = null)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = null)))
    }

    @Test fun shapeBeatsTimingWhenBothSayAbnormal() = assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.2, prematurity = 0.6)))
}
