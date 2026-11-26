package app.flytrack.feature.leave

import app.flytrack.core.model.FlightStatus
import app.flytrack.feature.airporthealth.AirportHealth
import app.flytrack.feature.airporthealth.AirportWeather
import app.flytrack.feature.airporthealth.HealthDetail
import app.flytrack.feature.airporthealth.HealthLevel
import app.flytrack.testing.Fixtures
import app.flytrack.testing.TestData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LeaveByTest {

    private val fixture: JsonObject = Json.parseToJsonElement(Fixtures.read("leave_by_cases.json")).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.contentOrNull

    // ---- shared fixtures (same cases as the iOS tests) ---------------------------------------------

    @Test fun leaveByMatchesSharedFixtures() {
        val cases = fixture.getValue("leaveBy").jsonArray
        assertTrue(cases.size >= 8)
        cases.map { it.jsonObject }.forEach { c ->
            val name = c.str("name")
            val dep = Instant.parse(c.str("depTime")!!)
            val international = c.getValue("international").jsonPrimitive.boolean
            val travel = c.getValue("travelMinutes").jsonPrimitive.int
            val fog = c.getValue("fogAtDep").jsonPrimitive.boolean
            val peak = c.getValue("peak").jsonPrimitive.boolean
            val result = leaveBy(dep, international, travel, fog, peak)
            assertEquals(name, Instant.parse(c.str("leaveBy")!!), result)
            assertEquals(name, c.getValue("bufferMinutes").jsonPrimitive.int, leaveBufferMinutes(international, fog, peak))
            assertEquals(name, Instant.parse(c.str("reminderAt")!!), result - LeaveRules.REMINDER_BEFORE_MIN.minutes)
        }
    }

    @Test fun peakHoursMatchSharedFixtures() {
        fixture.getValue("peak").jsonArray.map { it.jsonObject }.forEach { c ->
            assertEquals(c.str("name"), c.getValue("peak").jsonPrimitive.boolean, isPeakHour(Instant.parse(c.str("depTime")!!), c.str("tz")))
        }
    }

    @Test fun defaultsMatchFixture() {
        val defaults = fixture.getValue("defaults").jsonObject
        assertEquals(defaults.getValue("travelMinutes").jsonPrimitive.int, LeaveRules.DEFAULT_TRAVEL_MIN)
        assertEquals(defaults.getValue("reminderBeforeMinutes").jsonPrimitive.int, LeaveRules.REMINDER_BEFORE_MIN)
    }

    // ---- leavePlan --------------------------------------------------------------------------------

    /** 6E 2341 DEL-BOM departs 12:55Z = 18:25 IST (evening peak), domestic. */
    @Test fun planUsesScheduledDepartureAndDomesticPeakBuffer() {
        val plan = leavePlan(TestData.flight(), travelMinutes = 60, fogAtDep = false, now = TestData.NOW)!!
        assertEquals(Instant.parse("2026-11-25T12:55:00Z"), plan.departure)
        assertFalse(plan.international)
        assertTrue(plan.peak)
        assertEquals(140, plan.bufferMinutes)
        assertEquals(Instant.parse("2026-11-25T12:55:00Z") - 200.minutes, plan.leaveBy)
        assertEquals(plan.leaveBy - 15.minutes, plan.reminderAt)
        assertEquals("Asia/Kolkata", plan.depTz)
    }

    @Test fun planPrefersEstimatedDeparture() {
        val est = Instant.parse("2026-11-25T14:00:00Z") // 19:30 IST
        val plan = leavePlan(TestData.flight(estDep = est), 60, false, TestData.NOW)!!
        assertEquals(est, plan.departure)
    }

    @Test fun planIsInternationalAcrossZones() {
        val plan = leavePlan(TestData.flight(arrAirport = TestData.DXB), 60, fogAtDep = true, now = TestData.NOW)!!
        assertTrue(plan.international)
        assertTrue(plan.fog)
        assertEquals(180 + 30 + 20, plan.bufferMinutes)
    }

    @Test fun planClampsTravelMinutes() {
        assertEquals(LeaveRules.MAX_TRAVEL_MIN, leavePlan(TestData.flight(), 5_000, false, TestData.NOW)!!.travelMinutes)
        assertEquals(0, leavePlan(TestData.flight(), -5, false, TestData.NOW)!!.travelMinutes)
    }

    @Test fun noPlanForGoneCancelledOrTimelessFlights() {
        val now = TestData.NOW
        assertNull(leavePlan(TestData.flight(status = FlightStatus.CANCELLED), 60, false, now))
        assertNull(leavePlan(TestData.flight(status = FlightStatus.DIVERTED), 60, false, now))
        assertNull(leavePlan(TestData.flight(actDep = now, status = FlightStatus.DEPARTED), 60, false, now))
        assertNull(leavePlan(TestData.flight(schedDep = null), 60, false, now))
        assertNull(leavePlan(TestData.flight(schedDep = now - 1.hours), 60, false, now))
    }

    // ---- Airport Health --------------------------------------------------------------------------

    private fun health(level: HealthLevel, headline: String = "", fog: Boolean? = null) = AirportHealth(
        iata = "DEL",
        level = level,
        headline = headline,
        updatedAt = null,
        detail = fog?.let { HealthDetail(null, null, 0, 0, AirportWeather(300, null, it, null, "FG", null)) },
    )

    @Test fun fogOrDisruptionSlowsDeparture() {
        assertFalse(health(HealthLevel.NORMAL).slowsDeparture())
        assertFalse(health(HealthLevel.BUSY).slowsDeparture())
        assertTrue(health(HealthLevel.DISRUPTED).slowsDeparture())
        assertTrue(health(HealthLevel.SEVERE).slowsDeparture())
        assertTrue(health(HealthLevel.NORMAL, fog = true).slowsDeparture())
        assertFalse(health(HealthLevel.NORMAL, fog = false).slowsDeparture())
        // Free users get no detail: the headline still says fog.
        assertTrue(health(HealthLevel.BUSY, headline = "Fog: visibility 300 m").slowsDeparture())
    }

    // ---- directions ------------------------------------------------------------------------------

    @Test fun directionsNameTheTerminalAndCity() {
        val url = directionsUrl(TestData.DEL, "DEL", "1")
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1&destination="))
        assertTrue(url.endsWith("Indira+Gandhi+International+Airport+Terminal+1%2C+New+Delhi"))
    }

    @Test fun directionsWithoutAirportUseTheCode() {
        assertTrue(directionsUrl(null, "XYZ", null).endsWith("XYZ+airport"))
        assertTrue(directionsUrl(TestData.BOM, "BOM", " ").endsWith("Chhatrapati+Shivaji+International+Airport%2C+Mumbai"))
    }
}
