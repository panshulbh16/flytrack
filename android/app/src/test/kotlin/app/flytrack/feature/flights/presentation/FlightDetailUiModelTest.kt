package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundAircraft
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.TimeKind
import app.flytrack.testing.TestData
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FlightDetailUiModelTest {
    private val now = TestData.NOW
    private val schedDep = Instant.parse("2026-11-25T12:55:00Z")
    private val schedArr = Instant.parse("2026-11-25T15:10:00Z")

    private val prediction = InboundPrediction(
        computedAt = now,
        predictedDep = schedDep + 45.minutes,
        lateMin = 20,
        reason = "Inbound aircraft is running 45 min late.",
        inbound = InboundAircraft(
            flight = "6E 2032", fromIata = "BLR", status = FlightStatus.EN_ROUTE,
            schedArr = schedDep - 50.minutes, estArr = schedDep - 5.minutes, actArr = null, live = null,
        ),
    )

    // ---- endpoints ----------------------------------------------------------------------------

    @Test
    fun `each endpoint lists its airport and all three times in its own zone`() {
        val detail = TestData.flight(
            depAirport = TestData.DXB, arrAirport = TestData.DEL,
            status = FlightStatus.ARRIVED,
            schedDep = Instant.parse("2026-11-28T18:35:00Z"), estDep = Instant.parse("2026-11-28T18:50:00Z"),
            actDep = Instant.parse("2026-11-28T18:58:00Z"),
            schedArr = Instant.parse("2026-11-28T21:50:00Z"), estArr = Instant.parse("2026-11-28T22:05:00Z"),
            actArr = Instant.parse("2026-11-28T22:01:00Z"),
        ).toDetailUiModel(tracking = null, isPro = false, now = now)

        with(detail.departure) {
            assertEquals("DXB", code)
            assertEquals("Dubai", city)
            assertEquals("Dubai International Airport", airportName)
            assertEquals("22:35", scheduled)
            assertEquals("22:50", estimated)
            assertEquals("22:58", actual)
            assertEquals(FlightTimeUiModel("22:58", TimeKind.ACTUAL, "22:35", LocalDate.parse("2026-11-28")), time)
        }
        with(detail.arrival) {
            assertEquals("DEL", code)
            assertEquals("New Delhi", city)
            assertEquals("03:20", scheduled)
            assertEquals("03:35", estimated)
            assertEquals("03:31", actual)
            assertEquals(FlightTimeUiModel("03:31", TimeKind.ACTUAL, null, LocalDate.parse("2026-11-29"), dayOffset = 1), time)
        }
        assertEquals(detail.card.departure, detail.departure.time)
    }

    @Test
    fun `times that are not known are null, not invented`() {
        val detail = TestData.flight().toDetailUiModel(null, isPro = false, now = now)

        assertEquals("18:25", detail.departure.scheduled)
        assertNull(detail.departure.estimated)
        assertNull(detail.departure.actual)
        assertNull(detail.arrival.estimated)
        assertNull(detail.arrival.actual)
    }

    @Test
    fun `before take-off every place detail has a row, announced or not`() {
        val detail = TestData.flight(depTerminal = "1", depGate = null, depCheckin = "C12", arrTerminal = "2")
            .toDetailUiModel(null, isPro = false, now = now, newFields = setOf(FlightField.CHECK_IN_DESK))

        assertEquals(
            listOf(
                FlightInfoUiModel(FlightField.DEP_TERMINAL, "1"),
                FlightInfoUiModel(FlightField.DEP_GATE, null),
                FlightInfoUiModel(FlightField.CHECK_IN_DESK, "C12", isNew = true),
            ),
            detail.departure.info,
        )
        assertEquals(
            listOf(
                FlightInfoUiModel(FlightField.ARR_TERMINAL, "2"),
                FlightInfoUiModel(FlightField.ARR_GATE, null),
                FlightInfoUiModel(FlightField.BAGGAGE_BELT, null),
            ),
            detail.arrival.info,
        )
    }

    @Test
    fun `after take-off, departure details that never came are dropped but the belt is still awaited`() {
        val detail = TestData.flight(status = FlightStatus.LANDED, actDep = schedDep, actArr = schedArr, depTerminal = "1", depGate = null)
            .toDetailUiModel(null, isPro = false, now = now)

        assertEquals(listOf(FlightInfoUiModel(FlightField.DEP_TERMINAL, "1")), detail.departure.info)
        assertEquals(
            listOf(FlightField.ARR_TERMINAL, FlightField.ARR_GATE, FlightField.BAGGAGE_BELT),
            detail.arrival.info.map { it.field },
        )
    }

    @Test
    fun `a cancelled flight awaits nothing`() {
        val detail = TestData.flight(status = FlightStatus.CANCELLED, depTerminal = "1", depGate = null, arrTerminal = null)
            .toDetailUiModel(null, isPro = false, now = now)

        assertEquals(listOf(FlightInfoUiModel(FlightField.DEP_TERMINAL, "1")), detail.departure.info)
        assertTrue(detail.arrival.info.isEmpty())
    }

    @Test
    fun `aircraft, delay reason and distance`() {
        val detail = TestData.flight(delayReason = " Weather at Mumbai ").toDetailUiModel(null, isPro = false, now = now)

        assertEquals("Airbus A321neo", detail.aircraftModel)
        assertEquals("VT-ISR", detail.aircraftReg)
        assertEquals("Weather at Mumbai", detail.delayReason)
        assertEquals(LocalDate.parse("2026-11-25"), detail.flightDate)
        // Delhi to Mumbai is roughly 1,140 km as the crow flies.
        assertTrue("distance was ${detail.distanceKm}", detail.distanceKm in 1100..1180)

        val bare = TestData.flight(aircraftModel = " ", aircraftReg = null, depAirport = null)
            .toDetailUiModel(null, isPro = false, now = now)
        assertNull(bare.aircraftModel)
        assertNull(bare.aircraftReg)
        assertNull(bare.delayReason)
        assertNull(bare.distanceKm)
    }

    // ---- "Where's my plane" -------------------------------------------------------------------

    @Test
    fun `no insight, nothing shown`() {
        assertEquals(ProInsightUiModel.None, TestData.flight().toDetailUiModel(null, isPro = false, now = now).proInsight)
        assertEquals(ProInsightUiModel.None, TestData.flight().toDetailUiModel(null, isPro = true, now = now).proInsight)
    }

    @Test
    fun `a free user sees the teaser when the view says there is a Pro insight`() {
        val detail = TestData.flight(hasProInsight = true).toDetailUiModel(null, isPro = false, now = now)

        assertEquals(ProInsightUiModel.Teaser, detail.proInsight)
    }

    @Test
    fun `a free user never gets Pro data, even if a prediction is still cached`() {
        val teased = TestData.flight(hasProInsight = true, prediction = prediction, inboundFlightId = TestData.OTHER_FLIGHT_ID)
            .toDetailUiModel(null, isPro = false, now = now)
        assertEquals(ProInsightUiModel.Teaser, teased.proInsight)

        val quiet = TestData.flight(hasProInsight = false, prediction = prediction.copy(lateMin = 0))
            .toDetailUiModel(null, isPro = false, now = now)
        assertEquals(ProInsightUiModel.None, quiet.proInsight)
    }

    @Test
    fun `a Pro user sees where the plane is and when the flight can leave`() {
        val detail = TestData.flight(hasProInsight = true, prediction = prediction).toDetailUiModel(null, isPro = true, now = now)

        assertEquals(
            ProInsightUiModel.Details(
                inboundFlight = "6E 2032",
                inboundFrom = "BLR",
                inboundArrival = "18:20",
                inboundLanded = false,
                lateMinutes = 20,
                predictedDeparture = "19:10",
                reason = "Inbound aircraft is running 45 min late.",
            ),
            detail.proInsight,
        )
        assertTrue((detail.proInsight as ProInsightUiModel.Details).isLate)
    }

    @Test
    fun `a Pro user also sees a plane that is on time, and one that has landed`() {
        val onTime = prediction.copy(
            lateMin = 0,
            reason = null,
            predictedDep = null,
            inbound = prediction.inbound?.copy(status = FlightStatus.LANDED, actArr = schedDep - 48.minutes),
        )

        val details = TestData.flight(prediction = onTime).toDetailUiModel(null, isPro = true, now = now).proInsight as ProInsightUiModel.Details

        assertFalse(details.isLate)
        assertTrue(details.inboundLanded)
        assertEquals("17:37", details.inboundArrival)
        assertNull(details.predictedDeparture)
        assertNull(details.reason)
    }

    @Test
    fun `nine minutes is not late, ten is, like has_pro_insight`() {
        fun details(lateMin: Int) = TestData.flight(prediction = prediction.copy(lateMin = lateMin))
            .toDetailUiModel(null, isPro = true, now = now).proInsight as ProInsightUiModel.Details

        assertFalse(details(9).isLate)
        assertTrue(details(10).isLate)
        assertEquals(0, details(-3).lateMinutes)
    }

    @Test
    fun `a Pro user is never teased`() {
        val detail = TestData.flight(hasProInsight = true, prediction = null).toDetailUiModel(null, isPro = true, now = now)

        assertEquals(ProInsightUiModel.None, detail.proInsight)
    }

    @Test
    fun `the plane's previous leg stops mattering once the flight has left or is cancelled`() {
        val departed = TestData.flight(status = FlightStatus.EN_ROUTE, actDep = schedDep, hasProInsight = true, prediction = prediction)
        assertEquals(ProInsightUiModel.None, departed.toDetailUiModel(null, isPro = false, now = now).proInsight)
        assertEquals(ProInsightUiModel.None, departed.toDetailUiModel(null, isPro = true, now = now).proInsight)

        val cancelled = TestData.flight(status = FlightStatus.CANCELLED, hasProInsight = true)
        assertEquals(ProInsightUiModel.None, cancelled.toDetailUiModel(null, isPro = false, now = now).proInsight)
    }

    // ---- tracking, booking, alerts ------------------------------------------------------------

    @Test
    fun `a flight that is only cached has no booking and no alert switches`() {
        val detail = TestData.flight().toDetailUiModel(tracking = null, isPro = false, now = now)

        assertEquals(TrackingState.NOT_TRACKED, detail.trackingState)
        assertNull(detail.booking)
        assertTrue(detail.alerts.isEmpty())
    }

    @Test
    fun `tracked and archived are told apart`() {
        val flight = TestData.flight()

        assertEquals(TrackingState.TRACKED, flight.toDetailUiModel(TestData.tracking(), isPro = false, now = now).trackingState)
        assertEquals(
            TrackingState.ARCHIVED,
            flight.toDetailUiModel(TestData.tracking(archivedAt = now), isPro = false, now = now).trackingState,
        )
    }

    @Test
    fun `booking details come from the tracked row`() {
        val full = TestData.flight().toDetailUiModel(
            TestData.tracking(pnr = "QX7RTL", seat = "14C", cabin = Cabin.BUSINESS, note = "Window"),
            isPro = false,
            now = now,
        )
        assertEquals(BookingUiModel("QX7RTL", "14C", Cabin.BUSINESS, "Window"), full.booking)
        assertFalse(full.booking!!.isEmpty)

        val empty = TestData.flight().toDetailUiModel(TestData.tracking(note = "  "), isPro = false, now = now)
        assertEquals(BookingUiModel(null, null, null, null), empty.booking)
        assertTrue(empty.booking!!.isEmpty)
    }

    @Test
    fun `every alert group has a switch, on by default`() {
        val alerts = TestData.flight().toDetailUiModel(TestData.tracking(), isPro = true, now = now).alerts

        assertEquals(AlertGroup.entries.toList(), alerts.map { it.group })
        assertTrue(alerts.all { it.enabled && !it.locked })
    }

    @Test
    fun `a group is off only when all of its alert types are muted`() {
        val prefs = mapOf(
            AlertType.DEPARTED to false, AlertType.LANDED to false, // whole group
            AlertType.GATE_ASSIGNED to false, // one of three
            AlertType.BOARDING to true,
        )
        val alerts = TestData.flight().toDetailUiModel(TestData.tracking(notifyPrefs = prefs), isPro = true, now = now).alerts
            .associate { it.group to it.enabled }

        assertEquals(false, alerts[AlertGroup.TAKEOFF_AND_LANDING])
        assertEquals(true, alerts[AlertGroup.GATE])
        assertEquals(true, alerts[AlertGroup.BOARDING])
        assertEquals(true, alerts[AlertGroup.DELAYS])
    }

    @Test
    fun `the Pro-only alert is locked and off for a free user`() {
        val alerts = TestData.flight().toDetailUiModel(TestData.tracking(), isPro = false, now = now).alerts

        assertEquals(AlertToggleUiModel(AlertGroup.INBOUND_AIRCRAFT, enabled = false, locked = true), alerts.last())
        assertTrue(alerts.dropLast(1).none { it.locked })
    }

    // ---- alert groups against the backend contract --------------------------------------------

    @Test
    fun `every alert type the backend sends belongs to exactly one group`() {
        val grouped = AlertGroup.entries.flatMap { it.types }

        assertEquals("an alert type is in two groups", grouped.size, grouped.toSet().size)
        assertEquals("an alert type has no switch", AlertType.entries.toSet(), grouped.toSet())
    }

    @Test
    fun `only the inbound-aircraft group is Pro-only, like the server's fan-out`() {
        assertEquals(listOf(AlertGroup.INBOUND_AIRCRAFT), AlertGroup.entries.filter { it.proOnly })
        assertEquals(AlertType.entries.filter { it.proOnly }, AlertGroup.INBOUND_AIRCRAFT.types)
    }

    @Test
    fun `switching a group writes one notify_prefs entry per alert type, keyed like the backend`() {
        assertEquals(
            mapOf(AlertType.GATE_ASSIGNED to false, AlertType.GATE_CHANGE to false, AlertType.TERMINAL_CHANGE to false),
            AlertGroup.GATE.prefs(enabled = false),
        )
        assertEquals(
            setOf("DELAY", "DELAY_REDUCED", "SCHEDULE_CHANGE"),
            AlertGroup.DELAYS.prefs(enabled = true).keys.map { it.wire }.toSet(),
        )
        assertTrue(AlertGroup.DELAYS.prefs(enabled = true).values.all { it })
    }
}
