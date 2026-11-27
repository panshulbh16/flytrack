package app.flytrack.feature.flights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flytrack.R
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.feature.flights.presentation.FlightCardUiModel
import app.flytrack.feature.flights.ui.FlightCard
import app.flytrack.feature.flights.ui.NextFlightCard
import app.flytrack.feature.flights.ui.PreviewFlights
import app.flytrack.feature.friends.FriendsFlightsUiState
import app.flytrack.feature.intel.ConnectionsUiState
import app.flytrack.feature.intel.connectionsItems
import app.flytrack.feature.intel.rememberConnections
import app.flytrack.feature.friends.friendsFlightsItems
import app.flytrack.feature.friends.rememberFriendsFlights
import app.flytrack.feature.gmailimport.GmailSuggestion
import app.flytrack.feature.gmailimport.ImportChooserSheet
import app.flytrack.feature.gmailimport.isGmailImportAvailable
import app.flytrack.feature.importticket.ScanBoardingPassButton
import app.flytrack.push.NotificationPermissionPrompt
import app.flytrack.ui.components.BackendNotConfiguredState
import app.flytrack.ui.components.EmptyState
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.IconAction
import app.flytrack.feature.billing.ui.LocalProState
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.components.SectionCaption
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.theme.FlyTrackTheme

