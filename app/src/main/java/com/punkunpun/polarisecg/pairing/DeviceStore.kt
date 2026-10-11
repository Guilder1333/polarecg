package com.punkunpun.polarisecg.pairing

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the chosen H10 device ID (the 8-character ID printed on the strap, e.g. `A1B2C3D4`).
 * SharedPreferences is Android's simple key-value storage; fine for one small string.
 */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("polarisecg", Context.MODE_PRIVATE)

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit { putString(KEY_DEVICE_ID, value) }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
