package app.flytrack.feature.radar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.estimate.GeoPoint
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.nav.LocalAppNavigator

/**
 * A settings-style navigation row: icon, title, one line of detail, chevron. Shared by the P4 features
 * (radar, TripIt, gifts) so their rows look like the other Settings rows.
 */
@Composable
fun P4NavRow(iconRes: Int, title: String, message: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Settings row "Planes near me" (opens the proximity radar). */
@Composable
fun PlanesNearMeRow(modifier: Modifier = Modifier) {
    val navigator = LocalAppNavigator.current
    P4NavRow(
        iconRes = R.drawable.ic_radar,
        title = stringResource(R.string.radar_action_near_me),
        message = stringResource(R.string.radar_settings_near_me_message),
        onClick = { navigator.openRadar(iata = null) },
        modifier = modifier,
    )
}

/**
 * "Planes around your flight" under the in-air map (contract section 1: proximity radar around the flight's
 * live or estimated position). [point] is the plane's position; RadarQuery rounds it to 0.05° before sending.
 * Pro only, like the radar itself; free users see it outside crew mode and get the Pro teaser.
 */
@Composable
fun PlanesAroundFlightButton(point: GeoPoint, onBeforeOpen: () -> Unit, modifier: Modifier = Modifier) {
    val navigator = LocalAppNavigator.current
    if (!LocalProState.current.isPro && LocalUpsellsHidden.current) return
    SecondaryButton(
        text = stringResource(R.string.radar_title_around),
        onClick = {
            onBeforeOpen()
            navigator.openRadar(iata = null, lat = point.lat, lon = point.lon)
        },
        icon = painterResource(R.drawable.ic_radar),
        modifier = modifier,
    )
}
