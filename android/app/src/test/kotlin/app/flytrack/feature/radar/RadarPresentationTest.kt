package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarPresentationTest {

    private val snapshot = RadarParser.parse(Fixtures.read("p4/radar_proximity.json"))
    private val rows = RadarPresentation.rows(snapshot)

    @Test
    fun labelPrefersFlightThenCallsignThenRegistrationThenHex() {
        val byKey = rows.associateBy { it.key }
        assertEquals("6E 2341", byKey.getValue("800c1b").label)
        assertEquals("IGO2341", byKey.getValue("800c1b").callsign)
        assertEquals("AIC101", byKey.getValue("800abc").label)
        assertNull(byKey.getValue("800abc").callsign)
        assertEquals("VT-ABC", byKey.getValue("800fff").label)
        assertEquals("800DEF", byKey.getValue("800def").label)
    }

    @Test
    fun rowsAreNearestFirst() {
        assertEquals("800abc", rows.first().key)
        assertTrue(rows.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
    }

    @Test
    fun altitudeAndTrend() {
        val byKey = rows.associateBy { it.key }
        assertEquals(AltitudeLabel.OnGround, byKey.getValue("800abc").altitude)
        assertEquals(AltitudeLabel.Feet(12400), byKey.getValue("800c1b").altitude)
        assertEquals(VerticalTrend.CLIMBING, byKey.getValue("800c1b").trend)
        assertEquals(VerticalTrend.LEVEL, byKey.getValue("800def").trend)
        assertEquals(VerticalTrend.DESCENDING, byKey.getValue("800fff").trend)
        assertEquals(VerticalTrend.LEVEL, byKey.getValue("800abc").trend)
    }

    @Test
    fun duplicateHexIsShownOnce() {
        val twice = snapshot.copy(aircraft = snapshot.aircraft + snapshot.aircraft.first())
        assertEquals(rows.size, RadarPresentation.rows(twice).size)
    }

    @Test
    fun refreshPolicy() {
        assertEquals(10L, RadarRefreshPolicy.nextDelaySeconds(RadarResult.Ok(snapshot)))
        assertEquals(10L, RadarRefreshPolicy.nextDelaySeconds(RadarResult.FeedError))
        assertEquals(10L, RadarRefreshPolicy.nextDelaySeconds(RadarResult.Offline))
        assertEquals(60L, RadarRefreshPolicy.nextDelaySeconds(RadarResult.RateLimited))
        assertNull(RadarRefreshPolicy.nextDelaySeconds(RadarResult.ComingSoon))
        assertNull(RadarRefreshPolicy.nextDelaySeconds(RadarResult.ProRequired))
        assertNull(RadarRefreshPolicy.nextDelaySeconds(RadarResult.UnknownAirport))
    }

    @Test
    fun styleKeepsTheOfflineBaseAndAddsRadarLayersOnTop() {
        val style = RadarMapStyle.style(app.flytrack.feature.map.MapPalette.Light)
        val layers = style.getValue("layers").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals("ocean", layers.first())
        assertTrue("land" in layers)
        assertEquals(RadarMapStyle.LAYER_PLANES, layers.last())
        assertTrue(layers.indexOf(RadarMapStyle.LAYER_LABELS) < layers.indexOf(RadarMapStyle.LAYER_PLANES))
        val sources = style.getValue("sources").jsonObject.keys
        assertTrue(sources.containsAll(listOf("land", RadarMapStyle.SOURCE_PLANES, RadarMapStyle.SOURCE_RANGE, RadarMapStyle.SOURCE_CENTER)))
        // Offline: no glyphs, no sprite, no tiles.
        assertTrue("glyphs" !in style && "sprite" !in style)
    }

    @Test
    fun planesCarryBearingLabelAndGroundFlag() {
        val features = Json.parseToJsonElement(RadarMapStyle.planesGeoJson(rows)).jsonObject.getValue("features").jsonArray
        assertEquals(rows.size, features.size)
        val props = features.first().jsonObject.getValue("properties").jsonObject
        assertEquals(270.0, props.getValue("bearing").jsonPrimitive.double, 0.0)
        assertEquals("radar-label:AIC101", props.getValue("label").jsonPrimitive.content)
        assertTrue(props.getValue("ground").jsonPrimitive.boolean)
    }

    @Test
    fun rangeRingIsClosedAndBoundsCoverTheRadius() {
        val center = GeoPoint(28.55, 77.1)
        val ring = RadarMapStyle.circle(center, 75.0)
        assertEquals(ring.first(), ring.last())
        val (south, west, north, east) = RadarMapStyle.bounds(center, 75).toList()
        assertEquals(75.0 / 111.32, north - center.lat, 1e-9)
        assertTrue(south < center.lat && west < center.lon && east > center.lon)
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", RadarMapStyle.rangeGeoJson(null, 75))
    }
}
