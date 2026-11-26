package app.flytrack.feature.calendar

import app.flytrack.data.remote.BackendException
import app.flytrack.feature.forwarding.EmailForwardingViewModel
import app.flytrack.feature.forwarding.ForwardingUiState
import app.flytrack.feature.forwarding.SupabaseForwardingRepository
import app.flytrack.feature.forwarding.isForwardingAddress
import app.flytrack.feature.friends.FakeWave2Api
import app.flytrack.feature.friends.caller
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class CalendarAndForwardingTest {
    @get:Rule val main = MainDispatcherRule()

    private val feed = "https://abc.supabase.co/functions/v1/calendar-feed?t=AbCdEfGhIjKlMnOpQrStUvWxYz012345"

    // ---- device event ----------------------------------------------------------------------------

    @Test fun eventFromFlight() {
        val event = calendarEventFor(TestData.flight(), "%1\$s, Terminal %2\$s", "desc")!!
        assertEquals("✈ 6E 2341 DEL→BOM", event.title)
        assertEquals(Instant.parse("2026-11-25T12:55:00Z").toEpochMilliseconds(), event.beginMillis)
        assertEquals(Instant.parse("2026-11-25T15:10:00Z").toEpochMilliseconds(), event.endMillis)
        assertEquals("Indira Gandhi International Airport, Terminal 1", event.location)
        assertEquals("Asia/Kolkata", event.timeZone)
        assertEquals("desc", event.description)
    }

    @Test fun eventUsesBestTimesAndAlwaysHasALength() {
        val est = Instant.parse("2026-11-25T14:00:00Z")
        val delayed = calendarEventFor(TestData.flight(estDep = est, schedArr = null, depTerminal = null), "%1\$s T%2\$s", "")!!
        assertEquals(est.toEpochMilliseconds(), delayed.beginMillis)
        assertEquals((est + 2.hours).toEpochMilliseconds(), delayed.endMillis)
        assertEquals("Indira Gandhi International Airport", delayed.location)
        assertNull(calendarEventFor(TestData.flight(schedDep = null), "", ""))
        val noAirport = calendarEventFor(TestData.flight(depAirport = null), "%1\$s, T%2\$s", "")!!
        assertEquals("DEL, T1", noAirport.location)
    }

    // ---- feed links ------------------------------------------------------------------------------

    @Test fun feedLinks() {
        assertTrue(isFeedUrl(feed))
        assertFalse(isFeedUrl("http://abc.supabase.co/x"))
        assertFalse(isFeedUrl("https:// bad"))
        assertFalse(isFeedUrl("javascript:alert(1)"))
        assertEquals("webcal://abc.supabase.co/functions/v1/calendar-feed?t=AbCdEfGhIjKlMnOpQrStUvWxYz012345", webcalUrl(feed))
        assertEquals(
            "https://calendar.google.com/calendar/r?cid=webcal%3A%2F%2Fabc.supabase.co%2Ffunctions%2Fv1%2Fcalendar-feed%3Ft%3DAbCdEfGhIjKlMnOpQrStUvWxYz012345",
            googleCalendarSubscribeUrl(feed),
        )
    }

    @Test fun feedViewModelLoadsOnceAndRotates() = runTest {
        val api = FakeWave2Api().apply {
            answers["my_calendar_url"] = JsonPrimitive(feed)
            answers["rotate_calendar_token"] = JsonPrimitive("${feed}X")
        }
        val vm = CalendarFeedViewModel(SupabaseCalendarFeedRepository(caller(api)))
        assertNull(vm.uiState.value.url) // nothing is created until the user asks
        assertTrue(api.calls.isEmpty())

        vm.act(FeedAction.COPY)
        assertEquals(FeedAction.COPY to feed, vm.uiState.value.pending)
        vm.actionHandled()
        vm.act(FeedAction.SUBSCRIBE_GOOGLE)
        assertEquals(FeedAction.SUBSCRIBE_GOOGLE to feed, vm.uiState.value.pending)
        assertEquals(1, api.calls.size)

        vm.actionHandled()
        vm.rotate()
        assertEquals("${feed}X", vm.uiState.value.url)
        assertEquals(FeedMessage.ROTATED, vm.uiState.value.message)
        assertNull(vm.uiState.value.pending)
    }

    @Test fun feedViewModelErrors() = runTest {
        val api = FakeWave2Api().apply { answers["my_calendar_url"] = BackendException.Http(400, "55000", "not_configured") }
        val vm = CalendarFeedViewModel(SupabaseCalendarFeedRepository(caller(api)))
        vm.act(FeedAction.COPY)
        assertEquals(FeedMessage.NOT_CONFIGURED, vm.uiState.value.message)
        vm.messageShown()
        api.answers["my_calendar_url"] = JsonPrimitive("not a url")
        vm.act(FeedAction.COPY)
        assertEquals(FeedMessage.FAILED, vm.uiState.value.message)
        api.answers["my_calendar_url"] = BackendException.Network(java.io.IOException())
        vm.act(FeedAction.COPY)
        assertEquals(FeedMessage.OFFLINE, vm.uiState.value.message)
        assertNull(vm.uiState.value.pending)
    }

    // ---- forwarding ------------------------------------------------------------------------------

    @Test fun forwardingAddressShape() {
        assertTrue(isForwardingAddress("trips+ab3kq7mz2x@in.heygate.app"))
        assertFalse(isForwardingAddress("trips+ab3 kq@in.heygate.app"))
        assertFalse(isForwardingAddress("no-at-sign"))
        assertFalse(isForwardingAddress("a@localhost"))
    }

    @Test fun forwardingScreenOnlyLoadsForPro() = runTest {
        val api = FakeWave2Api().apply { answers["my_forwarding_address"] = JsonPrimitive("trips+ab3kq7mz2x@in.heygate.app") }
        val vm = EmailForwardingViewModel(SupabaseForwardingRepository(caller(api)))
        vm.setPro(false)
        assertEquals(ForwardingUiState.Locked, vm.uiState.value)
        assertTrue(api.calls.isEmpty())
        vm.setPro(true)
        assertEquals(ForwardingUiState.Ready("trips+ab3kq7mz2x@in.heygate.app"), vm.uiState.value)
        vm.setPro(true) // already loaded
        assertEquals(1, api.calls.size)
    }

    @Test fun forwardingScreenErrors() = runTest {
        val api = FakeWave2Api().apply { answers["my_forwarding_address"] = BackendException.Network(java.io.IOException()) }
        val vm = EmailForwardingViewModel(SupabaseForwardingRepository(caller(api)))
        vm.setPro(true)
        assertEquals(ForwardingUiState.Offline, vm.uiState.value)
        api.answers["my_forwarding_address"] = JsonPrimitive("garbage")
        vm.retry()
        assertEquals(ForwardingUiState.Failed, vm.uiState.value)
    }
}
