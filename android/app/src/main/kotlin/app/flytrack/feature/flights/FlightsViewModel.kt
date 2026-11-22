package app.flytrack.feature.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.TrackResult
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.flights.presentation.FlightCardUiModel
import app.flytrack.feature.flights.presentation.FlightChangeTracker
import app.flytrack.feature.flights.presentation.NextFlightUiModel
import app.flytrack.feature.flights.presentation.buildFlightSections
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One-off feedback of the list, shown as a snackbar. */
sealed interface FlightsMessage {
    /** Offers "Undo". */
    data class Archived(val flightId: String) : FlightsMessage

    /** Tracking was stopped. Offers "Undo", which tracks the flight again with the same details. */
    data class Untracked(val flightId: String) : FlightsMessage
    data object Offline : FlightsMessage
    data object RefreshFailed : FlightsMessage
    data object ActionFailed : FlightsMessage

    /** Restoring a flight would exceed the plan's number of active flights. */
    data object TrackingLimitReached : FlightsMessage
}

data class FlightsUiState(
    /** True until Room has answered once. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** False when the build has no backend keys: nothing can be added or refreshed. */
    val isBackendConfigured: Boolean = true,
    val next: NextFlightUiModel? = null,
    val upcoming: List<FlightCardUiModel> = emptyList(),
    val past: List<FlightCardUiModel> = emptyList(),
    val message: FlightsMessage? = null,
) {
    val isEmpty: Boolean get() = !isLoading && upcoming.isEmpty() && past.isEmpty()
}

/** Emits the clock's time now and then every [period], so countdowns and "updated … ago" stay current. */
internal fun Clock.ticks(period: Duration = 30.seconds): Flow<Instant> = flow {
    while (true) {
        emit(now())
        delay(period)
    }
}

@HiltViewModel
class FlightsViewModel @Inject constructor(
    private val flights: FlightRepository,
    auth: AuthRepository,
    private val clock: Clock,
    private val changes: FlightChangeTracker,
) : ViewModel() {

    private data class Transient(val isRefreshing: Boolean = false, val message: FlightsMessage? = null)

    private val transient = MutableStateFlow(Transient())

    /** What "Undo" needs to track a flight again exactly as it was. */
    private val untracked = mutableMapOf<String, TrackedFlight>()

    // The app shell refreshes once at launch, so the first appearance of the list does not repeat it.
    private var lastRefreshAt: Instant = clock.now()

    val uiState: StateFlow<FlightsUiState> = combine(
        flights.observeTrackedFlights(),
        auth.session,
        clock.ticks(),
        transient,
    ) { tracked, session, now, transient ->
        val sections = buildFlightSections(tracked, now) { changes.record(it, now) }
        FlightsUiState(
            isLoading = false,
            isRefreshing = transient.isRefreshing,
            isBackendConfigured = session != SessionState.NotConfigured,
            next = sections.next,
            upcoming = sections.upcoming,
            past = sections.past,
            message = transient.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlightsUiState())

    /** Pull-to-refresh: reports problems. */
    fun refresh() {
        if (transient.value.isRefreshing) return
        transient.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val result = flights.refresh()
            lastRefreshAt = clock.now()
            transient.update {
                it.copy(
                    isRefreshing = false,
                    message = when (result) {
                        ActionResult.Success -> it.message
                        ActionResult.Offline -> FlightsMessage.Offline
                        // The app shell already shows a banner for a build without backend keys.
                        ActionResult.NotConfigured -> it.message
                        else -> FlightsMessage.RefreshFailed
                    },
                )
            }
        }
    }

    /** The screen came (back) to the foreground: refresh quietly, unless that just happened. */
    fun onScreenResumed() {
        val now = clock.now()
        if (now - lastRefreshAt < MIN_QUIET_REFRESH_INTERVAL) return
        lastRefreshAt = now
        viewModelScope.launch { flights.refresh() }
    }

    fun archive(flightId: String) {
        viewModelScope.launch {
            val result = flights.archive(flightId, archived = true)
            show(if (result.isSuccess) FlightsMessage.Archived(flightId) else result.toFailureMessage())
        }
    }

    fun untrack(flightId: String) {
        viewModelScope.launch {
            val before = flights.observeTrackedFlights().first().firstOrNull { it.flight.id == flightId }?.tracking
            val result = flights.untrack(flightId)
            if (result.isSuccess) {
                if (before != null) untracked[flightId] = before
                show(FlightsMessage.Untracked(flightId))
            } else {
                show(result.toFailureMessage())
            }
        }
    }

    /** "Undo" on the snackbar of [message]. */
    fun undo(message: FlightsMessage) {
        when (message) {
            is FlightsMessage.Archived -> viewModelScope.launch {
                val result = flights.archive(message.flightId, archived = false)
                if (!result.isSuccess) show(result.toFailureMessage())
            }
            is FlightsMessage.Untracked -> viewModelScope.launch { trackAgain(message.flightId) }
            else -> Unit
        }
    }

    /** The snackbar of [message] is gone. */
    fun messageShown(message: FlightsMessage) {
        transient.update { if (it.message == message) it.copy(message = null) else it }
    }

    private suspend fun trackAgain(flightId: String) {
        val before = untracked.remove(flightId)
        val extras = before?.let { TrackExtras(it.pnr, it.seat, it.cabin, it.source, it.note) } ?: TrackExtras()
        when (flights.track(flightId, extras)) {
            is TrackResult.Tracked, is TrackResult.AlreadyTracked -> {
                // Muted alert types are not part of track(): put them back separately.
                val prefs = before?.notifyPrefs.orEmpty()
                if (prefs.isNotEmpty() && !flights.updateNotifyPrefs(flightId, prefs).isSuccess) {
                    show(FlightsMessage.ActionFailed)
                }
            }
            is TrackResult.TrackingLimitReached -> show(FlightsMessage.TrackingLimitReached)
            TrackResult.Offline -> show(FlightsMessage.Offline)
            TrackResult.NotConfigured -> Unit
            is TrackResult.InvalidInput, TrackResult.FlightNotFound, is TrackResult.Failed -> show(FlightsMessage.ActionFailed)
        }
    }

    private fun show(message: FlightsMessage?) {
        if (message != null) transient.update { it.copy(message = message) }
    }

    private fun ActionResult.toFailureMessage(): FlightsMessage? = when (this) {
        ActionResult.Success, ActionResult.NotConfigured -> null
        ActionResult.Offline -> FlightsMessage.Offline
        ActionResult.TrackingLimitReached -> FlightsMessage.TrackingLimitReached
        ActionResult.NotFound, is ActionResult.InvalidInput, is ActionResult.Failed -> FlightsMessage.ActionFailed
    }

    private companion object {
        val MIN_QUIET_REFRESH_INTERVAL = 1.minutes
    }
}
