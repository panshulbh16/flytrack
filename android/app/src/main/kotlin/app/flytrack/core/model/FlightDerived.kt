package app.flytrack.core.model

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant

/** How sure a time is. */
enum class TimeKind { SCHEDULED, ESTIMATED, ACTUAL }

/** The best time we know for a departure or arrival, and how sure it is. */
data class BestTime(val instant: Instant, val kind: TimeKind)

private fun best(actual: Instant?, estimated: Instant?, scheduled: Instant?): BestTime? = when {
    actual != null -> BestTime(actual, TimeKind.ACTUAL)
    estimated != null -> BestTime(estimated, TimeKind.ESTIMATED)
    scheduled != null -> BestTime(scheduled, TimeKind.SCHEDULED)
    else -> null
}

/** Actual, else estimated, else scheduled departure. */
val Flight.bestDepartureTime: BestTime? get() = best(actDep, estDep, schedDep)

/** Actual, else estimated, else scheduled arrival. */
val Flight.bestArrivalTime: BestTime? get() = best(actArr, estArr, schedArr)

val Flight.bestDeparture: Instant? get() = bestDepartureTime?.instant

val Flight.bestArrival: Instant? get() = bestArrivalTime?.instant

/** Same rounding as the server's `minutesBetween` (Math.round of the minute difference). */
private fun minutesBetween(later: Instant, earlier: Instant): Int =
    Math.round((later - earlier).inWholeMilliseconds / 60_000.0).toInt()

/**
 * Minutes the departure is behind schedule: actual (else estimated) minus scheduled.
 * Negative when early, null when the schedule or any newer time is unknown.
 */
val Flight.departureDelayMinutes: Int?
    get() {
        val scheduled = schedDep ?: return null
        val current = actDep ?: estDep ?: return null
        return minutesBetween(current, scheduled)
    }

/** Minutes the arrival is behind schedule; negative when early, null when unknown. */
val Flight.arrivalDelayMinutes: Int?
    get() {
        val scheduled = schedArr ?: return null
        val current = actArr ?: estArr ?: return null
        return minutesBetween(current, scheduled)
    }

/** Gate-to-gate duration from the best known times, null when either end is unknown or inconsistent. */
val Flight.duration: Duration?
    get() {
        val dep = bestDeparture ?: return null
        val arr = bestArrival ?: return null
        return (arr - dep).takeIf { it.isPositive() }
    }

/** Same rule as the server's `isDeparted`. */
val Flight.hasDeparted: Boolean
    get() = actDep != null || status in DEPARTED_STATUSES

/** Same rule as the server's `isLanded`. */
val Flight.hasLanded: Boolean
    get() = actArr != null || status == FlightStatus.LANDED || status == FlightStatus.ARRIVED

private val DEPARTED_STATUSES = setOf(
    FlightStatus.DEPARTED, FlightStatus.EN_ROUTE, FlightStatus.APPROACHING,
    FlightStatus.LANDED, FlightStatus.ARRIVED, FlightStatus.DIVERTED,
)

/** Coarse stage of the journey. Cancelled and diverted win over everything else. */
val Flight.phase: FlightPhase
    get() = when {
        status == FlightStatus.CANCELLED -> FlightPhase.CANCELLED
        status == FlightStatus.DIVERTED -> FlightPhase.DIVERTED
        hasLanded -> FlightPhase.LANDED
        hasDeparted -> FlightPhase.IN_AIR
        status == FlightStatus.BOARDING || status == FlightStatus.GATE_CLOSED -> FlightPhase.BOARDING
        status == FlightStatus.CHECK_IN -> FlightPhase.CHECK_IN
        else -> FlightPhase.UPCOMING
    }

/** Delays below this are shown as "on time" (the server alerts from 15 minutes as well). */
const val DELAY_THRESHOLD_MINUTES = 15

/**
 * Colour family for the status word and pill. The decision never depends on colour alone:
 * screens must always pair it with a status text.
 */
val Flight.statusTone: FlightStatusTone
    get() {
        val delay = if (hasLanded) arrivalDelayMinutes else departureDelayMinutes
        return when {
            status == FlightStatus.CANCELLED || status == FlightStatus.DIVERTED -> FlightStatusTone.CANCELLED
            (delay ?: 0) >= DELAY_THRESHOLD_MINUTES -> FlightStatusTone.DELAYED
            phase == FlightPhase.BOARDING || phase == FlightPhase.IN_AIR -> FlightStatusTone.ACTIVE
            status == FlightStatus.UNKNOWN -> FlightStatusTone.ESTIMATED
            else -> FlightStatusTone.ON_TIME
        }
    }

/** Fraction of the flight already flown (0..1) from the best known times, null before take-off data exists. */
fun Flight.progress(now: Instant): Float? {
    if (!hasDeparted) return null
    if (hasLanded) return 1f
    val dep = bestDeparture ?: return null
    val arr = bestArrival ?: return null
    val total = (arr - dep).inWholeSeconds
    if (total <= 0) return null
    return ((now - dep).inWholeSeconds.toFloat() / total).coerceIn(0f, 1f)
}

/** Great-circle distance in kilometres between the two airports, null when either is unknown. */
val Flight.distanceKm: Int?
    get() {
        val a = depAirport ?: return null
        val b = arrAirport ?: return null
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2).let { it * it }
        return (2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h))).roundToInt()
    }

private const val EARTH_RADIUS_KM = 6371.0
