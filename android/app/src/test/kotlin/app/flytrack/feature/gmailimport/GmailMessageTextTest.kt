package app.flytrack.feature.gmailimport

import app.flytrack.feature.importticket.parseTicketText
import app.flytrack.testing.Fixtures
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic emails in src/test/resources/fixtures/gmail_message_*.json, shaped like `users.messages.get?format=full`. */
class GmailMessageTextTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate(2026, 11, 24)

    private fun message(name: String): GmailMessage = json.decodeFromString(Fixtures.read(name))

    @Test
    fun `indigo text-only email`() {
        val content = message("gmail_message_indigo_text.json").content()

        assertEquals("IndiGo booking confirmation - PNR Q7K2LM", content.subject)
        assertTrue(content.text.startsWith("Dear Customer,\n"))
        assertNull(content.pdfAttachmentId)
        assertEquals(0L, content.pdfSize)
        val ticket = parseTicketText(content.text, today)
        val leg = ticket.flights.single()
        assertEquals("6E 2341", leg.flight.display)
        assertEquals(LocalDate(2026, 12, 3), leg.date)
        assertEquals("DEL", leg.fromIata)
        assertEquals("BOM", leg.toIata)
        assertEquals("Q7K2LM", ticket.pnr)
    }

    @Test
    fun `air india html-only email is stripped to text`() {
        val content = message("gmail_message_airindia_html.json").content()

        assertEquals("Your Air India e-ticket itinerary", content.subject) // header name matched case-insensitively
        assertEquals(
            """
            Dear Passenger,
            Your Air India booking is confirmed.
            PNR A1B2C3
            Flight AI 665
            Date Fri, 12 Dec 2026
            Route BOM → DEL
            Fare & taxes: ₹6,240
            """.trimIndent(),
            content.text,
        )
        val ticket = parseTicketText(content.text, today)
        val leg = ticket.flights.single()
        assertEquals("AI 665", leg.flight.display)
        assertEquals(LocalDate(2026, 12, 12), leg.date)
        assertEquals("BOM", leg.fromIata)
        assertEquals("DEL", leg.toIata)
        assertEquals("A1B2C3", ticket.pnr)
    }

    @Test
    fun `akasa multipart email reads the plain part then the html part`() {
        val content = message("gmail_message_akasa_multipart.json").content()

        assertEquals("Akasa Air booking confirmation", content.subject)
        assertTrue(content.text.startsWith("Akasa Air booking confirmed\n")) // the plain part comes first
        assertTrue(content.text.contains("\nView in browser\n")) // only the HTML part says this
        // The leg is in both parts. The parser reads it twice (the second copy without its route, which the
        // parser gives to the first copy); fromTicket keeps the first per flight and date, the plain part's.
        val ticket = parseTicketText(content.text, today)
        val suggestion = GmailSuggestions.fromTicket(ticket, "m1", content.subject, today, foundAtEpochMs = 0).single()
        assertEquals("QP1372_2026-12-18", suggestion.id)
        assertEquals("BLR", suggestion.fromIata)
        assertEquals("BOM", suggestion.toIata)
        assertEquals("K8M3QZ", suggestion.pnr)
    }

    @Test
    fun `indigo email with only a pdf ticket gives the attachment`() {
        val content = message("gmail_message_indigo_pdf.json").content()

        assertEquals("IndiGo e-Ticket - PNR Q7K2LM", content.subject)
        assertEquals(
            "Hello,\nYour IndiGo e-ticket is attached to this email.\n\nHello,\nYour IndiGo e-ticket is attached to this email.",
            content.text, // plain part, a line break, then the HTML part as text
        )
        assertTrue(parseTicketText(content.text, today).flights.isEmpty())
        assertEquals("ANGjdJ8syntheticPdfAttachment01", content.pdfAttachmentId)
        assertEquals(48_213L, content.pdfSize)
    }

    @Test
    fun `a plain part that is only a view-in-browser stub does not hide the html ticket`() {
        val content = message("gmail_message_indigo_html_stub.json").content()

        assertEquals(
            "View this email in your browser\n\nView this email in your browser\nPNR Q7K2LM\nFlight 6E 5123\nDate Mon, 14 Dec 2026\nRoute DEL → GOI",
            content.text,
        )
        val ticket = parseTicketText(content.text, today)
        val leg = ticket.flights.single()
        assertEquals("6E 5123", leg.flight.display)
        assertEquals(LocalDate(2026, 12, 14), leg.date)
        assertEquals("DEL", leg.fromIata)
        assertEquals("GOI", leg.toIata)
        assertEquals("Q7K2LM", ticket.pnr)
    }

    @Test
    fun `message without a payload reads as empty`() {
        assertEquals(MessageContent("", "", null, 0), GmailMessage(id = "m1").content())
    }

    @Test
    fun `undecodable body falls back to the html part`() {
        val html = GmailPart(mimeType = "text/html", body = GmailBody(data = "PHA-SGk8L3A-")) // "<p>Hi</p>"
        val broken = GmailPart(mimeType = "text/plain", body = GmailBody(data = "not base64!"))
        val message = GmailMessage(id = "m1", payload = GmailPart(mimeType = "multipart/alternative", parts = listOf(broken, html)))

        assertEquals("Hi", message.content().text)
    }

    @Test
    fun `base64url with and without padding`() {
        assertArrayEquals("<<??>>".toByteArray(), decodeBase64Url("PDw_Pz4-"))
        assertArrayEquals("a".toByteArray(), decodeBase64Url("YQ"))
        assertArrayEquals("a".toByteArray(), decodeBase64Url("YQ=="))
    }

    @Test
    fun `html to text drops styles, breaks lines and decodes entities`() {
        assertEquals(
            "DEL → BOM\nFare & taxes ₹4,520\nA<B",
            htmlToText("<style>p { color: red }</style><p>DEL&nbsp;&rarr;&nbsp;BOM</p><p>Fare &amp; taxes &#x20B9;4,520<br>A&lt;B</p><!-- x -->"),
        )
    }
}
