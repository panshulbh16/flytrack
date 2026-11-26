package app.flytrack.feature.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.feature.passport.PassportRepository
import app.flytrack.feature.passport.PassportResult
import app.flytrack.feature.passport.PassportTab
import app.flytrack.feature.passport.passportToday
import app.flytrack.feature.passport.yearArgument
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** What the compare screen shows for one tab. */
sealed interface CompareContent {
    data object Loading : CompareContent

    data class Loaded(
        val mine: PassportSide,
        val friend: FriendPassport,
        val rows: List<CompareRow>,
        val sharedCountries: List<String>,
    ) : CompareContent

    /** The friend does not share their Passport (or is no longer a friend). */
    data object NotShared : CompareContent
    data object Offline : CompareContent
    data object NotConfigured : CompareContent
    data object Failed : CompareContent
}

data class FriendPassportUiState(
    val friendName: String?,
    val tab: PassportTab = PassportTab.OVERVIEW,
    val currentYear: Int,
    val content: CompareContent = CompareContent.Loading,
)

/** "You vs Asha" (P3 B10): the caller's Passport and the friend's, loaded together for the same period. */
@HiltViewModel(assistedFactory = FriendPassportViewModel.Factory::class)
class FriendPassportViewModel @AssistedInject constructor(
    @Assisted("friendId") private val friendId: String,
    @Assisted("friendName") friendName: String?,
    private val mine: PassportRepository,
    private val friends: FriendPassportRepository,
    clock: Clock,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("friendId") friendId: String, @Assisted("friendName") friendName: String?): FriendPassportViewModel
    }

    private val year = passportToday(clock.now()).year
    private val _uiState = MutableStateFlow(FriendPassportUiState(friendName = friendName, currentYear = year))
    val uiState: StateFlow<FriendPassportUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    init {
        load(PassportTab.OVERVIEW)
    }

    fun selectTab(tab: PassportTab) {
        if (tab != _uiState.value.tab) load(tab)
    }

    fun retry() = load(_uiState.value.tab)

    private fun load(tab: PassportTab) {
        job?.cancel()
        _uiState.update { it.copy(tab = tab, content = CompareContent.Loading) }
        job = viewModelScope.launch {
            val yearArg = tab.yearArgument(year)
            val friendCall = async { friends.friendPassport(friendId, yearArg, year) }
            val mineCall = async { mine.passport(yearArg, year) }
            val friendResult = friendCall.await()
            val mineResult = mineCall.await()
            val content = when {
                friendResult is FriendPassportResult.NotShared -> CompareContent.NotShared
                friendResult is FriendPassportResult.Loaded && mineResult is PassportResult.Loaded -> {
                    val me = mineResult.passport.toSide()
                    CompareContent.Loaded(
                        mine = me,
                        friend = friendResult.passport,
                        rows = comparePassports(me, friendResult.passport.side),
                        sharedCountries = sharedCountries(mineResult.passport.countries, friendResult.passport.countryCodes),
                    )
                }
                friendResult is FriendPassportResult.NotConfigured || mineResult is PassportResult.NotConfigured -> CompareContent.NotConfigured
                friendResult is FriendPassportResult.Offline || mineResult is PassportResult.Offline -> CompareContent.Offline
                else -> CompareContent.Failed
            }
            _uiState.update { state ->
                val name = (content as? CompareContent.Loaded)?.friend?.displayName ?: state.friendName
                state.copy(content = content, friendName = name)
            }
        }
    }
}
