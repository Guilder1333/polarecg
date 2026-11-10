package com.punkunpun.polarecg

import com.punkunpun.polarecg.events.ClipCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loads = ConcurrentHashMap<Long, AtomicInteger>()
    private fun loadCount(beat: Long) = loads[beat]?.get() ?: 0

    /** Beats at 100, 200, ... 900 s: far from "now" (the clock below), so they can be kept. */
    private val beats = (1..9).map { it * 100_000L }
    private val now = 10_000_000L

    private fun cache(
        ttlMs: Long = 120_000L,
        clock: () -> Long = { now },
        load: suspend (Long) -> String? = { ms -> "clip$ms" },
    ) = ClipCache(scope, { ms -> loads.getOrPut(ms) { AtomicInteger() }.incrementAndGet(); delay(20); load(ms) }, ttlMs = ttlMs, now = clock)

    private suspend fun settle(cache: ClipCache<String>, vararg expected: Long) {
        for (i in 0 until 100) { if (cache.size == expected.size) return; delay(10) }
    }

    @Test fun aWindowIsLoadedOnceHoweverOftenItIsAsked() = runBlocking {
        val c = cache()
        assertEquals("clip300000", c.clip(300_000L).await())
        assertEquals("clip300000", c.clip(300_000L).await())
        val both = listOf(c.clip(300_000L), c.clip(300_000L)) // asked while it is already there
        assertEquals(listOf("clip300000", "clip300000"), both.map { it.await() })
        assertEquals(1, loadCount(300_000L))
    }

    @Test fun openingABeatLoadsTheNextTwoAndThePreviousOne() = runBlocking {
        val c = cache()
        c.open(500_000L).await()
        c.prefetch(500_000L) { beats }
        settle(c, 400_000L, 500_000L, 600_000L, 700_000L)
        assertEquals(4, c.size)
        for (b in listOf(400_000L, 600_000L, 700_000L)) assertEquals("clip$b", c.clip(b).await())
        assertEquals(listOf(1, 1, 1, 1), listOf(400_000L, 500_000L, 600_000L, 700_000L).map(::loadCount)) // no second load
        assertEquals(0, loadCount(800_000L)) // only two ahead
    }

    @Test fun movingOnLoadsOneMoreAheadAndForgetsTheOldOnes() = runBlocking {
        val c = cache()
        c.open(300_000L).await(); c.prefetch(300_000L) { beats }; settle(c, 200_000L, 300_000L, 400_000L, 500_000L)
        // Next: the window of 400 is there; 500 was loaded already, 600 starts now; 200 is dropped, 300 stays as "previous"
        c.open(400_000L).await(); c.prefetch(400_000L) { beats }
        for (i in 0 until 100) { if (loadCount(600_000L) > 0) break; delay(10) }
        assertEquals(1, loadCount(600_000L))
        assertEquals(1, loadCount(500_000L)) // not loaded again
        delay(100)
        assertEquals(4, c.size) // 300, 400, 500, 600
        c.clip(300_000L).await() // one step back: still there
        assertEquals(1, loadCount(300_000L))
    }

    @Test fun anEdgeOfTheListLoadsWhatThereIs() = runBlocking {
        val c = cache()
        c.open(100_000L).await(); c.prefetch(100_000L) { beats }
        settle(c, 100_000L, 200_000L, 300_000L)
        assertEquals(3, c.size) // no previous beat
        c.open(900_000L).await(); c.prefetch(900_000L) { beats }
        settle(c, 800_000L, 900_000L)
        assertEquals(2, c.size) // no next beat
    }

    @Test fun aWindowExpires() = runBlocking {
        var t = now
        val c = cache(ttlMs = 1_000L, clock = { t })
        c.clip(300_000L).await()
        t += 500; c.clip(300_000L).await()
        assertEquals(1, loadCount(300_000L))
        t += 600; c.clip(300_000L).await() // 1 100 ms after the first load
        assertEquals(2, loadCount(300_000L))
    }

    @Test fun aVeryRecentBeatIsNeverKept() = runBlocking {
        val c = cache()
        val recent = now - 5_000L // its window reaches to the end of a file that is still growing
        c.clip(recent).await(); c.clip(recent).await()
        assertEquals(2, loadCount(recent))
        assertEquals(0, c.size)
    }

    @Test fun noEcgAndFailedLoadsAreNullNotAnError() = runBlocking {
        val c = cache(load = { ms -> if (ms == 200_000L) error("bad file") else null })
        assertNull(c.clip(100_000L).await())
        assertNull(c.clip(200_000L).await())
    }

    @Test fun aLatePrefetchOfAnEarlierBeatDoesNotDropTheOpenOne() = runBlocking {
        val c = cache()
        c.open(300_000L).await()
        c.prefetch(300_000L) { delay(100); beats } // slow to find its neighbours
        c.open(700_000L).await()
        c.prefetch(700_000L) { beats } // the owner moved on
        delay(400)
        assertEquals("clip700000", c.clip(700_000L).await())
        assertEquals(1, loadCount(700_000L))
    }
}
