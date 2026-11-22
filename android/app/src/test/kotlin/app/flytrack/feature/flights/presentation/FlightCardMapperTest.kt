package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TimeKind
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.RelativeTime
import app.flytrack.core.time.RelativeUnit
import app.flytrack.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The default flight is 6E 2341 DEL-BOM on 25 Nov 2026, scheduled 18:25-20:40 IST
 * (12:55Z-15:10Z); the default "now" is 22 Nov 2026, 15:30 IST.
 */
class FlightCardMapperTest {
    private val now = TestData.NOW
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    private fun card(flight: Flight, at: Instant = now, newFields: Set<FlightField> = emptySet()) =
        flight.toCardUiModel(at, newFields)

    private fun depShifted(by: Duration, status: FlightStatus = FlightStatus.SCHEDULED) =
        TestData.flight(status = status, estDep = schedDep + by)

    private fun airborne(
        status: FlightStatus = FlightStatus.EN_ROUTE,
        leftLateBy: Duration = 2.minutes,
        arrivalShift: Duration? = null,
    ) = TestData.flight(status = status, actDep = schedDep + leftLateBy, estArr = arrivalShift?.let { schedArr + it })

    // ---- identity and route -------------------------------------------------------------------

    @Test
    fun `route shows codes and cities`() {
        val card = card(TestData.flight())

        assertEquals(TestData.FLIGHT_ID, card.flightId)
        assertEquals("6E 2341", card.designator)
        assertEquals("IndiGo", card.airlineName)
        assertEquals("DEL", card.depCode)
        assertEquals("BOM", card.arrCode)
        assertEquals("New Delhi", card.depCity)
        assertEquals("Mumbai", card.arrCity)
    }

    @Test
    fun `an unknown airport falls back to its code and a blank airline to nothing`() {
        val card = card(TestData.flight(depAirport = null, arrAirport = null, depIata = "IXZ", arrIata = "CCU", airlineName = " "))

        assertEquals("IXZ", card.depCity)
        assertEquals("CCU", card.arrCity)
        assertNull(card.airlineName)
    }

    // ---- times --------------------------------------------------------------------------------

    @Test
    fun `timetable times are shown in the airport's zone`() {
        val card = card(TestData.flight())

        assertEquals(
            FlightTimeUiModel(time = "18:25", kind = TimeKind.SCHEDULED, replacedTime = null, date = LocalDate.parse("2026-11-25")),
            card.departure,
        )
        assertEquals(
            FlightTimeUiModel(time = "20:40", kind = TimeKind.SCHEDULED, replacedTime = null, date = LocalDate.parse("2026-11-25")),
            card.arrival,
        )
        assertEquals(2.hours + 15.minutes, card.duration)
    }

    @Test
    fun `each end uses its own airport's zone and an overnight arrival is marked plus one`() {
        // Dubai 22:35 (+04:00) to Delhi 03:20 next day (+05:30).
        val card = card(
            TestData.flight(
                depAirport = TestData.DXB,
                arrAirport = TestData.DEL,
                flightDate = LocalDate.parse("2026-11-28"),
                schedDep = Instant.parse("2026-11-28T18:35:00Z"),
                schedArr = Instant.parse("2026-11-28T21:50:00Z"),
            ),
        )

        assertEquals("22:35", card.departure?.time)
        assertEquals(0, card.departure?.dayOffset)
        assertEquals("03:20", card.arrival?.time)
        assertEquals(1, card.arrival?.dayOffset)
        assertEquals(LocalDate.parse("2026-11-29"), card.arrival?.date)
    }

    @Test
    fun `a delayed departure shows the estimate and strikes the timetable time`() {
        val card = card(TestData.flight(estDep = schedDep + 25.minutes, estArr = schedArr + 25.minutes))

        assertEquals("18:50", card.departure?.time)
        assertEquals(TimeKind.ESTIMATED, card.departure?.kind)
        assertEquals("18:25", card.departure?.replacedTime)
        assertEquals("21:05", card.arrival?.time)
        assertEquals("20:40", card.arrival?.replacedTime)
    }

