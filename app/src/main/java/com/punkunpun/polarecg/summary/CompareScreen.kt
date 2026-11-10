package com.punkunpun.polarecg.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.punkunpun.polarecg.PolarApp
import com.punkunpun.polarecg.R
import com.punkunpun.polarecg.dsp.summary.HourBucket
import com.punkunpun.polarecg.dsp.summary.RangeSummary
import com.punkunpun.polarecg.dsp.summary.shiftedBy
import com.punkunpun.polarecg.events.localTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HOUR_MS = 3_600_000L

/** What is compared with what. */
enum class CompareMode {
    /** Today so far against yesterday up to the same time of day. */
    TODAY_YESTERDAY,

    /** The last 24 hours against the 24 hours before them. */
    LAST_24H,

    /** Two days picked on the calendar. */
    DAYS,
}

/** The two periods of a comparison, each [from, to). [previous] is shifted back by one day (or 24 hours) from [current]. */
class CompareBounds(val current: Pair<Long, Long>, val previous: Pair<Long, Long>)

/**
 * The periods to compare at [nowMs]. For today the earlier period ends at the same time of day, so the two are the same length
 * (otherwise the evening would always look quieter than a whole day). Days are local calendar days of [zone].
 */
fun compareBounds(mode: CompareMode, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): CompareBounds = when (mode) {
    CompareMode.LAST_24H -> CompareBounds((nowMs - 24 * HOUR_MS) to nowMs, (nowMs - 48 * HOUR_MS) to (nowMs - 24 * HOUR_MS))
    CompareMode.TODAY_YESTERDAY, CompareMode.DAYS -> { // DAYS has its own bounds ([compareDays]); without two days it shows today and yesterday
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        CompareBounds(start to nowMs, yesterday to (yesterday + (nowMs - start)))
    }
}

/**
 * Two picked days: [first] is the "now" column, [second] the "before" one (the two can be in any order). When one of them is today
 * (still running), both are cut to the same length, so the evening of today is not set against a whole day.
 */
fun compareDays(first: LocalDate, second: LocalDate, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): CompareBounds {
    val a = dayBounds(first, nowMs, zone)
    val b = dayBounds(second, nowMs, zone)
    val running = first.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() > nowMs ||
        second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() > nowMs
    if (!running) return CompareBounds(a, b)
    val length = minOf(a.second - a.first, b.second - b.first)
    return CompareBounds(a.first to a.first + length, b.first to b.first + length)
}

/**
 * The summary of two periods side by side (a table with the change) and their hours laid over each other: the heart rate and the
 * S+V burden per hour, the current period solid, the earlier one pale.
 */
