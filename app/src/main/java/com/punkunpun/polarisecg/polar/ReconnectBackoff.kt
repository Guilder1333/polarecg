package com.punkunpun.polarisecg.polar

/**
 * How long to wait before the next reconnect attempt: 2 s, 5 s, 15 s, then 30 s forever.
 * Call [next] after each failed attempt and [reset] once data is flowing again.
 * Plain Kotlin with no Android types, so it is unit-tested on the PC.
 */
class ReconnectBackoff(private val delaysMs: List<Long> = listOf(2_000, 5_000, 15_000, 30_000)) {
    private var attempt = 0

    // @Synchronized: SDK callbacks and our coroutines can call this from different threads.
    @Synchronized
    fun next(): Long {
        val delay = delaysMs[attempt]
        if (attempt < delaysMs.lastIndex) attempt++ // stay on the last value (the cap)
        return delay
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}
