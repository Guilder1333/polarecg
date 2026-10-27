package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.classify.BeatClassifier
import kotlin.test.Test
import kotlin.test.assertEquals

class BeatClassifierTest {
    private val classifier = BeatClassifier()

    /** Features of a normal beat in a steady rhythm; every test changes what it needs ("named arguments"). */
    private fun features(
        good: Boolean = true,
        corr: Double? = 0.98,
        prematurity: Double? = 1.0,
        rrSpread: Double? = 0.02,
        widthRatio: Double? = 1.0,
        areaRatio: Double? = 1.0,
        flip: Boolean = false,
    ) = BeatFeatures(
        timeMs = 0.0, good = good, rrPreMs = 800.0, rrPostMs = 800.0, rrRefMs = 800.0, rrSpread = rrSpread,
        prematurity = prematurity, compensation = 1.0, corr = corr, shift = 0.0, qrsWidthMs = 60.0,
        widthRatio = widthRatio, ampRatio = 1.0, areaRatio = areaRatio, polarityFlip = flip, normalTimed = true,
    )

    private fun cls(f: BeatFeatures) = classifier.classify(f)

    @Test fun normalBeatIsN() = assertEquals(BeatClass.NORMAL, cls(features()))

    @Test fun poorSignalIsQEvenIfItLooksLikeAnything() {
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(good = false)))
        assertEquals(BeatClass.UNCLASSIFIABLE, cls(features(good = false, corr = 0.1, prematurity = 0.5)))
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
        assertEquals(BeatClass.VENTRICULAR, cls(features(areaRatio = 2.5)))
        assertEquals(BeatClass.VENTRICULAR, cls(features(flip = true)))
    }

    @Test fun earlyNormalShapeInSteadyRhythmIsS() {
        assertEquals(BeatClass.PREMATURE, cls(features(prematurity = 0.7)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.85)))
    }

    @Test fun earlyBeatInIrregularRhythmIsNotS() {
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, rrSpread = 0.2)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = 0.7, rrSpread = null)))
        assertEquals(BeatClass.NORMAL, cls(features(prematurity = null)))
    }

    @Test fun shapeBeatsTimingWhenBothSayAbnormal() = assertEquals(BeatClass.VENTRICULAR, cls(features(corr = 0.2, prematurity = 0.6)))
}
