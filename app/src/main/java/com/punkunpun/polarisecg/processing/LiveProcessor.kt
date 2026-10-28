package com.punkunpun.polarisecg.processing

import android.util.Log
import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.polar.PolarConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "LiveProcessor"

/** What the pipeline has found since [LiveProcessor.start]: beats per class, and the latest one. */
data class LiveStats(
    val beats: Int = 0,
    val normal: Int = 0,
    val premature: Int = 0,
    val ventricular: Int = 0,
    /** Of the [ventricular] beats, how many are questionable (`Beat.vDoubt`). */
    val ventricularDoubt: Int = 0,
    val unclassifiable: Int = 0,
    val lastBeatUtcMs: Long? = null,
    /** Frames that made the pipeline throw (logged, the frame is skipped). Should stay 0. */
    val errors: Int = 0,
)

/**
 * Runs the [LivePipeline] (`:core-dsp`, the same code as the PC tool) on everything [connection] streams (P5.1).
 *
 * Threading: the Polar SDK delivers on its own threads; [PolarConnection] republishes the frames in
 * SharedFlows. Here both streams are collected on ONE background thread (`limitedParallelism(1)`), so
 * frames are processed one at a time in arrival order, which the pipeline requires, and never on the UI
 * thread. Each frame takes a few milliseconds (it is half a second of data).
 *
 * [start] begins with a fresh pipeline (template, quality history), [stop] flushes the last beats.
 * Nothing is stored yet (Room comes in P5.3); finished beats go to [beats] and into [stats].
 */
class LiveProcessor(private val connection: PolarConnection) {
    // Dispatchers.Default is a pool for CPU work; limitedParallelism(1) lets only one coroutine run at a time on it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private var scope: CoroutineScope? = null
    private var collectors: List<Job> = emptyList()
    private var pipeline = LivePipeline()
    private var stats = LiveStats()

    private val _stats = MutableStateFlow(LiveStats())
    val statsFlow: StateFlow<LiveStats> = _stats.asStateFlow()

    // extraBufferCapacity: a slow collector (the later database writer) must not block the pipeline.
    private val _beats = MutableSharedFlow<Beat>(extraBufferCapacity = 1024)
    val beats: SharedFlow<Beat> = _beats.asSharedFlow()

    @Synchronized
    fun start() {
        if (scope != null) return
        pipeline = LivePipeline()
        stats = LiveStats()
        _stats.value = stats
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        val p = pipeline
        collectors = listOf(
            s.launch { connection.ecg.collect { f -> guarded { p.addEcg(f.sensorTimeNs, f.phoneTimeMs, f.samplesUv) } } },
            s.launch { connection.acc.collect { f -> guarded { p.addAcc(f.sensorTimeNs, f.phoneTimeMs, f.xMg, f.yMg, f.zMg) } } },
        )
    }

    /** Stops collecting and publishes the beats that were still waiting. */
    @Synchronized
    fun stop() {
        val s = scope ?: return
        scope = null
        // A frame is handled without suspending, so cancelling never cuts one in half. The flush is queued on the
        // same single thread, hence after the last frame; it uses the pipeline of THIS run (a new start() makes another).
        collectors.forEach { it.cancel() }
        val p = pipeline
        s.launch { guarded { p.finish() } }.invokeOnCompletion { s.cancel() }
    }

    /** Runs [step] and publishes the beats it returns; an exception costs this frame, not the service. */
    private fun guarded(step: () -> List<Beat>) {
        try {
            for (b in step()) publish(b)
        } catch (e: Exception) {
            Log.e(TAG, "pipeline failed on a frame", e)
            stats = stats.copy(errors = stats.errors + 1)
            _stats.value = stats
        }
    }

    private fun publish(b: Beat) {
        stats = stats.copy(
            beats = stats.beats + 1,
            normal = stats.normal + if (b.cls == BeatClass.NORMAL) 1 else 0,
            premature = stats.premature + if (b.cls == BeatClass.PREMATURE) 1 else 0,
            ventricular = stats.ventricular + if (b.cls == BeatClass.VENTRICULAR) 1 else 0,
            ventricularDoubt = stats.ventricularDoubt + if (b.cls == BeatClass.VENTRICULAR && b.vDoubt) 1 else 0,
            unclassifiable = stats.unclassifiable + if (b.cls == BeatClass.UNCLASSIFIABLE) 1 else 0,
            lastBeatUtcMs = b.utcMs,
        )
        _stats.value = stats
        _beats.tryEmit(b)
    }
}
