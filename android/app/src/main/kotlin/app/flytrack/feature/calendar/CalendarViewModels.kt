package app.flytrack.feature.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Flight
import app.flytrack.data.repo.FlightRepository
import app.flytrack.feature.friends.RemoteResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The flight "Add to calendar" writes; null hides the button. */
@HiltViewModel(assistedFactory = AddToCalendarViewModel.Factory::class)
class AddToCalendarViewModel @AssistedInject constructor(
    @Assisted flightId: String,
    flights: FlightRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): AddToCalendarViewModel
    }

    val flight: StateFlow<Flight?> = merge(flights.observeTrackedFlight(flightId).map { it?.flight }, flights.observeFlight(flightId))
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** What the feed buttons do once the URL is known. */
enum class FeedAction { SUBSCRIBE_GOOGLE, COPY, SHARE }

enum class FeedMessage { OFFLINE, FAILED, NOT_CONFIGURED, ROTATED }

data class CalendarFeedUiState(
    val url: String? = null,
    val isWorking: Boolean = false,
    /** The URL is ready for [FeedAction]: the screen acts on it once, then calls [CalendarFeedViewModel.actionHandled]. */
    val pending: Pair<FeedAction, String>? = null,
    val message: FeedMessage? = null,
)

/** Settings → calendar subscription. The feed URL is created on first use, not when Settings opens. */
@HiltViewModel
class CalendarFeedViewModel @Inject constructor(
    private val repository: CalendarFeedRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarFeedUiState())
    val uiState: StateFlow<CalendarFeedUiState> = _uiState.asStateFlow()

    fun act(action: FeedAction) {
        val state = _uiState.value
        if (state.isWorking) return
        state.url?.let { url ->
            _uiState.update { it.copy(pending = action to url) }
            return
        }
        load(action) { repository.feedUrl() }
    }

    /** New secret URL: the old one stops working in every calendar that subscribed to it. */
    fun rotate() {
        if (_uiState.value.isWorking) return
        load(null) { repository.rotate() }
    }

    fun actionHandled() {
        _uiState.update { it.copy(pending = null) }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    private fun load(action: FeedAction?, call: suspend () -> RemoteResult<String>) {
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val result = call()
            _uiState.update {
                when (result) {
                    is RemoteResult.Ok -> it.copy(
                        url = result.value,
                        isWorking = false,
                        pending = action?.let { a -> a to result.value },
                        message = if (action == null) FeedMessage.ROTATED else it.message,
                    )
                    RemoteResult.Offline -> it.copy(isWorking = false, message = FeedMessage.OFFLINE)
                    RemoteResult.NotConfigured -> it.copy(isWorking = false, message = FeedMessage.NOT_CONFIGURED)
                    // `not_configured` (55000): the project URL is not set on the server yet.
                    is RemoteResult.Refused -> it.copy(
                        isWorking = false,
                        message = if (result.code == "55000") FeedMessage.NOT_CONFIGURED else FeedMessage.FAILED,
                    )
                    is RemoteResult.Failed -> it.copy(isWorking = false, message = FeedMessage.FAILED)
                }
            }
        }
    }
}
