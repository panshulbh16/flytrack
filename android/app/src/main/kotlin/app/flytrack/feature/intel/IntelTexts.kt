package app.flytrack.feature.intel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.feature.flights.ui.minutesAmountText
import app.flytrack.ui.components.StatusPill
import kotlin.math.abs

/** Name of a timeline milestone. */
@Composable
@ReadOnlyComposable
fun timelineLabel(key: TimelineKey): String = stringResource(
    when (key) {
        TimelineKey.SCHEDULED -> R.string.intel_timeline_scheduled
        TimelineKey.CHECKIN_OPEN -> R.string.intel_timeline_checkin_open
        TimelineKey.GATE_ASSIGNED -> R.string.intel_timeline_gate_assigned
        TimelineKey.BOARDING -> R.string.intel_timeline_boarding
        TimelineKey.GATE_CLOSED -> R.string.intel_timeline_gate_closed
        TimelineKey.PUSHBACK -> R.string.intel_timeline_pushback
        TimelineKey.TAKEOFF -> R.string.intel_timeline_takeoff
        TimelineKey.DIVERTED -> R.string.intel_timeline_diverted
        TimelineKey.LANDING -> R.string.intel_timeline_landing
        TimelineKey.AT_GATE -> R.string.intel_timeline_at_gate
        TimelineKey.BELT -> R.string.intel_timeline_belt
        TimelineKey.CANCELLED -> R.string.intel_timeline_cancelled
    },
)

/** One piece of a milestone's Pro detail. */
@Composable
fun timelineFactText(fact: TimelineFact): String = when (fact) {
    is TimelineFact.Gate -> fact.terminal?.let {
        "${stringResource(R.string.intel_fact_gate, fact.gate)} · ${stringResource(R.string.intel_fact_terminal, it)}"
    } ?: stringResource(R.string.intel_fact_gate, fact.gate)
    is TimelineFact.Terminal -> stringResource(R.string.intel_fact_terminal, fact.terminal)
    is TimelineFact.Delay -> when {
        fact.minutes > 0 -> stringResource(R.string.intel_fact_late, minutesAmountText(fact.minutes))
        fact.minutes < 0 -> stringResource(R.string.intel_fact_early, minutesAmountText(abs(fact.minutes)))
        else -> stringResource(R.string.intel_fact_on_time)
    }
    is TimelineFact.TaxiOut -> stringResource(R.string.intel_fact_taxi_out, minutesAmountText(fact.minutes))
    is TimelineFact.TaxiIn -> stringResource(R.string.intel_fact_taxi_in, minutesAmountText(fact.minutes))
    is TimelineFact.Belt -> stringResource(R.string.intel_fact_belt, fact.belt)
    is TimelineFact.CheckinCloses -> stringResource(R.string.intel_fact_checkin_closes, fact.time)
    is TimelineFact.Timetable -> when {
        fact.departure != null && fact.arrival != null -> stringResource(R.string.intel_fact_timetable, fact.departure, fact.arrival)
        fact.departure != null -> stringResource(R.string.intel_fact_departs, fact.departure)
        else -> stringResource(R.string.intel_fact_arrives, fact.arrival.orEmpty())
    }
    is TimelineFact.Diverted -> when {
        fact.from != null && fact.to != null -> stringResource(R.string.intel_fact_diverted, fact.from, fact.to)
        fact.to != null -> stringResource(R.string.intel_fact_diverted_to, fact.to)
        else -> stringResource(R.string.intel_fact_diverted_from, fact.from.orEmpty())
    }
}

/** The risk word that always goes with the risk colour. */
@Composable
@ReadOnlyComposable
fun riskText(risk: ConnectionRisk): String = stringResource(
    when (risk) {
        ConnectionRisk.OK -> R.string.intel_risk_ok
        ConnectionRisk.TIGHT -> R.string.intel_risk_tight
        ConnectionRisk.AT_RISK -> R.string.intel_risk_at_risk
        ConnectionRisk.MISSED -> R.string.intel_risk_missed
    },
)

/** How a connection row is worded: on the flights list both flights, on a detail screen the other one. */
enum class ConnectionPerspective { TRIP, ONWARD, ARRIVING }

/** One connection: flights, layover against the minimum connection time, risk pill, terminal change. */
@Composable
fun ConnectionRow(
    connection: ConnectionUiModel,
    perspective: ConnectionPerspective,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(R.string.intel_connection_flight_unknown)
    val title = when (perspective) {
        ConnectionPerspective.TRIP -> stringResource(
            R.string.intel_connection_route,
            connection.inFlight ?: unknown,
            connection.outFlight ?: unknown,
            connection.airport,
        )
        ConnectionPerspective.ONWARD -> stringResource(R.string.intel_connection_to, connection.outFlight ?: unknown)
        ConnectionPerspective.ARRIVING -> stringResource(R.string.intel_connection_from, connection.inFlight ?: unknown)
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                StatusPill(text = riskText(connection.risk), tone = connection.risk.tone)
            }
            Spacer(Modifier.height(6.dp))
            val times = when {
                connection.arrival != null && connection.departure != null ->
                    stringResource(R.string.intel_connection_times, connection.arrival, connection.departure)
                connection.departure != null -> stringResource(R.string.intel_connection_departs, connection.departure)
                else -> null
            }
            times?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                listOf(
                    stringResource(R.string.intel_connection_layover, minutesAmountText(connection.layoverMin.coerceAtLeast(0)), minutesAmountText(connection.mctMin)),
                    bufferText(connection.bufferMin),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            connection.terminalChange?.let {
                Text(stringResource(R.string.intel_connection_terminal, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            connection.transferNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun bufferText(bufferMin: Int): String = when {
    bufferMin > 0 -> stringResource(R.string.intel_connection_spare, minutesAmountText(bufferMin))
    bufferMin < 0 -> stringResource(R.string.intel_connection_short, minutesAmountText(-bufferMin))
    else -> stringResource(R.string.intel_connection_no_spare)
}
