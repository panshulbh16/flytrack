package app.flytrack.core.time

import kotlinx.datetime.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Instant

/** Used when an airport's zone is unknown. Same fallback as the server's alert text (notify.ts). */
const val FALLBACK_TIME_ZONE = "Asia/Kolkata"

/** Resolves an IANA id, falling back to [FALLBACK_TIME_ZONE] for null or unknown ids. */
fun zoneOf(tz: String?): ZoneId =
    tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of(FALLBACK_TIME_ZONE)

internal fun Instant.atZone(tz: String?): ZonedDateTime =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()).atZone(zoneOf(tz))

/**
 * Formats flight times in the AIRPORT's zone, never the phone's. Pure JVM code: no Android types.
 * Clock times always use ASCII digits so they line up with the tabular-numeral text style.
 */
object FlightTimeFormat {
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "18:50" (24 hour) in the given IANA zone. */
    fun time(instant: Instant, tz: String?): String = HOUR_MINUTE.format(instant.atZone(tz))

    /** "25 Nov" in the given IANA zone. */
    fun dayMonth(instant: Instant, tz: String?, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM", locale).format(instant.atZone(tz))

    /** "25 Nov". */
    fun dayMonth(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("d MMM", locale).format(date.toJava())

    /** "Wed, 25 Nov" in the given IANA zone. */
    fun weekdayDayMonth(instant: Instant, tz: String?, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(instant.atZone(tz))

    /** "Wed, 25 Nov". */
    fun weekdayDayMonth(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(date.toJava())

    /** "Wed, 25 Nov 2026": for date pickers and anything more than a few months away. */
    fun fullDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale).format(date.toJava())

    /** The calendar date of [instant] as seen at the airport. */
    fun localDate(instant: Instant, tz: String?): LocalDate = LocalDate.parse(instant.atZone(tz).toLocalDate().toString())

    /**
     * Calendar days between departure and arrival as seen at each airport: 1 for an overnight flight
     * ("+1" next to the arrival time), -1 when crossing the date line westbound.
     */
    fun arrivalDayOffset(dep: Instant, depTz: String?, arr: Instant, arrTz: String?): Int =
        (arr.atZone(arrTz).toLocalDate().toEpochDay() - dep.atZone(depTz).toLocalDate().toEpochDay()).toInt()

    private fun LocalDate.toJava(): java.time.LocalDate = java.time.LocalDate.parse(toString())
}
