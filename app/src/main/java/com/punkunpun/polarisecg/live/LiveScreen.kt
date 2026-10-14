package com.punkunpun.polarisecg.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.diagnostics.formatDuration
import com.punkunpun.polarisecg.dsp.live.SampleRing
import com.punkunpun.polarisecg.log.computeGaps
import com.punkunpun.polarisecg.log.eventsOfLatestSession
import com.punkunpun.polarisecg.polar.ConnectionState
import com.punkunpun.polarisecg.polar.ECG_RATE_HZ
import kotlinx.coroutines.delay

private const val WINDOW_SECONDS = 5
private val FPS_OPTIONS = listOf(1, 5, 20, 60)
private const val DEFAULT_FPS = 20
private const val MAX_SLIDE_MS = 1_500L // stop sliding if no data arrives (disconnect)
private const val MIN_SPAN_UV = 1_000f // never zoom into pure noise

/** Scrolling ECG (last 5 s), current HR, connection status and gap count. */
@Composable
fun LiveScreen(app: PolarisApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val connection = app.polarConnection
    val state by connection.state.collectAsState()
    val events by app.connectionLog.events.collectAsState()
    // Only this session: the log file also holds every earlier run.
    val gaps = remember(events) { computeGaps(eventsOfLatestSession(events)) }

    // The ring is a plain object, not Compose state, so writing to it does not redraw anything.
    // `frame` is state: bumping it on a timer is what makes the Canvas redraw.
    val ring = remember { SampleRing(ECG_RATE_HZ * WINDOW_SECONDS) }
    var frame by remember { mutableIntStateOf(0) }
    var heartRate by remember { mutableStateOf<Int?>(null) }
    var contact by remember { mutableStateOf<Boolean?>(null) }

    // Redraw rate chosen by the user. rememberSaveable keeps it across rotation.
    var fps by rememberSaveable { mutableIntStateOf(DEFAULT_FPS) }
    // Plain one-element array (not state): when the last ECG frame arrived, in System.nanoTime().
    val lastFrameNs = remember { LongArray(1) }

    LaunchedEffect(connection) {
        connection.ecg.collect {
            ring.add(it.samplesUv)
            lastFrameNs[0] = System.nanoTime()
        }
    }
    LaunchedEffect(connection) {
        connection.hr.collect {
            heartRate = it.heartRateBpm
            contact = it.contactDetected
        }
    }
    // Restarts whenever `fps` changes (the key), so the new rate applies immediately.
    LaunchedEffect(fps) {
        while (true) {
            delay(1000L / fps)
            frame++
        }
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val stateText = when (val s = state) {
        is ConnectionState.Disconnected ->
            stringResource(R.string.conn_state_disconnected) + (s.reason?.let { " ($it)" } ?: "")
        ConnectionState.Connecting -> stringResource(R.string.conn_state_connecting)
        ConnectionState.Connected -> stringResource(R.string.conn_state_connected)
        ConnectionState.Streaming -> stringResource(R.string.conn_state_streaming)
    }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.live_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            heartRate?.let { stringResource(R.string.live_hr, it) } ?: stringResource(R.string.live_hr_none),
            style = MaterialTheme.typography.displayMedium,
        )
        Text(stateText)
        if (contact == false) Text(stringResource(R.string.live_no_contact), color = MaterialTheme.colorScheme.error)
        Text(
            stringResource(
                R.string.live_gaps,
                gaps.size,
                formatDuration(gaps.sumOf { it.durationMs(System.currentTimeMillis()) }),
            ),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in FPS_OPTIONS) {
                // The selected rate is a filled button, the others are outlined.
                if (option == fps) {
                    Button(onClick = { fps = option }) { Text("$option FPS") }
                } else {
                    OutlinedButton(onClick = { fps = option }) { Text("$option FPS") }
                }
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            frame // read the state so this block is re-run on every tick
            val samples = ring.snapshot()
            // The strap delivers ECG in bursts (about one per second). Between bursts the trace
            // is slid left by the time elapsed since the last one, so it scrolls smoothly; the
            // next burst fills exactly the gap that was left on the right.
            val elapsedMs = ((System.nanoTime() - lastFrameNs[0]) / 1_000_000L).coerceIn(0L, MAX_SLIDE_MS)
            val shiftPx = size.width * elapsedMs / (WINDOW_SECONDS * 1000f)
            // Light 1 s vertical grid lines, so the scroll speed is readable.
            for (s in 0..WINDOW_SECONDS) {
                val x = size.width * s / WINDOW_SECONDS
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height))
            }
            if (samples.size < 2) return@Canvas

            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in samples) {
                if (v < lo) lo = v.toFloat()
                if (v > hi) hi = v.toFloat()
            }
            val mid = (lo + hi) / 2
            val span = maxOf(hi - lo, MIN_SPAN_UV) * 1.1f
            val capacity = ring.capacity
            // Newest sample is at the right edge; until the ring is full the trace grows from the right.
            val path = Path()
            val firstIndex = capacity - samples.size
            for (i in samples.indices) {
                val x = size.width * (firstIndex + i) / (capacity - 1) - shiftPx
                val y = size.height / 2 - (samples[i] - mid) / span * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
        Text(stringResource(R.string.live_scale_hint), style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }
    }
}
