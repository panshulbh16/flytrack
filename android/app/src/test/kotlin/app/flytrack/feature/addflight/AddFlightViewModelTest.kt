package app.flytrack.feature.addflight

import app.cash.turbine.test
import app.flytrack.core.model.Flight
import app.flytrack.core.model.Plan
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackSource
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.BadInputReason
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.gmailimport.SuggestionStatus
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.days

class AddFlightViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val fake = FakeFlightRepository()
    private val flights = GatedFlightRepository(fake)
    private val auth = FakeAuthRepository()
    private val profiles = FakeProfileRepository()
    private val clock = FakeClock() // 22 Nov 2026, 15:30 in India
    private val gmailStore = FakeGmailSuggestionStore()
    private val gmailNotifier = FakeGmailNotifier()

    private val today = LocalDate.parse("2026-11-22")
    private val delBom = TestData.flight()
    private val bomGoi = TestData.flight(
        id = TestData.OTHER_FLIGHT_ID,
        depAirport = TestData.BOM,
        arrAirport = TestData.BLR,
    )
    private val query = SearchQuery("6E 2341", today)

    private fun viewModel(
        prefill: AddFlightPrefill = AddFlightPrefill(),
        googleWebClientId: String = "web-client-id",
        auth: FakeAuthRepository = this.auth,
    ) = AddFlightViewModel(
        prefill = prefill,
        flights = flights,
        auth = auth,
        profiles = profiles,
        clock = clock,
        homeTimeZone = HomeTimeZone { "Asia/Kolkata" },
        backendConfig = BackendConfig("https://example.supabase.co", "anon-key", googleWebClientId),
        gmailSuggestions = gmailStore,
        gmailNotifier = gmailNotifier,
    )

    /** A view model showing [legs] as the result of a search for 6E 2341 today. */
    private fun viewModelWithResults(vararg legs: Flight = arrayOf(delBom)): AddFlightViewModel {
        fake.searchResult = SearchResult.Found(legs.toList())
        return viewModel().apply {
            onFlightInputChange("6e2341")
            onSearch()
        }
    }

    private val AddFlightViewModel.state get() = uiState.value

    // ---- initial state ----------------------------------------------------------------------------

    @Test
    fun `starts idle on today's date in the phone's zone with the searchable window`() {
        val state = viewModel().state

        assertEquals("", state.flightInput)
        assertNull(state.normalisedFlight)
        assertNull(state.flightError)
        assertEquals(today, state.date)
        assertEquals(today, state.today)
        assertEquals(LocalDate.parse("2026-11-21"), state.firstSelectableDate)
        assertEquals(LocalDate.parse("2027-11-22"), state.lastSelectableDate)
        assertEquals(SearchUiState.Idle, state.search)
        assertNull(state.prefillNotice)
        assertFalse(state.isTracking)
        assertFalse(state.isFromTicket)
        assertTrue(state.isBackendConfigured)
    }

    @Test
    fun `today follows the phone's time zone, not UTC`() {
        // 22 Nov 10:00 UTC is already 23 Nov on Kiritimati (UTC+14).
        val vm = AddFlightViewModel(
            AddFlightPrefill(), flights, auth, profiles, clock, HomeTimeZone { "Pacific/Kiritimati" },
            BackendConfig("https://example.supabase.co", "anon-key", ""),
            gmailStore, gmailNotifier,
        )

        assertEquals(LocalDate.parse("2026-11-23"), vm.state.today)
        assertEquals(LocalDate.parse("2026-11-23"), vm.state.date)
        assertEquals(LocalDate.parse("2026-11-22"), vm.state.firstSelectableDate)
    }

    // ---- live validation --------------------------------------------------------------------------

    @Test
    fun `a complete flight number shows its normalised form`() {
        val vm = viewModel()

        vm.onFlightInputChange("ai101")

        assertEquals("ai101", vm.state.flightInput)
        assertEquals("AI 101", vm.state.normalisedFlight)
        assertNull(vm.state.flightError)
    }

    @Test
    fun `unfinished typing is not an error, impossible text is`() {
        val vm = viewModel()

        vm.onFlightInputChange("6E")
        assertNull(vm.state.normalisedFlight)
        assertNull(vm.state.flightError)

        vm.onFlightInputChange("6EX")
        assertNull(vm.state.normalisedFlight)
        assertEquals(FlightInputError.NOT_A_FLIGHT_NUMBER, vm.state.flightError)

        vm.onFlightInputChange("6E 2341")
        assertEquals("6E 2341", vm.state.normalisedFlight)
        assertNull(vm.state.flightError)
    }

    @Test
    fun `very long pasted text is cut`() {
        val vm = viewModel()

        vm.onFlightInputChange("x".repeat(200))

        assertEquals(MAX_FLIGHT_INPUT_LENGTH, vm.state.flightInput.length)
    }

    @Test
    fun `pnr and seat are tidied while typing`() {
        val vm = viewModel()

        vm.onPnrInputChange("ab c-123")
        vm.onSeatInputChange("12 a")

        assertEquals("ABC123", vm.state.pnrInput)
        assertEquals("12A", vm.state.seatInput)
    }

    // ---- search: validation -----------------------------------------------------------------------

    @Test
    fun `searching with an empty or unfinished flight number asks for one and calls nothing`() {
        val vm = viewModel()

        vm.onSearch()
        assertEquals(FlightInputError.REQUIRED, vm.state.flightError)

        vm.onFlightInputChange("6E")
        assertNull(vm.state.flightError)
        vm.onSearch()
        assertEquals(FlightInputError.REQUIRED, vm.state.flightError)

        assertEquals(SearchUiState.Idle, vm.state.search)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `searching with impossible text keeps the specific error and calls nothing`() {
        val vm = viewModel()
        vm.onFlightInputChange("indigo")

        vm.onSearch()

        assertEquals(FlightInputError.NOT_A_FLIGHT_NUMBER, vm.state.flightError)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `typing again clears the required error`() {
        val vm = viewModel()
        vm.onSearch()

        vm.onFlightInputChange("6")

        assertNull(vm.state.flightError)
    }

    @Test
    fun `an unfinished pnr blocks the search until it is fixed`() {
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")
        vm.onPnrInputChange("AB1")

        vm.onSearch()
        assertTrue(vm.state.pnrInvalid)
        assertNull(vm.state.flightError)
        assertTrue(fake.searches.isEmpty())

        vm.onPnrInputChange("AB123")
        assertFalse(vm.state.pnrInvalid)
        vm.onSearch()
        assertEquals(1, fake.searches.size)
    }

    // ---- search: states ---------------------------------------------------------------------------

    @Test
    fun `search sends the normalised number and the date, shows searching, then the legs`() {
        fake.searchResult = SearchResult.Found(listOf(delBom, bomGoi))
        flights.searchGate = CompletableDeferred()
        val vm = viewModel()
        vm.onFlightInputChange(" 6e-2341 ")
        vm.onDateChange(LocalDate.parse("2026-11-25"))

        vm.onSearch()

        val sent = SearchQuery("6E 2341", LocalDate.parse("2026-11-25"))
        assertEquals(SearchUiState.Searching(sent), vm.state.search)
        assertTrue(vm.state.isSearching)

        flights.searchGate!!.complete(Unit)

        assertEquals(listOf("6E 2341" to LocalDate.parse("2026-11-25")), fake.searches)
        assertEquals(SearchUiState.Results(sent, listOf(delBom, bomGoi)), vm.state.search)
        assertFalse(vm.state.isSearching)
    }

    @Test
    fun `a second tap on search while one runs is ignored`() {
        flights.searchGate = CompletableDeferred()
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")

        vm.onSearch()
        vm.onSearch()
        flights.searchGate!!.complete(Unit)

        assertEquals(1, fake.searches.size)
    }

    @Test
    fun `search outcomes map to their states`() {
        val expected = mapOf(
            SearchResult.NotFound to SearchUiState.NotFound(query),
            SearchResult.Found(emptyList()) to SearchUiState.NotFound(query),
            SearchResult.RateLimited to SearchUiState.RateLimited,
            SearchResult.ProviderError to SearchUiState.ProviderError,
            SearchResult.Offline to SearchUiState.Offline,
            SearchResult.NotConfigured to SearchUiState.NotConfigured,
            SearchResult.BadInput(BadInputReason.OTHER) to SearchUiState.Rejected,
        )
        expected.forEach { (result, state) ->
            fake.searchResult = result
            val vm = viewModel()
            vm.onFlightInputChange("6E 2341")

            vm.onSearch()

            assertEquals(result.toString(), state, vm.state.search)
            assertNull(result.toString(), vm.state.flightError)
            assertFalse(result.toString(), vm.state.dateRejected)
        }
    }

    @Test
    fun `a flight number the server rejects is shown on the field`() {
        fake.searchResult = SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER)
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")

        vm.onSearch()

        assertEquals(SearchUiState.Idle, vm.state.search)
        assertEquals(FlightInputError.NOT_A_FLIGHT_NUMBER, vm.state.flightError)
    }

    @Test
    fun `a date the server rejects is shown on the date until it changes`() {
        fake.searchResult = SearchResult.BadInput(BadInputReason.DATE)
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")

        vm.onSearch()
        assertEquals(SearchUiState.Idle, vm.state.search)
        assertTrue(vm.state.dateRejected)

        vm.onDateChange(today.plusDays(1))
        assertFalse(vm.state.dateRejected)
    }

    @Test
    fun `try again after a provider error searches again`() {
        fake.searchResult = SearchResult.ProviderError
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")
        vm.onSearch()
        assertEquals(SearchUiState.ProviderError, vm.state.search)

        fake.searchResult = SearchResult.Found(listOf(delBom))
        vm.onSearch()

        assertEquals(2, fake.searches.size)
        assertEquals(SearchUiState.Results(query, listOf(delBom)), vm.state.search)
    }

    @Test
    fun `try again after being offline searches again`() {
        fake.searchResult = SearchResult.Offline
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")
        vm.onSearch()

        fake.searchResult = SearchResult.NotFound
        vm.onSearch()

        assertEquals(SearchUiState.NotFound(query), vm.state.search)
    }

    // ---- search: editing the form -----------------------------------------------------------------

    @Test
    fun `changing the flight number or the date clears what the last search showed`() {
        val vm = viewModelWithResults()
        assertTrue(vm.state.search is SearchUiState.Results)

        vm.onFlightInputChange("6E 2342")
        assertEquals(SearchUiState.Idle, vm.state.search)

        vm.onSearch()
        assertTrue(vm.state.search is SearchUiState.Results)
        vm.onDateChange(today.plusDays(1))
        assertEquals(SearchUiState.Idle, vm.state.search)
    }

    @Test
    fun `re-selecting the same value keeps the results, and so does editing pnr or seat`() {
        val vm = viewModelWithResults()

        vm.onFlightInputChange("6e2341")
        vm.onDateChange(today)
        vm.onPnrInputChange("ABC123")
        vm.onSeatInputChange("12A")

        assertTrue(vm.state.search is SearchUiState.Results)
    }

    @Test
    fun `editing while a search runs cancels it`() {
        fake.searchResult = SearchResult.Found(listOf(delBom))
        flights.searchGate = CompletableDeferred()
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")
        vm.onSearch()

        vm.onFlightInputChange("6E 234")
        flights.searchGate!!.complete(Unit)

        assertEquals(SearchUiState.Idle, vm.state.search)
        assertFalse(vm.state.isSearching)
    }

    @Test
    fun `a date outside the searchable window is ignored`() {
        val vm = viewModel()

        vm.onDateChange(today.plusDays(-2))
        vm.onDateChange(LocalDate.parse("2027-11-23"))
        assertEquals(today, vm.state.date)

        vm.onDateChange(today.plusDays(-1))
        assertEquals(LocalDate.parse("2026-11-21"), vm.state.date)
        vm.onDateChange(LocalDate.parse("2027-11-22"))
        assertEquals(LocalDate.parse("2027-11-22"), vm.state.date)
    }

    // ---- backend and account ----------------------------------------------------------------------

    @Test
    fun `without backend keys the search is not attempted`() {
        val vm = viewModel(auth = FakeAuthRepository.notConfigured())
        assertFalse(vm.state.isBackendConfigured)
        vm.onFlightInputChange("6E 2341")

        vm.onSearch()

        assertEquals(SearchUiState.NotConfigured, vm.state.search)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `only an anonymous user who can link google is told how to raise the search limit`() {
        assertTrue(viewModel().state.canRaiseSearchLimit)
        assertFalse(viewModel(googleWebClientId = "").state.canRaiseSearchLimit)

        val vm = viewModel()
        auth.setSession(SessionState.SignedIn(TestData.USER_ID, isAnonymous = false, email = "user@example.com"))
        assertFalse(vm.state.canRaiseSearchLimit)
    }

    // ---- prefill ----------------------------------------------------------------------------------

    @Test
    fun `a readable ticket fills the form and searches on its own`() {
        fake.searchResult = SearchResult.Found(listOf(delBom))

        val vm = viewModel(AddFlightPrefill("6e 2341", "2026-11-25", "abc-123"))

        assertEquals("6e 2341", vm.state.flightInput)
        assertEquals("6E 2341", vm.state.normalisedFlight)
        assertEquals(LocalDate.parse("2026-11-25"), vm.state.date)
        assertEquals("ABC123", vm.state.pnrInput)
        assertEquals(PrefillNotice.CheckDetails, vm.state.prefillNotice)
        assertTrue(vm.state.isFromTicket)
        assertEquals(listOf("6E 2341" to LocalDate.parse("2026-11-25")), fake.searches)
        assertEquals(SearchUiState.Results(SearchQuery("6E 2341", LocalDate.parse("2026-11-25")), listOf(delBom)), vm.state.search)
    }

    @Test
    fun `a ticket without a usable date fills the form but does not spend a search`() {
        val vm = viewModel(AddFlightPrefill("6E 2341", null, "ABC123"))

        assertEquals("6E 2341", vm.state.flightInput)
        assertEquals(today, vm.state.date)
        assertEquals(PrefillNotice.DateUnreadable, vm.state.prefillNotice)
        assertEquals(SearchUiState.Idle, vm.state.search)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `an old ticket keeps today's date and says why`() {
        val vm = viewModel(AddFlightPrefill("6E 2341", "2025-01-12", null))

        assertEquals(today, vm.state.date)
        assertEquals(PrefillNotice.DateOutOfRange(LocalDate.parse("2025-01-12")), vm.state.prefillNotice)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `an unreadable flight number from a ticket is flagged and not searched`() {
        val vm = viewModel(AddFlightPrefill("IndiGo flight", "2026-11-25", null))

        assertEquals("IndiGo flight", vm.state.flightInput)
        assertEquals(FlightInputError.NOT_A_FLIGHT_NUMBER, vm.state.flightError)
        assertEquals(LocalDate.parse("2026-11-25"), vm.state.date)
        assertEquals(PrefillNotice.FlightUnreadable, vm.state.prefillNotice)
        assertTrue(fake.searches.isEmpty())
    }

    @Test
    fun `the ticket note goes away once the user edits flight or date`() {
        val vm = viewModel(AddFlightPrefill("6E 2341", null, null))

        vm.onPnrInputChange("ABC123")
        assertEquals(PrefillNotice.DateUnreadable, vm.state.prefillNotice)

        vm.onDateChange(today.plusDays(3))
        assertNull(vm.state.prefillNotice)
        assertTrue(vm.state.isFromTicket)
    }

    @Test
    fun `a prefilled search without backend keys shows not configured`() {
        val vm = viewModel(AddFlightPrefill("6E 2341", "2026-11-25", null), auth = FakeAuthRepository.notConfigured())

        assertEquals(SearchUiState.NotConfigured, vm.state.search)
        assertTrue(fake.searches.isEmpty())
    }

    // ---- track ------------------------------------------------------------------------------------

    @Test
    fun `track sends the typed details and finishes with the flight id`() = runTest {
        val vm = viewModelWithResults()
        vm.onPnrInputChange("abc123")
        vm.onSeatInputChange("12a")

        vm.events.test {
            vm.onTrack(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }

        assertEquals(listOf(delBom.id to TrackExtras(pnr = "ABC123", seat = "12A", source = TrackSource.MANUAL)), fake.trackCalls)
        // The screen is closing: the button stays busy and further taps do nothing.
        assertEquals(delBom.id, vm.state.trackingFlightId)
        vm.onTrack(delBom.id)
        assertEquals(1, fake.trackCalls.size)
    }

    @Test
    fun `track without details sends none`() = runTest {
        val vm = viewModelWithResults()

        vm.onTrack(delBom.id)

        assertEquals(listOf(delBom.id to TrackExtras()), fake.trackCalls)
    }

    @Test
    fun `a flight added from a ticket is not recorded as typed by hand`() = runTest {
        fake.searchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel(AddFlightPrefill("6E 2341", "2026-11-25", "ABC123"))

        vm.onTrack(delBom.id)

        assertEquals(listOf(delBom.id to TrackExtras(pnr = "ABC123", source = TrackSource.SHARE)), fake.trackCalls)
    }

    @Test
    fun `tracking a gmail suggestion records gmail and marks the suggestion added`() = runTest {
        fake.searchResult = SearchResult.Found(listOf(delBom))
        val suggestion = GmailFixtures.suggestion("6E 2341", LocalDate(2026, 11, 25))
        gmailStore.insert(listOf(suggestion))
        val vm = viewModel(
            AddFlightPrefill("6E 2341", "2026-11-25", "ABC123", source = TrackSource.GMAIL, gmailSuggestionId = suggestion.id),
        )

        vm.events.test {
            vm.onTrack(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }

        assertEquals(TrackSource.GMAIL, fake.trackCalls.single().second.source)
        assertEquals(SuggestionStatus.ADDED, gmailStore.get(suggestion.id)?.status)
        assertEquals(listOf(suggestion.id), gmailNotifier.cancelled)
    }

    @Test
    fun `a failed track leaves the gmail suggestion pending`() = runTest {
        fake.searchResult = SearchResult.Found(listOf(delBom))
        fake.trackResult = TrackResult.Offline
        val suggestion = GmailFixtures.suggestion("6E 2341", LocalDate(2026, 11, 25))
        gmailStore.insert(listOf(suggestion))
        val vm = viewModel(
            AddFlightPrefill("6E 2341", "2026-11-25", null, source = TrackSource.GMAIL, gmailSuggestionId = suggestion.id),
        )

        vm.onTrack(delBom.id)

        assertEquals(TrackProblem.Offline, vm.state.trackNotice?.problem)
        assertEquals(SuggestionStatus.PENDING, gmailStore.get(suggestion.id)?.status)
        assertTrue(gmailNotifier.cancelled.isEmpty())
    }

    @Test
    fun `a pdf import is stored as pdf and default mutes are applied`() = runTest {
        fake.searchResult = SearchResult.Found(listOf(delBom))
        val defaults = object : app.flytrack.feature.settings.DefaultAlertPreferences {
            override val muted = kotlinx.coroutines.flow.flowOf(setOf(app.flytrack.core.model.AlertType.DEPARTED))
            override suspend fun setMuted(types: Set<app.flytrack.core.model.AlertType>, muted: Boolean) = Unit
        }
        val vm = AddFlightViewModel(
            prefill = AddFlightPrefill("6E 2341", "2026-11-25", null, source = TrackSource.PDF),
            flights = flights,
            auth = auth,
            profiles = profiles,
            clock = clock,
            homeTimeZone = HomeTimeZone { "Asia/Kolkata" },
            backendConfig = app.flytrack.data.remote.BackendConfig("https://example.supabase.co", "anon-key", "web"),
            gmailSuggestions = gmailStore,
            gmailNotifier = gmailNotifier,
            defaultAlerts = defaults,
        )

        vm.onTrack(delBom.id)

        assertEquals(TrackSource.PDF, fake.trackCalls.single().second.source)
        assertEquals(
            mapOf(app.flytrack.core.model.AlertType.DEPARTED to false),
            fake.tracked.value.single().tracking.notifyPrefs,
        )
    }

    @Test
    fun `a double tap on track sends one request`() = runTest {
        flights.trackGate = CompletableDeferred()
        val vm = viewModelWithResults(delBom, bomGoi)

        vm.events.test {
            vm.onTrack(delBom.id)
            vm.onTrack(delBom.id)
            vm.onTrack(bomGoi.id)
            vm.onOpenTracked(bomGoi.id)
            assertEquals(delBom.id, vm.state.trackingFlightId)
            expectNoEvents()

            flights.trackGate!!.complete(Unit)

            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
            expectNoEvents()
        }
        assertEquals(listOf(delBom.id), fake.trackCalls.map { it.first })
    }

    @Test
    fun `the form cannot be changed or searched while a flight is being added`() = runTest {
        flights.trackGate = CompletableDeferred()
        val vm = viewModelWithResults()
        vm.onTrack(delBom.id)

        vm.onFlightInputChange("AI 101")
        vm.onDateChange(today.plusDays(1))
        vm.onSearch()

        assertEquals("6e2341", vm.state.flightInput)
        assertEquals(today, vm.state.date)
        assertTrue(vm.state.search is SearchUiState.Results)
        assertEquals(1, fake.searches.size)
    }

    @Test
    fun `legs the user already tracks are marked, and the mark follows the list`() = runTest {
        fake.setTracked(TestData.tracked(delBom))
        val vm = viewModelWithResults(delBom, bomGoi)

        assertTrue(vm.state.isTracked(delBom.id))
        assertFalse(vm.state.isTracked(bomGoi.id))

        fake.setTracked(TestData.tracked(delBom), TestData.tracked(bomGoi))
        assertTrue(vm.state.isTracked(bomGoi.id))
    }

    @Test
    fun `an archived flight is offered for tracking again`() = runTest {
        fake.setTracked(TestData.tracked(delBom, TestData.tracking(delBom.id, archivedAt = TestData.NOW)))
        val vm = viewModelWithResults()

        assertFalse(vm.state.isTracked(delBom.id))
    }

    // ---- track: failures --------------------------------------------------------------------------

    private suspend fun problemFor(result: TrackResult, vm: AddFlightViewModel = viewModelWithResults()): TrackProblem? {
        fake.trackResult = result
        vm.events.test {
            vm.onTrack(delBom.id)
            expectNoEvents()
        }
        assertNull("the button must be usable again", vm.state.trackingFlightId)
        assertEquals(delBom.id, vm.state.trackNotice?.flightId)
        return vm.state.trackNotice?.problem
    }

    @Test
    fun `the free tracking limit names both limits`() = runTest {
        assertEquals(TrackProblem.LimitReached(limit = 10, proLimit = 50), problemFor(TrackResult.TrackingLimitReached("hint")))
    }

    @Test
    fun `the pro tracking limit names only its own`() = runTest {
        profiles.profile.value = TestData.profile(plan = Plan.PRO)

        assertEquals(TrackProblem.LimitReached(limit = 50, proLimit = null), problemFor(TrackResult.TrackingLimitReached()))
    }

    @Test
    fun `an expired pro plan counts as free, and so does an unknown profile`() = runTest {
        profiles.profile.value = TestData.profile(plan = Plan.PRO, proUntil = TestData.NOW - 1.days)
        assertEquals(TrackProblem.LimitReached(10, 50), problemFor(TrackResult.TrackingLimitReached()))

        profiles.profile.value = null
        assertEquals(TrackProblem.LimitReached(10, 50), problemFor(TrackResult.TrackingLimitReached()))
    }

    @Test
    fun `track failures map to their problems`() = runTest {
        assertEquals(TrackProblem.Offline, problemFor(TrackResult.Offline))
        assertEquals(TrackProblem.FlightGone, problemFor(TrackResult.FlightNotFound))
        assertEquals(TrackProblem.NotConfigured, problemFor(TrackResult.NotConfigured))
        assertEquals(TrackProblem.Failed, problemFor(TrackResult.Failed("boom")))
    }

    @Test
    fun `details the server rejects are marked on their field`() = runTest {
        val pnr = viewModelWithResults()
        assertEquals(TrackProblem.InvalidDetails, problemFor(TrackResult.InvalidInput(TrackField.PNR), pnr))
        assertTrue(pnr.state.pnrInvalid)
        assertFalse(pnr.state.seatInvalid)

        val seat = viewModelWithResults()
        assertEquals(TrackProblem.InvalidDetails, problemFor(TrackResult.InvalidInput(TrackField.SEAT), seat))
        assertTrue(seat.state.seatInvalid)
        assertFalse(seat.state.pnrInvalid)

        // The form has no note field, so there is nothing for the user to correct.
        val note = viewModelWithResults()
        assertEquals(TrackProblem.Failed, problemFor(TrackResult.InvalidInput(TrackField.NOTE), note))
        assertFalse(note.state.pnrInvalid || note.state.seatInvalid)
    }

    @Test
    fun `after a failure the same leg can be tracked again`() = runTest {
        val vm = viewModelWithResults()
        assertEquals(TrackProblem.Offline, problemFor(TrackResult.Offline, vm))

        fake.trackResult = null
        vm.events.test {
            vm.onTrack(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }
        assertEquals(2, fake.trackCalls.size)
    }

    @Test
    fun `a notice is cleared by the next attempt and by a new search`() = runTest {
        val vm = viewModelWithResults()
        problemFor(TrackResult.Offline, vm)

        vm.onSearch()

        assertNull(vm.state.trackNotice)
    }

    @Test
    fun `an unfinished pnr stops track before any request and points at the field`() = runTest {
        val vm = viewModelWithResults()
        vm.onPnrInputChange("AB1")

        vm.onTrack(delBom.id)

        assertTrue(fake.trackCalls.isEmpty())
        assertTrue(vm.state.pnrInvalid)
        assertEquals(TrackNotice(delBom.id, TrackProblem.InvalidDetails), vm.state.trackNotice)
        assertNull(vm.state.trackingFlightId)

        vm.onPnrInputChange("AB123")
        assertFalse(vm.state.pnrInvalid)
        assertNull(vm.state.trackNotice)
    }

    @Test
    fun `editing the pnr keeps a notice that is not about the details`() = runTest {
        val vm = viewModelWithResults()
        problemFor(TrackResult.Offline, vm)

        vm.onPnrInputChange("ABC123")

        assertEquals(TrackProblem.Offline, vm.state.trackNotice?.problem)
    }

    // ---- already tracked --------------------------------------------------------------------------

    @Test
    fun `opening a tracked leg without new details needs no request`() = runTest {
        fake.setTracked(TestData.tracked(delBom, TestData.tracking(delBom.id, pnr = "OLD123", seat = "1A")))
        fake.actionResult = ActionResult.Failed("must not be called")
        val vm = viewModelWithResults()

        vm.events.test {
            vm.onOpenTracked(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }
        assertTrue(fake.trackCalls.isEmpty())
        assertEquals("OLD123", fake.tracked.value.single().tracking.pnr)
    }

    @Test
    fun `opening a tracked leg saves a newly typed pnr and keeps everything else`() = runTest {
        fake.setTracked(
            TestData.tracked(delBom, TestData.tracking(delBom.id, seat = "1A", note = "window", source = TrackSource.PDF)),
        )
        val vm = viewModelWithResults()
        vm.onPnrInputChange("new123")

        vm.events.test {
            vm.onOpenTracked(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }

        val tracking = fake.tracked.value.single().tracking
        assertEquals("NEW123", tracking.pnr)
        assertEquals("1A", tracking.seat)
        assertEquals("window", tracking.note)
        assertEquals(TrackSource.PDF, tracking.source)
    }

    @Test
    fun `details that cannot be saved keep the screen open and say so`() = runTest {
        fake.setTracked(TestData.tracked(delBom))
        fake.actionResult = ActionResult.Offline
        val vm = viewModelWithResults()
        vm.onSeatInputChange("14C")

        vm.events.test {
            vm.onOpenTracked(delBom.id)
            expectNoEvents()
        }
        assertEquals(TrackNotice(delBom.id, TrackProblem.DetailsNotSaved), vm.state.trackNotice)
        assertNull(vm.state.trackingFlightId)

        // Clearing the detail is the way out the notice offers.
        vm.onSeatInputChange("")
        assertNull(vm.state.trackNotice)
        vm.events.test {
            vm.onOpenTracked(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }
    }

    @Test
    fun `a rejected detail on a tracked leg is marked on its field`() = runTest {
        fake.setTracked(TestData.tracked(delBom))
        fake.actionResult = ActionResult.InvalidInput(TrackField.SEAT)
        val vm = viewModelWithResults()
        vm.onSeatInputChange("14C")

        vm.onOpenTracked(delBom.id)

        assertTrue(vm.state.seatInvalid)
        assertEquals(TrackProblem.InvalidDetails, vm.state.trackNotice?.problem)
    }

    @Test
    fun `a leg the server says is tracked already still gets the typed details and opens`() = runTest {
        // A stale "Track" button: the repository answers AlreadyTracked instead of adding a second row.
        fake.setTracked(TestData.tracked(delBom, TestData.tracking(delBom.id, seat = "1A")))
        val vm = viewModelWithResults()
        vm.onPnrInputChange("ABC123")

        vm.events.test {
            vm.onTrack(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }

        assertEquals(1, fake.trackCalls.size)
        val tracking = fake.tracked.value.single().tracking
        assertEquals("ABC123", tracking.pnr)
        assertEquals("1A", tracking.seat)
    }

    @Test
    fun `open on a leg that is no longer in the list falls back to track`() = runTest {
        val vm = viewModelWithResults()

        vm.events.test {
            vm.onOpenTracked(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }
        assertEquals(listOf(delBom.id), fake.trackCalls.map { it.first })
    }
}

/** [FakeFlightRepository] whose search and track can be held open, to observe the in-flight states. */
private class GatedFlightRepository(private val delegate: FakeFlightRepository) : FlightRepository by delegate {
    var searchGate: CompletableDeferred<Unit>? = null
    var trackGate: CompletableDeferred<Unit>? = null

    override suspend fun search(flightText: String, date: LocalDate): SearchResult {
        searchGate?.await()
        return delegate.search(flightText, date)
    }

    override suspend fun track(flightId: String, extras: TrackExtras): TrackResult {
        trackGate?.await()
        return delegate.track(flightId, extras)
    }
}
