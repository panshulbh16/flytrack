package app.flytrack.feature.intel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Flight
import app.flytrack.data.repo.FlightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The connection assistant on the flights list (B4): `my_connections` for Pro, a teaser for free users
 * who track a connecting itinerary. Asked again whenever a tracked flight's times or terminals change.
 */
@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: IntelRepository,
    flights: FlightRepository,
    clock: Clock,
) : ViewModel() {

    private val answer = MutableStateFlow<IntelResult<List<Connection>>?>(null)

    private val tracked = flights.observeTrackedFlights().map { list -> list.map { it.flight } }

    val uiState: StateFlow<ConnectionsUiState> = combine(answer, tracked) { a, f -> connectionsState(a, f, clock.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState.Hidden)

    init {
        viewModelScope.launch {
            tracked.map { list -> list.map { it.connectionKey() }.sortedBy { it.id } }
                .distinctUntilChanged()
                .collectLatest { keys -> if (keys.size >= 2) load() else answer.value = IntelResult.Ok(emptyList()) }
        }
    }

    /** Resume and pull-to-refresh. */
    fun refresh() {
        viewModelScope.launch {
            if (tracked.first().size >= 2) load()
        }
    }

    private suspend fun load() {
        val result = repository.connections()
        // Offline or a failure keeps the last answer on screen.
        if (result is IntelResult.Ok || result == IntelResult.Locked || answer.value == null) answer.value = result
    }
}

private data class ConnectionKey(
    val id: String,
    val dep: String,
    val arr: String,
    val times: List<Instant?>,
    val terminals: List<String?>,
    val status: String,
)

private fun Flight.connectionKey() = ConnectionKey(
    id = id,
    dep = depIata,
    arr = arrIata,
    times = listOf(schedDep, estDep, actDep, schedArr, estArr, actArr),
    terminals = listOf(depTerminal, arrTerminal),
    status = status.wire,
)
