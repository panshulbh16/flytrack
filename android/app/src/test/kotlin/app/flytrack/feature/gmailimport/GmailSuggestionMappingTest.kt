package app.flytrack.feature.gmailimport

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Room itself does not run on the JVM (no SQLite driver); the row <-> model mapping does. */
class GmailSuggestionMappingTest {
    private val suggestion = GmailSuggestion(
        id = "6E2341_2026-12-03",
        messageId = "18c1f0a2b3d4e5f6",
        flight = "6E 2341",
        date = LocalDate(2026, 12, 3),
        fromIata = "DEL",
        toIata = null,
        pnr = "ABC123",
        subject = "Your IndiGo e-ticket",
        status = SuggestionStatus.DISMISSED,
        foundAtEpochMs = 1_764_000_000_000L,
    )

    @Test
    fun `the row stores the ISO date and the status wire value`() {
        val row = suggestion.toEntity()

        assertEquals("6E2341_2026-12-03", row.id)
        assertEquals("18c1f0a2b3d4e5f6", row.messageId)
        assertEquals("2026-12-03", row.date)
        assertEquals("dismissed", row.status)
        assertEquals("DEL", row.fromIata)
        assertNull(row.toIata)
        assertEquals(1_764_000_000_000L, row.foundAt)
    }

    @Test
    fun `a row maps back to the same suggestion`() {
        assertEquals(suggestion, suggestion.toEntity().toModel())
        val pending = suggestion.copy(status = SuggestionStatus.PENDING, pnr = null, fromIata = null)
        assertEquals(pending, pending.toEntity().toModel())
    }
}
