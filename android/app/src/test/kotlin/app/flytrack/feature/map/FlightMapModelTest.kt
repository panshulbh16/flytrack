package app.flytrack.feature.map

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.core.estimate.GreatCircle
import app.flytrack.core.estimate.PositionSource
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.LivePosition
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FlightMapModelTest {
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")
    private val del = GeoPoint(TestData.DEL.lat, TestData.DEL.lon)
    private val bom = GeoPoint(TestData.BOM.lat, TestData.BOM.lon)

    @Test
    fun `before take-off the whole route is dashed and there is no plane`() {
        val model = TestData.flight().toMapUiModel(schedDep - 1.hours)!!

        assertEquals(MapFlightState.NOT_DEPARTED, model.state)
        assertTrue(model.flownPath.isEmpty())
        assertEquals(GreatCircle.DEFAULT_SEGMENTS + 1, model.remainingPath.size)
        assertNull(model.plane)
        assertNull(model.source)
        assertEquals("New Delhi", model.depCity)
    }

    @Test
    fun `in the air the route splits under the estimated plane`() {
        val flight = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep)
        val now = schedDep + 67.5.minutes // half of 135 min
        val model = flight.toMapUiModel(now)!!

        assertEquals(MapFlightState.AIRBORNE, model.state)
        assertEquals(PositionSource.ESTIMATED, model.source)
        assertNotNull(model.plane)
        val plane = model.plane!!
        val mid = GreatCircle.interpolate(del, bom, 0.5)
        assertEquals(mid.lat, plane.point.lat, 1e-9)
        assertEquals(mid.lon, plane.point.lon, 1e-9)
        assertEquals(GreatCircle.bearingAt(del, bom, 0.5), plane.bearingDeg, 1e-9)
        assertEquals(plane.point, model.flownPath.last())
        assertEquals(plane.point, model.remainingPath.first())
        assertEquals(del, model.flownPath.first())
        assertEquals(bom, model.remainingPath.last())
        assertEquals(0.5, model.progress!!, 1e-9)
        assertEquals(schedArr - now, model.remaining)
    }

    @Test
    fun `a fresh live fix is labelled live with its time`() {
        val now = schedDep + 1.hours
        val fix = LivePosition(23.5, 74.6, 35000, 200.0, 440.0, now - 3.minutes)
        val model = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, live = fix).toMapUiModel(now)!!

        assertEquals(PositionSource.LIVE, model.source)
        assertEquals(GeoPoint(23.5, 74.6), model.plane!!.point)
        assertEquals(200.0, model.plane!!.bearingDeg, 0.0)
        assertEquals(now - 3.minutes, model.liveObservedAt)
        assertEquals(GeoPoint(23.5, 74.6), model.flownPath.last())
    }

    @Test
    fun `arrived, cancelled and unknown`() {
        val landed = TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr).toMapUiModel(schedArr)!!
        assertEquals(MapFlightState.ARRIVED, landed.state)
        assertTrue(landed.remainingPath.isEmpty())
        assertNull(landed.plane)

        val cancelled = TestData.flight(status = FlightStatus.CANCELLED).toMapUiModel(schedDep + 1.hours)!!
        assertEquals(MapFlightState.CANCELLED, cancelled.state)
        assertNull(cancelled.plane)

        val diverted = TestData.flight(status = FlightStatus.DIVERTED, actDep = schedDep).toMapUiModel(schedDep + 1.hours)!!
        assertEquals(MapFlightState.POSITION_UNKNOWN, diverted.state)

        assertNull(TestData.flight(arrAirport = null, arrIata = "BOM").toMapUiModel(schedDep))
    }

    @Test
    fun `split stays continuous across the antimeridian`() {
        val hnd = GeoPoint(35.5494, 139.7798)
        val sfo = GeoPoint(37.6190, -122.3750)
        val at = GreatCircle.interpolate(hnd, sfo, 0.6)
        val (flown, rest) = splitRoute(hnd, sfo, 0.6, at, 32)
        val all = flown + rest
        all.zipWithNext().forEach { (a, b) -> assertTrue(abs(b.lon - a.lon) < 180) }
        assertEquals(flown.last(), rest.first())
    }

    @Test
    fun `camera bounds cover both airports and the plane`() {
        val model = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep).toMapUiModel(schedDep + 1.hours)!!
        val bounds = mapBounds(model.cameraPoints)!!
        assertEquals(bom.lat, bounds.south, 0.0)
        assertEquals(del.lat, bounds.north, 0.0)
        assertEquals(bom.lon, bounds.west, 0.0)
        assertEquals(del.lon, bounds.east, 0.0)
        assertNull(mapBounds(emptyList()))
    }
}