/** Entry point of the start destination: the list of tracked flights. */
@Composable
fun FlightsRoute(
    onAddFlight: () -> Unit,
    onOpenFlight: (flightId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: FlightsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current
    var choosingImport by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenResumed() }
    // P2 wave 2: flights friends share with the user; refreshed on resume and pull-to-refresh.
    val friends = rememberFriendsFlights()
    // P3: connection assistant (Pro; a teaser for free users who track a connecting trip).
    val connections = rememberConnections()

    if (choosingImport) {
        ImportChooserSheet(
            onFromFile = navigator::openImportTicket,
            onFromGmail = navigator::openGmailImport,
            onDismiss = { choosingImport = false },
        )
    }

    FlightsScreen(
        state = state,
        onAddFlight = onAddFlight,
        // Without Gmail there is only one way to import, so the chooser is skipped.
        onImportTicket = { if (isGmailImportAvailable) choosingImport = true else navigator.openImportTicket() },
        onOpenFlight = onOpenFlight,
        onOpenSettings = onOpenSettings,
        onRefresh = {
            viewModel.refresh()
            friends.refresh()
            connections.refresh()
        },
        onArchive = viewModel::archive,
        onUntrack = viewModel::untrack,
        onUndo = viewModel::undo,
        onMessageShown = viewModel::messageShown,
        onAddGmailSuggestion = navigator::openAddFlightFromGmail,
        onDismissGmailSuggestion = viewModel::dismissGmailSuggestion,
        topContent = { NotificationPermissionPrompt() },
        friendsFlights = friends.state.value,
        connections = connections.state.value,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightsScreen(
    state: FlightsUiState,
    onAddFlight: () -> Unit,
    onImportTicket: () -> Unit,
    onOpenFlight: (flightId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onArchive: (flightId: String) -> Unit,
    onUntrack: (flightId: String) -> Unit,
    onUndo: (FlightsMessage) -> Unit,
    onMessageShown: (FlightsMessage) -> Unit,
    onAddGmailSuggestion: (GmailSuggestion) -> Unit = {},
    onDismissGmailSuggestion: (suggestionId: String) -> Unit = {},
    topContent: @Composable () -> Unit = {},
    friendsFlights: FriendsFlightsUiState = FriendsFlightsUiState(),
    connections: ConnectionsUiState = ConnectionsUiState.Hidden,
) {
    val snackbars = remember { SnackbarHostState() }
    FlightsSnackbar(state.message, snackbars, onUndo, onMessageShown)
    // Friends' flights alone are enough for the list (someone who only follows family).
    val showEmpty = state.isEmpty && friendsFlights.flights.isEmpty()

    Scaffold(
        topBar = {
            FlyTrackTopBar(
                title = stringResource(R.string.flights_title),
                actions = {
                    // P4: "Planes near me" for Pro users (free users find it in Settings, with the Pro note).
                    if (LocalProState.current.isPro) {
                        val navigator = LocalAppNavigator.current
                        IconAction(
                            icon = painterResource(R.drawable.ic_radar),
                            contentDescription = stringResource(R.string.radar_action_near_me),
                            onClick = { navigator.openRadar(iata = null) },
                        )
                    }
                    IconAction(
                        icon = painterResource(R.drawable.ic_ticket),
                        contentDescription = stringResource(R.string.flights_action_import_ticket),
                        onClick = onImportTicket,
                    )
                    IconAction(
                        icon = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.action_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            // The empty state carries its own "Add flight" button.
            if (!state.isLoading && !showEmpty) {
                ExtendedFloatingActionButton(
                    onClick = onAddFlight,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.action_add_flight)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            topContent()
            if (isGmailImportAvailable && state.gmailSuggestions.isNotEmpty()) {
                FoundInGmailCard(state.gmailSuggestions, onAddGmailSuggestion, onDismissGmailSuggestion)
            }
            PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().weight(1f),
        ) {
            when {
                state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
                showEmpty -> EmptyFlights(state.isBackendConfigured, onAddFlight, onImportTicket)
                else -> FlightList(state, onOpenFlight, onArchive, onUntrack, friendsFlights, connections)
            }
            }
        }
    }
}

@Composable
private fun FlightsSnackbar(
    message: FlightsMessage?,
    snackbars: SnackbarHostState,
    onUndo: (FlightsMessage) -> Unit,
    onMessageShown: (FlightsMessage) -> Unit,
) {
    val text = when (message) {
        null -> null
        is FlightsMessage.Archived -> stringResource(R.string.flights_message_archived)
        is FlightsMessage.Untracked -> stringResource(R.string.flights_message_untracked)
        FlightsMessage.Offline -> stringResource(R.string.flights_message_offline)
        FlightsMessage.RefreshFailed -> stringResource(R.string.flights_message_refresh_failed)
        FlightsMessage.ActionFailed -> stringResource(R.string.flights_message_action_failed)
        FlightsMessage.TrackingLimitReached -> stringResource(R.string.flights_message_tracking_limit)
    }
    val undoLabel = stringResource(R.string.flights_action_undo)
    LaunchedEffect(message) {
        if (message == null || text == null) return@LaunchedEffect
        val undoable = message is FlightsMessage.Archived || message is FlightsMessage.Untracked
        val result = snackbars.showSnackbar(
            message = text,
            actionLabel = undoLabel.takeIf { undoable },
            // Long, not Short: undo must stay reachable for TalkBack and switch users.
            duration = if (undoable) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo(message)
        onMessageShown(message)
    }
}

/** Scrollable so that pull-to-refresh also works on the empty list. */
@Composable
private fun EmptyFlights(isBackendConfigured: Boolean, onAddFlight: () -> Unit, onImportTicket: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isBackendConfigured) {
            EmptyState(
                title = stringResource(R.string.flights_empty_title),
                message = stringResource(R.string.flights_empty_message),
                actionLabel = stringResource(R.string.action_add_flight),
                onAction = onAddFlight,
            )
            SecondaryButton(
                text = stringResource(R.string.flights_action_import_ticket),
                onClick = onImportTicket,
                icon = painterResource(R.drawable.ic_ticket),
            )
            ScanBoardingPassButton()
            Spacer(Modifier.height(40.dp))
        } else {
            BackendNotConfiguredState()
        }
    }
}

@Composable
private fun FlightList(
    state: FlightsUiState,
    onOpenFlight: (String) -> Unit,
    onArchive: (String) -> Unit,
    onUntrack: (String) -> Unit,
    friendsFlights: FriendsFlightsUiState = FriendsFlightsUiState(),
    connections: ConnectionsUiState = ConnectionsUiState.Hidden,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Bottom room so the last card's menu is not hidden behind the "Add flight" button.
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.next?.let { next ->
            item(key = "next", contentType = "next") {
                NextFlightCard(model = next, onClick = { onOpenFlight(next.card.flightId) })
            }
        }
        // P3: connections between the tracked flights, right under the next flight.
        connectionsItems(connections, onOpenFlight)
        if (state.upcoming.isNotEmpty()) {
            item(key = "upcoming-header", contentType = "header") {
                SectionCaption(text = stringResource(R.string.flights_section_upcoming), modifier = Modifier.padding(top = 8.dp))
            }
            items(state.upcoming, key = { it.flightId }, contentType = { "flight" }) { card ->
                FlightListCard(card, onOpenFlight, onArchive, onUntrack)
            }
        }
        // P2 wave 2: friends' upcoming and just-landed flights, above the user's own past ones.
        friendsFlightsItems(friendsFlights, onOpenFlight)
        if (state.past.isNotEmpty()) {
            item(key = "past-header", contentType = "header") {
                SectionCaption(text = stringResource(R.string.flights_section_past), modifier = Modifier.padding(top = 8.dp))
            }
            items(state.past, key = { it.flightId }, contentType = { "flight" }) { card ->
                FlightListCard(card, onOpenFlight, onArchive, onUntrack)
            }
        }
    }
}

@Composable
private fun FlightListCard(
    card: FlightCardUiModel,
    onOpenFlight: (String) -> Unit,
    onArchive: (String) -> Unit,
    onUntrack: (String) -> Unit,
) {
    FlightCard(
        model = card,
        onClick = { onOpenFlight(card.flightId) },
        menu = { FlightCardMenu(card.designator, onArchive = { onArchive(card.flightId) }, onUntrack = { onUntrack(card.flightId) }) },
    )
}

/** A menu rather than a swipe: it is discoverable, and reachable with TalkBack and a keyboard. */
@Composable
private fun FlightCardMenu(designator: String, onArchive: () -> Unit, onUntrack: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconAction(
            icon = painterResource(R.drawable.ic_more_vert),
            contentDescription = stringResource(R.string.flights_action_more, designator),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.flights_action_archive)) },
                onClick = {
                    expanded = false
                    onArchive()
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_archive), contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.flights_action_untrack)) },
                onClick = {
                    expanded = false
                    onUntrack()
                },
                leadingIcon = { Icon(painterResource(R.drawable.ic_bell_off), contentDescription = null) },
            )
        }
    }
}

