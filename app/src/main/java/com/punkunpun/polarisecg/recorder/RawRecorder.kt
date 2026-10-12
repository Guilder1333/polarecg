package com.punkunpun.polarisecg.recorder

import android.os.Build
import android.util.Log
import com.punkunpun.polarisecg.dsp.raw.RawNames
import com.punkunpun.polarisecg.dsp.raw.RawStream
import com.punkunpun.polarisecg.dsp.raw.RawWriter
import com.punkunpun.polarisecg.dsp.raw.archiveSegment
import com.punkunpun.polarisecg.dsp.time.TimeMapper
import com.punkunpun.polarisecg.log.ConnectionLog
import com.punkunpun.polarisecg.log.LogType
import com.punkunpun.polarisecg.polar.ACC_RANGE_G
import com.punkunpun.polarisecg.polar.ACC_RATE_HZ
import com.punkunpun.polarisecg.polar.ACC_RESOLUTION_BITS
import com.punkunpun.polarisecg.polar.ECG_RATE_HZ
import com.punkunpun.polarisecg.polar.ECG_RESOLUTION_BITS
import com.punkunpun.polarisecg.polar.ConnectionState
import com.punkunpun.polarisecg.polar.PolarConnection
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "RawRecorder"
private const val MS_PER_HOUR = 3_600_000L
private const val TIME_MAP_EVERY_MS = 60_000L

/**
 * Records everything [connection] produces into a session folder (layout: docs/RAW_FORMAT.md).
 *
 * [start] either resumes the unfinished session left by a kill, or begins a new one (see
 * [SessionFolders]). [stop] with `sessionEnded = true` is the user's stop: it writes the end time,
 * and the next start is a new session. With `false` (service destroyed by the system) the session
 * stays open so the next start resumes it.
 */
