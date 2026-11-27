package app.flytrack.wear.ui

import app.flytrack.wear.data.NextFlightPayload
import app.flytrack.wear.data.NextFlightPayloadTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WatchFlightFormatterTest {

    private val formatter = WatchFlightFormatter(FakeWearStrings, Locale.ENGLISH, ZoneId.of("UTC"))
    private val base = NextFlightPayload.decode(NextFlightPayloadTest.PHONE_JSON.encodeToByteArray())!!
    private fun at(day: String, time: String): Instant = Instant.parse("2026-11-${day}T${time}Z")

    private fun upcoming(payload: NextFlightPayload, now: Instant): WatchFlight =
        (formatter.format(payload, now) as WatchNextFlight.Upcoming).flight

    @Test fun nothingFromThePhoneIsTheEmptyState() {
        assertEquals(WatchNextFlight.Empty, formatter.format(null, at("25", "10:00:00")))
    }

    @Test fun upcomingFlightTodayShowsNumberRouteStatusTimeAndGate() {
        val flight = upcoming(base, at("25", "10:00:00"))
        assertEquals("6E 2341", flight.designator)
        assertEquals("DEL → BOM", flight.route)
        assertEquals("DEL-BOM", flight.shortRoute)
        assertEquals("On time", flight.statusText)
        assertEquals(WatchTone.ON_TIME, flight.tone)
        // 12:55 UTC is 18:25 at Delhi.
        assertEquals("18:25", flight.departureTime)
        assertNull(flight.dateText)
        assertEquals("Departs 18:25", flight.whenText)
        assertEquals("Gate 42B · T1", flight.gateText)
    }

    @Test fun anotherDayAtTheAirportShowsTheDate() {
        // 22:00 UTC on the 24th is already the 25th in India: still "today" at the airport.
        assertNull(upcoming(base, at("24", "22:00:00")).dateText)
        assertEquals("Wed, 25 Nov", upcoming(base, at("24", "10:00:00")).dateText)
        assertEquals("Departs 18:25 · Wed, 25 Nov", upcoming(base, at("24", "10:00:00")).whenText)
    }

    @Test fun delayShowsTheMinutesAndTheEstimatedTime() {
        val delayed = base.copy(sw = "delayed", dm = 25, ed = "2026-11-25T13:20:00Z", tone = "delayed")
        val flight = upcoming(delayed, at("25", "10:00:00"))
        assertEquals("Delayed 25 min", flight.statusText)
        assertEquals("18:50", flight.departureTime)
        assertEquals(WatchTone.DELAYED, flight.tone)
    }

    @Test fun afterDepartureTheBeltReplacesTheGate() {
        val inAir = base.copy(st = "en_route", sw = "in_air", ad = "2026-11-25T13:00:00Z", bb = "5", tone = "active")
        val flight = upcoming(inAir, at("25", "14:00:00"))
        assertEquals("In the air", flight.statusText)
        assertEquals("Belt 5", flight.gateText)
        assertEquals("18:30", flight.departureTime)
        assertEquals(WatchTone.ACTIVE, flight.tone)
    }

    @Test fun noGateNoTerminalMeansNoGateLine() {
        assertNull(upcoming(base.copy(dg = null, dt = null), at("25", "10:00:00")).gateText)
    }

    @Test fun aStaleFlightDropsOffLikeOnThePhone() {
        // Arrival 15:10 UTC; shown until 45 min after it.
        assertTrue(formatter.format(base, at("25", "15:54:00")) is WatchNextFlight.Upcoming)
        assertEquals(WatchNextFlight.Empty, formatter.format(base, at("25", "15:55:00")))
        // Cancelled: an hour after the planned departure (12:55).
        val cancelled = base.copy(st = "cancelled", sw = "cancelled")
        assertTrue(formatter.format(cancelled, at("25", "13:54:00")) is WatchNextFlight.Upcoming)
        assertEquals(WatchNextFlight.Empty, formatter.format(cancelled, at("25", "13:55:00")))
        // No arrival time: four hours after departure.
        val noArrival = base.copy(sa = null)
        assertEquals(WatchNextFlight.Empty, formatter.format(noArrival, at("25", "16:55:00")))
    }

    @Test fun anOlderPhoneWithoutStatusWordFallsBackToTheServerStatus() {
        val old = base.copy(sw = null, tone = null, st = "boarding")
        val flight = upcoming(old, at("25", "12:20:00"))
        assertEquals(WatchStatus.BOARDING, flight.status)
        assertEquals("Boarding", flight.statusText)
        assertEquals(WatchTone.ACTIVE, flight.tone)
        val late = upcoming(base.copy(sw = null, tone = null, dm = 40), at("25", "10:00:00"))
        assertEquals("Delayed 40 min", late.statusText)
        assertEquals(WatchTone.DELAYED, late.tone)
        assertEquals(WatchStatus.CANCELLED, WatchFlightFormatter.fallbackStatus(base.copy(st = "cancelled")))
        assertEquals(WatchStatus.LANDED, WatchFlightFormatter.fallbackStatus(base.copy(st = "arrived")))
        assertEquals(WatchTone.ESTIMATED, WatchFlightFormatter.fallbackTone(WatchStatus.ON_TIME, estimated = true))
    }

    @Test fun anUnknownTimeZoneUsesTheWatchZone() {
        val flight = upcoming(base.copy(dtz = "Not/AZone"), at("25", "10:00:00"))
        assertEquals("12:55", flight.departureTime)
        val noZone = upcoming(base.copy(dtz = null), at("25", "10:00:00"))
        assertEquals("12:55", noZone.departureTime)
    }

    @Test fun noTimesAtAllIsStillShown() {
        val flight = upcoming(base.copy(sd = null, sa = null), at("25", "10:00:00"))
        assertNull(flight.departureTime)
        assertNull(flight.dateText)
        assertNull(flight.whenText)
    }

    @Test fun estimateIsFlagged() {
        val flight = upcoming(base.copy(est = true, tone = "estimated"), at("25", "10:00:00"))
        assertTrue(flight.estimated)
        assertEquals(WatchTone.ESTIMATED, flight.tone)
    }

    @Test fun complicationTextsFitTheirSlots() {
        val texts = ComplicationTexts.from(upcoming(base, at("25", "10:00:00")), FakeWearStrings)
        assertEquals("18:25", texts.shortText)
        assertEquals("DEL-BOM", texts.shortTitle)
        assertTrue(texts.shortTitle.length <= 7)
        assertEquals("6E 2341 · DEL → BOM", texts.longText)
        assertEquals("On time · 18:25 · Gate 42B · T1", texts.longTitle)
        assertEquals("6E 2341 · DEL → BOM · On time · 18:25 · Gate 42B · T1", texts.contentDescription)
    }

    @Test fun complicationWithoutTimeShowsTheFlightNumber() {
        val texts = ComplicationTexts.from(upcoming(base.copy(sd = null, sa = null), at("25", "10:00:00")), FakeWearStrings)
        assertEquals("6E 2341", texts.shortText)
        assertEquals("On time · Gate 42B · T1", texts.longTitle)
    }
}
