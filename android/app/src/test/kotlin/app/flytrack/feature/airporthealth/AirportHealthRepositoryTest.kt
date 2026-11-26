package app.flytrack.feature.airporthealth

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.feature.passport.FakeTravelApi
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.Fixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class AirportHealthRepositoryTest {
    @get:Rule val main = MainDispatcherRule()

    private val api = FakeTravelApi().apply {
        healthAnswers["DEL"] = Json.parseToJsonElement(Fixtures.read("airport_health_pro.json")).jsonObject
        healthAnswers["BOM"] = Json.parseToJsonElement(Fixtures.read("airport_health_free.json")).jsonObject
    }
    private val auth = FakeAuthRepository()
    private val clock = FakeClock()
    private val repo = SupabaseAirportHealthRepository(api, auth, clock, UnconfinedTestDispatcher())

    @Test
    fun `loads, normalises the code and caches for a few minutes`() = runTest {
        val first = repo.health(" del ")
        assertTrue(first is AirportHealthResult.Loaded)
        assertEquals(HealthLevel.SEVERE, (first as AirportHealthResult.Loaded).health.level)
        repo.health("DEL")
        assertEquals(listOf("airportHealth(DEL)"), api.calls)

        clock.advance(6.minutes)
        repo.health("DEL")
        repo.health("DEL", force = true)
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `unknown airports and bad codes are unavailable without a crash`() = runTest {
        assertEquals(AirportHealthResult.Unavailable, repo.health("XYZ")) // RPC returned null
        assertEquals(AirportHealthResult.Unavailable, repo.health("DELHI"))
        assertFalse("a bad code never reaches the server", api.calls.contains("airportHealth(DELHI)"))
        api.failure = BackendException.Http(400, "22023", "invalid_iata")
        assertEquals(AirportHealthResult.Unavailable, repo.health("ZZZ"))
    }

    @Test
    fun `errors are typed and not cached`() = runTest {
        api.failure = BackendException.Network(IOException("down"))
        assertEquals(AirportHealthResult.Offline, repo.health("DEL"))
        api.failure = BackendException.Http(500, null, "boom")
        assertTrue(repo.health("DEL") is AirportHealthResult.Failed)
        api.failure = null
        assertTrue(repo.health("DEL") is AirportHealthResult.Loaded)

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(AirportHealthResult.NotConfigured, repo.health("BLR"))
    }

    @Test
    fun `view model lists only the airports that answered`() = runTest {
        val vm = AirportHealthViewModel(repo)
        vm.load(listOf(HealthSide.DEPARTURE to "DEL", HealthSide.ARRIVAL to "BOM", HealthSide.ARRIVAL to "XYZ"))
        val state = vm.uiState.value
        assertEquals(listOf("DEL", "BOM"), state.entries.map { it.health.iata })
        assertEquals(HealthSide.DEPARTURE, state.entries.first().side)
        assertTrue("BOM came without Pro detail", state.showTeaser)

        vm.load(listOf(HealthSide.DEPARTURE to "DEL", HealthSide.ARRIVAL to "BOM", HealthSide.ARRIVAL to "XYZ"))
        assertEquals("same request twice is not reloaded", 3, api.calls.size)

        vm.load(listOf(HealthSide.ARRIVAL to "DEL"))
        assertFalse(vm.uiState.value.showTeaser)
        vm.load(emptyList())
        assertTrue(vm.uiState.value.entries.isEmpty())
    }
}
