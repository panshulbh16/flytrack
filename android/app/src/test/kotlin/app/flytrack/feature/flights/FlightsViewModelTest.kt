package app.flytrack.feature.flights

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.TrackSource
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.TrackResult
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.RelativeTime
import app.flytrack.core.time.RelativeUnit
import app.flytrack.data.repo.AuthRepository
import app.flytrack.feature.flights.presentation.Countdown
import app.flytrack.feature.flights.presentation.FlightChangeTracker
import app.flytrack.feature.flights.presentation.FlightField
import app.flytrack.feature.gmailimport.SuggestionStatus
import app.flytrack.feature.gmailimport.TrackedKey
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FlightsViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val flights = FakeFlightRepository()
    private val auth = FakeAuthRepository()
    private val clock = FakeClock()
    private val changes = FlightChangeTracker()

    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    private val gmailStore = FakeGmailSuggestionStore()
    private val gmailNotifier = FakeGmailNotifier()
    private var trackedKeys = emptySet<TrackedKey>()

    /** The phone's date at [app.flytrack.testing.TestData.NOW]. */
    private val today = LocalDate(2026, 11, 22)

    private fun viewModel(auth: AuthRepository = this.auth) = FlightsViewModel(
        flights, auth, clock, changes,
        gmailStore = gmailStore,
        gmailNotifier = gmailNotifier,
        trackedKeys = { trackedKeys },
        today = { today },
    )

    /** Keeps [FlightsViewModel.uiState] subscribed and returns a reader of its current value. */
    private fun TestScope.observe(vm: FlightsViewModel): () -> FlightsUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return {
            runCurrent()
            vm.uiState.value
        }
    }

    @Test
    fun `starts in the loading state`() {
        val initial = viewModel().uiState.value

        assertTrue(initial.isLoading)
        assertFalse("loading is not the same as empty", initial.isEmpty)
    }

    @Test
    fun `shows tracked flights grouped into upcoming and past, with the next takeoff`() = runTest {
        val past = TestData.flight(
            id = TestData.OTHER_FLIGHT_ID, status = FlightStatus.ARRIVED,
            schedDep = schedDep - 7.days, actDep = schedDep - 7.days, schedArr = schedArr - 7.days, actArr = schedArr - 7.days,
        )
        flights.setTracked(TestData.tracked(past), TestData.tracked())

        val state = observe(viewModel())()

        assertFalse(state.isLoading)
        assertFalse(state.isEmpty)
        assertEquals(listOf(TestData.FLIGHT_ID), state.upcoming.map { it.flightId })
        assertEquals(listOf(TestData.OTHER_FLIGHT_ID), state.past.map { it.flightId })
        assertEquals(TestData.FLIGHT_ID, state.next?.card?.flightId)
        assertEquals(Countdown.DepartsOn(RelativeDay.InDays(3)), state.next?.countdown)
        assertTrue(state.isBackendConfigured)
    }

    @Test
    fun `no tracked flights is the empty state`() = runTest {
        val state = observe(viewModel())()

        assertFalse(state.isLoading)
        assertTrue(state.isEmpty)
        assertNull(state.next)
    }

    @Test
    fun `a build without backend keys is flagged`() = runTest {
        val vm = viewModel(FakeAuthRepository.notConfigured())

        assertFalse(observe(vm)().isBackendConfigured)
    }

    @Test
    fun `the list follows the repository`() = runTest {
        val state = observe(viewModel())
        assertTrue(state().isEmpty)

        flights.setTracked(TestData.tracked())

        assertEquals(listOf(TestData.FLIGHT_ID), state().upcoming.map { it.flightId })
    }

    @Test
    fun `countdowns follow the clock while the screen is visible`() = runTest {
        flights.setTracked(TestData.tracked())
        clock.current = schedDep - 2.hours
        val state = observe(viewModel())
        assertEquals(Countdown.Departs(RelativeTime(2, RelativeUnit.HOURS, isPast = false)), state().next?.countdown)

        clock.advance(85.minutes)
        advanceTimeBy(31.seconds)

        assertEquals(Countdown.Departs(RelativeTime(35, RelativeUnit.MINUTES, isPast = false)), state().next?.countdown)
        assertEquals(clock.current, state().upcoming.single().asOf)
    }

    @Test
    fun `a gate that appears while the app is open is marked new`() = runTest {
        flights.setTracked(TestData.tracked(TestData.flight(depGate = null)))
        val state = observe(viewModel())
        assertTrue(state().upcoming.single().info.none { it.isNew })

        flights.setTracked(TestData.tracked(TestData.flight(depGate = "42B")))

        val gate = state().upcoming.single().info.single { it.field == FlightField.DEP_GATE }
        assertEquals("42B", gate.value)
        assertTrue(gate.isNew)
    }

    // ---- refresh ------------------------------------------------------------------------------

    @Test
    fun `pull to refresh asks the repository and stops spinning`() = runTest {
        val vm = viewModel()
        val state = observe(vm)

        vm.refresh()

        assertEquals(1, flights.refreshCalls)
        assertFalse(state().isRefreshing)
        assertNull(state().message)
    }

    @Test
    fun `pull to refresh reports being offline and other failures`() = runTest {
        val vm = viewModel()
        val state = observe(vm)

        flights.refreshResult = ActionResult.Offline
        vm.refresh()
        assertEquals(FlightsMessage.Offline, state().message)

        flights.refreshResult = ActionResult.Failed("boom")
        vm.refresh()
        assertEquals(FlightsMessage.RefreshFailed, state().message)
    }

    @Test
    fun `pull to refresh stays quiet when the backend is not configured`() = runTest {
        val vm = viewModel()
        val state = observe(vm)
        flights.refreshResult = ActionResult.NotConfigured

        vm.refresh()

        assertNull(state().message)
        assertFalse(state().isRefreshing)
    }

    @Test
    fun `coming back to the screen refreshes quietly, at most once a minute`() = runTest {
        val vm = viewModel()
        val state = observe(vm)
        flights.refreshResult = ActionResult.Offline

        // The app shell has just refreshed at launch.
        vm.onScreenResumed()
        assertEquals(0, flights.refreshCalls)

        clock.advance(2.minutes)
        vm.onScreenResumed()
        assertEquals(1, flights.refreshCalls)
        assertNull("a background refresh never nags", state().message)

        clock.advance(30.seconds)
        vm.onScreenResumed()
        assertEquals(1, flights.refreshCalls)

        clock.advance(31.seconds)
        vm.onScreenResumed()
        assertEquals(2, flights.refreshCalls)
    }

    // ---- archive and untrack, with undo -------------------------------------------------------

    @Test
    fun `archiving hides the flight and can be undone`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.archive(TestData.FLIGHT_ID)

        assertTrue(state().isEmpty)
        assertEquals(FlightsMessage.Archived(TestData.FLIGHT_ID), state().message)

        vm.undo(FlightsMessage.Archived(TestData.FLIGHT_ID))
        vm.messageShown(FlightsMessage.Archived(TestData.FLIGHT_ID))

        assertEquals(listOf(TestData.FLIGHT_ID), state().upcoming.map { it.flightId })
        assertNull(state().message)
    }

    @Test
    fun `a failed archive says so and changes nothing`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        flights.actionResult = ActionResult.Offline
        vm.archive(TestData.FLIGHT_ID)
        assertEquals(FlightsMessage.Offline, state().message)
        assertEquals(1, state().upcoming.size)

        flights.actionResult = ActionResult.Failed("boom")
        vm.archive(TestData.FLIGHT_ID)
        assertEquals(FlightsMessage.ActionFailed, state().message)
    }

    @Test
    fun `undoing an archive can hit the tracking limit`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        vm.archive(TestData.FLIGHT_ID)

        flights.actionResult = ActionResult.TrackingLimitReached
        vm.undo(FlightsMessage.Archived(TestData.FLIGHT_ID))

        assertEquals(FlightsMessage.TrackingLimitReached, state().message)
    }

    @Test
    fun `stopping tracking removes the flight`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.untrack(TestData.FLIGHT_ID)

        assertEquals(listOf(TestData.FLIGHT_ID), flights.untrackCalls)
        assertTrue(state().isEmpty)
        assertEquals(FlightsMessage.Untracked(TestData.FLIGHT_ID), state().message)
    }

    @Test
    fun `undoing stop tracking brings the flight back with its PNR, seat and muted alerts`() = runTest {
        val flight = TestData.flight()
        val tracking = TestData.tracking(
            pnr = "QX7RTL", seat = "14C", cabin = Cabin.BUSINESS, source = TrackSource.PDF, note = "Window",
            notifyPrefs = mapOf(AlertType.DEPARTED to false),
        )
        flights.setTracked(TestData.tracked(flight, tracking))
        // In the app the flight row stays in Room after untrack; the fake keeps untracked flights here.
        flights.cachedFlights.update { it + (flight.id to flight) }
        val vm = viewModel()
        val state = observe(vm)

        vm.untrack(flight.id)
        vm.undo(FlightsMessage.Untracked(flight.id))

        assertEquals(listOf(flight.id), state().upcoming.map { it.flightId })
        val restored = flights.tracked.value.single().tracking
        assertEquals("QX7RTL", restored.pnr)
        assertEquals("14C", restored.seat)
        assertEquals(Cabin.BUSINESS, restored.cabin)
        assertEquals(TrackSource.PDF, restored.source)
        assertEquals("Window", restored.note)
        assertEquals(mapOf(AlertType.DEPARTED to false), restored.notifyPrefs)
    }

    @Test
    fun `a failed undo of stop tracking is reported`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        vm.untrack(TestData.FLIGHT_ID)

        flights.trackResult = TrackResult.TrackingLimitReached()
        vm.undo(FlightsMessage.Untracked(TestData.FLIGHT_ID))
        assertEquals(FlightsMessage.TrackingLimitReached, state().message)

        flights.trackResult = TrackResult.Offline
        vm.undo(FlightsMessage.Untracked(TestData.FLIGHT_ID))
        assertEquals(FlightsMessage.Offline, state().message)

        flights.trackResult = TrackResult.FlightNotFound
        vm.undo(FlightsMessage.Untracked(TestData.FLIGHT_ID))
        assertEquals(FlightsMessage.ActionFailed, state().message)
    }

    @Test
    fun `a failed stop tracking keeps the flight`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        flights.actionResult = ActionResult.Offline

        vm.untrack(TestData.FLIGHT_ID)

        assertEquals(FlightsMessage.Offline, state().message)
        assertEquals(1, state().upcoming.size)
    }

    @Test
    fun `a message is cleared once shown, and only that message`() = runTest {
        val vm = viewModel()
        val state = observe(vm)
        flights.refreshResult = ActionResult.Offline
        vm.refresh()

        vm.messageShown(FlightsMessage.RefreshFailed)
        assertEquals(FlightsMessage.Offline, state().message)

        vm.messageShown(FlightsMessage.Offline)
        assertNull(state().message)
    }

    @Test
    fun `undo of a message without an action does nothing`() = runTest {
        val vm = viewModel()
        val state = observe(vm)

        vm.undo(FlightsMessage.Offline)

        assertNull(state().message)
        assertTrue(flights.trackCalls.isEmpty())
    }

    // ---- Found in Gmail -----------------------------------------------------------------------

    @Test
    fun `the gmail card lists pending upcoming suggestions that are not tracked, soonest first`() = runTest {
        val later = GmailFixtures.suggestion(flight = "AI 865", date = LocalDate(2026, 12, 20))
        val sooner = GmailFixtures.suggestion(flight = "6E 2341", date = LocalDate(2026, 12, 3))
        gmailStore.insert(
            listOf(
                later,
                sooner,
                GmailFixtures.suggestion(flight = "QP 1102", date = LocalDate(2026, 11, 21)),
                GmailFixtures.suggestion(flight = "UK 955", date = LocalDate(2026, 12, 5), status = SuggestionStatus.DISMISSED),
                GmailFixtures.suggestion(flight = "6E 6001", date = LocalDate(2026, 12, 9)),
            ),
        )
        trackedKeys = setOf(TrackedKey("6E6001", LocalDate(2026, 12, 9)))

        assertEquals(listOf(sooner, later), observe(viewModel())().gmailSuggestions)
    }

    @Test
    fun `no suggestions means no gmail card`() = runTest {
        assertTrue(observe(viewModel())().gmailSuggestions.isEmpty())
    }

    @Test
    fun `dismissing a suggestion hides it for good and cancels its notification`() = runTest {
        val suggestion = GmailFixtures.suggestion(flight = "6E 2341", date = LocalDate(2026, 12, 3))
        gmailStore.insert(listOf(suggestion))
        val vm = viewModel()
        val state = observe(vm)
        assertEquals(listOf(suggestion), state().gmailSuggestions)

        vm.dismissGmailSuggestion(suggestion.id)

        assertTrue(state().gmailSuggestions.isEmpty())
        assertEquals(SuggestionStatus.DISMISSED, gmailStore.get(suggestion.id)?.status)
        assertEquals(listOf(suggestion.id), gmailNotifier.cancelled)
    }
}
