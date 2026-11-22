package app.flytrack.core.flightnumber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightNumberParserTest {
    private fun parsed(input: String) = parseFlightNumber(input)?.let { it.carrier to it.number }

    @Test
    fun `mirrors the server's own test cases`() {
        // supabase/functions/tests/engine.test.ts: "search: input parsing, cache, rate limit, not found"
        assertEquals("6E" to "234", parsed("6e-0234"))
        assertEquals("AI" to "101A", parsed("AI 101A"))
        assertNull(parsed("12 345"))
        // inputs the same test file sends through searchFlight
        assertEquals("QP" to "1372", parsed("QP 1372"))
        assertEquals("QP" to "1372", parsed("QP1372"))
        assertEquals("6E" to "2340", parsed("6E2340"))
        assertEquals("SG" to "8", parsed("SG 8"))
        assertEquals("6E" to "1", parsed("6E 1"))
    }

    @Test
    fun `accepts spaces, dash and any letter case`() {
        assertEquals("6E" to "2341", parsed("6E 2341"))
        assertEquals("6E" to "2341", parsed("6e2341"))
        assertEquals("AI" to "101", parsed("AI-101"))
        assertEquals("AI" to "101", parsed("  ai - 101  "))
        assertEquals("AI" to "101", parsed("AI\t101\n"))
        assertEquals("IX" to "1234", parsed("ix1234"))
    }

    @Test
    fun `strips leading zeros and upper-cases the suffix letter`() {
        assertEquals("6E" to "234", parsed("6E 0234"))
        assertEquals("AI" to "7", parsed("AI 007"))
        assertEquals("AI" to "101A", parsed("ai101a"))
        assertEquals("6E" to "0", parsed("6E 0000")) // same as the server's Number("0000")
    }

    @Test
    fun `carrier may contain one digit but is never two digits`() {
        assertEquals("6E" to "2341", parsed("6E2341"))
        assertEquals("G8" to "123", parsed("G8 123"))
        assertEquals("9W" to "1", parsed("9w1"))
        assertNull(parsed("12 345"))
        assertNull(parsed("99-1"))
    }

    @Test
    fun `two-character carrier is taken before the digits like the server's regex does`() {
        // "6E2341": carrier 6E. "I51234": carrier I5, number 1234. "512345": carrier 51 -> rejected.
        assertEquals("I5" to "1234", parsed("I51234"))
        assertNull(parsed("512345"))
    }

    @Test
    fun `rejects what the server rejects`() {
        for (bad in listOf("", "   ", "6E", "2341", "6E 12345", "INDIGO 2341", "6E 23 41", "6E-", "6E 2341AB", "6E--2341", "6E_2341", "6E 2341 extra")) {
            assertNull("should reject '$bad'", parsed(bad))
        }
    }

    @Test
    fun `treats pasted no-break spaces as spaces like JavaScript does`() {
        assertEquals("6E" to "2341", parsed("6E 2341"))
        assertEquals("6E" to "2341", parsed("﻿6E 2341 "))
    }

    @Test
    fun `only ASCII letters and digits are accepted`() {
        assertNull(parsed("６Ｅ 2341")) // full-width
        assertNull(parsed("6E ２３４１"))
        assertNull(parsed("ÄI 101"))
    }

    @Test
    fun `display form is what the backend is sent`() {
        assertEquals("6E 2341", parseFlightNumber("6e-2341")!!.display)
        assertEquals("AI 101A", parseFlightNumber("ai101a")!!.display)
    }
}
