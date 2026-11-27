package app.flytrack.feature.radar

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.estimate.GeoPoint
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.ErrorState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.OfflineState
import app.flytrack.ui.components.updatedAgoText
import app.flytrack.ui.theme.FlyTrackTheme
import kotlinx.coroutines.delay
import java.text.NumberFormat
import kotlin.time.Clock

/**
 * Entry point of the Radar screen (route `Radar`). [iata] opens the ground radar of that airport;
 * [lat]/[lon] the proximity radar around that point (the user's flight); neither = "Planes near me".
 */
@Composable
fun RadarRoute(iata: String?, lat: Double?, lon: Double?, onBack: () -> Unit) {
    val target = remember(iata, lat, lon) {
        when {
            !iata.isNullOrBlank() -> RadarTarget.Airport(iata.uppercase())
            lat != null && lon != null -> RadarTarget.Around(GeoPoint(lat, lon))
            else -> RadarTarget.NearMe
        }
    }
    val title = when (target) {
        is RadarTarget.Airport -> stringResource(R.string.radar_title_ground, target.iata)
        is RadarTarget.Around -> stringResource(R.string.radar_title_around)
        RadarTarget.NearMe -> stringResource(R.string.radar_title_near_me)
    }
    Scaffold(topBar = { FlyTrackTopBar(title = title, onBack = onBack) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Pro only (contract section 1). The server checks too and answers pro_required.
            if (LocalProState.current.isPro) {
                RadarContent(target)
            } else {
                RadarProLocked()
            }
        }
    }
}

@Composable
private fun RadarContent(target: RadarTarget, viewModel: RadarViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current

    LaunchedEffect(target) { viewModel.start(target) }
    // Refresh only while the screen is visible.
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onLocationPermission(granted)
    }

    RadarScreen(
        state = state,
        onUseLocation = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
        onOpenAppSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onRetry = viewModel::retry,
        onSelect = viewModel::select,
    )
}

@Composable
fun RadarScreen(
    state: RadarUiState,
    onUseLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val centered = Modifier.fillMaxSize().padding(24.dp)
    when (state.phase) {
        RadarPhase.NEEDS_LOCATION -> EmptyState(
            title = stringResource(R.string.radar_location_title),
            message = stringResource(R.string.radar_location_message),
            icon = painterResource(R.drawable.ic_radar),
            actionLabel = stringResource(R.string.radar_location_action),
            onAction = onUseLocation,
            modifier = centered,
        )
        RadarPhase.LOCATION_DENIED -> EmptyState(
            title = stringResource(R.string.radar_location_denied_title),
            message = stringResource(R.string.radar_location_denied_message),
            icon = painterResource(R.drawable.ic_radar),
            actionLabel = stringResource(R.string.radar_location_open_settings),
            onAction = onOpenAppSettings,
            modifier = centered,
        )
        RadarPhase.LOCATION_OFF -> ErrorState(
            title = stringResource(R.string.radar_location_off_title),
            message = stringResource(R.string.radar_location_off_message),
            onRetry = onRetry,
            modifier = centered,
        )
        RadarPhase.LOCATION_UNAVAILABLE -> ErrorState(
            title = stringResource(R.string.radar_location_unavailable_title),
            message = stringResource(R.string.radar_location_unavailable_message),
            onRetry = onRetry,
            modifier = centered,
        )
        RadarPhase.LOCATING -> LoadingState(centered, label = stringResource(R.string.radar_locating))
        RadarPhase.LOADING -> LoadingState(centered, label = stringResource(R.string.radar_loading))
        RadarPhase.COMING_SOON -> RadarComingSoon(centered)
        RadarPhase.PRO_REQUIRED -> RadarProLocked()
        RadarPhase.UNKNOWN_AIRPORT -> EmptyState(
            title = stringResource(R.string.radar_unknown_airport_title),
            message = stringResource(
                R.string.radar_unknown_airport_message,
                (state.target as? RadarTarget.Airport)?.iata.orEmpty(),
            ),
            icon = painterResource(R.drawable.ic_radar),
            modifier = centered,
        )
        RadarPhase.OFFLINE -> OfflineState(centered, onRetry = onRetry)
        RadarPhase.NOT_CONFIGURED -> BackendNotConfiguredState(centered)
        RadarPhase.FAILED -> ErrorState(
            title = stringResource(R.string.radar_failed_title),
            message = stringResource(R.string.radar_failed_message),
            onRetry = onRetry,
            modifier = centered,
        )
        RadarPhase.SHOWING -> RadarShowing(state, onSelect)
    }
}

