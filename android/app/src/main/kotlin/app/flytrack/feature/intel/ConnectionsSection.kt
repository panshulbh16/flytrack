package app.flytrack.feature.intel

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.feature.billing.ui.LocalUpsellsHidden
import app.flytrack.feature.forecast.ProFeatureTeaser
import app.flytrack.ui.components.SectionCaption

/** The connection assistant of the flights list: its state and a way to refresh it (pull-to-refresh). */
class ConnectionsHandle internal constructor(val state: State<ConnectionsUiState>, private val viewModel: ConnectionsViewModel) {
    /** Pull-to-refresh of the flights list. */
    fun refresh() = viewModel.refresh()
}

/** Connections of the tracked flights (B4); asked again when the screen resumes. */
@Composable
fun rememberConnections(): ConnectionsHandle {
    val viewModel = hiltViewModel<ConnectionsViewModel>()
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    // Crew mode hides every upsell: the teaser becomes nothing.
    val upsellsHidden = LocalUpsellsHidden.current
    val shown = remember(state, upsellsHidden) {
        derivedStateOf { state.value.takeUnless { upsellsHidden && it is ConnectionsUiState.Teaser } ?: ConnectionsUiState.Hidden }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    return ConnectionsHandle(shown, viewModel)
}

/** "Connections" header and one row per connection (Pro), or the teaser; nothing when there are none. */
fun LazyListScope.connectionsItems(state: ConnectionsUiState, onOpenFlight: (String) -> Unit) {
    when (state) {
        ConnectionsUiState.Hidden -> Unit
        is ConnectionsUiState.Teaser -> {
            item(key = "connections-header", contentType = "header") { ConnectionsHeader() }
            item(key = "connections-teaser", contentType = "teaser") {
                ProFeatureTeaser(
                    title = pluralStringResource(R.plurals.intel_connections_teaser_title, state.count, state.count),
                    body = stringResource(R.string.intel_connections_teaser_body),
                )
            }
        }
        is ConnectionsUiState.Ready -> {
            item(key = "connections-header", contentType = "header") { ConnectionsHeader() }
            items(state.connections, key = { "connection:${it.key}" }, contentType = { "connection" }) { connection ->
                // The onward flight is the one to watch during the layover.
                ConnectionRow(connection, ConnectionPerspective.TRIP, onClick = { onOpenFlight(connection.outFlightId) })
            }
        }
    }
}

@Composable
private fun ConnectionsHeader() {
    SectionCaption(text = stringResource(R.string.intel_connections_title), modifier = Modifier.padding(top = 8.dp))
}
