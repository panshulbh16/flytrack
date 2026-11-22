package app.flytrack.feature.importticket

import app.flytrack.core.model.TrackSource
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketParserTest {
    private val today = LocalDate(2026, 11, 22)

    private fun parse(text: String) = parseTicketText(text, today)

    @Test
    fun `indigo email with a weekday date and a labelled pnr`() {
        val ticket = parse(
            """
            IndiGo
            Booking Reference: Q7K2LM
            Flight 6E 2341
            Delhi (DEL) to Mumbai (BOM)
            Tue, 25 Nov 2026
            Departs 18:50
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("6E 2341", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("DEL", flight.fromIata)
        assertEquals("BOM", flight.toIata)
        assertEquals("Q7K2LM", ticket.pnr)
    }

    @Test
    fun `air india`() {
        val ticket = parse(
            """
            Air India
            PNR: A1B2C3
            AI-101
            25 Nov 2026
            DEL → BOM
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("AI 101", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("DEL", flight.fromIata)
        assertEquals("BOM", flight.toIata)
        assertEquals("A1B2C3", ticket.pnr)
    }

    @Test
    fun `air india express compact date`() {
        val ticket = parse(
            """
            Air India Express
            IX 1345
            25NOV26
            BLR-CCU
            Booking ID: ZX9PL4
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("IX 1345", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("BLR", flight.fromIata)
        assertEquals("CCU", flight.toIata)
        assertEquals("ZX9PL4", ticket.pnr)
    }

    @Test
    fun `akasa day first slash date`() {
        val ticket = parse(
            """
            Akasa Air
            QP 1372
            25/11/2026
            BOM to DEL
            Confirmation code: K8M2NP
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("QP 1372", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("BOM", flight.fromIata)
        assertEquals("DEL", flight.toIata)
        assertEquals("K8M2NP", ticket.pnr)
    }

    @Test
    fun `spicejet record locator`() {
        val ticket = parse(
            """
            SpiceJet SG 815
            Departure date: 25 Nov 2026
            HYD-MAA
            Record locator M2N4P6
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("SG 815", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("HYD", flight.fromIata)
        assertEquals("MAA", flight.toIata)
        assertEquals("M2N4P6", ticket.pnr)
    }

    @Test
    fun `makemytrip omits the year on a future day`() {
        val ticket = parse(
            """
            MakeMyTrip
            Booking ID: NF7K2Q
            IndiGo 6E-2341
            Delhi (DEL) → Mumbai (BOM)
            Tue, 25 Nov
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("6E 2341", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("NF7K2Q", ticket.pnr)
        assertEquals("DEL", flight.fromIata)
        assertEquals("BOM", flight.toIata)
    }

    @Test
    fun `a return ticket keeps each flight with its own date`() {
        val ticket = parse(
            """
            EaseMyTrip
            Confirmation Number: H4P9LM
            Onward
            SpiceJet SG-815
            25/11/2026
            BLR to DEL
            Return
            Air India AI 102
            02/12/2026
            DEL to BLR
            """.trimIndent(),
        )
        assertEquals(2, ticket.flights.size)
        val onward = ticket.flights.first { it.flight.display == "SG 815" }
        val back = ticket.flights.first { it.flight.display == "AI 102" }
        assertEquals(LocalDate(2026, 11, 25), onward.date)
        assertEquals("BLR", onward.fromIata)
        assertEquals("DEL", onward.toIata)
        assertEquals(LocalDate(2026, 12, 2), back.date)
        assertEquals("DEL", back.fromIata)
        assertEquals("BLR", back.toIata)
        assertEquals("H4P9LM", ticket.pnr)
    }

    @Test
    fun `ocr noise still yields the indigo flight`() {
        val ticket = parse(
            """
            lndiGo
            PNR Q7K2LM
            6E 234l
            25 N0V 2026
            Delhi (DEL) to Mumbai (BOM)
            """.trimIndent(),
        )
        val flight = ticket.flights.single()
        assertEquals("6E 2341", flight.flight.display)
        assertEquals(LocalDate(2026, 11, 25), flight.date)
        assertEquals("Q7K2LM", ticket.pnr)
    }

    @Test
    fun `an indian carrier is listed before a foreign one`() {
        val ticket = parse("LH 760\n6E 2341\n25 Nov 2026")
        assertEquals("6E 2341", ticket.flights.first().flight.display)
        assertEquals("LH 760", ticket.flights[1].flight.display)
    }

    @Test
    fun `a date with no year rolls into next year when this year has passed`() {
        assertEquals(LocalDate(2027, 1, 2), inferYear(today, month = 1, day = 2))
        assertEquals(LocalDate(2026, 11, 20), inferYear(today, month = 11, day = 20))
    }

    @Test
    fun `slash dates are day first and a month of 25 is not a date`() {
        assertEquals(LocalDate(2026, 11, 25), parse("6E 2341\n25/11/2026").flights.single().date)
        assertNull(parse("6E 2341\n11/25/2026").flights.single().date)
    }

    @Test
    fun `a flight number next to the pnr label is not the pnr`() {
        val ticket = parse("PNR 6E2341\nBooking Reference: ABC123\n6E 2341\n25 Nov 2026")
        assertEquals("ABC123", ticket.pnr)
    }

    @Test
    fun `a seat number is not a flight`() {
        val ticket = parse("Seat 12A\nGate 31\n6E 2341")
        assertEquals(listOf("6E 2341"), ticket.flights.map { it.flight.display })
    }

    @Test
    fun `airline name plus a bare number is the indian carrier`() {
        val ticket = parse("IndiGo flight 2341 on 25 Nov 2026")
        assertEquals("6E 2341", ticket.flights.single().flight.display)
    }

    @Test
    fun `media kinds decide the tracking source`() {
        assertEquals(TicketMedia.PDF, mediaKind("application/pdf", null))
        assertEquals(TicketMedia.IMAGE, mediaKind("image/jpeg", "shot.png"))
        assertEquals(TicketMedia.TEXT, mediaKind(null, "mail.txt"))
        assertEquals(TrackSource.PDF, trackSourceFor(listOf(TicketMedia.PDF, TicketMedia.PDF)))
        assertEquals(TrackSource.SCREENSHOT, trackSourceFor(listOf(TicketMedia.IMAGE)))
        assertEquals(TrackSource.SHARE, trackSourceFor(listOf(TicketMedia.PDF, TicketMedia.IMAGE)))
        assertEquals(TrackSource.SHARE, trackSourceFor(listOf(TicketMedia.TEXT)))
    }

    @Test
    fun `bitmaps are capped on the long edge`() {
        assertEquals(1600 to 900, scaledSize(3200, 1800))
        assertEquals(800 to 600, scaledSize(800, 600))
        assertEquals(1 to 1, scaledSize(0, 40))
    }

    @Test
    fun `cloud json is validated with the flight number parser`() {
        val ticket = ParseTicketResponse(
            pnr = "ab-c123",
            flights = listOf(
                ParseTicketFlightDto("6e-0234", "2026-11-25", "del", "bom"),
                ParseTicketFlightDto("not a flight", "2026-11-25", "DEL", "BOM"),
                ParseTicketFlightDto("AI 101", "tomorrow", "DEL", "BOM"),
            ),
        ).toTicket()
        assertEquals("ABC123", ticket.pnr)
        assertEquals(2, ticket.flights.size)
        assertEquals("6E 234", ticket.flights[0].flight.display)
        assertEquals(LocalDate(2026, 11, 25), ticket.flights[0].date)
        assertEquals("DEL", ticket.flights[0].fromIata)
        assertNull(ticket.flights[1].date)
    }
}
