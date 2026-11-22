package app.flytrack.feature.addflight

import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LegStatusTest {
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    @Test
    fun `a timetable entry without an estimate is only scheduled`() {
        assertEquals(LegStatus.Scheduled, TestData.flight(status = FlightStatus.SCHEDULED).legStatus)
    }

    @Test
    fun `on time needs an estimate or open check-in`() {
        assertEquals(LegStatus.OnTime, TestData.flight(estDep = schedDep + 5.minutes).legStatus)
        assertEquals(LegStatus.OnTime, TestData.flight(estDep = schedDep - 10.minutes).legStatus)
        assertEquals(LegStatus.OnTime, TestData.flight(status = FlightStatus.CHECK_IN).legStatus)
    }

    @Test
    fun `a delay counts from fifteen minutes`() {
        assertEquals(LegStatus.OnTime, TestData.flight(estDep = schedDep + 14.minutes).legStatus)
        assertEquals(LegStatus.Late(15), TestData.flight(estDep = schedDep + 15.minutes).legStatus)
        assertEquals(LegStatus.Late(85), TestData.flight(estDep = schedDep + 85.minutes).legStatus)
    }

    @Test
    fun `a late flight that is boarding is still late`() {
        assertEquals(LegStatus.Late(40), TestData.flight(status = FlightStatus.BOARDING, estDep = schedDep + 40.minutes).legStatus)
        assertEquals(LegStatus.Boarding, TestData.flight(status = FlightStatus.BOARDING).legStatus)
        assertEquals(LegStatus.Boarding, TestData.flight(status = FlightStatus.GATE_CLOSED).legStatus)
    }

    @Test
    fun `departed, landed, cancelled and diverted flights say so`() {
        assertEquals(LegStatus.InAir, TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep + 60.minutes).legStatus)
        assertEquals(LegStatus.InAir, TestData.flight(actDep = schedDep).legStatus)
        assertEquals(LegStatus.Landed, TestData.flight(status = FlightStatus.LANDED).legStatus)
        assertEquals(LegStatus.Landed, TestData.flight(actDep = schedDep, actArr = schedArr).legStatus)
        assertEquals(LegStatus.Cancelled, TestData.flight(status = FlightStatus.CANCELLED, estDep = schedDep + 90.minutes).legStatus)
        assertEquals(LegStatus.Diverted, TestData.flight(status = FlightStatus.DIVERTED).legStatus)
    }

    @Test
    fun `an unknown status is not presented as on time`() {
        assertEquals(LegStatus.ScheduleOnly, TestData.flight(status = FlightStatus.UNKNOWN).legStatus)
        assertEquals(LegStatus.Late(30), TestData.flight(status = FlightStatus.UNKNOWN, estDep = schedDep + 30.minutes).legStatus)
    }

    @Test
    fun `listed times are the timetable, falling back to newer times`() {
        val delayed = TestData.flight(estDep = schedDep + 30.minutes, estArr = schedArr + 30.minutes)
        assertEquals(schedDep, delayed.listedDeparture)
        assertEquals(schedArr, delayed.listedArrival)
        assertEquals(135.minutes, delayed.listedDuration)

        val noSchedule = TestData.flight(schedDep = null, schedArr = null, estDep = schedDep, actArr = schedArr)
        assertEquals(schedDep, noSchedule.listedDeparture)
        assertEquals(schedArr, noSchedule.listedArrival)
    }

    @Test
    fun `duration is unknown without both times or when they are inconsistent`() {
        assertNull(TestData.flight(schedArr = null).listedDuration)
        assertNull(TestData.flight(schedDep = null).listedDuration)
        assertNull(TestData.flight(schedDep = schedArr, schedArr = schedDep).listedDuration)
    }
}
