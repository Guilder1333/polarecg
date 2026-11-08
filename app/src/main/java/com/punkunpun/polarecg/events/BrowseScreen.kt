package com.punkunpun.polarecg.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.dsp.annotations.ACCEPTED_NOTE
import com.punkunpun.polarecg.dsp.annotations.currentLabelRows
import com.punkunpun.polarecg.dsp.annotations.labelsToSave
import com.punkunpun.polarecg.dsp.classify.ClassifierConfig
import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.event.EventClip
import com.punkunpun.polarecg.storage.BeatEntity
import com.punkunpun.polarecg.storage.LabelEntity
import com.punkunpun.polarecg.storage.toRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which beats the browse lists. [EARLY_N] = normal beats that came early: the ones that may really be S (P4.8). */
enum class BrowseKind(val key: String) {
    S("S"), EARLY_N("EARLY_N"), V("V"), Q("Q");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: S
    }
}

internal const val LIST_CAP = 3000
private val timeOfDay = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd (EEE)")

/** Start and end (exclusive) of a local calendar day, in UTC ms. The owner thinks in local days; the database keeps UTC. */
internal fun dayRangeUtc(day: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    return day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * "Beats near a time" (P5.7): the beats of one automatic class (or the early normals) on one day, in time order. A tap opens the
 * ECG around the beat, cut from the raw files, where it can be labelled like an event clip. This is how the S beats and the
 * early-N group are reviewed, since they are not events and have no saved clip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseScreen(
    app: PolarApp,
    kind: BrowseKind,
    epochDay: Long,
    onKind: (BrowseKind) -> Unit,
    onDay: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    // kept by the caller: this screen leaves the screen while a beat is open, and these must not be forgotten with it
    listState: LazyListState,
    unlabelledOnly: Boolean,
    onUnlabelledOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val beatDao = app.database.beatDao()
    val labelDao = app.database.labelDao()

    // First visit (epochDay 0): the day of the newest saved beat, so the list is not empty on opening.
    LaunchedEffect(epochDay) {
        if (epochDay == 0L) {
            val newest = withContext(Dispatchers.IO) { beatDao.latestUtcMs() }
            onDay((newest?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()).toEpochDay())
        }
    }
    val day = LocalDate.ofEpochDay(if (epochDay == 0L) LocalDate.now().toEpochDay() else epochDay)
    val (from, to) = dayRangeUtc(day)

    var beats by remember { mutableStateOf<List<BeatEntity>>(emptyList()) }
    var loadedKey by remember { mutableStateOf<String?>(null) } // which (class, day) `beats` belongs to
    val key = "${kind.key}:$from"
    LaunchedEffect(key) {
        beats = withContext(Dispatchers.IO) {
            when (kind) {
                BrowseKind.EARLY_N -> beatDao.earlyNormals(from, to, ClassifierConfig().sPrematurity, LIST_CAP)
                else -> beatDao.byClass(from, to, kind.key, LIST_CAP)
            }
        }
        loadedKey = key
    }
    // null until Room has answered once: the list is not drawn before that
    val labelRows by remember(from) { labelDao.observeCurrentBetween(from, to) }.collectAsState<List<LabelEntity>, List<LabelEntity>?>(initial = null)
    val labelByBeat = remember(labelRows) { (labelRows ?: emptyList()).associateBy { it.beatUtcMs } }
    val shown = if (unlabelledOnly) beats.filter { labelByBeat[it.utcMs]?.label.isNullOrEmpty() } else beats
    // Drawing the list before beats and labels are there would show an empty or wrong list for a moment, and a LazyColumn with
    // no items throws its scroll position away: the reason the list used to jump to the top after a beat was reviewed.
    val ready = loadedKey == key && labelRows != null

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ev_back)) }
            Text(stringResource(R.string.br_title), style = MaterialTheme.typography.titleLarge)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = { onDay(day.minusDays(1).toEpochDay()) }) { Text("<") }
            Text(day.format(dayFormat), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { onDay(day.plusDays(1).toEpochDay()) }) { Text(">") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, label) in listOf(
                BrowseKind.S to R.string.br_kind_s,
                BrowseKind.EARLY_N to R.string.br_kind_early,
                BrowseKind.V to R.string.br_kind_v,
                BrowseKind.Q to R.string.br_kind_q,
            )) {
                FilterChip(selected = kind == k, onClick = { onKind(k) }, label = { Text(stringResource(label)) })
            }
        }
        Row(
            modifier = Modifier.clickable { onUnlabelledOnly(!unlabelledOnly) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = unlabelledOnly, onCheckedChange = onUnlabelledOnly)
            Text(stringResource(R.string.br_unlabelled_only))
        }
        if (!ready) {
            Text(stringResource(R.string.br_list_loading), style = MaterialTheme.typography.bodySmall)
            return@Column // leaves this Column block; the list below is not drawn yet
        }
        Text(stringResource(R.string.br_count, shown.size), style = MaterialTheme.typography.bodySmall)
        if (beats.size >= LIST_CAP) Text(stringResource(R.string.br_capped, LIST_CAP), style = MaterialTheme.typography.bodySmall)
        if (shown.isEmpty()) {
            Text(stringResource(R.string.br_empty))
        } else {
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.utcMs }) { b ->
                    val row = labelByBeat[b.utcMs]?.takeIf { it.label.isNotEmpty() }
                    // "ok S" = accepted as the automatic class; "you: V" = marked by hand
                    val mark = row?.let { if (it.note == ACCEPTED_NOTE) "ok ${it.label}" else "you: ${it.label}" }
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(b.utcMs) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val t = Instant.ofEpochMilli(b.utcMs).atZone(ZoneId.systemDefault()).format(timeOfDay)
                            Text("$t   ${b.cls}")
                            // the interval before the beat, and how early it came (1.00 = on time)
                            Text(listOfNotNull(b.rrMs?.let { "$it ms" }, b.prematurity?.let { "early %.2f".format(java.util.Locale.US, it) }).joinToString("  "))
                            Text(mark ?: "")
                        }
                    }
                }
            }
        }
    }
}

