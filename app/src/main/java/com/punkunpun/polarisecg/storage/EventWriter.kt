package com.punkunpun.polarisecg.storage

import android.content.Context
import android.util.Log
import com.punkunpun.polarisecg.dsp.event.ClipFormat
import com.punkunpun.polarisecg.dsp.event.RecordedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "EventWriter"

/** Where the ECG clips live: `files/clips/<eventId>.bin` (plan §8). Private to the app, like the database. */
fun clipsDir(context: Context) = File(context.filesDir, "clips")

/**
 * Saves the events of the live pipeline (P5.4): a row in the `event` table, and the ECG clip as a file when the pipeline cut one.
 * Events are rare (a few an hour, a few dozen at worst), so there is no batching: one row and one 12 KB file at a time.
 *
 * Order: row first (gives the id), then the file under that id (written to a temporary name and moved, so a kill never
 * leaves half a clip under a real name), then the row learns the file name. A kill in between leaves a row without clip or a
 * file without row, never a row pointing to a broken file. An existing file is never overwritten (if the database was
 * deleted the ids start again at 1: the new clip gets another name).
 *
 * Same lifecycle as [BeatWriter]: [start] subscribes, [finish] ends after the pipeline has flushed its last events.
 */
class EventWriter(private val dao: EventDao, private val clipsDir: File) {
    private var scope: CoroutineScope? = null
    private var queue: Channel<RecordedEvent>? = null
    private var subscriber: Job? = null

    @Synchronized
    fun start(events: SharedFlow<RecordedEvent>) {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val q = Channel<RecordedEvent>(Channel.UNLIMITED)
        queue = q
        // UNDISPATCHED: subscribe before start() returns (a SharedFlow forgets what it emitted before a collector came).
        subscriber = s.launch(start = CoroutineStart.UNDISPATCHED) { events.collect { q.trySend(it) } }
        s.launch {
            for (e in q) save(e) // ends when the queue is closed and empty
            s.cancel()
        }
    }

    /** Stops after [pipeline] (the live processor's job) is done and the queued events are written. Returns at once. */
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
            sub?.cancel()
            q?.close()
        }
    }

    private suspend fun save(e: RecordedEvent) {
        try {
            val id = dao.insert(e.toEntity())
            val clip = e.clip ?: return
            clipsDir.mkdirs()
            val target = freeName(id)
            val temp = File(clipsDir, "${target.name}.tmp")
            temp.writeBytes(ClipFormat.encode(clip))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            dao.setClipFile(id, target.name)
        } catch (ex: Exception) {
            Log.e(TAG, "could not save the ${e.event.type} at ${e.event.utcMs}", ex)
        }
    }

    /** `<id>.bin`, or `<id>_1.bin`, `<id>_2.bin`... if that name is taken. */
    private fun freeName(id: Long): File {
        var f = File(clipsDir, "$id.bin")
        var n = 0
        while (f.exists()) f = File(clipsDir, "${id}_${++n}.bin")
        return f
    }
}
