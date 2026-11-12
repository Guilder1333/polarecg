package com.punkunpun.polarecg.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.storage.PolarDatabase
import com.punkunpun.polarecg.dsp.summary.HourBucket
import com.punkunpun.polarecg.dsp.summary.HrvPoint
import com.punkunpun.polarecg.dsp.summary.LabelledBeat
import com.punkunpun.polarecg.dsp.summary.applyOwnerLabels
import com.punkunpun.polarecg.dsp.summary.MinuteSummary
import com.punkunpun.polarecg.dsp.summary.RangeSummary
import com.punkunpun.polarecg.dsp.summary.niceTicks
import com.punkunpun.polarecg.dsp.summary.summarizeRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The ranges the screen offers. */
enum class SummaryRange { LAST_24H, TODAY, LAST_NIGHT }

/** [from, to) of [range] at [nowMs] in [zone]. "Last night" is 22:00 to 08:00 (up to now if it is not over yet). */
fun rangeBounds(range: SummaryRange, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (range) {
        SummaryRange.LAST_24H -> (nowMs - 24 * 3_600_000L) to nowMs
        SummaryRange.TODAY -> today.atStartOfDay(zone).toInstant().toEpochMilli() to nowMs
        SummaryRange.LAST_NIGHT -> {
            // the night that ended this morning, or the one in progress if it is before 08:00
            val start = today.minusDays(1).atTime(22, 0).atZone(zone).toInstant().toEpochMilli()
            val end = today.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
            start to minOf(end, nowMs)
        }
    }
}

/**
 * What the summary shows for [from, to): the rows of the long-term tables, summed up, with the owner's labels preferred over the
 * automatic class (a relabelled beat counts as the owner's class; "?" and undone keep the automatic one). Beats already pruned
 * from the `beat` table cannot be corrected, those minutes keep the automatic counts. Room runs the reads off the UI thread.
 */
internal suspend fun loadSummary(database: PolarDatabase, from: Long, to: Long): RangeSummary {
    val dao = database.summaryDao()
    val stored = dao.minutesBetween(from, to).map {
        MinuteSummary(it.minuteUtcMs, it.beats, it.n, it.s, it.v, it.q, it.vDoubt, it.hrMeanBpm, it.hrMinBpm, it.hrMaxBpm, it.coverage, it.motionMg)
    }
    val labels = database.labelDao().currentBetween(from, to).associate { it.beatUtcMs to it.label }
    val minutes = if (labels.isEmpty()) stored else applyOwnerLabels(
        stored,
        database.beatDao().autoOfLabelled(from, to).mapNotNull { a -> labels[a.utcMs]?.let { LabelledBeat(a.utcMs, a.cls, a.vDoubt, it) } },
    )
    val hrv = dao.hrvBetween(from, to).map { HrvPoint(it.startUtcMs, it.valid, it.rmssdMs, it.sdnnMs) }
    return summarizeRange(minutes, hrv, from, to)
}

/** [from, to) of one local calendar day; today's day ends at [nowMs]. */
fun dayBounds(day: LocalDate, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to minOf(end, maxOf(nowMs, start))
}

/** The local calendar days that hold at least one of the [quarterHours] (UTC ms / 900 000) of `quarterHoursWithData`. */
fun daysWithData(quarterHours: List<Long>, zone: ZoneId = ZoneId.systemDefault()): Set<LocalDate> =
    quarterHours.mapTo(HashSet()) { Instant.ofEpochMilli(it * 900_000L).atZone(zone).toLocalDate() }

/** The date picker works in UTC: a picked day is the UTC midnight of that date; this is the calendar date it stands for. */
fun pickerDate(utcMillis: Long): LocalDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

