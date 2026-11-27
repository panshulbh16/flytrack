package app.flytrack.feature.addflight

import app.flytrack.core.flightnumber.parseFlightNumber
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.normalizePnr
import app.flytrack.core.time.PastSearchWindow
import app.flytrack.core.time.RouteSearchWindow
import app.flytrack.core.time.SearchWindow
import kotlinx.datetime.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Instant

// Pure input rules of the add-flight form. No android.* types: everything here is JVM-tested.

/** How far the typed text is from a flight number. */
enum class FlightInputCheck {
    EMPTY,

    /** Not a flight number yet, but more typing can make it one ("6E", "AI-"). */
    INCOMPLETE,

    VALID,

    /** No amount of further typing makes this a flight number ("AIR", "12 34", five digits). */
    INVALID,
}

// The same whitespace set as the parser in core.flightnumber (JavaScript's \s), so a pasted
// no-break space is treated the same way while typing as it is when parsing.
private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

/** Every prefix of a text the flight-number parser accepts: one carrier character, or the carrier plus a started number. */
private val FLIGHT_NUMBER_PREFIX =
    Regex("^$WS*(?:[A-Za-z0-9]|([A-Za-z0-9]{2})$WS*(?:-$WS*)?(?:[0-9]{1,4}[A-Za-z]?$WS*)?)?$")
private val TWO_DIGITS = Regex("^[0-9]{2}$")

/** Live validation of the flight-number field, consistent with [parseFlightNumber]. */
fun checkFlightInput(input: String): FlightInputCheck {
    if (input.isBlank()) return FlightInputCheck.EMPTY
    if (parseFlightNumber(input) != null) return FlightInputCheck.VALID
    val prefix = FLIGHT_NUMBER_PREFIX.find(input) ?: return FlightInputCheck.INVALID
    val carrier = prefix.groupValues[1]
    return if (TWO_DIGITS.matches(carrier)) FlightInputCheck.INVALID else FlightInputCheck.INCOMPLETE
}

/** The flight field keeps this much: room for any valid spelling (" 6E - 2341A ") and enough of a pasted airline name to show why it is wrong. */
const val MAX_FLIGHT_INPUT_LENGTH = 24

/** The database accepts `^[A-Z0-9]{5,8}$`; anything else a user or OCR adds (spaces, dashes, lower case) is noise. */
const val MIN_PNR_LENGTH = 5
const val MAX_PNR_LENGTH = 8

/** Keeps only what can be part of a PNR, upper-cased, so the field can never hold an illegal character. */
fun sanitizePnrInput(input: String): String =
    input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(MAX_PNR_LENGTH)

/** A PNR is optional: empty is fine, a started one must be complete. */
fun isPnrInputAcceptable(input: String): Boolean = input.isEmpty() || normalizePnr(input) != null

/** Seats are short codes such as "12A"; the database allows up to five characters. */
fun sanitizeSeatInput(input: String): String =
    input.uppercase().filterNot { it.isWhitespace() }.take(TrackExtras.MAX_SEAT_LENGTH)

internal fun LocalDate.plusDays(days: Int): LocalDate = LocalDate.fromEpochDays(toEpochDays() + days)

/**
 * The dates the form offers: yesterday (on this phone) up to the last day the backend's search
 * accepts, about twelve months ahead. The server counts its window in UTC, so west of UTC
 * "yesterday" can fall outside it; the range is clipped to what the server will take.
 */
fun selectableDates(today: LocalDate, now: Instant): ClosedRange<LocalDate> {
    val server = SearchWindow.dates(now)
    val first = maxOf(today.plusDays(-1), server.start)
    return first..maxOf(first, server.endInclusive)
}

/**
 * P3 B6: the dates of a past flight: up to a year before today (on this phone) and at most today, clipped to
 * what the server's past search accepts (365 days back in UTC).
 */
fun pastSelectableDates(today: LocalDate, now: Instant): ClosedRange<LocalDate> {
    val server = PastSearchWindow.dates(now)
    val first = maxOf(today.plusDays(-PastSearchWindow.MAX_DAYS_BACK), server.start)
    val last = minOf(today, server.endInclusive)
    return first..maxOf(first, last)
}

/** P3 B5: route search covers yesterday (on this phone) to 30 days ahead, clipped to the server's window. */
fun routeSelectableDates(today: LocalDate, now: Instant): ClosedRange<LocalDate> {
    val server = RouteSearchWindow.dates(now)
    val first = maxOf(today.plusDays(-1), server.start)
    val last = minOf(today.plusDays(RouteSearchWindow.MAX_DAYS_AHEAD), server.endInclusive)
    return first..maxOf(first, last)
}

/** The airline field of the route form: empty, or a complete two-character code. */
fun isAirlineInputAcceptable(input: String): Boolean = input.isEmpty() || input.length == 2

private const val MILLIS_PER_DAY = 86_400_000L

/** Midnight UTC of this date: the value Material's date picker works with. */
fun LocalDate.toUtcMillis(): Long = toEpochDays() * MILLIS_PER_DAY

fun utcMillisToLocalDate(utcMillis: Long): LocalDate = LocalDate.fromEpochDays(Math.floorDiv(utcMillis, MILLIS_PER_DAY))

/** What the importer's values mean for the form. */
data class ResolvedPrefill(
    val flightInput: String,
    val date: LocalDate?,
    val pnrInput: String,
    val notice: PrefillNotice?,
    /** True when flight and date are both usable: the search starts on its own. */
    val autoSearch: Boolean,
)

fun resolvePrefill(prefill: AddFlightPrefill, selectable: ClosedRange<LocalDate>): ResolvedPrefill {
    if (prefill.isEmpty) return ResolvedPrefill(flightInput = "", date = null, pnrInput = "", notice = null, autoSearch = false)

    val flightText = prefill.flight.orEmpty().trim().take(MAX_FLIGHT_INPUT_LENGTH)
    val flightOk = parseFlightNumber(flightText) != null
    val ticketDate = prefill.date?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val dateOk = ticketDate != null && ticketDate in selectable

    val notice = when {
        !flightOk -> PrefillNotice.FlightUnreadable
        ticketDate == null -> PrefillNotice.DateUnreadable
        !dateOk -> PrefillNotice.DateOutOfRange(ticketDate)
        else -> PrefillNotice.CheckDetails
    }
    return ResolvedPrefill(
        flightInput = flightText,
        date = ticketDate.takeIf { dateOk },
        pnrInput = sanitizePnrInput(prefill.pnr.orEmpty()),
        notice = notice,
        autoSearch = flightOk && dateOk,
    )
}

/**
 * The phone's time zone: it decides what "today" means before any airport is known.
 * A class instead of a direct `ZoneId.systemDefault()` call so tests can pin the zone.
 */
class HomeTimeZone(private val provider: () -> String) {
    @Inject constructor() : this({ ZoneId.systemDefault().id })

    val id: String get() = provider()
}
