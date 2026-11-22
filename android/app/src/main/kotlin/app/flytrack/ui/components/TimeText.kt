package app.flytrack.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.flytrack.R
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.RelativeTime
import app.flytrack.core.time.RelativeUnit
import app.flytrack.core.time.relativeTime
import app.flytrack.core.time.toDurationParts
import kotlin.time.Duration
import kotlin.time.Instant

// Turns the typed values of app.flytrack.core.time into localised text.

/** "Today", "Tomorrow", "Yesterday", "in 3 days", "2 days ago". */
@Composable
@ReadOnlyComposable
fun relativeDayText(day: RelativeDay): String = when (day) {
    RelativeDay.Today -> stringResource(R.string.relative_today)
    RelativeDay.Tomorrow -> stringResource(R.string.relative_tomorrow)
    RelativeDay.Yesterday -> stringResource(R.string.relative_yesterday)
    is RelativeDay.InDays -> pluralStringResource(R.plurals.relative_in_days, day.days, day.days)
    is RelativeDay.DaysAgo -> pluralStringResource(R.plurals.relative_days_ago, day.days, day.days)
}

/** "now", "in 35 min", "in 3 hr", "4 min ago", "2 days ago". */
@Composable
@ReadOnlyComposable
fun relativeTimeText(time: RelativeTime): String {
    val n = time.amount
    return when (time.unit) {
        RelativeUnit.NOW -> stringResource(R.string.relative_now)
        RelativeUnit.MINUTES ->
            pluralStringResource(if (time.isPast) R.plurals.relative_minutes_ago else R.plurals.relative_in_minutes, n, n)
        RelativeUnit.HOURS ->
            pluralStringResource(if (time.isPast) R.plurals.relative_hours_ago else R.plurals.relative_in_hours, n, n)
        RelativeUnit.DAYS ->
            pluralStringResource(if (time.isPast) R.plurals.relative_days_ago else R.plurals.relative_in_days, n, n)
    }
}

/** "Updated 4 min ago" / "Updated just now". Pass `Flight.updatedAt` and the current time. */
@Composable
@ReadOnlyComposable
fun updatedAgoText(updatedAt: Instant, now: Instant): String {
    // A phone clock slightly behind the server must not produce "Updated in 2 min".
    val elapsed = relativeTime(target = minOf(updatedAt, now), now = now)
    return if (elapsed.unit == RelativeUnit.NOW) {
        stringResource(R.string.updated_just_now)
    } else {
        stringResource(R.string.updated_ago, relativeTimeText(elapsed))
    }
}

/** "2h 05m", or "45m" below one hour. */
@Composable
@ReadOnlyComposable
fun durationText(duration: Duration): String = duration.toDurationParts().format(
    hoursMinutesPattern = stringResource(R.string.duration_hours_minutes),
    minutesPattern = stringResource(R.string.duration_minutes),
)