    @Test
    fun `an early departure also strikes the timetable time`() {
        val card = card(depShifted(-20.minutes))

        assertEquals("18:05", card.departure?.time)
        assertEquals("18:25", card.departure?.replacedTime)
    }

    @Test
    fun `a small shift shows the estimate without striking anything`() {
        val card = card(depShifted(10.minutes))

        assertEquals("18:35", card.departure?.time)
        assertEquals(TimeKind.ESTIMATED, card.departure?.kind)
        assertNull(card.departure?.replacedTime)
    }

    @Test
    fun `actual times win over estimates`() {
        val card = card(
            TestData.flight(
                status = FlightStatus.ARRIVED,
                estDep = schedDep + 20.minutes, actDep = schedDep + 31.minutes,
                estArr = schedArr + 20.minutes, actArr = schedArr + 27.minutes,
            ),
        )

        assertEquals(FlightTimeUiModel("18:56", TimeKind.ACTUAL, "18:25", LocalDate.parse("2026-11-25")), card.departure)
        assertEquals(FlightTimeUiModel("21:07", TimeKind.ACTUAL, "20:40", LocalDate.parse("2026-11-25")), card.arrival)
    }

    @Test
    fun `missing times stay missing`() {
        val card = card(TestData.flight(schedDep = null, schedArr = null))

        assertNull(card.departure)
        assertNull(card.arrival)
        assertNull(card.duration)
        assertNull(card.countdown)
        assertEquals(LocalDate.parse("2026-11-25"), card.departureDate)
    }

    @Test
    fun `a delay past midnight moves the departure date`() {
        // 18:25 IST + 6h 30m = 00:55 IST on the 26th.
        val card = card(depShifted(6.hours + 30.minutes))

        assertEquals("00:55", card.departure?.time)
        assertEquals(LocalDate.parse("2026-11-26"), card.departureDate)
        assertEquals(RelativeDay.InDays(4), card.departureDay)
    }

    @Test
    fun `the departure day is counted in the airport's calendar, not UTC`() {
        // 24 Nov 19:00Z is already 25 Nov 00:30 in India: the flight is "today".
        val card = card(TestData.flight(), at = Instant.parse("2026-11-24T19:00:00Z"))

        assertEquals(RelativeDay.Today, card.departureDay)
    }

    // ---- status word, tone and sentence -------------------------------------------------------

    @Test
    fun `every flight status maps to a word and a sentence`() {
        val expected = mapOf(
            FlightStatus.UNKNOWN to (FlightStatusWord.SCHEDULE_ONLY to StatusLine.ScheduleOnly),
            FlightStatus.SCHEDULED to (FlightStatusWord.SCHEDULED to StatusLine.BeforeDeparture(Punctuality.Unknown)),
            FlightStatus.CHECK_IN to (FlightStatusWord.CHECK_IN_OPEN to StatusLine.CheckInOpen(Punctuality.Unknown)),
            FlightStatus.BOARDING to (FlightStatusWord.BOARDING to StatusLine.Boarding(null, Punctuality.Unknown)),
            FlightStatus.GATE_CLOSED to (FlightStatusWord.GATE_CLOSED to StatusLine.GateClosed(Punctuality.Unknown)),
            FlightStatus.DEPARTED to (FlightStatusWord.DEPARTED to StatusLine.InAir(Punctuality.Unknown, Punctuality.Unknown)),
            FlightStatus.EN_ROUTE to (FlightStatusWord.IN_THE_AIR to StatusLine.InAir(Punctuality.Unknown, Punctuality.Unknown)),
            FlightStatus.APPROACHING to (FlightStatusWord.LANDING_SOON to StatusLine.InAir(Punctuality.Unknown, Punctuality.Unknown)),
            FlightStatus.LANDED to (FlightStatusWord.LANDED to StatusLine.Landed(Punctuality.Unknown, atGate = false)),
            FlightStatus.ARRIVED to (FlightStatusWord.ARRIVED to StatusLine.Landed(Punctuality.Unknown, atGate = true)),
            FlightStatus.CANCELLED to (FlightStatusWord.CANCELLED to StatusLine.Cancelled),
            FlightStatus.DIVERTED to (FlightStatusWord.DIVERTED to StatusLine.Diverted(landed = false)),
        )
        assertEquals("a status was added: decide its wording", FlightStatus.entries.toSet(), expected.keys)

        for ((status, wording) in expected) {
            val card = card(TestData.flight(status = status))
            assertEquals("word for $status", wording.first, card.statusWord)
            assertEquals("sentence for $status", wording.second, card.statusLine)
        }
    }

