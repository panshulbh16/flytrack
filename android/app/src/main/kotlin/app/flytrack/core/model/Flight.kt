package app.flytrack.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Last known aircraft position (`live_*` columns). */
data class LivePosition(
    val lat: Double,
    val lon: Double,
    val altFt: Int?,
    val headingDeg: Double?,
    val speedKts: Double?,
    val at: Instant?,
)

/** The aircraft's previous leg, inside [InboundPrediction] ("Where's my plane", Pro). */
data class InboundAircraft(
    /** e.g. "6E 2032". */
    val flight: String,
    /** IATA code of the airport the aircraft is coming from. */
    val fromIata: String,
    val status: FlightStatus,
    val schedArr: Instant?,
    val estArr: Instant?,
    val actArr: Instant?,
    val live: LivePosition?,
)

/**
 * `flight_view.prediction` (Pro only; the server sends null to free users):
 * will the inbound aircraft arrive in time to operate this flight?
 */
data class InboundPrediction(
    val computedAt: Instant?,
    /** When this flight can realistically leave; null when no prediction is possible. */
    val predictedDep: Instant?,
    /** Minutes later than the airline currently says (0 = on time). */
    val lateMin: Int,
    /** Server-written English sentence, null when the aircraft is on time. */
    val reason: String?,
    val inbound: InboundAircraft?,
)

/**
 * One flight leg as exposed by the backend's `public.flight_view`.
 * All instants are UTC; show them in the airport's zone ([depTz] / [arrTz]).
 */
data class Flight(
    val id: String,
    val carrierIata: String,
    /** No leading zeros, may end in a letter: "2341", "101A". */
    val flightNumber: String,
    /** Local departure date at the origin airport. */
    val flightDate: LocalDate,
    val depIata: String,
    val arrIata: String,
    val airlineName: String?,
    val status: FlightStatus,

    val schedDep: Instant?,
    val estDep: Instant?,
    val actDep: Instant?,
    val depTerminal: String?,
    val depGate: String?,
    val depCheckin: String?,

    val schedArr: Instant?,
    val estArr: Instant?,
    val actArr: Instant?,
    val arrTerminal: String?,
    val arrGate: String?,
    val baggageBelt: String?,

    val aircraftModel: String?,
    /** Tail number, e.g. "VT-ISR". */
    val aircraftReg: String?,
    /** Only when the airline or provider gave one. */
    val delayReason: String?,

    val live: LivePosition?,

    /** When the backend last asked the provider about this flight. */
    val lastPolledAt: Instant?,
    val updatedAt: Instant?,

    /** null until the airport is known to the backend's airports table. */
    val depAirport: Airport?,
    val arrAirport: Airport?,

    /** Pro only (null for free users): the flight row of the aircraft's previous leg. */
    val inboundFlightId: String?,
    /** Pro only (null for free users). */
    val prediction: InboundPrediction?,
    /** True for everyone when a Pro prediction of 10+ minutes exists: drives the paywall teaser. */
    val hasProInsight: Boolean,
) {
    /** "6E 2341". */
    val designator: String get() = "$carrierIata $flightNumber"

    val depTz: String? get() = depAirport?.tz
    val arrTz: String? get() = arrAirport?.tz
}
