package app.flytrack.feature.intel

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.bestDeparture
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.model.hasLanded
import app.flytrack.core.time.FlightTimeFormat
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Pure mapping of the intelligence answers to what the screens draw. No android.* types: the
// composables only turn these typed values into localised text (IntelTexts.kt).

// ---- A2 timeline --------------------------------------------------------------------------------

/** One piece of a step's Pro detail; a row can carry several ("Gate 5", "Taxi in 6 min"). */
sealed interface TimelineFact {
    data class Gate(val gate: String, val terminal: String?) : TimelineFact
    data class Terminal(val terminal: String) : TimelineFact

    /** Minutes against the timetable: > 0 late, < 0 early, 0 on time. */
    data class Delay(val minutes: Int) : TimelineFact
    data class TaxiOut(val minutes: Int) : TimelineFact
    data class TaxiIn(val minutes: Int) : TimelineFact
    data class Belt(val belt: String) : TimelineFact

    /** Web check-in closes at [time] (departure airport zone). */
    data class CheckinCloses(val time: String) : TimelineFact

    /** The timetable: departure and arrival in their airports' zones. */
    data class Timetable(val departure: String?, val arrival: String?) : TimelineFact

    /** Diverted from [from] (the planned destination) to [to]. */
    data class Diverted(val from: String?, val to: String?) : TimelineFact
}

/**
 * One row of the timeline.
 * @property time "18:50" in the zone of the airport where it happens; null when unknown
 * @property dayOffset calendar days from the flight date (+1 for an arrival after midnight, -1 for check-in opening the day before)
 * @property implied done, but the server has no time for it (a later milestone proves it happened)
 */
data class TimelineRowUiModel(
    val key: TimelineKey,
    val state: TimelineState,
    val time: String?,
    val dayOffset: Int,
    val implied: Boolean,
    val facts: List<TimelineFact>,
)

/**
 * @property blockMinutes off-block to on-block, once both are known (crew mode counts block time)
 * @property hasProDetail at least one step carries the Pro detail
 */
data class TimelineUiModel(val rows: List<TimelineRowUiModel>, val blockMinutes: Int?, val hasProDetail: Boolean)

/** [depTz] / [arrTz]: the airports' zones; [flightDate] the timetable date at the departure airport. */
fun List<TimelineStep>.toTimelineUi(depTz: String?, arrTz: String?, flightDate: LocalDate): TimelineUiModel? {
    if (isEmpty()) return null
    val rows = map { step ->
        val tz = if (step.key.atArrival) arrTz else depTz
        TimelineRowUiModel(
            key = step.key,
            state = step.state,
            time = step.at?.let { FlightTimeFormat.time(it, tz) },
            dayOffset = step.at?.let { dayOffset(it, tz, flightDate) } ?: 0,
            implied = step.at == null && step.state != TimelineState.NEXT,
            facts = step.detail?.let { factsOf(step.key, it, depTz, arrTz) }.orEmpty(),
        )
    }
    val offBlock = firstOrNull { it.key == TimelineKey.PUSHBACK && it.state != TimelineState.NEXT }?.at
    val onBlock = firstOrNull { it.key == TimelineKey.AT_GATE && it.state != TimelineState.NEXT }?.at
    val block = if (offBlock != null && onBlock != null && onBlock > offBlock) (onBlock - offBlock).inWholeMinutes.toInt() else null
    return TimelineUiModel(rows, blockMinutes = block, hasProDetail = any { it.detail != null })
}

private fun dayOffset(at: Instant, tz: String?, flightDate: LocalDate): Int =
    (FlightTimeFormat.localDate(at, tz).toEpochDays() - flightDate.toEpochDays()).toInt()