    @Test
    fun `every flight status maps to a tone`() {
        val expected = mapOf(
            FlightStatus.UNKNOWN to FlightStatusTone.ESTIMATED,
            FlightStatus.SCHEDULED to FlightStatusTone.ON_TIME,
            FlightStatus.CHECK_IN to FlightStatusTone.ON_TIME,
            FlightStatus.BOARDING to FlightStatusTone.ACTIVE,
            FlightStatus.GATE_CLOSED to FlightStatusTone.ACTIVE,
            FlightStatus.DEPARTED to FlightStatusTone.ACTIVE,
            FlightStatus.EN_ROUTE to FlightStatusTone.ACTIVE,
            FlightStatus.APPROACHING to FlightStatusTone.ACTIVE,
            FlightStatus.LANDED to FlightStatusTone.ON_TIME,
            FlightStatus.ARRIVED to FlightStatusTone.ON_TIME,
            FlightStatus.CANCELLED to FlightStatusTone.CANCELLED,
            FlightStatus.DIVERTED to FlightStatusTone.CANCELLED,
        )
        for ((status, tone) in expected) {
            assertEquals("tone for $status", tone, card(TestData.flight(status = status)).tone)
        }
    }

    @Test
    fun `an estimate close to the timetable is on time`() {
        val card = card(depShifted(5.minutes))

        assertEquals(FlightStatusWord.ON_TIME, card.statusWord)
        assertEquals(FlightStatusTone.ON_TIME, card.tone)
        assertEquals(StatusLine.BeforeDeparture(Punctuality.OnTime), card.statusLine)
    }

    @Test
    fun `a late estimate is delayed, in plain minutes`() {
        val card = card(depShifted(25.minutes))

        assertEquals(FlightStatusWord.DELAYED, card.statusWord)
        assertEquals(FlightStatusTone.DELAYED, card.tone)
        assertEquals(StatusLine.BeforeDeparture(Punctuality.Late(25)), card.statusLine)
    }

    @Test
    fun `an early estimate is early`() {
        val card = card(depShifted(-20.minutes))

        assertEquals(FlightStatusWord.EARLY, card.statusWord)
        assertEquals(StatusLine.BeforeDeparture(Punctuality.Early(20)), card.statusLine)
    }

    @Test
    fun `late and early start at fifteen minutes`() {
        assertEquals(FlightStatusWord.ON_TIME, card(depShifted(14.minutes)).statusWord)
        assertEquals(FlightStatusWord.DELAYED, card(depShifted(15.minutes)).statusWord)
        assertEquals(FlightStatusWord.ON_TIME, card(depShifted(-14.minutes)).statusWord)
        assertEquals(FlightStatusWord.EARLY, card(depShifted(-15.minutes)).statusWord)

        assertEquals(Punctuality.Unknown, Punctuality.of(null))
        assertEquals(Punctuality.OnTime, Punctuality.of(0))
        assertEquals(Punctuality.OnTime, Punctuality.of(14))
        assertEquals(Punctuality.Late(15), Punctuality.of(15))
        assertEquals(Punctuality.OnTime, Punctuality.of(-14))
        assertEquals(Punctuality.Early(15), Punctuality.of(-15))
    }

