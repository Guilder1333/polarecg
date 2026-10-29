package com.punkunpun.polarisecg

import android.app.Application
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.punkunpun.polarisecg.annotations.AnnotationStore
import com.punkunpun.polarisecg.annotations.AnnotationTypes
import com.punkunpun.polarisecg.log.ConnectionLog
import com.punkunpun.polarisecg.log.LogType
import com.punkunpun.polarisecg.pairing.DeviceStore
import com.punkunpun.polarisecg.polar.PolarConnection
import com.punkunpun.polarisecg.processing.LiveProcessor
import com.punkunpun.polarisecg.replay.ReplayPlayer
import com.punkunpun.polarisecg.storage.BeatWriter
import com.punkunpun.polarisecg.storage.PolarisDatabase
import java.io.File

/**
 * Created once when the app process starts, before any Activity or Service.
 * It is the place for objects that must be shared (and must be single instances).
 */
class PolarisApp : Application() {

    /** Append-only history of connects, drops and errors; the Diagnostics screen reads it. */
    val connectionLog by lazy { ConnectionLog(File(filesDir, "connection_log.tsv")) }

    /** Where raw recordings go: one sub-folder per session (see docs/RAW_FORMAT.md). */
    val sessionsDir by lazy { File(filesDir, "sessions") }

    override fun onCreate() {
        super.onCreate()
        // Every process start is logged: after Android kills us overnight this is how we see it.
        connectionLog.log(LogType.APP_START)
    }

    /** Remembers which H10 the user picked. */
    val deviceStore by lazy { DeviceStore(this) }

    /**
     * The Polar SDK entry point. `by lazy` means it is created on first use, not at startup,
     * so the Bluetooth stack is only touched once the user has granted permissions.
     * Only one instance should exist; the scanner and `PolarConnection` share it.
     */
    val polarApi: PolarBleApi by lazy {
        PolarBleApiDefaultImpl.defaultImplementation(
            this,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            ),
        )
    }

    /** The single connection to the strap; its Flows are what the rest of the app consumes. */
    val polarConnection by lazy { PolarConnection(polarApi, connectionLog) }

    /** Beat detection and classification on the live frames (P5.1); the service starts and stops it. */
    val liveProcessor by lazy { LiveProcessor(polarConnection) }

    /** The owner's actions and events (P5.10), written into the running session's folder. */
    val annotations by lazy { AnnotationStore(sessionsDir, AnnotationTypes(File(filesDir, "annotation_types.json"))) }

    /** Derived data: beats now, events, HRV and labels later (plan §8). */
    val database by lazy { PolarisDatabase.open(this) }

    /** Saves the live beats into [database] in batches (P5.3); the service starts and finishes it with the pipeline. */
    val beatWriter by lazy { BeatWriter(database.beatDao()) }

    /** Plays a recorded session through the live pipeline (P5.2). */
    val replayPlayer by lazy { ReplayPlayer(polarConnection, liveProcessor) }

    /**
     * Folders with sessions to replay: the app's own recordings, and `replay/` in the app's external files folder,
     * where a night recorded earlier can be copied from the PC (adb push, see docs/RAW_FORMAT.md).
     */
    fun replayableSessions(): List<File> {
        val dirs = listOfNotNull(sessionsDir, getExternalFilesDir("replay"))
        return dirs.flatMap { d -> d.listFiles { f -> f.isDirectory }?.toList().orEmpty() }.sortedByDescending { it.name }
    }
}
