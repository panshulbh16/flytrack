package app.flytrack.core.model

import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class FlightDerivedTest {
    private val sched = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    @Test
    fun `best departure prefers actual, then estimated, then scheduled`() {
        val scheduled = TestData.flight(schedDep = sched)
        assertEquals(BestTime(sched, TimeKind.SCHEDULED), scheduled.bestDepartureTime)

        val estimated = scheduled.copy(estDep = sched + 25.minutes)
        assertEquals(BestTime(sched + 25.minutes, TimeKind.ESTIMATED), estimated.bestDepartureTime)

        val actual = estimated.copy(actDep = sched + 31.minutes)
        assertEquals(BestTime(sched + 31.minutes, TimeKind.ACTUAL), actual.bestDepartureTime)
        assertEquals(sched + 31.minutes, actual.bestDeparture)
    }

    @Test
    fun `best arrival prefers actual, then estimated, then scheduled, and is null when nothing is known`() {
        val f = TestData.flight(schedArr = schedArr, estArr = schedArr + 10.minutes)
        assertEquals(BestTime(schedArr + 10.minutes, TimeKind.ESTIMATED), f.bestArrivalTime)
        assertEquals(schedArr + 4.minutes, f.copy(actArr = schedArr + 4.minutes).bestArrival)
        assertNull(TestData.flight(schedArr = null).bestArrivalTime)
        assertNull(TestData.flight(schedDep = null).bestDeparture)
    }

    @Test
    fun `departure delay is estimate minus schedule, rounded like the server`() {
        assertEquals(25, TestData.flight(schedDep = sched, estDep = sched + 25.minutes).departureDelayMinutes)
        assertEquals(0, TestData.flight(schedDep = sched, estDep = sched).departureDelayMinutes)
        // Math.round semantics: 24.5 -> 25, 24.4 -> 24
        assertEquals(25, TestData.flight(schedDep = sched, estDep = sched + 24.minutes + 30.seconds).departureDelayMinutes)
        assertEquals(24, TestData.flight(schedDep = sched, estDep = sched + 24.minutes + 29.seconds).departureDelayMinutes)
    }

    @Test
    fun `departure delay uses the actual time once known and can be negative`() {
        val f = TestData.flight(schedDep = sched, estDep = sched + 25.minutes, actDep = sched + 40.minutes)
        assertEquals(40, f.departureDelayMinutes)
        assertEquals(-5, TestData.flight(schedDep = sched, actDep = sched - 5.minutes).departureDelayMinutes)
    }

    @Test
    fun `delay is unknown without a schedule or without any newer time`() {
        assertNull(TestData.flight(schedDep = sched, estDep = null, actDep = null).departureDelayMinutes)
        assertNull(TestData.flight(schedDep = null, estDep = sched).departureDelayMinutes)
        assertNull(TestData.flight(schedArr = schedArr, estArr = null).arrivalDelayMinutes)
        assertEquals(12, TestData.flight(schedArr = schedArr, estArr = schedArr + 12.minutes).arrivalDelayMinutes)
    }

    @Test
    fun `duration is between the best known times`() {
        assertEquals(135.minutes, TestData.flight(schedDep = sched, schedArr = schedArr).duration)
        assertEquals(
            120.minutes,
            TestData.flight(schedDep = sched, actDep = sched + 30.minutes, schedArr = schedArr, estArr = schedArr + 15.minutes).duration,
        )
        assertNull(TestData.flight(schedArr = null).duration)
        assertNull("arrival before departure is inconsistent data", TestData.flight(schedDep = schedArr, schedArr = sched).duration)
    }

    @Test
    fun `phase follows the status`() {
        fun phaseOf(status: FlightStatus) = TestData.flight(status = status).phase
        assertEquals(FlightPhase.UPCOMING, phaseOf(FlightStatus.UNKNOWN))
        assertEquals(FlightPhase.UPCOMING, phaseOf(FlightStatus.SCHEDULED))
        assertEquals(FlightPhase.CHECK_IN, phaseOf(FlightStatus.CHECK_IN))
        assertEquals(FlightPhase.BOARDING, phaseOf(FlightStatus.BOARDING))
        assertEquals(FlightPhase.BOARDING, phaseOf(FlightStatus.GATE_CLOSED))
        assertEquals(FlightPhase.IN_AIR, phaseOf(FlightStatus.DEPARTED))
        assertEquals(FlightPhase.IN_AIR, phaseOf(FlightStatus.EN_ROUTE))
        assertEquals(FlightPhase.IN_AIR, phaseOf(FlightStatus.APPROACHING))
        assertEquals(FlightPhase.LANDED, phaseOf(FlightStatus.LANDED))
        assertEquals(FlightPhase.LANDED, phaseOf(FlightStatus.ARRIVED))
        assertEquals(FlightPhase.CANCELLED, phaseOf(FlightStatus.CANCELLED))
        assertEquals(FlightPhase.DIVERTED, phaseOf(FlightStatus.DIVERTED))
    }

    @Test
    fun `actual times decide the phase when the status lags behind`() {
        assertEquals(FlightPhase.IN_AIR, TestData.flight(status = FlightStatus.BOARDING, actDep = sched).phase)
        assertEquals(FlightPhase.LANDED, TestData.flight(status = FlightStatus.EN_ROUTE, actDep = sched, actArr = schedArr).phase)
        assertEquals(
            "cancelled wins over stale actual times",
            FlightPhase.CANCELLED,
            TestData.flight(status = FlightStatus.CANCELLED, actDep = sched).phase,
        )
    }

    @Test
    fun `hasDeparted and hasLanded mirror the server rules`() {
        assertFalse(TestData.flight(status = FlightStatus.BOARDING).hasDeparted)
        assertTrue(TestData.flight(status = FlightStatus.DIVERTED).hasDeparted)
        assertTrue(TestData.flight(actDep = sched).hasDeparted)
        assertFalse(TestData.flight(status = FlightStatus.APPROACHING).hasLanded)
        assertTrue(TestData.flight(status = FlightStatus.ARRIVED).hasLanded)
        assertTrue(TestData.flight(actArr = schedArr).hasLanded)
    }

    @Test
    fun `status tone`() {
        assertEquals(FlightStatusTone.ON_TIME, TestData.flight(status = FlightStatus.SCHEDULED).statusTone)
        assertEquals(FlightStatusTone.ON_TIME, TestData.flight(schedDep = sched, estDep = sched + 14.minutes).statusTone)
        assertEquals(FlightStatusTone.DELAYED, TestData.flight(schedDep = sched, estDep = sched + 15.minutes).statusTone)
        assertEquals(FlightStatusTone.ACTIVE, TestData.flight(status = FlightStatus.BOARDING).statusTone)
        assertEquals(FlightStatusTone.ACTIVE, TestData.flight(status = FlightStatus.EN_ROUTE, actDep = sched).statusTone)
        assertEquals(
            "a late boarding flight is shown as delayed, not as active",
            FlightStatusTone.DELAYED,
            TestData.flight(status = FlightStatus.BOARDING, schedDep = sched, estDep = sched + 40.minutes).statusTone,
        )
        assertEquals(FlightStatusTone.CANCELLED, TestData.flight(status = FlightStatus.CANCELLED).statusTone)
        assertEquals(FlightStatusTone.CANCELLED, TestData.flight(status = FlightStatus.DIVERTED).statusTone)
        assertEquals(FlightStatusTone.ESTIMATED, TestData.flight(status = FlightStatus.UNKNOWN).statusTone)
    }

    @Test
    fun `after landing the tone is judged by the arrival delay`() {
        val lateOutOnTimeIn = TestData.flight(
            status = FlightStatus.LANDED, schedDep = sched, actDep = sched + 30.minutes, schedArr = schedArr, actArr = schedArr + 5.minutes,
        )
        assertEquals(FlightStatusTone.ON_TIME, lateOutOnTimeIn.statusTone)
        assertEquals(FlightStatusTone.DELAYED, lateOutOnTimeIn.copy(actArr = schedArr + 45.minutes).statusTone)
    }

    @Test
    fun `progress is the flown fraction between take-off and landing`() {
        val f = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = sched, schedArr = sched + 120.minutes, estArr = sched + 120.minutes)
        assertNull(TestData.flight().progress(sched))
        assertEquals(0.25f, f.progress(sched + 30.minutes)!!, 0.001f)
        assertEquals(1f, f.progress(sched + 500.minutes)!!, 0.001f)
        assertEquals(1f, f.copy(status = FlightStatus.LANDED).progress(sched + 30.minutes)!!, 0.001f)
    }

    @Test
    fun `distance between airports`() {
        assertEquals(1138.0, TestData.flight().distanceKm!!.toDouble(), 15.0) // DEL-BOM great circle
        assertNull(TestData.flight(arrAirport = null).distanceKm)
    }

    @Test
    fun `designator and zones`() {
        val f = TestData.flight(carrierIata = "AI", flightNumber = "101A", arrAirport = TestData.DXB)
        assertEquals("AI 101A", f.designator)
        assertEquals("Asia/Kolkata", f.depTz)
        assertEquals("Asia/Dubai", f.arrTz)
        assertNull(TestData.flight(depAirport = null).depTz)
    }
}
