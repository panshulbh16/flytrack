package app.flytrack.feature.flights.ui

import app.flytrack.core.model.Airport
import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.InboundAircraft
import app.flytrack.core.model.InboundPrediction
import app.flytrack.core.model.TrackSource
import app.flytrack.core.model.TrackedFlight
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.feature.flights.FlightsUiState
import app.flytrack.feature.flights.presentation.FlightCardUiModel
import app.flytrack.feature.flights.presentation.FlightDetailUiModel
import app.flytrack.feature.flights.presentation.FlightField
import app.flytrack.feature.flights.presentation.buildFlightSections
import app.flytrack.feature.flights.presentation.toCardUiModel
import app.flytrack.feature.flights.presentation.toDetailUiModel
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Sample data for the `@Preview` functions of this feature. Built through the real mappers. */
internal object PreviewFlights {
    /** 25 Nov 2026, 17:40 in India. */
    private val now = Instant.parse("2026-11-25T12:10:00Z")

    private val del = Airport("DEL", "Indira Gandhi International Airport", "New Delhi", "Asia/Kolkata", 28.5665, 77.1031)
    private val bom = Airport("BOM", "Chhatrapati Shivaji Maharaj International Airport", "Mumbai", "Asia/Kolkata", 19.0887, 72.8679)
    private val blr = Airport("BLR", "Kempegowda International Airport", "Bengaluru", "Asia/Kolkata", 13.1979, 77.7063)
    private val dxb = Airport("DXB", "Dubai International Airport", "Dubai", "Asia/Dubai", 25.2528, 55.3644)

    private fun flight(
        id: String,
        carrier: String,
        number: String,
        airline: String,
        date: String,
        from: Airport,
        to: Airport,
        status: FlightStatus,
        schedDep: String,
        schedArr: String,
        estDep: String? = null,
        actDep: String? = null,
        estArr: String? = null,
        actArr: String? = null,
        depTerminal: String? = null,
        depGate: String? = null,
        depCheckin: String? = null,
        arrTerminal: String? = null,
        belt: String? = null,
        delayReason: String? = null,
        prediction: InboundPrediction? = null,
        hasProInsight: Boolean = false,
    ) = Flight(
        id = id, carrierIata = carrier, flightNumber = number, flightDate = LocalDate.parse(date),
        depIata = from.iata, arrIata = to.iata, airlineName = airline, status = status,
        schedDep = Instant.parse(schedDep), estDep = estDep?.let(Instant::parse), actDep = actDep?.let(Instant::parse),
        depTerminal = depTerminal, depGate = depGate, depCheckin = depCheckin,
        schedArr = Instant.parse(schedArr), estArr = estArr?.let(Instant::parse), actArr = actArr?.let(Instant::parse),
        arrTerminal = arrTerminal, arrGate = null, baggageBelt = belt,
        aircraftModel = "Airbus A321neo", aircraftReg = "VT-ISR", delayReason = delayReason,
        live = null, lastPolledAt = Instant.parse("2026-11-25T12:06:00Z"), updatedAt = Instant.parse("2026-11-25T11:40:00Z"),
        depAirport = from, arrAirport = to, inboundFlightId = null, prediction = prediction, hasProInsight = hasProInsight,
    )

    private val delayed = flight(
        id = "preview-delayed", carrier = "6E", number = "2341", airline = "IndiGo", date = "2026-11-25",
        from = del, to = bom, status = FlightStatus.CHECK_IN,
        schedDep = "2026-11-25T12:55:00Z", estDep = "2026-11-25T13:20:00Z",
        schedArr = "2026-11-25T15:10:00Z", estArr = "2026-11-25T15:35:00Z",
        depTerminal = "1", depGate = "42B", depCheckin = "C12-C18", arrTerminal = "2",
        delayReason = "Late arrival of the incoming aircraft", hasProInsight = true,
    )

