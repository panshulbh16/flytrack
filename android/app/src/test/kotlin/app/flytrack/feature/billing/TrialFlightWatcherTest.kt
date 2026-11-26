package app.flytrack.feature.billing

import app.flytrack.core.model.Plan
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalCoroutinesApi::class)
class TrialFlightWatcherTest {
    private val flights = FakeFlightRepository()
    private val profiles = FakeProfileRepository()
    private val clock = FakeClock()

    private fun kotlinx.coroutines.test.TestScope.watching(): TrialFlightWatcher {
        val watcher = TrialFlightWatcher(flights, profiles, clock, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { watcher.watch() }
        return watcher
    }

    @Test fun aNewlyTrackedFlightRereadsTheProfileOfAFreeUserWithoutTrial() = runTest {
        watching()
        assertEquals(0, profiles.refreshCalls) // the initial list does not count
        flights.tracked.value = listOf(TestData.tracked())
        assertEquals(1, profiles.refreshCalls)
    }

    @Test fun noRereadOnceTheTrialFlightIsKnown() = runTest {
        profiles.profile.value = TestData.profile().copy(trialFlightId = TestData.FLIGHT_ID)
        watching()
        flights.tracked.value = listOf(TestData.tracked(TestData.flight(id = "other")))
        assertEquals(0, profiles.refreshCalls)
    }

    @Test fun noRereadForProUsersOrWhenAFlightIsRemoved() = runTest {
        flights.tracked.value = listOf(TestData.tracked())
        profiles.profile.value = TestData.profile(plan = Plan.PRO, proUntil = TestData.NOW + 30.days)
        watching()
        flights.tracked.value = listOf(TestData.tracked(), TestData.tracked(TestData.flight(id = "other")))
        assertEquals(0, profiles.refreshCalls)
        profiles.profile.value = TestData.profile()
        flights.tracked.value = emptyList()
        assertEquals(0, profiles.refreshCalls)
    }

    @Test fun needsTrialRefreshRule() {
        assertTrue(TrialFlightWatcher.needsTrialRefresh(null, TestData.NOW))
        assertTrue(TrialFlightWatcher.needsTrialRefresh(TestData.profile(), TestData.NOW))
        assertFalse(TrialFlightWatcher.needsTrialRefresh(TestData.profile().copy(trialFlightId = "f"), TestData.NOW))
        assertFalse(TrialFlightWatcher.needsTrialRefresh(TestData.profile(plan = Plan.PRO), TestData.NOW))
        assertTrue(TrialFlightWatcher.needsTrialRefresh(TestData.profile(plan = Plan.PRO, proUntil = TestData.NOW - 1.days), TestData.NOW))
    }
}