internal fun percent(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f %%", it) } ?: "-"
internal fun one(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

/**
 * The summary (P5.6): beats and S/V burden, burden per hour, heart rate, HRV and how much was recorded, read from the
 * long-term tables, so it also works days later and after the beats were pruned.
 */
@Composable
fun SummaryScreen(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    var comparing by rememberSaveable { mutableStateOf(false) }
    if (comparing) {
        CompareScreen(app, onBack = { comparing = false }, modifier = modifier)
        return
    }
    var range by rememberSaveable { mutableStateOf(SummaryRange.LAST_24H) }
    // A chosen calendar day (epoch day) replaces the range chips until a chip is tapped again.
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickingDay by rememberSaveable { mutableStateOf(false) }
    var daysWithData by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var summary by remember { mutableStateOf<RangeSummary?>(null) }
    val dao = app.database.summaryDao()
    // A new row in the table (every minute while recording) makes the numbers load again.
    val rows by dao.observeMinuteCount().collectAsState(initial = 0)

    LaunchedEffect(rows) {
        daysWithData = withContext(Dispatchers.IO) { daysWithData(dao.quarterHoursWithData()) }
    }
    LaunchedEffect(range, pickedDay, rows) {
        val now = System.currentTimeMillis()
        val (from, to) = pickedDay?.let { dayBounds(LocalDate.ofEpochDay(it), now) } ?: rangeBounds(range, now)
        summary = withContext(Dispatchers.IO) { loadSummary(app.database, from, to) }
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sum_title), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((r, label) in listOf(
                SummaryRange.LAST_24H to R.string.sum_24h,
                SummaryRange.TODAY to R.string.sum_today,
                SummaryRange.LAST_NIGHT to R.string.sum_night,
            )) {
                FilterChip(selected = pickedDay == null && range == r, onClick = { pickedDay = null; range = r }, label = { Text(stringResource(label)) })
            }
            IconButton(onClick = { pickingDay = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.sum_pick_day))
            }
        }
        pickedDay?.let { Text(LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), style = MaterialTheme.typography.titleMedium) }
        if (pickingDay) DayPickerDialog(
            days = daysWithData,
            selected = pickedDay?.let(LocalDate::ofEpochDay),
            onPick = { pickedDay = it.toEpochDay(); pickingDay = false },
            onDismiss = { pickingDay = false },
        )
        val s = summary
        if (s == null || s.beats == 0) {
            Text(stringResource(R.string.sum_empty))
        } else {
            Text(stringResource(R.string.sum_beats, s.beats, s.n, s.s, s.v, s.q))
            Text(stringResource(R.string.sum_burden, percent(s.sPercent), percent(s.vPercent), percent(s.burdenPercent)), style = MaterialTheme.typography.titleMedium)
            if (s.vDoubt > 0) Text(stringResource(R.string.sum_vdoubt, s.vDoubt), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sum_hr, one(s.hrMinuteLowBpm), one(s.hrMeanBpm), one(s.hrMinuteHighBpm)))
            Text(stringResource(R.string.sum_hr_beats, one(s.hrMinBpm), one(s.hrMaxBpm)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sum_hrv, one(s.rmssdMedianMs), one(s.sdnnMedianMs), s.hrvWindows))
            Text(stringResource(R.string.sum_coverage, one(100 * s.coverage)))

            Text(stringResource(R.string.sum_chart_burden), style = MaterialTheme.typography.titleSmall)
            HourBars(s.hours, { it.sPercent }, { it.vPercent })
            Text(stringResource(R.string.sum_chart_hr), style = MaterialTheme.typography.titleSmall)
            HrBars(s.hours)
            Text(stringResource(R.string.sum_chart_rmssd), style = MaterialTheme.typography.titleSmall)
            HourBars(s.hours, { it.rmssdMs }, { null }, singleColor = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.sum_not_yet), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { comparing = true }) { Text(stringResource(R.string.cmp_open)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.sum_back)) }
        }
    }
}

/** Calendar dialog: only the days with recorded data can be chosen, the others are greyed out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayPickerDialog(
    days: Set<LocalDate>,
    selected: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val years = days.map { it.year }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        yearRange = (years.minOrNull() ?: LocalDate.now().year)..(years.maxOrNull() ?: LocalDate.now().year),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = pickerDate(utcTimeMillis) in days
            override fun isSelectableYear(year: Int) = years.contains(year)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = { state.selectedDateMillis?.let { onPick(pickerDate(it)) } }) {
                Text(stringResource(R.string.sum_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sum_cancel)) } },
    ) {
        DatePicker(state = state, title = title?.let { t -> { Text(t, modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp)) } })
    }
}

/** Width left of the bars for the value labels. */
private val GUTTER = 36.dp

/**
 * The frame every chart shares: a grid with the values at round numbers ([niceTicks] between [lo] and [hi]), the labels in a
 * gutter on the left, and the hour labels below. [bars] draws the bars of one chart; it gets the x of the plot's left edge, the
 * width of one hour's slot, a function from a value to its y on the canvas, and the start of the first hour.
 */
@Composable
internal fun HourChart(
    hours: List<HourBucket>,
    lo: Double,
    hi: Double,
    height: Dp,
    bars: DrawScope.(plotLeft: Float, slot: Float, y: (Double) -> Float, firstHourMs: Long) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val axisColor = MaterialTheme.colorScheme.onSurface
    val ticks = niceTicks(lo, hi)
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        if (hours.isEmpty()) return@Canvas
        val left = GUTTER.toPx()
        val first0 = hours.first().startUtcMs
        val span = ((hours.last().startUtcMs - first0) / 3_600_000L).toInt() + 1 // hours from the first to the last, gaps included
        val slot = (size.width - left) / span
        val y = { v: Double -> size.height - ((v - lo) / (hi - lo)).toFloat() * size.height }
        for (t in ticks) {
            val ty = y(t)
            drawLine(gridColor, Offset(left, ty), Offset(size.width, ty), strokeWidth = 1f)
            val text = textMeasurer.measure(tickText(t), labelStyle)
            // keep the label inside the canvas: the top and bottom lines are at its edges
            val labelY = (ty - text.size.height / 2f).coerceIn(0f, size.height - text.size.height)
            drawText(text, topLeft = Offset(left - 4.dp.toPx() - text.size.width, labelY))
        }
        bars(left, slot, y, first0)
        drawLine(axisColor, Offset(left, size.height), Offset(size.width, size.height), strokeWidth = 1f)
    }
    HourLabels(hours)
}

