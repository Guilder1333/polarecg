package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.motion.ACC_SAMPLE_RATE_HZ
import com.punkunpun.polarisecg.dsp.motion.MotionIndex
import com.punkunpun.polarisecg.dsp.motion.buildMotionBins
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.quality.QualityConfig
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.time.POLAR_EPOCH_UNIX_MS
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionTest {
    private val t0 = 1_791_843_330_000L // UTC ms of the first sample; a whole second
    private val fs = ACC_SAMPLE_RATE_HZ

    /**
     * 36-sample frames like the H10 sends. [shake] gives the extra acceleration (mG, on x) at second [t]; samples from
     * [gapFromS] to [gapToS] are missing.
     */
    private fun frames(
        seconds: Int,
        gapFromS: Double = -1.0,
        gapToS: Double = -1.0,
        shake: (Double) -> Double = { 0.0 },
    ): List<RawRecord.Acc> {
        val rnd = Random(2)
        val out = ArrayList<RawRecord.Acc>()
        val total = seconds * fs
        var i = 0
        while (i < total) {
            val n = minOf(36, total - i)
            val idx = (i until i + n).filter { (it.toDouble() / fs) !in gapFromS..gapToS }
            if (idx.isNotEmpty()) {
                val x = IntArray(idx.size) { (shake(idx[it].toDouble() / fs) + rnd.nextGaussian() * 2).toInt() }
                val y = IntArray(idx.size) { (rnd.nextGaussian() * 2).toInt() }
                val z = IntArray(idx.size) { 1000 + (rnd.nextGaussian() * 2).toInt() } // gravity
                val utcMs = t0 + idx.first() * 1000L / fs
                // the phone gets a frame ~100 ms after its last sample was taken
                out += RawRecord.Acc(utcMs + idx.size * 1000L / fs + 100, (utcMs - POLAR_EPOCH_UNIX_MS) * 1_000_000, x, y, z)
            }
            i += n
        }
        return out
    }

    @Test
    fun lyingStillGivesAboutTheSensorNoise() {
        val bins = buildMotionBins(frames(30))
        assertTrue(bins.size in 29..30)
        // three axes with 2 mG noise each: RMS deviation sqrt(3)*2 = 3.5 mG (like the 3-5 mG of a real night)
        assertTrue(bins.all { it.motionMg in 2.0..5.5 }, "max ${bins.maxOf { it.motionMg }}")
    }

    @Test
    fun gravityAloneIsNotMotion() {
        // z = 1000 mG constant: a large DC value must not count, only the variation
        val bins = buildMotionBins(frames(10))
        assertTrue(bins.all { it.motionMg < 6 })
    }

    @Test
    fun shakingShowsUpInTheSecondItHappens() {
        // 5 Hz oscillation of 200 mG amplitude between second 10 and 12
        val shake = { t: Double -> if (t in 10.0..12.0) 200 * sin(2 * PI * 5 * t) else 0.0 }
        val bins = buildMotionBins(frames(30, shake = shake))
        val byStart = bins.associateBy { (it.startUtcMs - t0) / 1000 }
        assertTrue(byStart.getValue(10).motionMg in 120.0..160.0, "second 10: ${byStart.getValue(10).motionMg}") // 200/sqrt(2) = 141
        assertTrue(byStart.getValue(11).motionMg in 120.0..160.0, "second 11: ${byStart.getValue(11).motionMg}")
        assertTrue(byStart.getValue(9).motionMg < 6, "second 9: ${byStart.getValue(9).motionMg}")
        assertTrue(byStart.getValue(13).motionMg < 6, "second 13: ${byStart.getValue(13).motionMg}")
    }

    @Test
    fun binsAreAlignedToUtcSeconds() {
        val bins = buildMotionBins(frames(5))
        assertTrue(bins.all { it.startUtcMs % 1000 == 0L })
        assertTrue(abs(bins.first().startUtcMs - t0) <= 1000)
    }

    @Test
    fun binsMostlyInAGapAreDropped() {
        val bins = buildMotionBins(frames(20, gapFromS = 5.2, gapToS = 9.9))
        val seconds = bins.map { (it.startUtcMs - t0) / 1000 }.toSet()
        assertTrue(6L !in seconds && 7L !in seconds && 8L !in seconds)
        assertTrue(2L in seconds && 12L in seconds)
    }

    @Test
    fun indexFindsTheHighestNearbySecondOrNull() {
        val shake = { t: Double -> if (t in 10.0..11.0) 300 * sin(2 * PI * 5 * t) else 0.0 }
        val index = MotionIndex(buildMotionBins(frames(30, shake = shake)))
        assertTrue(index.around(t0 + 10_500)!! > 100)
        assertTrue(index.around(t0 + 9_800)!! > 100) // +-0.5 s reaches into second 10
        assertTrue(index.around(t0 + 5_500)!! < 6)
        assertNull(index.around(t0 + 100_000)) // no data there
        assertNull(MotionIndex(emptyList()).around(t0))
    }

    private fun quality(sqi: Double = 1.0) = BeatQuality(sqi, 800.0, 1.0, 0.004, 1.0, 0.002, 1.0, true)

    @Test
    fun motionScoreFallsFromOkToBad() {
        val c = QualityConfig()
        assertEquals(1.0, quality().withMotion(5.0, c).sqi, 1e-9)
        assertEquals(1.0, quality().withMotion(c.motionOkMg, c).sqi, 1e-9)
        assertEquals(0.5, quality().withMotion((c.motionOkMg + c.motionBadMg) / 2, c).sqi, 1e-9)
        assertEquals(0.0, quality().withMotion(c.motionBadMg, c).sqi, 1e-9)
        assertEquals(0.0, quality().withMotion(2000.0, c).sqi, 1e-9)
    }

    @Test
    fun noAccDataChangesNothing() {
        val q = quality(0.9)
        val same = q.withMotion(null)
        assertEquals(0.9, same.sqi, 1e-12)
        assertNull(same.motionMg)
    }

    @Test
    fun motionMultipliesTheEcgScoreAndIsRecorded() {
        val q = quality(0.8).withMotion(400.0)
        assertNotNull(q.motionMg)
        assertEquals(0.8 * q.motionScore, q.sqi, 1e-12)
        assertTrue(q.sqi < 0.6) // 400 mG is above the good cut-off for an otherwise good beat
    }
}
