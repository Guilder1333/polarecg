package com.punkunpun.polarisecg.storage

import android.util.Log
import com.punkunpun.polarisecg.dsp.analysis.Beat
import com.punkunpun.polarisecg.dsp.hrv.HrvWindow
import com.punkunpun.polarisecg.dsp.summary.MinuteSummary
import com.punkunpun.polarisecg.dsp.summary.SummaryBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

private const val TAG = "BeatWriter"

/** Beats are written at the latest this long after the first one of a batch arrived... */
private const val FLUSH_AFTER_MS = 5_000L

/** ...or as soon as this many have collected. */
private const val MAX_BATCH = 500

/** Beats older than this are deleted when recording starts (plan §8: `beat` rows, e.g. 90 days). */
const val BEAT_RETENTION_DAYS = 90L

/**
 * Writes the beats of the live pipeline into the database in batches (P5.3): about one transaction every
 * five seconds instead of one per beat, which is kinder to the flash storage and the battery.
 *
 * [start] subscribes to the beats; [finish] ends it after the pipeline has flushed its last beats, so that
 * nothing waits in memory. If the process is killed, at most the last five seconds of beats are lost (the raw
 * files still have them).
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
class BeatWriter(
    private val dao: BeatDao,
    private val summaryDao: SummaryDao,
    private val flushAfterMs: Long = FLUSH_AFTER_MS,
) {
    private var scope: CoroutineScope? = null
    private var queue: Channel<Beat>? = null
    private var subscriber: Job? = null

    /**
     * Saves finished minutes and 5-minute windows. A row for the same time may already exist (Stop, then Start within the same
     * minute or window): the fuller one wins, so a restart never replaces a good row by a stump.
     */
    private suspend fun writeSummaries(minutes: List<MinuteSummary>, windows: List<HrvWindow>) {
        if (minutes.isEmpty() && windows.isEmpty()) return
        try {
            summaryDao.insertMinutes(minutes.map { it.toEntity() }.filter { m -> summaryDao.minuteAt(m.minuteUtcMs)?.let { it.beats >= m.beats } != true })
            summaryDao.insertHrv(windows.map { it.toEntity() }.filter { h -> summaryDao.hrvAt(h.startUtcMs)?.let { it.coverage >= h.coverage } != true })
        } catch (e: Exception) {
            Log.e(TAG, "could not write summaries", e)
        }
    }

    @Synchronized
    fun start(beats: SharedFlow<Beat>) {
        if (scope != null) return
        val summaries = SummaryBuilder() // one per start, kept by this coroutine only: nothing is carried across a restart
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val q = Channel<Beat>(Channel.UNLIMITED)
        queue = q
        // UNDISPATCHED: subscribe before start() returns, a SharedFlow forgets what it emitted before that.
        subscriber = s.launch(start = CoroutineStart.UNDISPATCHED) { beats.collect { q.trySend(it) } }
        s.launch {
            try {
                dao.deleteBefore(System.currentTimeMillis() - BEAT_RETENTION_DAYS * 86_400_000L)
            } catch (e: Exception) {
                Log.e(TAG, "retention failed", e)
            }
            while (true) {
                val first = q.receiveCatching().getOrNull() ?: break // null = closed and empty
                val batch = arrayListOf(first)
                // Collect more for up to flushAfterMs. `select` takes either the next beat or the timeout, never both:
                // cancelling a plain `receive` with withTimeoutOrNull could drop a beat that arrives at that very moment
                // (seen on the phone: one or two normal beats an hour missing from the database).
                val deadline = System.nanoTime() + flushAfterMs * 1_000_000L
                while (batch.size < MAX_BATCH) {
                    val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (leftMs <= 0) break
                    val next = select<ChannelResult<Beat>?> {
                        q.onReceiveCatching { it }
                        onTimeout(leftMs) { null }
                    } ?: break // timed out
                    batch += next.getOrNull() ?: break // closed: write what we have, the outer loop then ends
                }
                try {
                    dao.insertAll(batch.map { it.toEntity() })
                } catch (e: Exception) {
                    Log.e(TAG, "could not write ${batch.size} beats", e)
                }
                // The long-term rows (P5.6) are built from the same beats, in order, here in the one writer coroutine.
                val closed = batch.map { summaries.add(it) }
                writeSummaries(closed.flatMap { it.minutes }, closed.flatMap { it.hrv })
            }
            val last = summaries.finish() // a normal stop: the minute and window still open are complete enough to keep
            writeSummaries(last.minutes, last.hrv)
            s.cancel()
        }
    }

    /**
     * Stops after [pipeline] (the job of the live processor, which publishes its last beats when it ends) is done,
     * and after the queued beats are written. Returns at once; the work finishes in the background.
     */
    @Synchronized
    fun finish(pipeline: Job?) {
        val s = scope ?: return
        val q = queue
        val sub = subscriber
        scope = null
        queue = null
        subscriber = null
        CoroutineScope(Dispatchers.IO).launch {
            pipeline?.join()
            sub?.cancel() // no more beats are coming
            q?.close() // the writer drains what is queued and ends
        }
    }
}