private fun factsOf(key: TimelineKey, d: TimelineDetail, depTz: String?, arrTz: String?): List<TimelineFact> = when (key) {
    TimelineKey.SCHEDULED -> listOfNotNull(
        TimelineFact.Timetable(d.schedDep?.let { FlightTimeFormat.time(it, depTz) }, d.schedArr?.let { FlightTimeFormat.time(it, arrTz) })
            .takeIf { it.departure != null || it.arrival != null },
    )
    TimelineKey.CHECKIN_OPEN -> listOfNotNull(d.closesAt?.let { TimelineFact.CheckinCloses(FlightTimeFormat.time(it, depTz)) })
    TimelineKey.GATE_ASSIGNED, TimelineKey.BOARDING, TimelineKey.GATE_CLOSED -> listOfNotNull(gateFact(d.gate, d.terminal))
    TimelineKey.PUSHBACK, TimelineKey.LANDING -> listOfNotNull(d.delayMin?.let { TimelineFact.Delay(it) })
    TimelineKey.TAKEOFF -> listOfNotNull(d.taxiOutMin?.let { TimelineFact.TaxiOut(it) })
    TimelineKey.AT_GATE -> listOfNotNull(gateFact(d.gate, d.terminal), d.taxiInMin?.let { TimelineFact.TaxiIn(it) })
    TimelineKey.BELT -> listOfNotNull(d.belt?.let { TimelineFact.Belt(it) })
    TimelineKey.DIVERTED -> listOfNotNull(TimelineFact.Diverted(d.fromArr, d.toArr).takeIf { it.from != null || it.to != null })
    TimelineKey.CANCELLED -> listOfNotNull(
        d.schedDep?.let { TimelineFact.Timetable(FlightTimeFormat.time(it, depTz), null) },
    )
}

private fun gateFact(gate: String?, terminal: String?): TimelineFact? = when {
    gate != null -> TimelineFact.Gate(gate, terminal)
    terminal != null -> TimelineFact.Terminal(terminal)
    else -> null
}

// ---- B1 delay prediction, B2 gate, B3 taxi --------------------------------------------------------

data class FactorUiModel(val key: String, val label: String, val effectMin: Int)

/**
 * The prediction card.
 * @property likelyOnTime the expected delay is below [ON_TIME_BELOW_MINUTES]
 * @property delay15Pct / [delay60Pct] whole percent
 */
data class DelayPredictionUiModel(
    val expectedDelayMin: Int,
    val likelyOnTime: Boolean,
    val tone: FlightStatusTone,
    val delay15Pct: Int,
    val delay60Pct: Int,
    val factors: List<FactorUiModel>,
    val confidence: PredictionConfidence,
    val samples: Int?,
) {
    companion object {
        const val ON_TIME_BELOW_MINUTES = 5
    }
}

fun DelayPrediction.toUi(): DelayPredictionUiModel = DelayPredictionUiModel(
    expectedDelayMin = expectedDelayMin,
    likelyOnTime = expectedDelayMin < DelayPredictionUiModel.ON_TIME_BELOW_MINUTES,
    tone = if (expectedDelayMin >= app.flytrack.core.model.DELAY_THRESHOLD_MINUTES) FlightStatusTone.DELAYED else FlightStatusTone.ON_TIME,
    delay15Pct = percent(pDelay15),
    delay60Pct = percent(pDelay60),
    // Biggest effect first; ties keep the server's order.
    factors = factors.sortedByDescending { abs(it.effectMin) }.map { FactorUiModel(it.key, it.label, it.effectMin) },
    confidence = confidence,
    samples = samples,
)

private fun percent(p: Double): Int = (p * 100).roundToInt().coerceIn(0, 100)

/** "Usually gate 42B (7 of 9)". */
data class GatePredictionUiModel(val gate: String, val terminal: String?, val count: Int, val samples: Int)

fun GatePrediction.toUi(): GatePredictionUiModel = GatePredictionUiModel(gate, terminal, count.coerceIn(0, samples), samples)

/** "Typical taxi out at DEL: 18 min" (p50), with the slower p80 when it says more. */
data class TaxiLineUiModel(val iata: String, val outbound: Boolean, val typicalMin: Int, val slowMin: Int?)

