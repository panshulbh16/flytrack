package app.flytrack.feature.leave

import app.flytrack.core.model.Airport
import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.hasDeparted
import app.flytrack.core.time.zoneOf
import app.flytrack.feature.airporthealth.AirportHealth
import app.flytrack.feature.airporthealth.HealthHeadline
import app.flytrack.feature.airporthealth.HealthLevel
import app.flytrack.feature.airporthealth.typedHeadline
import app.flytrack.feature.checkin.isInternationalForCheckin
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The numbers of "When to leave" (P2 wave 2, section 5). Identical in the iOS app. */
object LeaveRules {
    const val BASE_DOMESTIC_MIN = 120
    const val BASE_INTERNATIONAL_MIN = 180

    /** Fog, or Airport Health `disrupted` or worse, at the departure airport. */
    const val FOG_EXTRA_MIN = 30

    /** Departure in a peak hour at the departure airport. */
    const val PEAK_EXTRA_MIN = 20

    /** Travel time to an airport the user never set. */
    const val DEFAULT_TRAVEL_MIN = 60
    const val MIN_TRAVEL_MIN = 0
    const val MAX_TRAVEL_MIN = 600

    /** The local notification fires this long before [leaveBy]. */
    const val REMINDER_BEFORE_MIN = 15

    /** Peak hours, departure airport local time, half-open: [05:00, 09:00) and [17:00, 21:00). */
    val PEAK_HOURS: List<IntRange> = listOf(5 until 9, 17 until 21)
}

/** Minutes kept before departure for the airport itself (check-in, security, boarding). */
fun leaveBufferMinutes(international: Boolean, fogAtDep: Boolean, peak: Boolean): Int =
    (if (international) LeaveRules.BASE_INTERNATIONAL_MIN else LeaveRules.BASE_DOMESTIC_MIN) +
        (if (fogAtDep) LeaveRules.FOG_EXTRA_MIN else 0) +
        (if (peak) LeaveRules.PEAK_EXTRA_MIN else 0)

/** `leaveBy = depTime − buffer − travelMinutes` (contract section 5). A negative travel time counts as 0. */
fun leaveBy(depTime: Instant, international: Boolean, travelMinutes: Int, fogAtDep: Boolean, peak: Boolean): Instant =
    depTime - (leaveBufferMinutes(international, fogAtDep, peak) + travelMinutes.coerceAtLeast(0)).minutes

/** Departure in [LeaveRules.PEAK_HOURS] at the departure airport ([depTz]; unknown = India). */
fun isPeakHour(depTime: Instant, depTz: String?): Boolean {
    val hour = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(depTime.epochSeconds), zoneOf(depTz)).hour
    return LeaveRules.PEAK_HOURS.any { hour in it }
}

/** Airport Health slows the way to the gate: fog (detail or headline), or level `disrupted` or `severe`. */
fun AirportHealth.slowsDeparture(): Boolean =
    level >= HealthLevel.DISRUPTED || detail?.weather?.fog == true || typedHeadline() is HealthHeadline.Fog

/** Travel minutes as stored: within [LeaveRules.MIN_TRAVEL_MIN]..[LeaveRules.MAX_TRAVEL_MIN]. */
fun clampTravelMinutes(minutes: Int): Int = minutes.coerceIn(LeaveRules.MIN_TRAVEL_MIN, LeaveRules.MAX_TRAVEL_MIN)

/** What the card shows for one flight. */
data class LeavePlan(
    val leaveBy: Instant,
    /** The departure the plan counts from: estimated, else scheduled. */
    val departure: Instant,
    val depTz: String?,
    val travelMinutes: Int,
    val bufferMinutes: Int,
    val international: Boolean,
    val fog: Boolean,
    val peak: Boolean,
) {
    /** When the "time to leave" notification fires. */
    val reminderAt: Instant get() = leaveBy - LeaveRules.REMINDER_BEFORE_MIN.minutes
}

/**
 * The plan for [flight], or null when there is nothing to plan: no departure time, the flight has left,
 * was cancelled or diverted, or its departure is already past.
 */
fun leavePlan(flight: Flight, travelMinutes: Int, fogAtDep: Boolean, now: Instant): LeavePlan? {
    if (flight.status == FlightStatus.CANCELLED || flight.status == FlightStatus.DIVERTED || flight.hasDeparted) return null
    val departure = flight.estDep ?: flight.schedDep ?: return null
    if (departure <= now) return null
    val international = flight.isInternationalForCheckin
    val peak = isPeakHour(departure, flight.depAirport?.tz)
    val travel = clampTravelMinutes(travelMinutes)
    return LeavePlan(
        leaveBy = leaveBy(departure, international, travel, fogAtDep, peak),
        departure = departure,
        depTz = flight.depAirport?.tz,
        travelMinutes = travel,
        bufferMinutes = leaveBufferMinutes(international, fogAtDep, peak),
        international = international,
        fog = fogAtDep,
        peak = peak,
    )
}

/**
 * Google Maps directions to the departure airport (the terminal when known). A universal link: it opens
 * the Maps app when installed, the browser otherwise. Without an airport name the coordinates are used.
 */
fun directionsUrl(airport: Airport?, iata: String, terminal: String?): String {
    val destination = when {
        airport != null && airport.name.isNotBlank() -> buildString {
            append(airport.name)
            terminal?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" Terminal ").append(it) }
            airport.city?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        }
        airport != null -> "${airport.lat},${airport.lon}"
        else -> "$iata airport"
    }
    return "https://www.google.com/maps/dir/?api=1&destination=" + URLEncoder.encode(destination, StandardCharsets.UTF_8)
}
