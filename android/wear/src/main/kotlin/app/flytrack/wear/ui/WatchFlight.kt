package app.flytrack.wear.ui

import app.flytrack.wear.data.NextFlightPayload
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** The status words the phone widget uses (`LiveStatusWord` in `:app`), wire form lower-cased. */
enum class WatchStatus(val wire: String) {
    ON_TIME("on_time"),
    DELAYED("delayed"),
    CHECK_IN("check_in"),
    BOARDING("boarding"),
    GATE_CLOSED("gate_closed"),
    DEPARTED("departed"),
    IN_AIR("in_air"),
    LANDED("landed"),
    CANCELLED("cancelled"),
    DIVERTED("diverted"),
    ;

    companion object {
        fun fromWire(value: String?): WatchStatus? = entries.firstOrNull { it.wire == value }
    }
}

/** Colour family of the status text, the design brief's flight-status tokens (dark theme). */
enum class WatchTone(val argb: Int) {
    ON_TIME(0xFFA9CFA0.toInt()),
    DELAYED(0xFFE8C98A.toInt()),
    CANCELLED(0xFFF2BDA3.toInt()),

    /** Boarding or in the air: the accent terracotta. */
    ACTIVE(0xFFE88A62.toInt()),

    /** The phone's offline estimate: muted. */
    ESTIMATED(0xFFC0CDBB.toInt()),
    ;