fun TaxiStats.taxiOutLine(): TaxiLineUiModel? = taxiOut?.takeIf { it.isKnown }?.let { TaxiLineUiModel(iata, true, it.p50!!, it.p80?.takeIf { p80 -> p80 > it.p50 }) }

fun TaxiStats.taxiInLine(): TaxiLineUiModel? = taxiIn?.takeIf { it.isKnown }?.let { TaxiLineUiModel(iata, false, it.p50!!, it.p80?.takeIf { p80 -> p80 > it.p50 }) }

/** The "Prediction" section of the detail screen. */
sealed interface PredictionBlockUiModel {
    data object Hidden : PredictionBlockUiModel

    /** Not Pro for this flight while a prediction would be available: the teaser. */
    data object Locked : PredictionBlockUiModel

    data class Ready(
        val delay: DelayPredictionUiModel?,
        val gate: GatePredictionUiModel?,
        val taxiOut: TaxiLineUiModel?,
        val taxiIn: TaxiLineUiModel?,
    ) : PredictionBlockUiModel {
        val isEmpty: Boolean get() = delay == null && gate == null && taxiOut == null && taxiIn == null
    }
}

/** The contract's window: from 72 h before the best known departure until departure. */
fun Flight.isPredictionWindowOpen(now: Instant): Boolean {
    if (status == FlightStatus.CANCELLED || status == FlightStatus.DIVERTED || hasDeparted) return false
    val dep = bestDeparture ?: return false
    return now < dep && now >= dep - PREDICTION_WINDOW
}

private val PREDICTION_WINDOW = 72.hours

/** Gate prediction is shown only while no real gate is assigned and the flight has not left. */
fun Flight.wantsGatePrediction(): Boolean =
    depGate.isNullOrBlank() && !hasDeparted && status != FlightStatus.CANCELLED && status != FlightStatus.DIVERTED

/** Taxi-out matters until take-off, taxi-in until landing. */
fun Flight.wantsTaxiOut(): Boolean = !hasDeparted && status != FlightStatus.CANCELLED

fun Flight.wantsTaxiIn(): Boolean = !hasLanded && status != FlightStatus.CANCELLED && status != FlightStatus.DIVERTED

/** What the server answered for the prediction section; null = not asked (or not wanted for this flight). */
data class PredictionAnswers(
    val delay: IntelResult<DelayPrediction?>? = null,
    val gate: IntelResult<GatePrediction?>? = null,
    val taxiOut: IntelResult<TaxiStats?>? = null,
    val taxiIn: IntelResult<TaxiStats?>? = null,
)

fun predictionBlock(flight: Flight, answers: PredictionAnswers, now: Instant): PredictionBlockUiModel {
    val locked = listOfNotNull(answers.delay, answers.gate, answers.taxiOut, answers.taxiIn).any { it == IntelResult.Locked }
    val ready = PredictionBlockUiModel.Ready(
        delay = answers.delay?.valueOrNull()?.takeIf { flight.isPredictionWindowOpen(now) }?.toUi(),
        gate = answers.gate?.valueOrNull()?.takeIf { flight.wantsGatePrediction() }?.toUi(),
        taxiOut = answers.taxiOut?.valueOrNull()?.takeIf { flight.wantsTaxiOut() }?.taxiOutLine(),
        taxiIn = answers.taxiIn?.valueOrNull()?.takeIf { flight.wantsTaxiIn() }?.taxiInLine(),
    )
    return when {
        !ready.isEmpty -> ready
        // Tease only while a prediction is worth having: not for a flight that left days ago.
        locked && flight.isPredictionWindowOpen(now) -> PredictionBlockUiModel.Locked
        else -> PredictionBlockUiModel.Hidden
    }
}

// ---- A5 aircraft ----------------------------------------------------------------------------------

/** One swap, in the departure airport's zone. */
data class SwapUiModel(val fromReg: String?, val toReg: String?, val fromModel: String?, val toModel: String?, val time: String?)

/**
 * The aircraft block. [model] and [reg] come from the flight row, [icaoType] for everyone, [ageYears] and
 * [swaps] for Pro.
 */
