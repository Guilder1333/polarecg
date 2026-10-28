package com.punkunpun.polarisecg.dsp.live

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.SegmentClock
import com.punkunpun.polarisecg.dsp.analysis.SegmentTracker
import com.punkunpun.polarisecg.dsp.beat.BeatFeatureExtractor
import com.punkunpun.polarisecg.dsp.beat.BeatFeatures
import com.punkunpun.polarisecg.dsp.beat.BeatInput
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.classify.BeatClassifier
import com.punkunpun.polarisecg.dsp.motion.MOTION_BIN_MS
import com.punkunpun.polarisecg.dsp.motion.MotionBin
import com.punkunpun.polarisecg.dsp.motion.MotionStream
import com.punkunpun.polarisecg.dsp.quality.BeatQuality
import com.punkunpun.polarisecg.dsp.quality.EcgBeatStream
import com.punkunpun.polarisecg.dsp.quality.QualityConfig
import com.punkunpun.polarisecg.dsp.quality.ScoredBeat

/**
 * The whole beat pipeline, frame by frame (plan §4, P5.1): ECG frames and accelerometer frames in,
 * finished [Beat]s out (time, quality, features, class N/S/V/Q, `vDoubt`, `rescued`).
 *
 * This is the one implementation: `analyzeEcg` (the PC tool) feeds a whole recording through it, the
 * phone feeds the frames as they arrive, so the two cannot disagree.
 *
 * A beat comes out late, for four reasons that add up to about 2-3 s:
 * - the frame after it must be here (its time is final then),
 * - the quality index looks 0.4 s *after* the beat (noise around it),
 * - the motion of the second around the beat must be complete (the accelerometer lags a little),
 * - the classifier needs the *next* beat (RR after, compensatory pause).
 * If the accelerometer stops, beats wait for it at most [MOTION_STALL_MS]; without any ACC data they
 * are not delayed and just have no motion score.
 *
 * Call [addEcg] / [addAcc] in arrival order from ONE thread at a time (not thread-safe), and [finish]
 * at the end to get the last beats. After [finish] the pipeline can go on with a new stretch of signal.
 */
class LivePipeline(
    private val qualityConfig: QualityConfig = QualityConfig(),
    /** How much accelerometer history is kept; a live pipeline needs seconds, `analyzeEcg` wants it all (see [motionBins]). */
    motionRetainMs: Long = 10 * 60_000L,
) {
    private class Pending(val position: Double, val segment: Int, val quality: BeatQuality, val snippet: DoubleArray?)

    private val tracker = SegmentTracker()
    private val motion = MotionStream(retainMs = motionRetainMs)
    // one extractor for the whole run: its normal-beat template carries over gaps (P4.2)
    private val extractor = BeatFeatureExtractor()
    private val classifier = BeatClassifier()

    private var segment = -1
    private var clock: SegmentClock? = null
    private var stream: EcgBeatStream? = null
    private var samplesInSegment = 0
    private var previousBeatUtc: Long? = null

    private val pending = ArrayDeque<Pending>() // scored, waiting for the motion of their second
    private val awaitingFeatures = ArrayDeque<Beat>() // created, waiting for the next beat to compute their features

    /** Index of the gap-free segment being processed (-1 before the first frame). */
    val segmentIndex: Int get() = segment

    /** Motion bins of the accelerometer data kept so far (all of it with `motionRetainMs` = Long.MAX_VALUE). */
    fun motionBins(): List<MotionBin> = motion.bins()

    /** One accelerometer frame. Returns beats that were waiting for it. */
    fun addAcc(sensorTimeNs: Long, phoneTimeMs: Long, xMg: IntArray, yMg: IntArray, zMg: IntArray): List<Beat> {
        motion.add(sensorTimeNs, phoneTimeMs, xMg, yMg, zMg)
        val out = ArrayList<Beat>()
        release(out, final = false)
        return out
    }

    /** One ECG frame (µV). Returns the beats that are finished now, in time order. */
    fun addEcg(sensorTimeNs: Long, phoneTimeMs: Long, samplesUv: IntArray): List<Beat> {
        val out = ArrayList<Beat>()
        if (samplesUv.isEmpty()) return out
        if (tracker.add(sensorTimeNs, phoneTimeMs, samplesUv.size)) {
            endSegment(out)
            segment++
            clock = SegmentClock(tracker.segmentStartUtcMs)
            stream = EcgBeatStream(qualityConfig) // detectors never run across a gap
            samplesInSegment = 0
            previousBeatUtc = null
        }
        clock!!.addFrame(samplesInSegment, sensorTimeNs)
        samplesInSegment += samplesUv.size
        enqueue(stream!!.process(samplesUv))
        release(out, final = false)
        return out
    }

    /** End of the signal (or of a stretch): the beats still waiting, judged with what is known. */
    fun finish(): List<Beat> {
        val out = ArrayList<Beat>()
        endSegment(out)
        clock = null
        stream = null
        return out
    }

    private fun endSegment(out: MutableList<Beat>) {
        val s = stream ?: return
        enqueue(s.finish())
        release(out, final = true)
        assign(extractor.finish(), out) // intervals are never computed across a gap
    }

    private fun enqueue(scored: List<ScoredBeat>) {
        val c = clock ?: return
        for (b in scored) pending += Pending(b.position, segment, b.quality, b.snippet)
    }

    /**
     * Moves beats from [pending] on to the feature extractor once their time is final (the frame after them is
     * here, see [SegmentClock.hasFrameAfter]; this makes live times equal to those of a whole recording) and the
     * motion of their second is known.
     */
    private fun release(out: MutableList<Beat>, final: Boolean) {
        val c = clock ?: return
        while (pending.isNotEmpty()) {
            val p = pending.first()
            val utcMs = c.sampleUtcMs(p.position)
            if (!final && !(c.hasFrameAfter(p.position) && motionKnown(utcMs))) break
            pending.removeFirst()
            val quality = p.quality.withMotion(motion.around(utcMs), qualityConfig)
            val good = quality.sqi >= qualityConfig.goodBeatSqi
            val beat = Beat(utcMs, p.segment, previousBeatUtc?.let { utcMs - it }, BeatClass.UNKNOWN, quality, good)
            previousBeatUtc = utcMs
            awaitingFeatures += beat
            assign(extractor.add(BeatInput(utcMs.toDouble(), p.snippet, good)), out)
        }
    }

    /** Features come out of the extractor in input order, one per beat: attach them and classify. */
    private fun assign(features: List<BeatFeatures>, out: MutableList<Beat>) {
        for (f in features) {
            val b = awaitingFeatures.removeFirst()
            b.features = f
            b.cls = classifier.classify(f, b.quality)
            b.vDoubt = classifier.vNeedsReview(f, b.quality)
            b.rescued = classifier.rescues(f, b.quality)
            out += b
        }
    }

    /** True if the bins that a beat at [utcMs] looks at (the second around it) are complete, or no ACC data will come. */
    private fun motionKnown(utcMs: Long): Boolean {
        val watermark = motion.watermarkUtcMs
        val ecgNow = clock?.sampleUtcMs(samplesInSegment.toDouble()) ?: return true
        if (watermark == null || ecgNow - watermark > MOTION_STALL_MS) return true // no ACC data, or it stopped
        val lastBin = Math.floorDiv(utcMs + MOTION_HALF_WIDTH_MS, MOTION_BIN_MS)
        return watermark >= (lastBin + 1) * MOTION_BIN_MS
    }

    private companion object {
        const val MOTION_STALL_MS = 3_000L
        const val MOTION_HALF_WIDTH_MS = 500L // the same as MotionStream.around
    }
}
