package app.flytrack.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.hasLanded
import app.flytrack.feature.flights.presentation.FlightCardUiModel
import app.flytrack.feature.flights.presentation.toCardUiModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One card of the "Friends' flights" section. */
data class FriendFlightUiModel(
    val friendId: String,
    /** null = the friend set no name: the screen says "Your friend". */
    val friendName: String?,
    val card: FlightCardUiModel,
)

/** The section is hidden while [flights] is empty: errors stay silent, it is an extra on the flights screen. */
data class FriendsFlightsUiState(val flights: List<FriendFlightUiModel> = emptyList())

/** Friends' flights for the flights screen. Refreshed when the screen resumes and on pull-to-refresh. */
@HiltViewModel
class FriendsFlightsViewModel @Inject constructor(
    private val repository: FriendsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val loaded = MutableStateFlow<List<FriendFlight>>(emptyList())
    private var refreshing = false

    val uiState: StateFlow<FriendsFlightsUiState> = combine(loaded, ticks()) { flights, now ->
        FriendsFlightsUiState(friendFlightCards(flights, now))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FriendsFlightsUiState())

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            // Offline or failing: keep what is on screen.
            repository.friendsFlights().valueOrNull()?.let { loaded.value = it }
            refreshing = false
        }
    }

    private fun ticks() = flow {
        while (true) {
            emit(clock.now())
            delay(30.seconds)
        }
    }
}

/**
 * Cards in the server's order (earliest departure first). Flights that landed more than [LANDED_SHOWN_FOR]
 * ago are dropped; the server keeps a departed flight for 24 h, which is long after it matters to friends.
 */
fun friendFlightCards(flights: List<FriendFlight>, now: Instant): List<FriendFlightUiModel> =
    flights.filter { ff ->
        val f = ff.flight
        val landedAt = f.actArr ?: f.estArr ?: f.schedArr
        !(f.hasLanded && landedAt != null && now - landedAt > LANDED_SHOWN_FOR)
    }.map { FriendFlightUiModel(it.friendId, it.friendName, it.flight.toCardUiModel(now)) }

private val LANDED_SHOWN_FOR = 6.hours

/** The "share with friends" switch of one flight on the detail screen. */
data class ShareWithFriendsUiState(
    /** null while unknown (loading, not tracked, offline): the row is hidden. */
    val share: Boolean? = null,
    val isSaving: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel(assistedFactory = ShareWithFriendsViewModel.Factory::class)
class ShareWithFriendsViewModel @AssistedInject constructor(
    @Assisted private val flightId: String,
    private val repository: FriendsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): ShareWithFriendsViewModel
    }

    private val _uiState = MutableStateFlow(ShareWithFriendsUiState())
    val uiState: StateFlow<ShareWithFriendsUiState> = _uiState.asStateFlow()

    /** Reads the row; called when the flight becomes tracked (again). */
    fun load() {
        viewModelScope.launch {
            val value = repository.shareWithFriends(flightId).valueOrNull()
            _uiState.update { if (it.isSaving) it else it.copy(share = value) }
        }
    }

    fun setShare(share: Boolean) {
        val before = _uiState.value
        if (before.isSaving || before.share == null || before.share == share) return
        _uiState.value = before.copy(share = share, isSaving = true, failed = false)
        viewModelScope.launch {
            val result = repository.setShareWithFriends(flightId, share)
            _uiState.update {
                when (result) {
                    is RemoteResult.Ok -> it.copy(share = result.value, isSaving = false)
                    else -> it.copy(share = before.share, isSaving = false, failed = result != RemoteResult.NotConfigured)
                }
            }
        }
    }

    fun failureShown() {
        _uiState.update { it.copy(failed = false) }
    }
}
