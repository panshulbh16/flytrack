package app.flytrack.feature.addflight

import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.time.SearchWindow
import app.flytrack.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class AddFlightInputTest {

    // ---- flight number while typing ---------------------------------------------------------------

    @Test
    fun `blank input is empty`() {
        assertEquals(FlightInputCheck.EMPTY, checkFlightInput(""))
        assertEquals(FlightInputCheck.EMPTY, checkFlightInput("   "))
    }

    @Test
    fun `complete flight numbers are valid`() {
        listOf("6E 2341", "6e2341", "ai101", "AI-101", " ai - 101 ", "6E 0234", "AI 101A", "6E 2341").forEach {
            assertEquals(it, FlightInputCheck.VALID, checkFlightInput(it))
        }
    }

    @Test
    fun `every prefix of a valid flight number is incomplete or valid, never invalid`() {
        listOf("6E 2341", "6e2341", "AI-101", " ai - 101 ", "6E 0234", "AI 101A", "9W 12").forEach { full ->
            for (length in 1..full.length) {
                val typed = full.take(length)
                if (typed.isBlank()) continue
                val check = checkFlightInput(typed)
                assertTrue("\"$typed\" (typing \"$full\") was $check", check == FlightInputCheck.INCOMPLETE || check == FlightInputCheck.VALID)
            }
        }
    }

    @Test
    fun `unfinished input is incomplete`() {
        listOf("6", "6E", "6E ", "AI-", "AI - ", "a").forEach {
            assertEquals(it, FlightInputCheck.INCOMPLETE, checkFlightInput(it))
        }
    }

    @Test
    fun `input that can never become a flight number is invalid`() {
        listOf("AIR", "air india 101", "12", "12 345", "6E 23415", "6E 2341AB", "6E-A", "6 E 2341", "6E--12", "indigo", "6E 23 41").forEach {
            assertEquals(it, FlightInputCheck.INVALID, checkFlightInput(it))
        }
    }

    @Test
    fun `the live check never calls something invalid that the parser accepts`() {
        val carriers = listOf("6E", "AI", "9w", "q2", "A1")
        val separators = listOf("", " ", "-", " - ", " ")
        val numbers = listOf("1", "012", "2341", "101a")
        for (c in carriers) for (s in separators) for (n in numbers) {
            val text = "$c$s$n"
            assertTrue(text, parseFlightNumber(text) != null)
            assertEquals(text, FlightInputCheck.VALID, checkFlightInput(text))
        }
    }

    // ---- PNR and seat -----------------------------------------------------------------------------

    @Test
    fun `pnr input keeps only upper-case letters and digits`() {
        assertEquals("ABC123", sanitizePnrInput("abc 123"))
        assertEquals("ABC123", sanitizePnrInput(" a-b-c/123 "))
        assertEquals("ABCDEFGH", sanitizePnrInput("abcdefghij"))
        assertEquals("", sanitizePnrInput("  -- "))
    }

    @Test
    fun `an empty or complete pnr is acceptable, a started one is not`() {
        assertTrue(isPnrInputAcceptable(""))
        assertTrue(isPnrInputAcceptable("ABC12"))
        assertTrue(isPnrInputAcceptable("ABCD1234"))
        assertFalse(isPnrInputAcceptable("ABC1"))
        assertFalse(isPnrInputAcceptable("A"))
    }

    @Test
    fun `seat input is upper-cased, has no spaces and at most five characters`() {
        assertEquals("12A", sanitizeSeatInput(" 12 a "))
        assertEquals("12ABC", sanitizeSeatInput("12abcdef"))
    }

    // ---- dates ------------------------------------------------------------------------------------

    @Test
    fun `selectable dates run from yesterday to the last day the server accepts`() {
        val today = LocalDate.parse("2026-11-22")
        val range = selectableDates(today, TestData.NOW)

        assertEquals(LocalDate.parse("2026-11-21"), range.start)
        assertEquals(LocalDate.parse("2027-11-22"), range.endInclusive)
        assertTrue(SearchWindow.contains(range.start, TestData.NOW))
        assertTrue(SearchWindow.contains(range.endInclusive, TestData.NOW))
    }

    @Test
    fun `west of UTC yesterday is dropped when the server would reject it`() {
        // 22 Nov 01:00 UTC is still 21 Nov, 20:00 in New York; the 20th is more than two days back for the server.
        val now = Instant.parse("2026-11-22T01:00:00Z")
        val today = LocalDate.parse("2026-11-21")

        val range = selectableDates(today, now)

        assertEquals(today, range.start)
        assertFalse(SearchWindow.contains(today.plusDays(-1), now))
    }

    @Test
    fun `every selectable date is accepted by the server window`() {
        listOf("2026-11-22T00:00:00Z", "2026-11-22T10:00:00Z", "2026-12-31T23:59:59Z", "2028-02-29T12:00:00Z").forEach { text ->
            val now = Instant.parse(text)
            val utcToday = LocalDate.parse(text.take(10))
            for (offsetDays in -1..1) {
                val range = selectableDates(utcToday.plusDays(offsetDays), now)
                assertTrue("$text $offsetDays start", SearchWindow.contains(range.start, now))
                assertTrue("$text $offsetDays end", SearchWindow.contains(range.endInclusive, now))
            }
        }
    }

    @Test
    fun `dates convert to the date picker's UTC milliseconds and back`() {
        val date = LocalDate.parse("2026-11-25")
        assertEquals(Instant.parse("2026-11-25T00:00:00Z").toEpochMilliseconds(), date.toUtcMillis())
        assertEquals(date, utcMillisToLocalDate(date.toUtcMillis()))
        assertEquals(date, utcMillisToLocalDate(date.toUtcMillis() + 86_399_999))
        assertEquals(LocalDate.parse("1969-12-31"), utcMillisToLocalDate(-1))
    }

    // ---- prefill ----------------------------------------------------------------------------------

    private val window = LocalDate.parse("2026-11-21")..LocalDate.parse("2027-11-22")

    @Test
    fun `no prefill leaves the form empty`() {
        val resolved = resolvePrefill(AddFlightPrefill(flight = " ", date = null, pnr = ""), window)

        assertEquals(ResolvedPrefill(flightInput = "", date = null, pnrInput = "", notice = null, autoSearch = false), resolved)
    }

    @Test
    fun `a readable ticket fills the form and searches`() {
        val resolved = resolvePrefill(AddFlightPrefill(" 6e2341 ", "2026-11-25", "ab-c123"), window)

        assertEquals("6e2341", resolved.flightInput)
        assertEquals(LocalDate.parse("2026-11-25"), resolved.date)
        assertEquals("ABC123", resolved.pnrInput)
        assertEquals(PrefillNotice.CheckDetails, resolved.notice)
        assertTrue(resolved.autoSearch)
    }

    @Test
    fun `an unreadable flight number is kept for editing and nothing is searched`() {
        val resolved = resolvePrefill(AddFlightPrefill("IndiGo 2341", "2026-11-25", null), window)

        assertEquals("IndiGo 2341", resolved.flightInput)
        assertEquals(LocalDate.parse("2026-11-25"), resolved.date)
        assertEquals(PrefillNotice.FlightUnreadable, resolved.notice)
        assertFalse(resolved.autoSearch)
    }

    @Test
    fun `a missing or malformed date does not search`() {
        listOf(null, "", "25/11/2026", "2026-13-45").forEach { date ->
            val resolved = resolvePrefill(AddFlightPrefill("6E 2341", date, null), window)

            assertNull(date, resolved.date)
            assertEquals(date, PrefillNotice.DateUnreadable, resolved.notice)
            assertFalse(date, resolved.autoSearch)
        }
    }

    @Test
    fun `a ticket date outside the searchable range is reported, not searched`() {
        val resolved = resolvePrefill(AddFlightPrefill("6E 2341", "2025-01-12", "ABC123"), window)

        assertNull(resolved.date)
        assertEquals(PrefillNotice.DateOutOfRange(LocalDate.parse("2025-01-12")), resolved.notice)
        assertFalse(resolved.autoSearch)
        assertEquals("ABC123", resolved.pnrInput)
    }

    @Test
    fun `only a pnr on the ticket still fills the pnr`() {
        val resolved = resolvePrefill(AddFlightPrefill(null, null, "abc123"), window)

        assertEquals("", resolved.flightInput)
        assertEquals("ABC123", resolved.pnrInput)
        assertEquals(PrefillNotice.FlightUnreadable, resolved.notice)
        assertFalse(resolved.autoSearch)
    }
}
