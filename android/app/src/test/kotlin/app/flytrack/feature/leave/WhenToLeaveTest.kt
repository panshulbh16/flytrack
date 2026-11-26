package app.flytrack.feature.leave

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.flytrack.core.model.FlightStatus
import app.flytrack.feature.airporthealth.AirportHealth
import app.flytrack.feature.airporthealth.AirportHealthRepository
import app.flytrack.feature.airporthealth.AirportHealthResult
import app.flytrack.feature.airporthealth.HealthLevel
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FakeTravelTimeStore : TravelTimeStore {
    val minutes = MutableStateFlow<Map<String, Int>>(emptyMap())
    override fun travelMinutes(iata: String): Flow<Int> = minutes.map { it[iata] ?: LeaveRules.DEFAULT_TRAVEL_MIN }
    override suspend fun setTravelMinutes(iata: String, minutes: Int) {
        this.minutes.value = this.minutes.value + (iata to clampTravelMinutes(minutes))
    }
}

class FakeReminderScheduler : LeaveReminderScheduler {
    val scheduled = mutableMapOf<String, Instant>()
    val cancelled = mutableListOf<String>()
    override fun schedule(flightId: String, at: Instant) {
        scheduled[flightId] = at
    }

    override fun cancel(flightId: String) {
        scheduled.remove(flightId)
        cancelled += flightId
    }
}