@Composable
fun CompareScreen(app: PolarApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    var mode by rememberSaveable { mutableStateOf(CompareMode.TODAY_YESTERDAY) }
    var firstDay by rememberSaveable { mutableStateOf<Long?>(null) }   // epoch days of the two picked days
    var secondDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickStep by rememberSaveable { mutableStateOf(0) }             // 0 = no dialog, 1 = picking the first day, 2 = the second
    var daysWithData by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var bounds by remember { mutableStateOf<CompareBounds?>(null) }
    var current by remember { mutableStateOf<RangeSummary?>(null) }
    var previous by remember { mutableStateOf<RangeSummary?>(null) }
    val dao = app.database.summaryDao()
    val rows by dao.observeMinuteCount().collectAsState(initial = 0)

    LaunchedEffect(rows) {
        daysWithData = withContext(Dispatchers.IO) { daysWithData(dao.quarterHoursWithData()) }
    }
    LaunchedEffect(mode, firstDay, secondDay, rows) {
        val now = System.currentTimeMillis()
        val a = firstDay
        val s = secondDay
        val b = if (mode == CompareMode.DAYS && a != null && s != null) compareDays(LocalDate.ofEpochDay(a), LocalDate.ofEpochDay(s), now)
        else compareBounds(mode, now)
        val (cur, prev) = withContext(Dispatchers.IO) {
            loadSummary(app.database, b.current.first, b.current.second) to loadSummary(app.database, b.previous.first, b.previous.second)
        }
        bounds = b
        current = cur
        previous = prev
    }

    Column(
        modifier = modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.cmp_title), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == CompareMode.TODAY_YESTERDAY, onClick = { mode = CompareMode.TODAY_YESTERDAY }, label = { Text(stringResource(R.string.cmp_today)) })
            FilterChip(selected = mode == CompareMode.LAST_24H, onClick = { mode = CompareMode.LAST_24H }, label = { Text(stringResource(R.string.cmp_24h)) })
            IconButton(onClick = { pickStep = 1 }) { Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.cmp_pick_days)) }
        }
        when (pickStep) {
            1 -> DayPickerDialog(
                days = daysWithData,
                selected = firstDay?.let(LocalDate::ofEpochDay),
                onPick = { firstDay = it.toEpochDay(); pickStep = 2 },
                onDismiss = { pickStep = 0 },
                title = stringResource(R.string.cmp_pick_first),
            )
            2 -> DayPickerDialog(
                days = daysWithData,
                selected = secondDay?.let(LocalDate::ofEpochDay),
                onPick = { secondDay = it.toEpochDay(); mode = CompareMode.DAYS; pickStep = 0 },
                onDismiss = { pickStep = 0 },
                title = stringResource(R.string.cmp_pick_second),
            )
        }
        val b = bounds
        val cur = current
        val prev = previous
        if (b != null && cur != null && prev != null) {
            Text(stringResource(R.string.cmp_now_period, localTime(b.current.first), localTime(b.current.second)))
            Text(stringResource(R.string.cmp_before_period, localTime(b.previous.first), localTime(b.previous.second)))
            if (cur.beats == 0 || prev.beats == 0) Text(stringResource(R.string.cmp_missing), color = MaterialTheme.colorScheme.error)

            val shortDay = DateTimeFormatter.ofPattern("MMM d")
            val nowLabel = if (mode == CompareMode.DAYS) firstDay?.let { LocalDate.ofEpochDay(it).format(shortDay) } else null
            val beforeLabel = if (mode == CompareMode.DAYS) secondDay?.let { LocalDate.ofEpochDay(it).format(shortDay) } else null
            CompareTable(cur, prev, nowLabel ?: stringResource(R.string.cmp_now), beforeLabel ?: stringResource(R.string.cmp_before))

            // the earlier hours moved onto the current ones: same x for the same time of day
            val shift = ((b.current.first - b.previous.first).toDouble() / HOUR_MS).roundToLong() * HOUR_MS
            val before = prev.hours.map { it.shiftedBy(shift) }
            val axis = axisHours(b.current.first, b.current.second)
            Text(stringResource(R.string.cmp_chart_hr), style = MaterialTheme.typography.titleSmall)
            LineCompare(axis, cur.hours, before, unit = 10.0, floorAtZero = false) { it.hrMeanBpm }
            Text(stringResource(R.string.cmp_chart_burden), style = MaterialTheme.typography.titleSmall)
            LineCompare(axis, cur.hours, before, unit = 1.0, floorAtZero = true) { it.burdenPercent }
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.sum_back)) }
    }
}

/** An empty hour for every hour of [fromMs, toMs): the x axis of the comparison charts (hours without data are gaps). */
private fun axisHours(fromMs: Long, toMs: Long): List<HourBucket> {
    val first = Math.floorDiv(fromMs, HOUR_MS) * HOUR_MS
    val last = Math.floorDiv(toMs - 1, HOUR_MS) * HOUR_MS
    return generateSequence(first) { it + HOUR_MS }.takeWhile { it <= last }
        .map { HourBucket(it, 0, 0, 0, 0, 0, 0.0, null) }.toList()
}

@Composable
private fun CompareTable(cur: RangeSummary, prev: RangeSummary, nowHeader: String, beforeHeader: String) {
    Column {
        TableRow(stringResource(R.string.cmp_metric), nowHeader, beforeHeader, stringResource(R.string.cmp_change), bold = true)
        TableRow(stringResource(R.string.cmp_beats), cur.beats.toString(), prev.beats.toString(), signed((cur.beats - prev.beats).toDouble(), 0))
        TableRow(stringResource(R.string.cmp_s), percent(cur.sPercent), percent(prev.sPercent), diff(cur.sPercent, prev.sPercent, 2, " pp"))
        TableRow(stringResource(R.string.cmp_v), percent(cur.vPercent), percent(prev.vPercent), diff(cur.vPercent, prev.vPercent, 2, " pp"))
        TableRow(stringResource(R.string.cmp_burden), percent(cur.burdenPercent), percent(prev.burdenPercent), diff(cur.burdenPercent, prev.burdenPercent, 2, " pp"))
        TableRow(stringResource(R.string.cmp_hr_mean), one(cur.hrMeanBpm), one(prev.hrMeanBpm), diff(cur.hrMeanBpm, prev.hrMeanBpm, 1))
        TableRow(stringResource(R.string.cmp_hr_min), one(cur.hrMinuteLowBpm), one(prev.hrMinuteLowBpm), diff(cur.hrMinuteLowBpm, prev.hrMinuteLowBpm, 1))
        TableRow(stringResource(R.string.cmp_hr_max), one(cur.hrMinuteHighBpm), one(prev.hrMinuteHighBpm), diff(cur.hrMinuteHighBpm, prev.hrMinuteHighBpm, 1))
        TableRow(stringResource(R.string.cmp_rmssd), one(cur.rmssdMedianMs), one(prev.rmssdMedianMs), diff(cur.rmssdMedianMs, prev.rmssdMedianMs, 1))
        TableRow(stringResource(R.string.cmp_sdnn), one(cur.sdnnMedianMs), one(prev.sdnnMedianMs), diff(cur.sdnnMedianMs, prev.sdnnMedianMs, 1))
        TableRow(stringResource(R.string.cmp_coverage), one(100 * cur.coverage), one(100 * prev.coverage), diff(100 * cur.coverage, 100 * prev.coverage, 1, " pp"))
    }
}

