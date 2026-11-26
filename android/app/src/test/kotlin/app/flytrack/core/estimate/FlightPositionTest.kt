package app.flytrack.core.estimate

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.LivePosition
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FlightPositionTest {
    // TestData.flight(): 6E 2341 DEL-BOM, scheduled 12:55-15:10 UTC (135 min).
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    private fun live(at: Instant, lat: Double = 23.0, lon: Double = 74.5, heading: Double? = 205.0) =
        LivePosition(lat = lat, lon = lon, altFt = 36000, headingDeg = heading, speedKts = 450.0, at = at)

    @Test
    fun `estimate times follow the contract`() {
        val flight = TestData.flight(
            estDep = schedDep + 20.minutes,
            actDep = schedDep + 25.minutes,
            estArr = schedArr + 10.minutes,
            actArr = schedArr + 5.minutes, // actual arrival is not part of arrTime
        )
        assertEquals(EstimateTimes(schedDep + 25.minutes, schedArr + 10.minutes), flight.estimateTimes)
        assertEquals(EstimateTimes(schedDep, schedArr), TestData.flight().estimateTimes)
        assertNull(TestData.flight(schedArr = null).estimateTimes)
    }

    @Test
    fun `a fresh live fix wins and is labelled live`() {
        val now = schedDep + 1.hours
        val flight = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, live = live(now - 19.minutes))

        val position = flight.position(now)!!
        assertEquals(PositionSource.LIVE, position.source)
        assertEquals(GeoPoint(23.0, 74.5), position.point)
        assertEquals(205.0, position.bearingDeg, 0.0)
        assertEquals(EstimatePhase.AIRBORNE, position.phase)
        assertEquals(now - 19.minutes, position.observedAt)
        assertEquals(schedArr - now, position.remaining)
        val ends = flight.routeEnds!!
        assertEquals(GreatCircle.fractionAlong(ends.dep, ends.arr, GeoPoint(23.0, 74.5)), position.progress, 1e-9)
    }

    @Test
    fun `a live fix older than 20 minutes falls back to the estimate`() {
        val now = schedDep + 1.hours
        val flight = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, live = live(now - 21.minutes))

        assertNull(flight.freshLivePosition(now))
        val position = flight.position(now)!!
        assertEquals(PositionSource.ESTIMATED, position.source)
        assertEquals(60.0 / 135.0, position.progress, 1e-9)
        assertNull(position.observedAt)
    }

    @Test
    fun `live fixes without a time, at null island or far in the future are ignored`() {
        val now = schedDep + 1.hours
        assertNull(TestData.flight(live = live(now).copy(at = null)).freshLivePosition(now))
        assertNull(TestData.flight(live = live(now, lat = 0.0, lon = 0.0)).freshLivePosition(now))
        assertNull(TestData.flight(live = live(now + 10.minutes)).freshLivePosition(now))
        assertEquals(live(now + 2.minutes), TestData.flight(live = live(now + 2.minutes)).freshLivePosition(now))
    }

    @Test
    fun `without a heading the live plane points at the destination`() {
        val now = schedDep + 1.hours
        val flight = TestData.flight(status = FlightStatus.EN_ROUTE, live = live(now, heading = null))
        val expected = GreatCircle.bearing(GeoPoint(23.0, 74.5), GeoPoint(TestData.BOM.lat, TestData.BOM.lon))

        assertEquals(expected, flight.position(now)!!.bearingDeg, 1e-9)
    }

    @Test
    fun `landed, cancelled and diverted flights`() {
        val now = schedArr + 1.hours
        val landed = TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr)
        val arrived = landed.position(now)!!
        assertEquals(EstimatePhase.ARRIVED, arrived.phase)
        assertEquals(GeoPoint(TestData.BOM.lat, TestData.BOM.lon), arrived.point)
        assertNull(arrived.remaining)

        assertNull(TestData.flight(status = FlightStatus.CANCELLED).position(schedDep + 1.hours))
        assertNull(TestData.flight(status = FlightStatus.DIVERTED, actDep = schedDep).position(schedDep + 1.hours))
        val divertedLive = TestData.flight(status = FlightStatus.DIVERTED, live = live(schedDep + 1.hours))
        assertEquals(PositionSource.LIVE, divertedLive.position(schedDep + 1.hours)!!.source)
    }

    @Test
    fun `departed by status before the planned time is airborne at the start`() {
        // Took off early: the status says departed but actual time is not in yet.
        val flight = TestData.flight(status = FlightStatus.DEPARTED)
        val position = flight.position(schedDep - 5.minutes)!!

        assertEquals(EstimatePhase.AIRBORNE, position.phase)
        assertEquals(0.0, position.progress, 0.0)
    }

    @Test
    fun `fresh data that says not departed keeps the plane at the gate, like the card`() {
        val recent = schedDep + 10.minutes
        val waiting = TestData.flight(lastPolledAt = recent, updatedAt = recent)

        val late = waiting.position(schedDep + 20.minutes)!!
        assertEquals(EstimatePhase.PRE_DEPARTURE, late.phase)
        assertEquals(0.0, late.progress, 0.0)
        assertEquals(waiting.routeEnds!!.dep, late.point)
        assertNull(waiting.progressFix(schedDep + 20.minutes))

        // Long past the planned arrival and still fresh "not departed": not "arrived".
        val polled = schedArr + 1.hours
        val veryLate = TestData.flight(lastPolledAt = polled, updatedAt = polled).position(polled + 5.minutes)!!
        assertEquals(EstimatePhase.PRE_DEPARTURE, veryLate.phase)
    }

    @Test
    fun `stale data that says not departed flies the offline estimate on the map too`() {
        val lastSeen = schedDep - 2.hours
        val offline = TestData.flight(lastPolledAt = lastSeen, updatedAt = lastSeen)
        val now = schedDep + 45.minutes

        val position = offline.position(now)!!
        assertEquals(EstimatePhase.AIRBORNE, position.phase)
        assertEquals(PositionSource.ESTIMATED, position.source)
        assertEquals(offline.progressFix(now)!!.progress.toDouble(), position.progress, 0.001)
    }

    @Test
    fun `fresh data that says in the air past the planned arrival is not arrived`() {
        val polled = schedArr + 10.minutes
        val overdue = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, lastPolledAt = polled, updatedAt = polled)

        val position = overdue.position(schedArr + 15.minutes)!!
        assertEquals(EstimatePhase.AIRBORNE, position.phase)
        assertEquals(1.0, position.progress, 0.0)
        assertEquals(kotlin.time.Duration.ZERO, position.remaining)
    }

    @Test
    fun `no airports, no position`() {
        assertNull(TestData.flight(depAirport = null, depIata = "DEL").position(schedDep + 1.hours))
    }

    // ---- progress for the card and detail screen -------------------------------------------

    @Test
    fun `in the air, progress is estimated from the times without a live fix`() {
        val flying = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, estArr = schedDep + 2.hours)
        val fix = flying.progressFix(schedDep + 30.minutes)!!

        assertEquals(0.25f, fix.progress, 0.001f)
        assertEquals(PositionSource.ESTIMATED, fix.source)
        assertEquals(schedDep + 2.hours, fix.arrival)
    }

    @Test
    fun `in the air with a fresh fix, progress is the distance flown`() {
        val now = schedDep + 30.minutes
        val midway = GreatCircle.interpolate(GeoPoint(TestData.DEL.lat, TestData.DEL.lon), GeoPoint(TestData.BOM.lat, TestData.BOM.lon), 0.6)
        val flying = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, live = live(now, midway.lat, midway.lon))
        val fix = flying.progressFix(now)!!

        assertEquals(0.6f, fix.progress, 0.001f)
        assertEquals(PositionSource.LIVE, fix.source)
    }

    @Test
    fun `stale data past the departure time shows the offline estimate`() {
        val lastSeen = schedDep - 2.hours
        val offline = TestData.flight(lastPolledAt = lastSeen, updatedAt = lastSeen)

        val fix = offline.progressFix(schedDep + 45.minutes)!!
        assertEquals(45f / 135f, fix.progress, 0.001f)
        assertEquals(PositionSource.ESTIMATED, fix.source)
        assertEquals(schedArr, fix.arrival)

        // Before departure, or once the arrival time has passed: no line.
        assertNull(offline.progressFix(schedDep - 1.minutes))
        assertNull(offline.progressFix(schedArr))
    }

    @Test
    fun `fresh data that says not departed shows no progress`() {
        val recent = schedDep + 10.minutes
        val waiting = TestData.flight(lastPolledAt = recent, updatedAt = recent)

        assertNull(waiting.progressFix(schedDep + 20.minutes))
    }

    @Test
    fun `no progress once landed or cancelled`() {
        val lastSeen = schedDep - 2.hours
        assertNull(TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr).progressFix(schedArr))
        assertNull(TestData.flight(status = FlightStatus.CANCELLED, lastPolledAt = lastSeen, updatedAt = lastSeen).progressFix(schedDep + 1.hours))
    }
}
