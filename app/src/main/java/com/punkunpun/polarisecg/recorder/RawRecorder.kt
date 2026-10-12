package com.punkunpun.polarisecg.recorder

import android.os.Build
import android.util.Log
import com.punkunpun.polarisecg.polar.ACC_RANGE_G
import com.punkunpun.polarisecg.polar.ACC_RATE_HZ
import com.punkunpun.polarisecg.polar.ACC_RESOLUTION_BITS
import com.punkunpun.polarisecg.polar.ECG_RATE_HZ
import com.punkunpun.polarisecg.polar.ECG_RESOLUTION_BITS
import com.punkunpun.polarisecg.polar.PolarConnection
import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "RawRecorder"
private const val MS_PER_HOUR = 3_600_000L

private val SESSION_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val HOUR_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH'Z'").withZone(ZoneOffset.UTC)

/**
 * Records everything [connection] produces into `<sessionsDir>/<startUtc>/` (format: docs/RAW_FORMAT.md).
 * One recorder = one session. Start it when acquisition starts and [stop] it when it ends;
 * reconnects in between simply continue the same files (the gap shows in the timestamps).
 */
class RawRecorder(
    private val sessionsDir: File,
    private val connection: PolarConnection,
    private val deviceId: String,
    private val appVersion: String,
) {
    // Dispatchers.IO: a thread pool meant for file/network work, so writing never blocks the UI.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMs = System.currentTimeMillis()
    val sessionDir = File(sessionsDir, SESSION_NAME_FORMAT.format(Instant.ofEpochMilli(startMs)))

    private lateinit var ecgFile: HourlyFile
    private lateinit var accFile: HourlyFile
    private lateinit var hrFile: HourlyFile

    fun start() {
        sessionDir.mkdirs()
        writeSessionJson(endMs = null)
        ecgFile = HourlyFile(sessionDir, "ecg", RawStream.ECG)
        accFile = HourlyFile(sessionDir, "acc", RawStream.ACC)
        hrFile = HourlyFile(sessionDir, "hr", RawStream.HR)

        collectInto("ECG", ecgFile) { f ->
            connection.ecg.collect { f.write(it.phoneTimeMs) { w -> w.writeEcg(it.phoneTimeMs, it.sensorTimeNs, it.samplesUv) } }
        }
        collectInto("ACC", accFile) { f ->
            connection.acc.collect {
                f.write(it.phoneTimeMs) { w -> w.writeAcc(it.phoneTimeMs, it.sensorTimeNs, it.xMg, it.yMg, it.zMg) }
            }
        }
        collectInto("HR", hrFile) { f ->
            connection.hr.collect {
                f.write(it.phoneTimeMs) { w -> w.writeHr(it.phoneTimeMs, it.heartRateBpm, it.contactDetected, it.rrMs) }
            }
        }
    }

    fun stop() {
        scope.cancel()
        ecgFile.close()
        accFile.close()
        hrFile.close()
        writeSessionJson(endMs = System.currentTimeMillis())
    }

    private fun collectInto(name: String, file: HourlyFile, block: suspend (HourlyFile) -> Unit) {
        scope.launch {
            try {
                block(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Most likely storage full. Recording stops for this stream, the app keeps running.
                Log.e(TAG, "$name recording failed", e)
            }
        }
    }

    private fun writeSessionJson(endMs: Long?) {
        val json = JSONObject()
            .put("formatVersion", 1)
            .put("startUtc", Instant.ofEpochMilli(startMs).toString())
            .put("endUtc", endMs?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL)
            .put("deviceId", deviceId)
            .put("firmware", JSONObject.NULL) // TODO: fill from the SDK's device-info callback
            .put("appVersion", appVersion)
            .put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("ecg", JSONObject().put("rateHz", ECG_RATE_HZ).put("resolutionBits", ECG_RESOLUTION_BITS).put("unit", "uV"))
            .put(
                "acc",
                JSONObject().put("rateHz", ACC_RATE_HZ).put("resolutionBits", ACC_RESOLUTION_BITS)
                    .put("rangeG", ACC_RANGE_G).put("unit", "mG"),
            )
        try {
            File(sessionDir, "session.json").writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "could not write session.json", e)
        }
    }
}

/** One stream's files, rotated every UTC hour (by the phone time of the frame being written). */
internal class HourlyFile(private val dir: File, private val prefix: String, private val stream: RawStream) {
    private var hourKey = Long.MIN_VALUE
    private var writer: RawWriter? = null
    private var closed = false

    // @Synchronized: the collector coroutine writes while stop() closes, possibly on another thread.
    @Synchronized
    fun write(phoneTimeMs: Long, block: (RawWriter) -> Unit) {
        if (closed) return
        val key = Math.floorDiv(phoneTimeMs, MS_PER_HOUR)
        if (key != hourKey || writer == null) {
            writer?.close()
            val name = "${prefix}_${HOUR_NAME_FORMAT.format(Instant.ofEpochMilli(key * MS_PER_HOUR))}.bin"
            writer = RawWriter(FileOutputStream(File(dir, name), true), stream)
            hourKey = key
        }
        block(writer!!)
    }

    @Synchronized
    fun close() {
        closed = true
        writer?.close()
        writer = null
    }
}
