package com.punkunpun.polarisecg.replay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.service.AcquisitionService

/** Speed factors offered; 0 = as fast as the pipeline can go. */
private val SPEEDS = listOf(1.0, 10.0, 60.0, 0.0)

/** Pick a recorded session and play it through the live pipeline (P5.2); the Live screen shows it while it runs. */
@Composable
fun ReplayScreen(app: PolarisApp, onBack: () -> Unit, onOpenLive: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val player = app.replayPlayer
    val state by player.state.collectAsState()
    val stats by app.liveProcessor.statsFlow.collectAsState()
    val sessionRunning by AcquisitionService.running.collectAsState()

    val sessions = remember { app.replayableSessions() }
    var selected by rememberSaveable { mutableStateOf(sessions.firstOrNull()?.absolutePath) }
    var speed by rememberSaveable { mutableDoubleStateOf(60.0) }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.replay_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.replay_hint), style = MaterialTheme.typography.bodySmall)

        if (sessionRunning) Text(stringResource(R.string.replay_session_running), color = MaterialTheme.colorScheme.error)

        if (sessions.isEmpty()) {
            Text(stringResource(R.string.replay_none))
        } else {
            Text(stringResource(R.string.replay_pick))
            for (dir in sessions) {
                val label = dir.name + if (dir.parentFile?.name == "replay") " (replay)" else ""
                if (dir.absolutePath == selected) {
                    Button(onClick = { selected = dir.absolutePath }, enabled = !state.running) { Text(label) }
                } else {
                    OutlinedButton(onClick = { selected = dir.absolutePath }, enabled = !state.running) { Text(label) }
                }
            }
        }

        Text(stringResource(R.string.replay_speed))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in SPEEDS) {
                val text = if (option == 0.0) stringResource(R.string.replay_max) else "${option.toInt()}×"
                if (option == speed) {
                    Button(onClick = { speed = option }, enabled = !state.running) { Text(text) }
                } else {
                    OutlinedButton(onClick = { speed = option }, enabled = !state.running) { Text(text) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running || state.loading) {
                Button(onClick = { player.stop() }) { Text(stringResource(R.string.replay_stop)) }
            } else {
                Button(
                    onClick = { selected?.let { player.start(java.io.File(it), speed) } },
                    enabled = selected != null && !sessionRunning,
                ) { Text(stringResource(R.string.replay_start)) }
            }
            OutlinedButton(onClick = onOpenLive) { Text(stringResource(R.string.live_open)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
        }

        if (state.loading) Text(stringResource(R.string.replay_loading))
        if (state.running) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Same numbers as the live screen; after the run they can be compared with `analyze` of the same session.
        Text(
            stringResource(
                R.string.live_beats,
                stats.beats, stats.normal, stats.premature, stats.ventricular, stats.ventricularDoubt, stats.unclassifiable,
            ),
        )
        if (stats.errors > 0) Text(stringResource(R.string.replay_errors, stats.errors), color = MaterialTheme.colorScheme.error)
    }
}