class FakeHealthRepository(var result: AirportHealthResult = AirportHealthResult.Unavailable) : AirportHealthRepository {
    val asked = mutableListOf<String>()
    override suspend fun health(iata: String, force: Boolean): AirportHealthResult {
        asked += iata
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WhenToLeaveTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val folder = TemporaryFolder()

    private val flights = FakeFlightRepository()
    private val travel = FakeTravelTimeStore()
    private val health = FakeHealthRepository()
    private val scheduler = FakeReminderScheduler()
    private val clock = FakeClock()

    /**
     * The view model ticks every 30 s for as long as it lives, and runTest drains the shared scheduler at the
     * end, so the scope is cancelled after each test (also when an assertion fails). [setUp] runs before the
     * view model is created.
     */
    private fun vmTest(setUp: () -> Unit = {}, block: suspend TestScope.(WhenToLeaveViewModel) -> Unit) = runTest {
        setUp()
        val vm = WhenToLeaveViewModel(TestData.FLIGHT_ID, flights, travel, health, scheduler, clock)
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    // ---- pure state ------------------------------------------------------------------------------

    @Test fun hiddenUntilProStateIsKnown() {
        assertEquals(WhenToLeaveUiState.Hidden, whenToLeaveState(TestData.flight(), 60, false, null, TestData.NOW))
        assertEquals(WhenToLeaveUiState.Hidden, whenToLeaveState(null, 60, false, true, TestData.NOW))
    }

    @Test fun lockedForFreeUsersOnUpcomingFlightsOnly() {
        assertEquals(WhenToLeaveUiState.Locked, whenToLeaveState(TestData.flight(), 60, false, false, TestData.NOW))
        val gone = TestData.flight(status = FlightStatus.CANCELLED)
        assertEquals(WhenToLeaveUiState.Hidden, whenToLeaveState(gone, 60, false, false, TestData.NOW))
    }

    @Test fun readyForPro() {
        val state = whenToLeaveState(TestData.flight(), 45, false, true, TestData.NOW) as WhenToLeaveUiState.Ready
        assertEquals("DEL", state.depIata)
        assertEquals("New Delhi", state.depCity)
        assertEquals(45, state.plan.travelMinutes)
        assertTrue(state.reminderAhead)
    }

    @Test fun reminderActions() {
        val ready = whenToLeaveState(TestData.flight(), 60, false, true, TestData.NOW) as WhenToLeaveUiState.Ready
        assertEquals(ReminderAction.Schedule(ready.plan.reminderAt), reminderAction(ready, proKnown = true))
        assertEquals(ReminderAction.Keep, reminderAction(ready.copy(reminderAhead = false), proKnown = true))
        assertEquals(ReminderAction.Cancel, reminderAction(WhenToLeaveUiState.Locked, proKnown = true))
        assertEquals(ReminderAction.Cancel, reminderAction(WhenToLeaveUiState.Hidden, proKnown = true))
        assertEquals(ReminderAction.Keep, reminderAction(WhenToLeaveUiState.Hidden, proKnown = false))
    }

    // ---- worker decision -------------------------------------------------------------------------

    @Test fun workerDecision() {
        val plan = leavePlan(TestData.flight(), 60, false, TestData.NOW)!!
        assertEquals(ReminderDecision.Skip, reminderDecision(null, eligible = true, now = TestData.NOW))
        assertEquals(ReminderDecision.Skip, reminderDecision(plan, eligible = false, now = plan.reminderAt))
        assertEquals(ReminderDecision.Notify(plan), reminderDecision(plan, eligible = true, now = plan.reminderAt))
        assertEquals(ReminderDecision.Notify(plan), reminderDecision(plan, eligible = true, now = plan.reminderAt - 1.minutes))
        // A delay moved the plan: fire later.
        assertEquals(ReminderDecision.Reschedule(plan.reminderAt), reminderDecision(plan, eligible = true, now = plan.reminderAt - 30.minutes))
        // Woken long after "leave by": too late to be useful.
        assertEquals(ReminderDecision.Skip, reminderDecision(plan, eligible = true, now = plan.leaveBy + 31.minutes))
        assertEquals(ReminderDecision.Notify(plan), reminderDecision(plan, eligible = true, now = plan.leaveBy + 10.minutes))
    }

    @Test fun workerWaitsForTheStoredSessionBeforeReadingPro() = runTest {
        val session = MutableStateFlow<app.flytrack.data.repo.SessionState>(app.flytrack.data.repo.SessionState.Loading)
        // Still loading: times out (the worker retries instead of treating a Pro user as free).
        assertEquals(false, awaitSessionKnown(session, timeout = 1.minutes))
        session.value = app.flytrack.data.repo.SessionState.SignedIn(TestData.USER_ID, isAnonymous = true, email = null)
        assertEquals(true, awaitSessionKnown(session, timeout = 1.minutes))
        session.value = app.flytrack.data.repo.SessionState.SignedOut
        assertEquals(true, awaitSessionKnown(session, timeout = 1.minutes))
    }

    // ---- view model ------------------------------------------------------------------------------

    @Test fun schedulesReminderForProAndCancelsWhenLocked() = vmTest({ flights.setTracked(TestData.tracked()) }) { vm ->
        vm.setProAccess(true)
        val ready = vm.uiState.first { it is WhenToLeaveUiState.Ready } as WhenToLeaveUiState.Ready
        assertEquals(ready.plan.reminderAt, scheduler.scheduled[TestData.FLIGHT_ID])

        vm.setProAccess(false)
        assertEquals(WhenToLeaveUiState.Locked, vm.uiState.value)
        assertTrue(TestData.FLIGHT_ID in scheduler.cancelled)
    }

    @Test fun travelMinutesAreRememberedPerAirportAndMoveTheReminder() = vmTest({ flights.setTracked(TestData.tracked()) }) { vm ->
        vm.setProAccess(true)
        val before = vm.uiState.first { it is WhenToLeaveUiState.Ready } as WhenToLeaveUiState.Ready
        vm.setTravelMinutes(90)
        val after = vm.uiState.first { (it as? WhenToLeaveUiState.Ready)?.plan?.travelMinutes == 90 } as WhenToLeaveUiState.Ready
        assertEquals(90, travel.minutes.value["DEL"])
        assertEquals(before.plan.leaveBy - 30.minutes, after.plan.leaveBy)
        assertEquals(after.plan.reminderAt, scheduler.scheduled[TestData.FLIGHT_ID])
    }

    @Test fun fogIsAskedNearDepartureAndAddsTime() = vmTest({
        // 2 h before departure.
        clock.current = Instant.parse("2026-11-25T10:55:00Z")
        health.result = AirportHealthResult.Loaded(AirportHealth("DEL", HealthLevel.SEVERE, "", null, null))
        flights.setTracked(TestData.tracked())
    }) { vm ->
        vm.setProAccess(true)
        val ready = vm.uiState.first { (it as? WhenToLeaveUiState.Ready)?.plan?.fog == true } as WhenToLeaveUiState.Ready
        assertEquals(listOf("DEL"), health.asked)
        assertEquals(120 + 30 + 20, ready.plan.bufferMinutes)
    }

    @Test fun healthIsNotAskedDaysAhead() = vmTest({ flights.setTracked(TestData.tracked()) }) { vm ->
        vm.setProAccess(true)
        vm.uiState.first { it is WhenToLeaveUiState.Ready }
        assertTrue(health.asked.isEmpty())
    }

    @Test fun archivedFlightHasNoCard() = vmTest({ flights.setTracked(TestData.tracked(tracking = TestData.tracking(archivedAt = TestData.NOW))) }) { vm ->
        vm.setProAccess(true)
        assertEquals(WhenToLeaveUiState.Hidden, vm.uiState.value)
    }

    // ---- DataStore -------------------------------------------------------------------------------

    @Test fun dataStoreRemembersMinutesPerAirport() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val file = File(folder.root, "leave.preferences_pb")
        val store = DataStoreTravelTimeStore(
            PreferenceDataStoreFactory.create(scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + dispatcher)) { file },
        )
        assertEquals(LeaveRules.DEFAULT_TRAVEL_MIN, store.travelMinutes("DEL").first())
        store.setTravelMinutes("del", 75)
        store.setTravelMinutes("BOM", 9_999)
        assertEquals(75, store.travelMinutes("DEL").first())
        assertEquals(LeaveRules.MAX_TRAVEL_MIN, store.travelMinutes("BOM").first())
        assertEquals(LeaveRules.DEFAULT_TRAVEL_MIN, store.travelMinutes("BLR").first())
    }
}