/** The colours of the two periods: the lines of the charts and the backgrounds of the table's columns, so the table is the legend. */
@Composable
private fun nowColor() = MaterialTheme.colorScheme.primary

@Composable
private fun beforeColor() = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)

@Composable
private fun TableRow(label: String, now: String, before: String, change: String, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    val style = MaterialTheme.typography.bodyMedium
    // no gaps between the rows, so the background of a column is one band
    val cell = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(2.2f).then(cell), fontWeight = weight, style = style)
        Text(now, modifier = Modifier.weight(1.2f).background(nowColor().copy(alpha = 0.25f)).then(cell), fontWeight = weight, style = style)
        Text(before, modifier = Modifier.weight(1.2f).background(beforeColor().copy(alpha = 0.25f)).then(cell), fontWeight = weight, style = style)
        Text(change, modifier = Modifier.weight(1.4f).then(cell), fontWeight = weight, style = style)
    }
}

private fun signed(v: Double, decimals: Int, unit: String = ""): String =
    (if (v > 0) "+" else "") + String.format(Locale.ROOT, "%.${decimals}f", v) + unit

/** The change from [before] to [now]; "-" if one of them is missing. */
private fun diff(now: Double?, before: Double?, decimals: Int, unit: String = ""): String =
    if (now == null || before == null) "-" else signed(now - before, decimals, unit)

/**
 * One value per hour as two lines over the same hours: [now] solid, [before] (already moved onto the current hours) pale. Only
 * neighbouring hours are joined, a missing hour is a gap. The scale covers both lines, rounded to [unit] (and starts at 0 if
 * [floorAtZero]).
 */
@Composable
private fun LineCompare(
    axis: List<HourBucket>,
    now: List<HourBucket>,
    before: List<HourBucket>,
    unit: Double,
    floorAtZero: Boolean,
    value: (HourBucket) -> Double?,
) {
    val nowColor = nowColor()
    val beforeColor = beforeColor()
    val nowPoints = now.mapNotNull { h -> value(h)?.let { h.startUtcMs to it } }
    val beforePoints = before.mapNotNull { h -> value(h)?.let { h.startUtcMs to it } }
    val all = (nowPoints + beforePoints).map { it.second }
    if (all.isEmpty()) return
    val lo = if (floorAtZero) 0.0 else floor(all.min() / unit) * unit
    val hi = (ceil(all.max() / unit) * unit).coerceAtLeast(lo + unit)
    HourChart(axis, lo, hi, 140.dp) { left, slot, y, first0 ->
        fun drawSeries(points: List<Pair<Long, Double>>, color: Color, width: Float) {
            var last: Offset? = null
            var lastIndex = Int.MIN_VALUE
            for ((t, v) in points.sortedBy { it.first }) {
                val index = ((t - first0) / HOUR_MS).toInt()
                if (index < 0 || index >= axis.size) continue
                val p = Offset(left + index * slot + slot / 2, y(v))
                if (last != null && index == lastIndex + 1) drawLine(color, last, p, strokeWidth = width)
                drawCircle(color, radius = width * 1.4f, center = p)
                last = p
                lastIndex = index
            }
        }
        drawSeries(beforePoints, beforeColor, 2.dp.toPx())
        drawSeries(nowPoints, nowColor, 2.dp.toPx())
    }
}
