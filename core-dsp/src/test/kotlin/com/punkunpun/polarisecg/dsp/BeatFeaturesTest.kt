package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.beat.BeatFeatureExtractor
import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.beat.BeatInput
import com.punkunpun.polarisecg.dsp.beat.BeatShape
import com.punkunpun.polarisecg.dsp.beat.BeatTemplate
import com.punkunpun.polarisecg.dsp.beat.BeatTemplateConfig
import com.punkunpun.polarisecg.dsp.quality.EcgBeatStream
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatFeaturesTest {
    private val fs = 130.0
    private val rnd = Random(3)

    private fun gauss(t: Double, c: Double, s: Double) = exp(-(t - c) * (t - c) / (2 * s * s))

    /**
     * A snippet in the layout of [BeatShape]: narrow R (+1000) with a T wave (+250) for a normal
     * beat; [wide] = a ventricular-looking beat (broad, bigger, opposite T wave); [sign] = -1
     * turns it upside down; [offset] moves it by that many samples; a little noise on top.
     */
    private fun snippet(wide: Boolean = false, sign: Double = 1.0, offset: Int = 0, noise: Double = 8.0): DoubleArray =
        DoubleArray(BeatShape.SNIPPET_LENGTH) { i ->
            val k = (i - BeatShape.CENTER - offset).toDouble() // samples from R
            val v = if (wide) 1500 * gauss(k, 0.0, 5.0) - 400 * gauss(k, 22.0, 9.0)
            else 1000 * gauss(k, 0.0, 1.5) - 150 * gauss(k, 3.5, 1.5) + 250 * gauss(k, 20.0, 7.0)
            sign * v + noise * rnd.nextGaussian()
        }

    @Test fun driftRatioIsSmallOnAFlatBaselineAndLargeUnderWander() {
        val flat = snippet()
        val wander = DoubleArray(flat.size) { flat[it] + 500 * kotlin.math.sin(2 * Math.PI * it / 70.0) } // a slow wave under the beat
        assertTrue((com.punkunpun.polarisecg.dsp.beat.driftRatio(flat, 1100.0) ?: 1.0) < 0.1)
        assertTrue((com.punkunpun.polarisecg.dsp.beat.driftRatio(wander, 1100.0) ?: 0.0) > 0.15)
        assertNull(com.punkunpun.polarisecg.dsp.beat.driftRatio(flat, 0.0))
    }

    private fun learned(config: BeatTemplateConfig = BeatTemplateConfig()): BeatTemplate {
        val t = BeatTemplate(config)
        repeat(config.learnBeats) { t.offer(snippet(), normalTimed = true) }
        return t
    }

    // ---- template (P4.2) ----

    @Test
    fun templateIsLearnedFromNormalTimedBeatsOnly() {
        val t = BeatTemplate()
        repeat(20) { t.offer(snippet(), normalTimed = false) }
        assertTrue(!t.ready, "premature-looking beats must not start a template")
        repeat(8) { t.offer(snippet(), normalTimed = true) }
        assertTrue(t.ready)
    }

    @Test
    fun normalBeatFitsAndWideBeatDoesNot() {
        val t = learned()
        assertTrue(t.match(snippet())!!.corr > 0.97)
        assertTrue(t.match(snippet(wide = true))!!.corr < 0.6)
    }

    @Test
    fun aBeatMovedByFewSamplesIsLinedUp() {
        val t = learned()
        for (offset in listOf(-5, -3, -2, 2, 3, 5)) {
            val m = t.match(snippet(offset = offset))!!
            assertTrue(m.corr > 0.95, "offset $offset corr ${m.corr}")
            assertEquals(offset.toDouble(), m.shift, 0.3, "offset $offset")
        }
    }

    @Test
    fun anUpsideDownBeatIsRecognised() {
        val m = learned().match(snippet(sign = -1.0))!!
        assertTrue(m.inverted > 0.95 && m.corr < 0.5, "corr ${m.corr} inverted ${m.inverted}")
    }

    @Test
    fun prematureBeatsDoNotChangeTheTemplate() {
        val t = learned()
        val before = t.current!!
        repeat(50) { t.offer(snippet(wide = true), normalTimed = false) }
        // 50 wide beats in a row would re-learn (see below) but never *blend* into the old template
        // while they are labelled premature: the template is either untouched or replaced as a whole.
        val after = t.current!!
        val same = before.indices.all { abs(before[it] - after[it]) < 1e-9 }
        assertTrue(same || t.relearned == 1, "template was blended")
    }

    @Test
    fun aFewVentricularBeatsAmongNormalOnesKeepTheTemplate() {
        val t = learned()
        repeat(100) { i -> t.offer(snippet(wide = i % 5 == 0), normalTimed = i % 5 != 0) }
        assertEquals(0, t.relearned)
        assertTrue(t.match(snippet())!!.corr > 0.97)
    }

    @Test
    fun bigeminyIsNotMistakenForAChangedShape() {
        val t = learned()
        repeat(100) { i -> t.offer(snippet(wide = i % 2 == 0), normalTimed = i % 2 != 0) }
        assertEquals(0, t.relearned, "half the beats are wide: that is a rhythm, not a new template")
    }

    @Test
    fun templateFollowsAShapeChangeThatLastsAndIgnoresShortOnes() {
        val t = learned()
        repeat(8) { t.offer(snippet(wide = true), normalTimed = true) } // 8 beats: too few
        repeat(5) { t.offer(snippet(), normalTimed = true) }
        assertEquals(0, t.relearned)
        repeat(60) { t.offer(snippet(sign = -1.0), normalTimed = true) } // the strap turned around
        assertEquals(1, t.relearned)
        assertTrue(t.match(snippet(sign = -1.0))!!.corr > 0.95)
    }

    @Test
    fun templateLearnedFromTheWrongBeatsIsReplacedByTheMajority() {
        // the first normal-timed beats are all ventricular (record 208 of MIT-BIH), then the usual
        // beat is mixed in as the bigger group
        val t = BeatTemplate()
        repeat(8) { t.offer(snippet(wide = true), normalTimed = true) }
        assertTrue(t.match(snippet())!!.corr < 0.6)
        repeat(100) { i -> t.offer(snippet(wide = i % 3 == 0), normalTimed = true) }
        assertTrue(t.relearned >= 1)
        assertTrue(t.match(snippet())!!.corr > 0.95)
    }

    // ---- features (P4.1) ----

    /** Runs beats at the given times (ms) with the given snippets through an extractor. */
    private fun run(times: List<Double>, shapes: List<DoubleArray>, good: (Int) -> Boolean = { true }): List<BeatFeatures> {
        val x = BeatFeatureExtractor()
        val out = ArrayList<BeatFeatures>()
        for (i in times.indices) out += x.add(BeatInput(times[i], shapes[i], good(i)))
        out += x.finish()
        return out
    }

    private fun regular(n: Int, rr: Double = 800.0) = List(n) { 1000.0 + it * rr }

    @Test
    fun oneResultPerBeatInOrder() {
        val times = regular(30)
        val f = run(times, times.map { snippet() })
        assertEquals(30, f.size)
        assertEquals(times, f.map { it.timeMs })
        assertNull(f.first().rrPreMs)
        assertNull(f.last().rrPostMs)
        assertEquals(800.0, f[10].rrPreMs!!, 1e-9)
        assertEquals(800.0, f[10].rrPostMs!!, 1e-9)
    }

    @Test
    fun regularRhythmHasPrematurityOneAndNoCompensationDeviation() {
        val times = regular(40)
        val f = run(times, times.map { snippet() })
        val late = f.drop(15).dropLast(1)
        assertTrue(late.all { abs(it.prematurity!! - 1.0) < 1e-9 })
        assertTrue(late.all { abs(it.compensation!! - 1.0) < 1e-9 })
        assertTrue(late.all { it.normalTimed })
        assertTrue(late.all { it.corr!! > 0.97 && abs(it.ampRatio!! - 1.0) < 0.1 && !it.polarityFlip })
    }

    @Test
    fun aVentricularBeatWithACompensatoryPauseHasTheExpectedFeatures() {
        // normal beats every 800 ms; beat 30 comes 480 ms after beat 29, then a pause that brings
        // the rhythm back (480 + 1120 = 2 * 800)
        val times = ArrayList(regular(31))
        times += times.last() + 480
        times += times.last() + 1120
        times += regular(10).map { times.last() + 800 + (it - 1000.0) }
        val shapes = times.indices.map { if (it == 31) snippet(wide = true) else snippet() }
        val f = run(times, shapes)
        val v = f[31]
        assertEquals(0.6, v.prematurity!!, 0.01)
        assertEquals(1.0, v.compensation!!, 0.01)
        assertTrue(!v.normalTimed)
        assertTrue(v.corr!! < 0.6, "corr ${v.corr}")
        assertTrue(v.qrsWidthMs!! > f[25].qrsWidthMs!! + 25, "width ${v.qrsWidthMs} vs ${f[25].qrsWidthMs}")
        assertTrue(v.areaRatio!! > 1.5, "area ${v.areaRatio}")
        assertTrue(!v.polarityFlip)
        // the beat after the pause is late, not premature, and the normal beats around keep their template
        assertEquals(1.4, f[32].prematurity!!, 0.01)
        assertTrue(f[33].corr!! > 0.97)
    }

    @Test
    fun anEarlyBeatWithNormalShapeIsPrematureButFitsTheTemplate() {
        val times = ArrayList(regular(31))
        times += times.last() + 560 // a PAC: early, normal shape
        times += times.last() + 800 // no full compensatory pause
        val f = run(times, times.map { snippet() })
        val s = f[31]
        assertEquals(0.7, s.prematurity!!, 0.01)
        assertEquals((560.0 + 800.0) / 1600.0, s.compensation!!, 0.01)
        assertTrue(s.corr!! > 0.97)
    }

    @Test
    fun polarityFlipIsReported() {
        val times = regular(40)
        val f = run(times, times.indices.map { if (it == 30) snippet(sign = -1.0) else snippet() })
        assertTrue(f[30].polarityFlip)
        assertTrue(f.filterIndexed { i, _ -> i != 30 }.none { it.polarityFlip })
    }

    @Test
    fun aBeatMarkedOnAnotherPointOfTheQrsGetsAnAlignment() {
        // beat 30 was marked 4 samples off (S instead of R, P3.8): its whole shape sits 4 samples
        // earlier in the snippet than in the other beats, so its shift is -4 samples
        val times = regular(60)
        val f = run(times, times.indices.map { if (it == 30) snippet(offset = -4) else snippet() })
        assertEquals(-4 * 1000.0 / fs, f[30].alignMs, 1.5)
        assertTrue(f.filterIndexed { i, _ -> i != 30 }.all { it.alignMs == 0.0 }, "other beats must stay untouched")
    }

    @Test
    fun smallAlignmentNoiseIsNotCorrected() {
        val times = regular(60)
        val f = run(times, times.indices.map { snippet(offset = if (it % 2 == 0) 0 else 1) })
        assertTrue(f.all { it.alignMs == 0.0 })
    }

    @Test
    fun featuresThatNeedAnythingMissingAreNull() {
        val times = regular(3)
        val f = run(times, listOf(snippet(), snippet(), snippet()))
        assertTrue(f.all { it.prematurity == null && it.corr == null }) // no reference RR, no template yet
        val x = BeatFeatureExtractor()
        val noShape = x.add(BeatInput(1000.0, null, true)) + x.add(BeatInput(1800.0, null, true)) + x.finish()
        assertEquals(2, noShape.size)
        assertNull(noShape[0].qrsWidthMs)
        assertNull(noShape[0].corr)
    }

    @Test
    fun poorBeatsNeitherTeachTheTemplateNorTheReferenceRr() {
        val times = regular(60)
        // every beat is poor: nothing is learned
        val f = run(times, times.map { snippet() }) { false }
        assertTrue(f.all { it.rrRefMs == null && it.corr == null })
    }

    @Test
    fun aBreakInTheSegmentDoesNotMakeAnInterval() {
        val x = BeatFeatureExtractor()
        val out = ArrayList<BeatFeatures>()
        for (t in regular(20)) out += x.add(BeatInput(t, snippet(), true))
        out += x.finish() // end of the segment
        for (t in regular(20).map { it + 600_000 }) out += x.add(BeatInput(t, snippet(), true))
        out += x.finish()
        assertEquals(40, out.size)
        assertNull(out[20].rrPreMs) // first beat of the second segment
        assertNull(out[19].rrPostMs)
        assertNotNull(out[21].corr) // the template carried over
    }

    // ---- the snippet from the beat stream ----

    @Test
    fun beatStreamDeliversSnippetsCentredOnTheBeat() {
        val rr = 0.8
        val n = (60 * fs).toInt()
        val x = IntArray(n) { j ->
            val t = j / fs
            var v = 20_000.0
            var b = 1.0
            while (b < 60.0) {
                if (abs(t - b) < 0.6) v += 1000 * gauss(t, b, 0.011) + 250 * gauss(t, b + 0.25, 0.045)
                b += rr
            }
            v.toInt()
        }
        val s = EcgBeatStream()
        val beats = s.process(x) + s.finish()
        assertTrue(beats.size > 60)
        val middle = beats.drop(5).dropLast(5)
        assertTrue(middle.all { it.snippet != null && it.snippet!!.size == BeatShape.SNIPPET_LENGTH })
        for (b in middle) {
            val sn = b.snippet!!
            val peak = (BeatShape.CENTER - 3..BeatShape.CENTER + 3).maxByOrNull { sn[it] }!!
            assertTrue(abs(peak - BeatShape.CENTER) <= 2, "R at ${peak - BeatShape.CENTER} samples from the centre")
        }
    }
}
