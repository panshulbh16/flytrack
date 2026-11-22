package app.flytrack.testing

import app.flytrack.core.model.Airport
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.LivePosition
import app.flytrack.core.model.Plan
import app.flytrack.core.model.TrackSource
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.UserProfile
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Ready-made domain objects for tests. Every builder has defaults; override only what the test is about. */
object TestData {
    /** 22 Nov 2026, 15:30 in India. The default "now" of [FakeClock]. */
    val NOW: Instant = Instant.parse("2026-11-22T10:00:00Z")

    const val USER_ID = "11111111-1111-4111-8111-111111111111"
    const val FLIGHT_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    const val OTHER_FLIGHT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    val DEL = Airport("DEL", "Indira Gandhi International Airport", "New Delhi", "Asia/Kolkata", 28.5665, 77.1031)
    val BOM = Airport("BOM", "Chhatrapati Shivaji International Airport", "Mumbai", "Asia/Kolkata", 19.0887, 72.8679)
    val BLR = Airport("BLR", "Kempegowda International Airport", "Bengaluru", "Asia/Kolkata", 13.1979, 77.7063)
    val DXB = Airport("DXB", "Dubai International Airport", "Dubai", "Asia/Dubai", 25.2528, 55.3644)

    /** 6E 2341 DEL-BOM on 25 Nov 2026, scheduled 18:25-20:40 IST, on time, nothing assigned yet. */
    fun flight(
        id: String = FLIGHT_ID,
        carrierIata: String = "6E",
        flightNumber: String = "2341",
        flightDate: LocalDate = LocalDate.parse("2026-11-25"),
        depAirport: Airport? = DEL,
        arrAirport: Airport? = BOM,
        depIata: String = depAirport?.iata ?: "DEL",
        arrIata: String = arrAirport?.iata ?: "BOM",
        airlineName: String? = "IndiGo",
        status: FlightStatus = FlightStatus.SCHEDULED,
        schedDep: Instant? = Instant.parse("2026-11-25T12:55:00Z"),
        estDep: Instant? = null,
        actDep: Instant? = null,
        depTerminal: String? = "1",
        depGate: String? = null,
        depCheckin: String? = null,
        schedArr: Instant? = Instant.parse("2026-11-25T15:10:00Z"),
        estArr: Instant? = null,
        actArr: Instant? = null,
        arrTerminal: String? = "2",
        arrGate: String? = null,
        baggageBelt: String? = null,
        aircraftModel: String? = "Airbus A321neo",
        aircraftReg: String? = "VT-ISR",
        delayReason: String? = null,
        live: LivePosition? = null,
        lastPolledAt: Instant? = NOW,
        updatedAt: Instant? = NOW,
        inboundFlightId: String? = null,
        prediction: InboundPrediction? = null,
        hasProInsight: Boolean = false,
    ): Flight = Flight(
        id = id, carrierIata = carrierIata, flightNumber = flightNumber, flightDate = flightDate,
        depIata = depIata, arrIata = arrIata, airlineName = airlineName, status = status,
        schedDep = schedDep, estDep = estDep, actDep = actDep,
        depTerminal = depTerminal, depGate = depGate, depCheckin = depCheckin,
        schedArr = schedArr, estArr = estArr, actArr = actArr,
        arrTerminal = arrTerminal, arrGate = arrGate, baggageBelt = baggageBelt,
        aircraftModel = aircraftModel, aircraftReg = aircraftReg, delayReason = delayReason,
        live = live, lastPolledAt = lastPolledAt, updatedAt = updatedAt,
        depAirport = depAirport, arrAirport = arrAirport,
        inboundFlightId = inboundFlightId, prediction = prediction, hasProInsight = hasProInsight,
    )

    fun tracking(
        flightId: String = FLIGHT_ID,
        id: String = "uf-$flightId",
        userId: String = USER_ID,
        pnr: String? = null,
        seat: String? = null,
        cabin: Cabin? = null,
        source: TrackSource = TrackSource.MANUAL,
        notifyPrefs: Map<AlertType, Boolean> = emptyMap(),
        note: String? = null,
        archivedAt: Instant? = null,
        createdAt: Instant? = NOW,
    ): TrackedFlight = TrackedFlight(id, userId, flightId, pnr, seat, cabin, source, notifyPrefs, note, archivedAt, createdAt)

    fun tracked(flight: Flight = flight(), tracking: TrackedFlight = tracking(flight.id)): TrackedFlightDetails =
        TrackedFlightDetails(tracking = tracking, flight = flight)

    fun profile(
        id: String = USER_ID,
        displayName: String? = null,
        plan: Plan = Plan.FREE,
        proUntil: Instant? = null,
    ): UserProfile = UserProfile(id, displayName, plan, proUntil)
}