data class AircraftCardUiModel(
    val model: String?,
    val icaoType: String?,
    val reg: String?,
    val ageYears: Double?,
    val swaps: List<SwapUiModel>,
)

/** [depTz]: swap times are shown in the departure airport's zone. */
fun aircraftCard(depTz: String?, model: String?, reg: String?, info: AircraftInfo?): AircraftCardUiModel {
    val tz = depTz
    return AircraftCardUiModel(
        model = model?.trim()?.takeIf { it.isNotEmpty() },
        // "A320" next to "Airbus A320" says nothing new.
        icaoType = info?.icaoType?.takeUnless { type -> model?.contains(type, ignoreCase = true) == true },
        reg = reg?.trim()?.takeIf { it.isNotEmpty() },
        ageYears = info?.ageYears,
        swaps = info?.swaps.orEmpty().map { SwapUiModel(it.oldReg, it.newReg, it.oldModel, it.newModel, it.at?.let { at -> FlightTimeFormat.time(at, tz) }) },
    )
}

// ---- B4 connections -------------------------------------------------------------------------------

/** Risk colours of the design brief: ok = on time, tight = delayed, at risk / missed = cancelled. */
val ConnectionRisk.tone: FlightStatusTone
    get() = when (this) {
        ConnectionRisk.OK -> FlightStatusTone.ON_TIME
        ConnectionRisk.TIGHT -> FlightStatusTone.DELAYED
        ConnectionRisk.AT_RISK, ConnectionRisk.MISSED -> FlightStatusTone.CANCELLED
    }

/**
 * One connection row.
 * @property departure the onward flight's best departure time, in the connecting airport's zone
 */
data class ConnectionUiModel(
    val inFlightId: String,
    val outFlightId: String,
    val airport: String,
    val inFlight: String?,
    val outFlight: String?,
    val arrival: String?,
    val departure: String?,
    val layoverMin: Int,
    val mctMin: Int,
    val bufferMin: Int,
    val risk: ConnectionRisk,
    val terminalChange: String?,
    val transferNote: String?,
) {
    val key: String get() = "$inFlightId:$outFlightId"
}

/** [tzOf] gives an airport's zone (from the cached flights); unknown zones fall back to India time. */
fun Connection.toUi(tzOf: (String) -> String?): ConnectionUiModel {
    val tz = tzOf(airport)
    return ConnectionUiModel(
        inFlightId = inFlightId,
        outFlightId = outFlightId,
        airport = airport,
        inFlight = inFlight,
        outFlight = outFlight,
        arrival = inArrival?.let { FlightTimeFormat.time(it, tz) },
        departure = outDeparture?.let { FlightTimeFormat.time(it, tz) },
        layoverMin = layoverMin,
        mctMin = mctMin,
        bufferMin = bufferMin,
        risk = risk,
        terminalChange = terminalChange,
        transferNote = transferNote,
    )
}

/** Zones of every airport the given flights touch. */
fun airportZones(flights: Collection<Flight>): Map<String, String> = buildMap {
    for (f in flights) {
        f.depAirport?.tz?.let { put(f.depIata, it) }
        f.arrAirport?.tz?.let { put(f.arrIata, it) }
    }
}

/**
 * The same trips `connections_for_user` derives, computed on the phone from the tracked flights: for every
 * leg, the next leg that departs from its arrival airport within 24 h of its arrival. Used for the free
 * teaser (the RPC itself is Pro); the risk always comes from the server.
 */
fun detectConnections(flights: Collection<Flight>, now: Instant? = null): List<Pair<Flight, Flight>> {
    val active = flights.filter { it.status != FlightStatus.CANCELLED }
    return active.mapNotNull { a ->
        val arrival = a.schedArr ?: a.estArr ?: return@mapNotNull null
        active.filter { b ->
            val dep = b.schedDep ?: b.estDep
            b.id != a.id && b.depIata == a.arrIata && dep != null && dep > arrival && dep <= arrival + CONNECTION_WINDOW
        }.minByOrNull { it.schedDep ?: it.estDep!! }?.let { a to it }
    }.filter { (_, out) ->
        // Like my_connections: a trip whose onward leg left more than 12 h ago is history, not worth a teaser.
        val outDep = out.bestDeparture
        now == null || outDep == null || outDep > now - CONNECTION_HISTORY
    }
}

