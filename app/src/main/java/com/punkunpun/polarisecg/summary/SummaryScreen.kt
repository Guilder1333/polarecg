package com.punkunpun.polarisecg.summary

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.punkunpun.polarisecg.PolarisApp
import com.punkunpun.polarisecg.R
import com.punkunpun.polarisecg.dsp.summary.HourBucket
import com.punkunpun.polarisecg.dsp.summary.HrvPoint
import com.punkunpun.polarisecg.dsp.summary.MinuteSummary
import com.punkunpun.polarisecg.dsp.summary.RangeSummary
import com.punkunpun.polarisecg.dsp.summary.summarizeRange
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
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

private fun percent(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f %%", it) } ?: "-"
private fun one(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

/**
 * The summary (P5.6): beats and S/V burden, burden per hour, heart rate, HRV and how much was recorded, read from the
 * long-term tables, so it also works days later and after the beats were pruned.
 */
@Composable
fun SummaryScreen(app: PolarisApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    var range by rememberSaveable { mutableStateOf(SummaryRange.LAST_24H) }
    var summary by remember { mutableStateOf<RangeSummary?>(null) }
    val dao = app.database.summaryDao()
    // A new row in the table (every minute while recording) makes the numbers load again.
    val rows by dao.observeMinuteCount().collectAsState(initial = 0)

    LaunchedEffect(range, rows) {
        val (from, to) = rangeBounds(range, System.currentTimeMillis())
        summary = withContext(Dispatchers.IO) {
            val minutes = dao.minutesBetween(from, to).map {
                MinuteSummary(it.minuteUtcMs, it.beats, it.n, it.s, it.v, it.q, it.vDoubt, it.hrMeanBpm, it.hrMinBpm, it.hrMaxBpm, it.coverage, it.motionMg)
            }
            val hrv = dao.hrvBetween(from, to).map { HrvPoint(it.startUtcMs, it.valid, it.rmssdMs, it.sdnnMs) }
            summarizeRange(minutes, hrv, from, to)
        }
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
                FilterChip(selected = range == r, onClick = { range = r }, label = { Text(stringResource(label)) })
            }
        }
        val s = summary
        if (s == null || s.beats == 0) {
            Text(stringResource(R.string.sum_empty))
        } else {
            Text(stringResource(R.string.sum_beats, s.beats, s.n, s.s, s.v, s.q))
            Text(stringResource(R.string.sum_burden, percent(s.sPercent), percent(s.vPercent), percent(s.burdenPercent)), style = MaterialTheme.typography.titleMedium)
            if (s.vDoubt > 0) Text(stringResource(R.string.sum_vdoubt, s.vDoubt), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sum_hr, one(s.hrMinBpm), one(s.hrMeanBpm), one(s.hrMaxBpm)))
            Text(stringResource(R.string.sum_hrv, one(s.rmssdMedianMs), one(s.sdnnMedianMs), s.hrvWindows))
            Text(stringResource(R.string.sum_coverage, one(100 * s.coverage)))

            Text(stringResource(R.string.sum_chart_burden), style = MaterialTheme.typography.titleSmall)
            HourBars(s.hours, { it.sPercent }, { it.vPercent })
            Text(stringResource(R.string.sum_chart_rmssd), style = MaterialTheme.typography.titleSmall)
            HourBars(s.hours, { it.rmssdMs }, { null }, singleColor = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.sum_not_yet), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.sum_back)) }
    }
}

/**
 * One bar per hour: [first] is drawn from the bottom, [second] stacked on top of it (S then V for the burden chart). A missing
 * hour is a gap. The labels are the hour of the day in the phone time zone, every third bar.
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
    val textColor = MaterialTheme.colorScheme.onSurface
    val zone = ZoneId.systemDefault()
    val maxValue = (hours.maxOfOrNull { (first(it) ?: 0.0) + (second(it) ?: 0.0) } ?: 0.0).coerceAtLeast(1.0)
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        if (hours.isEmpty()) return@Canvas
        val first0 = hours.first().startUtcMs
        val span = ((hours.last().startUtcMs - first0) / 3_600_000L).toInt() + 1 // hours from the first to the last, gaps included
        val slot = size.width / span
        val chartHeight = size.height
        for (h in hours) {
            val index = ((h.startUtcMs - first0) / 3_600_000L).toInt()
            val a = (first(h) ?: 0.0).toFloat() / maxValue.toFloat() * chartHeight
            val b = (second(h) ?: 0.0).toFloat() / maxValue.toFloat() * chartHeight
            val x = index * slot + slot * 0.1f
            drawRect(firstColor, topLeft = Offset(x, chartHeight - a), size = Size(slot * 0.8f, a))
            drawRect(secondColor, topLeft = Offset(x, chartHeight - a - b), size = Size(slot * 0.8f, b))
        }
        drawLine(textColor, Offset(0f, chartHeight), Offset(size.width, chartHeight), strokeWidth = 1f)
    }
    // hour labels as text under the canvas: the first hour, then every third one
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (h in hours.filterIndexed { i, _ -> i % 3 == 0 }) {
            Text(String.format(Locale.ROOT, "%02d", Instant.ofEpochMilli(h.startUtcMs).atZone(zone).hour), style = MaterialTheme.typography.labelSmall)
        }
    }
    Text(String.format(Locale.ROOT, "scale: up to %.1f", maxValue), style = MaterialTheme.typography.labelSmall)
}
