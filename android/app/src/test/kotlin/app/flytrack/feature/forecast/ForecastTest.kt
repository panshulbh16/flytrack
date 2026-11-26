package app.flytrack.feature.forecast

import app.flytrack.core.model.FlightStatus
import app.flytrack.data.remote.BackendException
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.caller
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Instant

class ForecastTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun parsesProAnswer() {
        val forecast = parseFlightForecast(Json.parseToJsonElement(PRO))!!
        assertEquals(ForecastHistory(23, 60, 61, 14, 6, 1), forecast.history)
        assertEquals(
            ArrivalForecast(Instant.parse("2026-11-25T15:16:00Z"), Instant.parse("2026-11-25T15:31:00Z"), ForecastMethod.HISTORY, ForecastConfidence.HIGH),
            forecast.forecast,
        )
        assertEquals(false, forecast.detailLocked)
    }

    @Test fun parsesLockedAndEmptyAnswers() {
        val locked = parseFlightForecast(Json.parseToJsonElement("""{"history":null,"forecast":null,"detail_locked":true}"""))!!
        assertTrue(locked.detailLocked)
        assertNull(locked.history)
        assertNull(parseFlightForecast(JsonNull))
        val cancelled = parseFlightForecast(Json.parseToJsonElement("""{"history":{"samples":0,"window_days":60,"cancelled":0},"forecast":null,"detail_locked":false}"""))!!
        assertTrue(cancelled.isEmpty)
    }

    @Test fun scheduleMethodAndOddValues() {
        val f = parseFlightForecast(
            Json.parseToJsonElement(
                """{"history":{"samples":2,"window_days":60,"on_time_pct":null,"avg_dep_delay_min":null,"median_arr_delay_min":null,"cancelled":0,"basis":"route"},
                    "forecast":{"arr_p50":"2026-11-25T15:10:00Z","arr_p80":"2026-11-25T15:00:00Z","method":"schedule","confidence":"whatever"},
                    "detail_locked":false}""",
            ),
        )!!
        val arrival = f.forecast!!
        assertEquals(ForecastMethod.SCHEDULE, arrival.method)
        assertEquals(ForecastConfidence.LOW, arrival.confidence)
        assertNull(arrival.p80) // earlier than p50: dropped
        assertNull(f.history!!.onTimePct)
    }

    @Test fun stateFollowsFlightAndAnswer() {
        val answer = parseFlightForecast(Json.parseToJsonElement(PRO))!!
        val flight = TestData.flight()
        val ready = forecastState(flight, answer) as ForecastUiState.Ready
        assertEquals("Asia/Kolkata", ready.arrTz)
        assertEquals(flight.schedArr, ready.scheduledArrival)
        assertEquals(ForecastUiState.Hidden, forecastState(flight, null))
        assertEquals(ForecastUiState.Hidden, forecastState(TestData.flight(status = FlightStatus.CANCELLED), answer))
        assertEquals(ForecastUiState.Hidden, forecastState(TestData.flight(status = FlightStatus.LANDED, actArr = TestData.NOW), answer))
        assertEquals(ForecastUiState.Locked, forecastState(flight, FlightForecast(null, null, true)))
    }

    @Test fun viewModelAsksTheRpcWithTheFlightId() = runTest {
        val api = FakeWave2Api().apply { answers["flight_forecast"] = Json.parseToJsonElement(PRO) }
        val flights = FakeFlightRepository().apply { setTracked(TestData.tracked()) }
        val vm = ForecastViewModel(TestData.FLIGHT_ID, SupabaseForecastRepository(caller(api)), flights)
        assertTrue(vm.uiState.value is ForecastUiState.Ready)
        assertEquals(TestData.FLIGHT_ID, api.calls.single().second.getValue("p_flight_id").jsonPrimitive.content)

        // A delay asks again; an error keeps the earlier answer.
        api.answers["flight_forecast"] = BackendException.Network(java.io.IOException())
        flights.setTracked(TestData.tracked(TestData.flight(estDep = Instant.parse("2026-11-25T13:30:00Z"))))
        assertEquals(2, api.calls.size)
        assertTrue(vm.uiState.value is ForecastUiState.Ready)
    }

    @Test fun repositoryTypesErrors() = runTest {
        val api = FakeWave2Api().apply { answers["flight_forecast"] = BackendException.Http(403, "42501", "forbidden") }
        assertEquals(RemoteResult.Refused("42501", "forbidden"), SupabaseForecastRepository(caller(api)).forecast(TestData.FLIGHT_ID))
    }

    private companion object {
        val PRO = """
            {"history":{"samples":23,"window_days":60,"on_time_pct":61,"avg_dep_delay_min":14,"median_arr_delay_min":6,"cancelled":1,"basis":"flight"},
             "forecast":{"arr_p50":"2026-11-25T15:16:00Z","arr_p80":"2026-11-25T15:31:00Z","method":"history","confidence":"high"},
             "detail_locked":false}
        """.trimIndent()
    }
}
