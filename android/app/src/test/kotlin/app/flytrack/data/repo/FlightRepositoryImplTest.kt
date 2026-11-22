package app.flytrack.data.repo

import app.cash.turbine.test
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackSource
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.AuthResult
import app.flytrack.core.result.BadInputReason
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.toEntity
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeBackendApi
import app.flytrack.testing.FakeClock
import app.flytrack.testing.Fixtures
import app.flytrack.testing.InMemoryDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FlightRepositoryImplTest {
    private val scope = TestScope()
    private val api = FakeBackendApi()
    private val auth = FakeAuthRepository()
    private val db = InMemoryDatabase()
    private val clock = FakeClock() // 22 Nov 2026 10:00Z
    private val repo = FlightRepositoryImpl(
        api, auth, db.flightDao, db.trackedDao, db.airportDao, db.transactions, clock, FlyTrackJson,
        UnconfinedTestDispatcher(scope.testScheduler),
    )

    /** [0] = 6E 2341 DEL-BOM dep 12:55Z, [1] = its inbound leg 6E 2032 BLR-DEL dep 08:30Z. */
    private val fixtures = FakeBackendApi.fixtureFlights()
    private val main = fixtures[0]
    private val inbound = fixtures[1]
    private val date = LocalDate.parse("2026-11-25")
    private val offline get() = BackendException.Network(IOException("no route to host"))

    private fun other(id: String, number: String, schedDep: String?) =
        main.copy(id = id, flightNumber = number, schedDep = schedDep, estDep = null, inboundFlightId = null, prediction = null)

    // ---- search -----------------------------------------------------------------------------------

    @Test
    fun `search sends the normalised flight number and returns the legs sorted by departure`() = scope.runTest {
        api.searchHandler = { _, _ -> listOf(main, inbound) }

        val result = repo.search(" 6e-2341 ", date)

        assertEquals(listOf("searchFlight(6E 2341, 2026-11-25)"), api.calls)
        val legs = (result as SearchResult.Found).legs
        assertEquals(listOf("6E 2032", "6E 2341"), legs.map { it.designator })
        assertEquals("New Delhi", legs[1].depAirport?.city)
    }

    @Test
    fun `search results are cached so the flight can be observed before it is tracked`() = scope.runTest {
        api.searchHandler = { _, _ -> listOf(main) }
        repo.search("6E 2341", date)

        val cached = repo.observeFlight(main.id).first()!!
        assertEquals("6E 2341", cached.designator)
        assertEquals("Asia/Kolkata", cached.depTz)
        assertNull(repo.observeTrackedFlight(main.id).first())
    }

    @Test
    fun `search rejects a bad flight number without calling the backend`() = scope.runTest {
        assertEquals(SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER), repo.search("indigo to mumbai", date))
        assertEquals(SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER), repo.search("12 345", date))
        assertTrue(api.calls.isEmpty())
        assertEquals(0, auth.ensureSignedInCalls)
    }

    @Test
    fun `search rejects a date outside the server's window without calling the backend`() = scope.runTest {
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), repo.search("6E 2341", LocalDate.parse("2026-11-19")))
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), repo.search("6E 2341", LocalDate.parse("2030-01-01")))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `search maps the function's error codes`() = scope.runTest {
        suspend fun failing(status: Int, fixture: String?): SearchResult {
            val error = fixture?.let { FlyTrackJson.parseToJsonElement(Fixtures.read(it)).jsonObject }
            api.failures["searchFlight"] = BackendException.Http(
                status,
                error?.get("error")?.jsonPrimitive?.content,
                error?.get("message")?.jsonPrimitive?.content,
            )
            return repo.search("6E 2341", date)
        }
        assertEquals(SearchResult.NotFound, failing(404, "search_error_not_found.json"))
        assertEquals(SearchResult.RateLimited, failing(429, "search_error_rate_limited.json"))
        assertEquals(SearchResult.BadInput(BadInputReason.DATE), failing(400, "search_error_bad_input.json"))
        assertEquals(SearchResult.ProviderError, failing(502, "search_error_provider.json"))
        assertEquals(SearchResult.ProviderError, failing(500, null))
        assertEquals("a 404 without the function's code means the function is missing", SearchResult.ProviderError, failing(404, null))
        assertEquals("the platform's own rate limit", SearchResult.RateLimited, failing(429, null))
    }

    @Test
    fun `search reports other bad input reasons`() = scope.runTest {
        api.failures["searchFlight"] = BackendException.Http(400, "bad_input", "Flight number looks wrong. Example: 6E 2341")
        assertEquals(SearchResult.BadInput(BadInputReason.FLIGHT_NUMBER), repo.search("6E 2341", date))
        api.failures["searchFlight"] = BackendException.Http(400, "bad_input", "JSON body required")
        assertEquals(SearchResult.BadInput(BadInputReason.OTHER), repo.search("6E 2341", date))
    }

    @Test
    fun `search with no legs, offline and not configured`() = scope.runTest {
        assertEquals(SearchResult.NotFound, repo.search("6E 2341", date))

        api.failure = offline
        assertEquals(SearchResult.Offline, repo.search("6E 2341", date))

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(SearchResult.NotConfigured, repo.search("6E 2341", date))

        auth.nextResult = AuthResult.Offline
        assertEquals("first launch without a connection: no session yet", SearchResult.Offline, repo.search("6E 2341", date))
    }

    // ---- track ------------------------------------------------------------------------------------

    @Test
    fun `tracking a flight makes it appear in the list with its airports`() = scope.runTest {
        api.addFlights(main)

        repo.observeTrackedFlights().test {
            assertTrue(awaitItem().isEmpty())

            val result = repo.track(main.id, TrackExtras(pnr = "abc 123", seat = "14c", cabin = Cabin.ECONOMY, source = TrackSource.PDF, note = " Window "))

            val tracking = (result as TrackResult.Tracked).tracking
            assertEquals("ABC123", tracking.pnr)
            assertEquals("14C", tracking.seat)
            assertEquals(Cabin.ECONOMY, tracking.cabin)
            assertEquals(TrackSource.PDF, tracking.source)
            assertEquals("Window", tracking.note)

            val item = expectMostRecentItem().single()
            assertEquals(tracking, item.tracking)
            assertEquals("6E 2341", item.flight.designator)
            assertEquals("Mumbai", item.flight.arrAirport?.city)
        }
        assertEquals("pdf", api.serverUserFlights.single().source)
    }

    @Test
    fun `invalid extras are rejected per field before any network call`() = scope.runTest {
        api.addFlights(main)
        assertEquals(TrackResult.InvalidInput(TrackField.PNR), repo.track(main.id, TrackExtras(pnr = "AB1")))
        assertEquals(TrackResult.InvalidInput(TrackField.SEAT), repo.track(main.id, TrackExtras(seat = "14CCCC")))
        assertEquals(TrackResult.InvalidInput(TrackField.NOTE), repo.track(main.id, TrackExtras(note = "x".repeat(201))))
        assertTrue(api.calls.isEmpty())

        assertTrue("blank extras mean 'not given'", repo.track(main.id, TrackExtras(pnr = " ", seat = "", note = " ")) is TrackResult.Tracked)
        assertNull(api.serverUserFlights.single().pnr)
    }

    @Test
    fun `the database's tracking limit becomes TrackingLimitReached`() = scope.runTest {
        api.addFlights(main, inbound)
        api.trackingLimit = 1
        assertTrue(repo.track(main.id) is TrackResult.Tracked)

        val result = repo.track(inbound.id)

        assertEquals(TrackResult.TrackingLimitReached("Archive an old flight or upgrade to Pro"), result)
        assertEquals(1, repo.observeTrackedFlights().first().size)
    }

    @Test
    fun `tracking twice reports AlreadyTracked and changes nothing`() = scope.runTest {
        api.addFlights(main)
        val first = (repo.track(main.id, TrackExtras(pnr = "ABC123")) as TrackResult.Tracked).tracking

        val second = repo.track(main.id, TrackExtras(pnr = "ZZZ999"))

        assertEquals(TrackResult.AlreadyTracked(first), second)
        assertEquals("ABC123", api.serverUserFlights.single().pnr)
    }

    @Test
    fun `tracking an archived flight un-archives it`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)
        repo.archive(main.id)
        assertTrue(repo.observeTrackedFlights().first().isEmpty())

        val result = repo.track(main.id, TrackExtras(seat = "2A"))

        val tracking = (result as TrackResult.Tracked).tracking
        assertFalse(tracking.isArchived)
        assertEquals("2A", tracking.seat)
        assertEquals(listOf(main.id), repo.observeTrackedFlights().first().map { it.flight.id })
        assertEquals(1, api.serverUserFlights.size)
    }

    @Test
    fun `a tracked row the device has not synced yet is adopted`() = scope.runTest {
        api.addFlights(main)
        api.serverUserFlights += FakeBackendApi.fixtureUserFlights()[0] // tracked on another phone

        val result = repo.track(main.id)

        assertTrue(result is TrackResult.AlreadyTracked)
        assertEquals("ABC123", repo.observeTrackedFlight(main.id).first()?.tracking?.pnr)
    }

    @Test
    fun `tracking failures`() = scope.runTest {
        assertEquals("flight row is gone on the server", TrackResult.FlightNotFound, repo.track("no-such-flight"))

        api.addFlights(main)
        api.failure = offline
        assertEquals(TrackResult.Offline, repo.track(main.id))

        api.failure = BackendException.Http(403, "42501", "permission denied for table user_flights")
        assertEquals(TrackResult.Failed("permission denied for table user_flights"), repo.track(main.id))

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(TrackResult.NotConfigured, repo.track(main.id))
        assertTrue(db.tracked.value.isEmpty())
    }

    @Test
    fun `tracking still succeeds when the follow-up flight fetch fails`() = scope.runTest {
        api.searchHandler = { _, _ -> listOf(main) }
        repo.search("6E 2341", date) // flight is cached from the search
        api.failures["flightViews"] = offline

        assertTrue(repo.track(main.id) is TrackResult.Tracked)

        assertEquals(listOf(main.id), repo.observeTrackedFlights().first().map { it.flight.id })
    }

    // ---- untrack / archive / prefs ------------------------------------------------------------------

    @Test
    fun `untrack deletes the row on the server and locally`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)

        assertEquals(ActionResult.Success, repo.untrack(main.id))

        assertTrue(api.serverUserFlights.isEmpty())
        assertTrue(repo.observeTrackedFlights().first().isEmpty())
        assertNull(repo.observeTrackedFlight(main.id).first())
        assertEquals(ActionResult.NotFound, repo.untrack(main.id))
    }

    @Test
    fun `untrack keeps the local row when the server cannot be reached`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)
        api.failure = offline

        assertEquals(ActionResult.Offline, repo.untrack(main.id))
        assertEquals(1, repo.observeTrackedFlights().first().size)
    }

    @Test
    fun `archive hides the flight from the list but keeps it observable`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)

        assertEquals(ActionResult.Success, repo.archive(main.id))

        assertTrue(repo.observeTrackedFlights().first().isEmpty())
        val archived = repo.observeTrackedFlight(main.id).first()!!
        assertEquals(clock.now(), archived.tracking.archivedAt)
        assertEquals(clock.now().toString(), api.serverUserFlights.single().archivedAt)

        assertEquals(ActionResult.Success, repo.archive(main.id, archived = false))
        assertEquals(1, repo.observeTrackedFlights().first().size)
        assertNull(api.serverUserFlights.single().archivedAt)
    }

    @Test
    fun `un-archiving beyond the limit is reported`() = scope.runTest {
        api.addFlights(main, inbound)
        api.trackingLimit = 1
        repo.track(main.id)
        repo.archive(main.id)
        repo.track(inbound.id)

        assertEquals(ActionResult.TrackingLimitReached, repo.archive(main.id, archived = false))
        assertTrue(repo.observeTrackedFlight(main.id).first()!!.tracking.isArchived)
    }

    @Test
    fun `notify prefs are merged and unknown server keys survive`() = scope.runTest {
        api.addFlights(main)
        api.serverUserFlights += FakeBackendApi.fixtureUserFlights()[0] // DEPARTED=false, LANDED=true, FUTURE_TYPE=false
        repo.refresh()

        val result = repo.updateNotifyPrefs(main.id, mapOf(AlertType.DEPARTED to true, AlertType.GATE_ASSIGNED to false))

        assertEquals(ActionResult.Success, result)
        assertEquals(
            mapOf("DEPARTED" to true, "LANDED" to true, "FUTURE_TYPE" to false, "GATE_ASSIGNED" to false),
            api.serverUserFlights.single().notifyPrefs,
        )
        val tracking = repo.observeTrackedFlight(main.id).first()!!.tracking
        assertTrue(tracking.notifies(AlertType.DEPARTED))
        assertFalse(tracking.notifies(AlertType.GATE_ASSIGNED))
        assertEquals(ActionResult.NotFound, repo.updateNotifyPrefs("unknown-flight", mapOf(AlertType.DELAY to false)))
    }

    @Test
    fun `tracking details can be changed and cleared`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id, TrackExtras(pnr = "ABC123", seat = "14C", note = "old"))

        assertEquals(ActionResult.Success, repo.updateTrackingDetails(main.id, TrackExtras(pnr = "xyz789", cabin = Cabin.BUSINESS)))

        val tracking = repo.observeTrackedFlight(main.id).first()!!.tracking
        assertEquals("XYZ789", tracking.pnr)
        assertNull(tracking.seat)
        assertNull(tracking.note)
        assertEquals(Cabin.BUSINESS, tracking.cabin)
        assertEquals(ActionResult.InvalidInput(TrackField.PNR), repo.updateTrackingDetails(main.id, TrackExtras(pnr = "!!")))
        assertEquals("nothing was changed", "XYZ789", api.serverUserFlights.single().pnr)
    }

    // ---- refresh ------------------------------------------------------------------------------------

    @Test
    fun `refresh pulls tracked flights, their flight rows and the inbound leg into Room`() = scope.runTest {
        api.addFlights(main, inbound)
        api.serverUserFlights += FakeBackendApi.fixtureUserFlights()[0]

        assertEquals(ActionResult.Success, repo.refresh())

        val item = repo.observeTrackedFlights().first().single()
        assertEquals("6E 2341", item.flight.designator)
        assertEquals("ABC123", item.tracking.pnr)
        assertEquals(inbound.id, item.flight.inboundFlightId)
        assertEquals("the Pro user's inbound flight is cached too", "6E 2032", repo.observeFlight(inbound.id).first()?.designator)
        assertEquals(listOf("userFlights", "flightViews(${main.id})", "flightViews(${inbound.id})"), api.calls)
    }

    @Test
    fun `refresh replaces local rows that no longer exist on the server`() = scope.runTest {
        api.addFlights(main, inbound)
        repo.track(main.id)
        repo.track(inbound.id)
        api.serverUserFlights.removeAll { it.flightId == inbound.id } // removed on another phone

        repo.refresh()

        assertEquals(listOf(main.id), repo.observeTrackedFlights().first().map { it.flight.id })
    }

    @Test
    fun `tracked flights are ordered by best departure, unknown times last`() = scope.runTest {
        val early = other("f-early", "100", "2026-11-24T03:00:00+00:00")
        val unknown = other("f-unknown", "300", null)
        api.addFlights(main, early, unknown)
        listOf(unknown, main, early).forEach { repo.track(it.id) }

        assertEquals(listOf("f-early", main.id, "f-unknown"), repo.observeTrackedFlights().first().map { it.flight.id })
    }

    @Test
    fun `refresh failure keeps the cache`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)

        api.failure = offline
        assertEquals(ActionResult.Offline, repo.refresh())
        assertEquals(1, repo.observeTrackedFlights().first().size)

        api.failure = BackendException.Http(500, null, "boom")
        assertEquals(ActionResult.Failed("boom"), repo.refresh())

        auth.nextResult = AuthResult.NotConfigured
        assertEquals(ActionResult.NotConfigured, repo.refresh())
        assertEquals(1, repo.observeTrackedFlights().first().size)
    }

    @Test
    fun `refresh with nothing tracked makes one request and clears the list`() = scope.runTest {
        api.addFlights(main)
        repo.track(main.id)
        api.serverUserFlights.clear()
        api.calls.clear()

        assertEquals(ActionResult.Success, repo.refresh())
        assertEquals(listOf("userFlights"), api.calls)
        assertTrue(repo.observeTrackedFlights().first().isEmpty())
    }

    @Test
    fun `refresh drops old untracked flights but keeps fresh search results`() = scope.runTest {
        val stale = other("f-stale", "900", "2026-11-10T03:00:00+00:00").copy(updatedAt = (clock.now() - 8.days).toString())
        val fresh = other("f-fresh", "901", "2026-11-25T03:00:00+00:00").copy(updatedAt = clock.now().toString())
        db.flightDao.upsert(listOf(stale.toEntity(), fresh.toEntity()))

        repo.refresh()

        assertNull(repo.observeFlight("f-stale").first())
        assertEquals("6E 901", repo.observeFlight("f-fresh").first()?.designator)
    }

    // ---- push-triggered sync ------------------------------------------------------------------------

    private fun compactJson(): String =
        FlyTrackJson.parseToJsonElement(Fixtures.read("fcm_messages.json")).jsonObject
            .getValue("sync").jsonObject.getValue("flight").jsonPrimitive.content

    private fun FlightViewDto.older() = copy(updatedAt = "2026-11-25T10:41:07+00:00")

    @Test
    fun `sync message is written to Room even when the network is down`() = scope.runTest {
        api.addFlights(main.older())
        repo.track(main.id)
        api.failure = offline

        val result = repo.applyServerSync(main.id, compactJson())

        assertEquals(ActionResult.Success, result)
        val flight = repo.observeTrackedFlight(main.id).first()!!.flight
        assertEquals(FlightStatus.BOARDING, flight.status)
        assertEquals("31", flight.depGate)
        assertEquals(Instant.parse("2026-11-25T13:35:00Z"), flight.estDep)
        assertEquals("airports stay joined", "Mumbai", flight.arrAirport?.city)
        assertEquals("Pro data is kept", 45, flight.prediction?.lateMin)
    }

    @Test
    fun `sync then fetches the full row from the server`() = scope.runTest {
        api.addFlights(main.older())
        repo.track(main.id)
        api.addFlights(main.copy(status = "boarding", depGate = "31", delayReason = "Air traffic control", updatedAt = "2026-11-25T12:41:11+00:00"))

        assertEquals(ActionResult.Success, repo.applyServerSync(main.id, compactJson()))

        val flight = repo.observeFlight(main.id).first()!!
        assertEquals("31", flight.depGate)
        assertEquals("Air traffic control", flight.delayReason) // only the full row carries it
    }

    @Test
    fun `sync without payload fetches the flight, and reports failures`() = scope.runTest {
        api.addFlights(main)
        assertEquals(ActionResult.Success, repo.applyServerSync(main.id))
        assertEquals("6E 2341", repo.observeFlight(main.id).first()?.designator)

        assertEquals(ActionResult.NotFound, repo.applyServerSync("no-such-flight"))
        api.failure = offline
        assertEquals(ActionResult.Offline, repo.applyServerSync(main.id))
    }

    @Test
    fun `a payload for another flight or unreadable JSON is not applied`() = scope.runTest {
        api.failure = offline
        assertEquals(ActionResult.Offline, repo.applyServerSync("some-other-flight", compactJson()))
        assertEquals(ActionResult.Offline, repo.applyServerSync(main.id, "{broken"))
        assertTrue(db.flights.value.isEmpty())
    }

    @Test
    fun `an outdated sync message does not overwrite newer data`() = scope.runTest {
        api.addFlights(main.copy(depGate = "40", updatedAt = "2026-11-25T12:50:00+00:00"))
        repo.track(main.id)
        api.failure = offline

        repo.applyServerSync(main.id, compactJson()) // payload is from 12:41

        assertEquals("40", repo.observeFlight(main.id).first()?.depGate)
    }
}
