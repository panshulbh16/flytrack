package app.flytrack.feature.addflight

import app.cash.turbine.test
import app.flytrack.core.model.RouteSearchQuery
import app.flytrack.core.model.TrackSource
import app.flytrack.core.result.SearchResult
import app.flytrack.data.remote.BackendConfig
import app.flytrack.feature.airports.AirportCatalog
import app.flytrack.feature.airports.AirportList
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** P3 add-flight: the "By route" tab (B5), past flights for Passport (B6), calendar prefill (A3). */
class AddFlightP3Test {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val flights = FakeFlightRepository()
    private val clock = FakeClock() // 22 Nov 2026, 15:30 in India
    private val today = LocalDate.parse("2026-11-22")
    private val delBom = TestData.flight()

    private fun viewModel(prefill: AddFlightPrefill = AddFlightPrefill()) = AddFlightViewModel(
        prefill = prefill,
        flights = flights,
        auth = FakeAuthRepository(),
        profiles = FakeProfileRepository(),
        clock = clock,
        homeTimeZone = HomeTimeZone { "Asia/Kolkata" },
        backendConfig = BackendConfig("https://example.supabase.co", "anon-key", "web-client-id"),
        gmailSuggestions = FakeGmailSuggestionStore(),
        gmailNotifier = FakeGmailNotifier(),
        airports = airportCatalog,
    )

    private val AddFlightViewModel.state get() = uiState.value

    // ---- tabs -------------------------------------------------------------------------------------

    @Test
    fun `starts on the flight-number tab with a route form for the next 30 days`() {
        val state = viewModel().state
        assertEquals(AddFlightMode.FLIGHT_NUMBER, state.mode)
        val route = state.route!!
        assertNull(route.from)
        assertEquals(today, route.date)
        assertEquals(LocalDate.parse("2026-11-21"), route.firstSelectableDate)
        assertEquals(LocalDate.parse("2026-12-22"), route.lastSelectableDate)
    }

    @Test
    fun `switching tabs clears the shown search`() = runTest {
        flights.searchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel()
        vm.onFlightInputChange("6E 2341")
        vm.onSearch()
        assertTrue(vm.state.search is SearchUiState.Results)

        vm.onModeChange(AddFlightMode.ROUTE)

        assertEquals(AddFlightMode.ROUTE, vm.state.mode)
        assertEquals(SearchUiState.Idle, vm.state.search)
    }

    // ---- route search -----------------------------------------------------------------------------

    @Test
    fun `route search needs a departure airport`() {
        val vm = viewModel()
        vm.onModeChange(AddFlightMode.ROUTE)
        vm.onSearch()
        assertEquals(RouteInputError.FROM_REQUIRED, vm.state.route!!.error)
        assertTrue(flights.routeSearches.isEmpty())
    }

    @Test
    fun `a one-character airline code is not searched`() {
        val vm = viewModel()
        vm.onModeChange(AddFlightMode.ROUTE)
        vm.onRouteFromChange(catalog["DEL"]!!)
        vm.onRouteAirlineChange("6")
        vm.onSearch()
        assertEquals(RouteInputError.AIRLINE_INCOMPLETE, vm.state.route!!.error)
        vm.onRouteAirlineChange("6e-")
        assertEquals("6E", vm.state.route!!.airlineInput)
        assertNull(vm.state.route!!.error)
    }

    @Test
    fun `route search sends the airports, date and airline and shows the departures`() {
        flights.routeSearchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel()
        vm.onModeChange(AddFlightMode.ROUTE)
        vm.onRouteFromChange(catalog["DEL"]!!)
        vm.onRouteToChange(catalog["BOM"]!!)
        vm.onRouteAirlineChange("6E")
        vm.onRouteDateChange(LocalDate.parse("2026-11-28"))

        vm.onSearch()

        assertEquals(listOf(RouteSearchQuery("DEL", "BOM", LocalDate.parse("2026-11-28"), "6E")), flights.routeSearches)
        val results = vm.state.search as SearchUiState.Results
        assertEquals("DEL → BOM", results.query.flight)
        assertEquals("DEL", results.query.route?.dep)
        assertTrue(flights.searches.isEmpty())
    }

    @Test
    fun `the arrival cannot equal the departure, and swap exchanges them`() {
        val vm = viewModel()
        vm.onRouteFromChange(catalog["DEL"]!!)
        vm.onRouteToChange(catalog["DEL"]!!)
        assertNull("same airport is dropped", vm.state.route!!.to)
        vm.onRouteToChange(catalog["BOM"]!!)
        vm.onRouteSwap()
        assertEquals("BOM", vm.state.route!!.from?.iata)
        assertEquals("DEL", vm.state.route!!.to?.iata)
        vm.onRouteFromChange(catalog["DEL"]!!)
        assertNull("a new origin equal to the destination clears it", vm.state.route!!.to)
    }

    @Test
    fun `route dates outside the window are ignored`() {
        val vm = viewModel()
        vm.onRouteDateChange(LocalDate.parse("2027-02-01"))
        assertEquals(today, vm.state.route!!.date)
    }

