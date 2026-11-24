package com.punkunpun.polarecg.workout

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.diagnostics.formatDuration
import com.punkunpun.polarecg.dsp.live.SampleRing
import com.punkunpun.polarecg.live.EcgTrace
import com.punkunpun.polarecg.live.WINDOW_SECONDS
import com.punkunpun.polarecg.log.formatJst
import com.punkunpun.polarecg.polar.ECG_RATE_HZ
import com.punkunpun.polarecg.storage.WorkoutEntity
import kotlinx.coroutines.delay
import org.json.JSONObject

private const val FPS = 20

/**
 * The bicycle screen: one big Start/Stop button, the heart rate and the ECG trace, and while a ride runs the time, the
 * distance and the speed. The ride itself belongs to [WorkoutTracker], so leaving this screen does not stop it.
 */
@Composable
fun BikeScreen(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val tracker = app.workouts
    val state by tracker.state.collectAsState()
    val recent by remember { app.workoutDatabase.workoutDao().observeRecent(5) }.collectAsState(emptyList())
    val connection = app.polarConnection

    // Same live feed as the Live screen: a ring of the last seconds that the Canvas redraws on a timer.
    val ring = remember { SampleRing(ECG_RATE_HZ * WINDOW_SECONDS) }
    val lastFrameNs = remember { LongArray(1) }
    var frame by remember { mutableIntStateOf(0) }
    var heartRate by remember { mutableStateOf<Int?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(connection) {
        connection.ecg.collect {
            ring.add(it.samplesUv)
            lastFrameNs[0] = System.nanoTime()
        }
    }
    LaunchedEffect(connection) { connection.hr.collect { heartRate = it.heartRateBpm } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L / FPS)
            frame++
            nowMs = System.currentTimeMillis()
        }
    }

    // The location permission is asked on the first ride; whatever the answer, the ride starts (without distance if refused).
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tracker.start() }
    val startRide = {
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.bike_title), style = MaterialTheme.typography.headlineMedium)

        if (state.active) {
            Button(
                onClick = { tracker.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) { Text(stringResource(R.string.bike_stop), style = MaterialTheme.typography.headlineSmall) }
        } else {
            Button(onClick = startRide, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text(stringResource(R.string.bike_start), style = MaterialTheme.typography.headlineSmall)
            }
        }

        Text(
            heartRate?.let { stringResource(R.string.live_hr, it) } ?: stringResource(R.string.live_hr_none),
            style = MaterialTheme.typography.displayLarge,
        )
        EcgTrace(ring, lastFrameNs, frame, Modifier.fillMaxWidth().height(180.dp))

        if (state.active) {
            Text(formatClock(nowMs - state.startMs), style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(stringResource(R.string.bike_distance, state.distanceM / 1000), style = MaterialTheme.typography.headlineLarge)
                state.speedMps?.let { Text(stringResource(R.string.bike_speed, it * 3.6), style = MaterialTheme.typography.headlineLarge) }
            }
            when {
                !state.gpsOn -> Text(stringResource(R.string.bike_no_gps), color = MaterialTheme.colorScheme.error)
                state.gpsAccuracyM == null -> Text(stringResource(R.string.bike_gps_wait))
                else -> Text(stringResource(R.string.bike_gps_acc, state.gpsAccuracyM!!.toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.bike_hint_active), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.bike_hint_idle), style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.bike_recent), style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) Text(stringResource(R.string.bike_recent_none))
        for (w in recent) Text(recentRow(w, state.active))

        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
    }
}

/** "1:05:09" / "12:03": the running time of a ride. */
private fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** One line of the recent list: day and time (JST), length, distance, mean heart rate. The figures come from the free-form `data`. */
@Composable
private fun recentRow(w: WorkoutEntity, someRideRunning: Boolean): String {
    val data = runCatching { JSONObject(w.data) }.getOrDefault(JSONObject())
    // An end time of null means running now (the newest row while a ride is on) or killed (an older row).
    val end = w.endUtcMs ?: data.optLong("updatedUtcMs", w.startUtcMs)
    val interrupted = w.endUtcMs == null && !someRideRunning
    return stringResource(
        R.string.bike_recent_row,
        formatJst(w.startUtcMs).substring(0, 16),
        formatDuration(end - w.startUtcMs),
        data.optDouble("distanceM", 0.0) / 1000,
        (if (data.has("avgHr")) stringResource(R.string.bike_recent_hr, data.getInt("avgHr")) else "") +
            (if (interrupted) stringResource(R.string.bike_recent_interrupted) else ""),
    )
}
