package app.flytrack.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.flights.ticks
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Clock

/** What the map sheet shows. */
sealed interface FlightMapUiState {
    data object Loading : FlightMapUiState

    /** The flight is not cached, or its airports have no coordinates: nothing to draw. */
    data object Unavailable : FlightMapUiState

    data class Ready(val model: FlightMapUiModel) : FlightMapUiState
}

/**
 * Feeds the map from Room only (no network call): the plane moves on the 30-second tick from the
 * live fix while it is fresh, else from the shared estimate, so the map works in airplane mode.
 */
@HiltViewModel(assistedFactory = FlightMapViewModel.Factory::class)
class FlightMapViewModel @AssistedInject constructor(
    @Assisted flightId: String,
    flights: FlightRepository,
    clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): FlightMapViewModel
    }

    val uiState: StateFlow<FlightMapUiState> = combine(
        flights.observeTrackedFlight(flightId).map { it?.flight },
        flights.observeFlight(flightId),
        clock.ticks(),
    ) { tracked, cached, now ->
        val model = (tracked ?: cached)?.toMapUiModel(now)
        if (model == null) FlightMapUiState.Unavailable else FlightMapUiState.Ready(model)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlightMapUiState.Loading)
}
