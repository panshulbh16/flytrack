package app.flytrack.feature.intel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The delay-trends chart of one airport (B7, Pro). */
sealed interface TrendsUiState {
    data object Idle : TrendsUiState
    data object Loading : TrendsUiState
    data class Ready(val trends: TrendsUiModel) : TrendsUiState

    /** Nothing recorded for the airport in the window. */
    data object Empty : TrendsUiState
    data object Locked : TrendsUiState
    data object Offline : TrendsUiState
    data object Failed : TrendsUiState
}

/** Pure mapping of the repository answer. */
fun trendsState(result: IntelResult<AirportTrends?>): TrendsUiState = when (result) {
    is IntelResult.Ok -> result.value?.toUi()?.let { TrendsUiState.Ready(it) } ?: TrendsUiState.Empty
    IntelResult.Locked -> TrendsUiState.Locked
    IntelResult.Offline -> TrendsUiState.Offline
    // The app shell already shows a banner for a build without backend keys.
    IntelResult.NotConfigured -> TrendsUiState.Empty
    is IntelResult.Failed -> TrendsUiState.Failed
}

@HiltViewModel
class AirportTrendsViewModel @Inject constructor(
    private val repository: IntelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TrendsUiState>(TrendsUiState.Idle)
    val uiState: StateFlow<TrendsUiState> = _uiState.asStateFlow()

    private var job: Job? = null
    private var loaded: String? = null

    /** Loads the trends of [iata] once (again after a failure). */
    fun load(iata: String) {
        val current = _uiState.value
        if (loaded == iata && (current is TrendsUiState.Ready || current == TrendsUiState.Empty || current == TrendsUiState.Loading)) return
        loaded = iata
        job?.cancel()
        _uiState.value = TrendsUiState.Loading
        job = viewModelScope.launch { _uiState.value = trendsState(repository.airportTrends(iata)) }
    }
}
