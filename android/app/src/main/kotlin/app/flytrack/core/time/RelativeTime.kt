package app.flytrack.core.time

import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Calendar-day distance, for "Today", "Tomorrow", "in 3 days", "2 days ago".
 * Turn it into text with `relativeDayText()` in ui.components (strings live in resources).
 */
sealed interface RelativeDay {
    data object Today : RelativeDay
    data object Tomorrow : RelativeDay
    data object Yesterday : RelativeDay
    data class InDays(val days: Int) : RelativeDay
    data class DaysAgo(val days: Int) : RelativeDay
}

fun relativeDay(target: LocalDate, today: LocalDate): RelativeDay =
    when (val days = (target.toEpochDays() - today.toEpochDays()).toInt()) {
        0 -> RelativeDay.Today
        1 -> RelativeDay.Tomorrow
        -1 -> RelativeDay.Yesterday
        else -> if (days > 0) RelativeDay.InDays(days) else RelativeDay.DaysAgo(-days)
    }

/** Calendar-day distance of [target] from [now], both read in the airport's zone [tz]. */
fun relativeDay(target: Instant, now: Instant, tz: String?): RelativeDay =
    relativeDay(FlightTimeFormat.localDate(target, tz), FlightTimeFormat.localDate(now, tz))

enum class RelativeUnit { NOW, MINUTES, HOURS, DAYS }

/**
 * Elapsed-time distance, for "updated 4 min ago" and "boards in 35 min".
 * [amount] is 0 for [RelativeUnit.NOW]. Turn it into text with `relativeTimeText()` in ui.components.
 */
data class RelativeTime(val amount: Int, val unit: RelativeUnit, val isPast: Boolean)

/** Whole units, rounded down: 59 s -> now, 4 min 50 s -> 4 minutes, 23 h 59 min -> 23 hours. */
fun relativeTime(target: Instant, now: Instant): RelativeTime {
    val diff = target - now
    val minutes = abs(diff.inWholeMinutes)
    val isPast = diff.isNegative()
    return when {
        minutes < 1 -> RelativeTime(0, RelativeUnit.NOW, isPast = false)
        minutes < 60 -> RelativeTime(minutes.toInt(), RelativeUnit.MINUTES, isPast)
        minutes < 24 * 60 -> RelativeTime((minutes / 60).toInt(), RelativeUnit.HOURS, isPast)
        else -> RelativeTime((minutes / (24 * 60)).toInt(), RelativeUnit.DAYS, isPast)
    }
}

/** Hours and minutes of a duration rounded to the nearest minute (never negative). */
data class DurationParts(val hours: Int, val minutes: Int) {
    /** "2h 05m", or "45m" below one hour. Pass the patterns from string resources in UI code. */
    fun format(hoursMinutesPattern: String = "%1\$dh %2\$02dm", minutesPattern: String = "%1\$dm"): String =
        if (hours > 0) hoursMinutesPattern.format(hours, minutes) else minutesPattern.format(minutes)
}

fun Duration.toDurationParts(): DurationParts {
    val totalMinutes = ((if (isNegative()) Duration.ZERO else this).inWholeSeconds + 30) / 60
    return DurationParts((totalMinutes / 60).toInt(), (totalMinutes % 60).toInt())
}

/** Today's date as seen in [tz]. */
fun Clock.today(tz: String?): LocalDate = FlightTimeFormat.localDate(now(), tz)
