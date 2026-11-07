package com.punkunpun.polarisecg.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarisecg.PolarisApp

/**
 * The event list and, after a tap, the review screen of one event (P5.7). `rememberSaveable` keeps the open event across a
 * screen rotation; 0 means "the list" (the database numbers events from 1).
 */
@Composable
fun EventsRoute(app: PolarisApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openEventId by rememberSaveable { mutableLongStateOf(0L) }
    if (openEventId == 0L) {
        EventListScreen(app, onOpen = { openEventId = it }, onBack = onBack, modifier = modifier)
    } else {
        ReviewScreen(app, eventId = openEventId, onBack = { openEventId = 0L }, modifier = modifier)
    }
}
