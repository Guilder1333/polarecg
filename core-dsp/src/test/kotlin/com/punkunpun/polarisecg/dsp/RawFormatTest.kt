package com.punkunpun.polarisecg.dsp

import com.punkunpun.polarisecg.dsp.raw.RawRecord
import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import com.punkunpun.polarisecg.dsp.raw.readRawFile
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RawFormatTest {

    private fun write(stream: RawStream, block: RawWriter.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        RawWriter(out, stream).apply(block).close()
        return out.toByteArray()
    }

    @Test
    fun ecgRoundTrip() {
        val bytes = write(RawStream.ECG) {
            writeEcg(1_700_000_000_000, 123_456_789_000, intArrayOf(10, -20, 300_000))
            writeEcg(1_700_000_000_500, 123_956_789_000, intArrayOf(1))
        }
        val file = readRawFile(bytes)
        assertEquals(RawStream.ECG, file.stream)
        val r = file.records[0] as RawRecord.Ecg
        assertEquals(1_700_000_000_000, r.phoneTimeMs)
        assertEquals(123_456_789_000, r.sensorTimeNs)
        assertContentEquals(intArrayOf(10, -20, 300_000), r.samplesUv)
        assertEquals(2, file.records.size)
    }

    @Test
    fun accRoundTrip() {
        val bytes = write(RawStream.ACC) {
            writeAcc(5, 6, intArrayOf(1, -8000), intArrayOf(2, 0), intArrayOf(1000, 7999))
        }
        val r = readRawFile(bytes).records.single() as RawRecord.Acc
        assertContentEquals(intArrayOf(1, -8000), r.xMg)
        assertContentEquals(intArrayOf(2, 0), r.yMg)
        assertContentEquals(intArrayOf(1000, 7999), r.zMg)
    }

    @Test
    fun hrRoundTripWithAndWithoutContact() {
        val bytes = write(RawStream.HR) {
            writeHr(1, 62, true, intArrayOf(950, 1010))
            writeHr(2, 63, null, intArrayOf())
        }
        val recs = readRawFile(bytes).records
        val a = recs[0] as RawRecord.Hr
        assertEquals(62, a.heartRateBpm)
        assertEquals(true, a.contact)
        assertContentEquals(intArrayOf(950, 1010), a.rrMs)
        val b = recs[1] as RawRecord.Hr
        assertNull(b.contact)
        assertEquals(0, b.rrMs.size)
    }

    @Test
    fun truncatedTailIsIgnored() {
        val bytes = write(RawStream.ECG) {
            writeEcg(1, 2, intArrayOf(1, 2, 3))
            writeEcg(3, 4, intArrayOf(4, 5, 6))
        }
        val cut = bytes.copyOf(bytes.size - 5) // app killed mid-write
        assertEquals(1, readRawFile(cut).records.size)
    }

    @Test
    fun badMagicIsRejected() {
        assertFailsWith<IllegalArgumentException> { readRawFile(ByteArray(20)) }
    }
}
