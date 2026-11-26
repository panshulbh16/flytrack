package app.flytrack.feature.airporthealth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.model.FlightPhase
import app.flytrack.feature.billing.ui.LocalPaywallLauncher
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.components.StatusPill
import app.flytrack.ui.theme.FlyTrackTheme
import java.text.NumberFormat

/**
 * "Airport conditions" on the flight detail screen: level word and a localised headline for the
 * departure and/or arrival airport, the Pro detail or a Pro teaser. Draws its own hairline above it and
 * nothing at all when there is nothing to show (landed flight, offline, unknown airport).
 */
@Composable
fun AirportHealthBlock(depIata: String, arrIata: String, phase: FlightPhase, modifier: Modifier = Modifier) {
    val requests = healthRequests(depIata, arrIata, healthSides(phase))
    if (requests.isEmpty()) return
    val viewModel = hiltViewModel<AirportHealthViewModel>(key = "airport-health:$depIata:$arrIata")
    LaunchedEffect(requests) { viewModel.load(requests) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.entries.isEmpty()) return
    AirportHealthContent(state, modifier)
}

@Composable
fun AirportHealthContent(state: AirportHealthUiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Spacer(Modifier.height(20.dp))
        HairlineDivider()
        Spacer(Modifier.height(16.dp))
        SectionCaption(stringResource(R.string.airporthealth_title))
        state.entries.forEach { entry ->
            Spacer(Modifier.height(12.dp))
            AirportRow(entry)
        }
        if (state.showTeaser) {
            Spacer(Modifier.height(16.dp))
            HealthTeaser()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.airporthealth_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AirportRow(entry: AirportHealthEntry) {
    val health = entry.health
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (entry.side == HealthSide.DEPARTURE) R.string.airporthealth_departure else R.string.airporthealth_arrival,
                        health.iata,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                headlineText(health).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(text = levelWord(health.level), tone = health.level.tone)
        }
        health.detail?.let { DetailRows(it) }
    }
}

@Composable
private fun DetailRows(detail: HealthDetail) {
    val numbers = NumberFormat.getIntegerInstance()
    Column(Modifier.padding(top = 6.dp)) {
        if (detail.hasDelaySample) {
            detail.avgDepDelayMin?.let { DetailRow(stringResource(R.string.airporthealth_avg_delay), stringResource(R.string.airporthealth_avg_delay_value, it)) }
            detail.delayedPct?.let { DetailRow(stringResource(R.string.airporthealth_delayed_pct), stringResource(R.string.airporthealth_delayed_pct_value, it)) }
        }
        DetailRow(stringResource(R.string.airporthealth_cancelled), numbers.format(detail.cancelledToday))
        detail.weather?.let { w ->
            w.visibilityM?.let {
                DetailRow(
                    stringResource(R.string.airporthealth_visibility),
                    if (it >= VISIBILITY_10KM) stringResource(R.string.airporthealth_visibility_10km) else stringResource(R.string.airporthealth_visibility_value, numbers.format(it)),
                )
            }
            w.category?.let { DetailRow(stringResource(R.string.airporthealth_category), it.name) }
            w.windKts?.let { DetailRow(stringResource(R.string.airporthealth_wind), stringResource(R.string.airporthealth_wind_value, it)) }
            weatherWord(w.wx)?.let { DetailRow(stringResource(R.string.airporthealth_weather), it) }
        }
        Text(
            if (detail.hasDelaySample) {
                pluralStringResource(R.plurals.airporthealth_sample, detail.sampleSize, detail.sampleSize)
            } else {
                stringResource(R.string.airporthealth_sample_small)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** METAR "9999" and the US "10+" statute miles both mean 10 km or more. */
private const val VISIBILITY_10KM = 9999

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HealthTeaser() {
    val launcher = LocalPaywallLauncher.current
    Surface(
        onClick = launcher::openPaywall,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.billing_badge_pro).uppercase(), style = FlyTrackTheme.type.caption)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.airporthealth_teaser_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.airporthealth_teaser_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.billing_action_see_plans), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun levelWord(level: HealthLevel): String = stringResource(
    when (level) {
        HealthLevel.NORMAL -> R.string.airporthealth_level_normal
        HealthLevel.BUSY -> R.string.airporthealth_level_busy
        HealthLevel.DISRUPTED -> R.string.airporthealth_level_disrupted
        HealthLevel.SEVERE -> R.string.airporthealth_level_severe
    },
)

@Composable
private fun headlineText(health: AirportHealth): String {
    val numbers = NumberFormat.getIntegerInstance()
    return when (val h = health.typedHeadline()) {
        HealthHeadline.Normal -> stringResource(R.string.airporthealth_headline_normal)
        is HealthHeadline.Fog -> h.visibilityM?.let { stringResource(R.string.airporthealth_headline_fog_visibility, numbers.format(it)) }
            ?: stringResource(R.string.airporthealth_headline_fog)
        HealthHeadline.Thunderstorms -> stringResource(R.string.airporthealth_headline_thunderstorms)
        is HealthHeadline.LowVisibility -> stringResource(R.string.airporthealth_headline_low_visibility, numbers.format(h.visibilityM))
        is HealthHeadline.LowCloud -> stringResource(R.string.airporthealth_headline_low_cloud, numbers.format(h.ceilingFt))
        HealthHeadline.PoorWeather -> stringResource(R.string.airporthealth_headline_poor_weather)
        is HealthHeadline.Cancellations -> pluralStringResource(R.plurals.airporthealth_headline_cancellations, h.count, h.count)
        is HealthHeadline.RunningLate -> stringResource(R.string.airporthealth_headline_late, h.minutes)
        is HealthHeadline.DelayedShare -> stringResource(R.string.airporthealth_headline_delayed_share, h.percent)
        is HealthHeadline.Other -> h.text
    }
}

/** Plain word for the main METAR present-weather group; null when there is none or it is not one we name. */
@Composable
private fun weatherWord(wx: String?): String? = when (presentWeather(wx)) {
    PresentWeather.FOG -> stringResource(R.string.airporthealth_wx_fog)
    PresentWeather.MIST -> stringResource(R.string.airporthealth_wx_mist)
    PresentWeather.HAZE -> stringResource(R.string.airporthealth_wx_haze)
    PresentWeather.SMOKE -> stringResource(R.string.airporthealth_wx_smoke)
    PresentWeather.THUNDERSTORM -> stringResource(R.string.airporthealth_wx_thunderstorm)
    PresentWeather.RAIN -> stringResource(R.string.airporthealth_wx_rain)
    PresentWeather.DUST -> stringResource(R.string.airporthealth_wx_dust)
    null -> wx
}
