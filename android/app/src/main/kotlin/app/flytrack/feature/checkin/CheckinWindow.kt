package app.flytrack.feature.checkin

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.hasDeparted
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One airline's web check-in rule (`public.checkin_rules`). */
data class CheckinRule(
    val carrierIata: String,
    val opensHoursBefore: Int,
    val closesMinutesBefore: Int,
    val closesMinutesBeforeIntl: Int,
    /** Null or not https: the app shows the window but no button. */
    val checkinUrl: String?,
    /** False: the times are the airline's usual ones, not confirmed for this carrier. */
    val verified: Boolean,
)

/** Where the flight stands in its web check-in window. */
sealed interface CheckinWindow {
    val opensAt: Instant
    val closesAt: Instant

    /** "Web check-in opens in 6 h". */
    data class NotOpenYet(override val opensAt: Instant, override val closesAt: Instant) : CheckinWindow

    /** "Check in now →". [closingSoon] once the close is less than [CLOSING_SOON] away (the CHECKIN_CLOSING alert). */
    data class Open(override val opensAt: Instant, override val closesAt: Instant, val closingSoon: Boolean) : CheckinWindow

    /** Web check-in is over; the airport counter is the only way left. */
    data class Closed(override val opensAt: Instant, override val closesAt: Instant) : CheckinWindow

    companion object {
        /** Same lead as the server's CHECKIN_CLOSING event: `closes_at − 30 min`. */
        val CLOSING_SOON: Duration = 30.minutes
    }
}

/**
 * The time check-in is counted from: the estimated departure when the airline published one,
 * else the timetable (the same rule as the `flights.checkin_*` trigger).
 */
val Flight.checkinBaseDeparture: Instant? get() = estDep ?: schedDep

/**
 * International when the two airports are in different time zones. The app has no airport
 * country, and for India-first traffic "same zone" is "same country" (every Indian airport is
 * Asia/Kolkata). Unknown zones count as international, which only makes the close earlier.
 */
val Flight.isInternationalForCheckin: Boolean
    get() {
        val dep = depAirport?.tz ?: return true
        val arr = arrAirport?.tz ?: return true
        return dep != arr
    }

/**
 * The check-in window of [flight] under [rule] at [now], or null when there is nothing to show:
 * no rule, no departure time, the flight is cancelled, diverted or already gone.
 */
fun checkinWindow(rule: CheckinRule?, flight: Flight, now: Instant): CheckinWindow? {
    if (rule == null) return null
    if (flight.status == FlightStatus.CANCELLED || flight.status == FlightStatus.DIVERTED || flight.hasDeparted) return null
    val departure = flight.checkinBaseDeparture ?: return null
    if (now >= departure) return null
    val closeMinutes = if (flight.isInternationalForCheckin) rule.closesMinutesBeforeIntl else rule.closesMinutesBefore
    val opensAt = departure - rule.opensHoursBefore.coerceAtLeast(0).hours
    val closesAt = departure - closeMinutes.coerceAtLeast(0).minutes
    return when {
        now < opensAt -> CheckinWindow.NotOpenYet(opensAt, closesAt)
        now < closesAt -> CheckinWindow.Open(opensAt, closesAt, closingSoon = closesAt - now <= CheckinWindow.CLOSING_SOON)
        else -> CheckinWindow.Closed(opensAt, closesAt)
    }
}

/**
 * [url] when it may be opened in a browser: an absolute https link. Everything that reaches the
 * app from outside (push extras, a table row) goes through this before an intent is built.
 */
fun safeCheckinUrl(url: String?): String? {
    val trimmed = url?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    return trimmed
}

/** The check-in card of the detail screen. Times are shown in [depTz], the departure airport's zone. */
data class CheckinUiModel(
    val window: CheckinWindow,
    /** Safe https link, or null: then no button. */
    val checkinUrl: String?,
    val verified: Boolean,
    val depTz: String?,
    val now: Instant,
) {
    /** "Check in now →" is offered only while the window is open and a link is known. */
    val canCheckInNow: Boolean get() = window is CheckinWindow.Open && checkinUrl != null
}

/** [checkinWindow] packed for the screen, or null when the card is hidden. */
fun checkinUiModel(rule: CheckinRule?, flight: Flight, now: Instant): CheckinUiModel? {
    val window = checkinWindow(rule, flight, now) ?: return null
    return CheckinUiModel(window, rule?.checkinUrl, rule?.verified == true, flight.depAirport?.tz, now)
}