@Composable
private fun RadarComingSoon(modifier: Modifier) {
    EmptyState(
        title = stringResource(R.string.radar_coming_soon_title),
        message = stringResource(R.string.radar_coming_soon_message),
        icon = painterResource(R.drawable.ic_radar),
        modifier = modifier,
    )
}

@Composable
private fun RadarProLocked() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
        if (LocalUpsellsHidden.current) {
            // Crew mode: no upsell, just say what the screen needs.
            EmptyState(title = stringResource(R.string.radar_pro_locked_crew), icon = painterResource(R.drawable.ic_radar))
        } else {
            ProFeatureTeaser(title = stringResource(R.string.radar_pro_title), body = stringResource(R.string.radar_pro_message))
        }
    }
}

@Composable
private fun RadarShowing(state: RadarUiState, onSelect: (String?) -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ground = state.target is RadarTarget.Airport
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(5_000)
            value = Clock.System.now()
        }
    }
    Column(Modifier.fillMaxSize()) {
        RadarMapView(
            rows = state.rows,
            center = state.center,
            radiusKm = state.radiusKm,
            dark = dark,
            contentDescription = stringResource(R.string.radar_cd_map, state.rows.size),
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth().height(340.dp),
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = if (ground) {
                    pluralStringResource(R.plurals.radar_count_ground, state.rows.size, state.rows.size)
                } else {
                    pluralStringResource(R.plurals.radar_count_near, state.rows.size, state.rows.size, state.radiusKm)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val updated = state.asOf?.let { updatedAgoText(it, now) }
            Text(
                text = listOfNotNull(updated, stringResource(R.string.radar_refresh_note)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.notice?.let { notice ->
                Text(
                    text = stringResource(
                        when (notice) {
                            RadarNotice.STALE_OFFLINE -> R.string.radar_notice_offline
                            RadarNotice.STALE_FEED -> R.string.radar_notice_feed
                            RadarNotice.SLOWED_DOWN -> R.string.radar_notice_slowed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlyTrackTheme.accentText,
                    modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        HairlineDivider()
        if (state.rows.isEmpty()) {
            Text(
                text = if (ground) stringResource(R.string.radar_empty_ground) else stringResource(R.string.radar_empty_near, state.radiusKm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(state.rows, key = { it.key }) { row ->
                    RadarRow(row, selected = row.key == state.selectedKey, onClick = { onSelect(row.key) })
                    HairlineDivider(Modifier.padding(start = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun RadarRow(row: RadarAircraftUiModel, selected: Boolean, onClick: () -> Unit) {
    val altitude = altitudeText(row.altitude)
    val speed = stringResource(R.string.radar_speed_kts, row.speedKts)
    val distance = stringResource(R.string.radar_distance_km, formatKm(row.distanceKm))
    val description = stringResource(R.string.radar_cd_row, row.label, altitude, speed, distance)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_map_plane),
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(22.dp).rotate(row.trackDeg.toFloat()),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            val sub = listOfNotNull(row.callsign, row.registration).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(altitude, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            val trend = trendText(row.trend)
            Text(
                text = listOfNotNull(if (row.onGround) null else speed, trend, distance).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun altitudeText(altitude: AltitudeLabel): String = when (altitude) {
    AltitudeLabel.OnGround -> stringResource(R.string.radar_on_ground)
    is AltitudeLabel.Feet -> stringResource(R.string.radar_altitude_ft, NumberFormat.getIntegerInstance().format(altitude.feet))
}

@Composable
private fun trendText(trend: VerticalTrend): String? = when (trend) {
    VerticalTrend.CLIMBING -> stringResource(R.string.radar_trend_climbing)
    VerticalTrend.DESCENDING -> stringResource(R.string.radar_trend_descending)
    VerticalTrend.LEVEL, VerticalTrend.UNKNOWN -> null
}

/** "0.8" below 10 km, whole kilometres above. */
internal fun formatKm(km: Double): String =
    if (km < 10) "%.1f".format(java.util.Locale.getDefault(), km) else km.toInt().toString()