    @Test
    fun `an estimate counts even when the provider sent no status`() {
        val onTime = card(depShifted(3.minutes, status = FlightStatus.UNKNOWN))
        assertEquals(FlightStatusWord.ON_TIME, onTime.statusWord)
        assertEquals(FlightStatusTone.ON_TIME, onTime.tone)
        assertEquals(StatusLine.BeforeDeparture(Punctuality.OnTime), onTime.statusLine)

        val late = card(depShifted(40.minutes, status = FlightStatus.UNKNOWN))
        assertEquals(FlightStatusWord.DELAYED, late.statusWord)
        assertEquals(FlightStatusTone.DELAYED, late.tone)
        assertEquals(StatusLine.BeforeDeparture(Punctuality.Late(40)), late.statusLine)
    }

    @Test
    fun `a delay outranks open check-in in the word, and the sentence keeps both`() {
        val card = card(depShifted(25.minutes, status = FlightStatus.CHECK_IN))

        assertEquals(FlightStatusWord.DELAYED, card.statusWord)
        assertEquals(FlightStatusTone.DELAYED, card.tone)
        assertEquals(StatusLine.CheckInOpen(Punctuality.Late(25)), card.statusLine)
    }

    @Test
    fun `boarding names the gate and keeps the delay`() {
        val onTime = card(TestData.flight(status = FlightStatus.BOARDING, depGate = "42B"))
        assertEquals(FlightStatusWord.BOARDING, onTime.statusWord)
        assertEquals(FlightStatusTone.ACTIVE, onTime.tone)
        assertEquals(StatusLine.Boarding("42B", Punctuality.Unknown), onTime.statusLine)

        val late = card(TestData.flight(status = FlightStatus.BOARDING, depGate = " ", estDep = schedDep + 30.minutes))
        assertEquals(FlightStatusWord.BOARDING, late.statusWord)
        assertEquals(FlightStatusTone.DELAYED, late.tone)
        assertEquals(StatusLine.Boarding(null, Punctuality.Late(30)), late.statusLine)
    }

    @Test
    fun `gate closed keeps the delay`() {
        val card = card(depShifted(18.minutes, status = FlightStatus.GATE_CLOSED))

        assertEquals(FlightStatusWord.GATE_CLOSED, card.statusWord)
        assertEquals(StatusLine.GateClosed(Punctuality.Late(18)), card.statusLine)
    }

    @Test
    fun `an actual departure time means departed, whatever the status says`() {
        val card = card(TestData.flight(status = FlightStatus.SCHEDULED, actDep = schedDep + 4.minutes))

        assertEquals(FlightPhase.IN_AIR, card.phase)
        assertEquals(FlightStatusWord.DEPARTED, card.statusWord)
        assertEquals(StatusLine.InAir(arrival = Punctuality.Unknown, departure = Punctuality.OnTime), card.statusLine)
    }

    @Test
    fun `in the air, a late arrival estimate is delayed`() {
        val card = card(airborne(arrivalShift = 20.minutes))

        assertEquals(FlightStatusWord.IN_THE_AIR, card.statusWord)
        assertEquals(FlightStatusTone.DELAYED, card.tone)
        assertEquals(StatusLine.InAir(arrival = Punctuality.Late(20), departure = Punctuality.OnTime), card.statusLine)
    }

    @Test
    fun `in the air, a flight that left late but lands on time is not painted delayed`() {
        val card = card(airborne(leftLateBy = 30.minutes, arrivalShift = 5.minutes))

        assertEquals(FlightStatusTone.ACTIVE, card.tone)
        assertEquals(StatusLine.InAir(arrival = Punctuality.OnTime, departure = Punctuality.Late(30)), card.statusLine)
    }

    @Test
    fun `in the air without an arrival estimate, the late take-off is what is known`() {
        val card = card(airborne(leftLateBy = 30.minutes))

        assertEquals(FlightStatusTone.DELAYED, card.tone)
        assertEquals(StatusLine.InAir(arrival = Punctuality.Unknown, departure = Punctuality.Late(30)), card.statusLine)
    }

    @Test
    fun `in the air and early`() {
        val card = card(airborne(status = FlightStatus.APPROACHING, arrivalShift = -18.minutes))

        assertEquals(FlightStatusWord.LANDING_SOON, card.statusWord)
        assertEquals(FlightStatusTone.ACTIVE, card.tone)
        assertEquals(StatusLine.InAir(arrival = Punctuality.Early(18), departure = Punctuality.OnTime), card.statusLine)
    }

