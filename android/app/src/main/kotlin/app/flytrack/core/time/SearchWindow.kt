package app.flytrack.core.time

import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The flight dates the backend's search accepts: the date at 00:00 UTC must lie between two days
 * ago and 365 days ahead (same check as `searchFlight` in supabase/functions/_shared/engine.ts).
 */
object SearchWindow {
    private fun LocalDate.startOfDayUtc(): Instant = Instant.parse("${this}T00:00:00Z")

    fun contains(date: LocalDate, now: Instant): Boolean {
        val day = date.startOfDayUtc()
        return day >= now - 2.days && day <= now + 365.days
    }

    /** First and last selectable date, for date pickers. */
    fun dates(now: Instant): ClosedRange<LocalDate> {
        val firstCandidate = FlightTimeFormat.localDate(now - 2.days, "UTC")
        val first = if (contains(firstCandidate, now)) firstCandidate else LocalDate.fromEpochDays(firstCandidate.toEpochDays() + 1)
        return first..FlightTimeFormat.localDate(now + 365.days, "UTC")
    }
}
