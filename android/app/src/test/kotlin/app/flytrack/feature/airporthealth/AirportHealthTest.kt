package app.flytrack.feature.airporthealth

import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.time.Instant

class AirportHealthTest {
    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(Fixtures.read(name)).jsonObject
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `reads the Pro answer of the contract`() {
        val health = parseAirportHealth(fixture("airport_health_pro.json"), "DEL")!!
        assertEquals("DEL", health.iata)
        assertEquals(HealthLevel.SEVERE, health.level)
        assertEquals(Instant.parse("2026-11-22T09:30:00Z"), health.updatedAt)
        val detail = health.detail!!
        assertEquals(34, detail.avgDepDelayMin)
        assertEquals(41, detail.delayedPct)
        assertEquals(3, detail.cancelledToday)
        assertEquals(58, detail.sampleSize)
        assertTrue(detail.hasDelaySample)
        assertEquals(AirportWeather(300, FlightCategory.LIFR, true, 4, "FG", Instant.parse("2026-11-22T09:30:00Z")), detail.weather)
        assertEquals(HealthHeadline.Fog(300), health.typedHeadline())
    }

    @Test
    fun `free answers have no detail`() {
        val health = parseAirportHealth(fixture("airport_health_free.json"), "BOM")!!
        assertEquals(HealthLevel.BUSY, health.level)
        assertNull(health.detail)
        assertNotNull("fractional seconds parse", health.updatedAt)
        assertEquals(HealthHeadline.RunningLate(27), health.typedHeadline())
    }

    @Test
    fun `an unknown level hides the card instead of guessing`() {
        assertNull(parseAirportHealth(json("""{"iata":"DEL","level":"apocalyptic","headline":"x"}"""), "DEL"))
        assertNull(parseAirportHealth(json("""{"iata":"DEL"}"""), "DEL"))
    }

    @Test
    fun `missing iata uses the requested code and a bad timestamp is ignored`() {
        val health = parseAirportHealth(json("""{"level":"normal","headline":"Operating normally","updated_at":"soon"}"""), "BLR")!!
        assertEquals("BLR", health.iata)
        assertNull(health.updatedAt)
        assertEquals(HealthHeadline.Normal, health.typedHeadline())
    }

    @Test
    fun `every server headline has a localised form`() {
        fun h(text: String, level: HealthLevel = HealthLevel.DISRUPTED) =
            AirportHealth("DEL", level, text, null, null).typedHeadline()
        assertEquals(HealthHeadline.Fog(450), h("Fog: visibility 450 m"))
        assertEquals(HealthHeadline.Fog(null), h("Fog"))
        assertEquals(HealthHeadline.Thunderstorms, h("Thunderstorms at the airport"))
        assertEquals(HealthHeadline.LowVisibility(2500), h("Low visibility: 2500 m"))
        assertEquals(HealthHeadline.LowCloud(600), h("Low cloud: 600 ft"))
        assertEquals(HealthHeadline.PoorWeather, h("Poor flying weather"))
        assertEquals(HealthHeadline.Cancellations(12), h("12 flights cancelled today", HealthLevel.SEVERE))
        assertEquals(HealthHeadline.RunningLate(48), h("Departures running 48 min late"))
        assertEquals(HealthHeadline.DelayedShare(35), h("35% of departures delayed", HealthLevel.BUSY))
        assertEquals(HealthHeadline.Normal, h("Operating normally", HealthLevel.NORMAL))
        assertEquals(HealthHeadline.Other("Runway closed"), h("Runway closed"))
    }

    @Test
    fun `an unknown sentence still localises from the Pro weather`() {
        val weather = AirportWeather(700, FlightCategory.LIFR, fog = true, windKts = 3, wx = "FG", observedAt = null)
        val health = AirportHealth("DEL", HealthLevel.DISRUPTED, "Something new", null, HealthDetail(null, null, 0, 0, weather))
        assertEquals(HealthHeadline.Fog(700), health.typedHeadline())
    }

