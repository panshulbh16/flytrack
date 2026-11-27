package app.flytrack.feature.intel

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

// Typed answers of the P3 "flight intelligence" RPCs (docs/P3_CONTRACTS.md A2, A5, B1–B4, B7).
// Parsing lives in IntelParsers.kt; everything here is plain data without android.* types.

/** `flight_timeline[].key`, in the order the server lists them. */
enum class TimelineKey(val wire: String) {
    SCHEDULED("scheduled"),
    CHECKIN_OPEN("checkin_open"),
    GATE_ASSIGNED("gate_assigned"),
    BOARDING("boarding"),
    GATE_CLOSED("gate_closed"),
    PUSHBACK("pushback"),
    TAKEOFF("takeoff"),
    DIVERTED("diverted"),
    LANDING("landing"),
    AT_GATE("at_gate"),
    BELT("belt"),
    CANCELLED("cancelled");

    /** Milestones at the arrival airport: their times are shown in the arrival zone. */
    val atArrival: Boolean get() = this == DIVERTED || this == LANDING || this == AT_GATE || this == BELT

    companion object {
        /** Null for a key this app version does not know (the step is skipped). */
        fun fromWire(value: String?): TimelineKey? = entries.firstOrNull { it.wire == value }
    }
}

/** `flight_timeline[].state`. */
enum class TimelineState(val wire: String) {
    DONE("done"), CURRENT("current"), NEXT("next");

    companion object {
        fun fromWire(value: String?): TimelineState = entries.firstOrNull { it.wire == value } ?: NEXT
    }
}

/**
 * The Pro `detail` of one step. Every field is optional; which ones are set depends on the key
 * (gate / terminal for the gate steps, delay for pushback and landing, taxi minutes for take-off and at-gate).
 */
data class TimelineDetail(
    val gate: String? = null,
    val terminal: String? = null,
    val belt: String? = null,
    val delayMin: Int? = null,
    val taxiOutMin: Int? = null,
    val taxiInMin: Int? = null,
    val closesAt: Instant? = null,
    val schedDep: Instant? = null,
    val schedArr: Instant? = null,
    val fromArr: String? = null,
    val toArr: String? = null,
)

/**
 * One milestone of `flight_timeline`.
 * @property at when it happened (done/current) or is expected (next); null when unknown or only implied
 * @property detail Pro only; null for free users
 */
data class TimelineStep(
    val key: TimelineKey,
    val at: Instant?,
    val state: TimelineState,
    val detail: TimelineDetail?,
)

/** `delay_prediction.confidence`. */
enum class PredictionConfidence(val wire: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromWire(value: String?): PredictionConfidence = entries.firstOrNull { it.wire == value } ?: LOW
    }
}

/**
 * One reason behind the prediction.
 * @property key history, inbound, weather, time_of_day, airport (or a newer one)
 * @property label the server's sentence in the requested language
 */
data class PredictionFactor(val key: String, val effectMin: Int, val label: String)

/** Answer of `delay_prediction(p_flight_id, p_lang)` (B1, Pro). */
data class DelayPrediction(
    /** Chance of a departure delay of 15 min or more, 0..1. */
    val pDelay15: Double,
    /** Chance of 60 min or more, 0..1. */
    val pDelay60: Double,
    val expectedDelayMin: Int,
    val factors: List<PredictionFactor>,
    val confidence: PredictionConfidence,
    /** Past legs behind the route estimate; null when the server did not say. */
    val samples: Int?,
    /** "rules-v1": names the method so a model can replace it later. */
    val model: String,
    val computedAt: Instant?,
)

/** Answer of `gate_prediction(p_flight_id)` (B2, Pro): "Usually gate 42B (7 of 9)". */
data class GatePrediction(
    val gate: String,
    val terminal: String?,
    val share: Double,
    /** Legs that used [gate]. */
    val count: Int,
    /** Legs with a known gate. */
    val samples: Int,
    val alternatives: List<GateAlternative>,
)

data class GateAlternative(val gate: String, val share: Double)

/** Typical taxi time in whole minutes; [p50] and [p80] are null without samples. */
data class TaxiTime(val p50: Int?, val p80: Int?, val samples: Int) {
    val isKnown: Boolean get() = p50 != null && samples > 0
}

/** Answer of `taxi_stats(p_iata)` (B3, Pro). */
data class TaxiStats(val iata: String, val taxiOut: TaxiTime?, val taxiIn: TaxiTime?)

/** `my_connections[].risk` (B4). */
enum class ConnectionRisk(val wire: String) {
    OK("ok"), TIGHT("tight"), AT_RISK("at_risk"), MISSED("missed");

    companion object {
        /** An unknown value is treated as at risk: better a loud warning than a silent one. */
        fun fromWire(value: String?): ConnectionRisk = entries.firstOrNull { it.wire == value } ?: AT_RISK

        /** The contract's rule for a buffer (layover − minimum connection time). */
        fun forBuffer(bufferMin: Int): ConnectionRisk = when {
            bufferMin >= OK_FROM_MINUTES -> OK
            bufferMin >= 0 -> TIGHT
            else -> AT_RISK
        }

        const val OK_FROM_MINUTES = 30
    }
}

/** One row of `my_connections()` (B4, Pro). */
data class Connection(
    val inFlightId: String,
    val outFlightId: String,
    val airport: String,
    /** "6E 2341"; null when the server did not send it. */
    val inFlight: String?,
    val outFlight: String?,
    val inArrival: Instant?,
    val outDeparture: Instant?,
    val layoverMin: Int,
    val mctMin: Int,
    val bufferMin: Int,
    val risk: ConnectionRisk,
    /** "T1→T3"; null when the terminal stays the same or is unknown. */
    val terminalChange: String?,
    /** The server's English note about the transfer, e.g. the shuttle between terminals. */
    val transferNote: String?,
)

/** One hour of `airport_trends.by_hour` (airport-local hour of the scheduled departure). */
data class HourTrend(val hour: Int, val avgDepDelay: Int, val delayedPct: Int, val samples: Int)

/** One day of `airport_trends.by_day`; [avgDepDelay] is null when every leg was cancelled. */
data class DayTrend(val date: LocalDate, val avgDepDelay: Int?, val cancelled: Int, val samples: Int)

/** Answer of `airport_trends(p_iata, p_days)` (B7, Pro). */
data class AirportTrends(val iata: String, val days: Int, val byHour: List<HourTrend>, val byDay: List<DayTrend>) {
    val isEmpty: Boolean get() = byHour.isEmpty() && byDay.none { it.samples > 0 }
}

/** One AIRCRAFT_SWAP event of the flight (A5, Pro). */
data class AircraftSwap(
    val oldReg: String?,
    val newReg: String?,
    val oldModel: String?,
    val newModel: String?,
    val at: Instant?,
)

/**
 * The P3 aircraft columns of `flight_view` plus the swap history.
 * @property icaoType "A20N"; for everyone
 * @property ageYears Pro only (null for free users and unknown tails)
 * @property swaps Pro only (the events policy hides them from free users), oldest first
 */
data class AircraftInfo(val icaoType: String?, val ageYears: Double?, val swaps: List<AircraftSwap>)
