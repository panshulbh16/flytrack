package app.flytrack.feature.checkin

import app.flytrack.core.model.FlightStatus
import app.flytrack.testing.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CheckinWindowTest {
    private val indigo = BUILT_IN_RULES.getValue("6E")
    private val dep = Instant.parse("2026-11-25T12:55:00Z")
    private val domestic = TestData.flight(schedDep = dep) // DEL-BOM, both Asia/Kolkata
    private val international = TestData.flight(arrAirport = TestData.DXB, schedDep = dep)

    @Test
    fun `before the window it opens 48 hours before departure`() {
        val window = checkinWindow(indigo, domestic, dep - 54.hours)
        assertEquals(CheckinWindow.NotOpenYet(opensAt = dep - 48.hours, closesAt = dep - 60.minutes), window)
    }

    @Test
    fun `the window opens exactly at its start`() {
        val window = checkinWindow(indigo, domestic, dep - 48.hours)
        assertEquals(CheckinWindow.Open(dep - 48.hours, dep - 60.minutes, closingSoon = false), window)
    }

    @Test
    fun `closing soon in the last 30 minutes, like CHECKIN_CLOSING`() {
        assertEquals(false, (checkinWindow(indigo, domestic, dep - 91.minutes) as CheckinWindow.Open).closingSoon)
        assertEquals(true, (checkinWindow(indigo, domestic, dep - 90.minutes) as CheckinWindow.Open).closingSoon)
        assertEquals(true, (checkinWindow(indigo, domestic, dep - 61.minutes) as CheckinWindow.Open).closingSoon)
    }

    @Test
    fun `closed after the domestic close until departure`() {
        assertEquals(CheckinWindow.Closed(dep - 48.hours, dep - 60.minutes), checkinWindow(indigo, domestic, dep - 60.minutes))
        assertTrue(checkinWindow(indigo, domestic, dep - 1.minutes) is CheckinWindow.Closed)
    }

    @Test
    fun `international flights close earlier`() {
        assertTrue(international.isInternationalForCheckin)
        assertFalse(domestic.isInternationalForCheckin)
        assertEquals(dep - 75.minutes, checkinWindow(indigo, international, dep - 2.hours)?.closesAt)
        assertTrue(checkinWindow(indigo, international, dep - 70.minutes) is CheckinWindow.Closed)
        assertTrue(checkinWindow(indigo, domestic, dep - 70.minutes) is CheckinWindow.Open)
    }

    @Test
    fun `unknown airports count as international`() {
        assertTrue(TestData.flight(arrAirport = null, arrIata = "XYZ").isInternationalForCheckin)
    }

    @Test
    fun `an estimated departure moves the window`() {
        val late = TestData.flight(schedDep = dep, estDep = dep + 2.hours)
        assertEquals(dep + 2.hours - 60.minutes, checkinWindow(indigo, late, dep - 3.hours)?.closesAt)
        assertTrue(checkinWindow(indigo, late, dep - 30.minutes) is CheckinWindow.Open)
    }

    @Test
    fun `nothing to show without a rule, a time, or a flight that can still be boarded`() {
        val now = dep - 10.hours
        assertNull(checkinWindow(null, domestic, now))
        assertNull(checkinWindow(indigo, TestData.flight(schedDep = null, estDep = null), now))
        assertNull(checkinWindow(indigo, TestData.flight(schedDep = dep, status = FlightStatus.CANCELLED), now))
        assertNull(checkinWindow(indigo, TestData.flight(schedDep = dep, status = FlightStatus.DIVERTED), now))
        assertNull(checkinWindow(indigo, TestData.flight(schedDep = dep, actDep = now), now))
        assertNull(checkinWindow(indigo, domestic, dep))
        assertNull(checkinWindow(indigo, domestic, dep + 1.hours))
    }

    @Test
    fun `the button is offered only while open and with a link`() {
        val open = checkinUiModel(indigo, domestic, dep - 5.hours)!!
        assertTrue(open.canCheckInNow)
        assertEquals("Asia/Kolkata", open.depTz)
        assertTrue(open.verified)
        assertFalse(checkinUiModel(indigo.copy(checkinUrl = null), domestic, dep - 5.hours)!!.canCheckInNow)
        assertFalse(checkinUiModel(indigo, domestic, dep - 50.hours)!!.canCheckInNow)
        assertFalse(checkinUiModel(BUILT_IN_RULES.getValue("SG"), domestic, dep - 5.hours)!!.verified)
    }

    @Test
    fun `only absolute https links may be opened`() {
        assertEquals("https://www.goindigo.in/web-check-in.html", safeCheckinUrl(" https://www.goindigo.in/web-check-in.html "))
        assertNull(safeCheckinUrl(null))
        assertNull(safeCheckinUrl(""))
        assertNull(safeCheckinUrl("http://www.goindigo.in/"))
        assertNull(safeCheckinUrl("javascript:alert(1)"))
        assertNull(safeCheckinUrl("intent://scan#Intent;scheme=zxing;end"))
        assertNull(safeCheckinUrl("file:///sdcard/x.html"))
        assertNull(safeCheckinUrl("https:///nohost"))
        assertNull(safeCheckinUrl("https://exa mple.com"))
    }

    @Test
    fun `a tapped push opens the page only for check-in alerts`() {
        val url = "https://www.spicejet.com/check-in"
        assertEquals(url, checkinUrlFromPush("CHECKIN_OPEN", url))
        assertEquals(url, checkinUrlFromPush("CHECKIN_CLOSING", url))
        assertNull(checkinUrlFromPush("GATE_CHANGE", url))
        assertNull(checkinUrlFromPush(null, url))
        assertNull(checkinUrlFromPush("CHECKIN_OPEN", "http://evil.example"))
    }

    @Test
    fun `the built-in seed matches the contract`() {
        assertEquals(setOf("6E", "AI", "IX", "QP", "SG"), BUILT_IN_RULES.keys)
        assertTrue(BUILT_IN_RULES.values.all { it.opensHoursBefore == 48 && it.closesMinutesBefore == 60 && it.closesMinutesBeforeIntl == 75 })
        assertTrue(BUILT_IN_RULES.values.all { safeCheckinUrl(it.checkinUrl) != null })
        assertEquals(listOf("6E"), BUILT_IN_RULES.values.filter { it.verified }.map { it.carrierIata })
    }
}
