package app.flytrack.core.time

import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * P3 B6: the dates `search-flight` accepts with `mode: "past"`: from 365 days before today (UTC) up to
 * tomorrow (UTC), the same check as `validatePastDate` in supabase/functions/_shared/past-search.ts.
 */
object PastSearchWindow {
    const val MAX_DAYS_BACK = 365

    fun contains(date: LocalDate, now: Instant): Boolean = date in dates(now)

    fun dates(now: Instant): ClosedRange<LocalDate> {
        val todayUtc = FlightTimeFormat.localDate(now, "UTC")
        return LocalDate.fromEpochDays(todayUtc.toEpochDays() - MAX_DAYS_BACK)..LocalDate.fromEpochDays(todayUtc.toEpochDays() + 1)
    }
}

/**
 * P3 B5: the dates `search-route` accepts: the date at 00:00 UTC between two days ago and 30 days ahead
 * (`normalizeRouteQuery` in supabase/functions/_shared/route-search.ts).
 */
object RouteSearchWindow {
    const val MAX_DAYS_AHEAD = 30

    private fun LocalDate.startOfDayUtc(): Instant = Instant.parse("${this}T00:00:00Z")

    fun contains(date: LocalDate, now: Instant): Boolean {
        val day = date.startOfDayUtc()
        return day >= now - 2.days && day <= now + MAX_DAYS_AHEAD.days
    }

    fun dates(now: Instant): ClosedRange<LocalDate> {
        val firstCandidate = FlightTimeFormat.localDate(now - 2.days, "UTC")
        val first = if (contains(firstCandidate, now)) firstCandidate else LocalDate.fromEpochDays(firstCandidate.toEpochDays() + 1)
        val lastCandidate = FlightTimeFormat.localDate(now + MAX_DAYS_AHEAD.days, "UTC")
        val last = if (contains(lastCandidate, now)) lastCandidate else LocalDate.fromEpochDays(lastCandidate.toEpochDays() - 1)
        return first..last
    }
}
