package app.flytrack.feature.addflight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackSource
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.model.UserProfile
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.BadInputReason
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.core.time.today
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.gmailimport.GmailNotifier
import app.flytrack.feature.gmailimport.GmailSuggestionStore
import app.flytrack.feature.gmailimport.SuggestionStatus
import app.flytrack.feature.settings.DefaultAlertPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Add-flight flow: type a flight number and date, search, pick a leg, track it.
 *
 * The form is validated locally with the same parser as the server, so a search (they are
 * limited per day) is never spent on input the server would reject.
 */
@HiltViewModel(assistedFactory = AddFlightViewModel.Factory::class)
class AddFlightViewModel @AssistedInject constructor(
    @Assisted prefill: AddFlightPrefill,
    private val flights: FlightRepository,
    auth: AuthRepository,
    profiles: ProfileRepository,
    private val clock: Clock,
    homeTimeZone: HomeTimeZone,
    backendConfig: BackendConfig,
    private val gmailSuggestions: GmailSuggestionStore,
    private val gmailNotifier: GmailNotifier,
    private val defaultAlerts: DefaultAlertPreferences = DefaultAlertPreferences.AllOn,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(prefill: AddFlightPrefill): AddFlightViewModel
    }

    private val _uiState: MutableStateFlow<AddFlightUiState>
    val uiState: StateFlow<AddFlightUiState>

    private val _events = Channel<AddFlightEvent>(Channel.BUFFERED)
    val events: Flow<AddFlightEvent> = _events.receiveAsFlow()

    // A prefill without a source is a share (the route did not say which). An empty form was typed.
    private val source = prefill.source ?: if (prefill.isEmpty) TrackSource.MANUAL else TrackSource.SHARE
    private val gmailSuggestionId = prefill.gmailSuggestionId

    private var searchJob: Job? = null
    private var trackedByFlightId: Map<String, TrackedFlight> = emptyMap()
    private var profile: UserProfile? = null

    init {
        val today = clock.today(homeTimeZone.id)
        val selectable = selectableDates(today, clock.now())
        val resolved = resolvePrefill(prefill, selectable)
        _uiState = MutableStateFlow(
            AddFlightUiState(
                flightInput = resolved.flightInput,
                date = resolved.date ?: today.coerceIn(selectable),
                today = today,
                firstSelectableDate = selectable.start,
                lastSelectableDate = selectable.endInclusive,
                pnrInput = resolved.pnrInput,
                seatInput = sanitizeSeatInput(prefill.seat.orEmpty()),
                prefillNotice = resolved.notice,
                isFromTicket = !prefill.isEmpty,
            ).withFlightInput(resolved.flightInput),
        )
        uiState = _uiState.asStateFlow()

        viewModelScope.launch {
            auth.session.collect { session ->
                _uiState.update {
                    it.copy(
                        isBackendConfigured = session != SessionState.NotConfigured,
                        canRaiseSearchLimit = session is SessionState.SignedIn && session.isAnonymous &&
                            backendConfig.isGoogleSignInConfigured,
                    )
                }
            }
        }
        viewModelScope.launch {
            flights.observeTrackedFlights().collect { list ->
                trackedByFlightId = list.associate { it.flight.id to it.tracking }
                _uiState.update { it.copy(trackedFlightIds = trackedByFlightId.keys) }
            }
        }
        viewModelScope.launch { profiles.observeProfile().collect { profile = it } }

        if (resolved.autoSearch) onSearch()
    }

    // ---- form -------------------------------------------------------------------------------------

    fun onFlightInputChange(text: String) {
        val input = text.take(MAX_FLIGHT_INPUT_LENGTH)
        if (_uiState.value.isTracking || input == _uiState.value.flightInput) return
        resetSearch()
        _uiState.update { it.withFlightInput(input).copy(prefillNotice = null) }
    }

    fun onDateChange(date: LocalDate) {
        val state = _uiState.value
        if (state.isTracking || !state.isSelectable(date)) return
        if (date == state.date) {
            _uiState.update { it.copy(dateRejected = false) }
            return
        }
        resetSearch()
        _uiState.update { it.copy(date = date, dateRejected = false, prefillNotice = null) }
    }

    fun onPnrInputChange(text: String) {
        _uiState.update { it.copy(pnrInput = sanitizePnrInput(text), pnrInvalid = false).withoutDetailsNotice() }
    }

    fun onSeatInputChange(text: String) {
        _uiState.update { it.copy(seatInput = sanitizeSeatInput(text), seatInvalid = false).withoutDetailsNotice() }
    }

    /** A changed flight number or date makes whatever is shown below the form stale. */
    private fun resetSearch() {
        searchJob?.cancel()
        _uiState.update { it.copy(search = SearchUiState.Idle, trackNotice = null) }
    }

    // ---- search -----------------------------------------------------------------------------------

    /** Also the "Try again" action of every error state: those states only exist for the current input. */
    fun onSearch() {
        val state = _uiState.value
        if (state.isSearching || state.isTracking) return

        val number = parseFlightNumber(state.flightInput)
        val pnrOk = isPnrInputAcceptable(state.pnrInput)
        if (number == null || !pnrOk) {
            _uiState.update {
                it.copy(
                    flightError = when {
                        number != null -> null
                        checkFlightInput(it.flightInput) == FlightInputCheck.INVALID -> FlightInputError.NOT_A_FLIGHT_NUMBER
                        else -> FlightInputError.REQUIRED
                    },
                    pnrInvalid = !pnrOk,
                )
            }
            return
        }
        if (!state.isBackendConfigured) {
            _uiState.update { it.copy(search = SearchUiState.NotConfigured) }
            return
        }

        val query = SearchQuery(number.display, state.date)
        _uiState.update { it.copy(search = SearchUiState.Searching(query), trackNotice = null, dateRejected = false) }
        searchJob = viewModelScope.launch {
            val result = flights.search(query.flight, query.date)
            _uiState.update { it.withSearchResult(query, result) }
        }
    }

    private fun AddFlightUiState.withSearchResult(query: SearchQuery, result: SearchResult): AddFlightUiState = when (result) {
        is SearchResult.Found ->
            copy(search = if (result.legs.isEmpty()) SearchUiState.NotFound(query) else SearchUiState.Results(query, result.legs))
        SearchResult.NotFound -> copy(search = SearchUiState.NotFound(query))
        SearchResult.RateLimited -> copy(search = SearchUiState.RateLimited)
        SearchResult.ProviderError -> copy(search = SearchUiState.ProviderError)
        SearchResult.Offline -> copy(search = SearchUiState.Offline)
        SearchResult.NotConfigured -> copy(search = SearchUiState.NotConfigured)
        // The form validates like the server, so these only happen when the two disagree
        // (a newer server rule, or a phone clock that is days off).
        is SearchResult.BadInput -> when (result.reason) {
            BadInputReason.FLIGHT_NUMBER -> copy(search = SearchUiState.Idle, flightError = FlightInputError.NOT_A_FLIGHT_NUMBER)
            BadInputReason.DATE -> copy(search = SearchUiState.Idle, dateRejected = true)
            BadInputReason.OTHER -> copy(search = SearchUiState.Rejected)
        }
    }

    // ---- track ------------------------------------------------------------------------------------

    /** "Track" on a leg. Ignored while any track request runs, which also swallows a double tap. */
    fun onTrack(flightId: String) {
        if (_uiState.value.isTracking) return
        val extras = validatedExtras(flightId) ?: return
        _uiState.update { it.copy(trackingFlightId = flightId, trackNotice = null) }
        viewModelScope.launch {
            when (val result = flights.track(flightId, extras)) {
                is TrackResult.Tracked -> {
                    applyDefaultMutes(flightId)
                    finish(flightId)
                }
                // The local list was stale. The flight is tracked, but the details typed here are not on it yet.
                is TrackResult.AlreadyTracked -> saveDetailsAndFinish(result.tracking, extras)
                is TrackResult.TrackingLimitReached -> trackFailed(flightId, limitReached())
                is TrackResult.InvalidInput -> detailsRejected(flightId, result.field)
                TrackResult.FlightNotFound -> trackFailed(flightId, TrackProblem.FlightGone)
                TrackResult.Offline -> trackFailed(flightId, TrackProblem.Offline)
                TrackResult.NotConfigured -> trackFailed(flightId, TrackProblem.NotConfigured)
                is TrackResult.Failed -> trackFailed(flightId, TrackProblem.Failed)
            }
        }
    }

    /** "Open" on a leg the user tracks already. A PNR or seat typed in the form is saved to it first. */
    fun onOpenTracked(flightId: String) {
        if (_uiState.value.isTracking) return
        val existing = trackedByFlightId[flightId] ?: return onTrack(flightId)
        val extras = validatedExtras(flightId) ?: return
        _uiState.update { it.copy(trackingFlightId = flightId, trackNotice = null) }
        viewModelScope.launch { saveDetailsAndFinish(existing, extras) }
    }

    /** The form's optional details, or null (with the fields and the leg marked) when they are not acceptable. */
    private fun validatedExtras(flightId: String): TrackExtras? {
        val state = _uiState.value
        if (!isPnrInputAcceptable(state.pnrInput)) {
            _uiState.update { it.copy(pnrInvalid = true, trackNotice = TrackNotice(flightId, TrackProblem.InvalidDetails)) }
            return null
        }
        return TrackExtras(
            pnr = state.pnrInput.ifEmpty { null },
            seat = state.seatInput.ifEmpty { null },
            source = source,
        )
    }

    private suspend fun saveDetailsAndFinish(existing: TrackedFlight, typed: TrackExtras) {
        val flightId = existing.flightId
        val pnr = typed.pnr ?: existing.pnr
        val seat = typed.seat ?: existing.seat
        if (pnr == existing.pnr && seat == existing.seat) return finish(flightId)

        // updateTrackingDetails replaces all four fields, so the untouched ones are passed back as they are.
        val merged = TrackExtras(pnr = pnr, seat = seat, cabin = existing.cabin, source = existing.source, note = existing.note)
        when (val result = flights.updateTrackingDetails(flightId, merged)) {
            ActionResult.Success -> finish(flightId)
            is ActionResult.InvalidInput -> detailsRejected(flightId, result.field)
            ActionResult.Offline, ActionResult.NotConfigured, ActionResult.NotFound,
            ActionResult.TrackingLimitReached, is ActionResult.Failed,
            -> trackFailed(flightId, TrackProblem.DetailsNotSaved)
        }
    }

    private suspend fun applyDefaultMutes(flightId: String) {
        val muted = defaultAlerts.muted.first()
        if (muted.isEmpty()) return
        flights.updateNotifyPrefs(flightId, muted.associateWith { false })
    }

    private suspend fun finish(flightId: String) {
        // The flight is tracked now, so the Gmail suggestion it came from is done. Backing out never gets here.
        gmailSuggestionId?.let { id ->
            gmailSuggestions.setStatus(listOf(id), SuggestionStatus.ADDED)
            gmailNotifier.cancel(listOf(id))
        }
        // trackingFlightId stays set: the screen is about to close, and a late tap must not start a second request.
        _events.send(AddFlightEvent.Finished(flightId))
    }

    private fun trackFailed(flightId: String, problem: TrackProblem) {
        _uiState.update { it.copy(trackingFlightId = null, trackNotice = TrackNotice(flightId, problem)) }
    }

    private fun detailsRejected(flightId: String, field: TrackField) {
        _uiState.update {
            it.copy(
                trackingFlightId = null,
                pnrInvalid = it.pnrInvalid || field == TrackField.PNR,
                seatInvalid = it.seatInvalid || field == TrackField.SEAT,
                // The form has no note field, so a rejected note is not something the user can fix here.
                trackNotice = TrackNotice(flightId, if (field == TrackField.NOTE) TrackProblem.Failed else TrackProblem.InvalidDetails),
            )
        }
    }

    private fun limitReached(): TrackProblem.LimitReached {
        val now = clock.now()
        val isPro = profile?.isPro(now) == true
        return TrackProblem.LimitReached(
            limit = profile?.trackingLimit(now) ?: UserProfile.FREE_TRACKING_LIMIT,
            proLimit = UserProfile.PRO_TRACKING_LIMIT.takeUnless { isPro },
        )
    }
}

private fun AddFlightUiState.withFlightInput(input: String): AddFlightUiState {
    val check = checkFlightInput(input)
    return copy(
        flightInput = input,
        normalisedFlight = parseFlightNumber(input)?.display,
        // Only text that can no longer become a flight number is flagged while typing.
        flightError = FlightInputError.NOT_A_FLIGHT_NUMBER.takeIf { check == FlightInputCheck.INVALID },
    )
}

/** Editing PNR or seat answers an "invalid details" notice; other notices stay. */
private fun AddFlightUiState.withoutDetailsNotice(): AddFlightUiState =
    if (trackNotice?.problem == TrackProblem.InvalidDetails || trackNotice?.problem == TrackProblem.DetailsNotSaved) {
        copy(trackNotice = null)
    } else {
        this
    }
