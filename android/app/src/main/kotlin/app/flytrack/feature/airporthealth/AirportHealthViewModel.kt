package app.flytrack.feature.airporthealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One airport on the card. */
data class AirportHealthEntry(val side: HealthSide, val health: AirportHealth)

/** Only airports that answered are listed: conditions are an extra, so errors stay silent. */
data class AirportHealthUiState(val entries: List<AirportHealthEntry> = emptyList()) {
    /** At least one answer came without the Pro detail: show the teaser instead. */
    val showTeaser: Boolean get() = entries.any { it.health.detail == null }
}

@HiltViewModel
class AirportHealthViewModel @Inject constructor(
    private val repository: AirportHealthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AirportHealthUiState())
    val uiState: StateFlow<AirportHealthUiState> = _uiState.asStateFlow()

    private var requested: List<Pair<HealthSide, String>> = emptyList()
    private var job: Job? = null

    /** Loads the given airports (side to IATA code); a repeat call with the same list does nothing unless [force]. */
    fun load(airports: List<Pair<HealthSide, String>>, force: Boolean = false) {
        if (!force && airports == requested) return
        requested = airports
        job?.cancel()
        if (airports.isEmpty()) {
            _uiState.value = AirportHealthUiState()
            return
        }
        job = viewModelScope.launch {
            val answers = airports.map { (side, iata) -> async { side to repository.health(iata, force) } }.awaitAll()
            _uiState.value = AirportHealthUiState(
                answers.mapNotNull { (side, result) -> (result as? AirportHealthResult.Loaded)?.let { AirportHealthEntry(side, it.health) } },
            )
        }
    }
}

/** The (side, IATA) pairs a flight's card asks for: [healthSides] of its phase, same airport only once. */
fun healthRequests(depIata: String, arrIata: String, sides: List<HealthSide>): List<Pair<HealthSide, String>> =
    sides.map { side -> side to (if (side == HealthSide.DEPARTURE) depIata else arrIata).trim().uppercase() }
        .filter { it.second.isNotEmpty() }
        .distinctBy { it.second }
