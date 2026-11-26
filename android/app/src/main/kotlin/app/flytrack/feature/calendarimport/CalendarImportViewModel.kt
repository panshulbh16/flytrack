package app.flytrack.feature.calendarimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.time.today
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.addflight.HomeTimeZone
import app.flytrack.feature.airports.AirportCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import app.flytrack.di.DefaultDispatcher
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** The import screen, from the first tap to the list. */
sealed interface CalendarImportState {
    /** Nothing read yet: the screen explains and offers "Scan my calendar" (which asks for the permission). */
    data object Intro : CalendarImportState

    /** The user said no to READ_CALENDAR; [permanently] = Android will not ask again (open app settings). */
    data class PermissionDenied(val permanently: Boolean) : CalendarImportState
    data object Scanning : CalendarImportState

    /** [eventsRead] events were read; [suggestions] are the flight-like ones (may be empty). */
    data class Results(val suggestions: List<CalendarSuggestion>, val eventsRead: Int, val truncated: Boolean) : CalendarImportState
    data object Unavailable : CalendarImportState
}

/** P3 A3: scan the next 365 days of the device calendar for flights and list them as suggestions. */
@HiltViewModel
class CalendarImportViewModel @Inject constructor(
    private val calendar: DeviceCalendar,
    private val airports: AirportCatalog,
    private val flights: FlightRepository,
    private val clock: Clock,
    private val homeTimeZone: HomeTimeZone,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow<CalendarImportState>(CalendarImportState.Intro)
    val state: StateFlow<CalendarImportState> = _state.asStateFlow()

    /** The permission is already granted: no need to show the intro again (e.g. a second visit). */
    val canScanWithoutAsking: Boolean get() = calendar.hasPermission()

    /** Called after the permission was granted (or was already). */
    fun scan() {
        if (_state.value == CalendarImportState.Scanning) return
        _state.value = CalendarImportState.Scanning
        viewModelScope.launch {
            val now = clock.now()
            when (val read = calendar.events(now - 1.days, now + SCAN_DAYS.days)) {
                DeviceCalendarResult.NoPermission -> _state.value = CalendarImportState.PermissionDenied(permanently = false)
                DeviceCalendarResult.Unavailable -> _state.value = CalendarImportState.Unavailable
                is DeviceCalendarResult.Events -> {
                    val catalog = airports.load()
                    val tracked = flights.observeTrackedFlights().first().map { it.flight }
                    val today = clock.today(homeTimeZone.id)
                    val suggestions = withContext(cpu) {
                        val parser = CalendarFlightParser(catalog)
                        buildSuggestions(read.events.flatMap(parser::candidates), tracked, today)
                    }
                    _state.value = CalendarImportState.Results(suggestions, read.events.size, read.truncated)
                }
            }
        }
    }

    fun permissionDenied(permanently: Boolean) {
        _state.value = CalendarImportState.PermissionDenied(permanently)
    }

    /** Back on screen (e.g. after a suggestion was added): mark newly tracked flights. */
    fun refreshTracked() {
        val results = _state.value as? CalendarImportState.Results ?: return
        viewModelScope.launch {
            val tracked = flights.observeTrackedFlights().first().map { it.flight }
            val keys = tracked.map { "${it.designator}|${it.flightDate}" to "${it.depIata}|${it.arrIata}|${it.flightDate}" }
            _state.update { s ->
                if (s !is CalendarImportState.Results) return@update s
                s.copy(
                    suggestions = s.suggestions.map { sug ->
                        if (sug.alreadyTracked || sug.date == null) return@map sug
                        val hit = keys.any { (n, r) ->
                            (sug.flight != null && n == "${sug.flight}|${sug.date}") ||
                                (sug.flight == null && sug.from != null && r.startsWith("${sug.from.iata}|") && r.endsWith("|${sug.date}") &&
                                    (sug.to == null || r == "${sug.from.iata}|${sug.to.iata}|${sug.date}"))
                        }
                        if (hit) sug.copy(alreadyTracked = true) else sug
                    },
                )
            }
        }
    }

    companion object {
        /** The contract's window: the next 365 days. */
        const val SCAN_DAYS = 365
    }
}