private val CONNECTION_WINDOW = 24.hours
private val CONNECTION_HISTORY = 12.hours

/** The connections section of the flights list. */
sealed interface ConnectionsUiState {
    data object Hidden : ConnectionsUiState

    /** Free user with at least one connection among the tracked flights: the teaser. */
    data class Teaser(val count: Int) : ConnectionsUiState

    data class Ready(val connections: List<ConnectionUiModel>) : ConnectionsUiState
}

/** [answer] null = not asked yet; [now] drops stale trips from the free teaser. Missed connections stay listed (the user needs to rebook). */
fun connectionsState(answer: IntelResult<List<Connection>>?, flights: Collection<Flight>, now: Instant? = null): ConnectionsUiState {
    val zones = airportZones(flights)
    return when (answer) {
        is IntelResult.Ok -> if (answer.value.isEmpty()) ConnectionsUiState.Hidden else ConnectionsUiState.Ready(answer.value.map { it.toUi(zones::get) })
        IntelResult.Locked -> detectConnections(flights, now).size.takeIf { it > 0 }?.let { ConnectionsUiState.Teaser(it) } ?: ConnectionsUiState.Hidden
        else -> ConnectionsUiState.Hidden
    }
}

/** The connections that start or end with [flightId], for its detail screen. */
fun List<ConnectionUiModel>.forFlight(flightId: String): List<ConnectionUiModel> =
    filter { it.inFlightId == flightId || it.outFlightId == flightId }

// ---- B7 airport trends ----------------------------------------------------------------------------

/**
 * The trends chart.
 * @property hours 24 bars, one per local hour; null where there were no departures
 * @property worstHour the hour with the highest average delay (at least [MIN_HOUR_SAMPLES] legs)
 */
data class TrendsUiModel(
    val iata: String,
    val days: Int,
    val hours: List<HourTrend?>,
    val byDay: List<DayTrend>,
    val worstHour: HourTrend?,
    val bestHour: HourTrend?,
    val maxHourDelay: Int,
    val maxDayDelay: Int,
    val totalCancelled: Int,
) {
    companion object {
        /** Fewer legs than this make an hour too noisy to call it the worst or the best. */
        const val MIN_HOUR_SAMPLES = 3
    }
}

fun AirportTrends.toUi(): TrendsUiModel? {
    if (isEmpty) return null
    val byHourMap = byHour.associateBy { it.hour }
    val reliable = byHour.filter { it.samples >= TrendsUiModel.MIN_HOUR_SAMPLES }
    val worst = reliable.maxByOrNull { it.avgDepDelay }
    val best = reliable.minByOrNull { it.avgDepDelay }?.takeIf { it != worst }
    return TrendsUiModel(
        iata = iata,
        days = days,
        hours = (0..23).map { byHourMap[it] },
        byDay = byDay,
        worstHour = worst,
        bestHour = best,
        maxHourDelay = byHour.maxOfOrNull { it.avgDepDelay }?.coerceAtLeast(0) ?: 0,
        maxDayDelay = byDay.mapNotNull { it.avgDepDelay }.maxOrNull()?.coerceAtLeast(0) ?: 0,
        totalCancelled = byDay.sumOf { it.cancelled },
    )
}

/** Bar height 0..1 against [max]; early (negative) averages draw as 0. */
fun barFraction(value: Int?, max: Int): Float = if (value == null || max <= 0) 0f else (value.coerceAtLeast(0).toFloat() / max).coerceIn(0f, 1f)

/** "hi" for a Hindi phone, else English: the prediction labels exist in en, hi and hinglish. */
fun predictionLanguage(languageTag: String?): String = if (languageTag?.lowercase()?.startsWith("hi") == true) "hi" else "en"
