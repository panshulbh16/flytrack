package app.flytrack.feature.terminalmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.nav.LocalAppNavigator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TerminalMapEntryPoint {
    fun terminalMaps(): TerminalMapRepository
}

/**
 * "Terminal map" and "Ground radar" under an airport on the flight screen (P4 sections 1 and 4).
 * [gate] is the departure gate, [belt] the arrival belt; both are highlighted on the terminal map.
 * Both features are Pro: free users see the buttons (the screens explain Pro), except in crew mode,
 * where nothing is sold and the buttons are hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AirportMapButtons(
    iata: String,
    gate: String?,
    belt: String?,
    flightId: String?,
    showGroundRadar: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    val pro = LocalProState.current
    val hasAccess = pro.isPro || (flightId != null && pro.accessFor(flightId) != FlightProAccess.LOCKED)
    if (!hasAccess && LocalUpsellsHidden.current) return
    val offersMap = remember(iata, context) {
        EntryPointAccessors.fromApplication(context.applicationContext, TerminalMapEntryPoint::class.java).terminalMaps().isOffered(iata)
    }
    // The ground radar is Pro without the trial-flight exception (the server checks current_user_is_pro()).
    val radar = showGroundRadar && (pro.isPro || !LocalUpsellsHidden.current)
    if (!offersMap && !radar) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offersMap) {
            SecondaryButton(
                text = stringResource(R.string.terminalmap_action_open),
                onClick = { navigator.openTerminalMap(iata, gate, belt, flightId) },
                icon = painterResource(R.drawable.ic_terminalmap),
            )
        }
        if (radar) {
            SecondaryButton(
                text = stringResource(R.string.radar_action_ground),
                onClick = { navigator.openRadar(iata = iata) },
                icon = painterResource(R.drawable.ic_radar),
            )
        }
    }
}
