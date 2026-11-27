package app.flytrack.feature.intel

import app.flytrack.data.remote.FlyTrackJson
import app.flytrack.data.remote.parseInstantOrNull
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

// Wire format of the P3 intelligence RPCs. Every parser is lenient: an unknown key or a missing optional
// field never fails the whole answer, and rows that cannot be used (unknown timeline key, connection
// without ids) are dropped. A JSON value of the wrong kind throws IllegalArgumentException /
// SerializationException, which the repository turns into a typed failure.

@Serializable
private data class TimelineDetailDto(
    val gate: String? = null,
    val terminal: String? = null,
    val belt: String? = null,
    @SerialName("delay_min") val delayMin: Int? = null,
    @SerialName("taxi_out_min") val taxiOutMin: Int? = null,
    @SerialName("taxi_in_min") val taxiInMin: Int? = null,
    @SerialName("closes_at") val closesAt: String? = null,
    @SerialName("sched_dep") val schedDep: String? = null,
    @SerialName("sched_arr") val schedArr: String? = null,
    @SerialName("from_arr") val fromArr: String? = null,
    @SerialName("to_arr") val toArr: String? = null,
)

@Serializable
private data class TimelineStepDto(
    val key: String? = null,
    val at: String? = null,
    val state: String? = null,
    val detail: TimelineDetailDto? = null,
)

/** `flight_timeline`: the ordered list; null or a non-array answer is an empty timeline. */
fun parseTimeline(element: JsonElement, json: Json = FlyTrackJson): List<TimelineStep> {
    if (element !is JsonArray) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(TimelineStepDto.serializer()), element).mapNotNull { dto ->
        val key = TimelineKey.fromWire(dto.key) ?: return@mapNotNull null
        TimelineStep(
            key = key,
            at = parseInstantOrNull(dto.at),
            state = TimelineState.fromWire(dto.state),
            detail = dto.detail?.let { d ->
                TimelineDetail(
                    gate = d.gate.clean(),
                    terminal = d.terminal.clean(),
                    belt = d.belt.clean(),
                    delayMin = d.delayMin,
                    taxiOutMin = d.taxiOutMin?.takeIf { it >= 0 },
                    taxiInMin = d.taxiInMin?.takeIf { it >= 0 },
                    closesAt = parseInstantOrNull(d.closesAt),
                    schedDep = parseInstantOrNull(d.schedDep),
                    schedArr = parseInstantOrNull(d.schedArr),
                    fromArr = d.fromArr.clean(),
                    toArr = d.toArr.clean(),
                )
            },
        )
    }
}

@Serializable
private data class FactorDto(
    val key: String? = null,
    @SerialName("effect_min") val effectMin: Double? = null,
    val label: String? = null,
)

@Serializable
private data class DelayPredictionDto(
    @SerialName("p_delay_15") val pDelay15: Double? = null,
    @SerialName("p_delay_60") val pDelay60: Double? = null,
    @SerialName("expected_delay_min") val expectedDelayMin: Double? = null,
    val factors: List<FactorDto> = emptyList(),
    val confidence: String? = null,
    val samples: Int? = null,
    val model: String? = null,
    @SerialName("computed_at") val computedAt: String? = null,
)

/** `delay_prediction`: null outside the 72 h window (or for a cancelled / departed flight). */
fun parseDelayPrediction(element: JsonElement, json: Json = FlyTrackJson): DelayPrediction? {
    if (element !is JsonObject) return null
    val dto = json.decodeFromJsonElement(DelayPredictionDto.serializer(), element)
    val p15 = dto.pDelay15 ?: return null
    val p60 = (dto.pDelay60 ?: 0.0).coerceIn(0.0, 1.0)
    return DelayPrediction(
        pDelay15 = p15.coerceIn(0.0, 1.0),
        // The server keeps p60 <= p15; keep the card consistent even if it did not.
        pDelay60 = minOf(p60, p15.coerceIn(0.0, 1.0)),
        expectedDelayMin = (dto.expectedDelayMin ?: 0.0).roundToInt().coerceAtLeast(0),
        factors = dto.factors.mapNotNull { f ->
            val label = f.label.clean() ?: return@mapNotNull null
            PredictionFactor(key = f.key.clean() ?: "other", effectMin = (f.effectMin ?: 0.0).roundToInt(), label = label)
        },
        confidence = PredictionConfidence.fromWire(dto.confidence),
        samples = dto.samples?.takeIf { it >= 0 },
        model = dto.model.clean() ?: "rules-v1",
        computedAt = parseInstantOrNull(dto.computedAt),
    )
}