class RawRecorder(
    private val sessionsDir: File,
    private val connection: PolarConnection,
    private val deviceId: String,
    private val appVersion: String,
    private val log: ConnectionLog,
) {
    // Dispatchers.IO: a thread pool meant for file/network work, so writing never blocks the UI.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var session: OpenedSession
    val sessionDir: File get() = session.dir

    private lateinit var ecgFile: HourlyFile
    private lateinit var accFile: HourlyFile
    private lateinit var hrFile: HourlyFile

    // Sensor clock -> phone UTC (P1.8). Fed from the ECG frames; its state changes are written to
    // timemap.csv so the PC can convert sensor timestamps without replaying everything.
    private val timeMapper = TimeMapper()
    @Volatile private var newConnection = false
    private var lastTimeMapMs = 0L

    fun start() {
        // Blocking file work (a resume inspects the files a killed run left), but it is a few ms.
        session = SessionFolders.open(sessionsDir, deviceId, System.currentTimeMillis(), ::newSessionMeta)
        val dir = session.dir
        Log.i(TAG, "${if (session.resumed) "Resumed" else "Started"} session ${dir.name}")

        ecgFile = HourlyFile(dir, "ecg", RawStream.ECG)
        accFile = HourlyFile(dir, "acc", RawStream.ACC)
        hrFile = HourlyFile(dir, "hr", RawStream.HR)

        val timeMap = File(dir, TIME_MAP_FILE)
        if (!timeMap.exists()) timeMap.writeText("connection,phoneUtcMs,sensorNs,offsetMs,kind\n")

        collectInto("STATE") {
            // Every (re)start of the streams may come with a different sensor clock.
            var wasStreaming = false
            connection.state.collect { state ->
                val streaming = state == ConnectionState.Streaming
                if (streaming && !wasStreaming) {
                    timeMapper.startConnection()
                    newConnection = true
                }
                wasStreaming = streaming
            }
        }
        collectInto("ECG") {
            connection.ecg.collect {
                ecgFile.write(it.phoneTimeMs) { w -> w.writeEcg(it.phoneTimeMs, it.sensorTimeNs, it.samplesUv) }
                trackTime(it.sensorTimeNs, it.samplesUv.size, it.phoneTimeMs)
            }
        }
        collectInto("ACC") {
            connection.acc.collect {
                accFile.write(it.phoneTimeMs) { w -> w.writeAcc(it.phoneTimeMs, it.sensorTimeNs, it.xMg, it.yMg, it.zMg) }
            }
        }
        collectInto("HR") {
            connection.hr.collect {
                hrFile.write(it.phoneTimeMs) { w -> w.writeHr(it.phoneTimeMs, it.heartRateBpm, it.contactDetected, it.rrMs) }
            }
        }
    }

    /**
     * Stops writing and archives the current files. [sessionEnded] = true records the end time
     * (proper stop); false leaves the session open to be resumed.
     */
    fun stop(sessionEnded: Boolean) {
        scope.cancel()
        ecgFile.close()
        accFile.close()
        hrFile.close()
        if (sessionEnded) {
            try {
                SessionFolders.end(session, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "could not write end time", e)
            }
        }
    }

    private fun collectInto(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Most likely storage full. Recording stops for this stream, the app keeps running.
                Log.e(TAG, "$name recording failed", e)
            }
        }
    }

    /**
     * [firstSampleNs] is the sensor time of the frame's first sample. The phone receives the frame
     * after its LAST sample was taken, so that is the instant paired with [phoneMs].
     */
    private fun trackTime(firstSampleNs: Long, sampleCount: Int, phoneMs: Long) {
        val lastSampleNs = firstSampleNs + (sampleCount - 1) * 1_000_000_000L / ECG_RATE_HZ
        val jump = timeMapper.observe(lastSampleNs, phoneMs)
        val kind = when {
            jump != null -> "jump"
            newConnection -> "start"
            phoneMs - lastTimeMapMs >= TIME_MAP_EVERY_MS -> "periodic"
            else -> return
        }
        newConnection = false
        lastTimeMapMs = phoneMs
        if (jump != null) {
            log.log(LogType.CLOCK_JUMP, "offset ${if (jump.deltaMs >= 0) "+" else ""}${jump.deltaMs} ms", phoneMs)
        }
        // Connection numbers continue across a resume (see OpenedSession.timeMapConnectionBase).
        val connectionNumber = session.timeMapConnectionBase + timeMapper.connection
        try {
            File(session.dir, TIME_MAP_FILE)
                .appendText("$connectionNumber,$phoneMs,$lastSampleNs,${timeMapper.offsetMs()},$kind\n")
        } catch (e: Exception) {
            Log.e(TAG, "could not write $TIME_MAP_FILE", e)
        }
    }

    private fun newSessionMeta(startMs: Long): JSONObject = JSONObject()
        .put("formatVersion", 1)
        .put("startUtc", SessionFolders.iso(startMs))
        .put("endUtc", JSONObject.NULL)
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
}

/**
 * One stream's files. The file being written is always `<prefix>.bin`; at every UTC hour change
 * (by the phone time of the frame being written) and at close it is renamed to
 * `<prefix>_<firstRecordTime>.bin` (see archiveSegment). Rotation is lazy: nothing is created
 * until the first frame arrives, so a silent stream leaves no file.
 */
internal class HourlyFile(private val dir: File, private val prefix: String, private val stream: RawStream) {
    private var hourKey = Long.MIN_VALUE
    private var writer: RawWriter? = null
    private var closed = false

    private val current get() = RawNames.currentFile(dir, prefix)

    // @Synchronized: the collector coroutine writes while stop() closes, possibly on another thread.
    @Synchronized
    fun write(phoneTimeMs: Long, block: (RawWriter) -> Unit) {
        if (closed) return
        val key = Math.floorDiv(phoneTimeMs, MS_PER_HOUR)
        if (key != hourKey || writer == null) {
            finishCurrent()
            // Normally nothing is there. If something is, keep it rather than appending a second
            // header into the middle of it.
            archiveSegment(current, prefix)
            writer = RawWriter(FileOutputStream(current, true), stream)
            hourKey = key
        }
        block(writer!!)
    }

    @Synchronized
    fun close() {
        closed = true
        finishCurrent()
    }

    private fun finishCurrent() {
        writer?.close()
        writer = null
        archiveSegment(current, prefix)
    }
}
