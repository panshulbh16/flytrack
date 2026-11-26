package app.flytrack.feature.calendarimport

import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.Flight
import app.flytrack.feature.airports.CatalogAirport
import kotlinx.datetime.LocalDate
import java.time.ZoneId
import kotlin.time.Instant

// From parser candidates to the suggestions the import screen lists (P3 A3). Pure, JVM-tested.

/** How a suggestion becomes a tracked flight: the normal search-and-track path, with source 'calendar'. */
sealed interface SuggestionAction {
    /** Search this flight number on this date (add-flight, flight-number tab). */
    data class ByNumber(val flight: String, val date: LocalDate) : SuggestionAction

    /** No flight number: search the day's departures from [from] (add-flight, "By route" tab). */
    data class ByRoute(val from: String, val to: String?, val date: LocalDate) : SuggestionAction

    /** Not enough to search: no flight number and no departure airport, or no date. */
    data object NeedsDetails : SuggestionAction
}

data class CalendarSuggestion(
    /** The parser's source id ("device:<event>:<begin>", plus ":legN"): stable across scans. */
    val id: String,
    /** "6E 2341" (normalised), or null when the event names none. */
    val flight: String?,
    val from: CatalogAirport?,
    val to: CatalogAirport?,
    /** Local departure date at the origin (or the event's date when the origin is unknown). */
    val date: LocalDate?,
    /** "06:00" at the departure airport, when the event states it. */
    val departTime: String?,
    val issues: List<ReviewIssue>,
    /** "Leg 1 of 2" of a multi-leg event. */
    val leg: Int?,
    val legCount: Int?,
    val action: SuggestionAction,
    /** The user already tracks a flight that matches (same number and date, or same route and date). */
    val alreadyTracked: Boolean,
)

/**
 * Suggestions for [candidates] (already split into legs): from [today] on, one per flight (the same number
 * and date, or the same route and date, appear once), soonest first. Candidates without a flight number and
 * without any airport are dropped: a "Flight" reminder with nothing else is not worth a row.
 */
fun buildSuggestions(candidates: List<CalendarCandidate>, tracked: List<Flight>, today: LocalDate): List<CalendarSuggestion> {
    val seen = HashSet<String>()
    return candidates.asSequence()
        .mapNotNull { c ->
            val flight = c.number.takeIf { it.isNotEmpty() }?.let { parseFlightNumber(it)?.display }
            if (flight == null && c.from == null && c.to == null) return@mapNotNull null
            val date = candidateDate(c)
            if (date != null && date < today) return@mapNotNull null
            val action = when {
                date == null -> SuggestionAction.NeedsDetails
                flight != null -> SuggestionAction.ByNumber(flight, date)
                c.from != null -> SuggestionAction.ByRoute(c.from.iata, c.to?.iata?.takeIf { it != c.from.iata }, date)
                else -> SuggestionAction.NeedsDetails
            }
            val key = when {
                flight != null && date != null -> "n:$flight:$date"
                else -> "r:${c.from?.iata}:${c.to?.iata}:$date:${c.sourceId.takeIf { date == null }}"
            }
            if (!seen.add(key)) return@mapNotNull null
            CalendarSuggestion(
                id = c.sourceId,
                flight = flight,
                from = c.from,
                to = c.to,
                date = date,
                departTime = c.departLocal.takeIf { it.length >= 16 }?.substring(11, 16),
                issues = c.issues,
                leg = c.leg,
                legCount = c.legCount,
                action = action,
                alreadyTracked = date != null && tracked.any { it.matches(flight, c.from?.iata, c.to?.iata, date) },
            )
        }
        .sortedWith(compareBy<CalendarSuggestion, LocalDate?>(nullsLast()) { it.date }.thenBy { it.departTime ?: "99:99" }.thenBy { it.id })
        .toList()
}

/** The local departure date: from the stated departure time, else the all-day date, else the start instant. */
internal fun candidateDate(c: CalendarCandidate): LocalDate? {
    if (c.departLocal.length >= 10) return runCatching { LocalDate.parse(c.departLocal.take(10)) }.getOrNull()
    c.allDayDate?.let { return runCatching { LocalDate.parse(it) }.getOrNull() }
    val start = c.startInstant ?: return null
    return localDate(start, c.from?.tz ?: HOME_ZONE)
}

private fun localDate(instant: Instant, tz: String): LocalDate? = try {
    val d = java.time.Instant.ofEpochMilli(instant.toEpochMilliseconds()).atZone(ZoneId.of(tz)).toLocalDate()
    LocalDate(d.year, d.monthValue, d.dayOfMonth)
} catch (e: java.time.DateTimeException) {
    null
}

private const val HOME_ZONE = "Asia/Kolkata"

private fun Flight.matches(flight: String?, from: String?, to: String?, date: LocalDate): Boolean {
    if (flightDate != date) return false
    if (flight != null) return designator == flight
    return from != null && depIata == from && (to == null || arrIata == to)
}
