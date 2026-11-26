package app.flytrack.core.estimate

import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The shared estimate algorithm against `fixtures/estimate_cases.json` (the same cases as the Swift tests). */
class PositionEstimateTest {
    private val fixture = Json.parseToJsonElement(Fixtures.read("estimate_cases.json")).jsonObject
    private val routes = fixture.getValue("routes").jsonObject

    private fun JsonObject.d(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.point(key: String) = getValue(key).jsonObject.let { GeoPoint(it.d("lat"), it.d("lon")) }

    @Test
    fun `every fixture case matches position, progress and phase`() {
        val cases = fixture.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val route = routes.getValue(case.s("route")).jsonObject
            val estimate = estimatePosition(
                dep = route.point("dep"),
                arr = route.point("arr"),
                depTime = Instant.parse(route.s("depTime")),
                arrTime = Instant.parse(route.s("arrTime")),
                now = Instant.parse(case.s("now")),
            )
            val label = "${case.s("route")} at ${case.s("now")}"
            assertEquals(label, case.s("phase"), estimate.phase.wire)
            assertEquals(label, case.d("progress"), estimate.progress, 1e-6)
            assertEquals(label, case.d("lat"), estimate.lat, 1e-3)
            assertEquals(label, case.d("lon"), estimate.lon, 1e-3)
        }
    }

    @Test
    fun `fixture distances and bearings match the great-circle helpers`() {
        for ((name, element) in routes) {
            val route = element.jsonObject
            val dep = route.point("dep")
            val arr = route.point("arr")
            assertEquals(name, route.d("distanceKm"), GreatCircle.distanceKm(dep, arr), 0.01)
            assertEquals(name, route.d("initialBearing"), GreatCircle.bearing(dep, arr), 0.01)
            assertEquals(name, route.d("initialBearing"), GreatCircle.bearingAt(dep, arr, 0.0), 0.01)
            assertEquals(name, route.d("midBearing"), GreatCircle.bearingAt(dep, arr, 0.5), 0.01)
            assertEquals(name, route.d("finalBearing"), GreatCircle.bearingAt(dep, arr, 1.0), 0.01)
        }
    }

    @Test
    fun `progress is clamped and the phase follows the clock`() {
        val dep = GeoPoint(28.5665, 77.1031)
        val arr = GeoPoint(19.0887, 72.8679)
        val t0 = Instant.parse("2026-11-25T12:55:00Z")
        val t1 = t0 + 2.hours

        val before = estimatePosition(dep, arr, t0, t1, t0 - 1.minutes)
        assertEquals(EstimatePhase.PRE_DEPARTURE, before.phase)
        assertEquals(0.0, before.progress, 0.0)
        assertEquals(dep, before.point)

        val after = estimatePosition(dep, arr, t0, t1, t1 + 5.hours)
        assertEquals(EstimatePhase.ARRIVED, after.phase)
        assertEquals(1.0, after.progress, 0.0)
        assertEquals(arr, after.point)
    }

    @Test
    fun `an arrival not after the departure never reports airborne`() {
        val dep = GeoPoint(28.5665, 77.1031)
        val arr = GeoPoint(25.2528, 55.3644)
        val t = Instant.parse("2026-11-22T04:30:00Z")

        assertEquals(EstimatePhase.PRE_DEPARTURE, estimatePosition(dep, arr, t, t - 1.hours, t - 1.minutes).phase)
        assertEquals(EstimatePhase.ARRIVED, estimatePosition(dep, arr, t, t, t).phase)
        assertEquals(EstimatePhase.ARRIVED, estimatePosition(dep, arr, t, t - 1.hours, t + 1.minutes).phase)
    }

    @Test
    fun `wire names match the contract`() {
        assertEquals(listOf("pre_departure", "airborne", "arrived"), EstimatePhase.entries.map { it.wire })
    }
}