/** Pending bookings found in Gmail. "Add" opens Add flight filled in; nothing is tracked without confirming there. */
@Composable
private fun FoundInGmailCard(
    suggestions: List<GmailSuggestion>,
    onAdd: (GmailSuggestion) -> Unit,
    onDismiss: (suggestionId: String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        // ponytail: scrolls inside a capped height; add a "see all" link to the Gmail screen if long lists show up.
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionCaption(stringResource(R.string.flights_gmail_title))
            suggestions.forEach { suggestion ->
                Spacer(Modifier.height(12.dp))
                GmailSuggestionRow(suggestion, onAdd, onDismiss)
            }
        }
    }
}

@Composable
private fun GmailSuggestionRow(
    suggestion: GmailSuggestion,
    onAdd: (GmailSuggestion) -> Unit,
    onDismiss: (suggestionId: String) -> Unit,
) {
    val date = FlightTimeFormat.dayMonth(suggestion.date)
    val from = suggestion.fromIata
    val to = suggestion.toIata
    val headline = if (from != null && to != null) {
        stringResource(R.string.flights_gmail_row_route, suggestion.flight, date, from, to)
    } else {
        stringResource(R.string.flights_gmail_row, suggestion.flight, date)
    }
    // Every row has "Add" and "Dismiss": name the flight so TalkBack users know which one.
    val addDescription = stringResource(R.string.flights_gmail_add_description, suggestion.flight, date)
    val dismissDescription = stringResource(R.string.flights_gmail_dismiss_description, suggestion.flight, date)
    Text(headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    Text(
        text = suggestion.subject,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(
            text = stringResource(R.string.flights_gmail_add),
            onClick = { onAdd(suggestion) },
            modifier = Modifier.weight(1f).semantics { contentDescription = addDescription },
        )
        SecondaryButton(
            text = stringResource(R.string.flights_gmail_dismiss),
            onClick = { onDismiss(suggestion.id) },
            modifier = Modifier.weight(1f).semantics { contentDescription = dismissDescription },
        )
    }
}

@Preview(name = "List, light", showBackground = true, heightDp = 1100)
@Composable
private fun FlightsScreenLightPreview() {
    FlyTrackTheme(darkTheme = false) { PreviewScreen(PreviewFlights.listState()) }
}

@Preview(name = "List, dark", showBackground = true, heightDp = 1100)
@Composable
private fun FlightsScreenDarkPreview() {
    FlyTrackTheme(darkTheme = true) { PreviewScreen(PreviewFlights.listState()) }
}

@Preview(name = "Empty, light", showBackground = true)
@Composable
private fun FlightsScreenEmptyPreview() {
    FlyTrackTheme(darkTheme = false) { PreviewScreen(FlightsUiState(isLoading = false)) }
}

@Preview(name = "Empty, dark", showBackground = true)
@Composable
private fun FlightsScreenEmptyDarkPreview() {
    FlyTrackTheme(darkTheme = true) { PreviewScreen(FlightsUiState(isLoading = false)) }
}

@Composable
private fun PreviewScreen(state: FlightsUiState) {
    FlightsScreen(
        state = state,
        onAddFlight = {},
        onImportTicket = {},
        onOpenFlight = {},
        onOpenSettings = {},
        onRefresh = {},
        onArchive = {},
        onUntrack = {},
        onUndo = {},
        onMessageShown = {},
    )
}
