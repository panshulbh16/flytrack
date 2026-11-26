package app.flytrack.feature.calendar

import app.flytrack.core.model.Flight
import app.flytrack.core.model.bestArrival
import app.flytrack.core.model.bestDeparture
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The feed URL the server returns: absolute https with a host. Anything else is not offered to other apps. */
fun isFeedUrl(url: String): Boolean {
    if (url.any { it.isWhitespace() }) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

/** The same feed as `webcal://` (Apple Calendar and most desktop clients subscribe from it). */
fun webcalUrl(feedUrl: String): String = "webcal://" + feedUrl.substringAfter("://")

/**
 * Google Calendar's "add by URL" link for the feed. Google Calendar for Android cannot subscribe to a feed
 * itself; this opens the web page that does, and the calendar then syncs to the phone.
 */
fun googleCalendarSubscribeUrl(feedUrl: String): String =
    "https://calendar.google.com/calendar/r?cid=" + URLEncoder.encode(webcalUrl(feedUrl), StandardCharsets.UTF_8)

/** One device calendar event ("Add to calendar" on the detail screen). Times are epoch milliseconds (UTC). */
data class CalendarEventSpec(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val location: String?,
    val description: String,
    /** IANA zone of the departure airport, for the event's display zone. */
    val timeZone: String?,
)

/** "✈ 6E 2341 DEL→BOM": the same SUMMARY as the calendar feed (contract section 3). */
fun calendarEventTitle(flight: Flight): String = "✈ ${flight.designator} ${flight.depIata}→${flight.arrIata}"

/**
 * The event for [flight]: best known departure to best known arrival, or null without a departure time.
 * A missing or impossible arrival becomes departure + 2 h, so the event always has a length.
 *
 * @param terminalTemplate "%1$s, Terminal %2$s" (airport name, terminal) from resources
 * @param description the localised description (status line and app hint)
 */
fun calendarEventFor(flight: Flight, terminalTemplate: String, description: String): CalendarEventSpec? {
    val begin: Instant = flight.bestDeparture ?: return null
    val end = flight.bestArrival?.takeIf { it > begin } ?: (begin + DEFAULT_LENGTH)
    val airportName = flight.depAirport?.name ?: flight.depIata
    val location = flight.depTerminal?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { String.format(java.util.Locale.ROOT, terminalTemplate, airportName, it) }
        ?: airportName
    return CalendarEventSpec(
        title = calendarEventTitle(flight),
        beginMillis = begin.toEpochMilliseconds(),
        endMillis = end.toEpochMilliseconds(),
        location = location,
        description = description,
        timeZone = flight.depAirport?.tz,
    )
}

private val DEFAULT_LENGTH = 2.hours
