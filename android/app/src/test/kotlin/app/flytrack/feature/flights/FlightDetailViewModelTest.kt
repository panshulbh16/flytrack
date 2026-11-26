package app.flytrack.feature.flights

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.Plan
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.core.time.RelativeTime
import app.flytrack.core.time.RelativeUnit
import app.flytrack.feature.checkin.CheckinWindow
import app.flytrack.feature.checkin.FakeCheckinRulesRepository
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.share.FakeShareRepository
import app.flytrack.feature.share.ShareFlight
import app.flytrack.feature.share.ShareRequest
import app.flytrack.feature.share.ShareResult
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.flights.presentation.Countdown
import app.flytrack.feature.flights.presentation.FlightChangeTracker
import app.flytrack.feature.flights.presentation.FlightField
import app.flytrack.feature.flights.presentation.FlightStatusWord
import app.flytrack.feature.flights.presentation.ProInsightUiModel
import app.flytrack.feature.flights.presentation.TrackingState
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeProfileRepository
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class FlightDetailViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val flights = FakeFlightRepository()
    private val profiles = FakeProfileRepository()
    private val clock = FakeClock()
    private val changes = FlightChangeTracker()

    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val prediction = InboundPrediction(computedAt = clock.now(), predictedDep = schedDep + 20.minutes, lateMin = 20, reason = null, inbound = null)

    private val shares = FakeShareRepository()
    private val checkinRules = FakeCheckinRulesRepository()

    private fun viewModel(flightId: String = TestData.FLIGHT_ID) =
        FlightDetailViewModel(flightId, flights, profiles, clock, changes, shares, checkinRules)

    /** Keeps [FlightDetailViewModel.uiState] subscribed and returns a reader of its current value. */
    private fun TestScope.observe(vm: FlightDetailViewModel): () -> FlightDetailUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return {
            runCurrent()
            vm.uiState.value
        }
    }

    private fun alert(state: FlightDetailUiState, group: AlertGroup) = state.detail!!.alerts.single { it.group == group }

    // ---- loading the flight -------------------------------------------------------------------

    @Test
    fun `shows the tracked flight and fetches its latest state once`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(pnr = "QX7RTL", seat = "14C")))

        val state = observe(viewModel())()

        assertFalse(state.isLoading)
        val detail = state.detail!!
        assertEquals("6E 2341", detail.card.designator)
        assertEquals(FlightStatusWord.SCHEDULED, detail.card.statusWord)
        assertEquals(TrackingState.TRACKED, detail.trackingState)
        assertEquals("QX7RTL", detail.booking?.pnr)
        assertEquals("14C", detail.booking?.seat)
        assertEquals(listOf<Pair<String, String?>>(TestData.FLIGHT_ID to null), flights.syncCalls)
    }

    @Test
    fun `a flight nobody knows ends as not found, not as an endless spinner`() = runTest {
        flights.syncResult = ActionResult.Offline

        val state = observe(viewModel())()

        assertFalse(state.isLoading)
        assertNull(state.detail)
        assertNull("the quiet first fetch never nags", state.message)
    }

    @Test
    fun `a flight that is only cached is shown as not tracked and can be tracked`() = runTest {
        val flight = TestData.flight()
        flights.cachedFlights.update { it + (flight.id to flight) }
        val vm = viewModel()
        val state = observe(vm)

        assertEquals(TrackingState.NOT_TRACKED, state().detail?.trackingState)
        assertTrue(state().detail!!.alerts.isEmpty())

        vm.track()

        assertEquals(TrackingState.TRACKED, state().detail?.trackingState)
        assertFalse(state().isWorking)
        assertEquals(AlertGroup.entries.size, state().detail!!.alerts.size)
    }

    @Test
    fun `tracking failures are reported`() = runTest {
        val flight = TestData.flight()
        flights.cachedFlights.update { it + (flight.id to flight) }
        val vm = viewModel()
        val state = observe(vm)

        flights.trackResult = TrackResult.TrackingLimitReached("free plan")
        vm.track()
        assertEquals(FlightDetailMessage.TRACKING_LIMIT_REACHED, state().message)

        flights.trackResult = TrackResult.Offline
        vm.track()
        assertEquals(FlightDetailMessage.OFFLINE, state().message)

        flights.trackResult = TrackResult.FlightNotFound
        vm.track()
        assertEquals(FlightDetailMessage.ACTION_FAILED, state().message)
        assertEquals(TrackingState.NOT_TRACKED, state().detail?.trackingState)
    }

    @Test
    fun `the screen follows the flight and the clock`() = runTest {
        flights.setTracked(TestData.tracked())
        clock.current = schedDep - 50.minutes
        val state = observe(viewModel())
        assertEquals(Countdown.Departs(RelativeTime(50, RelativeUnit.MINUTES, isPast = false)), state().detail?.card?.countdown)

        flights.setTracked(TestData.tracked(TestData.flight(status = FlightStatus.BOARDING, depGate = "42B")))
        clock.advance(15.minutes)
        advanceTimeBy(31.seconds)

        val card = state().detail!!.card
        assertEquals(FlightStatusWord.BOARDING, card.statusWord)
        assertEquals(Countdown.Departs(RelativeTime(35, RelativeUnit.MINUTES, isPast = false)), card.countdown)
        assertTrue("the gate appeared while the screen was open", card.info.single { it.field == FlightField.DEP_GATE }.isNew)
    }

    // ---- "Where's my plane" -------------------------------------------------------------------

    @Test
    fun `a free user gets the teaser and never the prediction`() = runTest {
        flights.setTracked(TestData.tracked(TestData.flight(hasProInsight = true, prediction = prediction)))
        profiles.profile.value = TestData.profile(plan = Plan.FREE)

        assertEquals(ProInsightUiModel.Teaser, observe(viewModel())().detail?.proInsight)
    }

    @Test
    fun `until the profile is known, the user counts as free`() = runTest {
        flights.setTracked(TestData.tracked(TestData.flight(hasProInsight = true, prediction = prediction)))
        profiles.profile.value = null

        assertEquals(ProInsightUiModel.Teaser, observe(viewModel())().detail?.proInsight)
    }

    @Test
    fun `a Pro user gets the prediction, until Pro runs out`() = runTest {
        flights.setTracked(TestData.tracked(TestData.flight(hasProInsight = true, prediction = prediction)))
        profiles.profile.value = TestData.profile(plan = Plan.PRO, proUntil = clock.now() + 1.days)
        val state = observe(viewModel())

        val insight = state().detail?.proInsight
        assertTrue("was $insight", insight is ProInsightUiModel.Details)
        assertFalse(alert(state(), AlertGroup.INBOUND_AIRCRAFT).locked)

        clock.advance(2.days)
        advanceTimeBy(31.seconds)

        assertTrue(alert(state(), AlertGroup.INBOUND_AIRCRAFT).locked)
    }

    // ---- alerts -------------------------------------------------------------------------------

    @Test
    fun `switching an alert group off mutes each of its types in notify_prefs`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        assertTrue(alert(state(), AlertGroup.GATE).enabled)

        vm.setAlertEnabled(AlertGroup.GATE, false)

        assertEquals(
            mapOf(AlertType.GATE_ASSIGNED to false, AlertType.GATE_CHANGE to false, AlertType.TERMINAL_CHANGE to false),
            flights.tracked.value.single().tracking.notifyPrefs,
        )
        assertFalse(alert(state(), AlertGroup.GATE).enabled)
        assertTrue(alert(state(), AlertGroup.DELAYS).enabled)
        assertNull(state().message)
    }

    @Test
    fun `switching an alert group back on keeps the other preferences`() = runTest {
        val prefs = mapOf(AlertType.DEPARTED to false, AlertType.LANDED to false, AlertType.BOARDING to false)
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(notifyPrefs = prefs)))
        val vm = viewModel()
        val state = observe(vm)
        assertFalse(alert(state(), AlertGroup.TAKEOFF_AND_LANDING).enabled)

        vm.setAlertEnabled(AlertGroup.TAKEOFF_AND_LANDING, true)

        assertEquals(
            mapOf(AlertType.DEPARTED to true, AlertType.LANDED to true, AlertType.BOARDING to false),
            flights.tracked.value.single().tracking.notifyPrefs,
        )
        assertTrue(alert(state(), AlertGroup.TAKEOFF_AND_LANDING).enabled)
        assertFalse(alert(state(), AlertGroup.BOARDING).enabled)
    }

    @Test
    fun `a switch that could not be saved goes back and says why`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        flights.actionResult = ActionResult.Offline

        vm.setAlertEnabled(AlertGroup.BAGGAGE, false)

        assertTrue(alert(state(), AlertGroup.BAGGAGE).enabled)
        assertEquals(FlightDetailMessage.OFFLINE, state().message)
        assertTrue(flights.tracked.value.single().tracking.notifyPrefs.isEmpty())
    }

    @Test
    fun `the Pro-only alert cannot be switched by a free user`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        assertTrue(alert(state(), AlertGroup.INBOUND_AIRCRAFT).locked)

        vm.setAlertEnabled(AlertGroup.INBOUND_AIRCRAFT, true)

        assertTrue(flights.tracked.value.single().tracking.notifyPrefs.isEmpty())
        assertFalse(alert(state(), AlertGroup.INBOUND_AIRCRAFT).enabled)
    }

    @Test
    fun `a Pro user can mute the inbound-aircraft alert`() = runTest {
        flights.setTracked(TestData.tracked())
        profiles.profile.value = TestData.profile(plan = Plan.PRO)
        val vm = viewModel()
        val state = observe(vm)

        vm.setAlertEnabled(AlertGroup.INBOUND_AIRCRAFT, false)

        assertEquals(mapOf(AlertType.INBOUND_LATE to false), flights.tracked.value.single().tracking.notifyPrefs)
        assertFalse(alert(state(), AlertGroup.INBOUND_AIRCRAFT).enabled)
    }

    // ---- refresh ------------------------------------------------------------------------------

    @Test
    fun `pull to refresh fetches this flight again`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.refresh()

        assertEquals(2, flights.syncCalls.size)
        assertFalse(state().isRefreshing)
        assertNull(state().message)
    }

    @Test
    fun `pull to refresh reports being offline and other failures`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        flights.syncResult = ActionResult.Offline
        vm.refresh()
        assertEquals(FlightDetailMessage.OFFLINE, state().message)
        vm.messageShown(FlightDetailMessage.OFFLINE)
        assertNull(state().message)

        flights.syncResult = ActionResult.NotFound
        vm.refresh()
        assertEquals(FlightDetailMessage.REFRESH_FAILED, state().message)

        vm.messageShown(FlightDetailMessage.OFFLINE)
        assertEquals("only the message that was shown is cleared", FlightDetailMessage.REFRESH_FAILED, state().message)
    }

    // ---- archive, restore, untrack ------------------------------------------------------------

    @Test
    fun `archiving and restoring`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.setArchived(true)
        assertEquals(TrackingState.ARCHIVED, state().detail?.trackingState)
        assertFalse(state().isClosed)

        vm.setArchived(false)
        assertEquals(TrackingState.TRACKED, state().detail?.trackingState)
        assertFalse(state().isWorking)
    }

    @Test
    fun `restoring can hit the tracking limit`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(archivedAt = TestData.NOW)))
        val vm = viewModel()
        val state = observe(vm)
        flights.actionResult = ActionResult.TrackingLimitReached

        vm.setArchived(false)

        assertEquals(FlightDetailMessage.TRACKING_LIMIT_REACHED, state().message)
        assertEquals(TrackingState.ARCHIVED, state().detail?.trackingState)
    }

    @Test
    fun `stop tracking closes the screen`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.untrack()

        assertEquals(listOf(TestData.FLIGHT_ID), flights.untrackCalls)
        assertTrue(state().isClosed)
    }

    @Test
    fun `stop tracking also closes when the row is already gone`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        flights.actionResult = ActionResult.NotFound

        vm.untrack()

        assertTrue(state().isClosed)
        assertNull(state().message)
    }

    @Test
    fun `a failed stop tracking stays on the screen and says why`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        flights.actionResult = ActionResult.Offline

        vm.untrack()

        assertFalse(state().isClosed)
        assertEquals(FlightDetailMessage.OFFLINE, state().message)
        assertEquals(TrackingState.TRACKED, state().detail?.trackingState)
    }

    // ---- booking editor -----------------------------------------------------------------------

    @Test
    fun `the booking editor opens with the saved PNR and seat`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(pnr = "QX7RTL", seat = null)))
        val vm = viewModel()
        val state = observe(vm)
        assertNull(state().editor)

        vm.openBookingEditor()

        assertEquals(BookingEditorState(pnr = "QX7RTL", seat = ""), state().editor)
    }

    @Test
    fun `the booking editor is not offered for a flight that is not tracked`() = runTest {
        val flight = TestData.flight()
        flights.cachedFlights.update { it + (flight.id to flight) }
        val vm = viewModel()
        val state = observe(vm)

        vm.openBookingEditor()

        assertNull(state().editor)
    }

    @Test
    fun `saving the booking keeps cabin and note and closes the editor`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(pnr = "OLD123", cabin = Cabin.BUSINESS, note = "Window")))
        val vm = viewModel()
        val state = observe(vm)

        vm.openBookingEditor()
        vm.onPnrChange("qx7rtl")
        vm.onSeatChange("14c")
        assertEquals(BookingEditorState(pnr = "QX7RTL", seat = "14C", cabin = Cabin.BUSINESS), state().editor)
        vm.saveBooking()

        val saved = flights.tracked.value.single().tracking
        assertEquals("QX7RTL", saved.pnr)
        assertEquals("14C", saved.seat)
        assertEquals(Cabin.BUSINESS, saved.cabin)
        assertEquals("Window", saved.note)
        assertNull(state().editor)
        assertEquals("QX7RTL", state().detail?.booking?.pnr)
    }

    @Test
    fun `blank booking fields clear the saved values`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(pnr = "QX7RTL", seat = "14C")))
        val vm = viewModel()
        observe(vm)()

        vm.openBookingEditor()
        vm.onPnrChange("  ")
        vm.onSeatChange("")
        vm.saveBooking()

        val saved = flights.tracked.value.single().tracking
        assertNull(saved.pnr)
        assertNull(saved.seat)
    }

    @Test
    fun `the seat is limited to what the database accepts`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        vm.openBookingEditor()
        vm.onSeatChange("14c-window")

        assertEquals("14C-W", state().editor?.seat)
    }

    @Test
    fun `an invalid PNR is marked on its field and the editor stays open`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        vm.openBookingEditor()
        vm.onPnrChange("AB")
        flights.actionResult = ActionResult.InvalidInput(TrackField.PNR)

        vm.saveBooking()

        assertEquals(BookingEditorState(pnr = "AB", seat = "", error = TrackField.PNR, isSaving = false), state().editor)
        assertNull(state().message)

        vm.onPnrChange("ABC")
        assertNull("typing in the field clears its error", state().editor?.error)
    }

    @Test
    fun `a booking that could not be saved keeps the editor open and says why`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        vm.openBookingEditor()
        vm.onPnrChange("QX7RTL")
        flights.actionResult = ActionResult.Offline

        vm.saveBooking()

        assertNotNull(state().editor)
        assertFalse(state().editor!!.isSaving)
        assertEquals(FlightDetailMessage.OFFLINE, state().message)
    }

    @Test
    fun `the booking editor can be dismissed`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        vm.openBookingEditor()
        vm.onPnrChange("QX7RTL")

        vm.dismissBookingEditor()

        assertNull(state().editor)
        assertNull(flights.tracked.value.single().tracking.pnr)
    }

    // ---- cabin --------------------------------------------------------------------------------

    @Test
    fun `the cabin picker saves the chosen cabin and can clear it`() = runTest {
        flights.setTracked(TestData.tracked(tracking = TestData.tracking(cabin = Cabin.ECONOMY, note = "Window")))
        val vm = viewModel()
        val state = observe(vm)

        vm.openBookingEditor()
        assertEquals(Cabin.ECONOMY, state().editor?.cabin)
        vm.onCabinChange(Cabin.PREMIUM_ECONOMY)
        vm.saveBooking()
        assertEquals(Cabin.PREMIUM_ECONOMY, flights.tracked.value.single().tracking.cabin)
        assertEquals("Window", flights.tracked.value.single().tracking.note)

        vm.openBookingEditor()
        vm.onCabinChange(null)
        vm.saveBooking()
        assertNull(flights.tracked.value.single().tracking.cabin)
    }

    // ---- share --------------------------------------------------------------------------------

    @Test
    fun `sharing a tracked flight hands its link to the screen once`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        assertTrue(state().canShare)

        vm.share(ShareTarget.WHATSAPP_FIRST)

        val expected = ShareRequest(ShareFlight("6E", "2341", "DEL", "BOM"), "https://heygate.app/f/#tok", ShareTarget.WHATSAPP_FIRST)
        assertEquals(expected, state().share)
        assertFalse(state().isSharing)
        assertEquals(listOf(TestData.FLIGHT_ID), shares.calls)
        vm.shareHandled(expected)
        assertNull(state().share)
    }

    @Test
    fun `a flight that is not tracked cannot be shared`() = runTest {
        val flight = TestData.flight()
        flights.cachedFlights.update { it + (flight.id to flight) }
        val vm = viewModel()
        val state = observe(vm)
        assertFalse(state().canShare)
    }

    @Test
    fun `share failures are reported`() = runTest {
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        shares.result = ShareResult.Offline
        vm.share(ShareTarget.SHARE_SHEET)
        assertEquals(FlightDetailMessage.OFFLINE, state().message)
        vm.messageShown(FlightDetailMessage.OFFLINE)

        shares.result = ShareResult.NotTracked
        vm.share(ShareTarget.SHARE_SHEET)
        assertEquals(FlightDetailMessage.SHARE_NOT_TRACKED, state().message)
        vm.messageShown(FlightDetailMessage.SHARE_NOT_TRACKED)

        shares.result = ShareResult.Failed("boom")
        vm.share(ShareTarget.SHARE_SHEET)
        assertEquals(FlightDetailMessage.SHARE_FAILED, state().message)
        assertNull(state().share)
    }

    // ---- check-in -----------------------------------------------------------------------------

    @Test
    fun `the check-in card follows the airline rule and the clock`() = runTest {
        // TestData's flight departs 2026-11-25T12:55Z; the clock starts 2026-11-22T10:00Z.
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)

        val before = state().checkin?.window
        assertTrue(before is CheckinWindow.NotOpenYet)
        assertEquals(Instant.parse("2026-11-23T12:55:00Z"), before?.opensAt)
        assertEquals(listOf("6E"), checkinRules.asked)

        clock.advance(2.days + 3.hours) // 2026-11-24T13:00Z: open
        advanceTimeBy(31.seconds)
        assertTrue(state().checkin?.window is CheckinWindow.Open)
        assertTrue(state().checkin!!.canCheckInNow)
    }

    @Test
    fun `no rule for the airline hides the card`() = runTest {
        checkinRules.rules = emptyMap()
        flights.setTracked(TestData.tracked())
        val vm = viewModel()
        val state = observe(vm)
        assertNotNull(state().detail)
        assertNull(state().checkin)
    }
}
