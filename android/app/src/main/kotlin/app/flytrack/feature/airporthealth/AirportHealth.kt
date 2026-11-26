package app.flytrack.feature.airporthealth

import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatusTone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** `get_airport_health.level` (P2 contract, section 1), mildest first. */
enum class HealthLevel(val wire: String) {
    NORMAL("normal"),
    BUSY("busy"),
    DISRUPTED("disrupted"),
    SEVERE("severe");

    /** Theme tone of the level pill; the level word always goes with it, so colour is never the only cue. */
    val tone: FlightStatusTone
        get() = when (this) {
            NORMAL -> FlightStatusTone.ON_TIME
            BUSY, DISRUPTED -> FlightStatusTone.DELAYED
            SEVERE -> FlightStatusTone.CANCELLED
        }

    companion object {
        /** Null for a level this app version does not know. */
        fun fromWire(value: String?): HealthLevel? = entries.firstOrNull { it.wire == value }
    }
}

/** METAR flight category. */
enum class FlightCategory { VFR, MVFR, IFR, LIFR }

data class AirportWeather(
    val visibilityM: Int?,
    val category: FlightCategory?,
    val fog: Boolean,
    val windKts: Int?,
    /** Present weather, e.g. "FG", "BR", "HZ", "TSRA". */
    val wx: String?,
    val observedAt: Instant?,
)

/** The Pro part of the answer; null for free users. */
data class HealthDetail(
    val avgDepDelayMin: Int?,
    val delayedPct: Int?,
    val cancelledToday: Int,
    val sampleSize: Int,
    val weather: AirportWeather?,
) {
    /** The server applies delay rules only from this many departures in the last 3 h. */
    val hasDelaySample: Boolean get() = sampleSize >= MIN_DELAY_SAMPLE

    companion object {
        const val MIN_DELAY_SAMPLE = 8
    }
}

data class AirportHealth(
    val iata: String,
    val level: HealthLevel,
    /** The server's English line; the app shows [HealthHeadline] localised and falls back to this. */
    val headline: String,
    val updatedAt: Instant?,
    val detail: HealthDetail?,
)

// ---- wire format -----------------------------------------------------------------------------------

@Serializable
private data class HealthDto(
    val iata: String? = null,
    val level: String? = null,
    val headline: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val detail: DetailDto? = null,
)

@Serializable
private data class DetailDto(
    @SerialName("avg_dep_delay_min") val avgDepDelayMin: Int? = null,
    @SerialName("delayed_pct") val delayedPct: Int? = null,
    @SerialName("cancelled_today") val cancelledToday: Int? = null,
    @SerialName("sample_size") val sampleSize: Int? = null,
    val weather: WeatherDto? = null,
)

@Serializable
private data class WeatherDto(
    @SerialName("visibility_m") val visibilityM: Int? = null,
    val category: String? = null,
    val fog: Boolean? = null,
    @SerialName("wind_kts") val windKts: Int? = null,
    val wx: String? = null,
    @SerialName("observed_at") val observedAt: String? = null,
)

private val HealthJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Reads a `get_airport_health` answer. Null when the level is missing or unknown to this app version
 * (the card is then left out rather than guessing). Throws [kotlinx.serialization.SerializationException]
 * when the shape is wrong.
 */
fun parseAirportHealth(json: JsonObject, requestedIata: String): AirportHealth? {
    val dto = HealthJson.decodeFromJsonElement(HealthDto.serializer(), json)
    val level = HealthLevel.fromWire(dto.level) ?: return null
    return AirportHealth(
        iata = dto.iata?.takeIf { it.isNotBlank() } ?: requestedIata,
        level = level,
        headline = dto.headline.orEmpty(),
        updatedAt = dto.updatedAt.toInstantOrNull(),
        detail = dto.detail?.let { d ->
            HealthDetail(
                avgDepDelayMin = d.avgDepDelayMin,
                delayedPct = d.delayedPct,
                cancelledToday = d.cancelledToday ?: 0,
                sampleSize = d.sampleSize ?: 0,
                weather = d.weather?.let { w ->
                    AirportWeather(
                        visibilityM = w.visibilityM,
                        category = FlightCategory.entries.firstOrNull { it.name == w.category },
                        fog = w.fog == true,
                        windKts = w.windKts,
                        wx = w.wx?.takeIf { it.isNotBlank() },
                        observedAt = w.observedAt.toInstantOrNull(),
                    )
                },
            )
        },
    )
}