@Serializable
private data class GateAlternativeDto(val gate: String? = null, val share: Double? = null)

@Serializable
private data class GatePredictionDto(
    val gate: String? = null,
    val terminal: String? = null,
    val share: Double? = null,
    val count: Int? = null,
    val samples: Int? = null,
    val alternatives: List<GateAlternativeDto> = emptyList(),
)

/** `gate_prediction`: null below 4 samples. */
fun parseGatePrediction(element: JsonElement, json: Json = FlyTrackJson): GatePrediction? {
    if (element !is JsonObject) return null
    val dto = json.decodeFromJsonElement(GatePredictionDto.serializer(), element)
    val gate = dto.gate.clean() ?: return null
    val samples = dto.samples ?: return null
    val share = (dto.share ?: 0.0).coerceIn(0.0, 1.0)
    return GatePrediction(
        gate = gate,
        terminal = dto.terminal.clean(),
        share = share,
        // Older answers carried only the share: derive the count from it.
        count = dto.count ?: (share * samples).roundToInt(),
        samples = samples,
        alternatives = dto.alternatives.mapNotNull { a -> a.gate.clean()?.let { GateAlternative(it, (a.share ?: 0.0).coerceIn(0.0, 1.0)) } },
    )
}

@Serializable
private data class TaxiTimeDto(val p50: Int? = null, val p80: Int? = null, val samples: Int? = null)

@Serializable
private data class TaxiStatsDto(
    val iata: String? = null,
    @SerialName("taxi_out") val taxiOut: TaxiTimeDto? = null,
    @SerialName("taxi_in") val taxiIn: TaxiTimeDto? = null,
)

/** `taxi_stats`; [requestedIata] names the airport when the answer does not. */
fun parseTaxiStats(element: JsonElement, requestedIata: String, json: Json = FlyTrackJson): TaxiStats? {
    if (element !is JsonObject) return null
    val dto = json.decodeFromJsonElement(TaxiStatsDto.serializer(), element)
    fun TaxiTimeDto.toModel() = TaxiTime(p50 = p50, p80 = p80, samples = samples ?: 0)
    return TaxiStats(iata = dto.iata.clean() ?: requestedIata, taxiOut = dto.taxiOut?.toModel(), taxiIn = dto.taxiIn?.toModel())
}

@Serializable
private data class ConnectionDto(
    @SerialName("in_flight_id") val inFlightId: String? = null,
    @SerialName("out_flight_id") val outFlightId: String? = null,
    val airport: String? = null,
    @SerialName("in_flight") val inFlight: String? = null,
    @SerialName("out_flight") val outFlight: String? = null,
    @SerialName("in_arr") val inArr: String? = null,
    @SerialName("out_dep") val outDep: String? = null,
    @SerialName("layover_min") val layoverMin: Int? = null,
    @SerialName("mct_min") val mctMin: Int? = null,
    @SerialName("buffer_min") val bufferMin: Int? = null,
    val risk: String? = null,
    @SerialName("terminal_change") val terminalChange: String? = null,
    @SerialName("transfer_note") val transferNote: String? = null,
)

/** `my_connections`: ordered by departure of the onward flight. */
fun parseConnections(element: JsonElement, json: Json = FlyTrackJson): List<Connection> {
    if (element !is JsonArray) return emptyList()
    return json.decodeFromJsonElement(ListSerializer(ConnectionDto.serializer()), element).mapNotNull { dto ->
        val inId = dto.inFlightId.clean() ?: return@mapNotNull null
        val outId = dto.outFlightId.clean() ?: return@mapNotNull null
        val layover = dto.layoverMin ?: return@mapNotNull null
        val mct = dto.mctMin ?: return@mapNotNull null
        val buffer = dto.bufferMin ?: (layover - mct)
        Connection(
            inFlightId = inId,
            outFlightId = outId,
            airport = dto.airport.clean().orEmpty(),
            inFlight = dto.inFlight.clean(),
            outFlight = dto.outFlight.clean(),
            inArrival = parseInstantOrNull(dto.inArr),
            outDeparture = parseInstantOrNull(dto.outDep),
            layoverMin = layover,
            mctMin = mct,
            bufferMin = buffer,
            risk = dto.risk?.let { ConnectionRisk.fromWire(it) } ?: ConnectionRisk.forBuffer(buffer),
            terminalChange = dto.terminalChange.clean(),
            transferNote = dto.transferNote.clean(),
        )
    }
}