private fun tickText(v: Double) = if (v == Math.rint(v)) String.format(Locale.ROOT, "%.0f", v) else String.format(Locale.ROOT, "%.1f", v)

/**
 * One bar per hour: [first] is drawn from the bottom, [second] stacked on top of it (S then V for the burden chart). A missing
 * hour is a gap. The scale runs from 0 to the tallest bar.
 */
@Composable
private fun HourBars(
    hours: List<HourBucket>,
    first: (HourBucket) -> Double?,
    second: (HourBucket) -> Double?,
    singleColor: Color? = null,
) {
    val firstColor = singleColor ?: MaterialTheme.colorScheme.primary
    val secondColor = MaterialTheme.colorScheme.error
    val maxValue = (hours.maxOfOrNull { (first(it) ?: 0.0) + (second(it) ?: 0.0) } ?: 0.0).coerceAtLeast(1.0)
    HourChart(hours, 0.0, maxValue, 120.dp) { left, slot, y, first0 ->
        for (h in hours) {
            val x = left + ((h.startUtcMs - first0) / 3_600_000L).toInt() * slot + slot * 0.1f
            val a = first(h) ?: 0.0
            val b = second(h) ?: 0.0
            drawRect(firstColor, topLeft = Offset(x, y(a)), size = Size(slot * 0.8f, y(0.0) - y(a)))
            drawRect(secondColor, topLeft = Offset(x, y(a + b)), size = Size(slot * 0.8f, y(a) - y(a + b)))
        }
    }
}

/**
 * Hour labels under a chart: the hour of the day in the phone time zone, under the slot of every hour that is a multiple of 3.
 * Each label sits at the x of its own slot (the same geometry as [HourChart]), so a gap in the data does not shift the labels.
 */
@Composable
private fun HourLabels(hours: List<HourBucket>) {
    if (hours.isEmpty()) return
    val zone = ZoneId.systemDefault()
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    Canvas(modifier = Modifier.fillMaxWidth().height(16.dp)) {
        val left = GUTTER.toPx()
        val first0 = hours.first().startUtcMs
        val span = ((hours.last().startUtcMs - first0) / 3_600_000L).toInt() + 1
        val slot = (size.width - left) / span
        for (k in 0 until span) {
            val hour = Instant.ofEpochMilli(first0 + k * 3_600_000L).atZone(zone).hour
            if (hour % 3 != 0) continue
            val text = textMeasurer.measure(String.format(Locale.ROOT, "%02d", hour), style)
            val x = (left + (k + 0.5f) * slot - text.size.width / 2f).coerceIn(0f, size.width - text.size.width)
            drawText(text, topLeft = Offset(x, 0f))
        }
    }
}

/**
 * Heart rate per hour as a candlestick: the body runs from the hour's lowest to its highest per-minute average, the thin line
 * (wick) from its lowest to its highest single interval, and a solid tick marks the mean. The scale covers the wicks, rounded to
 * 10 bpm. An hour without HR is a gap.
 */
@Composable
private fun HrBars(hours: List<HourBucket>) {
    val color = MaterialTheme.colorScheme.primary
    val wickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val withHr = hours.filter {
        it.hrMeanBpm != null && it.hrMinBpm != null && it.hrMaxBpm != null && it.hrMinuteLowBpm != null && it.hrMinuteHighBpm != null
    }
    if (withHr.isEmpty()) return
    val lo = floor(withHr.minOf { it.hrMinBpm!! } / 10) * 10
    val hi = (ceil(withHr.maxOf { it.hrMaxBpm!! } / 10) * 10).coerceAtLeast(lo + 10)
    HourChart(hours, lo, hi, 140.dp) { left, slot, y, first0 ->
        for (h in withHr) {
            val x = left + ((h.startUtcMs - first0) / 3_600_000L).toInt() * slot + slot * 0.1f
            val centre = x + slot * 0.4f
            drawLine(wickColor, Offset(centre, y(h.hrMaxBpm!!)), Offset(centre, y(h.hrMinBpm!!)), strokeWidth = 1.5.dp.toPx())
            val top = y(h.hrMinuteHighBpm!!)
            drawRect(color.copy(alpha = 0.45f), topLeft = Offset(x, top), size = Size(slot * 0.8f, (y(h.hrMinuteLowBpm!!) - top).coerceAtLeast(2f)))
            drawRect(color, topLeft = Offset(x, y(h.hrMeanBpm!!) - 1.5.dp.toPx()), size = Size(slot * 0.8f, 3.dp.toPx()))
        }
    }
    Text(stringResource(R.string.sum_hr_legend), style = MaterialTheme.typography.labelSmall)
}
