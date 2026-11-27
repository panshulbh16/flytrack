package app.flytrack.feature.intel

import app.flytrack.data.remote.FlyTrackJson
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** The shapes below are those of migrations 22–25 and 27 (flight_timeline, delay_prediction, ...). */
class IntelParsersTest {

    private fun json(text: String): JsonElement = FlyTrackJson.parseToJsonElement(text)

    @Test
    fun `timeline keeps the server order, parses times and Pro detail, skips unknown keys`() {
        val steps = parseTimeline(
            json(
                """
                [{"key":"scheduled","at":"2026-11-25T12:55:00Z","state":"done","detail":{"sched_dep":"2026-11-25T12:55:00Z","sched_arr":"2026-11-25T15:10:00Z"}},
                 {"key":"gate_assigned","at":"2026-11-25T11:40:00Z","state":"done","detail":{"gate":" 42B ","terminal":"1"}},
                 {"key":"boarding","at":null,"state":"done","detail":{"gate":"42B","terminal":"1"}},
                 {"key":"pushback","at":"2026-11-25T13:12:00Z","state":"current","detail":{"delay_min":17}},
                 {"key":"holding_pattern","at":null,"state":"next","detail":null},
                 {"key":"takeoff","at":null,"state":"next","detail":{"taxi_out_min":null}},
                 {"key":"landing","at":"2026-11-25T15:20:00Z","state":"next","detail":{"delay_min":10}}]
                """,
            ),
        )

        assertEquals(
            listOf(TimelineKey.SCHEDULED, TimelineKey.GATE_ASSIGNED, TimelineKey.BOARDING, TimelineKey.PUSHBACK, TimelineKey.TAKEOFF, TimelineKey.LANDING),
            steps.map { it.key },
        )
        assertEquals(Instant.parse("2026-11-25T11:40:00Z"), steps[1].at)
        assertEquals("42B", steps[1].detail?.gate)
        assertNull(steps[2].at)
        assertEquals(TimelineState.CURRENT, steps[3].state)
        assertEquals(17, steps[3].detail?.delayMin)
        assertNull(steps[4].detail?.taxiOutMin)
        assertEquals(Instant.parse("2026-11-25T15:10:00Z"), steps[0].detail?.schedArr)
    }

    @Test
    fun `free timeline has no detail, and a non-array answer is empty`() {
        val steps = parseTimeline(json("""[{"key":"scheduled","at":"2026-11-25T12:55:00Z","state":"current","detail":null}]"""))
        assertNull(steps.single().detail)
        assertTrue(parseTimeline(JsonNull).isEmpty())
        assertTrue(parseTimeline(json("""{"key":"scheduled"}""")).isEmpty())
    }

    @Test
    fun `delay prediction parses the contract example`() {
        val p = parseDelayPrediction(
            json(
                """
                { "p_delay_15": 0.42, "p_delay_60": 0.08, "expected_delay_min": 17,
                  "factors": [{"key":"history","effect_min":9,"label":"This flight is late 45% of the time"},
                              {"key":"inbound","effect_min":20,"label":"Your plane is running 20 min late"},
                              {"key":"weather","effect_min":15,"label":"Fog at DEL"},
                              {"key":"time_of_day","effect_min":4,"label":"Evening departures run later"},
                              {"key":"airport","effect_min":6,"label":"DEL departures are averaging 22 min late"}],
                  "confidence": "medium", "samples": 12, "model": "rules-v1", "computed_at": "2026-11-25T10:00:00Z" }
                """,
            ),
        )!!

        assertEquals(0.42, p.pDelay15, 1e-9)
        assertEquals(0.08, p.pDelay60, 1e-9)
        assertEquals(17, p.expectedDelayMin)
        assertEquals(5, p.factors.size)
        assertEquals(PredictionFactor("weather", 15, "Fog at DEL"), p.factors[2])
        assertEquals(PredictionConfidence.MEDIUM, p.confidence)
        assertEquals(12, p.samples)
        assertEquals("rules-v1", p.model)
        assertEquals(Instant.parse("2026-11-25T10:00:00Z"), p.computedAt)
    }

    @Test
    fun `delay prediction outside the window is null, odd values are clamped`() {
        assertNull(parseDelayPrediction(JsonNull))
        val p = parseDelayPrediction(json("""{"p_delay_15":0.2,"p_delay_60":0.5,"expected_delay_min":-3,"confidence":"certain","factors":[{"key":"x","effect_min":1}]}"""))!!
        assertEquals("p60 never above p15", 0.2, p.pDelay60, 1e-9)
        assertEquals(0, p.expectedDelayMin)
        assertEquals(PredictionConfidence.LOW, p.confidence)
        assertTrue("a factor without a label is dropped", p.factors.isEmpty())
    }

    @Test
    fun `gate prediction reads count and samples, and derives the count from the share when missing`() {
        val g = parseGatePrediction(
            json("""{"gate":"42B","terminal":"1","share":0.78,"count":7,"samples":9,"alternatives":[{"gate":"40","share":0.11},{"gate":"","share":0.1}]}"""),
        )!!
        assertEquals(GatePrediction("42B", "1", 0.78, 7, 9, listOf(GateAlternative("40", 0.11))), g)

        val old = parseGatePrediction(json("""{"gate":"5","share":0.5,"samples":8}"""))!!
        assertEquals(4, old.count)
        assertNull(parseGatePrediction(JsonNull))
    }

