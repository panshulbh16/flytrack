package app.flytrack.data.repo

import app.flytrack.core.model.RouteSearchQuery
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackSource
import app.flytrack.core.result.BadInputReason
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.remote.dto.SearchFlightResponse
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.InMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3 additions: past search (B6), route search (B5) and archived tracking for Passport. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightRepositoryP3Test {
    private val scope = TestScope()
    private val api = FakeBackendApi()
    private val db = InMemoryDatabase()
    private val clock = FakeClock() // 22 Nov 2026 10:00Z
    private val repo = FlightRepositoryImpl(
        api, FakeAuthRepository(), db.flightDao, db.trackedDao, db.airportDao, db.transactions, clock, FlyTrackJson,
        UnconfinedTestDispatcher(scope.testScheduler),
    )
    private val main = FakeBackendApi.fixtureFlights()[0]
    private val bodies = mutableListOf<String>()

    private fun answer(function: String, vararg flights: app.flytrack.data.remote.dto.FlightViewDto) {
        api.functionHandlers[function] = { body ->
            bodies += body
            flights.forEach { api.serverFlights[it.id] = it }
            FlyTrackJson.encodeToString(SearchFlightResponse.serializer(), SearchFlightResponse(flights.toList()))
        }
    }

    // ---- past search --------------------------------------------------------------------------------

    @Test
    fun `past search sends mode past with the normalised number and caches the legs`() = scope.runTest {
        answer("search-flight", main)

        val result = repo.searchPast("6e2341", LocalDate.parse("2026-03-01"))

        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("6E 2341", body["flight"]!!.jsonPrimitive.content)
        assertEquals("2026-03-01", body["date"]!!.jsonPrimitive.content)
        assertEquals("past", body["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf(main.id), (result as SearchResult.Found).legs.map { it.id })
        assertNotNull(repo.observeFlight(main.id).first())
    }

    @Test
    fun `past search checks the number and the year-back window before any call`() = scope.runTest {
        assertEquals(SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER), repo.searchPast("AIR", LocalDate.parse("2026-03-01")))
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), repo.searchPast("6E 2341", LocalDate.parse("2025-11-01")))
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), repo.searchPast("6E 2341", LocalDate.parse("2026-11-25")))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the normal search keeps its body without a mode`() = scope.runTest {
        api.searchHandler = { _, _ -> listOf(main) }
        repo.search("6E 2341", LocalDate.parse("2026-11-25"))
        assertTrue(api.calls.single().startsWith("searchFlight("))
    }

    // ---- route search -------------------------------------------------------------------------------

    @Test
    fun `route search sends the upper-cased query and returns the departures sorted`() = scope.runTest {
        answer("search-route", main)

        val result = repo.searchRoute(RouteSearchQuery(dep = "del", arr = " bom ", date = LocalDate.parse("2026-11-28"), airline = "6e"))

        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("DEL", body["dep"]!!.jsonPrimitive.content)
        assertEquals("BOM", body["arr"]!!.jsonPrimitive.content)
        assertEquals("6E", body["airline"]!!.jsonPrimitive.content)
        assertEquals("2026-11-28", body["date"]!!.jsonPrimitive.content)
        assertTrue(result is SearchResult.Found)
    }

    @Test
    fun `route search omits empty filters`() = scope.runTest {
        answer("search-route", main)
        repo.searchRoute(RouteSearchQuery(dep = "DEL", arr = "", date = LocalDate.parse("2026-11-28"), airline = " "))
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertNull(body["arr"])
        assertNull(body["airline"])
    }

    @Test
    fun `route search rejects bad airports and dates locally`() = scope.runTest {
        val date = LocalDate.parse("2026-11-28")
        assertEquals(SearchResult.BadInput(BadInputReason.OTHER), repo.searchRoute(RouteSearchQuery("DE", null, date)))
        assertEquals(SearchResult.BadInput(BadInputReason.OTHER), repo.searchRoute(RouteSearchQuery("DEL", "DEL", date)))
        assertEquals(SearchResult.BadInput(BadInputReason.OTHER), repo.searchRoute(RouteSearchQuery("DEL", null, date, airline = "6")))
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), repo.searchRoute(RouteSearchQuery("DEL", null, LocalDate.parse("2027-01-10"))))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `route search maps the function's errors like the flight search`() = scope.runTest {
        val q = RouteSearchQuery("DEL", "BOM", LocalDate.parse("2026-11-28"))
        api.functionHandlers["search-route"] = { throw BackendException.Http(429, "rate_limited", "Too many") }
        assertEquals(SearchResult.RateLimited, repo.searchRoute(q))
        api.functionHandlers["search-route"] = { throw BackendException.Http(404, "not_found", "none") }
        assertEquals(SearchResult.NotFound, repo.searchRoute(q))
        api.functionHandlers["search-route"] = { """{"flights":[]}""" }
        assertEquals(SearchResult.NotFound, repo.searchRoute(q))
        api.functionHandlers["search-route"] = { "not json" }
        assertEquals(SearchResult.ProviderError, repo.searchRoute(q))
        api.functionHandlers.remove("search-route")
        assertEquals("an undeployed function is a provider error", SearchResult.ProviderError, repo.searchRoute(q))
    }

    // ---- past flights into Passport --------------------------------------------------------------

    @Test
    fun `a past flight is inserted archived and never counts against the cap`() = scope.runTest {
        api.addFlights(main)
        api.trackingLimit = 0

        val result = repo.track(main.id, TrackExtras(source = TrackSource.MANUAL, archived = true))

        val tracking = (result as TrackResult.Tracked).tracking
        assertTrue(tracking.isArchived)
        assertEquals(clock.now().toString(), api.serverUserFlights.single().archivedAt)
        assertTrue("an archived flight is not in the active list", repo.observeTrackedFlights().first().isEmpty())
        assertNotNull(repo.observeTrackedFlight(main.id).first())
    }

    @Test
    fun `adding a past flight never un-archives or changes an existing row`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id, TrackExtras(archived = true))
        val before = api.serverUserFlights.single()

        val again = repo.track(main.id, TrackExtras(archived = true))

        assertTrue(again is TrackResult.AlreadyTracked)
        assertEquals(before, api.serverUserFlights.single())
        assertFalse(api.calls.any { it.startsWith("updateUserFlight") })
    }

    @Test
    fun `tracking normally still un-archives an archived flight`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id, TrackExtras(archived = true))

        val result = repo.track(main.id, TrackExtras())

        assertTrue(result is TrackResult.Tracked)
        assertNull(api.serverUserFlights.single().archivedAt)
    }
}
