package com.punkunpun.polarisecg

import android.app.Application
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.punkunpun.polarisecg.log.ConnectionLog
import com.punkunpun.polarisecg.log.LogType
import com.punkunpun.polarisecg.pairing.DeviceStore
import com.punkunpun.polarisecg.polar.PolarConnection
import java.io.File

/**
 * Created once when the app process starts, before any Activity or Service.
 * It is the place for objects that must be shared (and must be single instances).
 */
class PolarisApp : Application() {

    /** Append-only history of connects, drops and errors; the Diagnostics screen reads it. */
    val connectionLog by lazy { ConnectionLog(File(filesDir, "connection_log.tsv")) }

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
}
