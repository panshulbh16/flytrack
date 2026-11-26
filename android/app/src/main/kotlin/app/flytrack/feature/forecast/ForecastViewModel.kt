package app.flytrack.feature.forecast

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.hasLanded
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.friends.RemoteResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** The arrival forecast block of the detail screen (P2 wave 2, section 4). */
sealed interface ForecastUiState {
    /** Nothing to show: loading, offline, landed, cancelled, or the server has no data. */
    data object Hidden : ForecastUiState

    /** Not Pro for this flight: the teaser. */
    data object Locked : ForecastUiState

    data class Ready(
        val history: ForecastHistory?,
        val forecast: ArrivalForecast?,
        /** Arrival airport zone: the forecast times are shown there. */
        val arrTz: String?,
        /** Scheduled arrival, to say "6 min after schedule". */
        val scheduledArrival: Instant?,
    ) : ForecastUiState
}

@HiltViewModel(assistedFactory = ForecastViewModel.Factory::class)
class ForecastViewModel @AssistedInject constructor(
    @Assisted private val flightId: String,
    private val repository: ForecastRepository,
    flights: FlightRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): ForecastViewModel
    }

    private val answer = MutableStateFlow<FlightForecast?>(null)

    private val flight = merge(flights.observeTrackedFlight(flightId).map { it?.flight }, flights.observeFlight(flightId))
        .distinctUntilChanged()

    val uiState: StateFlow<ForecastUiState> = combine(flight, answer) { f, a -> forecastState(f, a) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ForecastUiState.Hidden)

    init {
        // Asked again when the times the forecast starts from change (a delay, the take-off).
        viewModelScope.launch {
            flight.map { f -> f?.takeIf { it.wantsForecast() }?.let { listOf(it.estDep, it.actDep, it.estArr) } }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null) return@collect
                    when (val result = repository.forecast(flightId)) {
                        is RemoteResult.Ok -> answer.value = result.value
                        // Keep an earlier answer; errors stay silent (the block is an extra).
                        else -> Unit
                    }
                }
        }
    }
}

private fun Flight.wantsForecast(): Boolean =
    status != FlightStatus.CANCELLED && status != FlightStatus.DIVERTED && !hasLanded

/** Pure mapping of the flight and the server's answer to the block. */
fun forecastState(flight: Flight?, answer: FlightForecast?): ForecastUiState = when {
    flight == null || answer == null || !flight.wantsForecast() -> ForecastUiState.Hidden
    answer.detailLocked -> ForecastUiState.Locked
    answer.isEmpty -> ForecastUiState.Hidden
    else -> ForecastUiState.Ready(answer.history?.takeIf { it.samples > 0 }, answer.forecast, flight.arrAirport?.tz, flight.schedArr)
}