    /** The headlines are written by get_airport_health in migration 13: every format there must parse. */
    @Test
    fun `headline formats in the migration are all recognised`() {
        val migrations = File("../../supabase/migrations")
        assumeTrue("backend sources not present next to the android project", migrations.isDirectory)
        val sql = migrations.listFiles()!!.filter { it.name.endsWith("_airport_weather.sql") }.joinToString("\n") { it.readText() }
        assumeTrue(sql.isNotEmpty())
        val body = sql.substringAfter("function public.get_airport_health").substringBefore("end \$\$;")
        val formats = Regex("""format\('([^']+)'""").findAll(body).map { it.groupValues[1] }.toList()
        val literals = Regex("""then '([A-Z][^']+)'|else '([A-Z][^']+)'|coalesce\(v_wx_text, '([^']+)'\)|:= '([A-Z][^']+)';""")
            .findAll(body).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toList()
        assertTrue("found the server's sentences", formats.size >= 5 && literals.size >= 3)
        for (format in formats) {
            val sample = format.replace("%%", "%").replace("%s", "42")
            val typed = AirportHealth("DEL", HealthLevel.DISRUPTED, sample, null, null).typedHeadline()
            assertFalse("not localised: $sample", typed is HealthHeadline.Other)
        }
        for (literal in literals) {
            val typed = AirportHealth("DEL", HealthLevel.DISRUPTED, literal, null, null).typedHeadline()
            assertFalse("not localised: $literal", typed is HealthHeadline.Other)
        }
    }

    @Test
    fun `levels map to status tones mildest to worst`() {
        assertEquals(FlightStatusTone.ON_TIME, HealthLevel.NORMAL.tone)
        assertEquals(FlightStatusTone.DELAYED, HealthLevel.BUSY.tone)
        assertEquals(FlightStatusTone.DELAYED, HealthLevel.DISRUPTED.tone)
        assertEquals(FlightStatusTone.CANCELLED, HealthLevel.SEVERE.tone)
        assertEquals(listOf("normal", "busy", "disrupted", "severe"), HealthLevel.entries.map { it.wire })
    }

    @Test
    fun `which airports matter in each phase`() {
        val both = listOf(HealthSide.DEPARTURE, HealthSide.ARRIVAL)
        assertEquals(both, healthSides(FlightPhase.UPCOMING))
        assertEquals(both, healthSides(FlightPhase.CHECK_IN))
        assertEquals(both, healthSides(FlightPhase.BOARDING))
        assertEquals(listOf(HealthSide.ARRIVAL), healthSides(FlightPhase.IN_AIR))
        assertEquals(listOf(HealthSide.ARRIVAL), healthSides(FlightPhase.DIVERTED))
        assertTrue(healthSides(FlightPhase.LANDED).isEmpty())
        assertTrue(healthSides(FlightPhase.CANCELLED).isEmpty())
    }

    @Test
    fun `requests skip blank codes and ask for one airport once`() {
        val sides = listOf(HealthSide.DEPARTURE, HealthSide.ARRIVAL)
        assertEquals(listOf(HealthSide.DEPARTURE to "DEL", HealthSide.ARRIVAL to "BOM"), healthRequests("del", "BOM", sides))
        assertEquals(listOf(HealthSide.DEPARTURE to "DEL"), healthRequests("DEL", "DEL", sides))
        assertEquals(listOf(HealthSide.ARRIVAL to "BOM"), healthRequests(" ", "BOM", sides))
    }

    @Test
    fun `METAR present weather in words`() {
        assertEquals(PresentWeather.FOG, presentWeather("FG"))
        assertEquals(PresentWeather.FOG, presentWeather("BCFG BR"))
        assertEquals(PresentWeather.THUNDERSTORM, presentWeather("+TSRA"))
        assertEquals(PresentWeather.MIST, presentWeather("BR"))
        assertEquals(PresentWeather.HAZE, presentWeather("HZ"))
        assertEquals(PresentWeather.SMOKE, presentWeather("FU HZ"))
        assertEquals(PresentWeather.RAIN, presentWeather("-RA"))
        assertEquals(PresentWeather.DUST, presentWeather("DU"))
        assertNull(presentWeather(""))
        assertNull(presentWeather(null))
        assertNull(presentWeather("SN"))
    }
}
