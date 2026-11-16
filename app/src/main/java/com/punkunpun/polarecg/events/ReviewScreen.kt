package com.punkunpun.polarecg.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.dsp.annotations.OwnerLabel
import com.punkunpun.polarecg.dsp.event.ClipBeat
import com.punkunpun.polarecg.dsp.event.ClipFormat
import com.punkunpun.polarecg.dsp.event.EventClip
import com.punkunpun.polarecg.dsp.event.eventTitle
import com.punkunpun.polarecg.dsp.event.nearestBeat
import com.punkunpun.polarecg.dsp.event.neighbourIntervals
import com.punkunpun.polarecg.dsp.motion.AccWindow
import com.punkunpun.polarecg.dsp.quality.QualityConfig
import com.punkunpun.polarecg.storage.EventEntity
import com.punkunpun.polarecg.storage.LabelEntity
import com.punkunpun.polarecg.storage.clipsDir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private const val DP_PER_SECOND = 110   // horizontal scale of the trace: 1 s = 110 dp (a beat about every 90 dp at 75 bpm)
private const val TAP_RANGE_MS = 450L   // a tap this close to a beat selects it
private const val MIN_SPAN_UV = 600f
private const val ACC_TOP_MG = 400.0     // top of the movement band; more is drawn at the top
private const val ACC_GRID_LOW_MG = 10.0 // below this the strap is still (breathing, heartbeat)
private val MOTION_OK_MG = QualityConfig().motionOkMg // from here on the signal quality counts motion against the beat

/** The text shown for a label value: "N", "S", "V", "Q", "?" or "undone" for the empty one. */
private fun labelText(label: String) = if (label.isEmpty()) "undone" else label

/** The owner's current mark on a beat, or null if there is none (never labelled, or undone). "?" counts as a mark. */
private fun ownerMark(row: LabelEntity?): String? = row?.label?.takeIf { it.isNotEmpty() }

private fun localTimeMs(utcMs: Long) = "${localTime(utcMs)}.${(utcMs % 1000).toString().padStart(3, '0')}"

private fun autoText(b: ClipBeat): String {
    val notes = buildList {
        if (!b.good) add("poor signal")
        if (b.rescued) add("rescued")
        if (b.vDoubt) add("questionable")
    }
    return if (notes.isEmpty()) b.cls else "${b.cls} (${notes.joinToString(", ")})"
}

/**
 * Review of one event (P5.7): the ECG clip with a marker on every beat (the automatic class above it, the owner's label
 * below it). Tap a beat, then press N / S / V / Q / ? (or Undo) to label it. Every press adds a row to the `label` table;
 * nothing is overwritten, and the automatic class in `beat.cls` never changes (rule in plan section 8).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(app: PolarApp, eventId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val eventDao = app.database.eventDao()

    var event by remember { mutableStateOf<EventEntity?>(null) }
    var clip by remember { mutableStateOf<EventClip?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var acc by remember { mutableStateOf<AccWindow?>(null) }
    var accLoading by remember { mutableStateOf(true) }

    LaunchedEffect(eventId) {
        withContext(Dispatchers.IO) {
            val e = eventDao.byId(eventId)
            event = e
            // The clip file can be missing (pruned, or never cut): the screen then says so instead of failing.
            clip = e?.clipFile?.let { name ->
                runCatching { ClipFormat.decode(File(clipsDir(app), name).readBytes()) }.getOrNull()
            }
        }
        loaded = true
    }
    // The clip file holds no accelerometer: it comes from the raw files, if they are still on the phone. That read is heavy (the
    // ECG of the session is read to place the ACC on its clock), so the ECG is shown first and the movement band fills in later.
    LaunchedEffect(clip) {
        val c = clip ?: return@LaunchedEffect
        val half = (c.ecg.endUtcMs - c.ecg.startUtcMs) / 2
        acc = withContext(Dispatchers.IO) { runCatching { loadRawWindow(app.sessionsDir, c.ecg.startUtcMs + half, half)?.acc }.getOrNull() }
        accLoading = false
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.ev_back)) }
            val e = event
            Text(
                if (e != null) "${eventTitle(e.type)}  ${localTime(e.startUtcMs)}" else stringResource(R.string.ev_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        event?.let { Text(it.detail) }
        val c = clip
        if (c == null) {
            if (loaded) Text(stringResource(R.string.ev_clip_missing))
            return@Column // leaves this Column block; nothing below is drawn
        }

        ClipReviewBody(app, c, initialBeatMs = 0L, acc = acc, accLoading = accLoading)
    }
}

/**
 * Marks the owner has chosen but not saved yet (the beat browse): beat time -> label, "" = undo of a saved label. Nothing is
 * written until the screen's Accept; [set] with null drops a pending mark. `null` instead of a [StagedLabels] = every press is saved
 * at once (the event review).
 */
