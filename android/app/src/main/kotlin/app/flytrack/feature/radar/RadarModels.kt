package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Radar (docs/P4_CONTRACTS.md section 1 and the notes in section 7). Everything in this file is pure
 * Kotlin and JVM-tested.
 */
object RadarContract {
    const val FUNCTION = "radar"
    const val MODE_GROUND = "ground"
    const val MODE_PROXIMITY = "proximity"

    /** The screen asks again this often while it is visible. */
    const val REFRESH_SECONDS = 10L

    /** After `rate_limited` (60 requests / 10 min) the screen waits longer before the next try. */
    const val RATE_LIMITED_BACKOFF_SECONDS = 60L

    /** The server rounds proximity coordinates to this grid; the app rounds first so the exact spot never leaves the phone. */
    const val GRID_DEG = 0.05

    const val GROUND_RADIUS_KM = 5
    const val PROXIMITY_RADIUS_KM = 75
}

/** What the radar is asked for. */
sealed interface RadarQuery {
    /** Aircraft on the ground within 5 km of an airport. */
    data class Ground(val iata: String) : RadarQuery

    /** Aircraft within 75 km of a point (already rounded with [roundToGrid]). */
    data class Proximity(val lat: Double, val lon: Double) : RadarQuery

    /** Query parameters of `GET /functions/v1/radar`. */
    fun parameters(): List<Pair<String, String>> = when (this) {
        is Ground -> listOf("mode" to RadarContract.MODE_GROUND, "iata" to iata.uppercase())
        is Proximity -> listOf(
            "mode" to RadarContract.MODE_PROXIMITY,
            "lat" to formatCoordinate(lat),
            "lon" to formatCoordinate(lon),
        )
    }

    companion object {
        /** A proximity query around [point], rounded to the 0.05° grid before it is sent anywhere. */
        fun around(point: GeoPoint): Proximity = Proximity(roundToGrid(point.lat), roundToGrid(point.lon))
    }
}

/** Rounds a coordinate to the nearest multiple of [RadarContract.GRID_DEG] (0.05°, about 5 km). */
fun roundToGrid(value: Double): Double {
    val steps = (value / RadarContract.GRID_DEG).roundToLong()
    // Through a decimal string, so 28.650000000000002 becomes 28.65.
    return "%.2f".format(java.util.Locale.ROOT, steps * RadarContract.GRID_DEG).toDouble()
}

private fun formatCoordinate(value: Double): String = "%.2f".format(java.util.Locale.ROOT, value)

/** One aircraft as the app uses it. */
data class RadarAircraft(
    val icao24: String,
    /** Trimmed; empty when the transponder sends none. */
    val callsign: String,
    val registration: String?,
    val position: GeoPoint,
    val altitudeFt: Int,
    val groundSpeedKts: Int,
    val trackDeg: Double,
    val onGround: Boolean,
    val verticalRateFpm: Int?,
    val seenAt: Instant?,
    /** Our flight designator ("6E2341") when the server matched the callsign. */
    val flight: String?,
)

/** One radar answer. */
data class RadarSnapshot(
    val center: GeoPoint,
    val radiusKm: Int,
    val aircraft: List<RadarAircraft>,
    val feed: String?,
    val asOf: Instant?,
)

/** Typed outcome of one radar request. */
sealed interface RadarResult {
    data class Ok(val snapshot: RadarSnapshot) : RadarResult

    /** `feed_unavailable` / `feed_unlicensed`: the friendly "Radar is coming soon" state, never an error. */
    data object ComingSoon : RadarResult

    /** `pro_required` (402). */
    data object ProRequired : RadarResult

    /** `rate_limited` (429): wait longer, keep what is on screen. */
    data object RateLimited : RadarResult

    /** `feed_error` (502): the feed failed this time; keep the screen and retry on the next tick. */
    data object FeedError : RadarResult

    /** `not_found` (404): unknown airport. */
    data object UnknownAirport : RadarResult

    data object Offline : RadarResult
    data object NotConfigured : RadarResult

