package app.flytrack.feature.importticket

import app.flytrack.core.flightnumber.FlightNumber
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardingPassParserTest {
    private fun legs(text: String, today: LocalDate = LocalDate(2026, 11, 22)) = parseBoardingPass(text, today)

    @Test
    fun `resolution 792 example`() {
        assertEquals(
            listOf(BoardingPassLeg("ABC123", "YUL", "FRA", FlightNumber("AC", "834"), LocalDate(2026, 11, 22), "1A")),
            legs("M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"),
        )
    }

    @Test
    fun `indigo pass with conditional and security data`() {
        val leg = legs(
            "M1SHARMA/RAHUL        EXY7ZQP DELBOM6E 2341 330Y012A0045 149>5180  W6328B6E              2A00000000000000 6E                        N^164GIWVC5EH7JNT684FVNJ91W2QA4DVN5J8K4F0L0GEQ3DF5TGBN8709HKT5D3DW3GBHFCVHMY7J5T6HFR41W2QA4DVN5J8K4F0L0GE",
        ).single()
        assertEquals(BoardingPassLeg("XY7ZQP", "DEL", "BOM", FlightNumber("6E", "2341"), LocalDate(2026, 11, 26), "12A"), leg)
    }

    @Test
    fun `two legs over new year`() {
        val found = legs(
            "M2IYER/ANANYA MS      EQWE9RT DELDXBEK 0511 365Y034K0101 148>5180  W6363BEK              2A1762345678901 EK                        NQWE9RT DXBLHREK 0001 001Y061A0202 12B2A1762345678901 EK                        N",
            today = LocalDate(2026, 12, 30),
        )
        assertEquals(listOf("EK 511", "EK 1"), found.map { it.flight.display })
        assertEquals(listOf(LocalDate(2026, 12, 31), LocalDate(2027, 1, 1)), found.map { it.date })
        assertEquals(listOf("DEL-DXB", "DXB-LHR"), found.map { "${it.fromIata}-${it.toIata}" })
        assertEquals(listOf("34K", "61A"), found.map { it.seat })
    }

    @Test
    fun `suffix letter and no seat`() {
        val leg = legs("M1KUMAR/AARAV MSTR    EZX12AB BLRGOIQP 1372A002YINF 0007 100", today = LocalDate(2027, 1, 1)).single()
        assertEquals("QP 1372A", leg.flight.display)
        assertEquals(LocalDate(2027, 1, 2), leg.date)
        assertNull(leg.seat)
    }

    @Test
    fun `julian day picks the year nearest today`() {
        assertEquals(LocalDate(2026, 12, 30), julianDate(364, LocalDate(2027, 1, 2)))
        assertEquals(LocalDate(2027, 1, 2), julianDate(2, LocalDate(2026, 12, 30)))
        assertEquals(LocalDate(2028, 12, 31), julianDate(366, LocalDate(2028, 6, 1)))
        assertNull(julianDate(366, LocalDate(2026, 6, 1)))
        assertNull(julianDate(0, LocalDate(2026, 6, 1)))
    }

    @Test
    fun `other barcodes are not boarding passes`() {
        assertEquals(emptyList<BoardingPassLeg>(), legs("https://example.com/checkin"))
        assertEquals(emptyList<BoardingPassLeg>(), legs("M1TOO SHORT"))
        assertEquals(emptyList<BoardingPassLeg>(), legs(""))
    }
}
