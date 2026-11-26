package app.flytrack.feature.passport

import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.time.Instant

class PassportTest {
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(Fixtures.read(name)).jsonObject
    private val pro = parsePassport(fixture("passport_pro_all.json"), fallbackYear = 2026)
    private val today = LocalDate.of(2026, 11, 22)

    @Test
    fun `reads every field of the contract example`() {
        assertEquals(PassportScope.ALL, pro.scope)
        assertEquals(2026, pro.year)
        assertFalse(pro.proLimited)
        assertEquals(14, pro.flights)
        assertEquals(18234, pro.distanceKm)
        assertEquals(1810, pro.airborneMinutes)
        assertEquals(listOf("IN", "AE", "US").sorted(), pro.countries)
        assertEquals(CountedAirline("6E", "IndiGo", 8), pro.airlines.first())
        assertEquals(CountedAirline("AI", null, 4), pro.airlines[1])
        assertEquals(CountedAircraft("A320neo", 7), pro.aircraft.first())
        assertEquals(LongestFlight("c0ffee00-0000-4000-8000-000000000001", "AI", "101", "DEL", "JFK", 11752), pro.longest)
        assertEquals(TopRoute("DEL", "BOM", 5), pro.topRoute)
        assertEquals(DelayStats(412, 64, WorstDelay("SG", "8", 185)), pro.delay)
        assertEquals(1730, pro.co2Kg)
        assertEquals(CO2_METHOD_V1, pro.co2Method)
        assertEquals("2025-12-03", pro.firstFlightDate)
    }

    @Test
    fun `entries without a code are dropped instead of failing the screen`() {
        assertEquals(listOf("DEL", "BOM", "JFK"), pro.airports.map { it.iata })
    }

    @Test
    fun `an empty free answer has no highlights and asks for Pro`() {
        val empty = parsePassport(fixture("passport_free_empty.json"), fallbackYear = 2026)
        assertTrue(empty.isEmpty)
        assertTrue(empty.proLimited)
        assertEquals(PassportScope.LAST_12_MONTHS, empty.scope)
        assertNull(empty.longest)
        assertNull(empty.topRoute)
        assertNull(empty.delay.onTimePct)
        assertNull(empty.toYearInReviewCard(today))
        assertNull(empty.earthLaps)
    }

    @Test
    fun `missing fields fall back to safe defaults`() {
        val minimal = parsePassport(Json.parseToJsonElement("""{"flights": 2}""").jsonObject, fallbackYear = 2025)
        assertEquals(2, minimal.flights)
        assertEquals(2025, minimal.year)
        assertEquals(PassportScope.LAST_12_MONTHS, minimal.scope)
        assertEquals(CO2_METHOD_V1, minimal.co2Method)
        assertTrue(minimal.airports.isEmpty())
    }

    @Test
    fun `overview bars are the twelve months ending this month`() {
        val bars = pro.monthBars(today)
        assertEquals(12, bars.size)
        assertEquals(YearMonth.of(2025, 12), bars.first().month)
        assertEquals(YearMonth.of(2026, 11), bars.last().month)
        assertEquals(1, bars.first().flights)
        assertEquals(7, bars.last().flights)
        assertEquals(1f, bars.last().fraction)
        assertEquals(2, bars.first { it.month == YearMonth.of(2026, 1) }.flights)
        assertEquals(4, bars.first { it.month == YearMonth.of(2026, 3) }.flights)
        assertEquals(0f, bars.first { it.month == YearMonth.of(2026, 2) }.fraction)
    }

    @Test
    fun `year bars run January to December of that year`() {
        val year = pro.copy(scope = PassportScope.YEAR, year = 2026)
        val bars = year.monthBars(today)
        assertEquals((1..12).map { YearMonth.of(2026, it) }, bars.map { it.month })
        assertEquals(7, bars.first { it.month == YearMonth.of(2026, 11) }.flights)
        assertEquals(0, bars.last().flights)
        assertEquals("December 2025 is not part of 2026", 13, bars.sumOf { it.flights })
    }

    @Test
    fun `no flights at all gives flat bars`() {
        val bars = pro.copy(byMonth = emptyList()).monthBars(today)
        assertTrue(bars.all { it.fraction == 0f && it.flights == 0 })
    }

    @Test
    fun `little numbers`() {
        assertEquals(30, pro.hoursInAir) // 1810 min = 30.2 h
        assertEquals(0.5, pro.earthLaps!!, 0.0001) // 18234 / 40075 = 0.455
        assertEquals(
            listOf(14, 18234, 30, 3, 3, 3),
            pro.statTiles().map { it.value },
        )
        assertEquals(StatKind.entries.filterNot { it == StatKind.BLOCK_HOURS }, pro.statTiles().map { it.kind })
    }

    @Test
    fun `crew mode counts block hours instead of hours in the air`() {
        val crew = pro.copy(blockMinutes = 2_130).statTiles(crewMode = true) // 35.5 h
        assertEquals(StatKind.BLOCK_HOURS, crew[2].kind)
        assertEquals(36, crew[2].value)
        assertEquals(StatKind.HOURS, pro.statTiles(crewMode = false)[2].kind)
    }

    @Test
    fun `year in review card is built only for a non-empty year`() {
        assertNull(pro.toYearInReviewCard(today))
        val card = pro.copy(scope = PassportScope.YEAR).toYearInReviewCard(today)
        assertNotNull(card)
        card!!
        assertEquals(2026, card.year)
        assertEquals(14, card.flights)
        assertEquals(30, card.hours)
        assertEquals(3, card.airports)
        assertEquals("IndiGo", card.topAirline)
        assertEquals(TopRoute("DEL", "BOM", 5), card.topRoute)
        assertEquals(12, card.bars.size)
    }

    @Test
    fun `a route flown once is not a top route on the card, and a nameless airline shows its code`() {
        val card = pro.copy(
            scope = PassportScope.YEAR,
            topRoute = TopRoute("DEL", "BOM", 1),
            airlines = listOf(CountedAirline("QP", null, 1)),
        ).toYearInReviewCard(today)!!
        assertNull(card.topRoute)
        assertEquals("QP", card.topAirline)
    }

    @Test
    fun `tabs map to the p_year argument and dates use Indian time`() {
        assertNull(PassportTab.OVERVIEW.yearArgument(2026))
        assertEquals(2026, PassportTab.YEAR.yearArgument(2026))
        // 31 Dec 19:00 UTC is already 1 Jan in India: Year in Review flips with the server.
        assertEquals(LocalDate.of(2027, 1, 1), passportToday(Instant.parse("2026-12-31T19:00:00Z")))
    }

    @Test
    fun `month helpers reject malformed months`() {
        assertNull(MonthCount("2026", 1).monthNumber)
        assertNull(MonthCount("2026-13", 1).monthNumber)
        assertEquals(3, MonthCount("2026-03", 1).monthNumber)
    }
}