internal class StagedLabels(val marks: Map<Long, String>, val set: (beatUtcMs: Long, label: String?) -> Unit)

private val PENDING_COLOR = Color(0xFFF57C00) // orange: a mark that is not saved yet (saved ones are blue)

/**
 * The trace of [clip] with the beat details and the label buttons below it: shared by the event review and the beat browse.
 * [initialBeatMs] is the beat selected at first (0 = none). With [staging] the buttons only stage marks (shown in orange);
 * without it they save at once. [acc] is drawn as a movement band under the ECG (null = no ACC data, or [accLoading] while it is
 * still being read). Emits its children into the caller's Column.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClipReviewBody(
    app: PolarApp,
    c: EventClip,
    initialBeatMs: Long,
    staging: StagedLabels? = null,
    acc: AccWindow? = null,
    accLoading: Boolean = false,
) {
    val labelDao = app.database.labelDao()
    val scope = rememberCoroutineScope()
    var selectedBeatMs by rememberSaveable(c.ecg.startUtcMs) { mutableLongStateOf(initialBeatMs) } // 0 = none selected
    // Current label of every beat in the clip; the Flow re-runs when a row is added.
    val labelRows by remember(c) { labelDao.observeCurrentBetween(c.ecg.startUtcMs, c.ecg.endUtcMs + 1) }
        .collectAsState(initial = emptyList())
    val labelByBeat = remember(labelRows) { labelRows.associateBy { it.beatUtcMs } }

    Text(stringResource(R.string.ev_hint), style = MaterialTheme.typography.bodySmall)
    ClipTrace(
        clip = c,
        labels = labelByBeat,
        staged = staging?.marks ?: emptyMap(),
        selectedBeatMs = selectedBeatMs,
        acc = acc,
        onTap = { utcMs -> selectedBeatMs = nearestBeat(c.beats, utcMs, TAP_RANGE_MS)?.utcMs ?: 0L },
    )
    Text(
        when {
            acc != null -> stringResource(R.string.ev_acc_legend, ACC_GRID_LOW_MG.toInt(), MOTION_OK_MG.toInt())
            accLoading -> stringResource(R.string.ev_acc_loading)
            else -> stringResource(R.string.ev_acc_none)
        },
        style = MaterialTheme.typography.bodySmall,
    )

    val beat = c.beats.firstOrNull { it.utcMs == selectedBeatMs }
    if (beat == null) {
        Text(stringResource(R.string.ev_select_beat))
    } else {
        val current = labelByBeat[beat.utcMs]
        var history by remember(beat.utcMs) { mutableStateOf(emptyList<LabelEntity>()) }
        LaunchedEffect(beat.utcMs, current?.id) { history = withContext(Dispatchers.IO) { labelDao.historyOf(beat.utcMs) } }

        Text(localTimeMs(beat.utcMs), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.ev_auto, autoText(beat)))
        neighbourIntervals(c.beats, c.beats.indexOf(beat)).let { (before, after) ->
            Text(stringResource(R.string.ev_rr, before?.toString() ?: "-", after?.toString() ?: "-"))
        }
        // the motion the signal quality used for this beat (saved with the beat; gone once the beat row is pruned after 90 days)
        var motionMg by remember(beat.utcMs) { mutableStateOf<Double?>(null) }
        LaunchedEffect(beat.utcMs) { motionMg = withContext(Dispatchers.IO) { app.database.beatDao().beatAt(beat.utcMs)?.motionMg } }
        Text(stringResource(R.string.ev_motion, motionMg?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-", MOTION_OK_MG.toInt()))
        val pending = staging?.marks?.get(beat.utcMs)
        val shownMark = if (pending != null) pending.ifEmpty { labelText("") } else ownerMark(current) ?: stringResource(R.string.ev_not_labelled)
        Text(
            stringResource(R.string.ev_yours, shownMark) + if (pending != null) " " + stringResource(R.string.br_pending) else "",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in OwnerLabel.CHOICES) {
                val chosen = choice.isNotEmpty() && (pending ?: current?.label) == choice
                val onClick = if (staging != null) {
                    {
                        // Undo drops a pending mark; with none pending it stages the undo of a saved label (a beat with no label: nothing to do)
                        when {
                            choice.isNotEmpty() -> staging.set(beat.utcMs, choice)
                            pending != null -> staging.set(beat.utcMs, null)
                            ownerMark(current) != null -> staging.set(beat.utcMs, "")
                        }
                    }
                } else {
                    {
                        // Room needs a background thread; the row is added, never changed (see LabelDao).
                        scope.launch(Dispatchers.IO) {
                            labelDao.insert(
                                LabelEntity(beatUtcMs = beat.utcMs, label = choice, note = "", createdAtUtcMs = System.currentTimeMillis()),
                            )
                        }
                        Unit
                    }
                }
                val text = if (choice.isEmpty()) "Undo" else choice
                if (chosen) Button(onClick = onClick) { Text(text) } else FilledTonalButton(onClick = onClick) { Text(text) }
            }
        }
        if (history.isNotEmpty()) {
            Text(stringResource(R.string.ev_history), style = MaterialTheme.typography.titleSmall)
            for (row in history.asReversed()) {
                Text("${localTimeMs(row.createdAtUtcMs)}  ${labelText(row.label)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The scrollable ECG strip: [DP_PER_SECOND] dp per second, so a 20 s clip is wider than the screen and is swiped sideways.
 * Above each beat its automatic class, below it the owner's label (if any); the selected beat has a tinted column.
 * `Canvas` draws with plain functions (no Compose element per beat), so 30 beats and 2 600 samples stay cheap.
 */
