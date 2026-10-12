package com.punkunpun.polarisecg.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.punkunpun.polarisecg.MainActivity
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.polar.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "acquisition"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "com.punkunpun.polarisecg.action.STOP"

/**
 * Foreground service that keeps the H10 connection alive while the app is in the background or
 * the screen is off. Android only lets a service run for long if it shows a persistent
 * notification ("foreground service"); the notification here also shows connection status and HR.
 *
 * The service does not own the connection: [PolarisApp.polarConnection] does, and the UI and later
 * the recorder/DSP read its Flows. The service's job is to (1) keep the process alive,
 * (2) call connect/disconnect, and (3) keep the notification up to date.
 */
class AcquisitionService : Service() {

    // Same idea as in PolarConnection: our own scope, cancelled in onDestroy.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // A service has no constructor access to `application`, so this is read lazily.
    private val app get() = application as PolarisApp

    override fun onBind(intent: Intent?): IBinder? = null // not a bound service

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be called within a few seconds of startForegroundService(), so do it first.
        // The 3-argument form declares the service type; it must match the manifest.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val deviceId = app.deviceStore.deviceId
        if (deviceId == null) {
            // Nothing to connect to (user never paired). Don't sit there with a useless notification.
            stopSelf()
            return START_NOT_STICKY
        }

        _running.value = true
        acquireWakeLock()
        if (app.polarConnection.state.value is ConnectionState.Disconnected) {
            app.polarConnection.connect(deviceId)
        }
        watchStatusForNotification()

        // START_STICKY: if Android kills the process, restart the service (intent will be null).
        // The code above then reconnects using the saved device ID.
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        notificationJob?.cancel()
        scope.cancel()
        app.polarConnection.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    /** Re-draws the notification whenever the connection state or heart rate changes. */
    private fun watchStatusForNotification() {
        notificationJob?.cancel()
        val connection = app.polarConnection
        // Latest HR as a StateFlow so we can `combine` it with the connection state.
        val hr = MutableStateFlow<Int?>(null)
        notificationJob = scope.launch {
            launch { connection.hr.collect { hr.value = it.heartRateBpm } }
            combine(connection.state, hr) { state, bpm -> statusText(state, bpm) }
                .collect { text ->
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(text))
                }
        }
    }

    private fun statusText(state: ConnectionState, bpm: Int?): String = when (state) {
        is ConnectionState.Disconnected -> getString(R.string.notif_disconnected)
        ConnectionState.Connecting -> getString(R.string.notif_connecting)
        ConnectionState.Connected -> getString(R.string.notif_connected)
        ConnectionState.Streaming ->
            if (bpm != null) getString(R.string.notif_streaming_hr, bpm) else getString(R.string.notif_streaming)
    }

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AcquisitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // updates must not buzz or pop up again
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // IMPORTANCE_LOW: shown, but silent. Never alert at night for a status notification.
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /**
     * Partial wake lock: keeps the CPU running with the screen off so BLE data keeps being
     * processed. Held only while the service runs; the 12 h timeout is a safety net against leaks.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "polarisecg:acquisition").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    companion object {
        // Whether the service is currently running, for the UI to show Start or Stop.
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Call from the UI while the app is visible (Android forbids starting from the background). */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AcquisitionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AcquisitionService::class.java))
        }
    }
}
