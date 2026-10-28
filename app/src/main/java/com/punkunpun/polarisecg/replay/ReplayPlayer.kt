package com.punkunpun.polarisecg.replay

import android.util.Log
import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.readStream
import com.punkunpun.polarisecg.polar.AccFrame
import com.punkunpun.polarisecg.polar.ConnectionState
import com.punkunpun.polarisecg.polar.EcgFrame
import com.punkunpun.polarisecg.polar.HrSample
import com.punkunpun.polarisecg.polar.PolarConnection
import com.punkunpun.polarisecg.processing.LiveProcessor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ReplayPlayer"

/** Pauses in the recording longer than this (a gap, a night with the strap off) are shortened to it, before the speed-up. */
private const val MAX_PAUSE_MS = 3_000L

/** Do not sleep for less than this: at high speeds most frames are due almost at once, and many tiny sleeps only cost time. */
private const val MIN_SLEEP_MS = 15L

/** What the replay is doing, for the screen. */
data class ReplayState(
    val running: Boolean = false,
    val loading: Boolean = false,
    val session: String? = null,
    /** 0..1 of the recording played so far. */
    val progress: Float = 0f,
    /** A problem to show (nothing to play, session not readable); null if all is well. */
    val message: String? = null,
)

/**
 * Plays a recorded session (docs/RAW_FORMAT.md) through the live pipeline at N times real speed (P5.2).
 *
 * The frames are published by [PolarConnection.replayEcg] and friends, i.e. through the flows the strap
 * would use, and processed by the same [LiveProcessor] as in a real session. So the live screen and the
 * beat counts can be tried at the desk, and the counts must equal what `:tools:cli analyze` finds in
 * the same session (the pipeline is the same code). Nothing is written: no recorder is involved.
 *
 * Do not start it while a real session runs ([PolarConnection] would mix both): the screen checks that.
 */
class ReplayPlayer(private val connection: PolarConnection, private val processor: LiveProcessor) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow(ReplayState())
    val state: StateFlow<ReplayState> = _state.asStateFlow()

    /**
     * Starts playing [sessionDir]. [speed] is the factor over real time; 0 or less means as fast as the
     * pipeline can go. Does nothing if a replay is already running.
     */
    @Synchronized
    fun start(sessionDir: File, speed: Double) {
        if (job?.isActive == true) return
        _state.value = ReplayState(loading = true, session = sessionDir.name)
        job = scope.launch {
            try {
                val records = withContext(Dispatchers.IO) { load(sessionDir) }
                if (records.isEmpty()) {
                    _state.value = ReplayState(session = sessionDir.name, message = "no readable records")
                    return@launch
                }
                _state.value = ReplayState(running = true, session = sessionDir.name)
                processor.start(fromConnection = false) // frames are handed over in order, see play()
                connection.replayState(ConnectionState.Streaming)
                play(records, speed, sessionDir.name)
            } catch (e: CancellationException) {
                throw e // a stop, not a failure
            } catch (e: Exception) {
                Log.e(TAG, "replay failed", e)
                _state.value = ReplayState(session = sessionDir.name, message = e.message ?: e.javaClass.simpleName)
            } finally {
                // the last beats of the replay are flushed by stop(), so the counts on the screen are complete
                processor.stop()
                connection.replayState(ConnectionState.Disconnected("replay"))
                _state.value = _state.value.copy(running = false, loading = false)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
    }

    /** All records of the three streams in the order they arrived (by phone time; equal times keep file order). */
    private fun load(dir: File): List<RawRecord> {
        val all = ArrayList<RawRecord>()
        for (prefix in listOf("ecg", "acc", "hr")) all += readStream(dir, prefix).records
        return all.sortedBy { it.phoneTimeMs } // sortedBy is stable
    }

    private suspend fun play(records: List<RawRecord>, speed: Double, session: String) {
        val first = records.first().phoneTimeMs
        val span = (records.last().phoneTimeMs - first).coerceAtLeast(1)
        var previous = first
        var owedMs = 0.0 // time that should have passed but was not slept yet
        for ((i, r) in records.withIndex()) {
            if (speed > 0) {
                owedMs += (r.phoneTimeMs - previous).coerceIn(0, MAX_PAUSE_MS) / speed
                if (owedMs >= MIN_SLEEP_MS) {
                    delay(owedMs.toLong())
                    owedMs = 0.0
                }
            }
            previous = r.phoneTimeMs
            when (r) {
                // To the pipeline directly (strict order, back-pressure), and through the connection's flows for the live screen.
                is RawRecord.Ecg -> EcgFrame(r.sensorTimeNs, r.phoneTimeMs, r.samplesUv).let { processor.submitEcg(it); connection.replayEcg(it) }
                is RawRecord.Acc -> AccFrame(r.sensorTimeNs, r.phoneTimeMs, r.xMg, r.yMg, r.zMg).let { processor.submitAcc(it); connection.replayAcc(it) }
                is RawRecord.Hr -> connection.replayHr(HrSample(r.phoneTimeMs, r.heartRateBpm, r.rrMs, r.contact))
            }
            if (i % 50 == 0) _state.value = ReplayState(running = true, session = session, progress = (r.phoneTimeMs - first).toFloat() / span)
        }
    }
}
