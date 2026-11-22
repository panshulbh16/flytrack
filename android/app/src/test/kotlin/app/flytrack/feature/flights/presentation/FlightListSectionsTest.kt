package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.RelativeTime
import app.flytrack.core.time.RelativeUnit
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FlightListSectionsTest {
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    private fun landed(at: Instant? = schedArr) =
        TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = at, schedArr = at)

    private fun sections(vararg flights: Flight, now: Instant = TestData.NOW) =
        buildFlightSections(flights.map { TestData.tracked(it) }, now)

    // ---- isPast -------------------------------------------------------------------------------

    @Test
    fun `a flight ahead or in the air is not past`() {
        assertFalse(TestData.flight().isPast(TestData.NOW))
        assertFalse(TestData.flight().isPast(schedDep + 1.hours))
        assertFalse(TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep).isPast(schedArr + 1.hours))
    }

    @Test
    fun `a landed flight stays up front for two hours, for the baggage belt`() {
        assertFalse(landed().isPast(schedArr + 1.hours + 59.minutes))
        assertTrue(landed().isPast(schedArr + 2.hours))
    }

    @Test
    fun `a landed flight without any arrival time is past`() {
        assertTrue(landed(at = null).isPast(TestData.NOW))
    }

    @Test
    fun `a cancelled flight stays up front until its day is over at the departure airport`() {
        val cancelled = TestData.flight(status = FlightStatus.CANCELLED)

        assertFalse(cancelled.isPast(TestData.NOW))
        // 25 Nov 23:59 in India.
        assertFalse(cancelled.isPast(Instant.parse("2026-11-25T18:29:00Z")))
        // 26 Nov 00:00 in India.
        assertTrue(cancelled.isPast(Instant.parse("2026-11-25T18:30:00Z")))
    }

    @Test
    fun `a flight that never reported a landing is past twelve hours after its arrival time`() {
        val silent = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep)

        assertFalse(silent.isPast(schedArr + 11.hours))
        assertTrue(silent.isPast(schedArr + 12.hours))
    }

    @Test
    fun `with only a departure time, a day after it is past`() {
        val flight = TestData.flight(schedArr = null)

        assertFalse(flight.isPast(schedDep + 23.hours))
        assertTrue(flight.isPast(schedDep + 24.hours))
    }

    @Test
    fun `without any time, the flight date decides`() {
        val flight = TestData.flight(schedDep = null, schedArr = null)

        assertFalse(flight.isPast(Instant.parse("2026-11-26T06:00:00Z")))
        assertTrue(flight.isPast(Instant.parse("2026-11-26T06:30:00Z")))
    }

    // ---- grouping -----------------------------------------------------------------------------

    @Test
    fun `flights are grouped into upcoming, soonest first, and past, latest first`() {
        val now = schedDep - 1.hours
        val soon = TestData.flight(id = "soon")
        val later = TestData.flight(id = "later", schedDep = schedDep + 2.days, schedArr = schedArr + 2.days)
        val lastWeek = TestData.flight(
            id = "last-week", status = FlightStatus.ARRIVED,
            schedDep = schedDep - 7.days, actDep = schedDep - 7.days, schedArr = schedArr - 7.days, actArr = schedArr - 7.days,
        )
        val yesterday = TestData.flight(
            id = "yesterday", status = FlightStatus.ARRIVED,
            schedDep = schedDep - 1.days, actDep = schedDep - 1.days, schedArr = schedArr - 1.days, actArr = schedArr - 1.days,
        )

        val sections = sections(later, lastWeek, soon, yesterday, now = now)

        assertEquals(listOf("soon", "later"), sections.upcoming.map { it.flightId })
        assertEquals(listOf("yesterday", "last-week"), sections.past.map { it.flightId })
        assertFalse(sections.isEmpty)
    }

    @Test
    fun `no flights, no sections`() {
        val sections = sections()

        assertTrue(sections.isEmpty)
        assertNull(sections.next)
    }

    @Test
    fun `flights without a departure time go last`() {
        val untimed = TestData.flight(id = "untimed", schedDep = null, schedArr = null)
        val timed = TestData.flight(id = "timed")

        assertEquals(listOf("timed", "untimed"), sections(untimed, timed).upcoming.map { it.flightId })
    }

    // ---- next flight --------------------------------------------------------------------------

    @Test
    fun `the next takeoff is the soonest flight still on the ground`() {
        val soon = TestData.flight(id = "soon")
        val later = TestData.flight(id = "later", schedDep = schedDep + 2.days, schedArr = schedArr + 2.days)

        val next = sections(later, soon).next

        assertEquals("soon", next?.card?.flightId)
        assertEquals(Countdown.DepartsOn(RelativeDay.InDays(3)), next?.countdown)
    }

    @Test
    fun `a flight in the air comes before the next takeoff`() {
        val now = schedArr - 40.minutes
        val flying = TestData.flight(id = "flying", status = FlightStatus.EN_ROUTE, actDep = schedDep)
        val sooner = TestData.flight(id = "sooner", schedDep = now + 30.minutes, schedArr = now + 3.hours)

        val next = sections(sooner, flying, now = now).next

        assertEquals("flying", next?.card?.flightId)
        assertEquals(Countdown.Lands(RelativeTime(40, RelativeUnit.MINUTES, isPast = false)), next?.countdown)
    }

    @Test
    fun `cancelled, landed and overdue flights are never the next takeoff`() {
        val now = schedDep + 10.minutes
        val cancelled = TestData.flight(id = "cancelled", status = FlightStatus.CANCELLED)
        val overdue = TestData.flight(id = "overdue")
        val justLanded = TestData.flight(
            id = "landed", status = FlightStatus.LANDED,
            schedDep = schedDep - 3.hours, actDep = schedDep - 3.hours, schedArr = schedDep - 1.hours, actArr = schedDep - 1.hours,
        )
        val tomorrow = TestData.flight(id = "tomorrow", schedDep = schedDep + 1.days, schedArr = schedArr + 1.days)

        val sections = sections(cancelled, overdue, justLanded, tomorrow, now = now)

        assertEquals(listOf("landed", "cancelled", "overdue", "tomorrow"), sections.upcoming.map { it.flightId })
        assertEquals("tomorrow", sections.next?.card?.flightId)

        assertNull(sections(cancelled, overdue, justLanded, now = now).next)
    }

    @Test
    fun `new fields are looked up per flight`() {
        val withGate = TestData.flight(id = "a", depGate = "42B")
        val other = TestData.flight(id = "b", depGate = "7", schedDep = schedDep + 1.hours)

        val sections = buildFlightSections(
            tracked = listOf(TestData.tracked(withGate), TestData.tracked(other)),
            now = TestData.NOW,
            newFields = { if (it.id == "a") setOf(FlightField.DEP_GATE) else emptySet() },
        )

        assertEquals(listOf(true, false), sections.upcoming.map { card -> card.info.single { it.field == FlightField.DEP_GATE }.isNew })
    }
}