private fun String?.toInstantOrNull(): Instant? = this?.let {
    try {
        // Postgres jsonb timestamps: "2026-11-26T05:30:00+00:00" or with fractional seconds.
        Instant.parse(it)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// ---- localised headline ------------------------------------------------------------------------

/**
 * The server's `headline` as a typed value, so the app can show it in the UI language. The server
 * writes a small, fixed set of English sentences (migration 13, `get_airport_health`); anything else
 * becomes [Other] and is shown as sent.
 */
sealed interface HealthHeadline {
    data object Normal : HealthHeadline
    data class Fog(val visibilityM: Int?) : HealthHeadline
    data object Thunderstorms : HealthHeadline
    data class LowVisibility(val visibilityM: Int) : HealthHeadline
    data class LowCloud(val ceilingFt: Int) : HealthHeadline
    data object PoorWeather : HealthHeadline
    data class Cancellations(val count: Int) : HealthHeadline
    data class RunningLate(val minutes: Int) : HealthHeadline
    data class DelayedShare(val percent: Int) : HealthHeadline
    data class Other(val text: String) : HealthHeadline
}

private val FOG_VIS = Regex("""^Fog: visibility (\d+) m$""")
private val LOW_VIS = Regex("""^Low visibility: (\d+) m$""")
private val LOW_CLOUD = Regex("""^Low cloud: (\d+) ft$""")
private val CANCELLED = Regex("""^(\d+) flights cancelled today$""")
private val LATE = Regex("""^Departures running (\d+) min late$""")
private val DELAYED_PCT = Regex("""^(\d+)% of departures delayed$""")

/**
 * Typed form of [AirportHealth.headline]. When the server's sentence is unknown, the Pro weather
 * fields (when present) still give a localised line before falling back to the English text.
 */
fun AirportHealth.typedHeadline(): HealthHeadline {
    val text = headline.trim()
    fun int(regex: Regex): Int? = regex.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
    int(FOG_VIS)?.let { return HealthHeadline.Fog(it) }
    int(LOW_VIS)?.let { return HealthHeadline.LowVisibility(it) }
    int(LOW_CLOUD)?.let { return HealthHeadline.LowCloud(it) }
    int(CANCELLED)?.let { return HealthHeadline.Cancellations(it) }
    int(LATE)?.let { return HealthHeadline.RunningLate(it) }
    int(DELAYED_PCT)?.let { return HealthHeadline.DelayedShare(it) }
    when (text) {
        "Operating normally" -> return HealthHeadline.Normal
        "Fog" -> return HealthHeadline.Fog(null)
        "Thunderstorms at the airport" -> return HealthHeadline.Thunderstorms
        "Poor flying weather" -> return HealthHeadline.PoorWeather
    }
    val weather = detail?.weather
    return when {
        weather?.fog == true -> HealthHeadline.Fog(weather.visibilityM)
        weather?.wx?.contains("TS") == true -> HealthHeadline.Thunderstorms
        text.isEmpty() && level == HealthLevel.NORMAL -> HealthHeadline.Normal
        else -> HealthHeadline.Other(text)
    }
}

// ---- which airports the flight screen shows ----------------------------------------------------

enum class HealthSide { DEPARTURE, ARRIVAL }

/**
 * Airports whose conditions still matter: both before take-off, only the destination in the air,
 * none once the flight has landed or was cancelled.
 */
fun healthSides(phase: FlightPhase): List<HealthSide> = when (phase) {
    FlightPhase.UPCOMING, FlightPhase.CHECK_IN, FlightPhase.BOARDING -> listOf(HealthSide.DEPARTURE, HealthSide.ARRIVAL)
    FlightPhase.IN_AIR, FlightPhase.DIVERTED -> listOf(HealthSide.ARRIVAL)
    FlightPhase.LANDED, FlightPhase.CANCELLED -> emptyList()
}

// ---- METAR present weather -----------------------------------------------------------------------

/** The present-weather groups the card names in words, most important first. */
enum class PresentWeather { THUNDERSTORM, FOG, SMOKE, DUST, HAZE, MIST, RAIN }

/**
 * Main phenomenon of a METAR `wxString` such as "FG", "-RA BR", "TSRA", "HZ". Null when empty or none
 * of [PresentWeather] (the raw string is then shown as it is).
 */
fun presentWeather(wx: String?): PresentWeather? {
    val groups = wx?.uppercase()?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    if (groups.isEmpty()) return null
    fun has(code: String) = groups.any { it.contains(code) }
    return when {
        has("TS") -> PresentWeather.THUNDERSTORM
        // "FG" includes MIFG/BCFG/PRFG/FZFG; "VCFG" (in the vicinity) still reads as fog for travellers.
        has("FG") -> PresentWeather.FOG
        has("FU") -> PresentWeather.SMOKE
        has("DU") || has("SA") || has("DS") || has("SS") -> PresentWeather.DUST
        has("HZ") -> PresentWeather.HAZE
        has("BR") -> PresentWeather.MIST
        has("RA") || has("DZ") || has("SH") -> PresentWeather.RAIN
        else -> null
    }
}
