package com.punkunpun.polarisecg.pairing

import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A Polar strap seen during a scan. [rssi] is signal strength in dBm (closer to 0 = nearer). */
data class FoundDevice(val deviceId: String, val name: String, val rssi: Int)

class H10Scanner(private val api: PolarBleApi) {

    /**
     * Scans until the collecting coroutine is cancelled. Emits the full, de-duplicated list of
     * H10 straps found so far (strongest signal first) every time something new is heard.
     * Other Polar devices (watches, Verity Sense...) are ignored: only the H10 streams raw ECG.
     */
    fun scan(): Flow<List<FoundDevice>> = flow {
        val found = LinkedHashMap<String, FoundDevice>()
        api.searchForDevice().collect { info ->
            if (info.name.contains("H10", ignoreCase = true)) {
                found[info.deviceId] = FoundDevice(info.deviceId, info.name, info.rssi)
                emit(found.values.sortedByDescending { it.rssi })
            }
        }
    }
}