    companion object {
        fun fromWire(value: String?): WatchTone? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Localised words; the watch implements it with `strings.xml`, tests with an English fake. */
interface WearStrings {
    fun status(status: WatchStatus): String

    /** "Delayed 25 min". */
    fun delayedBy(minutes: Int): String

    /** "DEL → BOM". */
    fun route(dep: String, arr: String): String

    /** "Gate 42B". */
    fun gate(gate: String): String

    /** "T1". */
    fun terminal(terminal: String): String

    /** "Belt 5". */
    fun belt(belt: String): String

    /** "Departs 18:25". */
    fun departs(time: String): String

    /** "Gate 42B · T1". */
    fun join(parts: List<String>): String
}

/** Everything the Tile, the complication and the app screen draw for one flight. */
data class WatchFlight(
    val flightId: String,
    /** "6E 2341". */
    val designator: String,
    /** "DEL → BOM". */
    val route: String,
    /** "DEL-BOM": fits a short-text complication title (7 characters). */
    val shortRoute: String,
    /** "On time", "Delayed 25 min", "Boarding". */
    val statusText: String,
    val status: WatchStatus,
    val tone: WatchTone,
    /** Best known departure, airport-local 24 h ("18:25"); null when no time is known. */
    val departureTime: String?,
    /** "Wed, 25 Nov" when the departure is not today at the airport, else null. */
    val dateText: String?,
    /** "Gate 42B · T1" before departure, "Belt 5" after; null when nothing is known. */
    val gateText: String?,
    /** "Departs 18:25 · Wed, 25 Nov" (the time line of the Tile and the app screen); null when no time is known. */
    val whenText: String?,
    val estimated: Boolean,
)

/** What the watch shows: a flight, or the empty state ("No upcoming flight"). */
sealed interface WatchNextFlight {
    data object Empty : WatchNextFlight
    data class Upcoming(val flight: WatchFlight) : WatchNextFlight
}

/**
 * Turns the phone's payload into [WatchNextFlight]. Pure: no Android types, so it is unit-tested.
 *
 * The phone replaces or deletes the payload as soon as its flight changes. When the watch is out
 * of reach for long, the last payload would go stale, so the watch applies the phone's own
 * relevance rule (landed + 45 min, cancelled + 1 h after departure, departure + 4 h without an
 * arrival time) and falls back to the empty state.
 */
class WatchFlightFormatter(
    private val strings: WearStrings,
    private val locale: Locale = Locale.getDefault(),
    private val deviceZone: ZoneId = ZoneId.systemDefault(),
) {

    fun format(payload: NextFlightPayload?, now: Instant): WatchNextFlight {
        if (payload == null) return WatchNextFlight.Empty
        val relevantUntil = relevantUntil(payload)
        if (relevantUntil != null && !now.isBefore(relevantUntil)) return WatchNextFlight.Empty

        val status = WatchStatus.fromWire(payload.sw) ?: fallbackStatus(payload)
        val delay = payload.dm
        val statusText = if (status == WatchStatus.DELAYED && delay != null && delay > 0) {
            strings.delayedBy(delay)
        } else {
            strings.status(status)
        }
        val departure = bestDeparture(payload)
        val depZone = zone(payload.dtz)
        val afterDeparture = status in AFTER_DEPARTURE
        val dateText = departure
            ?.takeIf { !afterDeparture && it.atZone(depZone).toLocalDate() != now.atZone(depZone).toLocalDate() }
            ?.let { DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(it.atZone(depZone)) }
        val gateParts = if (afterDeparture) {
            listOfNotNull(payload.bb?.takeIf { it.isNotBlank() }?.let(strings::belt))
        } else {
            listOfNotNull(
                payload.dg?.takeIf { it.isNotBlank() }?.let(strings::gate),
                payload.dt?.takeIf { it.isNotBlank() }?.let(strings::terminal),
            )
        }
        val tone = WatchTone.fromWire(payload.tone) ?: fallbackTone(status, payload.est)
        val departureTime = departure?.let { HOUR_MINUTE.format(it.atZone(depZone)) }
        val whenText = listOfNotNull(departureTime?.let(strings::departs), dateText)
            .takeIf { it.isNotEmpty() }
            ?.let(strings::join)
        return WatchNextFlight.Upcoming(
            WatchFlight(
                flightId = payload.id,
                designator = "${payload.c} ${payload.n}",
                route = strings.route(payload.dep, payload.arr),
                shortRoute = "${payload.dep}-${payload.arr}",
                statusText = statusText,
                status = status,
                tone = tone,
                departureTime = departureTime,
                dateText = dateText,
                gateText = gateParts.takeIf { it.isNotEmpty() }?.let(strings::join),
                whenText = whenText,
                estimated = payload.est,
            ),
        )
    }

    private fun zone(tz: String?): ZoneId = tz?.let {
        try {
            ZoneId.of(it)
        } catch (e: DateTimeException) {
            null
        }
    } ?: deviceZone

    companion object {
        private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        private val AFTER_LANDING: Duration = Duration.ofMinutes(45)
        private val CANCELLED_VISIBLE: Duration = Duration.ofHours(1)
        private val NO_ARRIVAL_VISIBLE: Duration = Duration.ofHours(4)
        private val AFTER_DEPARTURE = setOf(WatchStatus.DEPARTED, WatchStatus.IN_AIR, WatchStatus.LANDED, WatchStatus.DIVERTED)

        fun parse(iso: String?): Instant? = iso?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        }

        fun bestDeparture(p: NextFlightPayload): Instant? = parse(p.ad) ?: parse(p.ed) ?: parse(p.sd)

        fun bestArrival(p: NextFlightPayload): Instant? = parse(p.aa) ?: parse(p.ea) ?: parse(p.sa)

        /** Until when the payload is worth showing; null when it carries no times at all (shown as is). */
        fun relevantUntil(p: NextFlightPayload): Instant? = when (p.st) {
            "cancelled" -> bestDeparture(p)?.plus(CANCELLED_VISIBLE)
            else -> bestArrival(p)?.plus(AFTER_LANDING) ?: bestDeparture(p)?.plus(NO_ARRIVAL_VISIBLE)
        }

        /** For a payload without `sw` (an older phone build): derived from the server status alone. */
        fun fallbackStatus(p: NextFlightPayload): WatchStatus = when (p.st) {
            "cancelled" -> WatchStatus.CANCELLED
            "diverted" -> WatchStatus.DIVERTED
            "landed", "arrived" -> WatchStatus.LANDED
            "departed" -> WatchStatus.DEPARTED
            "en_route", "approaching" -> WatchStatus.IN_AIR
            "gate_closed" -> WatchStatus.GATE_CLOSED
            "boarding" -> WatchStatus.BOARDING
            else -> if ((p.dm ?: 0) >= DELAY_THRESHOLD_MINUTES) WatchStatus.DELAYED else WatchStatus.ON_TIME
        }

        fun fallbackTone(status: WatchStatus, estimated: Boolean): WatchTone = when {
            estimated -> WatchTone.ESTIMATED
            status == WatchStatus.CANCELLED || status == WatchStatus.DIVERTED -> WatchTone.CANCELLED
            status == WatchStatus.DELAYED -> WatchTone.DELAYED
            status in setOf(WatchStatus.BOARDING, WatchStatus.GATE_CLOSED, WatchStatus.DEPARTED, WatchStatus.IN_AIR) -> WatchTone.ACTIVE
            else -> WatchTone.ON_TIME
        }

        /** Same as the phone: delays below this read "On time". */
        const val DELAY_THRESHOLD_MINUTES = 15
    }
}
