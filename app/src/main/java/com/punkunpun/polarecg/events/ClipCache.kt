package com.punkunpun.polarecg.events

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the beat windows (ECG + beats around one beat) of the beats near the one on the screen, so that Next and Previous show
 * at once (the raw files behind a window are read from the start of the session: 20-40 MB for a night, so a window takes a while).
 *
 * [prefetch] is called when a beat is opened: it starts loading the next beat and the one after it (so the next press of Next
 * finds the window ready, and the press after it too), and the previous beat, and forgets every other window. What is loading
 * is shared: [clip] for a beat that is already loading waits for that load instead of starting a second one.
 *
 * - A window is kept for [ttlMs] at most: the files of a recording in progress grow, and a window cut near their end is short.
 * - A beat less than [freshMs] before [now] is never kept, for the same reason.
 * - Thread-safe: the map is guarded by a lock; the loads run on [Dispatchers.IO].
 *
 * @param load reads one window (blocking, heavy); null means "no ECG for this beat" and is kept like any other result.
 */
class ClipCache<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (Long) -> T?,
    private val ttlMs: Long = 120_000L,
    private val freshMs: Long = 30_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Entry<T : Any>(val result: Deferred<T?>, val at: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Entry<T>>() // insertion order: the oldest first
    private var prefetching: Job? = null
    private var current = Long.MIN_VALUE // the beat on the screen: its window is never dropped, even by a late prefetch of an earlier beat

    /** The screen asks for the window of the beat it is opening: like [clip], and the window is protected from being dropped. */
    fun open(beatUtcMs: Long): Deferred<T?> {
        synchronized(lock) { current = beatUtcMs }
        return clip(beatUtcMs)
    }

    /** The window of [beatUtcMs]: from the cache, from a load already running, or a new load. */
    fun clip(beatUtcMs: Long): Deferred<T?> = synchronized(lock) {
        val t = now()
        entries[beatUtcMs]?.let { if (t - it.at <= ttlMs) return it.result else entries.remove(beatUtcMs)?.result?.cancel() }
        val deferred = scope.async(Dispatchers.IO) {
            try {
                load(beatUtcMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // an unreadable file is "no ECG" for this beat; the next try (after the TTL) may read it
            }
        }
        // a window too close to the end of the recording is cut short now and would stay short: use it, do not keep it
        if (t - beatUtcMs >= freshMs) entries[beatUtcMs] = Entry(deferred, t)
        deferred
    }

    /**
     * [center] was opened. [beats] gives the ids of the beats of the list being browsed in time order (it runs on the IO thread).
     * Starts loading the next two beats and the previous one, and drops the windows of all other beats. Replaces a prefetch that is
     * still working out its neighbours for a beat that has been left already.
     */
    fun prefetch(center: Long, beats: suspend () -> List<Long>) {
        synchronized(lock) { current = center }
        val job = scope.launch {
            val list = withContext(Dispatchers.IO) { beats() }
            val i = list.indexOf(center)
            val wanted = if (i < 0) emptyList() else listOfNotNull(list.getOrNull(i + 1), list.getOrNull(i + 2), list.getOrNull(i - 1))
            for (b in wanted) clip(b) // the next one first, so it is the first to be read
            retainOnly(setOf(center) + wanted)
        }
        synchronized(lock) {
            prefetching?.cancel()
            prefetching = job
        }
    }

    private fun retainOnly(keep: Set<Long>) = synchronized(lock) {
        val it = entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep && e.key != current) {
                e.value.result.cancel() // a no-op for a finished load
                it.remove()
            }
        }
    }

    /** Number of windows held or loading (for tests). */
    val size: Int get() = synchronized(lock) { entries.size }
}
