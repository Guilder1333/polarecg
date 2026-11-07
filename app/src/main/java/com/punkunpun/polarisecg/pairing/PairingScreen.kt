package com.punkunpun.polarisecg.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.R

/**
 * The main screen: the current H10 and the buttons to the other screens. Choosing the strap (scan, instructions) is on the
 * connection screen, see [ConnectionScreen]. Stateless; see PairingRoute.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow is still marked experimental
@Composable
fun PairingScreen(
    selectedDeviceId: String?,
    onOpenConnection: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenEvents: () -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenReplay: () -> Unit,
    annotationPanel: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.main_title), style = MaterialTheme.typography.headlineMedium)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (selectedDeviceId != null) stringResource(R.string.pair_selected, selectedDeviceId) else stringResource(R.string.main_no_device),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedButton(onClick = onOpenConnection) { Text(stringResource(R.string.conn_open)) }
        }

        annotationPanel()

        // FlowRow: the buttons do not fit in one line; a plain Row squeezed the last one until its text went vertical.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenLive) {
                Text(stringResource(R.string.live_open))
            }
            OutlinedButton(onClick = onOpenSummary) {
                Text(stringResource(R.string.sum_open))
            }
            OutlinedButton(onClick = onOpenEvents) {
                Text(stringResource(R.string.ev_open))
            }
            OutlinedButton(onClick = onOpenBrowse) {
                Text(stringResource(R.string.br_open))
            }
            OutlinedButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.diag_open))
            }
            OutlinedButton(onClick = onOpenReplay) {
                Text(stringResource(R.string.replay_open))
            }
        }
    }
}
