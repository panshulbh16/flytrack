package app.flytrack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.share.SharedContentHolder
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.gmailimport.GmailNotifier
import app.flytrack.feature.gmailimport.GmailSuggestion
import app.flytrack.feature.gmailimport.GmailSuggestionStore
import app.flytrack.feature.gmailimport.SuggestionStatus
import app.flytrack.ui.nav.IncomingIntent
import app.flytrack.ui.nav.LaunchRequest
import app.flytrack.ui.nav.resolveLaunchRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the app shell should navigate because of an incoming intent. */
sealed interface ShellDestination {
    data class Flight(val flightId: String) : ShellDestination
    data object ImportTicket : ShellDestination

    /** Track on a Gmail suggestion notification: Add flight prefilled from the stored suggestion. */
    data class AddFromGmail(val suggestion: GmailSuggestion) : ShellDestination
}

/** App-shell state: session bootstrap on launch and routing of incoming intents. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val flights: FlightRepository,
    private val profile: ProfileRepository,
    private val sharedContent: SharedContentHolder,
    private val gmailSuggestions: GmailSuggestionStore,
    private val gmailNotifier: GmailNotifier,
) : ViewModel() {

    val session: StateFlow<SessionState> = auth.session

    // A channel, not a StateFlow: each intent must navigate exactly once, also across rotation.
    private val _destinations = Channel<ShellDestination>(Channel.BUFFERED)
    val destinations: Flow<ShellDestination> = _destinations.receiveAsFlow()

    init {
        viewModelScope.launch {
            // First launch signs in anonymously; then one sync so the list is fresh without any screen asking.
            if (auth.ensureSignedIn().isSuccess) {
                launch { flights.refresh() }
                launch { profile.refresh() }
            }
        }
    }

    fun onIntent(intent: IncomingIntent) {
        when (val request = resolveLaunchRequest(intent)) {
            is LaunchRequest.OpenFlight -> {
                _destinations.trySend(ShellDestination.Flight(request.flightId))
                // The tap usually follows a push: make sure the detail screen shows the change it announced.
                viewModelScope.launch { flights.applyServerSync(request.flightId) }
            }
            is LaunchRequest.ImportShared -> {
                sharedContent.offer(request.content)
                _destinations.trySend(ShellDestination.ImportTicket)
            }
            is LaunchRequest.OpenGmailSuggestion -> {
                // Notification action buttons do not auto-cancel, so Track removes its notification here.
                gmailNotifier.cancel(listOf(request.suggestionId))
                viewModelScope.launch {
                    // Deleted, dismissed or already added since the notification was posted: just open the app.
                    val suggestion = gmailSuggestions.get(request.suggestionId)
                    if (suggestion?.status == SuggestionStatus.PENDING) {
                        _destinations.trySend(ShellDestination.AddFromGmail(suggestion))
                    }
                }
            }
            null -> Unit
        }
    }
}
