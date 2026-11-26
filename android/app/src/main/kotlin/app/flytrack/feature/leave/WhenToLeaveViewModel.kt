package app.flytrack.feature.leave

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Flight
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.airporthealth.AirportHealthRepository
import app.flytrack.feature.airporthealth.AirportHealthResult
import app.flytrack.feature.flights.ticks
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The "When to leave" card. */
sealed interface WhenToLeaveUiState {
    /** No card: not tracked (or archived), already gone, cancelled, or Pro state not known yet. */
    data object Hidden : WhenToLeaveUiState

    /** Free user on a flight without the trial: the teaser. */
    data object Locked : WhenToLeaveUiState

    data class Ready(
        val plan: LeavePlan,
        val depIata: String,
        /** City (or code) of the departure airport, for "to reach Delhi airport". */
        val depCity: String,
        val directionsUrl: String,
        /** The notification is (or will be) scheduled: its time is still ahead. */
        val reminderAhead: Boolean,
    ) : WhenToLeaveUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = WhenToLeaveViewModel.Factory::class)
class WhenToLeaveViewModel @AssistedInject constructor(
    @Assisted private val flightId: String,
    flights: FlightRepository,
    private val travel: TravelTimeStore,
    private val health: AirportHealthRepository,
    private val scheduler: LeaveReminderScheduler,
    private val clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): WhenToLeaveViewModel
    }

    /** Pro on this flight (Pro, or the trial flight); null until the screen says. */
    private val proAccess = MutableStateFlow<Boolean?>(null)

    private val flight: Flow<Flight?> = flights.observeTrackedFlight(flightId)
        .map { tracked -> tracked?.takeUnless { it.tracking.isArchived }?.flight }
        .distinctUntilChanged()

    private val depIata: Flow<String?> = flight.map { it?.depIata }.distinctUntilChanged()

    private val travelMinutes: Flow<Int> = depIata.flatMapLatest { iata ->
        if (iata == null) flowOf(LeaveRules.DEFAULT_TRAVEL_MIN) else travel.travelMinutes(iata)
    }

    /** Fog at the departure airport; asked only within [HEALTH_WINDOW] of departure (weather is "now"). */
    private val fog = MutableStateFlow(false)
    private var fogAskedFor: String? = null

    val uiState: StateFlow<WhenToLeaveUiState> = combine(flight, travelMinutes, fog, proAccess, clock.ticks()) { f, minutes, fogAtDep, pro, now ->
        whenToLeaveState(f, minutes, fogAtDep, pro, now)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WhenToLeaveUiState.Hidden)

    init {
        // The reminder follows the plan while the screen is open; the worker re-checks it when it fires.
        viewModelScope.launch {
            uiState.map { state -> reminderAction(state, proKnown = proAccess.value != null) }
                .distinctUntilChanged()
                .collect { action ->
                    when (action) {
                        is ReminderAction.Schedule -> scheduler.schedule(flightId, action.at)
                        ReminderAction.Cancel -> scheduler.cancel(flightId)
                        // The time has come: leave a firing reminder alone.
                        ReminderAction.Keep -> Unit
                    }
                }
        }
        viewModelScope.launch {
            flight.collect { f -> f?.let { maybeAskHealth(it) } }
        }
    }

    fun setProAccess(pro: Boolean) {
        proAccess.value = pro
    }

    /** "Change" on the card: the travel time to this flight's departure airport, remembered for next time. */
    fun setTravelMinutes(minutes: Int) {
        val state = uiState.value as? WhenToLeaveUiState.Ready ?: return
        viewModelScope.launch { travel.setTravelMinutes(state.depIata, minutes) }
    }

    private suspend fun maybeAskHealth(flight: Flight) {
        val departure = flight.estDep ?: flight.schedDep ?: return
        val now = clock.now()
        if (departure - now > HEALTH_WINDOW || departure < now) return
        val key = "${flight.depIata}:${departure.epochSeconds / 3600}"
        if (key == fogAskedFor) return
        fogAskedFor = key
        fog.value = (health.health(flight.depIata) as? AirportHealthResult.Loaded)?.health?.slowsDeparture() == true
    }

    private companion object {
        val HEALTH_WINDOW = 24.hours
    }
}

/** The card for [flight] at [now]; pure so it can be tested without a ViewModel. */
fun whenToLeaveState(flight: Flight?, travelMinutes: Int, fogAtDep: Boolean, proAccess: Boolean?, now: Instant): WhenToLeaveUiState {
    if (flight == null || proAccess == null) return WhenToLeaveUiState.Hidden
    val plan = leavePlan(flight, travelMinutes, fogAtDep, now) ?: return WhenToLeaveUiState.Hidden
    if (!proAccess) return WhenToLeaveUiState.Locked
    return WhenToLeaveUiState.Ready(
        plan = plan,
        depIata = flight.depIata,
        depCity = flight.depAirport?.displayCity ?: flight.depIata,
        directionsUrl = directionsUrl(flight.depAirport, flight.depIata, flight.depTerminal),
        reminderAhead = plan.reminderAt > now,
    )
}

/** What the open screen does with the reminder job. */
sealed interface ReminderAction {
    data class Schedule(val at: Instant) : ReminderAction
    data object Cancel : ReminderAction
    data object Keep : ReminderAction
}

/** Schedule while the reminder time is ahead; cancel when there is no card for a known Pro state; else keep. */
fun reminderAction(state: WhenToLeaveUiState, proKnown: Boolean): ReminderAction = when (state) {
    is WhenToLeaveUiState.Ready -> if (state.reminderAhead) ReminderAction.Schedule(state.plan.reminderAt) else ReminderAction.Keep
    WhenToLeaveUiState.Locked -> ReminderAction.Cancel
    WhenToLeaveUiState.Hidden -> if (proKnown) ReminderAction.Cancel else ReminderAction.Keep
}
