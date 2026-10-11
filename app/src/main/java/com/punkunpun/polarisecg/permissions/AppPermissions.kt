package com.punkunpun.polarisecg.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Everything the app needs the user to approve at runtime.
 *
 * A Kotlin `object` is a singleton: there is exactly one `AppPermissions`, no need to create it.
 */
object AppPermissions {

    /** Runtime permissions for talking to the H10 (asked together in one system dialog). */
    val bluetooth = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    fun hasBluetooth(context: Context): Boolean = bluetooth.all { isGranted(context, it) }

    fun hasNotifications(context: Context): Boolean = isGranted(context, NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent that shows the system "allow background activity?" dialog for this app.
     * This is not a normal runtime permission, so it cannot go through the permission launcher.
     */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
