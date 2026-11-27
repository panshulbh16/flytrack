package app.flytrack.feature.addflight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.flytrack.R
import app.flytrack.core.time.RelativeDay
import app.flytrack.feature.airports.AirportCatalog
import app.flytrack.feature.airports.AirportList
import app.flytrack.feature.airports.CatalogAirport
import app.flytrack.feature.airports.search
import app.flytrack.ui.components.FlyTrackTopBar
import app.flytrack.ui.components.HairlineDivider
import app.flytrack.ui.components.IconAction
import app.flytrack.ui.components.LoadingState
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.relativeDayText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject

/** "Flight number" | "By route" (P3 B5). */
@Composable
internal fun AddFlightModeTabs(mode: AddFlightMode, enabled: Boolean, onModeChange: (AddFlightMode) -> Unit) {
    val labels = listOf(
        AddFlightMode.FLIGHT_NUMBER to stringResource(R.string.search_tab_flight_number),
        AddFlightMode.ROUTE to stringResource(R.string.search_tab_route),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = mode == value,
                onClick = { onModeChange(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** The "By route" form: from, to (optional), date, airline (optional), search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RouteSearchForm(
    state: AddFlightUiState,
    route: RouteForm,
    onFromChange: (CatalogAirport) -> Unit,
    onToChange: (CatalogAirport?) -> Unit,
    onSwap: () -> Unit,
    onAirlineChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !state.isTracking
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        state.prefillNotice?.let { notice ->
            PrefillNoticeRow(notice, state)
            Spacer(Modifier.height(16.dp))
        }
        Text(
            stringResource(R.string.search_route_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        val fromError = when (route.error) {
            RouteInputError.FROM_REQUIRED -> stringResource(R.string.search_route_error_from)
            RouteInputError.SAME_AIRPORT -> stringResource(R.string.search_route_error_same)
            else -> null
        }
        AirportField(
            label = stringResource(R.string.search_route_from),
            airport = route.from,
            placeholder = stringResource(R.string.search_route_choose),
            error = fromError,
            enabled = editable,
            onClick = { picking = PICK_FROM },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconAction(
                icon = painterResource(R.drawable.ic_search_swap),
                contentDescription = stringResource(R.string.search_route_swap),
                onClick = onSwap,
                enabled = editable && route.from != null && route.to != null,
            )
        }
        AirportField(
            label = stringResource(R.string.search_route_to),
            airport = route.to,
            placeholder = stringResource(R.string.search_route_any_destination),
            error = null,
            enabled = editable,
            onClick = { picking = PICK_TO },
            onClear = if (route.to != null && editable) ({ onToChange(null) }) else null,
        )
        Spacer(Modifier.height(20.dp))

        FieldLabel(stringResource(R.string.addflight_date_label))
        Spacer(Modifier.height(4.dp))
        val shortcuts = listOf(
            state.today to RelativeDay.Today,
            state.today.plusDays(1) to RelativeDay.Tomorrow,
        )
        val isOtherDate = shortcuts.none { (day, _) -> day == route.date }
        FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.forEach { (day, relative) ->
                DayChip(
                    text = relativeDayText(relative),
                    selected = day == route.date,
                    enabled = editable && route.isSelectable(day),
                    onClick = { onDateChange(day) },
                )
            }
            val otherText = if (isOtherDate) formDateText(route.date, state.today) else stringResource(R.string.addflight_date_other)
            val otherDescription = stringResource(R.string.addflight_date_pick_description, otherText)
            DayChip(
                text = otherText,
                selected = isOtherDate,
                enabled = editable,
                onClick = { showDatePicker = true },
                icon = painterResource(R.drawable.ic_calendar),
                modifier = Modifier.semantics { contentDescription = otherDescription },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.search_route_date_hint, ROUTE_SEARCH_DAYS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        val airlineError = if (route.error == RouteInputError.AIRLINE_INCOMPLETE) stringResource(R.string.search_route_error_airline) else null
        OutlinedTextField(
            value = route.airlineInput,
            onValueChange = onAirlineChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text(stringResource(R.string.search_route_airline)) },
            supportingText = { Text(airlineError ?: stringResource(R.string.search_route_airline_hint)) },
            isError = airlineError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = stringResource(R.string.search_route_search),
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.isBackendConfigured && editable,
            loading = state.isSearching,
            icon = painterResource(R.drawable.ic_search),
        )
        Text(
            stringResource(R.string.search_route_cost_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    when (picking) {
        PICK_FROM -> AirportPickerDialog(
            title = stringResource(R.string.search_route_from),
            onPick = {
                picking = null
                onFromChange(it)
            },
            onDismiss = { picking = null },
        )
        PICK_TO -> AirportPickerDialog(
            title = stringResource(R.string.search_route_to),
            onPick = {
                picking = null
                onToChange(it)
            },
            onDismiss = { picking = null },
        )
    }
    if (showDatePicker) {
        DepartureDatePickerDialog(
            selected = route.date,
            first = route.firstSelectableDate,
            last = route.lastSelectableDate,
            onConfirm = { date ->
                showDatePicker = false
                onDateChange(date)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

private const val PICK_FROM = "from"
private const val PICK_TO = "to"
private const val ROUTE_SEARCH_DAYS = 30

/** A tappable field showing "DEL · Delhi" (or the placeholder); opens the airport picker. */
@Composable
private fun AirportField(
    label: String,
    airport: CatalogAirport?,
    placeholder: String,
    error: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val value = airport?.let { stringResource(R.string.search_airport_value, it.iata, it.city) } ?: placeholder
    Column {
        FieldLabel(label)
        Spacer(Modifier.height(4.dp))
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, if (error != null) colors.error else colors.outline),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "$label: $value"
                },
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (airport == null) colors.onSurfaceVariant else colors.onSurface,
                    modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onClear != null) {
                    IconAction(
                        icon = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.search_route_clear_to),
                        onClick = onClear,
                    )
                }
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Holds the bundled catalog for the picker. */
@HiltViewModel
class AirportPickerViewModel @Inject constructor(catalog: AirportCatalog) : ViewModel() {
    private val _airports = MutableStateFlow<AirportList?>(null)
    val airports: StateFlow<AirportList?> = _airports.asStateFlow()

    init {
        viewModelScope.launch { _airports.value = catalog.load() }
    }
}

/** Full-screen search over the bundled airports (code, city or name). */
@Composable
internal fun AirportPickerDialog(title: String, onPick: (CatalogAirport) -> Unit, onDismiss: () -> Unit) {
    val viewModel = hiltViewModel<AirportPickerViewModel>()
    val airports by viewModel.airports.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                FlyTrackTopBar(title = title, onBack = onDismiss)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(40) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus),
                    label = { Text(stringResource(R.string.search_airport_query)) },
                    placeholder = { Text(stringResource(R.string.search_airport_query_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.small,
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                val list = airports
                if (list == null) {
                    LoadingState()
                } else {
                    val results = remember(list, query) { list.search(query) }
                    if (results.isEmpty()) {
                        Text(
                            stringResource(R.string.search_airport_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        items(results, key = { it.iata }) { airport ->
                            AirportRow(airport, onClick = { onPick(airport) })
                            HairlineDivider(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(8.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun AirportRow(airport: CatalogAirport, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(airport.iata, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.search_airport_city_country, airport.city, airport.country),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                airport.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Departures from DEL", "DEL → BOM", plus " · 6E" for an airline: the caption of a route search. */
@Composable
internal fun searchLabel(query: SearchQuery): String {
    val route = query.route ?: return query.flight
    val base = if (route.arr == null) {
        stringResource(R.string.search_route_label_from, route.dep)
    } else {
        stringResource(R.string.addflight_leg_route, route.dep, route.arr)
    }
    return if (route.airline == null) base else stringResource(R.string.search_route_label_airline, base, route.airline)
}