    @Test
    fun `a rejected route search shows the generic rejection`() {
        flights.routeSearchResult = SearchResult.BadInput(app.flytrack.core.result.BadInputReason.DATE)
        val vm = viewModel()
        vm.onModeChange(AddFlightMode.ROUTE)
        vm.onRouteFromChange(catalog["DEL"]!!)
        vm.onSearch()
        assertEquals(SearchUiState.Rejected, vm.state.search)
        assertFalse(vm.state.dateRejected)
    }

    // ---- past flights -----------------------------------------------------------------------------

    @Test
    fun `the past-flight toggle switches to the last year and back`() {
        val vm = viewModel()
        vm.onDateChange(LocalDate.parse("2026-12-24"))

        vm.onPastFlightChange(true)
        assertTrue(vm.state.pastFlight)
        assertEquals(LocalDate.parse("2025-11-22"), vm.state.firstSelectableDate)
        assertEquals(today, vm.state.lastSelectableDate)
        assertEquals("a future date moves to yesterday", LocalDate.parse("2026-11-21"), vm.state.date)

        vm.onDateChange(LocalDate.parse("2026-03-01"))
        assertEquals(LocalDate.parse("2026-03-01"), vm.state.date)

        vm.onPastFlightChange(false)
        assertFalse(vm.state.pastFlight)
        assertEquals("the upcoming date comes back", LocalDate.parse("2026-12-24"), vm.state.date)
        assertEquals(LocalDate.parse("2027-11-22"), vm.state.lastSelectableDate)
    }

    @Test
    fun `a past search uses the past mode and tracks straight into Passport`() = runTest {
        flights.pastSearchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel(AddFlightPrefill())
        vm.onPastFlightChange(true)
        vm.onDateChange(LocalDate.parse("2026-03-01"))
        vm.onFlightInputChange("6E 2341")
        vm.onSearch()
        assertEquals(listOf("6E 2341" to LocalDate.parse("2026-03-01")), flights.pastSearches)
        assertTrue((vm.state.search as SearchUiState.Results).query.past)

        vm.events.test {
            vm.onTrack(delBom.id)
            assertEquals(AddFlightEvent.Finished(delBom.id), awaitItem())
        }
        val extras = flights.trackCalls.single().second
        assertTrue(extras.archived)
        assertEquals(TrackSource.MANUAL, extras.source)
    }

    @Test
    fun `an upcoming search still tracks an active flight`() = runTest {
        flights.searchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel(AddFlightPrefill(flight = "6E 2341", date = "2026-11-25", source = TrackSource.CALENDAR))
        assertEquals(PrefillNotice.FromCalendar, vm.state.prefillNotice)
        vm.events.test {
            vm.onTrack(delBom.id)
            awaitItem()
        }
        val extras = flights.trackCalls.single().second
        assertFalse(extras.archived)
        assertEquals(TrackSource.CALENDAR, extras.source)
    }

    // ---- calendar route prefill --------------------------------------------------------------------

    @Test
    fun `a calendar suggestion without a number opens the route tab and searches`() {
        flights.routeSearchResult = SearchResult.Found(listOf(delBom))
        val vm = viewModel(AddFlightPrefill(date = "2026-11-28", source = TrackSource.CALENDAR, routeFrom = "del", routeTo = "BOM"))

        assertEquals(AddFlightMode.ROUTE, vm.state.mode)
        assertEquals("DEL", vm.state.route!!.from?.iata)
        assertEquals("BOM", vm.state.route!!.to?.iata)
        assertEquals(LocalDate.parse("2026-11-28"), vm.state.route!!.date)
        assertEquals(listOf(RouteSearchQuery("DEL", "BOM", LocalDate.parse("2026-11-28"))), flights.routeSearches)
        assertEquals(PrefillNotice.FromCalendar, vm.state.prefillNotice)
    }

    @Test
    fun `a calendar flight beyond route search's reach is explained, not searched`() {
        val vm = viewModel(AddFlightPrefill(date = "2027-03-01", source = TrackSource.CALENDAR, routeFrom = "DEL"))
        assertEquals(AddFlightMode.ROUTE, vm.state.mode)
        assertEquals(PrefillNotice.RouteDateTooFar(LocalDate.parse("2027-03-01"), LocalDate.parse("2026-12-22")), vm.state.prefillNotice)
        assertTrue(flights.routeSearches.isEmpty())
    }

    // ---- input rules --------------------------------------------------------------------------------

    @Test
    fun `past and route date ranges follow the phone and the server`() {
        val now = clock.now()
        assertEquals(LocalDate.parse("2025-11-22")..today, pastSelectableDates(today, now))
        assertEquals(LocalDate.parse("2026-11-21")..LocalDate.parse("2026-12-22"), routeSelectableDates(today, now))
        assertTrue(isAirlineInputAcceptable(""))
        assertTrue(isAirlineInputAcceptable("6E"))
        assertFalse(isAirlineInputAcceptable("6"))
    }

    private companion object {
        val catalog: AirportList by lazy { AirportList.parse(File("src/main/assets/airports.json").readText()) }
        val airportCatalog = object : AirportCatalog {
            override suspend fun load(): AirportList = catalog
        }
    }
}