/** Staged marks survive a screen rotation as text: "beatMs:label,beatMs:label" (labels are N S V Q ? or empty, no ":" or ","). */
private val StagedSaver = Saver<Map<Long, String>, String>(
    save = { m -> m.entries.joinToString(",") { "${it.key}:${it.value}" } },
    restore = { s ->
        if (s.isEmpty()) emptyMap() else s.split(',').associate { part -> part.split(':', limit = 2).let { it[0].toLong() to it[1] } }
    },
)

/**
 * The ECG around one beat from the raw files. The owner marks only the beats that are wrong: the buttons stage marks (orange), and
 * **Accept** saves them and confirms the opened beat and the one before and after it as their automatic class ([labelsToSave]); **Cancel**
 * (and the system Back) saves nothing. So "Unlabelled only" in the list hides everything that was looked at.
 */
@Composable
fun BrowseWindowScreen(
    app: PolarApp,
    beatUtcMs: Long,
    onBack: () -> Unit,
    /** The beat after this one in the list being browsed (any label), or null at the end. */
    nextBeatOf: suspend (Long) -> Long?,
    /** The beat before this one in that list, or null at the start. */
    prevBeatOf: suspend (Long) -> Long?,
    onOpenBeat: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack) // Back = Cancel: nothing was saved
    val scope = rememberCoroutineScope()
    var clip by remember(beatUtcMs) { mutableStateOf<EventClip?>(null) }
    var loaded by remember(beatUtcMs) { mutableStateOf(false) }
    var staged by rememberSaveable(beatUtcMs, stateSaver = StagedSaver) { mutableStateOf(emptyMap<Long, String>()) }
    var saving by remember(beatUtcMs) { mutableStateOf(false) }
    var nextMs by remember(beatUtcMs) { mutableStateOf<Long?>(null) }
    var prevMs by remember(beatUtcMs) { mutableStateOf<Long?>(null) }
    LaunchedEffect(beatUtcMs) {
        nextMs = withContext(Dispatchers.IO) { nextBeatOf(beatUtcMs) }
        prevMs = withContext(Dispatchers.IO) { prevBeatOf(beatUtcMs) }
    }

    LaunchedEffect(beatUtcMs) {
        clip = withContext(Dispatchers.IO) {
            val ecg = loadRawWindow(app.sessionsDir, beatUtcMs, WINDOW_HALF_MS) ?: return@withContext null
            val beats = app.database.beatDao().beatsBetween(ecg.startUtcMs, ecg.endUtcMs + 1)
                .map { ClipBeat(it.utcMs, it.cls, it.good, it.vDoubt, it.rescued) }
            EventClip(ecg, beats, eventStartUtcMs = beatUtcMs, eventEndUtcMs = beatUtcMs)
        }
        loaded = true
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val c = clip
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (c == null) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ev_back)) }
            } else {
                OutlinedIconButton(onClick = onBack, enabled = !saving) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.br_cancel)) }
                // Accept saves and returns to the list; Next saves the same way and opens the following beat
                val save = { goNext: Boolean ->
                    saving = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = app.database.labelDao()
                            // read the labels again now: they may have changed since the strip was opened
                            val current = currentLabelRows(dao.currentBetween(c.ecg.startUtcMs, c.ecg.endUtcMs + 1).map { it.toRecord() })
                            // only the opened beat and its direct neighbours count as looked at: with the V or Q filter the strip
                            // holds other beats the owner never judged
                            val i = c.beats.indexOfFirst { it.utcMs == beatUtcMs }
                            val looked = c.beats.slice(maxOf(0, i - 1)..minOf(c.beats.lastIndex, i + 1)).map { it.utcMs }.toSet()
                            val rows = labelsToSave(c.beats, current, staged, System.currentTimeMillis(), confirm = if (i >= 0) looked else null)
                            dao.insertAll(rows.map { LabelEntity(beatUtcMs = it.beatUtcMs, label = it.label, note = it.note, createdAtUtcMs = it.createdAtUtcMs) })
                        }
                        val next = nextMs
                        if (goNext && next != null) onOpenBeat(next) else onBack()
                    }
                }
                // Previous saves nothing, like Cancel
                OutlinedIconButton(onClick = { prevMs?.let(onOpenBeat) }, enabled = !saving && prevMs != null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.br_prev)) }
                FilledIconButton(enabled = !saving, onClick = { save(false) }) { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.br_accept)) }
                FilledTonalIconButton(enabled = !saving && nextMs != null, onClick = { save(true) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.br_next)) }
            }
        }
        Text(localTime(beatUtcMs), style = MaterialTheme.typography.titleMedium)
        if (c == null) {
            Text(stringResource(if (loaded) R.string.br_no_ecg else R.string.br_loading))
        } else {
            Text(stringResource(R.string.br_hint), style = MaterialTheme.typography.bodySmall)
            ClipReviewBody(
                app, c, initialBeatMs = beatUtcMs,
                staging = StagedLabels(staged) { beat, label -> staged = if (label == null) staged - beat else staged + (beat to label) },
            )
        }
    }
}

private const val WINDOW_HALF_MS = 10_000L
