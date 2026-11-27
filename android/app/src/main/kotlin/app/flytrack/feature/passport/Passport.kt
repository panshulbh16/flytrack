package app.flytrack.feature.passport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** What `my_passport` counted (P2 contract, section 2). */
enum class PassportScope(val wire: String) {
    /** Pro, all time. */
    ALL("all"),

    /** Free users without a year: the last 12 months. */
    LAST_12_MONTHS("last12"),

    /** One calendar year (Year in Review). */
    YEAR("year");

    companion object {
        fun fromWire(value: String?): PassportScope = entries.firstOrNull { it.wire == value } ?: LAST_12_MONTHS
    }
}

data class CountedAirport(val iata: String, val count: Int)
data class CountedAirline(val carrier: String, val name: String?, val count: Int)
data class CountedAircraft(val model: String, val count: Int)
data class LongestFlight(val flightId: String?, val carrier: String, val number: String, val dep: String, val arr: String, val distanceKm: Int)
data class TopRoute(val dep: String, val arr: String, val count: Int)
data class WorstDelay(val carrier: String, val number: String, val depDelayMin: Int)
data class DelayStats(val totalMin: Int, val onTimePct: Int?, val worst: WorstDelay?)

/** One bar of the by-month chart. [month] is "2026-01". */
data class MonthCount(val month: String, val flights: Int) {
    /** 1..12, or null when [month] is not "yyyy-MM". */
    val monthNumber: Int? get() = month.substringAfter('-', "").toIntOrNull()?.takeIf { it in 1..12 }
    val year: Int? get() = month.substringBefore('-').toIntOrNull()
}

/** The caller's passport, as `my_passport` returns it. Lists are already sorted by the server (most first). */
data class Passport(
    val scope: PassportScope,
    val year: Int,
    /** A free user whose answer was cut to the last 12 months: show the Pro upsell. */
    val proLimited: Boolean,
    val flights: Int,
    val distanceKm: Int,
    val airborneMinutes: Int,
    val airports: List<CountedAirport>,
    val countries: List<String>,
    val airlines: List<CountedAirline>,
    val aircraft: List<CountedAircraft>,
    val longest: LongestFlight?,
    val topRoute: TopRoute?,
    val delay: DelayStats,
    /** Economy, per passenger, distance-based: always shown as an estimate. */
    val co2Kg: Int,
    val co2Method: String,
    val byMonth: List<MonthCount>,
    /** ISO date "2025-12-03", null when there are no flights. */
    val firstFlightDate: String?,
    /** P3 B12: off-block to on-block minutes (crew mode shows block hours); 0 when the server has none. */
    val blockMinutes: Int = 0,
) {
    val isEmpty: Boolean get() = flights == 0
}

// ---- wire format -----------------------------------------------------------------------------------

@Serializable
private data class PassportDto(
    val scope: String? = null,
    val year: Int? = null,
    @SerialName("pro_limited") val proLimited: Boolean = false,
    val flights: Int = 0,
    @SerialName("distance_km") val distanceKm: Int = 0,
    @SerialName("airborne_minutes") val airborneMinutes: Int = 0,
    val airports: List<AirportCountDto>? = null,
    val countries: List<String>? = null,
    val airlines: List<AirlineCountDto>? = null,
    val aircraft: List<AircraftCountDto>? = null,
    val longest: LongestDto? = null,
    @SerialName("top_route") val topRoute: TopRouteDto? = null,
    val delay: DelayDto? = null,
    @SerialName("co2_kg") val co2Kg: Int = 0,
    @SerialName("co2_method") val co2Method: String? = null,
    @SerialName("by_month") val byMonth: List<MonthDto>? = null,
    @SerialName("first_flight_date") val firstFlightDate: String? = null,
    @SerialName("block_minutes") val blockMinutes: Int = 0,
)

