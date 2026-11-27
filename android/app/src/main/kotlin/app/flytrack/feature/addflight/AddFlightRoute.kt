package app.flytrack.feature.addflight

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.flytrack.R
import app.flytrack.core.model.TrackSource
import app.flytrack.feature.airports.CatalogAirport
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.nav.LocalAppNavigator
import app.flytrack.ui.theme.FlyTrackTheme
import kotlinx.datetime.LocalDate

/**
 * Entry point of the add-flight screen.
 *
 * @param prefillFlight flight number text from the ticket importer, e.g. "6E 2341"
 * @param prefillDate ISO local departure date from the importer, e.g. "2026-11-25"
 * @param prefillPnr booking reference from the importer
 * @param onDone called with the tracked flight's id (the app then shows its detail screen), or
 *   null to simply go back to the list
 */
@Composable
fun AddFlightRoute(
    prefillFlight: String?,
    prefillDate: String?,
    prefillPnr: String?,
    onDone: (flightId: String?) -> Unit,
    onBack: () -> Unit,
    prefillSource: String? = null,
    prefillSeat: String? = null,
    prefillGmailSuggestionId: String? = null,
    prefillFrom: String? = null,
    prefillTo: String? = null,
) {
    val prefill = AddFlightPrefill(
        flight = prefillFlight,
        date = prefillDate,
        pnr = prefillPnr,
        source = prefillSource?.let { raw -> TrackSource.entries.firstOrNull { it.wire == raw } },
        seat = prefillSeat,
        gmailSuggestionId = prefillGmailSuggestionId,
        routeFrom = prefillFrom,
        routeTo = prefillTo,
    )
    val viewModel = hiltViewModel<AddFlightViewModel, AddFlightViewModel.Factory>(
        creationCallback = { factory -> factory.create(prefill) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = LocalAppNavigator.current

    val currentOnDone by rememberUpdatedState(onDone)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        // Navigation must not run while the screen is stopped, so events wait for STARTED.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is AddFlightEvent.Finished -> currentOnDone(event.flightId)
                }
            }
        }
    }

    AddFlightScreen(
        state = state,
        onFlightInputChange = viewModel::onFlightInputChange,
        onDateChange = viewModel::onDateChange,
        onPnrInputChange = viewModel::onPnrInputChange,
        onSeatInputChange = viewModel::onSeatInputChange,
        onSearch = viewModel::onSearch,
        onTrack = viewModel::onTrack,
        onOpenTracked = viewModel::onOpenTracked,
        onImportTicket = navigator::openImportTicket,
        onOpenSettings = navigator::openSettings,
        onShowMyFlights = { onDone(null) },
        onBack = onBack,
        onModeChange = viewModel::onModeChange,
        onPastFlightChange = viewModel::onPastFlightChange,
        onRouteFromChange = viewModel::onRouteFromChange,
        onRouteToChange = viewModel::onRouteToChange,
        onRouteSwap = viewModel::onRouteSwap,
        onRouteAirlineChange = viewModel::onRouteAirlineChange,
        onRouteDateChange = viewModel::onRouteDateChange,
        onImportCalendar = navigator::openCalendarImport,
    )
}

/** Stateless add-flight screen: the form on top, the search outcome below a hairline. */
@Composable
fun AddFlightScreen(
    state: AddFlightUiState,
    onFlightInputChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onPnrInputChange: (String) -> Unit,
    onSeatInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTrack: (flightId: String) -> Unit,
    onOpenTracked: (flightId: String) -> Unit,
    onImportTicket: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowMyFlights: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onModeChange: (AddFlightMode) -> Unit = {},
    onPastFlightChange: ((Boolean) -> Unit)? = null,
    onRouteFromChange: (CatalogAirport) -> Unit = {},
    onRouteToChange: (CatalogAirport?) -> Unit = {},
    onRouteSwap: () -> Unit = {},
    onRouteAirlineChange: (String) -> Unit = {},
    onRouteDateChange: (LocalDate) -> Unit = {},
    onImportCalendar: (() -> Unit)? = null,
) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val outcomeRequester = remember { BringIntoViewRequester() }

    // The outcome sits below the form: bring it into view whenever its kind changes
    // (searching, results, an error), so a search never seems to do nothing on a small screen.
    val outcomeKind = state.search::class
    LaunchedEffect(outcomeKind) {
        if (state.search != SearchUiState.Idle) outcomeRequester.bringIntoView()
    }

    val submit = {
        focusManager.clearFocus()
        onSearch()
    }

    Scaffold(
        modifier = modifier,
        topBar = { FlyTrackTopBar(title = stringResource(R.string.addflight_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = emphasised(
                    text = stringResource(R.string.addflight_headline, stringResource(R.string.addflight_headline_key)),
                    key = stringResource(R.string.addflight_headline_key),
                ),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(20.dp))

            val route = state.route
            if (route != null) {
                AddFlightModeTabs(state.mode, enabled = !state.isTracking, onModeChange = onModeChange)
                Spacer(Modifier.height(20.dp))
            }
            if (route != null && state.mode == AddFlightMode.ROUTE) {
                RouteSearchForm(
                    state = state,
                    route = route,
                    onFromChange = onRouteFromChange,
                    onToChange = onRouteToChange,
                    onSwap = onRouteSwap,
                    onAirlineChange = onRouteAirlineChange,
                    onDateChange = onRouteDateChange,
                    onSearch = submit,
                )
            } else {
                AddFlightForm(
                    state = state,
                    onFlightInputChange = onFlightInputChange,
                    onDateChange = onDateChange,
                    onPickDate = { showDatePicker = true },
                    onPnrInputChange = onPnrInputChange,
                    onSeatInputChange = onSeatInputChange,
                    onSearch = submit,
                    onPastFlightChange = onPastFlightChange,
                )
            }

            Spacer(Modifier.height(24.dp))
            HairlineDivider()

            SearchOutcome(
                state = state,
                onRetry = submit,
                onPickDate = { showDatePicker = true },
                onTrack = onTrack,
                onOpenTracked = onOpenTracked,
                onImportTicket = onImportTicket,
                onOpenSettings = onOpenSettings,
                onShowMyFlights = onShowMyFlights,
                modifier = Modifier.bringIntoViewRequester(outcomeRequester),
                onImportCalendar = onImportCalendar,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        DepartureDatePickerDialog(
            selected = state.date,
            first = state.firstSelectableDate,
            last = state.lastSelectableDate,
            onConfirm = { date ->
                showDatePicker = false
                onDateChange(date)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun AddFlightScreenIdlePreview() {
    FlyTrackTheme {
        AddFlightScreen(state = previewState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 1100)
@Composable
private fun AddFlightScreenNotFoundPreview() {
    FlyTrackTheme(darkTheme = true) {
        val state = previewState().copy(
            flightInput = "6e2341",
            normalisedFlight = "6E 2341",
            search = SearchUiState.NotFound(SearchQuery("6E 2341", LocalDate(2026, 11, 22))),
        )
        AddFlightScreen(state = state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

private fun previewState() = AddFlightUiState(
    date = LocalDate(2026, 11, 22),
    today = LocalDate(2026, 11, 22),
    firstSelectableDate = LocalDate(2026, 11, 21),
    lastSelectableDate = LocalDate(2027, 11, 22),
)
