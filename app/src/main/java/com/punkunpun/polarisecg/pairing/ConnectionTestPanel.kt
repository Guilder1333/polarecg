package com.punkunpun.polarisecg.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.polar.ConnectionState
import com.punkunpun.polarisecg.recorder.latestSession
import com.punkunpun.polarisecg.recorder.shareZip
import com.punkunpun.polarisecg.recorder.zipSession
import com.punkunpun.polarisecg.service.AcquisitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Temporary developer panel to check P1.3 on a real strap: connect, and watch frame counters tick.
 * It goes away once the Live screen (P1.10) exists. Start/Stop control [AcquisitionService].
 */
@Composable
fun ConnectionTestPanel(app: PolarisApp, deviceId: String?) {
    val connection = app.polarConnection
    // collectAsState turns a StateFlow into Compose state: the UI redraws on every new value.
    val state by connection.state.collectAsState()
    val running by AcquisitionService.running.collectAsState()
    val context = LocalContext.current

    // The counters live in PolarConnection, so they keep counting while this panel is off screen.
    val ecgFrames by connection.ecgFrames.collectAsState()
    val accFrames by connection.accFrames.collectAsState()
    val heartRate = connection.lastHr.collectAsState().value?.toString() ?: "–"

    val stateText = when (val s = state) {
        is ConnectionState.Disconnected ->
            stringResource(R.string.conn_state_disconnected) + (s.reason?.let { " ($it)" } ?: "")
        ConnectionState.Connecting -> stringResource(R.string.conn_state_connecting)
        ConnectionState.Connected -> stringResource(R.string.conn_state_connected)
        ConnectionState.Streaming -> stringResource(R.string.conn_state_streaming)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.conn_title), style = MaterialTheme.typography.titleMedium)
        Text(stateText)
        Text(stringResource(R.string.conn_stats, heartRate, ecgFrames, accFrames))
        var rawOn by remember { mutableStateOf(app.deviceStore.rawRecording) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = rawOn,
                onCheckedChange = { rawOn = it; app.deviceStore.rawRecording = it },
                enabled = !running, // the choice is read when recording starts
            )
            Text(stringResource(R.string.raw_toggle))
        }
        val scope = rememberCoroutineScope()
        OutlinedButton(onClick = {
            scope.launch {
                // Zipping can take a moment for a night of data, so do it off the UI thread.
                val zip = withContext(Dispatchers.IO) {
                    latestSession(app.sessionsDir)?.let { zipSession(context, it, app.database) }
                }
                if (zip == null) {
                    Toast.makeText(context, R.string.raw_export_none, Toast.LENGTH_SHORT).show()
                } else {
                    shareZip(context, zip)
                }
            }
        }) { Text(stringResource(R.string.raw_export)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The service owns connect/disconnect now, so it keeps running with the screen off.
            if (!running) {
                Button(onClick = { AcquisitionService.start(context) }, enabled = deviceId != null) {
                    Text(stringResource(R.string.conn_connect))
                }
            } else {
                Button(onClick = { AcquisitionService.stop(context) }) {
                    Text(stringResource(R.string.conn_disconnect))
                }
            }
        }
    }
}
