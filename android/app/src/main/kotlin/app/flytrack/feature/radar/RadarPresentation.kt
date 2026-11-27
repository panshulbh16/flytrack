package app.flytrack.feature.radar

import app.flytrack.core.estimate.GeoPoint
import app.flytrack.core.estimate.GreatCircle
import app.flytrack.core.flightnumber.parseFlightNumber
import kotlin.math.roundToInt

/** How an aircraft's altitude is shown. */
sealed interface AltitudeLabel {
    data object OnGround : AltitudeLabel

    /** Rounded to 100 ft. */
    data class Feet(val feet: Int) : AltitudeLabel
}

/** Climbing, level or descending, from the vertical rate. */
enum class VerticalTrend { CLIMBING, LEVEL, DESCENDING, UNKNOWN }

/** One row of the list under the map, and one plane on it. */
data class RadarAircraftUiModel(
    val key: String,
    /** "6E 2341" when matched to a flight, else the callsign, else the registration, else the ICAO hex. */
    val label: String,
    /** The callsign when [label] is something else (a matched flight shows its callsign underneath). */
    val callsign: String?,
    val registration: String?,
    val altitude: AltitudeLabel,
    val speedKts: Int,
    val trackDeg: Double,
    val trend: VerticalTrend,
    val distanceKm: Double,
    val position: GeoPoint,
    val onGround: Boolean,
    /** Our flight designator without the space ("6E2341") when matched. */
    val flight: String?,
)

object RadarPresentation {
    /** Vertical rates within ±this count as level flight. */
    private const val LEVEL_FPM = 300

    fun label(aircraft: RadarAircraft): String {
        aircraft.flight?.let { flight -> return parseFlightNumber(flight)?.display ?: flight }
        if (aircraft.callsign.isNotEmpty()) return aircraft.callsign
        aircraft.registration?.let { return it }
        return aircraft.icao24.uppercase()
    }

    fun altitude(aircraft: RadarAircraft): AltitudeLabel =
        if (aircraft.onGround) AltitudeLabel.OnGround else AltitudeLabel.Feet(((aircraft.altitudeFt / 100.0).roundToInt()) * 100)

    fun trend(aircraft: RadarAircraft): VerticalTrend {
        if (aircraft.onGround) return VerticalTrend.LEVEL
        val rate = aircraft.verticalRateFpm ?: return VerticalTrend.UNKNOWN
        return when {
            rate > LEVEL_FPM -> VerticalTrend.CLIMBING
            rate < -LEVEL_FPM -> VerticalTrend.DESCENDING
            else -> VerticalTrend.LEVEL
        }
    }

    /** The list: nearest first, then by label so equal distances do not jump around between refreshes. */
    fun rows(snapshot: RadarSnapshot): List<RadarAircraftUiModel> = snapshot.aircraft
        .distinctBy { it.icao24.ifEmpty { "${it.position.lat},${it.position.lon}" } }
        .map { aircraft ->
            val label = label(aircraft)
            RadarAircraftUiModel(
                key = aircraft.icao24.ifEmpty { label },
                label = label,
                callsign = aircraft.callsign.takeIf { it.isNotEmpty() && it != label },
                registration = aircraft.registration,
                altitude = altitude(aircraft),
                speedKts = aircraft.groundSpeedKts,
                trackDeg = aircraft.trackDeg,
                trend = trend(aircraft),
                distanceKm = GreatCircle.distanceKm(snapshot.center, aircraft.position),
                position = aircraft.position,
                onGround = aircraft.onGround,
                flight = aircraft.flight,
            )
        }
        .sortedWith(compareBy<RadarAircraftUiModel> { it.distanceKm }.thenBy { it.label })

    /** "Updated 8 s ago" granularity: whole seconds since the answer, never negative. */
    fun ageSeconds(asOfEpochMs: Long?, nowEpochMs: Long): Long? = asOfEpochMs?.let { ((nowEpochMs - it) / 1000).coerceAtLeast(0) }
}

/**
 * When the next request goes out. The screen refreshes every 10 s while visible; a rate-limited answer
 * waits a minute, so a user who keeps the screen open never burns through the quota.
 */
object RadarRefreshPolicy {
    fun nextDelaySeconds(last: RadarResult): Long? = when (last) {
        is RadarResult.Ok, RadarResult.FeedError, RadarResult.Offline, is RadarResult.Failed -> RadarContract.REFRESH_SECONDS
        RadarResult.RateLimited -> RadarContract.RATE_LIMITED_BACKOFF_SECONDS
        // Nothing will change by asking again: stop until the user does something.
        RadarResult.ComingSoon, RadarResult.ProRequired, RadarResult.UnknownAirport, RadarResult.NotConfigured -> null
    }
}
