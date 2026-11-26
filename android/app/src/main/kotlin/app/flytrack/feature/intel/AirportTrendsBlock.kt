package app.flytrack.feature.intel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.model.DELAY_THRESHOLD_MINUTES
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.flights.ui.minutesAmountText
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.theme.FlyTrackTheme
import kotlinx.datetime.LocalDate

/**
 * "Show delay trends" under one airport of the Airport Health block (B7, Pro): average departure delay by
 * local hour and by day over the last two weeks, drawn with Compose Canvas. Loaded only when opened.
 */
@Composable
fun AirportTrendsBlock(iata: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(iata) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(if (open) R.string.intel_trends_hide else R.string.intel_trends_show))
        }
        if (open) {
            val viewModel = hiltViewModel<AirportTrendsViewModel>(key = "trends:$iata")
            LaunchedEffect(iata) { viewModel.load(iata) }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            TrendsContent(state, onRetry = { viewModel.load(iata) })
        }
    }
}

@Composable
fun TrendsContent(state: TrendsUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        when (state) {
            TrendsUiState.Idle, TrendsUiState.Loading -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), strokeWidth = 2.dp)
            TrendsUiState.Empty -> Note(stringResource(R.string.intel_trends_empty))
            TrendsUiState.Locked -> ProFeatureTeaser(
                title = stringResource(R.string.intel_trends_teaser_title),
                body = stringResource(R.string.intel_trends_teaser_body),
            )
            TrendsUiState.Offline -> Retry(stringResource(R.string.intel_trends_offline), onRetry)
            TrendsUiState.Failed -> Retry(stringResource(R.string.intel_trends_failed), onRetry)
            is TrendsUiState.Ready -> TrendsChart(state.trends)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Retry(text: String, onRetry: () -> Unit) {
    Note(text)
    TextButton(onClick = onRetry) { Text(stringResource(R.string.intel_trends_retry)) }
}

@Composable
fun TrendsChart(trends: TrendsUiModel) {
    val onTime = FlyTrackTheme.statusColors.onTime.content
    val delayed = FlyTrackTheme.statusColors.delayed.content
    val empty = MaterialTheme.colorScheme.outlineVariant
    val hourLabel = { hour: Int -> String.format(java.util.Locale.ROOT, "%02d:00", hour) }

    Text(stringResource(R.string.intel_trends_title, trends.days), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    val hoursSummary = listOfNotNull(
        trends.worstHour?.let { stringResource(R.string.intel_trends_worst, hourLabel(it.hour), minutesAmountText(it.avgDepDelay.coerceAtLeast(0))) },
        trends.bestHour?.let {
            if (it.avgDepDelay <= 0) {
                stringResource(R.string.intel_trends_best_on_time, hourLabel(it.hour))
            } else {
                stringResource(R.string.intel_trends_best, hourLabel(it.hour), minutesAmountText(it.avgDepDelay))
            }
        },
    )
    val hoursDescription = (listOf(stringResource(R.string.intel_trends_cd_hours)) + hoursSummary).joinToString(" ")
    Bars(
        values = trends.hours.map { it?.avgDepDelay },
        max = trends.maxHourDelay,
        colorFor = { v -> if (v == null) empty else if (v >= DELAY_THRESHOLD_MINUTES) delayed else onTime },
        description = hoursDescription,
    )
    AxisLabels(listOf(hourLabel(0), hourLabel(6), hourLabel(12), hourLabel(18), hourLabel(23)))
    hoursSummary.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(
        stringResource(R.string.intel_trends_local_time, trends.iata),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (trends.byDay.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.intel_trends_by_day), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Bars(
            values = trends.byDay.map { it.avgDepDelay },
            max = trends.maxDayDelay,
            colorFor = { v -> if (v == null) empty else if (v >= DELAY_THRESHOLD_MINUTES) delayed else onTime },
            description = stringResource(R.string.intel_trends_cd_days, trends.byDay.size),
            height = 56,
        )
        AxisLabels(listOf(dayLabel(trends.byDay.first().date), dayLabel(trends.byDay.last().date)))
        if (trends.totalCancelled > 0) {
            Text(
                pluralStringResource(R.plurals.intel_trends_cancelled, trends.totalCancelled, trends.totalCancelled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dayLabel(date: LocalDate): String = FlightTimeFormat.dayMonth(date)

/** Evenly spaced bars; a missing value draws a short hairline stub so the gap reads as "no flights". */
@Composable
private fun Bars(values: List<Int?>, max: Int, colorFor: (Int?) -> Color, description: String, height: Int = 88) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (values.isEmpty()) return@Canvas
        val slot = size.width / values.size
        val barWidth = (slot * 0.7f).coerceAtLeast(1f)
        val radius = CornerRadius(barWidth / 4, barWidth / 4)
        values.forEachIndexed { index, value ->
            val left = index * slot + (slot - barWidth) / 2
            val fraction = barFraction(value, max)
            val barHeight = if (value == null) 2.dp.toPx() else (size.height * fraction).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = colorFor(value),
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun AxisLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp).clearAndSetSemantics { }) {
        labels.forEachIndexed { index, label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = when (index) {
                    0 -> TextAlign.Start
                    labels.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TrendsChartPreview() {
    val trends = AirportTrends(
        iata = "DEL",
        days = 14,
        byHour = (5..23).map { HourTrend(it, avgDepDelay = (it * 7) % 40, delayedPct = (it * 5) % 60, samples = 20) },
        byDay = (1..14).map { DayTrend(LocalDate(2026, 11, it + 10), avgDepDelay = (it * 9) % 35, cancelled = it % 5 / 4, samples = 120) },
    ).toUi()!!
    FlyTrackTheme { Column(Modifier.padding(16.dp)) { TrendsChart(trends) } }
}
