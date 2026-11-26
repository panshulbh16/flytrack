package app.flytrack.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/** Why the alert settings cannot be shown or edited right now. */
enum class AlertPrefsProblem { OFFLINE, NOT_CONFIGURED, FAILED }

data class AlertPrefsUiState(
    /** Null until the server row was read once: the rows are shown disabled. */
    val prefs: AlertPrefs? = null,
    val loading: Boolean = true,
    val problem: AlertPrefsProblem? = null,
    /** A write failed and was rolled back: show it once. */
    val saveFailed: Boolean = false,
) {
    val editable: Boolean get() = prefs != null
}

/** Quiet hours and alert language rows of Settings (P2 contract, sections 3 and 4). */
@HiltViewModel
class AlertPrefsViewModel @Inject constructor(
    private val repository: AlertPrefsRepository,
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val problem = MutableStateFlow<AlertPrefsProblem?>(null)
    private val saveFailed = MutableStateFlow(false)

    val uiState: StateFlow<AlertPrefsUiState> = combine(repository.prefs, loading, problem, saveFailed) { prefs, isLoading, prob, failed ->
        AlertPrefsUiState(prefs = prefs, loading = isLoading && prefs == null, problem = prob.takeIf { prefs == null }, saveFailed = failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlertPrefsUiState(prefs = repository.prefs.value))

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            problem.value = repository.refresh().problem()
            loading.value = false
        }
    }

    fun setQuietHoursEnabled(enabled: Boolean) = update(AlertPrefsChange.QuietHoursEnabled(enabled))

    fun setQuietStart(time: LocalTime) = update(AlertPrefsChange.QuietStart(time))

    fun setQuietEnd(time: LocalTime) = update(AlertPrefsChange.QuietEnd(time))

    fun setLanguage(language: AlertLanguage) = update(AlertPrefsChange.Language(language))

    fun saveFailedShown() {
        saveFailed.value = false
    }

    private fun update(change: AlertPrefsChange) {
        if (repository.prefs.value == null) return
        viewModelScope.launch {
            if (repository.update(change) !is AlertPrefsResult.Loaded) saveFailed.value = true
        }
    }

    private fun AlertPrefsResult.problem(): AlertPrefsProblem? = when (this) {
        is AlertPrefsResult.Loaded -> null
        AlertPrefsResult.Offline -> AlertPrefsProblem.OFFLINE
        AlertPrefsResult.NotConfigured -> AlertPrefsProblem.NOT_CONFIGURED
        is AlertPrefsResult.Failed -> AlertPrefsProblem.FAILED
    }
}
