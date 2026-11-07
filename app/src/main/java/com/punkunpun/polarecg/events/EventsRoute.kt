package com.punkunpun.polarecg.events

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarecg.PolarApp
import kotlinx.coroutines.launch

/**
 * The event list and the review screen of one event (P5.7). `rememberSaveable` keeps the position across a screen rotation.
 * 0 means "not open" for the id (the database numbers events from 1).
 */
@Composable
fun EventsRoute(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openEventId by rememberSaveable { mutableLongStateOf(0L) }
    // The list's scroll position lives here, not in the list screen: that screen is left while an event is open, and the
    // position must still be there when the owner comes back. (`rememberLazyListState` also survives a rotation.)
    val listState = rememberLazyListState()
    if (openEventId != 0L) {
        ReviewScreen(app, eventId = openEventId, onBack = { openEventId = 0L }, modifier = modifier)
    } else {
        EventListScreen(app, onOpen = { openEventId = it }, onBack = onBack, listState = listState, modifier = modifier)
    }
}

/**
 * The beat browse and its ECG window (P5.7), a screen of its own (button on the main screen). 0 means "not open" for the beat
 * (no beat is at 0 ms).
 */
@Composable
fun BrowseRoute(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var openBeatMs by rememberSaveable { mutableLongStateOf(0L) }
    // the browse's own choices survive a trip to a beat window and back
    var kindKey by rememberSaveable { mutableStateOf(BrowseKind.S.key) }
    var epochDay by rememberSaveable { mutableLongStateOf(0L) } // 0 = the day of the newest beat, chosen on first open
    var unlabelledOnly by rememberSaveable { mutableStateOf(false) }
    // scroll position: kept here because the browse screen is left while a beat is open
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    if (openBeatMs != 0L) {
        BrowseWindowScreen(app, beatUtcMs = openBeatMs, onBack = { openBeatMs = 0L }, modifier = modifier)
    } else {
        BrowseScreen(
            app,
            kind = BrowseKind.fromKey(kindKey),
            epochDay = epochDay,
            // another class or day is another list: start at its top
            onKind = { kindKey = it.key; scope.launch { listState.scrollToItem(0) } },
            onDay = { if (epochDay != 0L) scope.launch { listState.scrollToItem(0) }; epochDay = it },
            onOpen = { openBeatMs = it },
            onBack = onBack,
            listState = listState,
            unlabelledOnly = unlabelledOnly,
            onUnlabelledOnly = { unlabelledOnly = it },
            modifier = modifier,
        )
    }
}
