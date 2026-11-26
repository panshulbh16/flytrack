package app.flytrack.feature.intel

import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class IntelPresentationTest {

    // TestData.flight(): DEL 12:55Z (18:25 IST) → BOM 15:10Z (20:40 IST) on 25 Nov.
    private val flight = TestData.flight()
    private val now = Instant.parse("2026-11-25T08:00:00Z")

    // ---- timeline -------------------------------------------------------------------------------

    @Test
    fun `timeline times are in the airport's zone, arrival steps in the arrival zone`() {
        val ui = listOf(
            TimelineStep(TimelineKey.SCHEDULED, flight.schedDep, TimelineState.DONE, TimelineDetail(schedDep = flight.schedDep, schedArr = flight.schedArr)),
            TimelineStep(TimelineKey.PUSHBACK, Instant.parse("2026-11-25T13:12:00Z"), TimelineState.CURRENT, TimelineDetail(delayMin = 17)),
            TimelineStep(TimelineKey.LANDING, Instant.parse("2026-11-25T18:40:00Z"), TimelineState.NEXT, null),
        ).toTimelineUi(depTz = "Asia/Kolkata", arrTz = "Asia/Dubai", flightDate = flight.flightDate)!!

        assertEquals("18:25", ui.rows[0].time)
        assertEquals(listOf(TimelineFact.Timetable("18:25", "19:10")), ui.rows[0].facts)
        assertEquals("18:42", ui.rows[1].time)
        assertEquals(listOf(TimelineFact.Delay(17)), ui.rows[1].facts)
        assertEquals("arrival zone", "22:40", ui.rows[2].time)
        assertTrue(ui.rows[2].facts.isEmpty())
        assertTrue(ui.hasProDetail)
    }

    @Test
    fun `a milestone after midnight gets +1, an implied one has no time`() {
        val ui = listOf(
            TimelineStep(TimelineKey.BOARDING, null, TimelineState.DONE, null),
            TimelineStep(TimelineKey.LANDING, Instant.parse("2026-11-25T19:00:00Z"), TimelineState.NEXT, null),
        ).toTimelineUi("Asia/Kolkata", "Asia/Kolkata", flight.flightDate)!!

        assertTrue(ui.rows[0].implied)
        assertNull(ui.rows[0].time)
        assertEquals("00:30", ui.rows[1].time)
        assertEquals(1, ui.rows[1].dayOffset)
        assertFalse(ui.rows[1].implied)
        assertFalse("free answer", ui.hasProDetail)
    }

    @Test
    fun `check-in opening the day before gets -1`() {
        val ui = listOf(
            TimelineStep(TimelineKey.CHECKIN_OPEN, Instant.parse("2026-11-23T13:00:00Z"), TimelineState.DONE, null),
        ).toTimelineUi("Asia/Kolkata", "Asia/Kolkata", flight.flightDate)!!
        assertEquals(-2, ui.rows[0].dayOffset)
    }

    @Test
    fun `facts per key and block time once off-block and on-block are known`() {
        val off = Instant.parse("2026-11-25T13:00:00Z")
        val on = Instant.parse("2026-11-25T15:14:00Z")
        val ui = listOf(
            TimelineStep(TimelineKey.CHECKIN_OPEN, null, TimelineState.DONE, TimelineDetail(closesAt = Instant.parse("2026-11-25T12:00:00Z"))),
            TimelineStep(TimelineKey.GATE_CLOSED, null, TimelineState.DONE, TimelineDetail(gate = "42B")),
            TimelineStep(TimelineKey.GATE_ASSIGNED, null, TimelineState.DONE, TimelineDetail(terminal = "1")),
            TimelineStep(TimelineKey.PUSHBACK, off, TimelineState.DONE, TimelineDetail(delayMin = -3)),
            TimelineStep(TimelineKey.TAKEOFF, off + 14.minutes, TimelineState.DONE, TimelineDetail(taxiOutMin = 14)),
            TimelineStep(TimelineKey.DIVERTED, null, TimelineState.DONE, TimelineDetail(fromArr = "BOM", toArr = "AMD")),
            TimelineStep(TimelineKey.AT_GATE, on, TimelineState.DONE, TimelineDetail(gate = "5", taxiInMin = 6)),
            TimelineStep(TimelineKey.BELT, on, TimelineState.CURRENT, TimelineDetail(belt = "7")),
        ).toTimelineUi("Asia/Kolkata", "Asia/Kolkata", flight.flightDate)!!

        assertEquals(listOf(TimelineFact.CheckinCloses("17:30")), ui.rows[0].facts)
        assertEquals(listOf(TimelineFact.Gate("42B", null)), ui.rows[1].facts)
        assertEquals(listOf(TimelineFact.Terminal("1")), ui.rows[2].facts)
        assertEquals(listOf(TimelineFact.Delay(-3)), ui.rows[3].facts)
        assertEquals(listOf(TimelineFact.TaxiOut(14)), ui.rows[4].facts)
        assertEquals(listOf(TimelineFact.Diverted("BOM", "AMD")), ui.rows[5].facts)
        assertEquals(listOf(TimelineFact.Gate("5", null), TimelineFact.TaxiIn(6)), ui.rows[6].facts)
        assertEquals(listOf(TimelineFact.Belt("7")), ui.rows[7].facts)
        assertEquals(134, ui.blockMinutes)
    }

    @Test
    fun `no steps, no timeline`() {
        assertNull(emptyList<TimelineStep>().toTimelineUi(null, null, LocalDate(2026, 11, 25)))
    }

    // ---- prediction -----------------------------------------------------------------------------

    private val prediction = DelayPrediction(
        pDelay15 = 0.424, pDelay60 = 0.08, expectedDelayMin = 17,
        factors = listOf(PredictionFactor("history", 9, "late 45%"), PredictionFactor("inbound", 20, "plane late"), PredictionFactor("time_of_day", -4, "morning")),
        confidence = PredictionConfidence.MEDIUM, samples = 12, model = "rules-v1", computedAt = null,
    )

    @Test
    fun `prediction card rounds chances, orders factors by effect and picks the tone`() {
        val ui = prediction.toUi()
        assertEquals(42, ui.delay15Pct)
        assertEquals(8, ui.delay60Pct)
        assertEquals(listOf("inbound", "history", "time_of_day"), ui.factors.map { it.key })
        assertEquals(FlightStatusTone.DELAYED, ui.tone)
        assertFalse(ui.likelyOnTime)

        val calm = prediction.copy(expectedDelayMin = 4).toUi()
        assertTrue(calm.likelyOnTime)
        assertEquals(FlightStatusTone.ON_TIME, calm.tone)
    }

    @Test
    fun `the prediction window is 72 h before departure until departure`() {
        val dep = flight.schedDep!!
        assertTrue(flight.isPredictionWindowOpen(dep - 72.hours))
        assertTrue(flight.isPredictionWindowOpen(dep - 1.minutes))
        assertFalse(flight.isPredictionWindowOpen(dep - 73.hours))
        assertFalse(flight.isPredictionWindowOpen(dep))
        assertFalse(flight.copy(status = FlightStatus.CANCELLED).isPredictionWindowOpen(now))
        assertFalse(flight.copy(actDep = now).isPredictionWindowOpen(now))
    }

    @Test
    fun `prediction block shows Pro answers, the usual gate only without a real gate`() {
        val answers = PredictionAnswers(
            delay = IntelResult.Ok(prediction),
            gate = IntelResult.Ok(GatePrediction("42B", "1", 0.78, 7, 9, emptyList())),
            taxiOut = IntelResult.Ok(TaxiStats("DEL", TaxiTime(18, 27, 312), null)),
            taxiIn = IntelResult.Ok(TaxiStats("BOM", null, TaxiTime(6, 6, 40))),
        )
        val ready = predictionBlock(flight, answers, now) as PredictionBlockUiModel.Ready
        assertEquals(17, ready.delay?.expectedDelayMin)
        assertEquals(GatePredictionUiModel("42B", "1", 7, 9), ready.gate)
        assertEquals(TaxiLineUiModel("DEL", true, 18, 27), ready.taxiOut)
        assertEquals("p80 equal to p50 says nothing more", TaxiLineUiModel("BOM", false, 6, null), ready.taxiIn)

        val withGate = predictionBlock(flight.copy(depGate = "12"), answers, now) as PredictionBlockUiModel.Ready
        assertNull(withGate.gate)
    }

    @Test
    fun `free users in the window get the teaser, outside it nothing`() {
        val locked = PredictionAnswers(delay = IntelResult.Locked, gate = IntelResult.Locked, taxiOut = IntelResult.Locked, taxiIn = IntelResult.Locked)
        assertEquals(PredictionBlockUiModel.Locked, predictionBlock(flight, locked, now))
        assertEquals(PredictionBlockUiModel.Hidden, predictionBlock(flight, locked.copy(delay = null), flight.schedDep!! - 100.hours))
        assertEquals(PredictionBlockUiModel.Hidden, predictionBlock(flight, PredictionAnswers(), now))
        assertEquals(
            "offline: nothing rather than a teaser",
            PredictionBlockUiModel.Hidden,
            predictionBlock(flight, PredictionAnswers(delay = IntelResult.Offline), now),
        )
    }

    @Test
    fun `taxi lines stop once they no longer matter`() {
        val departed = flight.copy(status = FlightStatus.EN_ROUTE, actDep = now)
        assertFalse(departed.wantsTaxiOut())
        assertTrue(departed.wantsTaxiIn())
        val landed = departed.copy(status = FlightStatus.LANDED, actArr = now + 2.hours)
        assertFalse(landed.wantsTaxiIn())
        assertFalse(departed.wantsGatePrediction())
    }

    // ---- aircraft -------------------------------------------------------------------------------

    @Test
    fun `aircraft card adds the ICAO type only when it says something new`() {
        val swap = AircraftSwap("VT-IJK", "VT-ISR", "Airbus A320", "Airbus A321neo", Instant.parse("2026-11-25T09:00:00Z"))
        val card = aircraftCard("Asia/Kolkata", " Airbus A321neo ", "VT-ISR", AircraftInfo("A21N", 8.4, listOf(swap)))
        assertEquals("Airbus A321neo", card.model)
        assertEquals("A21N", card.icaoType)
        assertEquals(8.4, card.ageYears!!, 1e-9)
        assertEquals(SwapUiModel("VT-IJK", "VT-ISR", "Airbus A320", "Airbus A321neo", "14:30"), card.swaps.single())

        assertNull(aircraftCard(null, "Airbus A320", null, AircraftInfo("A320", null, emptyList())).icaoType)
        assertEquals(AircraftCardUiModel(null, null, null, null, emptyList()), aircraftCard(null, " ", "", null))
    }

    // ---- connections ----------------------------------------------------------------------------

    private val connection = Connection(
        inFlightId = TestData.FLIGHT_ID, outFlightId = TestData.OTHER_FLIGHT_ID, airport = "BOM",
        inFlight = "6E 2341", outFlight = "AI 805",
        inArrival = Instant.parse("2026-11-25T15:10:00Z"), outDeparture = Instant.parse("2026-11-25T16:35:00Z"),
        layoverMin = 85, mctMin = 75, bufferMin = 10, risk = ConnectionRisk.TIGHT, terminalChange = "T1→T2", transferNote = null,
    )

    @Test
    fun `risk colours follow the design brief`() {
        assertEquals(FlightStatusTone.ON_TIME, ConnectionRisk.OK.tone)
        assertEquals(FlightStatusTone.DELAYED, ConnectionRisk.TIGHT.tone)
        assertEquals(FlightStatusTone.CANCELLED, ConnectionRisk.AT_RISK.tone)
        assertEquals(FlightStatusTone.CANCELLED, ConnectionRisk.MISSED.tone)
    }

    @Test
    fun `connection rows show times in the connecting airport's zone`() {
        val ui = connection.toUi(mapOf("BOM" to "Asia/Kolkata")::get)
        assertEquals("20:40", ui.arrival)
        assertEquals("22:05", ui.departure)
        assertEquals("${TestData.FLIGHT_ID}:${TestData.OTHER_FLIGHT_ID}", ui.key)
        assertEquals(listOf(ui), listOf(ui).forFlight(TestData.OTHER_FLIGHT_ID))
        assertTrue(listOf(ui).forFlight("other").isEmpty())
    }

    private val onward = TestData.flight(
        id = TestData.OTHER_FLIGHT_ID, carrierIata = "AI", flightNumber = "805",
        depAirport = TestData.BOM, arrAirport = TestData.DEL,
        schedDep = Instant.parse("2026-11-25T16:35:00Z"), schedArr = Instant.parse("2026-11-25T18:45:00Z"),
    )

    @Test
    fun `connections are detected like connections_for_user`() {
        assertEquals(listOf(flight to onward), detectConnections(listOf(flight, onward)))
        // too late: more than 24 h after the arrival
        val nextDay = onward.copy(schedDep = flight.schedArr!! + 25.hours, schedArr = flight.schedArr!! + 27.hours)
        assertTrue(detectConnections(listOf(flight, nextDay)).isEmpty())
        // before the arrival
        assertTrue(detectConnections(listOf(flight, onward.copy(schedDep = flight.schedArr!! - 1.minutes))).isEmpty())
        // a cancelled leg breaks the trip
        assertTrue(detectConnections(listOf(flight, onward.copy(status = FlightStatus.CANCELLED))).isEmpty())
    }

    @Test
    fun `the free teaser ignores a trip whose onward leg left more than 12 h ago`() {
        val flights = listOf(flight, onward)
        val outDep = onward.schedDep!!
        assertEquals(ConnectionsUiState.Teaser(1), connectionsState(IntelResult.Locked, flights, outDep + 11.hours))
        assertEquals(ConnectionsUiState.Hidden, connectionsState(IntelResult.Locked, flights, outDep + 13.hours))
        assertTrue(detectConnections(flights, outDep + 13.hours).isEmpty())
    }

    @Test
    fun `connections state - Pro rows, free teaser only with a trip, errors hidden`() {
        val flights = listOf(flight, onward)
        val ready = connectionsState(IntelResult.Ok(listOf(connection)), flights) as ConnectionsUiState.Ready
        assertEquals("zone from the cached flights", "22:05", ready.connections.single().departure)
        assertEquals(ConnectionsUiState.Hidden, connectionsState(IntelResult.Ok(emptyList()), flights))
        assertEquals(ConnectionsUiState.Teaser(1), connectionsState(IntelResult.Locked, flights))
        assertEquals(ConnectionsUiState.Hidden, connectionsState(IntelResult.Locked, listOf(flight)))
        assertEquals(ConnectionsUiState.Hidden, connectionsState(IntelResult.Offline, flights))
        assertEquals(ConnectionsUiState.Hidden, connectionsState(null, flights))
    }

    // ---- trends ---------------------------------------------------------------------------------

    @Test
    fun `trends fill 24 hours and name the worst and best reliable hours`() {
        val trends = AirportTrends(
            "DEL", 14,
            byHour = listOf(HourTrend(6, 12, 28, 40), HourTrend(18, 32, 61, 40), HourTrend(3, 90, 100, 1), HourTrend(9, -2, 0, 10)),
            byDay = listOf(DayTrend(LocalDate(2026, 11, 20), 19, 2, 120), DayTrend(LocalDate(2026, 11, 21), null, 3, 3)),
        ).toUi()!!

        assertEquals(24, trends.hours.size)
        assertNull(trends.hours[0])
        assertEquals(12, trends.hours[6]?.avgDepDelay)
        assertEquals("a 1-leg hour is not the worst", 18, trends.worstHour?.hour)
        assertEquals(9, trends.bestHour?.hour)
        assertEquals(90, trends.maxHourDelay)
        assertEquals(19, trends.maxDayDelay)
        assertEquals(5, trends.totalCancelled)
        assertNull(AirportTrends("DEL", 14, emptyList(), listOf(DayTrend(LocalDate(2026, 11, 20), null, 0, 0))).toUi())
    }

    @Test
    fun `bar fractions are clamped`() {
        assertEquals(0.5f, barFraction(10, 20))
        assertEquals(0f, barFraction(-5, 20))
        assertEquals(0f, barFraction(null, 20))
        assertEquals(0f, barFraction(10, 0))
        assertEquals(1f, barFraction(40, 20))
    }

    @Test
    fun `prediction labels are asked for in Hindi on a Hindi phone`() {
        assertEquals("hi", predictionLanguage("hi"))
        assertEquals("hi", predictionLanguage("hi-IN"))
        assertEquals("en", predictionLanguage("en"))
        assertEquals("en", predictionLanguage("ta"))
        assertEquals("en", predictionLanguage(null))
    }
}
