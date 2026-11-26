package app.flytrack.feature.live

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LiveContentFormatterTest {

    private val formatter = LiveContentFormatter(FakeLiveStrings)
    private fun at(time: String): Instant = Instant.parse("2026-11-25T${time}Z")

    @Test fun beforeDepartureShowsDepartureTimeGateAndTerminalInAirportTime() {
        val flight = TestData.flight(depGate = "42B", lastPolledAt = at("11:55:00"))
        val content = formatter.notification(flight, at("12:00:00"))!!
        assertEquals("6E 2341 · DEL → BOM", content.title)
        assertEquals("On time · Departs 18:25 · Gate 42B · T1", content.text)
        assertEquals("18:25", content.shortCriticalText)
        assertNull(content.subText)
        assertEquals(LiveStatusWord.ON_TIME, content.statusWord)
        assertEquals(FlightStatusTone.ON_TIME, content.tone)
        assertEquals(TestData.FLIGHT_ID, content.flightId)
    }

    @Test fun aLateDepartureSaysByHowMuch() {
        val flight = TestData.flight(estDep = at("13:20:00"), lastPolledAt = at("11:55:00"))
        val content = formatter.notification(flight, at("12:00:00"))!!
        assertEquals("Delayed 25 min · Departs 18:50 · T1", content.text)
        assertEquals(FlightStatusTone.DELAYED, content.tone)
    }

    @Test fun boardingAndGateClosedComeFromTheStatus() {
        val boarding = TestData.flight(status = FlightStatus.BOARDING, depGate = "7", lastPolledAt = at("12:20:00"))
        assertEquals("Boarding · Departs 18:25 · Gate 7 · T1", formatter.notification(boarding, at("12:25:00"))!!.text)
        val closed = boarding.copy(status = FlightStatus.GATE_CLOSED)
        assertEquals(LiveStatusWord.GATE_CLOSED, formatter.notification(closed, at("12:45:00"))!!.statusWord)
    }

    @Test fun offlineInTheAirIsLabelledEstimated() {
        val flight = TestData.flight(
            status = FlightStatus.DEPARTED, actDep = at("12:55:00"), baggageBelt = "5",
            lastPolledAt = at("12:58:00"), updatedAt = at("12:58:00"),
        )
        val content = formatter.notification(flight, at("14:00:00"))!!
        assertEquals("In the air · Arrives 20:40 · Belt 5", content.text)
        assertEquals("Estimated", content.subText)
        assertEquals(FlightStatusTone.ESTIMATED, content.tone)
        assertEquals("20:40", content.shortCriticalText)
        assertEquals(LiveStage.AIRBORNE, content.progress.stage)
    }

    @Test fun freshlyDepartedSaysDeparted() {
        val flight = TestData.flight(status = FlightStatus.DEPARTED, actDep = at("12:55:00"), lastPolledAt = at("13:00:00"))
        assertEquals(LiveStatusWord.DEPARTED, formatter.notification(flight, at("13:05:00"))!!.statusWord)
    }

    @Test fun landedShowsLandingTimeBeltAndTerminal() {
        val flight = TestData.flight(
            status = FlightStatus.LANDED, actDep = at("12:55:00"), actArr = at("15:05:00"), baggageBelt = "5",
            lastPolledAt = at("15:10:00"),
        )
        val content = formatter.notification(flight, at("15:20:00"))!!
        assertEquals("Landed 20:35 · Belt 5 · T2", content.text)
        assertEquals("Belt 5", content.shortCriticalText)
    }

    @Test fun arrivalTimeIsInTheArrivalAirportsZone() {
        val flight = TestData.flight(
            arrAirport = TestData.DXB, status = FlightStatus.EN_ROUTE, actDep = at("12:55:00"),
            schedArr = at("16:40:00"), lastPolledAt = at("13:55:00"), arrTerminal = null,
        )
        assertEquals("In the air · Arrives 20:40", formatter.notification(flight, at("14:00:00"))!!.text)
    }

    @Test fun cancelledFlightsHaveNoLiveNotification() {
        assertNull(formatter.notification(TestData.flight(status = FlightStatus.CANCELLED), at("12:00:00")))
    }

    @Test fun countdownFormats() {
        assertEquals("3 days", formatter.countdown(3.days + 2.hours))
        assertEquals("2h 05m", formatter.countdown(2.hours + 5.minutes + 30.seconds))
        assertEquals("47h 59m", formatter.countdown(47.hours + 59.minutes))
        assertEquals("35 min", formatter.countdown(35.minutes))
        assertEquals("1 min", formatter.countdown(30.seconds))
        assertNull(formatter.countdown(0.seconds))
        assertNull(formatter.countdown((-5).minutes))
    }
}
