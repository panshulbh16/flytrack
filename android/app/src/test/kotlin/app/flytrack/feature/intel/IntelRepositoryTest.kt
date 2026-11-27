package app.flytrack.feature.intel

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** [IntelApi] for tests: answers per RPC name, records every call. */
class FakeIntelApi : IntelApi {
    /** RPC name to answer (a JSON text or a [BackendException] to throw); missing = SQL null. */
    val answers = mutableMapOf<String, Any>()
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var aircraftColumns: Any = JsonNull
    var swapEvents: Any = "[]"

    private fun answer(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is BackendException -> throw value
        is JsonElement -> value
        is String -> FlyTrackJson.parseToJsonElement(value)
        else -> error("unexpected fake answer $value")
    }

    override suspend fun rpc(name: String, args: JsonObject): JsonElement {
        calls += name to args
        return answer(answers[name])
    }

    override suspend fun aircraftColumns(flightId: String): JsonElement = answer(aircraftColumns)

    override suspend fun aircraftSwapEvents(flightId: String): JsonElement = answer(swapEvents)
}

private fun proRequired() = BackendException.Http(400, "42501", "pro_required")

class IntelRepositoryTest {

    private fun TestScope.repository(api: IntelApi, auth: FakeAuthRepository = FakeAuthRepository(), clock: FakeClock = FakeClock()) =
        SupabaseIntelRepository(api, auth, FlyTrackJson, clock, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `timeline calls the RPC with the contract's argument`() = runTest {
        val api = FakeIntelApi().apply { answers["flight_timeline"] = """[{"key":"scheduled","at":"2026-11-25T12:55:00Z","state":"current"}]""" }
        val result = repository(api).timeline(TestData.FLIGHT_ID)

        assertEquals(TimelineKey.SCHEDULED, (result as IntelResult.Ok).value.single().key)
        assertEquals("flight_timeline" to TestData.FLIGHT_ID, api.calls.single().let { it.first to it.second["p_flight_id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `pro_required becomes Locked, other refusals Failed, network Offline`() = runTest {
        val api = FakeIntelApi().apply {
            answers["delay_prediction"] = proRequired()
            answers["gate_prediction"] = BackendException.Http(400, "42501", "not_allowed")
            answers["my_connections"] = BackendException.Network(java.io.IOException("down"))
        }
        val repo = repository(api)
        assertEquals(IntelResult.Locked, repo.delayPrediction(TestData.FLIGHT_ID, "en"))
        assertTrue(repo.gatePrediction(TestData.FLIGHT_ID) is IntelResult.Failed)
        assertEquals(IntelResult.Offline, repo.connections())
        assertEquals("p_lang is sent", "en", api.calls.first().second["p_lang"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sign-in problems short-circuit without a call`() = runTest {
        val api = FakeIntelApi()
        val auth = FakeAuthRepository().apply { nextResult = AuthResult.Offline }
        assertEquals(IntelResult.Offline, repository(api, auth).timeline(TestData.FLIGHT_ID))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an unreadable answer is a failure, not a crash`() = runTest {
        val api = FakeIntelApi().apply { answers["flight_timeline"] = """[{"key":{"nested":true}}]""" }
        assertTrue(repository(api).timeline(TestData.FLIGHT_ID) is IntelResult.Failed)
    }

    @Test
    fun `taxi stats and trends are cached for a while, failures are not`() = runTest {
        val clock = FakeClock()
        val api = FakeIntelApi().apply {
            answers["taxi_stats"] = """{"iata":"DEL","taxi_out":{"p50":18,"p80":27,"samples":312}}"""
            answers["airport_trends"] = proRequired()
        }
        val repo = repository(api, clock = clock)

        repo.taxiStats("del")
        repo.taxiStats("DEL")
        assertEquals(1, api.calls.count { it.first == "taxi_stats" })
        assertEquals("DEL", api.calls.first().second["p_iata"]!!.jsonPrimitive.content)
        clock.advance(31.minutes)
        repo.taxiStats("DEL")
        assertEquals(2, api.calls.count { it.first == "taxi_stats" })

        assertEquals(IntelResult.Locked, repo.airportTrends("DEL"))
        assertEquals(IntelResult.Locked, repo.airportTrends("DEL"))
        assertEquals(2, api.calls.count { it.first == "airport_trends" })
        assertEquals("14", api.calls.last().second["p_days"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an invalid airport code is not sent`() = runTest {
        val api = FakeIntelApi()
        assertEquals(IntelResult.Ok(null), repository(api).taxiStats("DELHI"))
        assertEquals(IntelResult.Ok(null), repository(api).airportTrends(""))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `aircraft combines the flight_view columns and the swap events`() = runTest {
        val api = FakeIntelApi().apply {
            aircraftColumns = """{"aircraft_icao_type":"A21N","aircraft_age_years":8.4}"""
            swapEvents = """[{"payload":{"old_reg":"VT-IJK","new_reg":"VT-ISR"},"created_at":"2026-11-25T09:00:00Z"}]"""
        }
        val info = (repository(api).aircraft(TestData.FLIGHT_ID) as IntelResult.Ok).value
        assertEquals("A21N", info.icaoType)
        assertEquals("VT-ISR", info.swaps.single().newReg)
    }
}

class IntelViewModelsTest {
    @get:Rule val main = MainDispatcherRule()

    private val now = Instant.parse("2026-11-25T08:00:00Z")

    private fun TestScope.repo(api: FakeIntelApi, clock: FakeClock) =
        SupabaseIntelRepository(api, FakeAuthRepository(), FlyTrackJson, clock, UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `detail intel loads every block for an upcoming Pro flight`() = runTest {
        val clock = FakeClock(now)
        val api = FakeIntelApi().apply {
            answers["flight_timeline"] = """[{"key":"scheduled","at":"2026-11-25T12:55:00Z","state":"current","detail":{"sched_dep":"2026-11-25T12:55:00Z"}}]"""
            answers["delay_prediction"] = """{"p_delay_15":0.42,"p_delay_60":0.08,"expected_delay_min":17,"factors":[],"confidence":"high"}"""
            answers["gate_prediction"] = """{"gate":"42B","share":0.78,"count":7,"samples":9}"""
            answers["taxi_stats"] = """{"taxi_out":{"p50":18,"p80":27,"samples":312},"taxi_in":{"p50":7,"p80":9,"samples":200}}"""
            answers["my_connections"] = """[{"in_flight_id":"${TestData.FLIGHT_ID}","out_flight_id":"x","airport":"BOM","layover_min":85,"mct_min":75,"buffer_min":10,"risk":"tight"}]"""
            aircraftColumns = """{"aircraft_icao_type":"A21N","aircraft_age_years":8.4}"""
        }
        val flights = FakeFlightRepository().apply { setTracked(TestData.tracked()) }
        val vm = FlightIntelViewModel(TestData.FLIGHT_ID, repo(api, clock), flights, clock)

        val state = vm.uiState.first { it.timeline != null && it.prediction is PredictionBlockUiModel.Ready && it.aircraft != null }
        val ready = state.prediction as PredictionBlockUiModel.Ready
        assertEquals(17, ready.delay?.expectedDelayMin)
        assertEquals("42B", ready.gate?.gate)
        assertEquals(18, ready.taxiOut?.typicalMin)
        assertEquals(7, ready.taxiIn?.typicalMin)
        assertEquals("18:25", state.timeline!!.rows.single().time)
        assertEquals(8.4, state.aircraft!!.ageYears!!, 1e-9)
        assertEquals(ConnectionRisk.TIGHT, state.connections.single().risk)
        assertTrue(api.calls.any { it.first == "delay_prediction" })
    }

    @Test
    fun `a departed flight is not asked for predictions, a new status asks again`() = runTest {
        val clock = FakeClock(now)
        val api = FakeIntelApi().apply { answers["flight_timeline"] = "[]" }
        val departed = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = now)
        val flights = FakeFlightRepository().apply { setTracked(TestData.tracked(departed)) }
        val vm = FlightIntelViewModel(TestData.FLIGHT_ID, repo(api, clock), flights, clock)
        vm.uiState.first()

        assertTrue(api.calls.none { it.first == "delay_prediction" || it.first == "gate_prediction" })
        assertEquals("taxi-in at the arrival only", listOf("BOM"), api.calls.filter { it.first == "taxi_stats" }.map { it.second["p_iata"]!!.jsonPrimitive.content })

        val timelineCalls = api.calls.count { it.first == "flight_timeline" }
        flights.setTracked(TestData.tracked(departed.copy(status = FlightStatus.LANDED, actArr = now)))
        vm.uiState.first()
        assertEquals(timelineCalls + 1, api.calls.count { it.first == "flight_timeline" })
    }

    @Test
    fun `connections list - Pro rows, teaser for free users with a trip`() = runTest {
        val clock = FakeClock(now)
        val onward = TestData.flight(
            id = TestData.OTHER_FLIGHT_ID, carrierIata = "AI", flightNumber = "805", depAirport = TestData.BOM, arrAirport = TestData.DEL,
            schedDep = Instant.parse("2026-11-25T16:35:00Z"), schedArr = Instant.parse("2026-11-25T18:45:00Z"),
        )
        val flights = FakeFlightRepository().apply { setTracked(TestData.tracked(), TestData.tracked(onward)) }

        val free = FakeIntelApi().apply { answers["my_connections"] = proRequired() }
        val freeVm = ConnectionsViewModel(repo(free, clock), flights, clock)
        assertEquals(ConnectionsUiState.Teaser(1), freeVm.uiState.first { it != ConnectionsUiState.Hidden })

        val pro = FakeIntelApi().apply {
            answers["my_connections"] = """[{"in_flight_id":"${TestData.FLIGHT_ID}","out_flight_id":"${TestData.OTHER_FLIGHT_ID}","airport":"BOM","in_flight":"6E 2341","out_flight":"AI 805","layover_min":85,"mct_min":75,"buffer_min":10,"risk":"tight","out_dep":"2026-11-25T16:35:00Z"}]"""
        }
        val proVm = ConnectionsViewModel(repo(pro, clock), flights, clock)
        val ready = proVm.uiState.first { it is ConnectionsUiState.Ready } as ConnectionsUiState.Ready
        assertEquals("22:05", ready.connections.single().departure)
    }

    @Test
    fun `connections are not asked for with a single tracked flight`() = runTest {
        val clock = FakeClock(now)
        val api = FakeIntelApi()
        val flights = FakeFlightRepository().apply { setTracked(TestData.tracked()) }
        val vm = ConnectionsViewModel(repo(api, clock), flights, clock)
        assertEquals(ConnectionsUiState.Hidden, vm.uiState.first())
        vm.refresh()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `trends load once and map every outcome`() = runTest {
        val clock = FakeClock(now)
        val api = FakeIntelApi().apply { answers["airport_trends"] = """{"iata":"DEL","days":14,"by_hour":[{"hour":6,"avg_dep_delay":12,"delayed_pct":28,"samples":40}],"by_day":[]}""" }
        val vm = AirportTrendsViewModel(repo(api, clock))
        vm.load("DEL")
        vm.load("DEL")
        assertTrue(vm.uiState.value is TrendsUiState.Ready)
        assertEquals(1, api.calls.size)

        assertEquals(TrendsUiState.Locked, trendsState(IntelResult.Locked))
        assertEquals(TrendsUiState.Empty, trendsState(IntelResult.Ok(null)))
        assertEquals(TrendsUiState.Offline, trendsState(IntelResult.Offline))
        assertEquals(TrendsUiState.Failed, trendsState(IntelResult.Failed("x")))
    }
}