@Serializable
private data class HourTrendDto(
    val hour: Int? = null,
    @SerialName("avg_dep_delay") val avgDepDelay: Int? = null,
    @SerialName("delayed_pct") val delayedPct: Int? = null,
    val samples: Int? = null,
)

@Serializable
private data class DayTrendDto(
    val date: String? = null,
    @SerialName("avg_dep_delay") val avgDepDelay: Int? = null,
    val cancelled: Int? = null,
    val samples: Int? = null,
)

@Serializable
private data class AirportTrendsDto(
    val iata: String? = null,
    val days: Int? = null,
    @SerialName("by_hour") val byHour: List<HourTrendDto> = emptyList(),
    @SerialName("by_day") val byDay: List<DayTrendDto> = emptyList(),
)

/** `airport_trends`: null for an airport the backend does not know. Hours are sorted 0..23, days oldest first. */
fun parseAirportTrends(element: JsonElement, requestedIata: String, json: Json = FlyTrackJson): AirportTrends? {
    if (element !is JsonObject) return null
    val dto = json.decodeFromJsonElement(AirportTrendsDto.serializer(), element)
    return AirportTrends(
        iata = dto.iata.clean() ?: requestedIata,
        days = dto.days ?: DEFAULT_TREND_DAYS,
        byHour = dto.byHour.mapNotNull { h ->
            val hour = h.hour?.takeIf { it in 0..23 } ?: return@mapNotNull null
            HourTrend(hour, avgDepDelay = h.avgDepDelay ?: 0, delayedPct = (h.delayedPct ?: 0).coerceIn(0, 100), samples = h.samples ?: 0)
        }.sortedBy { it.hour },
        byDay = dto.byDay.mapNotNull { d ->
            val date = d.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            DayTrend(date, avgDepDelay = d.avgDepDelay, cancelled = d.cancelled ?: 0, samples = d.samples ?: 0)
        }.sortedBy { it.date },
    )
}

const val DEFAULT_TREND_DAYS = 14

@Serializable
private data class AircraftColumnsDto(
    @SerialName("aircraft_icao_type") val icaoType: String? = null,
    @SerialName("aircraft_age_years") val ageYears: Double? = null,
)

@Serializable
private data class SwapPayloadDto(
    @SerialName("old_reg") val oldReg: String? = null,
    @SerialName("new_reg") val newReg: String? = null,
    @SerialName("old_model") val oldModel: String? = null,
    @SerialName("new_model") val newModel: String? = null,
)

@Serializable
private data class SwapEventDto(
    val payload: SwapPayloadDto? = null,
    @SerialName("old_value") val oldValue: String? = null,
    @SerialName("new_value") val newValue: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * The aircraft columns of one `flight_view` row ([columns], null when the row is not readable) and the
 * flight's AIRCRAFT_SWAP rows of `flight_events` ([swapEvents]).
 */
fun parseAircraftInfo(columns: JsonElement, swapEvents: JsonElement, json: Json = FlyTrackJson): AircraftInfo {
    val dto = (columns as? JsonObject)?.let { json.decodeFromJsonElement(AircraftColumnsDto.serializer(), it) }
    val swaps = (swapEvents as? JsonArray)?.let { json.decodeFromJsonElement(ListSerializer(SwapEventDto.serializer()), it) }.orEmpty()
        .map { e ->
            AircraftSwap(
                oldReg = e.payload?.oldReg.clean() ?: e.oldValue.clean(),
                newReg = e.payload?.newReg.clean() ?: e.newValue.clean(),
                oldModel = e.payload?.oldModel.clean(),
                newModel = e.payload?.newModel.clean(),
                at = parseInstantOrNull(e.createdAt),
            )
        }
        .sortedBy { it.at }
    return AircraftInfo(
        icaoType = dto?.icaoType.clean(),
        ageYears = dto?.ageYears?.takeIf { it >= 0.0 },
        swaps = swaps,
    )
}

private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
