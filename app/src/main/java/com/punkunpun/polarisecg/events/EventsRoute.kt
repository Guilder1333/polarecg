package com.punkunpun.polarisecg.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarisecg.PolarisApp

/**
 * The event list, the review screen of one event, and the beat browse with its ECG window (P5.7). `rememberSaveable` keeps the
 * position across a screen rotation. 0 means "not open" for the ids (the database numbers events from 1; no beat is at 0 ms).
 */
@Composable
fun EventsRoute(app: PolarisApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openEventId by rememberSaveable { mutableLongStateOf(0L) }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var openBeatMs by rememberSaveable { mutableLongStateOf(0L) }
    // the browse's own choices survive a trip to a beat window and back
    var kindKey by rememberSaveable { mutableStateOf(BrowseKind.S.key) }
    var epochDay by rememberSaveable { mutableLongStateOf(0L) } // 0 = the day of the newest beat, chosen on first open
    when {
        openEventId != 0L -> ReviewScreen(app, eventId = openEventId, onBack = { openEventId = 0L }, modifier = modifier)
        browsing && openBeatMs != 0L -> BrowseWindowScreen(app, beatUtcMs = openBeatMs, onBack = { openBeatMs = 0L }, modifier = modifier)
        browsing -> BrowseScreen(
            app,
            kind = BrowseKind.fromKey(kindKey),
            epochDay = epochDay,
            onKind = { kindKey = it.key },
            onDay = { epochDay = it },
            onOpen = { openBeatMs = it },
            onBack = { browsing = false },
            modifier = modifier,
        )
        else -> EventListScreen(app, onOpen = { openEventId = it }, onBrowse = { browsing = true }, onBack = onBack, modifier = modifier)
    }
}