    /** [message] is technical English: log it, never show it. */
    data class Failed(val message: String?) : RadarResult
}

// ---- wire format ---------------------------------------------------------------------------------------

@Serializable
internal data class RadarPointDto(val lat: Double, val lon: Double)

@Serializable
internal data class RadarAircraftDto(
    val icao24: String = "",
    val callsign: String? = null,
    val reg: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("alt_ft") val altFt: Double? = null,
    @SerialName("gs_kts") val gsKts: Double? = null,
    @SerialName("track_deg") val trackDeg: Double? = null,
    @SerialName("on_ground") val onGround: Boolean = false,
    @SerialName("vrate_fpm") val vrateFpm: Double? = null,
    @SerialName("seen_at") val seenAt: String? = null,
    val flight: String? = null,
)

@Serializable
internal data class RadarResponseDto(
    val center: RadarPointDto,
    @SerialName("radius_km") val radiusKm: Double = 0.0,
    val aircraft: List<RadarAircraftDto> = emptyList(),
    val feed: String? = null,
    @SerialName("as_of") val asOf: String? = null,
)

internal val RadarJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Parsing and error mapping of the `radar` function. */
object RadarParser {

    /** The 200 body; aircraft without a position are dropped. Throws [kotlinx.serialization.SerializationException] on a bad body. */
    fun parse(body: String): RadarSnapshot {
        val dto = RadarJson.decodeFromString(RadarResponseDto.serializer(), body)
        return RadarSnapshot(
            center = GeoPoint(dto.center.lat, dto.center.lon),
            radiusKm = dto.radiusKm.toInt(),
            aircraft = dto.aircraft.mapNotNull { it.toModel() },
            feed = dto.feed,
            asOf = dto.asOf?.let(::parseInstantOrNull),
        )
    }

    /** An error answer (status and the body's `error` field) as a typed result. */
    fun error(status: Int, code: String?): RadarResult = when (code) {
        "feed_unavailable", "feed_unlicensed" -> RadarResult.ComingSoon
        "pro_required" -> RadarResult.ProRequired
        "rate_limited" -> RadarResult.RateLimited
        "feed_error" -> RadarResult.FeedError
        "not_found" -> RadarResult.UnknownAirport
        // The function is not deployed yet, or the backend has no feed configured at all.
        "not_configured" -> RadarResult.ComingSoon
        else -> when (status) {
            402 -> RadarResult.ProRequired
            429 -> RadarResult.RateLimited
            502 -> RadarResult.FeedError
            503 -> RadarResult.ComingSoon
            404 -> if (code == null) RadarResult.ComingSoon else RadarResult.UnknownAirport
            else -> RadarResult.Failed("radar: HTTP $status ${code.orEmpty()}")
        }
    }

    private fun RadarAircraftDto.toModel(): RadarAircraft? {
        val lat = lat ?: return null
        val lon = lon ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return RadarAircraft(
            icao24 = icao24.trim().lowercase(),
            callsign = callsign.orEmpty().trim(),
            registration = reg?.trim()?.ifEmpty { null },
            position = GeoPoint(lat, lon),
            altitudeFt = if (onGround) 0 else (altFt ?: 0.0).toInt().coerceAtLeast(0),
            groundSpeedKts = (gsKts ?: 0.0).toInt().coerceAtLeast(0),
            trackDeg = normalizeBearing(trackDeg ?: 0.0),
            onGround = onGround,
            verticalRateFpm = vrateFpm?.toInt(),
            seenAt = seenAt?.let(::parseInstantOrNull),
            flight = flight?.trim()?.ifEmpty { null },
        )
    }
}

internal fun parseInstantOrNull(text: String): Instant? = try {
    Instant.parse(text)
} catch (e: IllegalArgumentException) {
    null
}

/** 0 ≤ bearing < 360. */
fun normalizeBearing(deg: Double): Double {
    if (!deg.isFinite()) return 0.0
    val m = deg % 360.0
    return if (m < 0) m + 360.0 else m
}
