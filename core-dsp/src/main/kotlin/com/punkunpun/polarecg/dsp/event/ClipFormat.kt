package com.punkunpun.polarecg.dsp.event

import com.punkunpun.polarecg.dsp.live.EcgClip
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One beat drawn on a clip: its time and what the pipeline made of it. */
data class ClipBeat(val utcMs: Long, val cls: String, val good: Boolean, val vDoubt: Boolean, val rescued: Boolean)

/**
 * What is saved for one event (`files/clips/<eventId>.bin`, plan §8): the ECG around it and the beats in it. The beats are in
 * the file because the `beat` table is pruned after 90 days and clips are kept much longer: a clip must still show its markers.
 * [eventStartUtcMs] / [eventEndUtcMs] are the event itself, inside the clip.
 */
class EventClip(
    val ecg: EcgClip,
    val beats: List<ClipBeat>,
    val eventStartUtcMs: Long,
    val eventEndUtcMs: Long,
)

/**
 * The clip file. All numbers little-endian.
 *
 * ```
 *  0  4  "PCLP"
 *  4  2  version = 1
 *  6  2  reserved = 0
 *  8  8  int64   startUtcMs of the first sample
 * 16  8  float64 msPerSample
 * 24  8  int64   eventStartUtcMs
 * 32  8  int64   eventEndUtcMs
 * 40  4  int32   sampleCount n
 * 44  4  int32   beatCount m
 * 48  4*n  int32 samples (µV)
 *  .  8*m  beats: int32 ms after startUtcMs, uint8 class letter (N S V Q), uint8 flags (1 good, 2 vDoubt, 4 rescued), 2 spare
 * ```
 * Layout also in `docs/RAW_FORMAT.md` (Clips).
 */
object ClipFormat {
    private const val VERSION = 1
    private const val HEADER_BYTES = 48
    private const val BEAT_BYTES = 8
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte())

    fun encode(clip: EventClip): ByteArray {
        val e = clip.ecg
        val buf = ByteBuffer.allocate(HEADER_BYTES + 4 * e.size + BEAT_BYTES * clip.beats.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC).putShort(VERSION.toShort()).putShort(0)
        buf.putLong(e.startUtcMs).putDouble(e.msPerSample).putLong(clip.eventStartUtcMs).putLong(clip.eventEndUtcMs)
        buf.putInt(e.size).putInt(clip.beats.size)
        for (s in e.samplesUv) buf.putInt(s)
        for (b in clip.beats) {
            buf.putInt((b.utcMs - e.startUtcMs).toInt())
            buf.put((b.cls.firstOrNull() ?: '?').code.toByte())
            buf.put(((if (b.good) 1 else 0) or (if (b.vDoubt) 2 else 0) or (if (b.rescued) 4 else 0)).toByte())
            buf.putShort(0)
        }
        return buf.array()
    }

    /** Reads a clip file; null if it is not one (wrong magic or version) or is cut short. */
    fun decode(bytes: ByteArray): EventClip? {
        if (bytes.size < HEADER_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC) || buf.short.toInt() != VERSION) return null
        buf.short // reserved
        val start = buf.long
        val msPerSample = buf.double
        val eventStart = buf.long
        val eventEnd = buf.long
        val n = buf.int
        val m = buf.int
        if (n < 0 || m < 0 || bytes.size.toLong() < HEADER_BYTES + 4L * n + BEAT_BYTES.toLong() * m) return null
        val samples = IntArray(n) { buf.int }
        val beats = List(m) {
            val offset = buf.int
            val cls = buf.get().toInt().toChar().toString()
            val flags = buf.get().toInt()
            buf.short
            ClipBeat(start + offset, cls, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
        }
        return EventClip(EcgClip(start, msPerSample, samples), beats, eventStart, eventEnd)
    }
}
