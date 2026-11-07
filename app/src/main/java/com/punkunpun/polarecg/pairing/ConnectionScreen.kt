package com.punkunpun.polarecg.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

private const val SCAN_DURATION_MS = 20_000L

/**
 * The H10 connection screen: instructions, the strap in use, and scanning for another one. (Recording start/stop is on the
 * main screen.) Needs BLUETOOTH_SCAN permission to have been granted before it is shown.
 */
@Composable
fun ConnectionRoute(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var devices by remember { mutableStateOf(emptyList<FoundDevice>()) }
    // Scan on opening only if nothing is paired yet. Scanning while connected/streaming can disturb the link.
    var scanRequested by remember { mutableStateOf(app.deviceStore.deviceId == null) }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf(app.deviceStore.deviceId) }

    // Re-runs whenever scanRequested flips to true. If the screen leaves composition the
    // coroutine is cancelled automatically, which stops the BLE scan.
    LaunchedEffect(scanRequested) {
        if (!scanRequested) return@LaunchedEffect
        scanning = true
        error = null
        try {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                H10Scanner(app.polarApi).scan().collect { devices = it }
            }
        } catch (e: CancellationException) {
            throw e // never swallow cancellation, or the coroutine would not really stop
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            scanning = false
            scanRequested = false
        }
    }

    ConnectionScreen(
        devices = devices,
        scanning = scanning,
        errorMessage = error,
        selectedDeviceId = selectedId,
        onRescan = { scanRequested = true },
        onSelect = {
            app.deviceStore.deviceId = it.deviceId
            selectedId = it.deviceId
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless connection UI: shows the scan result and reports taps. See [ConnectionRoute] for the logic. */
@Composable
fun ConnectionScreen(
    devices: List<FoundDevice>,
    scanning: Boolean,
    errorMessage: String?,
    selectedDeviceId: String?,
    onRescan: () -> Unit,
    onSelect: (FoundDevice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.conn_back)) }
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleLarge)
        }

        if (selectedDeviceId != null) {
            Text(
                stringResource(R.string.pair_selected, selectedDeviceId),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(stringResource(R.string.pair_hint))


        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRescan, enabled = !scanning) {
                Text(stringResource(R.string.pair_scan))
            }
            if (scanning) {
                // Fixed size: without it the spinner grabs whatever width is left and spins off-centre.
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.pair_scanning))
            }
        }

        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
        }
        if (!scanning && devices.isEmpty() && errorMessage == null) {
            Text(stringResource(R.string.pair_none_found))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.deviceId }) { device ->
                val isSelected = device.deviceId == selectedDeviceId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isSelected) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.pair_device_details, device.deviceId, device.rssi),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (isSelected) {
                            Text(stringResource(R.string.pair_in_use))
                        } else {
                            Button(onClick = { onSelect(device) }) {
                                Text(stringResource(R.string.pair_use))
                            }
                        }
                    }
                }
            }
        }
    }
}
