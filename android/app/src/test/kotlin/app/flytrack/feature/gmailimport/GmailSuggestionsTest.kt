package app.flytrack.feature.gmailimport

import app.flytrack.core.flightnumber.FlightNumber
import app.flytrack.feature.importticket.ParsedFlight
import app.flytrack.feature.importticket.ParsedTicket
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailSuggestionsTest {
    private val today = LocalDate(2026, 11, 24)
    private val dec3 = LocalDate(2026, 12, 3)
    private val dec12 = LocalDate(2026, 12, 12)
    private val yesterday = LocalDate(2026, 11, 23)

    private fun suggestion(
        flight: String,
        date: LocalDate,
        status: SuggestionStatus = SuggestionStatus.PENDING,
        foundAt: Long = 1L,
        pnr: String? = null,
        subject: String = "Booking confirmation",
    ) = GmailSuggestion(
        id = GmailSuggestions.suggestionId(GmailSuggestions.compact(flight), date),
        messageId = "msg-$foundAt",
        flight = flight,
        date = date,
        fromIata = null,
        toIata = null,
        pnr = pnr,
        subject = subject,
        status = status,
        foundAtEpochMs = foundAt,
    )

    @Test
    fun `ids are the compact flight and the iso date`() {
        assertEquals("6E2341", GmailSuggestions.compact("6E 2341"))
        assertEquals("AI101A", GmailSuggestions.compact("ai-101a"))
        val id = GmailSuggestions.suggestionId("6E2341", dec3)
        assertEquals("6E2341_2026-12-03", id)
        assertTrue(Regex("""^[A-Z0-9]{3,8}_\d{4}-\d{2}-\d{2}$""").matches(id))
    }

    @Test
    fun `status wire values round trip and unknown reads as dismissed`() {
        SuggestionStatus.entries.forEach { assertEquals(it, SuggestionStatus.fromWire(it.wire)) }
        assertEquals(SuggestionStatus.DISMISSED, SuggestionStatus.fromWire("archived"))
    }

    @Test
    fun `cancellation and refund subjects are caught`() {
        assertTrue(GmailSuggestions.isCancellation("Your booking has been CANCELLED"))
        assertTrue(GmailSuggestions.isCancellation("Cancellation of PNR Q7K2LM"))
        assertTrue(GmailSuggestions.isCancellation("Refund processed for your e-ticket"))
        assertFalse(GmailSuggestions.isCancellation("IndiGo booking confirmation - PNR Q7K2LM"))
    }

    @Test
    fun `from ticket drops undated and past legs and keeps legs without a route`() {
        val ticket = ParsedTicket(
            flights = listOf(
                ParsedFlight(FlightNumber("6E", "2341"), dec3, "DEL", "BOM"),
                ParsedFlight(FlightNumber("AI", "101"), null, "DEL", "LHR"),
                ParsedFlight(FlightNumber("QP", "1372"), yesterday, "BLR", "BOM"),
                ParsedFlight(FlightNumber("QP", "1373"), today, null, null),
            ),
            pnr = "Q7K2LM",
        )

        val found = GmailSuggestions.fromTicket(ticket, "msg-1", "IndiGo booking confirmation", today, foundAtEpochMs = 42L)

        assertEquals(listOf("6E2341_2026-12-03", "QP1373_2026-11-24"), found.map { it.id })
        assertEquals(
            GmailSuggestion(
                id = "6E2341_2026-12-03", messageId = "msg-1", flight = "6E 2341", date = dec3,
                fromIata = "DEL", toIata = "BOM", pnr = "Q7K2LM", subject = "IndiGo booking confirmation",
                status = SuggestionStatus.PENDING, foundAtEpochMs = 42L,
            ),
            found[0],
        )
        assertEquals(null to null, found[1].fromIata to found[1].toIata)
    }

    @Test
    fun `from ticket gives one suggestion per flight and date`() {
        val ticket = ParsedTicket(
            flights = listOf(
                ParsedFlight(FlightNumber("6E", "2341"), dec3, "DEL", "BOM"),
                ParsedFlight(FlightNumber("6E", "2341"), dec3, null, null),
            ),
            pnr = null,
        )

        assertEquals(1, GmailSuggestions.fromTicket(ticket, "msg-1", "Itinerary", today, 1L).size)
    }

    @Test
    fun `new only merges duplicates keeping the newest email`() {
        val older = suggestion("6E 2341", dec3, foundAt = 10L, pnr = "OLD111", subject = "Old booking")
        val newer = suggestion("6E 2341", dec3, foundAt = 20L, pnr = "NEW222", subject = "Changed booking")
        val tieLater = suggestion("AI 665", dec12, foundAt = 5L, subject = "Second")

        val result = GmailSuggestions.newOnly(
            found = listOf(newer, older, suggestion("AI 665", dec12, foundAt = 5L, subject = "First"), tieLater),
            existingIds = emptySet(),
            tracked = emptySet(),
        )

        assertEquals(listOf(newer, tieLater), result)
    }

    @Test
    fun `new only drops tracked flights and existing suggestions of any status`() {
        val tracked = suggestion("6E 2341", dec3)
        val dismissedBefore = suggestion("AI 665", dec12)
        val fresh = suggestion("QP 1372", dec12)

        val result = GmailSuggestions.newOnly(
            found = listOf(tracked, dismissedBefore, fresh),
            existingIds = setOf(dismissedBefore.id),
            tracked = setOf(TrackedKey("6E2341", dec3)),
        )

        assertEquals(listOf(fresh), result)
    }

    @Test
    fun `visible shows upcoming untracked pending suggestions soonest first`() {
        val later = suggestion("QP 1372", dec12)
        val soon = suggestion("AI 665", dec3)
        val todayOne = suggestion("6E 100", today)
        val all = listOf(
            later,
            suggestion("6E 2341", dec3), // tracked another way
            suggestion("SG 8", dec3, status = SuggestionStatus.DISMISSED),
            suggestion("IX 1345", dec3, status = SuggestionStatus.ADDED),
            suggestion("UK 955", yesterday),
            soon,
            todayOne,
        )

        val shown = GmailSuggestions.visible(all, today, tracked = setOf(TrackedKey("6E2341", dec3)))

        assertEquals(listOf(todayOne, soon, later), shown)
    }

    @Test
    fun `cleanup deletes past suggestions of any status and marks tracked pending ones added`() {
        val all = listOf(
            suggestion("6E 1", yesterday),
            suggestion("6E 2", yesterday, status = SuggestionStatus.DISMISSED),
            suggestion("6E 3", yesterday, status = SuggestionStatus.ADDED),
            suggestion("6E 2341", dec3), // pending, now tracked
            suggestion("AI 665", dec12, status = SuggestionStatus.DISMISSED), // tracked but not pending
            suggestion("QP 1372", dec12), // pending, untracked: left alone
        )
        val tracked = setOf(TrackedKey("6E2341", dec3), TrackedKey("AI665", dec12))

        val cleanup = GmailSuggestions.cleanup(all, today, tracked)

        assertEquals(listOf("6E1_2026-11-23", "6E2_2026-11-23", "6E3_2026-11-23"), cleanup.deleteIds)
        assertEquals(listOf("6E2341_2026-12-03"), cleanup.markAddedIds)
    }
}
