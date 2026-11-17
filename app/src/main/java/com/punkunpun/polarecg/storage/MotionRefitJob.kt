package com.punkunpun.polarecg.storage

import com.punkunpun.polarecg.dsp.motion.MotionIndex
import com.punkunpun.polarecg.dsp.motion.buildMotionBins
import com.punkunpun.polarecg.dsp.motion.refitMotion
import com.punkunpun.polarecg.dsp.raw.RawRecord
import com.punkunpun.polarecg.dsp.raw.readStream
import java.io.File

/** How many beats were looked at and how many got a different motion. */
class MotionRefitResult(val checked: Int, val changed: Int, val noAcc: Boolean)

/**
 * Works the per-beat motion of one recorded session out again from its raw ACC files and fixes the saved beats
 * (see `refitMotion` in :core-dsp for what is changed and what is not). Needed once, for beats saved before the ACC time-line
 * fix in `MotionStream`. Running it again changes nothing. Blocking: call it off the UI thread; not while a session is recording.
 */
fun refitSessionMotion(sessionDir: File, dao: BeatDao): MotionRefitResult {
    val acc = readStream(sessionDir, "acc").records.filterIsInstance<RawRecord.Acc>()
    if (acc.isEmpty()) return MotionRefitResult(0, 0, noAcc = true)
    val index = MotionIndex(buildMotionBins(acc))
    val from = acc.minOf { it.phoneTimeMs } - 60_000L // phone time of a frame is a little after its samples
    val to = acc.maxOf { it.phoneTimeMs } + 1_000L

    var checked = 0
    var changed = 0
    // an hour at a time (~4 000 rows), so that the whole session never sits in memory as rows
    var start = from
    while (start < to) {
        val end = minOf(start + 3_600_000L, to)
        val fixed = ArrayList<BeatEntity>()
        for (b in dao.beatsBetween(start, end)) {
            checked++
            val r = refitMotion(b.sqi, b.motionMg, b.good, index.around(b.utcMs)) ?: continue
            val moved = b.motionMg == null || kotlin.math.abs(b.motionMg - r.motionMg) > 0.05
            if (moved || r.good != b.good) {
                fixed += b.copy(motionMg = r.motionMg, sqi = r.sqi, good = r.good) // `copy` makes a changed copy of a data class
                changed++
            }
        }
        if (fixed.isNotEmpty()) dao.insertAllBlocking(fixed)
        start = end
    }
    return MotionRefitResult(checked, changed, noAcc = false)
}
