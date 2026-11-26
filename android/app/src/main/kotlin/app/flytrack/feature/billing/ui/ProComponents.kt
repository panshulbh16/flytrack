package app.flytrack.feature.billing.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.feature.billing.FlightProAccess
import app.flytrack.feature.billing.ProRepository
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.billing.TrialFlightWatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import app.flytrack.ui.theme.FlyTrackTheme

/** Opens the paywall. Provided by FlyTrackNavHost; does nothing in previews. */
fun interface PaywallLauncher {
    fun openPaywall()
}

val LocalPaywallLauncher = staticCompositionLocalOf { PaywallLauncher { } }

/** The user's Pro state for any composable under [ProStateProvider]; [ProState.Free] in previews. */
val LocalProState = compositionLocalOf { ProState.Free }

/** Activity-wide Pro state; starting it also configures the store SDK. */
@HiltViewModel
class ProStatusViewModel @Inject constructor(pro: ProRepository, trialWatcher: TrialFlightWatcher) : ViewModel() {
    init {
        pro.start()
        trialWatcher.start()
    }

    val state: StateFlow<ProState> = pro.observe().stateIn(viewModelScope, SharingStarted.Eagerly, ProState.Free)
}

/** Provides [LocalProState] and [LocalPaywallLauncher] to [content]. Used once, by the NavHost. */
@Composable
fun ProStateProvider(onOpenPaywall: () -> Unit, content: @Composable () -> Unit) {
    val viewModel = hiltViewModel<ProStatusViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalProState provides state,
        LocalPaywallLauncher provides PaywallLauncher(onOpenPaywall),
        content = content,
    )
}

/** Small pill: "Pro", or "Pro free for this flight". */
@Composable
fun ProBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = text.uppercase(),
            style = FlyTrackTheme.type.caption,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The flight the current screen shows. FlyTrackNavHost provides it around the flight detail
 * destination, so [ProFlightBadge] works without threading the id through the screen.
 */
val LocalProFlightId = staticCompositionLocalOf<String?> { null }

/** [ProFlightBadge] for [LocalProFlightId]; nothing outside a flight screen. */
@Composable
fun ProFlightBadge(modifier: Modifier = Modifier) {
    val flightId = LocalProFlightId.current ?: return
    ProFlightBadge(flightId = flightId, modifier = modifier)
}

/** "Pro free for this flight" when [flightId] is the user's trial flight; nothing otherwise. */
@Composable
fun ProFlightBadge(flightId: String, modifier: Modifier = Modifier) {
    if (LocalProState.current.accessFor(flightId) == FlightProAccess.TRIAL) {
        ProBadge(text = stringResource(R.string.billing_badge_trial), modifier = modifier)
    }
}

/**
 * Shown to free users where Pro has something (`has_pro_insight`). It says that Pro knows more,
 * never what, and opens the paywall.
 */
@Composable
fun ProTeaser(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val launcher = LocalPaywallLauncher.current
    val open = onClick ?: launcher::openPaywall
    Surface(
        onClick = open,
        modifier = modifier
            .fillMaxWidth()
            .semantics { role = Role.Button },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(painter = painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text = stringResource(R.string.billing_badge_pro).uppercase(), style = FlyTrackTheme.type.caption)
                Spacer(Modifier.height(2.dp))
                Text(text = stringResource(R.string.billing_teaser_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(text = stringResource(R.string.billing_teaser_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.billing_action_see_plans), style = MaterialTheme.typography.labelLarge)
                    Icon(painter = painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ProTeaserPreview() {
    FlyTrackTheme {
        Column(Modifier.padding(16.dp)) {
            ProBadge(stringResource(R.string.billing_badge_trial))
            Spacer(Modifier.height(12.dp))
            ProTeaser(onClick = {})
        }
    }
}
