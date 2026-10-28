package com.punkunpun.polarisecg.cli

import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.analysis.analyzeEcg
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.readStream
import java.io.File
import java.time.Instant

/**
 * `live-check <session>`: what the phone does, on the PC. Feeds the session to [LivePipeline] in arrival
 * order (ECG and ACC frames interleaved by phone time, as in a replay or a real night) and compares the
 * beats with the whole-recording `analyze` of the same data. Any difference comes from the live ordering.
 */
internal fun liveCheckCommand(folder: File): Int {
    val ecg = readStream(folder, "ecg").records.filterIsInstance<RawRecord.Ecg>()
    val acc = readStream(folder, "acc").records.filterIsInstance<RawRecord.Acc>()
    if (ecg.isEmpty()) { System.err.println("No ECG records found in $folder"); return 1 }

    val batch = analyzeEcg(ecg, acc).beats

    val pipeline = LivePipeline()
    val live = ArrayList<Beat>()
    for (r in (ecg + acc).sortedBy { it.phoneTimeMs }) { // sortedBy is stable
        when (r) {
            is RawRecord.Ecg -> live += pipeline.addEcg(r.sensorTimeNs, r.phoneTimeMs, r.samplesUv)
            is RawRecord.Acc -> live += pipeline.addAcc(r.sensorTimeNs, r.phoneTimeMs, r.xMg, r.yMg, r.zMg)
            else -> {}
        }
    }
    live += pipeline.finish()

    fun counts(b: List<Beat>) = "beats %d  N %d  S %d  V %d (%d questionable)  Q %d".format(
        b.size, b.count { it.cls == "N" }, b.count { it.cls == "S" }, b.count { it.cls == "V" },
        b.count { it.cls == "V" && it.vDoubt }, b.count { it.cls == "Q" },
    )
    println("batch: ${counts(batch)}")
    println("live:  ${counts(live)}")

    val liveByTime = live.associateBy { it.utcMs }
    val different = batch.filter { b -> liveByTime[b.utcMs]?.let { it.cls != b.cls || it.vDoubt != b.vDoubt || it.good != b.good } != false }
    println("beats missing or different in live: ${different.size}")
    for (b in different.take(20)) {
        val l = liveByTime[b.utcMs]
        println(
            "  %s batch %s%s motion %s | live %s".format(
                Instant.ofEpochMilli(b.utcMs), b.cls, if (b.vDoubt) "?" else "", b.quality.motionMg?.let { "%.0f".format(it) } ?: "-",
                l?.let { "${it.cls}${if (it.vDoubt) "?" else ""} motion ${it.quality.motionMg?.let { m -> "%.0f".format(m) } ?: "-"}" } ?: "missing",
            ),
        )
    }
    return 0
}
