package app.flytrack.feature.passport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Clock

/** What one tab shows. */
sealed interface PassportContent {
    data object Loading : PassportContent
    data class Loaded(val passport: Passport) : PassportContent
    data object Offline : PassportContent
    data object NotConfigured : PassportContent
    data object Failed : PassportContent
}

data class PassportUiState(
    val tab: PassportTab = PassportTab.OVERVIEW,
    val currentYear: Int,
    val today: LocalDate,
    val content: PassportContent = PassportContent.Loading,
    /** True while a pull-to-refresh / retry reloads content that is already shown. */
    val refreshing: Boolean = false,
    /** The overview tab's label: "All time" once the server said so, else "Last 12 months" (free). */
    val overviewIsAllTime: Boolean = false,
) {
    val yearCard: YearInReviewCardModel?
        get() = (content as? PassportContent.Loaded)?.passport?.toYearInReviewCard(today)
}

@HiltViewModel
class PassportViewModel @Inject constructor(
    private val repository: PassportRepository,
    clock: Clock,
) : ViewModel() {

    private val today = passportToday(clock.now())
    private val loaded = mutableMapOf<PassportTab, PassportContent>()
    private var job: Job? = null

    private val _uiState = MutableStateFlow(PassportUiState(currentYear = today.year, today = today))
    val uiState: StateFlow<PassportUiState> = _uiState.asStateFlow()

    init {
        load(PassportTab.OVERVIEW, force = false)
    }

    fun selectTab(tab: PassportTab) {
        if (tab == _uiState.value.tab) return
        load(tab, force = false)
    }

    fun refresh() = load(_uiState.value.tab, force = true)

    private fun load(tab: PassportTab, force: Boolean) {
        val cached = loaded[tab]
        if (!force && cached is PassportContent.Loaded) {
            job?.cancel()
            _uiState.update { it.copy(tab = tab, content = cached, refreshing = false) }
            return
        }
        job?.cancel()
        _uiState.update {
            val showing = it.tab == tab && it.content is PassportContent.Loaded
            it.copy(tab = tab, content = if (showing) it.content else PassportContent.Loading, refreshing = showing)
        }
        job = viewModelScope.launch {
            val content = when (val result = repository.passport(tab.yearArgument(today.year), today.year)) {
                is PassportResult.Loaded -> PassportContent.Loaded(result.passport)
                PassportResult.Offline -> PassportContent.Offline
                PassportResult.NotConfigured -> PassportContent.NotConfigured
                is PassportResult.Failed -> PassportContent.Failed
            }
            if (content is PassportContent.Loaded) loaded[tab] = content
            _uiState.update { state ->
                // Keep what is on screen when a refresh fails; say nothing, the pull indicator just stops.
                val keep = state.refreshing && content !is PassportContent.Loaded
                state.copy(
                    content = if (keep) state.content else content,
                    refreshing = false,
                    overviewIsAllTime = if (tab == PassportTab.OVERVIEW && content is PassportContent.Loaded) {
                        content.passport.scope == PassportScope.ALL
                    } else {
                        state.overviewIsAllTime
                    },
                )
            }
        }
    }
}