    @Test
    fun `taxi stats keep both directions`() {
        val t = parseTaxiStats(
            json("""{"iata":"DEL","window_days":30,"taxi_out":{"p50":18,"p80":27,"samples":312},"taxi_in":{"p50":null,"p80":null,"samples":0}}"""),
            "DEL",
        )!!
        assertEquals(TaxiTime(18, 27, 312), t.taxiOut)
        assertEquals(false, t.taxiIn!!.isKnown)
        assertEquals("BOM", parseTaxiStats(json("""{"taxi_out":null}"""), "BOM")!!.iata)
    }

    @Test
    fun `connections parse every field and fall back to the buffer rule`() {
        val list = parseConnections(
            json(
                """
                [{"in_flight_id":"a","out_flight_id":"b","airport":"DEL","in_flight":"6E 2341","out_flight":"AI 805",
                  "in_arr":"2026-11-25T15:10:00Z","out_dep":"2026-11-25T16:35:00Z","layover_min":85,"mct_min":75,"buffer_min":10,
                  "risk":"tight","terminal_change":"T1→T3","transfer_note":"Shuttle between T1 and T3, about 20–30 min"},
                 {"in_flight_id":"c","out_flight_id":"d","airport":"BOM","layover_min":40,"mct_min":45},
                 {"in_flight_id":null,"out_flight_id":"x","layover_min":1,"mct_min":1}]
                """,
            ),
        )
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals(ConnectionRisk.TIGHT, first.risk)
        assertEquals("T1→T3", first.terminalChange)
        assertEquals(Instant.parse("2026-11-25T16:35:00Z"), first.outDeparture)
        assertEquals(-5, list[1].bufferMin)
        assertEquals(ConnectionRisk.AT_RISK, list[1].risk)
    }

    @Test
    fun `risk follows the contract table`() {
        assertEquals(ConnectionRisk.OK, ConnectionRisk.forBuffer(30))
        assertEquals(ConnectionRisk.TIGHT, ConnectionRisk.forBuffer(29))
        assertEquals(ConnectionRisk.TIGHT, ConnectionRisk.forBuffer(0))
        assertEquals(ConnectionRisk.AT_RISK, ConnectionRisk.forBuffer(-1))
        assertEquals(ConnectionRisk.MISSED, ConnectionRisk.fromWire("missed"))
        assertEquals("unknown risk is loud", ConnectionRisk.AT_RISK, ConnectionRisk.fromWire("weird"))
    }

    @Test
    fun `airport trends sort hours and days and drop bad rows`() {
        val t = parseAirportTrends(
            json(
                """
                {"iata":"DEL","days":14,
                 "by_hour":[{"hour":18,"avg_dep_delay":32,"delayed_pct":61,"samples":40},{"hour":6,"avg_dep_delay":12,"delayed_pct":28,"samples":40},{"hour":25,"avg_dep_delay":1}],
                 "by_day":[{"date":"2026-11-21","avg_dep_delay":null,"cancelled":3,"samples":3},{"date":"2026-11-20","avg_dep_delay":19,"cancelled":2,"samples":120},{"date":"bad"}]}
                """,
            ),
            "DEL",
        )!!
        assertEquals(listOf(6, 18), t.byHour.map { it.hour })
        assertEquals(listOf(LocalDate(2026, 11, 20), LocalDate(2026, 11, 21)), t.byDay.map { it.date })
        assertNull(t.byDay[1].avgDepDelay)
        assertNull(parseAirportTrends(JsonNull, "XXX"))
    }

    @Test
    fun `aircraft info reads flight_view columns and swap events oldest first`() {
        val info = parseAircraftInfo(
            json("""{"aircraft_icao_type":"A21N","aircraft_age_years":8.4}"""),
            json(
                """
                [{"payload":{"old_reg":"VT-IJK","new_reg":"VT-ISR","old_model":"Airbus A320","new_model":"Airbus A321neo"},"created_at":"2026-11-25T09:00:00+00:00"},
                 {"payload":{},"old_value":"VT-AAA","new_value":"VT-IJK","created_at":"2026-11-24T09:00:00+00:00"}]
                """,
            ),
        )
        assertEquals("A21N", info.icaoType)
        assertEquals(8.4, info.ageYears!!, 1e-9)
        assertEquals(listOf("VT-AAA", "VT-IJK"), info.swaps.map { it.oldReg })
        assertEquals("Airbus A321neo", info.swaps[1].newModel)

        val free = parseAircraftInfo(json("""{"aircraft_icao_type":"A21N","aircraft_age_years":null}"""), json("[]"))
        assertNull(free.ageYears)
        assertTrue(free.swaps.isEmpty())
        assertEquals(AircraftInfo(null, null, emptyList()), parseAircraftInfo(JsonNull, JsonNull))
    }
}
