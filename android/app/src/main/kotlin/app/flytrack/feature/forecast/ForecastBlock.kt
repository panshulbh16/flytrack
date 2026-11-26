package app.flytrack.feature.forecast

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.NumeralTimeText
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.theme.FlyTrackTheme
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * "Arrival forecast" on the flight detail screen: the likely arrival and the flight's on-time record for
 * Pro, the teaser for free users. Draws its own hairline above it, and nothing when there is nothing to show.
 */
@Composable
fun ForecastBlock(flightId: String, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ForecastViewModel, ForecastViewModel.Factory>(key = "forecast:$flightId") { it.create(flightId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ForecastContent(state, modifier)
}

@Composable
fun ForecastContent(state: ForecastUiState, modifier: Modifier = Modifier) {
    if (state == ForecastUiState.Hidden) return
    Column(modifier) {
        Spacer(Modifier.height(20.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
        SectionCaption(stringResource(R.string.forecast_title))
        Spacer(Modifier.height(12.dp))
        when (state) {
            ForecastUiState.Hidden -> Unit
            ForecastUiState.Locked -> ProFeatureTeaser(
                title = stringResource(R.string.forecast_teaser_title),
                body = stringResource(R.string.forecast_teaser_body),
            )
            is ForecastUiState.Ready -> ReadyContent(state)
        }
    }
}

@Composable
private fun ReadyContent(state: ForecastUiState.Ready) {
    state.forecast?.let { forecast ->
        val p50 = FlightTimeFormat.time(forecast.p50, state.arrTz)
        Text(stringResource(R.string.forecast_likely_arrival), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            NumeralTimeText(time = p50, contentDescription = stringResource(R.string.forecast_cd_likely_arrival, p50))
        }
        arrivalVsSchedule(forecast.p50, state.scheduledArrival)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        forecast.p80?.let { p80 ->
            Text(
                stringResource(R.string.forecast_p80, FlightTimeFormat.time(p80, state.arrTz)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            basisText(forecast, state.history),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.history?.let { history ->
        Spacer(Modifier.height(12.dp))
        Text(
            pluralStringResource(R.plurals.forecast_history_title, history.windowDays, history.windowDays),
            style = MaterialTheme.typography.titleSmall,
        )
        history.onTimePct?.let { Row2(stringResource(R.string.forecast_on_time), stringResource(R.string.forecast_percent, it)) }
        history.avgDepDelayMin?.let { Row2(stringResource(R.string.forecast_avg_dep_delay), delayText(it)) }
        history.medianArrDelayMin?.let { Row2(stringResource(R.string.forecast_median_arr_delay), delayText(it)) }
        Row2(
            stringResource(R.string.forecast_cancelled),
            stringResource(R.string.forecast_cancelled_value, history.cancelled, history.samples),
        )
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "12 min late", "3 min early", "on time". */
@Composable
private fun delayText(minutes: Int): String = when {
    minutes > 0 -> pluralStringResource(R.plurals.forecast_minutes_late, minutes, minutes)
    minutes < 0 -> pluralStringResource(R.plurals.forecast_minutes_early, -minutes, -minutes)
    else -> stringResource(R.string.forecast_on_schedule)
}

/** "6 min after schedule" etc.; null without a scheduled arrival. */
@Composable
private fun arrivalVsSchedule(p50: Instant, scheduled: Instant?): String? {
    scheduled ?: return null
    val minutes = ((p50 - scheduled).inWholeSeconds / 60.0).roundToInt()
    return when {
        minutes > 0 -> pluralStringResource(R.plurals.forecast_after_schedule, minutes, minutes)
        minutes < 0 -> pluralStringResource(R.plurals.forecast_before_schedule, -minutes, -minutes)
        else -> stringResource(R.string.forecast_at_schedule)
    }
}

@Composable
private fun basisText(forecast: ArrivalForecast, history: ForecastHistory?): String {
    val confidence = stringResource(
        when (forecast.confidence) {
            ForecastConfidence.HIGH -> R.string.forecast_confidence_high
            ForecastConfidence.MEDIUM -> R.string.forecast_confidence_medium
            ForecastConfidence.LOW -> R.string.forecast_confidence_low
        },
    )
    val basis = if (forecast.method == ForecastMethod.HISTORY && history != null) {
        pluralStringResource(R.plurals.forecast_basis_history, history.samples, history.samples, history.windowDays)
    } else {
        stringResource(R.string.forecast_basis_schedule)
    }
    return stringResource(R.string.forecast_basis, confidence, basis)
}

@Preview(showBackground = true)
@Composable
private fun ForecastPreview() {
    FlyTrackTheme {
        Column(Modifier.padding(16.dp)) {
            ForecastContent(
                ForecastUiState.Ready(
                    history = ForecastHistory(23, 60, 61, 14, 6, 1),
                    forecast = ArrivalForecast(
                        Instant.parse("2026-11-25T15:16:00Z"),
                        Instant.parse("2026-11-25T15:31:00Z"),
                        ForecastMethod.HISTORY,
                        ForecastConfidence.HIGH,
                    ),
                    arrTz = "Asia/Kolkata",
                    scheduledArrival = Instant.parse("2026-11-25T15:10:00Z"),
                ),
            )
            ForecastContent(ForecastUiState.Locked)
        }
    }
}
