package app.flytrack.feature.intel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.flights.ui.minutesAmountText
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.StatusPill
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.theme.FlyTrackTheme
import java.text.NumberFormat
import kotlin.math.abs

// The P3 intelligence blocks of the flight detail screen. Each one draws its own hairline above it and
// nothing at all when it has nothing to show, like the forecast and airport-health blocks. They share one
// FlightIntelViewModel per flight.

@Composable
private fun intelViewModel(flightId: String): FlightIntelViewModel =
    hiltViewModel<FlightIntelViewModel, FlightIntelViewModel.Factory>(key = "intel:$flightId") { it.create(flightId) }

@Composable
private fun intelState(flightId: String): FlightIntelUiState {
    val state by intelViewModel(flightId).uiState.collectAsStateWithLifecycle()
    return state
}

/** Asks every intelligence block of [flightId] again (the detail screen's pull-to-refresh). */
@Composable
fun rememberFlightIntelRefresh(flightId: String): () -> Unit {
    val viewModel = intelViewModel(flightId)
    return remember(viewModel) { { viewModel.refresh() } }
}

@Composable
private fun BlockFrame(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Spacer(Modifier.height(20.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
        SectionCaption(title)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ---- A2 timeline ------------------------------------------------------------------------------------

/** "Timeline": every milestone from the timetable to the baggage belt (A2). */
@Composable
fun FlightTimelineBlock(flightId: String, modifier: Modifier = Modifier) {
    intelState(flightId).timeline?.let { TimelineContent(it, modifier) }
}

@Composable
fun TimelineContent(timeline: TimelineUiModel, modifier: Modifier = Modifier) {
    BlockFrame(stringResource(R.string.intel_timeline_title), modifier) {
        timeline.rows.forEachIndexed { index, row ->
            TimelineRow(row, isFirst = index == 0, isLast = index == timeline.rows.lastIndex, nextState = timeline.rows.getOrNull(index + 1)?.state)
        }
        timeline.blockMinutes?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.intel_timeline_block, minutesAmountText(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!timeline.hasProDetail && !LocalUpsellsHidden.current) {
            // Free users get the milestones; one quiet line says what Pro adds.
            val launcher = LocalPaywallLauncher.current
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.intel_timeline_pro_hint),
                style = MaterialTheme.typography.bodySmall,
                color = FlyTrackTheme.accentText,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = launcher::openPaywall)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TimelineRow(row: TimelineRowUiModel, isFirst: Boolean, isLast: Boolean, nextState: TimelineState?) {
    val reached = row.state != TimelineState.NEXT
    val done = MaterialTheme.colorScheme.primary
    val current = MaterialTheme.colorScheme.tertiary
    val pending = MaterialTheme.colorScheme.outline
    val pendingLine = MaterialTheme.colorScheme.outlineVariant
    val dotColor = when (row.state) {
        TimelineState.DONE -> done
        TimelineState.CURRENT -> current
        TimelineState.NEXT -> pending
    }
    // The line below a dot is solid up to the latest milestone, then hairline.
    val lineBelow = if (reached && nextState != null && nextState != TimelineState.NEXT) done else pendingLine
    val lineAbove = if (reached) done else pendingLine
    val label = timelineLabel(row.key)
    val stateText = when (row.state) {
        TimelineState.DONE -> stringResource(R.string.intel_timeline_state_done)
        TimelineState.CURRENT -> stringResource(R.string.intel_timeline_state_current)
        TimelineState.NEXT -> stringResource(R.string.intel_timeline_state_next)
    }
    val facts = row.facts.map { timelineFactText(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { stateDescription = stateText },
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2
            val dotY = 12.dp.toPx()
            val radius = if (row.state == TimelineState.CURRENT) 6.dp.toPx() else 4.5.dp.toPx()
            val stroke = 2.dp.toPx()
            if (!isFirst) drawLine(lineAbove, Offset(x, 0f), Offset(x, dotY - radius), strokeWidth = stroke)
            if (!isLast) drawLine(lineBelow, Offset(x, dotY + radius), Offset(x, size.height), strokeWidth = stroke)
            if (reached) {
                drawCircle(dotColor, radius, Offset(x, dotY))
            } else {
                drawCircle(dotColor, radius, Offset(x, dotY), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.state == TimelineState.CURRENT) FontWeight.SemiBold else null,
                    color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                row.time?.let { time ->
                    Text(
                        when {
                            row.dayOffset > 0 -> "$time ${stringResource(R.string.intel_timeline_day_offset, row.dayOffset)}"
                            // check-in usually opens the day before
                            row.dayOffset < 0 -> "$time ${stringResource(R.string.intel_timeline_day_offset_before, -row.dayOffset)}"
                            else -> time
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- B1 / B2 / B3 prediction ------------------------------------------------------------------------

/** "Delay prediction" with its factors, the usual gate and the typical taxi times (Pro), or the teaser. */
@Composable
fun FlightPredictionBlock(flightId: String, modifier: Modifier = Modifier) {
    PredictionContent(intelState(flightId).prediction, modifier)
}

@Composable
fun PredictionContent(block: PredictionBlockUiModel, modifier: Modifier = Modifier) {
    when (block) {
        PredictionBlockUiModel.Hidden -> Unit
        // Crew mode: no upsell, so no block at all.
        PredictionBlockUiModel.Locked -> if (!LocalUpsellsHidden.current) BlockFrame(stringResource(R.string.intel_prediction_title), modifier) {
            ProFeatureTeaser(
                title = stringResource(R.string.intel_prediction_teaser_title),
                body = stringResource(R.string.intel_prediction_teaser_body),
            )
        }
        is PredictionBlockUiModel.Ready -> BlockFrame(stringResource(R.string.intel_prediction_title), modifier) {
            block.delay?.let { DelayPredictionCard(it) }
            block.gate?.let {
                if (block.delay != null) Spacer(Modifier.height(16.dp))
                GateLine(it)
            }
            val taxi = listOfNotNull(block.taxiOut, block.taxiIn)
            if (taxi.isNotEmpty()) {
                if (block.delay != null || block.gate != null) Spacer(Modifier.height(12.dp))
                taxi.forEach { TaxiLine(it) }
            }
        }
    }
}

@Composable
private fun DelayPredictionCard(model: DelayPredictionUiModel) {
    val headline = if (model.likelyOnTime) {
        stringResource(R.string.intel_prediction_on_time)
    } else {
        stringResource(R.string.intel_prediction_late, minutesAmountText(model.expectedDelayMin))
    }
    Text(headline, style = FlyTrackTheme.type.statusWordSmall, color = FlyTrackTheme.statusColors.forTone(model.tone).content)
    Spacer(Modifier.height(8.dp))
    ChanceRow(stringResource(R.string.intel_prediction_chance_15, model.delay15Pct), model.delay15Pct)
    ChanceRow(stringResource(R.string.intel_prediction_chance_60, model.delay60Pct), model.delay60Pct)
    if (model.factors.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.intel_prediction_why), style = MaterialTheme.typography.titleSmall)
        model.factors.forEach { FactorRow(it) }
    }
    Spacer(Modifier.height(8.dp))
    val confidence = stringResource(
        when (model.confidence) {
            PredictionConfidence.LOW -> R.string.intel_prediction_confidence_low
            PredictionConfidence.MEDIUM -> R.string.intel_prediction_confidence_medium
            PredictionConfidence.HIGH -> R.string.intel_prediction_confidence_high
        },
    )
    Text(
        model.samples?.let { stringResource(R.string.intel_prediction_confidence_samples, confidence, pluralStringResource(R.plurals.intel_prediction_samples, it, it)) }
            ?: confidence,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.intel_prediction_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A sentence over a thin bar; the sentence carries the number, the bar only repeats it. */
@Composable
private fun ChanceRow(text: String, percent: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}

@Composable
private fun FactorRow(factor: FactorUiModel) {
    val amount = minutesAmountText(abs(factor.effectMin))
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}) {
        Text(factor.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (factor.effectMin != 0) {
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (factor.effectMin > 0) R.string.intel_prediction_effect_plus else R.string.intel_prediction_effect_minus, amount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GateLine(gate: GatePredictionUiModel) {
    Text(
        if (gate.terminal != null) {
            stringResource(R.string.intel_gate_usual_terminal, gate.gate, gate.terminal, gate.count, gate.samples)
        } else {
            stringResource(R.string.intel_gate_usual, gate.gate, gate.count, gate.samples)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(stringResource(R.string.intel_gate_usual_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TaxiLine(line: TaxiLineUiModel) {
    val typical = minutesAmountText(line.typicalMin)
    val sentence = stringResource(if (line.outbound) R.string.intel_taxi_out else R.string.intel_taxi_in, line.iata, typical)
    Text(
        line.slowMin?.let { stringResource(R.string.intel_taxi_with_slow, sentence, stringResource(R.string.intel_taxi_slow, minutesAmountText(it))) } ?: sentence,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

// ---- A5 aircraft ------------------------------------------------------------------------------------

/**
 * "Aircraft": type and registration for everyone, the ICAO type when it adds something, and for Pro the
 * age and every swap before departure (A5). [model] and [reg] come from the flight row.
 */
@Composable
fun FlightAircraftBlock(flightId: String, model: String?, reg: String?, modifier: Modifier = Modifier) {
    val state = intelState(flightId)
    AircraftContent(aircraftCard(state.depTz, model, reg, state.aircraft), modifier)
}

@Composable
fun AircraftContent(card: AircraftCardUiModel, modifier: Modifier = Modifier) {
    val unknown = stringResource(R.string.flights_detail_aircraft_unknown)
    Column(modifier) {
        SectionCaption(text = stringResource(R.string.flights_detail_aircraft))
        Spacer(Modifier.height(4.dp))
        LabelValue(stringResource(R.string.flights_detail_aircraft_type), card.model ?: unknown)
        card.icaoType?.let { LabelValue(stringResource(R.string.intel_aircraft_icao), it) }
        LabelValue(stringResource(R.string.flights_detail_aircraft_reg), card.reg ?: unknown)
        card.ageYears?.let { age ->
            val years = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 }.format(age)
            LabelValue(stringResource(R.string.intel_aircraft_age), stringResource(R.string.intel_aircraft_age_value, years))
        }
        if (card.swaps.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.intel_aircraft_swaps), style = MaterialTheme.typography.titleSmall)
            card.swaps.forEach { SwapRow(it) }
        }
    }
}

@Composable
private fun SwapRow(swap: SwapUiModel) {
    val unknown = stringResource(R.string.intel_aircraft_swap_unknown)
    @Composable
    fun plane(reg: String?, model: String?): String {
        val r = reg ?: unknown
        return model?.let { stringResource(R.string.intel_aircraft_swap_with_model, r, it) } ?: r
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(stringResource(R.string.intel_aircraft_swap, plane(swap.fromReg, swap.fromModel), plane(swap.toReg, swap.toModel)), style = MaterialTheme.typography.bodyMedium)
        swap.time?.let {
            Text(stringResource(R.string.intel_aircraft_swap_at, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- B4 connections on the detail screen -------------------------------------------------------------

/** The connections this flight is part of (Pro). Nothing for free users: the flights list carries the teaser. */
@Composable
fun FlightConnectionsBlock(flightId: String, modifier: Modifier = Modifier) {
    val connections = intelState(flightId).connections
    if (connections.isEmpty()) return
    val navigator = LocalAppNavigator.current
    BlockFrame(stringResource(R.string.intel_connections_title), modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            connections.forEach { connection ->
                val onward = connection.inFlightId == flightId
                ConnectionRow(
                    connection,
                    perspective = if (onward) ConnectionPerspective.ONWARD else ConnectionPerspective.ARRIVING,
                    onClick = { navigator.openFlight(if (onward) connection.outFlightId else connection.inFlightId) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.intel_connection_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- previews ----------------------------------------------------------------------------------------

@Preview(showBackground = true)
@Composable
private fun IntelBlocksPreview() {
    FlyTrackTheme {
        Column(Modifier.padding(16.dp)) {
            TimelineContent(
                TimelineUiModel(
                    rows = listOf(
                        TimelineRowUiModel(TimelineKey.SCHEDULED, TimelineState.DONE, "18:50", 0, false, listOf(TimelineFact.Timetable("18:50", "21:05"))),
                        TimelineRowUiModel(TimelineKey.GATE_ASSIGNED, TimelineState.DONE, "17:20", 0, false, listOf(TimelineFact.Gate("42B", "1"))),
                        TimelineRowUiModel(TimelineKey.BOARDING, TimelineState.CURRENT, "18:20", 0, false, emptyList()),
                        TimelineRowUiModel(TimelineKey.PUSHBACK, TimelineState.NEXT, "19:05", 0, false, listOf(TimelineFact.Delay(15))),
                        TimelineRowUiModel(TimelineKey.LANDING, TimelineState.NEXT, "21:15", 0, false, emptyList()),
                    ),
                    blockMinutes = null,
                    hasProDetail = true,
                ),
            )
            PredictionContent(
                PredictionBlockUiModel.Ready(
                    delay = DelayPredictionUiModel(
                        17, false, app.flytrack.core.model.FlightStatusTone.DELAYED, 42, 8,
                        listOf(FactorUiModel("inbound", "Your plane is running 20 min late", 20), FactorUiModel("weather", "Fog at DEL", 15)),
                        PredictionConfidence.MEDIUM, 12,
                    ),
                    gate = GatePredictionUiModel("42B", "1", 7, 9),
                    taxiOut = TaxiLineUiModel("DEL", true, 18, 27),
                    taxiIn = null,
                ),
            )
        }
    }
}
