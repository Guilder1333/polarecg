package com.punkunpun.polarisecg.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.log.ConnectionLog
import com.punkunpun.polarisecg.log.computeGaps
import java.text.DateFormat
import java.util.Date

/** Lists data gaps (with total time lost) and the raw connection events, newest first. */
@Composable
fun DiagnosticsScreen(log: ConnectionLog, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack) // system back button returns to the previous screen
    val events by log.events.collectAsState()
    // remember(events): recompute only when the list changes, not on every redraw.
    val gaps = remember(events) { computeGaps(events).asReversed() }
    val now = System.currentTimeMillis()
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val timeOnly = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.diag_back)) }

        if (gaps.isEmpty()) {
            Text(stringResource(R.string.diag_no_gaps))
        } else {
            val total = gaps.sumOf { it.durationMs(now) }
            Text(
                stringResource(R.string.diag_gaps_summary, gaps.size, formatDuration(total)),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (gaps.isNotEmpty()) {
                item { Text(stringResource(R.string.diag_gaps_title), style = MaterialTheme.typography.titleSmall) }
                items(gaps) { gap ->
                    val start = dateTime.format(Date(gap.startMs))
                    val duration = formatDuration(gap.durationMs(now))
                    Text(
                        if (gap.endMs == null) {
                            stringResource(R.string.diag_gap_ongoing, start, duration, gap.reason)
                        } else {
                            stringResource(
                                R.string.diag_gap_line, start, timeOnly.format(Date(gap.endMs)), duration, gap.reason,
                            )
                        },
                    )
                }
            }
            item { Text(stringResource(R.string.diag_events_title), style = MaterialTheme.typography.titleSmall) }
            items(events.asReversed()) { e ->
                Text(
                    "${dateTime.format(Date(e.timeMs))}  ${e.type}" + (e.detail?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 3725000 -> "1h 02m 05s"; short durations drop the larger units. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "%dh %02dm %02ds".format(h, m, s)
        m > 0 -> "%dm %02ds".format(m, s)
        else -> "${s}s"
    }
}
