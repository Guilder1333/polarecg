package com.punkunpun.polarisecg.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.R

/**
 * Explains each permission and lets the user grant it.
 *
 * This composable is "stateless": it receives the current state and callbacks as parameters
 * and holds nothing itself. The caller (MainActivity) owns the state. That pattern is called
 * "state hoisting" and makes UI easy to preview and test.
 */
@Composable
fun PermissionsScreen(
    bluetoothGranted: Boolean,
    notificationsGranted: Boolean,
    batteryGranted: Boolean,
    onRequestBluetooth: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.perm_intro))

        PermissionCard(
            title = stringResource(R.string.perm_bluetooth_title),
            why = stringResource(R.string.perm_bluetooth_why),
            granted = bluetoothGranted,
            onGrant = onRequestBluetooth,
        )
        PermissionCard(
            title = stringResource(R.string.perm_notifications_title),
            why = stringResource(R.string.perm_notifications_why),
            granted = notificationsGranted,
            onGrant = onRequestNotifications,
        )
        PermissionCard(
            title = stringResource(R.string.perm_battery_title),
            why = stringResource(R.string.perm_battery_why),
            granted = batteryGranted,
            onGrant = onRequestBattery,
        )

        // Bluetooth is the only hard requirement; the others make overnight recording reliable.
        if (bluetoothGranted) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.perm_continue))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    why: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (granted) {
                    Text(stringResource(R.string.perm_granted), color = MaterialTheme.colorScheme.primary)
                } else {
                    Button(onClick = onGrant) { Text(stringResource(R.string.perm_grant)) }
                }
            }
            Text(why, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
