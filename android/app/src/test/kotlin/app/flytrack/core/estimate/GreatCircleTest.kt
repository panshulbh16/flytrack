package app.flytrack.core.estimate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GreatCircleTest {
    private val del = GeoPoint(28.5665, 77.1031)
    private val bom = GeoPoint(19.0887, 72.8679)
    private val dxb = GeoPoint(25.2528, 55.3644)

    @Test
    fun `interpolation ends are the airports themselves`() {
        assertEquals(del, GreatCircle.interpolate(del, bom, 0.0))
        assertEquals(bom, GreatCircle.interpolate(del, bom, 1.0))
        assertEquals(del, GreatCircle.interpolate(del, bom, -0.3))
        assertEquals(bom, GreatCircle.interpolate(del, bom, 1.7))
    }

    @Test
    fun `interpolated points split the distance in proportion`() {
        val total = GreatCircle.distanceKm(del, dxb)
        for (f in listOf(0.1, 0.33, 0.5, 0.9)) {
            val p = GreatCircle.interpolate(del, dxb, f)
            assertEquals(total * f, GreatCircle.distanceKm(del, p), 0.01)
            assertEquals(total * (1 - f), GreatCircle.distanceKm(p, dxb), 0.01)
            assertEquals(f, GreatCircle.fractionAlong(del, dxb, p), 1e-6)
        }
    }

    @Test
    fun `the same airport twice is harmless`() {
        assertEquals(0.0, GreatCircle.distanceKm(del, del), 0.0)
        assertEquals(del, GreatCircle.interpolate(del, del, 0.5))
        assertEquals(0.0, GreatCircle.bearingAt(del, del, 0.5), 0.0)
        assertEquals(0.0, GreatCircle.fractionAlong(del, del, del), 0.0)
    }

    @Test
    fun `cardinal bearings`() {
        val origin = GeoPoint(0.0, 0.0)
        assertEquals(0.0, GreatCircle.bearing(origin, GeoPoint(10.0, 0.0)), 1e-9)
        assertEquals(90.0, GreatCircle.bearing(origin, GeoPoint(0.0, 10.0)), 1e-9)
        assertEquals(180.0, GreatCircle.bearing(origin, GeoPoint(-10.0, 0.0)), 1e-9)
        assertEquals(270.0, GreatCircle.bearing(origin, GeoPoint(0.0, -10.0)), 1e-9)
    }

    @Test
    fun `path has both ends and the requested number of segments`() {
        val path = GreatCircle.path(del, dxb, 16)
        assertEquals(17, path.size)
        assertEquals(del, path.first())
        assertEquals(dxb, path.last())
    }

    @Test
    fun `path across the antimeridian is unwrapped`() {
        // Tokyo Haneda to San Francisco crosses 180°.
        val hnd = GeoPoint(35.5494, 139.7798)
        val sfo = GeoPoint(37.6190, -122.3750)
        val path = GreatCircle.path(hnd, sfo, 32)
        path.zipWithNext().forEach { (a, b) -> assertTrue("jump ${a.lon} -> ${b.lon}", abs(b.lon - a.lon) < 180) }
        assertEquals(-122.375 + 360, path.last().lon, 1e-6)
    }
}