@Serializable private data class AirportCountDto(val iata: String? = null, val count: Int = 0)
@Serializable private data class AirlineCountDto(val carrier: String? = null, val name: String? = null, val count: Int = 0)
@Serializable private data class AircraftCountDto(val model: String? = null, val count: Int = 0)

@Serializable
private data class LongestDto(
    @SerialName("flight_id") val flightId: String? = null,
    val carrier: String? = null,
    val number: String? = null,
    val dep: String? = null,
    val arr: String? = null,
    @SerialName("distance_km") val distanceKm: Int? = null,
)

@Serializable private data class TopRouteDto(val dep: String? = null, val arr: String? = null, val count: Int = 0)

@Serializable
private data class DelayDto(
    @SerialName("total_min") val totalMin: Int = 0,
    @SerialName("on_time_pct") val onTimePct: Int? = null,
    val worst: WorstDto? = null,
)

@Serializable
private data class WorstDto(
    val carrier: String? = null,
    val number: String? = null,
    @SerialName("dep_delay_min") val depDelayMin: Int? = null,
)

@Serializable private data class MonthDto(val month: String? = null, val flights: Int = 0)

private val PassportJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Reads a `my_passport` answer. Entries the app cannot use (no IATA code, no model) are dropped instead
 * of failing the whole screen. Throws [kotlinx.serialization.SerializationException] when the shape is wrong.
 */
fun parsePassport(json: JsonObject, fallbackYear: Int): Passport {
    val dto = PassportJson.decodeFromJsonElement(PassportDto.serializer(), json)
    return Passport(
        scope = PassportScope.fromWire(dto.scope),
        year = dto.year ?: fallbackYear,
        proLimited = dto.proLimited,
        flights = dto.flights.coerceAtLeast(0),
        distanceKm = dto.distanceKm.coerceAtLeast(0),
        airborneMinutes = dto.airborneMinutes.coerceAtLeast(0),
        airports = dto.airports.orEmpty().mapNotNull { a -> a.iata?.takeIf { it.isNotBlank() }?.let { CountedAirport(it, a.count) } },
        countries = dto.countries.orEmpty().filter { it.isNotBlank() },
        airlines = dto.airlines.orEmpty().mapNotNull { a ->
            a.carrier?.takeIf { it.isNotBlank() }?.let { CountedAirline(it, a.name?.takeIf { n -> n.isNotBlank() }, a.count) }
        },
        aircraft = dto.aircraft.orEmpty().mapNotNull { a -> a.model?.takeIf { it.isNotBlank() }?.let { CountedAircraft(it, a.count) } },
        longest = dto.longest?.let { l ->
            if (l.carrier == null || l.number == null || l.dep == null || l.arr == null || l.distanceKm == null) {
                null
            } else {
                LongestFlight(l.flightId, l.carrier, l.number, l.dep, l.arr, l.distanceKm)
            }
        },
        topRoute = dto.topRoute?.let { r -> if (r.dep == null || r.arr == null) null else TopRoute(r.dep, r.arr, r.count) },
        delay = DelayStats(
            totalMin = dto.delay?.totalMin?.coerceAtLeast(0) ?: 0,
            onTimePct = dto.delay?.onTimePct?.coerceIn(0, 100),
            worst = dto.delay?.worst?.let { w ->
                if (w.carrier == null || w.number == null || w.depDelayMin == null) null else WorstDelay(w.carrier, w.number, w.depDelayMin)
            },
        ),
        co2Kg = dto.co2Kg.coerceAtLeast(0),
        co2Method = dto.co2Method ?: CO2_METHOD_V1,
        byMonth = dto.byMonth.orEmpty().mapNotNull { m -> m.month?.let { MonthCount(it, m.flights) } },
        firstFlightDate = dto.firstFlightDate,
        blockMinutes = dto.blockMinutes.coerceAtLeast(0),
    )
}

/** `co2_method` of the distance-band estimate in the contract. */
const val CO2_METHOD_V1 = "estimate-v1"
