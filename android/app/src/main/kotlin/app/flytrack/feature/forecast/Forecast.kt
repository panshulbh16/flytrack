package app.flytrack.feature.forecast

import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Instant

/** `flight_forecast.forecast.confidence`. */
enum class ForecastConfidence(val wire: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromWire(value: String?): ForecastConfidence = entries.firstOrNull { it.wire == value } ?: LOW
    }
}

/** `forecast.method`: from past legs, or only from the timetable. */
enum class ForecastMethod(val wire: String) {
    HISTORY("history"), SCHEDULE("schedule");

    companion object {
        fun fromWire(value: String?): ForecastMethod = entries.firstOrNull { it.wire == value } ?: SCHEDULE
    }
}

/** On-time history of the flight (or its route) over the last [windowDays] days. */
data class ForecastHistory(
    val samples: Int,
    val windowDays: Int,
    /** Departures within 15 min of schedule, of flown legs; null when unknown. */
    val onTimePct: Int?,
    val avgDepDelayMin: Int?,
    val medianArrDelayMin: Int?,
    val cancelled: Int,
)

/** Likely arrival: half of past legs arrived by [p50], four in five by [p80]. */
data class ArrivalForecast(
    val p50: Instant,
    val p80: Instant?,
    val method: ForecastMethod,
    val confidence: ForecastConfidence,
)

/** Answer of `flight_forecast(p_flight_id)` (P2 wave 2, section 4). */
data class FlightForecast(
    val history: ForecastHistory?,
    val forecast: ArrivalForecast?,
    /** The caller is not Pro for this flight: [history] and [forecast] are null, show the teaser. */
    val detailLocked: Boolean,
) {
    /** Nothing worth a card (no history samples and no forecast). */
    val isEmpty: Boolean get() = !detailLocked && forecast == null && (history == null || history.samples == 0)
}

// ---- wire format ---------------------------------------------------------------------------------

private val ForecastJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class ForecastDto(
    val history: HistoryDto? = null,
    val forecast: ArrivalDto? = null,
    @SerialName("detail_locked") val detailLocked: Boolean = false,
)

@Serializable
private data class HistoryDto(
    val samples: Int = 0,
    @SerialName("window_days") val windowDays: Int = 60,
    @SerialName("on_time_pct") val onTimePct: Int? = null,
    @SerialName("avg_dep_delay_min") val avgDepDelayMin: Int? = null,
    @SerialName("median_arr_delay_min") val medianArrDelayMin: Int? = null,
    val cancelled: Int = 0,
)

@Serializable
private data class ArrivalDto(
    @SerialName("arr_p50") val p50: String? = null,
    @SerialName("arr_p80") val p80: String? = null,
    val method: String? = null,
    val confidence: String? = null,
)

/** Reads a `flight_forecast` answer; null for SQL null. Throws SerializationException on a wrong shape. */
fun parseFlightForecast(json: JsonElement): FlightForecast? {
    if (json is JsonNull) return null
    val dto = ForecastJson.decodeFromJsonElement(ForecastDto.serializer(), json)
    val history = dto.history?.let {
        ForecastHistory(it.samples, it.windowDays, it.onTimePct, it.avgDepDelayMin, it.medianArrDelayMin, it.cancelled)
    }
    val forecast = dto.forecast?.let { f ->
        val p50 = f.p50.toInstantOrNull() ?: return@let null
        ArrivalForecast(p50, f.p80.toInstantOrNull()?.takeIf { it >= p50 }, ForecastMethod.fromWire(f.method), ForecastConfidence.fromWire(f.confidence))
    }
    return if (dto.detailLocked) FlightForecast(null, null, true) else FlightForecast(history, forecast, false)
}

private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { Instant.parse(it) }.getOrNull() }

interface ForecastRepository {
    /** `flight_forecast(p_flight_id)`; Ok(null) when the server has nothing. Never throws. */
    suspend fun forecast(flightId: String): RemoteResult<FlightForecast?>
}

class SupabaseForecastRepository @Inject constructor(
    private val caller: Wave2Caller,
) : ForecastRepository {
    override suspend fun forecast(flightId: String): RemoteResult<FlightForecast?> = caller.call { api, _ ->
        parseFlightForecast(api.rpc(RPC_FLIGHT_FORECAST, buildJsonObject { put("p_flight_id", flightId) }))
    }

    private companion object {
        const val RPC_FLIGHT_FORECAST = "flight_forecast"
    }
}
