package com.punkunpun.polarisecg.dsp.live

/**
 * Fixed-size "ring" of the most recent samples: when full, each new sample overwrites the oldest.
 * Used by the live ECG plot (last ~5 s). Thread-safe, because the UI reads while a coroutine writes.
 */
class SampleRing(val capacity: Int) {
    private val data = IntArray(capacity)
    private var next = 0 // index where the next sample goes
    private var filled = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @get:Synchronized
    val size: Int get() = filled

    @Synchronized
    fun add(samples: IntArray) {
        for (s in samples) {
            data[next] = s
            next = (next + 1) % capacity
        }
        filled = minOf(capacity, filled + samples.size)
    }

    /** Copy of the contents, oldest sample first. */
    @Synchronized
    fun snapshot(): IntArray {
        val out = IntArray(filled)
        val start = (next - filled + capacity) % capacity
        for (i in 0 until filled) out[i] = data[(start + i) % capacity]
        return out
    }

    @Synchronized
    fun clear() {
        next = 0
        filled = 0
    }
}
