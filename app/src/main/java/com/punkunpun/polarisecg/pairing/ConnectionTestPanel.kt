package com.punkunpun.polarisecg.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.polar.ConnectionState
import com.punkunpun.polarisecg.service.AcquisitionService

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

    var ecgFrames by remember { mutableIntStateOf(0) }
    var accFrames by remember { mutableIntStateOf(0) }
    var heartRate by remember { mutableStateOf("–") }

    LaunchedEffect(connection) { connection.ecg.collect { ecgFrames++ } }
    LaunchedEffect(connection) { connection.acc.collect { accFrames++ } }
    LaunchedEffect(connection) { connection.hr.collect { heartRate = it.heartRateBpm.toString() } }

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