    @Test
    fun `landed late, early and on time`() {
        fun landed(shift: Duration, status: FlightStatus = FlightStatus.LANDED) =
            card(TestData.flight(status = status, actDep = schedDep, actArr = schedArr + shift))

        val late = landed(25.minutes)
        assertEquals(FlightStatusWord.LANDED, late.statusWord)
        assertEquals(FlightStatusTone.DELAYED, late.tone)
        assertEquals(StatusLine.Landed(Punctuality.Late(25), atGate = false), late.statusLine)

        val early = landed(-16.minutes)
        assertEquals(FlightStatusTone.ON_TIME, early.tone)
        assertEquals(StatusLine.Landed(Punctuality.Early(16), atGate = false), early.statusLine)

        val arrived = landed(3.minutes, status = FlightStatus.ARRIVED)
        assertEquals(FlightStatusWord.ARRIVED, arrived.statusWord)
        assertEquals(StatusLine.Landed(Punctuality.OnTime, atGate = true), arrived.statusLine)
    }

    @Test
    fun `an actual arrival time means landed, whatever the status says`() {
        val card = card(TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, actArr = schedArr))

        assertEquals(FlightPhase.LANDED, card.phase)
        assertEquals(FlightStatusWord.LANDED, card.statusWord)
    }

    @Test
    fun `cancelled wins over everything`() {
        val card = card(
            TestData.flight(status = FlightStatus.CANCELLED, estDep = schedDep + 90.minutes, depGate = "12", depTerminal = "1"),
            at = schedDep - 1.hours,
        )

        assertEquals(FlightStatusWord.CANCELLED, card.statusWord)
        assertEquals(FlightStatusTone.CANCELLED, card.tone)
        assertEquals(StatusLine.Cancelled, card.statusLine)
        assertNull(card.countdown)
        assertNull(card.progress)
        assertTrue(card.info.isEmpty())
    }

    @Test
    fun `diverted says whether it is on the ground`() {
        val flying = card(TestData.flight(status = FlightStatus.DIVERTED, actDep = schedDep))
        assertEquals(FlightStatusWord.DIVERTED, flying.statusWord)
        assertEquals(FlightStatusTone.CANCELLED, flying.tone)
        assertEquals(StatusLine.Diverted(landed = false), flying.statusLine)
        assertNull(flying.countdown)

        val landed = card(TestData.flight(status = FlightStatus.DIVERTED, actDep = schedDep, actArr = schedArr + 50.minutes))
        assertEquals(FlightStatusWord.DIVERTED, landed.statusWord)
        assertEquals(StatusLine.Diverted(landed = true), landed.statusLine)
    }

    // ---- terminal, gate, belt -----------------------------------------------------------------

    @Test
    fun `before take-off the card shows the departure terminal and gate`() {
        val card = card(TestData.flight(depTerminal = "1", depGate = "42B", depCheckin = "C12", arrTerminal = "2", baggageBelt = "5"))

        assertEquals(
            listOf(FlightInfoUiModel(FlightField.DEP_TERMINAL, "1"), FlightInfoUiModel(FlightField.DEP_GATE, "42B")),
            card.info,
        )
    }

    @Test
    fun `after take-off the card shows the arrival terminal, gate and belt`() {
        val card = card(
            TestData.flight(
                status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr,
                depTerminal = "1", depGate = "42B", arrTerminal = "2", arrGate = "B7", baggageBelt = "5",
            ),
        )

        assertEquals(
            listOf(
                FlightInfoUiModel(FlightField.ARR_TERMINAL, "2"),
                FlightInfoUiModel(FlightField.ARR_GATE, "B7"),
                FlightInfoUiModel(FlightField.BAGGAGE_BELT, "5"),
            ),
            card.info,
        )
    }

    @Test
    fun `values that are not announced or blank are left out`() {
        val card = card(TestData.flight(depTerminal = null, depGate = "  "))

        assertTrue(card.info.isEmpty())
    }

    @Test
    fun `a newly known value is flagged`() {
        val card = card(
            TestData.flight(depTerminal = "1", depGate = "42B"),
            newFields = setOf(FlightField.DEP_GATE, FlightField.BAGGAGE_BELT),
        )

        assertEquals(
            listOf(FlightInfoUiModel(FlightField.DEP_TERMINAL, "1"), FlightInfoUiModel(FlightField.DEP_GATE, "42B", isNew = true)),
            card.info,
        )
    }

    // ---- countdown and progress ---------------------------------------------------------------

    @Test
    fun `days ahead, the countdown is in calendar days at the departure airport`() {
        assertEquals(Countdown.DepartsOn(RelativeDay.InDays(3)), card(TestData.flight()).countdown)

        // 30 hours ahead: 24 Nov 12:25 IST, so the flight is tomorrow.
        assertEquals(Countdown.DepartsOn(RelativeDay.Tomorrow), card(TestData.flight(), at = schedDep - 30.hours).countdown)
    }

    @Test
    fun `within a day, the countdown is in hours and minutes`() {
        assertEquals(
            Countdown.Departs(RelativeTime(23, RelativeUnit.HOURS, isPast = false)),
            card(TestData.flight(), at = schedDep - (23.hours + 59.minutes)).countdown,
        )
        assertEquals(
            Countdown.Departs(RelativeTime(5, RelativeUnit.HOURS, isPast = false)),
            card(TestData.flight(), at = schedDep - 5.hours).countdown,
        )
        assertEquals(
            Countdown.Departs(RelativeTime(35, RelativeUnit.MINUTES, isPast = false)),
            card(TestData.flight(status = FlightStatus.BOARDING), at = schedDep - 35.minutes).countdown,
        )
    }

    @Test
    fun `the countdown follows the estimate, not the timetable`() {
        val card = card(depShifted(25.minutes), at = schedDep - 35.minutes)

        assertEquals(Countdown.Departs(RelativeTime(1, RelativeUnit.HOURS, isPast = false)), card.countdown)
    }

    @Test
    fun `no countdown once the departure time has passed without a take-off`() {
        assertNull(card(TestData.flight(), at = schedDep).countdown)
        assertNull(card(TestData.flight(), at = schedDep + 20.minutes).countdown)
    }

    @Test
    fun `in the air, the countdown is to landing`() {
        val flight = airborne(arrivalShift = 10.minutes)

        assertEquals(
            Countdown.Lands(RelativeTime(40, RelativeUnit.MINUTES, isPast = false)),
            card(flight, at = schedArr - 30.minutes).countdown,
        )
        // Past the estimate but not reported as landed: nothing sensible to count down to.
        assertNull(card(flight, at = schedArr + 30.minutes).countdown)
    }

    @Test
    fun `no countdown after landing`() {
        val card = card(TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr), at = schedArr + 5.minutes)

        assertNull(card.countdown)
    }

    @Test
    fun `progress exists only in the air`() {
        assertNull(card(TestData.flight()).progress)
        assertNull(card(TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr)).progress)

        val flying = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, estArr = schedDep + 2.hours)
        assertEquals(0.5f, card(flying, at = schedDep + 1.hours).progress!!, 0.001f)
        assertEquals(0.25f, card(flying, at = schedDep + 30.minutes).progress!!, 0.001f)
    }

    // ---- freshness ----------------------------------------------------------------------------

    @Test
    fun `updated is the later of the last check and the last change`() {
        val polled = Instant.parse("2026-11-22T09:58:00Z")
        val changed = Instant.parse("2026-11-22T07:00:00Z")

        assertEquals(polled, card(TestData.flight(lastPolledAt = polled, updatedAt = changed)).updatedAt)
        assertEquals(polled, card(TestData.flight(lastPolledAt = changed, updatedAt = polled)).updatedAt)
        assertEquals(changed, card(TestData.flight(lastPolledAt = null, updatedAt = changed)).updatedAt)
        assertNull(card(TestData.flight(lastPolledAt = null, updatedAt = null)).updatedAt)
        assertEquals(now, card(TestData.flight()).asOf)
    }
}
