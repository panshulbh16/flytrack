package app.flytrack.feature.friends

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.flights.ui.FlightCard
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator

/**
 * Friends' flights for the flights screen. Call it in the Route; it refreshes when the screen resumes.
 * Pass the state to [friendsFlightsItems] and call [FriendsFlightsHandle.refresh] on pull-to-refresh.
 */
class FriendsFlightsHandle internal constructor(
    val state: State<FriendsFlightsUiState>,
    private val viewModel: FriendsFlightsViewModel,
) {
    val hasFlights: Boolean get() = state.value.flights.isNotEmpty()

    fun refresh() = viewModel.refresh()
}

@Composable
fun rememberFriendsFlights(): FriendsFlightsHandle {
    val viewModel = hiltViewModel<FriendsFlightsViewModel>()
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    return FriendsFlightsHandle(state, viewModel)
}

/** "Friends' flights" header and one card per flight; nothing when there are none. */
fun LazyListScope.friendsFlightsItems(state: FriendsFlightsUiState, onOpenFlight: (String) -> Unit) {
    if (state.flights.isEmpty()) return
    item(key = "friends-header", contentType = "header") {
        SectionCaption(text = stringResource(R.string.friends_flights_section), modifier = Modifier.padding(top = 8.dp))
    }
    items(state.flights, key = { "friend:${it.friendId}:${it.card.flightId}" }, contentType = { "friend-flight" }) { item ->
        Column {
            Text(
                stringResource(R.string.friends_flight_of, friendName(item.friendName)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            FlightCard(model = item.card, onClick = { onOpenFlight(item.card.flightId) })
        }
    }
}

/**
 * "Show to friends" switch of one tracked flight on the detail screen. Draws its own hairline; nothing while
 * the value is unknown (not tracked, loading, offline).
 */
@Composable
fun ShareWithFriendsRow(flightId: String, tracked: Boolean, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<ShareWithFriendsViewModel, ShareWithFriendsViewModel.Factory>(key = "share-friends:$flightId") { it.create(flightId) }
    LaunchedEffect(tracked) { if (tracked) viewModel.load() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.failed) {
        if (state.failed) {
            Toast.makeText(context, R.string.friends_message_failed, Toast.LENGTH_SHORT).show()
            viewModel.failureShown()
        }
    }
    val share = state.share
    if (!tracked || share == null) return
    val title = stringResource(R.string.friends_share_title)
    Column(modifier) {
        Spacer(Modifier.height(20.dp))
        HairlineDivider()
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (share) R.string.friends_share_on else R.string.friends_share_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = share,
                onCheckedChange = viewModel::setShare,
                enabled = !state.isSaving,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
    }
}

/** Settings rows of wave 2: Friends, Import by email (Pro). Calendar has its own section. */
@Composable
fun Wave2SettingsRows(modifier: Modifier = Modifier) {
    val navigator = LocalAppNavigator.current
    Column(modifier) {
        NavigationRow(
            title = stringResource(R.string.friends_settings_row),
            message = stringResource(R.string.friends_settings_row_message),
            onClick = navigator::openFriends,
        )
        NavigationRow(
            title = stringResource(R.string.forwarding_settings_row),
            message = stringResource(R.string.forwarding_settings_row_message),
            onClick = navigator::openEmailForwarding,
        )
    }
}

@Composable
private fun NavigationRow(title: String, message: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
