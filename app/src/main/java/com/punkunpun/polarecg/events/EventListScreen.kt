package com.punkunpun.polarecg.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.dsp.event.eventTitle
import com.punkunpun.polarecg.storage.EventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LIST_LIMIT = 500
private val dateTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

/** Local time of [utcMs] ("11-06 14:03:21"): the owner reads local time, the database keeps UTC. */
internal fun localTime(utcMs: Long): String = dateTime.format(Instant.ofEpochMilli(utcMs).atZone(ZoneId.systemDefault()))

/** The events the live pipeline found (V couplets, runs, bigeminy, pauses...), newest first. A tap opens the review screen. */
@Composable
fun EventListScreen(app: PolarApp, onOpen: (Long) -> Unit, onBack: () -> Unit, listState: LazyListState, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    // A Flow from Room: the list redraws by itself when a new event is saved.
    val events by app.database.eventDao().observeRecent(LIST_LIMIT).collectAsState(initial = emptyList())
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ev_back)) }
            Text(stringResource(R.string.ev_title), style = MaterialTheme.typography.titleLarge)
        }
        if (events.isEmpty()) {
            Text(stringResource(R.string.ev_empty))
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.id }) { EventRow(it, onClick = { onOpen(it.id) }) }
            }
        }
    }
}

@Composable
private fun EventRow(e: EventEntity, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(eventTitle(e.type), style = MaterialTheme.typography.titleMedium)
                Text(localTime(e.startUtcMs))
            }
            Text(e.detail, style = MaterialTheme.typography.bodyMedium)
            if (e.clipFile == null) {
                Text(stringResource(R.string.ev_no_clip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
