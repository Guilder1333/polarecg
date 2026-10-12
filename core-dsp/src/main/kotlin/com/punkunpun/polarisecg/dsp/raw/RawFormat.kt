package com.punkunpun.polarisecg.dsp.raw

import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Raw recording format, version 1. The byte layout is documented in docs/RAW_FORMAT.md; keep the
 * two in sync. Pure JVM so the phone writes files and the PC (:tools:cli) reads them with the
 * same code.
 *
 * Every number is little-endian. A file holds ONE stream (ECG, ACC or HR) as a header followed
 * by records back to back. There are no record lengths: a record's size follows from its own
 * fields, and a half-written record at the end of a file (app killed mid-write) is ignored.
 */

enum class RawStream(val id: Int) {
    ECG(1),
    ACC(2),
    HR(3),
    ;

    companion object {
        fun fromId(id: Int): RawStream? = entries.firstOrNull { it.id == id }
    }
}

object RawFormat {
    val MAGIC = byteArrayOf('P'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(), 'G'.code.toByte())
    const val VERSION = 1
    const val HEADER_SIZE = 8
}

sealed interface RawRecord {
    /** Phone UTC milliseconds when the frame arrived. */
    val phoneTimeMs: Long

    /** [sensorTimeNs] is the sensor clock (ns since 2000-01-01 UTC) of the first sample. */
    class Ecg(override val phoneTimeMs: Long, val sensorTimeNs: Long, val samplesUv: IntArray) : RawRecord

    class Acc(
        override val phoneTimeMs: Long,
        val sensorTimeNs: Long,
        val xMg: IntArray,
        val yMg: IntArray,
        val zMg: IntArray,
    ) : RawRecord

    /** [contact] is null when the strap does not report skin contact. */
    class Hr(override val phoneTimeMs: Long, val heartRateBpm: Int, val contact: Boolean?, val rrMs: IntArray) :
        RawRecord
}

/**
 * Writes one stream to [out]. Every record is flushed immediately, so if the app process is
 * killed nothing but the record being written is lost (it survives in the OS, not just our buffer).
 */
class RawWriter(private val out: OutputStream, val stream: RawStream) : Closeable {

    init {
        val header = ByteBuffer.allocate(RawFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(RawFormat.MAGIC).putShort(RawFormat.VERSION.toShort()).putShort(stream.id.toShort())
        out.write(header.array())
        out.flush()
    }

    fun writeEcg(phoneTimeMs: Long, sensorTimeNs: Long, samplesUv: IntArray) {
        require(stream == RawStream.ECG)
        val b = buffer(8 + 8 + 2 + 4 * samplesUv.size)
        b.putLong(phoneTimeMs).putLong(sensorTimeNs).putShort(samplesUv.size.toShort())
        for (v in samplesUv) b.putInt(v)
        emit(b)
    }

    fun writeAcc(phoneTimeMs: Long, sensorTimeNs: Long, xMg: IntArray, yMg: IntArray, zMg: IntArray) {
        require(stream == RawStream.ACC)
        val n = xMg.size
        require(yMg.size == n && zMg.size == n)
        val b = buffer(8 + 8 + 2 + 6 * n)
        b.putLong(phoneTimeMs).putLong(sensorTimeNs).putShort(n.toShort())
        for (i in 0 until n) {
            // Values are milli-g within +-8000, which fits a signed 16-bit number.
            b.putShort(xMg[i].coerceIn(-32768, 32767).toShort())
            b.putShort(yMg[i].coerceIn(-32768, 32767).toShort())
            b.putShort(zMg[i].coerceIn(-32768, 32767).toShort())
        }
        emit(b)
    }

    fun writeHr(phoneTimeMs: Long, heartRateBpm: Int, contact: Boolean?, rrMs: IntArray) {
        require(stream == RawStream.HR)
        val n = minOf(rrMs.size, 255)
        val b = buffer(8 + 2 + 1 + 1 + 2 * n)
        b.putLong(phoneTimeMs).putShort(heartRateBpm.coerceIn(0, 65535).toShort())
        b.put(when (contact) { null -> -1; false -> 0; true -> 1 }.toByte())
        b.put(n.toByte())
        for (i in 0 until n) b.putShort(rrMs[i].coerceIn(0, 65535).toShort())
        emit(b)
    }

    override fun close() = out.close()

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun emit(b: ByteBuffer) {
        out.write(b.array())
        out.flush()
    }
}

class RawFile(val stream: RawStream, val records: List<RawRecord>)

/** Parses a whole file. Throws [IllegalArgumentException] for a bad header; ignores a truncated tail. */
fun readRawFile(bytes: ByteArray): RawFile {
    require(bytes.size >= RawFormat.HEADER_SIZE) { "File too short for a header" }
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    for (i in RawFormat.MAGIC.indices) require(b.get() == RawFormat.MAGIC[i]) { "Not a PolarisECG raw file" }
    val version = b.short.toInt()
    require(version == RawFormat.VERSION) { "Unsupported raw format version $version" }
    val stream = requireNotNull(RawStream.fromId(b.short.toInt())) { "Unknown stream type" }

    val records = ArrayList<RawRecord>()
    while (true) {
        val start = b.position()
        val rec = when (stream) {
            RawStream.ECG -> readEcg(b)
            RawStream.ACC -> readAcc(b)
            RawStream.HR -> readHr(b)
        }
        if (rec == null) break // truncated tail: stop, keep everything before it
        records += rec
        check(b.position() > start)
    }
    return RawFile(stream, records)
}

private fun readEcg(b: ByteBuffer): RawRecord? {
    if (b.remaining() < 18) return null
    val phone = b.long
    val sensor = b.long
    val n = b.short.toInt() and 0xFFFF
    if (b.remaining() < 4 * n) return null
    return RawRecord.Ecg(phone, sensor, IntArray(n) { b.int })
}

private fun readAcc(b: ByteBuffer): RawRecord? {
    if (b.remaining() < 18) return null
    val phone = b.long
    val sensor = b.long
    val n = b.short.toInt() and 0xFFFF
    if (b.remaining() < 6 * n) return null
    val x = IntArray(n)
    val y = IntArray(n)
    val z = IntArray(n)
    for (i in 0 until n) {
        x[i] = b.short.toInt()
        y[i] = b.short.toInt()
        z[i] = b.short.toInt()
    }
    return RawRecord.Acc(phone, sensor, x, y, z)
}

private fun readHr(b: ByteBuffer): RawRecord? {
    if (b.remaining() < 12) return null
    val phone = b.long
    val hr = b.short.toInt() and 0xFFFF
    val contactByte = b.get().toInt()
    val n = b.get().toInt() and 0xFF
    if (b.remaining() < 2 * n) return null
    val contact = when (contactByte) { 0 -> false; 1 -> true; else -> null }
    return RawRecord.Hr(phone, hr, contact, IntArray(n) { b.short.toInt() and 0xFFFF })
}
