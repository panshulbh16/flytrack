package app.flytrack.feature.widget

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.feature.live.FakeLiveStrings
import app.flytrack.feature.live.LiveStatusWord
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.time.Instant

class NextFlightWidgetModelTest {

    private val builder = NextFlightWidgetModelBuilder(FakeLiveStrings, Locale.ENGLISH)
    private fun at(day: String, time: String): Instant = Instant.parse("2026-11-${day}T${time}Z")

    @Test fun noFlightsIsTheEmptyState() {
        assertEquals(NextFlightWidgetModel.Empty, builder.build(emptyList(), at("25", "12:00:00")))
    }

    @Test fun upcomingFlightTodayShowsDepartureGateAndCountdown() {
        val flight = TestData.flight(depGate = "42B", lastPolledAt = at("25", "10:00:00"))
        val model = builder.build(listOf(TestData.tracked(flight)), at("25", "10:50:00")) as NextFlightWidgetModel.Upcoming
        assertEquals("DEL → BOM", model.route)
        assertEquals("6E 2341", model.designator)
        assertEquals("On time", model.statusText)
        assertEquals("Departs 18:25", model.timeText)
        assertNull(model.dateText)
        assertEquals("Gate 42B · T1", model.gateBeltText)
        assertEquals("Departs in 2h 05m", model.countdownText)
        assertEquals(TestData.FLIGHT_ID, model.flightId)
        assertFalse(model.estimated)
    }

    @Test fun aFlightOnAnotherDayShowsItsDate() {
        val model = builder.build(listOf(TestData.tracked()), at("22", "10:00:00")) as NextFlightWidgetModel.Upcoming
        assertEquals("Wed, 25 Nov", model.dateText)
        assertEquals("Departs in 3 days", model.countdownText)
    }

    @Test fun inTheAirCountsDownToTheLandingAndShowsTheBelt() {
        val flight = TestData.flight(
            status = FlightStatus.EN_ROUTE, actDep = TestData.flight().schedDep, baggageBelt = "5",
            lastPolledAt = at("25", "14:00:00"),
        )
        val model = builder.build(flight, at("25", "14:05:00"))
        assertEquals(LiveStatusWord.IN_AIR, model.statusWord)
        assertEquals("Arrives 20:40", model.timeText)
        assertEquals("Belt 5", model.gateBeltText)
        assertEquals("Lands in 1h 05m", model.countdownText)
        assertEquals(FlightStatusTone.ACTIVE, model.tone)
    }

    @Test fun offlineEstimateIsFlagged() {
        val flight = TestData.flight(lastPolledAt = at("25", "11:00:00"), updatedAt = at("25", "11:00:00"))
        val model = builder.build(flight, at("25", "14:05:00"))
        assertTrue(model.estimated)
        assertEquals(LiveStatusWord.IN_AIR, model.statusWord)
        assertEquals(FlightStatusTone.ESTIMATED, model.tone)
    }

    @Test fun landedAndCancelledHaveNoCountdown() {
        val landed = TestData.flight(
            status = FlightStatus.LANDED, actDep = TestData.flight().schedDep, actArr = at("25", "15:05:00"),
            lastPolledAt = at("25", "15:06:00"),
        )
        val model = builder.build(landed, at("25", "15:10:00"))
        assertEquals("Landed 20:35", model.timeText)
        assertNull(model.countdownText)
        val cancelled = builder.build(TestData.flight(status = FlightStatus.CANCELLED), at("25", "12:00:00"))
        assertEquals("Cancelled", cancelled.statusText)
        assertNull(cancelled.countdownText)
    }

    @Test fun redrawKeyIgnoresTheCountdown() {
        val flight = TestData.flight(lastPolledAt = at("25", "10:00:00"))
        val a = builder.build(flight, at("25", "10:50:00"))
        val b = builder.build(flight, at("25", "10:51:00"))
        assertEquals(a.redrawKey, b.redrawKey)
        assertFalse(a == b)
    }
}
