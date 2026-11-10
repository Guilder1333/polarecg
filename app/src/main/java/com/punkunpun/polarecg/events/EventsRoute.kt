package com.punkunpun.polarecg.events

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.dsp.classify.ClassifierConfig
import com.punkunpun.polarecg.storage.BeatEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
fun BrowseRoute(
    app: PolarApp,
    onBack: () -> Unit,
    /** A beat to open at once (deep link), 0 = none. */
    deepBeatMs: Long = 0L,
    onDeepBeatHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var openBeatMs by rememberSaveable { mutableLongStateOf(0L) }
    // the browse's own choices survive a trip to a beat window and back
    var kindKey by rememberSaveable { mutableStateOf(BrowseKind.S.key) }
    var epochDay by rememberSaveable { mutableLongStateOf(0L) } // 0 = the day of the newest beat, chosen on first open
    var unlabelledOnly by rememberSaveable { mutableStateOf(false) }
    // scroll position: kept here because the browse screen is left while a beat is open
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A deep link: show the list of the beat's class on the beat's day behind it (so Prev and Next work), then open the beat.
    LaunchedEffect(deepBeatMs) {
        if (deepBeatMs != 0L) {
            val beat = withContext(Dispatchers.IO) { app.database.beatDao().beatAt(deepBeatMs) }
            kindKey = when (beat?.cls) { "V" -> BrowseKind.V.key; "Q" -> BrowseKind.Q.key; "N" -> BrowseKind.EARLY_N.key; else -> BrowseKind.S.key }
            epochDay = Instant.ofEpochMilli(deepBeatMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
            openBeatMs = deepBeatMs
            onDeepBeatHandled()
        }
    }
    // the beats of the list being browsed (class and day), whatever their labels, in time order
    suspend fun browseList(): List<BeatEntity> {
        val kind = BrowseKind.fromKey(kindKey)
        val (from, to) = dayRangeUtc(LocalDate.ofEpochDay(epochDay))
        val dao = app.database.beatDao()
        return if (kind == BrowseKind.EARLY_N) dao.earlyNormals(from, to, ClassifierConfig().sPrematurity, LIST_CAP) else dao.byClass(from, to, kind.key, LIST_CAP)
    }
    // The windows of the beats around the open one, kept across Next / Previous (the screen below is rebuilt for every beat).
    val clipCache = remember { ClipCache(scope, { ms -> loadBeatClip(app, ms) }) }
    if (openBeatMs != 0L) {
        BrowseWindowScreen(
            app, beatUtcMs = openBeatMs, onBack = { openBeatMs = 0L },
            nextBeatOf = { ms -> browseList().firstOrNull { it.utcMs > ms }?.utcMs },
            prevBeatOf = { ms -> browseList().lastOrNull { it.utcMs < ms }?.utcMs },
            browseList = { browseList().map { it.utcMs } },
            cache = clipCache,
            onOpenBeat = { openBeatMs = it },
            modifier = modifier,
        )
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
