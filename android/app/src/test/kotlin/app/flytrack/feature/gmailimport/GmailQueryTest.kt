package app.flytrack.feature.gmailimport

import org.junit.Assert.assertEquals
import org.junit.Test

class GmailQueryTest {
    private val terms =
        "subject:(ticket OR e-ticket OR itinerary OR booking OR PNR OR confirmation OR \"boarding pass\") " +
            "-subject:(cancel OR cancelled OR canceled OR cancellation OR refund OR refunded)"

    @Test
    fun `terms are exactly the spec's`() {
        assertEquals(terms, GmailQuery.TERMS)
    }

    @Test
    fun `first scan looks back 180 days`() {
        assertEquals("$terms newer_than:180d", GmailQuery.build(lastCheckEpochSeconds = null))
    }

    @Test
    fun `later scans start an hour before the last check`() {
        assertEquals("$terms after:1764000000", GmailQuery.build(lastCheckEpochSeconds = 1_764_003_600))
    }
}
