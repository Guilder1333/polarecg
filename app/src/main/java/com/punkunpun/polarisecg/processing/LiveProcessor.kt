package com.punkunpun.polarisecg.processing

import android.util.Log
import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.classify.BeatClass
import com.punkunpun.polarisecg.dsp.live.LivePipeline
import com.punkunpun.polarisecg.polar.AccFrame
import com.punkunpun.polarisecg.polar.EcgFrame
import com.punkunpun.polarisecg.polar.PolarConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

private const val TAG = "LiveProcessor"

/** Frames a replay may have queued ahead of the pipeline. */
private const val REPLAY_QUEUE = 256

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
    /** Phone time of [LiveProcessor.start]. After a kill and resume the counts cover only the time since then. */
    val startedMs: Long = 0,
) {
    /** For `session.json` ("liveStats"): to compare with `analyze` of the same session's raw files (P5.1). */
    fun toJson(): JSONObject = JSONObject()
        .put("sinceUtc", Instant.ofEpochMilli(startedMs).toString())
        .put("lastBeatUtc", lastBeatUtcMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
        .put("beats", beats)
        .put("normal", normal)
        .put("premature", premature)
        .put("ventricular", ventricular)
        .put("ventricularDoubt", ventricularDoubt)
        .put("unclassifiable", unclassifiable)
        .put("errors", errors)
}

/**
 * Runs the [LivePipeline] (`:core-dsp`, the same code as the PC tool) on everything [connection] streams (P5.1).
 *
 * Threading: the Polar SDK delivers on its own threads; [PolarConnection] republishes the frames in
 * SharedFlows. Two cheap forwarders put the ECG and ACC frames into ONE queue in the order they arrive, and one
 * coroutine on a background thread takes them out one at a time (the pipeline requires arrival order, and the two
 * streams must not run ahead of each other: beats wait for the motion of their second). Never on the UI thread.
 * Each frame takes a few milliseconds (it is half a second of data).
 *
 * [start] begins with a fresh pipeline (template, quality history), [stop] flushes the last beats.
 * Nothing is stored yet (Room comes in P5.3); finished beats go to [beats] and into [stats].
 */
class LiveProcessor(private val connection: PolarConnection) {
    private sealed interface Input {
        class Ecg(val frame: EcgFrame) : Input
        class Acc(val frame: AccFrame) : Input
    }

    // Dispatchers.Default is a pool for CPU work; limitedParallelism(1) lets only one coroutine run at a time on it.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private var scope: CoroutineScope? = null
    private var collectors: List<Job> = emptyList()
    private var queue: Channel<Input>? = null
    private var worker: Job? = null
    private var stats = LiveStats()

    private val _stats = MutableStateFlow(LiveStats())
    val statsFlow: StateFlow<LiveStats> = _stats.asStateFlow()

    // extraBufferCapacity: a slow collector (the later database writer) must not block the pipeline.
    private val _beats = MutableSharedFlow<Beat>(extraBufferCapacity = 1024)
    val beats: SharedFlow<Beat> = _beats.asSharedFlow()

    /**
     * Starts with a fresh pipeline. With [fromConnection] (a real session) the frames of [connection] are
     * processed; without it (replay, P5.2) the caller hands them over with [submitEcg] / [submitAcc], in order
     * and with back-pressure, so that a replay at any speed gives the same beats as `analyze`.
     */
    @Synchronized
    fun start(fromConnection: Boolean = true) {
        if (scope != null) return
        val pipeline = LivePipeline()
        stats = LiveStats(startedMs = System.currentTimeMillis())
        _stats.value = stats
        val s =CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        // Real strap: never block the SDK's callbacks, the queue is unbounded (a few frames per second). Replay: bounded.
        val q = Channel<Input>(if (fromConnection) Channel.UNLIMITED else REPLAY_QUEUE)
        queue = q
        // UNDISPATCHED: run on this thread up to the first suspension, i.e. until collect() has subscribed. A SharedFlow
        // forgets what was emitted before a collector subscribed, and a replay (P5.2) emits right after start().
        collectors = if (fromConnection) listOf(
            s.launch(start = CoroutineStart.UNDISPATCHED) { connection.ecg.collect { q.trySend(Input.Ecg(it)) } },
            s.launch(start = CoroutineStart.UNDISPATCHED) { connection.acc.collect { q.trySend(Input.Acc(it)) } },
        ) else emptyList()
        worker = s.launch {
            for (input in q) { // ends when the queue is closed and empty, see stop()
                when (input) {
                    is Input.Ecg -> guarded { pipeline.addEcg(input.frame.sensorTimeNs, input.frame.phoneTimeMs, input.frame.samplesUv) }
                    is Input.Acc -> input.frame.let { f -> guarded { pipeline.addAcc(f.sensorTimeNs, f.phoneTimeMs, f.xMg, f.yMg, f.zMg) } }
                }
            }
            guarded { pipeline.finish() } // after the last frame: the beats that were still waiting
            s.cancel()
        }
    }

    /** For a replay: hands over one frame, waiting if the pipeline is busy. */
    suspend fun submitEcg(frame: EcgFrame) { queue?.send(Input.Ecg(frame)) }
    suspend fun submitAcc(frame: AccFrame) { queue?.send(Input.Acc(frame)) }

    /**
     * Stops taking frames and publishes the beats that were still waiting (after the frames already queued).
     * Returns the processing job: `join()` it to know that [statsFlow] holds the final counts (null if not running).
     */
    @Synchronized
    fun stop(): Job? {
        if (scope == null) return null
        scope = null
        collectors.forEach { it.cancel() }
        queue?.close() // the processing coroutine drains what is queued, flushes, and ends
        queue = null
        return worker.also { worker = null }
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
