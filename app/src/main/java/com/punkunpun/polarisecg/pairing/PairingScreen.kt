package com.punkunpun.polarisecg.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.R

/** Stateless pairing UI: shows the scan result and reports taps. See PairingRoute for the logic. */
@Composable
fun PairingScreen(
    devices: List<FoundDevice>,
    scanning: Boolean,
    errorMessage: String?,
    selectedDeviceId: String?,
    onRescan: () -> Unit,
    onSelect: (FoundDevice) -> Unit,
    onOpenDiagnostics: () -> Unit,
    connectionPanel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineMedium)

        if (selectedDeviceId != null) {
            Text(
                stringResource(R.string.pair_selected, selectedDeviceId),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(stringResource(R.string.pair_hint))

        if (selectedDeviceId != null) connectionPanel()

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRescan, enabled = !scanning) {
                Text(stringResource(R.string.pair_scan))
            }
            OutlinedButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.diag_open))
            }
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
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
