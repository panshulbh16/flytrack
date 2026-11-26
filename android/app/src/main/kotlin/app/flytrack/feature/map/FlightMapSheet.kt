package app.flytrack.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.estimate.PositionSource
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.time.relativeTime
import app.flytrack.feature.flights.ui.FlightProgressLine
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.StatusPill
import app.flytrack.ui.components.durationText
import app.flytrack.ui.components.relativeTimeText
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Entry point from the flight detail screen: a "Show on map" button that opens the map sheet.
 * Self-contained (it owns the open/closed state), so the detail screen only places it.
 */
@Composable
fun FlightMapButton(flightId: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(flightId) { mutableStateOf(false) }
    Column(modifier) {
        SecondaryButton(
            text = stringResource(R.string.map_action_show),
            onClick = { open = true },
            icon = painterResource(R.drawable.ic_map_route),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.map_entry_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) FlightMapSheet(flightId = flightId, onDismiss = { open = false })
}

/** The map, full screen above the detail screen. */
@Composable
fun FlightMapSheet(flightId: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val viewModel = hiltViewModel<FlightMapViewModel, FlightMapViewModel.Factory>(key = "map-$flightId") { factory ->
            factory.create(flightId)
        }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        FlightMapScreen(state = state, onClose = onDismiss)
    }
}

/** Stateless map screen. */
@Composable
fun FlightMapScreen(state: FlightMapUiState, onClose: () -> Unit) {
    val ready = state as? FlightMapUiState.Ready
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                FlyTrackTopBar(
                    title = ready?.model?.let { stringResource(R.string.map_title_route, it.depCode, it.arrCode) }.orEmpty(),
                    onBack = onClose,
                )
            },
        ) { padding ->
            val content = Modifier.fillMaxSize().padding(padding)
            when (state) {
                FlightMapUiState.Loading -> LoadingState(modifier = content)
                FlightMapUiState.Unavailable -> ErrorState(
                    title = stringResource(R.string.map_unavailable_title),
                    message = stringResource(R.string.map_unavailable_message),
                    modifier = content,
                )
                is FlightMapUiState.Ready -> Column(content) {
                    val model = state.model
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        FlightMapView(
                            model = model,
                            dark = MaterialTheme.colorScheme.background.luminance() < 0.5f,
                            contentDescription = stringResource(R.string.map_cd_map, model.depCity, model.arrCity),
                            modifier = Modifier.fillMaxSize(),
                        )
                        model.source?.let { source ->
                            PositionSourceChip(source, Modifier.align(Alignment.TopStart).padding(16.dp))
                        }
                    }
                    HairlineDivider()
                    MapInfoPanel(model)
                }
            }
        }
    }
}

/** "Live" (terracotta) or "Estimated" (outline, muted): docs/P2_CONTRACTS.md section 5. */
@Composable
fun PositionSourceChip(source: PositionSource, modifier: Modifier = Modifier) {
    val spoken = stringResource(
        if (source == PositionSource.LIVE) R.string.map_cd_source_live else R.string.map_cd_source_estimated,
    )
    StatusPill(
        text = stringResource(if (source == PositionSource.LIVE) R.string.map_source_live else R.string.map_source_estimated),
        tone = if (source == PositionSource.LIVE) FlightStatusTone.ACTIVE else FlightStatusTone.ESTIMATED,
        modifier = modifier.semantics { contentDescription = spoken },
    )
}

@Composable
private fun MapInfoPanel(model: FlightMapUiModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = model.designator,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (model.state) {
            MapFlightState.AIRBORNE -> AirborneInfo(model)
            MapFlightState.NOT_DEPARTED -> InfoLine(stringResource(R.string.map_state_not_departed))
            MapFlightState.ARRIVED -> InfoLine(stringResource(R.string.map_state_arrived))
            MapFlightState.CANCELLED -> InfoLine(stringResource(R.string.map_state_cancelled))
            MapFlightState.POSITION_UNKNOWN -> InfoLine(stringResource(R.string.map_state_position_unknown))
        }
    }
}

@Composable
private fun AirborneInfo(model: FlightMapUiModel) {
    val progress = model.progress ?: return
    FlightProgressLine(progress = progress.toFloat(), depCode = model.depCode, arrCode = model.arrCode)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.map_progress, (progress.coerceIn(0.0, 1.0) * 100).roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        model.remaining?.let { remaining ->
            Text(
                text = stringResource(R.string.map_remaining, durationText(remaining)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val seen = model.liveObservedAt
    when {
        model.source == PositionSource.LIVE && seen != null ->
            InfoLine(stringResource(R.string.map_live_seen, relativeTimeText(relativeTime(seen, model.asOf))))
        model.source == PositionSource.ESTIMATED -> InfoLine(stringResource(R.string.map_estimated_note))
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Under the progress line of the flight detail screen: the "Live" / "Estimated" chip and the time
 * left until [arrival], both from the same fix as the progress (core/estimate).
 */
@Composable
fun ProgressSourceRow(source: PositionSource, arrival: Instant?, now: Instant, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PositionSourceChip(source)
        arrival?.takeIf { it > now }?.let { touchdown ->
            Text(
                text = stringResource(R.string.map_remaining, durationText(touchdown - now)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
