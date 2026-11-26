package app.flytrack.feature.map

import app.cash.turbine.test
import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FlightMapViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")

    @Test
    fun `a tracked flight is drawn from Room`() = runTest {
        val flights = FakeFlightRepository().apply {
            setTracked(TestData.tracked(flight = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep)))
        }
        val vm = FlightMapViewModel(TestData.FLIGHT_ID, flights, FakeClock(schedDep + 30.minutes))

        vm.uiState.test {
            var state = awaitItem()
            if (state == FlightMapUiState.Loading) state = awaitItem()
            assertTrue(state is FlightMapUiState.Ready)
            assertEquals(MapFlightState.AIRBORNE, (state as FlightMapUiState.Ready).model.state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unknown flight is unavailable`() = runTest {
        val vm = FlightMapViewModel("missing", FakeFlightRepository(), FakeClock())

        vm.uiState.test {
            var state = awaitItem()
            if (state == FlightMapUiState.Loading) state = awaitItem()
            assertEquals(FlightMapUiState.Unavailable, state)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