    private val inAir = flight(
        id = "preview-in-air", carrier = "AI", number = "865", airline = "Air India", date = "2026-11-25",
        from = bom, to = blr, status = FlightStatus.EN_ROUTE,
        schedDep = "2026-11-25T11:00:00Z", actDep = "2026-11-25T11:08:00Z",
        schedArr = "2026-11-25T12:50:00Z", estArr = "2026-11-25T12:46:00Z",
        depTerminal = "2", depGate = "67", arrTerminal = "1",
    )

    private val overnight = flight(
        id = "preview-overnight", carrier = "EK", number = "511", airline = "Emirates", date = "2026-11-28",
        from = dxb, to = del, status = FlightStatus.SCHEDULED,
        schedDep = "2026-11-28T18:35:00Z", schedArr = "2026-11-28T21:50:00Z",
        depTerminal = "3",
    )

    private val landed = flight(
        id = "preview-landed", carrier = "UK", number = "955", airline = "Vistara", date = "2026-11-21",
        from = blr, to = del, status = FlightStatus.ARRIVED,
        schedDep = "2026-11-21T04:30:00Z", actDep = "2026-11-21T04:34:00Z",
        schedArr = "2026-11-21T07:15:00Z", actArr = "2026-11-21T07:09:00Z",
        arrTerminal = "3", belt = "7",
    )

    private fun tracking(flight: Flight, pnr: String? = null, seat: String? = null) = TrackedFlight(
        id = "uf-${flight.id}", userId = "preview-user", flightId = flight.id,
        pnr = pnr, seat = seat, cabin = Cabin.ECONOMY.takeIf { pnr != null }, source = TrackSource.MANUAL,
        notifyPrefs = mapOf(AlertType.DEPARTED to false, AlertType.LANDED to false), note = null,
        archivedAt = null, createdAt = now,
    )

    fun delayedCard(): FlightCardUiModel = delayed.toCardUiModel(now, newFields = setOf(FlightField.DEP_GATE))

    fun inAirCard(): FlightCardUiModel = inAir.toCardUiModel(now)

    fun landedCard(): FlightCardUiModel = landed.toCardUiModel(now)

    fun listState(): FlightsUiState {
        val sections = buildFlightSections(
            tracked = listOf(inAir, delayed, overnight, landed).map { TrackedFlightDetails(tracking(it), it) },
            now = now,
            newFields = { if (it.id == delayed.id) setOf(FlightField.DEP_GATE) else emptySet() },
        )
        return FlightsUiState(isLoading = false, next = sections.next, upcoming = sections.upcoming, past = sections.past)
    }

    /** A free user's view: the Pro teaser, PNR and seat, two muted alert groups. */
    fun detail(): FlightDetailUiModel = delayed.toDetailUiModel(
        tracking = tracking(delayed, pnr = "QX7RTL", seat = "14C"),
        isPro = false,
        now = now,
        newFields = setOf(FlightField.DEP_GATE),
    )

    /** A Pro user's view of the same flight, with the "Where's my plane" data. */
    fun detailPro(): FlightDetailUiModel = delayed.copy(
        prediction = InboundPrediction(
            computedAt = now,
            predictedDep = Instant.parse("2026-11-25T13:40:00Z"),
            lateMin = 20,
            reason = "Inbound aircraft 6E 2032 from BLR is running 45 min late.",
            inbound = InboundAircraft(
                flight = "6E 2032", fromIata = "BLR", status = FlightStatus.EN_ROUTE,
                schedArr = Instant.parse("2026-11-25T12:05:00Z"), estArr = Instant.parse("2026-11-25T12:50:00Z"),
                actArr = null, live = null,
            ),
        ),
    ).toDetailUiModel(tracking = tracking(delayed, pnr = "QX7RTL", seat = "14C"), isPro = true, now = now)

    fun inAirDetail(): FlightDetailUiModel = inAir.toDetailUiModel(tracking = tracking(inAir), isPro = false, now = now)
}
