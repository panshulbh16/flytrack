package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class RadarParserTest {

    private val snapshot = RadarParser.parse(Fixtures.read("p4/radar_proximity.json"))

    @Test
    fun parsesCenterRadiusFeedAndTime() {
        assertEquals(GeoPoint(28.55, 77.1), snapshot.center)
        assertEquals(75, snapshot.radiusKm)
        assertEquals("opensky", snapshot.feed)
        assertEquals(Instant.parse("2026-11-27T10:00:05.123Z"), snapshot.asOf)
    }

    @Test
    fun dropsAircraftWithoutAPosition() {
        assertEquals(4, snapshot.aircraft.size)
        assertTrue(snapshot.aircraft.none { it.callsign == "NOPOS" })
    }

    @Test
    fun trimsCallsignAndLowercasesHex() {
        val first = snapshot.aircraft.first()
        assertEquals("IGO2341", first.callsign)
        assertEquals("800c1b", first.icao24)
        assertEquals("VT-IZS", first.registration)
        assertEquals("6E2341", first.flight)
        assertEquals(12437, first.altitudeFt)
        assertEquals(1408, first.verticalRateFpm)
        assertEquals("800abc", snapshot.aircraft[1].icao24)
    }

    @Test
    fun normalisesTrackAndGroundAltitude() {
        val ground = snapshot.aircraft[1]
        assertTrue(ground.onGround)
        assertEquals(0, ground.altitudeFt)
        assertEquals(270.0, ground.trackDeg, 0.0)
        assertEquals(40.0, snapshot.aircraft[2].trackDeg, 0.0)
        assertNull(ground.verticalRateFpm)
        assertNull(ground.flight)
    }

    @Test
    fun mapsErrorCodes() {
        assertEquals(RadarResult.ComingSoon, RadarParser.error(503, "feed_unavailable"))
        assertEquals(RadarResult.ComingSoon, RadarParser.error(503, "feed_unlicensed"))
        assertEquals(RadarResult.ProRequired, RadarParser.error(402, "pro_required"))
        assertEquals(RadarResult.RateLimited, RadarParser.error(429, "rate_limited"))
        assertEquals(RadarResult.FeedError, RadarParser.error(502, "feed_error"))
        assertEquals(RadarResult.UnknownAirport, RadarParser.error(404, "not_found"))
        // An undeployed function answers 404 without a code: the friendly state, not an error.
        assertEquals(RadarResult.ComingSoon, RadarParser.error(404, null))
        assertEquals(RadarResult.ProRequired, RadarParser.error(402, null))
        assertEquals(RadarResult.RateLimited, RadarParser.error(429, null))
        assertTrue(RadarParser.error(400, "bad_input") is RadarResult.Failed)
        assertTrue(RadarParser.error(500, "internal") is RadarResult.Failed)
    }

    @Test
    fun roundsToTheFiveHundredthsGrid() {
        assertEquals(28.6, roundToGrid(28.6139), 0.0)
        assertEquals(28.65, roundToGrid(28.64), 0.0)
        assertEquals(77.2, roundToGrid(77.2090), 0.0)
        assertEquals(28.6, roundToGrid(28.624), 0.0)
        assertEquals(28.65, roundToGrid(28.626), 0.0)
        assertEquals(-33.85, roundToGrid(-33.8688), 0.0)
        assertEquals(0.0, roundToGrid(0.01), 0.0)
    }

    @Test
    fun proximityQueryIsRoundedBeforeItIsSent() {
        val query = RadarQuery.around(GeoPoint(28.61394, 77.20902))
        assertEquals(listOf("mode" to "proximity", "lat" to "28.60", "lon" to "77.20"), query.parameters())
        assertEquals(listOf("mode" to "ground", "iata" to "DEL"), RadarQuery.Ground("del").parameters())
    }

    @Test
    fun bearingsWrapInto0To360() {
        assertEquals(0.0, normalizeBearing(360.0), 0.0)
        assertEquals(350.0, normalizeBearing(-10.0), 0.0)
        assertEquals(0.0, normalizeBearing(Double.NaN), 0.0)
    }
}
