package app.flytrack.feature.calendarimport

import app.flytrack.feature.airports.AirportList
import app.flytrack.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin port against the shared fixtures (fixtures/calendar_events.json), whose expected values were
 * produced by HeyTurbulence's JavaScript parser. The iOS port runs the same file.
 */
class CalendarFlightParserTest {

    private val parser = CalendarFlightParser(catalog, sourcePrefix = "google:primary:")

    @Test fun everyFixtureMatchesTheJavaScriptParser() {
        val cases = Json.parseToJsonElement(Fixtures.read("calendar_events.json")).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 60)
        val failures = mutableListOf<String>()
        for (case in cases) {
            val c = case.jsonObject
            val name = c.str("name")!!
            val event = googleEvent(c["event"]!!.jsonObject)
            val expected = c["expected"]
            val actual = parser.parse(event)
            try {
                if (expected == null || expected is JsonNull) {
                    assertNull("$name: not a flight", actual)
                    continue
                }
                val e = expected.jsonObject
                checkNotNull(actual) { "$name: expected a candidate" }
                assertEquals("$name sourceId", e.str("sourceId"), actual.sourceId)
                assertEquals("$name from", e.str("from"), actual.from?.iata)
                assertEquals("$name to", e.str("to"), actual.to?.iata)
                assertEquals("$name number", e.str("number"), actual.number)
                assertEquals("$name legs", e.pairs("legs"), actual.legs)
                assertEquals("$name legNumbers", e.strings("legNumbers"), actual.legDetails.map { it.number })
                assertEquals("$name legTimes", e["legTimes"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }, actual.legDetails.map { it.times })
                assertEquals("$name startInstant", e.str("startInstant"), actual.startInstant?.toString())
                assertEquals("$name endInstant", e.str("endInstant"), actual.endInstant?.toString())
                assertEquals("$name departLocal", e.str("departLocal"), actual.departLocal)
                assertEquals("$name arriveLocal", e.str("arriveLocal"), actual.arriveLocal)
                assertEquals("$name needsReview", e.bool("needsReview"), actual.needsReview)
                assertEquals("$name requiresCorrection", e.bool("requiresCorrection"), actual.requiresCorrection)
                assertEquals("$name issues", e.strings("issues"), actual.issues.map { it.name })
                assertEquals("$name sortDate", e.str("sortDate"), actual.sortDate)

                val split = e["split"]!!.jsonArray
                val legs = parser.splitLegs(actual)
                if (split.isEmpty()) {
                    assertEquals("$name: single leg passes through", listOf(actual), legs)
                } else {
                    assertEquals("$name split count", split.size, legs.size)
                    split.zip(legs).forEach { (exp, leg) ->
                        val l = exp.jsonObject
                        val tag = "$name leg ${l["leg"]!!.jsonPrimitive.int}"
                        assertEquals("$tag sourceId", l.str("sourceId"), leg.sourceId)
                        assertEquals("$tag leg", l["leg"]!!.jsonPrimitive.int, leg.leg)
                        assertEquals("$tag legCount", l["legCount"]!!.jsonPrimitive.int, leg.legCount)
                        assertEquals("$tag from", l.str("from"), leg.from?.iata)
                        assertEquals("$tag to", l.str("to"), leg.to?.iata)
                        assertEquals("$tag number", l.str("number"), leg.number)
                        assertEquals("$tag startInstant", l.str("startInstant"), leg.startInstant?.toString())
                        assertEquals("$tag endInstant", l.str("endInstant"), leg.endInstant?.toString())
                        assertEquals("$tag departLocal", l.str("departLocal"), leg.departLocal)
                        assertEquals("$tag arriveLocal", l.str("arriveLocal"), leg.arriveLocal)
                        assertEquals("$tag missing", l.str("missing"), leg.missing?.name)
                        assertEquals("$tag issues", l.strings("issues"), leg.issues.map { it.name })
                        assertTrue("$tag needs review", leg.needsReview)
                    }
                }
            } catch (err: AssertionError) {
                failures += err.message.orEmpty()
            } catch (err: IllegalStateException) {
                failures += err.message.orEmpty()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun nullAndSingleCandidatesSplitTrivially() {
        assertEquals(emptyList<CalendarCandidate>(), parser.splitLegs(null))
        val single = parser.parse(CalendarEvent(id = "x", summary = "Flight BLR → SIN"))!!
        assertEquals(listOf(single), parser.splitLegs(single))
        assertEquals(listOf(single), parser.candidates(CalendarEvent(id = "x", summary = "Flight BLR → SIN")))
    }

    @Test fun statedLegTimesAreSurfacedNotFilledIn() {
        val legs = parser.candidates(
            CalendarEvent(
                id = "e",
                summary = "Emirates to London",
                description = "EK 569 BLR → DXB 04:30 - 07:05<br>EK 1 DXB → LHR 07:45 - 11:25",
                start = EventTime(dateTime = "2026-11-26T04:30:00+05:30"),
                end = EventTime(dateTime = "2026-11-26T11:25:00+00:00"),
            ),
        )
        assertEquals(listOf("04:30", "07:05"), legs[0].statedTimes)
        assertEquals(listOf(ReviewIssue.STATED_TIMES), legs[0].issues)
        assertEquals("", legs[0].arriveLocal)
    }

    @Test fun deviceEventsUseTheDevicePrefix() {
        val p = CalendarFlightParser(catalog).parse(CalendarEvent(id = "42", summary = "Flight DEL → BOM"))!!
        assertEquals("device:42", p.sourceId)
    }

    @Test fun hostileDescriptionsStayFast() {
        for (description in listOf(
            "flight\n" + "ab-cd\n".repeat(5000),
            "flight " + "a ".repeat(16000),
            "flight " + "a.".repeat(20000),
        )) {
            val started = System.nanoTime()
            parser.parse(
                CalendarEvent(
                    id = "x",
                    summary = "Flight BLR to SIN",
                    description = description,
                    start = EventTime(dateTime = "2026-11-26T10:20:00+05:30"),
                    end = EventTime(dateTime = "2026-11-26T17:40:00+08:00"),
                ),
            )
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $ms ms", ms < 4000)
        }
    }

    @Test fun cleanDecodesHtmlAndEntities() {
        assertEquals("a\n b → c &x;", CalendarFlightParser.clean("<script>x</script>a<br> b &rarr; c &x;").replace("  ", " "))
        assertTrue(CalendarFlightParser.boundary("new delhi", "delhi"))
        assertTrue(!CalendarFlightParser.boundary("delhiwala", "delhi"))
    }

    companion object {
        /** The bundled catalog, read from the app's assets (the tests run in android/app). */
        val catalog: AirportList by lazy { AirportList.parse(File("src/main/assets/airports.json").readText()) }

        /** A fixture event (Google Calendar's JSON) as the parser's input. */
        fun googleEvent(o: JsonObject): CalendarEvent = CalendarEvent(
            id = o.str("id").orEmpty(),
            summary = o.str("summary"),
            description = o.str("description"),
            location = o.str("location"),
            start = o["start"]?.let(::eventTime),
            end = o["end"]?.let(::eventTime),
            status = o.str("status"),
            eventType = o.str("eventType"),
        )

        private fun eventTime(e: JsonElement): EventTime? {
            val o = e as? JsonObject ?: return null
            return EventTime(dateTime = o.str("dateTime"), timeZone = o.str("timeZone"), date = o.str("date"))
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeUnless { it is JsonNull }?.content

        private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
        private fun JsonObject.strings(key: String): List<String> = (this[key] as JsonArray).map { it.jsonPrimitive.content }
        private fun JsonObject.pairs(key: String): List<Pair<String, String>> =
            (this[key] as JsonArray).map { p -> p.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } }
    }
}