@Composable
private fun ClipTrace(
    clip: EventClip,
    labels: Map<Long, LabelEntity>,
    staged: Map<Long, String>,
    selectedBeatMs: Long,
    acc: AccWindow?,
    onTap: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val pxPerSecond = with(density) { DP_PER_SECOND.dp.toPx() }
    val scroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val quiet = MaterialTheme.colorScheme.outline
    val colors = mapOf(
        "N" to quiet,
        "S" to MaterialTheme.colorScheme.tertiary,
        "SS" to MaterialTheme.colorScheme.tertiary,
        "V" to MaterialTheme.colorScheme.error,
        "Q" to quiet,
    )
    val accLine = MaterialTheme.colorScheme.primary
    val ecg = clip.ecg
    val seconds = ecg.size * ecg.msPerSample / 1000.0
    // movement per ACC sample (mG), worked out once per window, not on every redraw
    val movement = remember(acc) { acc?.movementMg() }
    val widthDp = (seconds * DP_PER_SECOND).toFloat().dp

    // Start with the event in view (a little before it), not at the very beginning of the clip.
    LaunchedEffect(clip) {
        val eventX = (clip.eventStartUtcMs - ecg.startUtcMs) / 1000f * pxPerSecond
        scroll.scrollTo((eventX - pxPerSecond).toInt().coerceAtLeast(0))
    }
    // Vertical range: the 0.2 % / 99.8 % values of the samples, so one big artifact does not flatten the heartbeats.
    val range = remember(clip) {
        val sorted = ecg.samplesUv.sortedArray()
        if (sorted.isEmpty()) {
            0f to 1f
        } else {
            sorted[(sorted.size * 0.002).toInt()].toFloat() to sorted[(sorted.size * 0.998).toInt().coerceAtMost(sorted.size - 1)].toFloat()
        }
    }
    val lo = range.first
    val hi = range.second

    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Canvas(
            modifier = Modifier
                .width(widthDp)
                .height(if (acc != null) 326.dp else 256.dp)
                .pointerInput(clip) {
                    // The tap position is in the strip's own pixels (it moves with the scroll): pixels -> seconds -> UTC.
                    detectTapGestures { p -> onTap(ecg.startUtcMs + (p.x / pxPerSecond * 1000).toLong()) }
                },
        ) {
            val topBand = 38.dp.toPx() // two rows: the automatic class letters, and under them the intervals
            val bottomBand = 26.dp.toPx() // the owner's labels
            val accBand = if (acc != null) 70.dp.toPx() else 0f // the movement band, at the very bottom
            val plotHeight = size.height - topBand - bottomBand - accBand
            val labelTop = topBand + plotHeight + 3.dp.toPx()
            val mid = (lo + hi) / 2
            val span = maxOf(hi - lo, MIN_SPAN_UV) * 1.15f
            fun yOf(uv: Int) = topBand + plotHeight / 2 - ((uv - mid) / span * plotHeight).coerceIn(-plotHeight / 2, plotHeight / 2)

            // 1 s grid
            var s = 0
            while (s <= seconds) {
                val x = s * pxPerSecond
                drawLine(grid, Offset(x, topBand), Offset(x, topBand + plotHeight))
                s++
            }

            // the selected beat: a tinted column
            val sel = clip.beats.firstOrNull { it.utcMs == selectedBeatMs }
            if (sel != null) {
                val x = (sel.utcMs - ecg.startUtcMs) / 1000f * pxPerSecond
                drawRect(tint, Offset(x - 0.25f * pxPerSecond, 0f), Size(0.5f * pxPerSecond, size.height))
            }
            // the event itself: a thin bar along the top edge
            val ex0 = (clip.eventStartUtcMs - ecg.startUtcMs) / 1000f * pxPerSecond
            val ex1 = (clip.eventEndUtcMs - ecg.startUtcMs) / 1000f * pxPerSecond
            drawRect(colors.getValue("V").copy(alpha = 0.5f), Offset(ex0, 0f), Size(maxOf(ex1 - ex0, 4f), 3.dp.toPx()))

            val path = Path()
            val stepPx = (ecg.msPerSample / 1000.0 * pxPerSecond).toFloat()
            for (i in 0 until ecg.size) {
                val x = i * stepPx
                val y = yOf(ecg.samplesUv[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = Stroke(width = 1.6.dp.toPx()))

            val letterStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val rrStyle = TextStyle(fontSize = 11.sp, color = quiet)
            for ((i, b) in clip.beats.withIndex()) {
                // the interval from the previous beat, in ms, centred between the two beats
                if (i > 0) {
                    val prevX = (clip.beats[i - 1].utcMs - ecg.startUtcMs) / 1000f * pxPerSecond
                    val thisX = (b.utcMs - ecg.startUtcMs) / 1000f * pxPerSecond
                    val rr = textMeasurer.measure((b.utcMs - clip.beats[i - 1].utcMs).toString(), rrStyle)
                    drawText(rr, topLeft = Offset((prevX + thisX) / 2f - rr.size.width / 2f, 21.dp.toPx()))
                }
                val x = (b.utcMs - ecg.startUtcMs) / 1000f * pxPerSecond
                val color = colors[b.cls] ?: quiet
                drawLine(color.copy(alpha = 0.5f), Offset(x, topBand), Offset(x, topBand + plotHeight))
                val auto = textMeasurer.measure(b.cls, letterStyle.copy(color = color))
                drawText(auto, topLeft = Offset(x - auto.size.width / 2f, 2.dp.toPx()))
                val pending = staged[b.utcMs]
                val mark = if (pending != null) pending.ifEmpty { "-" } else ownerMark(labels[b.utcMs])
                if (mark != null) {
                    // the owner's label below the trace: blue when saved, orange while it is only staged; never confused with the automatic letter above
                    val own = textMeasurer.measure(mark, letterStyle.copy(color = if (pending != null) PENDING_COLOR else Color(0xFF1E88E5)))
                    drawText(own, topLeft = Offset(x - own.size.width / 2f, labelTop))
                }
            }

            if (acc != null && movement != null) {
                // Movement band: a square-root scale (0 at the bottom, ACC_TOP_MG at the top), so a strap lying still (a few mG) is a
                // low ripple, a small shift (10-50 mG) is clearly visible and walking (hundreds) still fits.
                val accTop = size.height - accBand + 4.dp.toPx()
                val accBottom = size.height - 2.dp.toPx()
                fun yAcc(mg: Double) = accBottom - (sqrt(mg.coerceIn(0.0, ACC_TOP_MG) / ACC_TOP_MG) * (accBottom - accTop)).toFloat()
                drawLine(grid, Offset(0f, accBottom), Offset(size.width, accBottom))
                val dashes = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                drawLine(grid, Offset(0f, yAcc(ACC_GRID_LOW_MG)), Offset(size.width, yAcc(ACC_GRID_LOW_MG)), pathEffect = dashes)
                val okColor = colors.getValue("V").copy(alpha = 0.45f)
                drawLine(okColor, Offset(0f, yAcc(MOTION_OK_MG)), Offset(size.width, yAcc(MOTION_OK_MG)), pathEffect = dashes)
                // the scale, repeated every 5 s so it is in view wherever the strip is scrolled
                val scaleStyle = TextStyle(fontSize = 9.sp, color = quiet)
                var k = 0
                while (k <= seconds) {
                    val x = k * pxPerSecond + 2.dp.toPx()
                    for (mg in listOf(ACC_GRID_LOW_MG, MOTION_OK_MG)) {
                        val t = textMeasurer.measure(mg.toInt().toString(), scaleStyle)
                        drawText(t, topLeft = Offset(x, yAcc(mg) - t.size.height))
                    }
                    k += 5
                }
                // where the beats are, faintly, so a jolt can be matched to a beat
                for (b in clip.beats) {
                    val x = (b.utcMs - ecg.startUtcMs) / 1000f * pxPerSecond
                    drawLine((colors[b.cls] ?: quiet).copy(alpha = 0.3f), Offset(x, accTop), Offset(x, accBottom))
                }
                val accPath = Path()
                for (i in 0 until acc.size) {
                    val x = (acc.utcMs[i] - ecg.startUtcMs) / 1000f * pxPerSecond
                    val y = yAcc(movement[i])
                    // a hole in the ACC (more than 0.1 s without a sample) is left empty rather than bridged with a line
                    if (i == 0 || acc.utcMs[i] - acc.utcMs[i - 1] > 100) accPath.moveTo(x, y) else accPath.lineTo(x, y)
                }
                drawPath(accPath, accLine, style = Stroke(width = 1.4.dp.toPx()))
            }
        }
    }
}
