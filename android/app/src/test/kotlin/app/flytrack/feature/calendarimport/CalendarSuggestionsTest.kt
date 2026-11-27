package app.flytrack.feature.calendarimport

import app.flytrack.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSuggestionsTest {
    private val parser = CalendarFlightParser(CalendarFlightParserTest.catalog)
    private val today = LocalDate.parse("2026-11-22")

    private fun event(id: String, summary: String, start: String? = "2026-11-25T06:00:00+05:30", end: String? = "2026-11-25T08:10:00+05:30", description: String? = null) =
        CalendarEvent(id = id, summary = summary, description = description, start = start?.let { EventTime(dateTime = it) }, end = end?.let { EventTime(dateTime = it) })

    private fun suggestions(vararg events: CalendarEvent, tracked: List<app.flytrack.core.model.Flight> = emptyList()) =
        buildSuggestions(events.flatMap(parser::candidates), tracked, today)

    @Test
    fun `a numbered flight is searched by number on its local date`() {
        val s = suggestions(event("1", "IndiGo 6E 2341 DEL → BOM")).single()
        assertEquals("6E 2341", s.flight)
        assertEquals(LocalDate.parse("2026-11-25"), s.date)
        assertEquals("06:00", s.departTime)
        assertEquals(SuggestionAction.ByNumber("6E 2341", LocalDate.parse("2026-11-25")), s.action)
        assertEquals("device:1", s.id)
    }

    @Test
    fun `leading zeros are normalised like the search`() {
        assertEquals("6E 234", suggestions(event("1", "Flight 6E 0234 DEL to BOM")).single().flight)
    }

    @Test
    fun `without a number the route tab is used`() {
        val s = suggestions(event("1", "Flight Delhi to Mumbai Chhatrapati Shivaji")).single()
        assertNull(s.flight)
        assertEquals(SuggestionAction.ByRoute("DEL", "BOM", LocalDate.parse("2026-11-25")), s.action)
    }

    @Test
    fun `past events, duplicates and empty reminders are dropped, the rest sorted`() {
        val list = suggestions(
            event("late", "Flight AI 101 DEL - JFK", start = "2026-12-01T01:00:00+05:30", end = "2026-12-01T16:00:00-05:00"),
            event("past", "Flight 6E 1 DEL → BOM", start = "2026-11-20T06:00:00+05:30", end = "2026-11-20T08:00:00+05:30"),
            event("a", "IndiGo 6E 2341 DEL → BOM"),
            event("b", "6E 2341 reminder flight", description = "DEL → BOM"),
            event("c", "Flight ✈"),
        )
        assertEquals(listOf("device:a", "device:late"), list.map { it.id })
    }

    @Test
    fun `an all-day event uses its date and without a time it is flagged`() {
        val e = CalendarEvent(id = "d", summary = "Flight 6E 2341 DEL → BOM", start = EventTime(date = "2026-11-30"), end = EventTime(date = "2026-12-01"))
        val s = suggestions(e).single()
        assertEquals(LocalDate.parse("2026-11-30"), s.date)
        assertNull(s.departTime)
        assertTrue(ReviewIssue.UNTIMED in s.issues)
    }

    @Test
    fun `no date means it needs details`() {
        val s = suggestions(event("x", "Flight 6E 2341 DEL → BOM", start = null, end = null)).single()
        assertEquals(SuggestionAction.NeedsDetails, s.action)
    }

    @Test
    fun `multi-leg events become one suggestion per leg`() {
        val list = suggestions(event("m", "Emirates to London", description = "EK 569 BLR → DXB 04:30 - 07:05\nEK 1 DXB → LHR 07:45 - 11:25"))
        assertEquals(listOf("device:m:leg1", "device:m:leg2"), list.map { it.id })
        assertEquals(listOf("EK 569", "EK 1"), list.map { it.flight })
        assertEquals(listOf(1, 2), list.map { it.leg })
        // The second leg has no stated date of its own: it is listed but cannot be searched yet.
        assertEquals(SuggestionAction.NeedsDetails, list[1].action)
    }

    @Test
    fun `tracked flights are marked`() {
        val tracked = TestData.flight() // 6E 2341 DEL-BOM
        val s = suggestions(event("1", "IndiGo ${tracked.designator} DEL → BOM", start = "${tracked.flightDate}T06:00:00+05:30", end = "${tracked.flightDate}T08:00:00+05:30"), tracked = listOf(tracked)).single()
        assertTrue(s.alreadyTracked)
        val other = suggestions(event("2", "IndiGo 6E 9999 DEL → BOM", start = "${tracked.flightDate}T06:00:00+05:30", end = "${tracked.flightDate}T08:00:00+05:30"), tracked = listOf(tracked)).single()
        assertFalse(other.alreadyTracked)
    }

    @Test
    fun `device rows map to the parser's events`() {
        val timed = DeviceEventRow(7, "Flight DEL → BOM", null, "T3", 1_795_564_800_000, 1_795_572_600_000, allDay = false, status = 1).toCalendarEvent()
        assertEquals("7:1795564800000", timed.id)
        assertEquals("2026-11-25T00:00:00Z", timed.start?.dateTime)
        assertNull(timed.status)
        val allDay = DeviceEventRow(8, "Flight", null, null, 1_795_478_400_000, 1_795_564_800_000, allDay = true, status = 2).toCalendarEvent()
        assertEquals("2026-11-24", allDay.start?.date)
        assertNull(allDay.start?.dateTime)
        assertEquals("cancelled", allDay.status)
    }
}
