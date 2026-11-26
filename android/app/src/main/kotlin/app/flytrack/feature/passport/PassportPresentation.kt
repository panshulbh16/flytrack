package app.flytrack.feature.passport

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.time.Instant


/** The two views of the passport screen. */
enum class PassportTab {
    /** `p_year` null: all time for Pro, the last 12 months for free users. */
    OVERVIEW,

    /** `p_year` = the current year: Year in Review, free for everyone. */
    YEAR,
}

/** `my_passport` counts years and months in Indian time; the app uses the same calendar. */
val PASSPORT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

fun passportToday(now: Instant): LocalDate = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(PASSPORT_ZONE).toLocalDate()

/** The `p_year` argument for [tab]. */
fun PassportTab.yearArgument(currentYear: Int): Int? = when (this) {
    PassportTab.OVERVIEW -> null
    PassportTab.YEAR -> currentYear
}

/** One bar of the by-month chart. [fraction] is relative to the busiest month (0 when nothing was flown). */
data class MonthBar(val month: YearMonth, val flights: Int, val fraction: Float)

/**
 * Twelve bars: January to December of the passport's year for Year in Review, otherwise the twelve
 * months that end with [today]'s month. Months without flights are zero bars, so gaps stay visible.
 */
fun Passport.monthBars(today: LocalDate): List<MonthBar> {
    val months = if (scope == PassportScope.YEAR) {
        (1..12).map { YearMonth.of(year, it) }
    } else {
        val last = YearMonth.from(today)
        (11 downTo 0).map { last.minusMonths(it.toLong()) }
    }
    val counts = byMonth.mapNotNull { m ->
        val y = m.year ?: return@mapNotNull null
        val n = m.monthNumber ?: return@mapNotNull null
        YearMonth.of(y, n) to m.flights
    }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
    val max = months.maxOf { counts[it] ?: 0 }
    return months.map { ym ->
        val n = counts[ym] ?: 0
        MonthBar(ym, n, if (max == 0) 0f else n.toFloat() / max)
    }
}

/** Whole hours in the air, rounded to the nearest hour. */
val Passport.hoursInAir: Int get() = (airborneMinutes / 60.0).roundToInt()

/** Equatorial circumference, for the "times around the Earth" line. */
const val EARTH_CIRCUMFERENCE_KM = 40_075.0

/** Distance flown as laps of the Earth, one decimal; null below a tenth of a lap (not worth a line). */
val Passport.earthLaps: Double?
    get() {
        val laps = (distanceKm / EARTH_CIRCUMFERENCE_KM * 10).roundToInt() / 10.0
        return laps.takeIf { it >= 0.1 }
    }

/** Big numbers of the stats grid, in display order. */
enum class StatKind { FLIGHTS, DISTANCE_KM, HOURS, AIRPORTS, COUNTRIES, AIRLINES }

data class StatTile(val kind: StatKind, val value: Int)

fun Passport.statTiles(): List<StatTile> = listOf(
    StatTile(StatKind.FLIGHTS, flights),
    StatTile(StatKind.DISTANCE_KM, distanceKm),
    StatTile(StatKind.HOURS, hoursInAir),
    StatTile(StatKind.AIRPORTS, airports.size),
    StatTile(StatKind.COUNTRIES, countries.size),
    StatTile(StatKind.AIRLINES, airlines.size),
)

/** Longest lists the screen shows; the server already sorts them, most flown first. */
const val PASSPORT_LIST_LIMIT = 8

/** What the Year in Review card shows. Built only for a non-empty year passport. */
data class YearInReviewCardModel(
    val year: Int,
    val flights: Int,
    val distanceKm: Int,
    val hours: Int,
    val airports: Int,
    val countries: Int,
    val topRoute: TopRoute?,
    val longest: LongestFlight?,
    /** Airline name, or its code when the name is unknown. */
    val topAirline: String?,
    val bars: List<MonthBar>,
)

fun Passport.toYearInReviewCard(today: LocalDate): YearInReviewCardModel? {
    if (scope != PassportScope.YEAR || isEmpty) return null
    return YearInReviewCardModel(
        year = year,
        flights = flights,
        distanceKm = distanceKm,
        hours = hoursInAir,
        airports = airports.size,
        countries = countries.size,
        topRoute = topRoute?.takeIf { it.count > 1 },
        longest = longest,
        topAirline = airlines.firstOrNull()?.let { it.name ?: it.carrier },
        bars = monthBars(today),
    )
}
