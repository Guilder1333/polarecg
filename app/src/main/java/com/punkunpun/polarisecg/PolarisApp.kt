package com.punkunpun.polarisecg

import android.app.Application
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.punkunpun.polarisecg.pairing.DeviceStore

/**
 * Created once when the app process starts, before any Activity or Service.
 * It is the place for objects that must be shared (and must be single instances).
 */
class PolarisApp : Application() {

    /** Remembers which H10 the user picked. */
    val deviceStore by lazy { DeviceStore(this) }

    /**
     * The Polar SDK entry point. `by lazy` means it is created on first use, not at startup,
     * so the Bluetooth stack is only touched once the user has granted permissions.
     * Only one instance should exist; the scanner now, and `PolarConnection` (P1.3) later, share it.
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
}
