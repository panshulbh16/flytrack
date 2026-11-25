package app.flytrack.feature.gmailimport

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailAutoCheckTest {
    private val email = "traveller@gmail.com"
    private val now = 1_800_000_000L

    @Test
    fun `background check runs only when connected, auto-check is on and access is not lost`() {
        assertTrue(gmailAutoCheckEnabled(email, autoCheck = true, needsReconnect = false))
        assertFalse(gmailAutoCheckEnabled(null, autoCheck = true, needsReconnect = false))
        assertFalse(gmailAutoCheckEnabled(email, autoCheck = false, needsReconnect = false))
        assertFalse(gmailAutoCheckEnabled(email, autoCheck = true, needsReconnect = true))
    }

    @Test
    fun `with the release switch off nothing checks gmail`() {
        assertFalse(gmailAutoCheckEnabled(email, autoCheck = true, needsReconnect = false, importEnabled = false))
        assertFalse(
            gmailAppOpenCheckDue(
                email, autoCheck = true, needsReconnect = false, lastAppOpenCheckEpochSeconds = null,
                nowEpochSeconds = now, importEnabled = false,
            ),
        )
    }

    @Test
    fun `with the release switch off a stored account is wiped`() {
        assertTrue(gmailLeftoversNeedWipe(email, importEnabled = false))
        assertFalse(gmailLeftoversNeedWipe(null, importEnabled = false))
        assertFalse(gmailLeftoversNeedWipe(email, importEnabled = true))
    }

    @Test
    fun `app-open check runs at most once an hour`() {
        fun due(last: Long?) = gmailAppOpenCheckDue(email, autoCheck = true, needsReconnect = false, lastAppOpenCheckEpochSeconds = last, nowEpochSeconds = now)

        assertTrue(due(null))
        assertFalse(due(now))
        assertFalse(due(now - 3_599))
        assertTrue(due(now - 3_600))
        assertTrue(due(now - 86_400))
    }

    @Test
    fun `a clock set backwards does not block the app-open check`() {
        assertTrue(gmailAppOpenCheckDue(email, autoCheck = true, needsReconnect = false, lastAppOpenCheckEpochSeconds = now + 7_200, nowEpochSeconds = now))
    }

    @Test
    fun `app-open check needs the same conditions as the background check`() {
        assertFalse(gmailAppOpenCheckDue(null, autoCheck = true, needsReconnect = false, lastAppOpenCheckEpochSeconds = null, nowEpochSeconds = now))
        assertFalse(gmailAppOpenCheckDue(email, autoCheck = false, needsReconnect = false, lastAppOpenCheckEpochSeconds = null, nowEpochSeconds = now))
        assertFalse(gmailAppOpenCheckDue(email, autoCheck = true, needsReconnect = true, lastAppOpenCheckEpochSeconds = null, nowEpochSeconds = now))
    }

    @Test
    fun `notification route is shown only when both airports are known`() {
        val base = GmailSuggestion(
            id = "6E2341_2026-12-03",
            messageId = "m1",
            flight = "6E 2341",
            date = LocalDate(2026, 12, 3),
            fromIata = "DEL",
            toIata = "BOM",
            pnr = "ABC123",
            subject = "Your IndiGo booking",
            status = SuggestionStatus.PENDING,
            foundAtEpochMs = 0L,
        )
        assertEquals("DEL→BOM", base.notificationRoute())
        assertNull(base.copy(toIata = null).notificationRoute())
        assertNull(base.copy(fromIata = null).notificationRoute())
    }
}
