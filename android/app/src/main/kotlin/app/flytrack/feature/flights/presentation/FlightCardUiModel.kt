package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.DELAY_THRESHOLD_MINUTES
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.model.TimeKind
import app.flytrack.core.time.RelativeDay
import app.flytrack.core.time.RelativeTime
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

// Everything a flight card shows, as typed values. No Android types and no display text: the
// composables in feature.flights.ui turn these into localised strings, so the decisions here can
// be unit-tested on the JVM.

/** The one key word of a flight, shown in italic serif. */
enum class FlightStatusWord {
    /** Status unknown to the provider: only the timetable is known. */
    SCHEDULE_ONLY,

    /** The provider knows the flight but has not published an estimate yet. */
    SCHEDULED,
    ON_TIME,
    DELAYED,
    EARLY,
    CHECK_IN_OPEN,
    BOARDING,
    GATE_CLOSED,
    DEPARTED,
    IN_THE_AIR,
    LANDING_SOON,
    LANDED,
    ARRIVED,
    CANCELLED,
    DIVERTED,
}

/** How a departure or arrival compares with the timetable. */
sealed interface Punctuality {
    data class Late(val minutes: Int) : Punctuality
    data class Early(val minutes: Int) : Punctuality

    /** Within [DELAY_THRESHOLD_MINUTES] of the timetable either way. */
    data object OnTime : Punctuality

    /** No estimate or actual time to compare with the timetable. */
    data object Unknown : Punctuality

    companion object {
        /** [delayMinutes] as the model's `departureDelayMinutes` / `arrivalDelayMinutes` give it (negative = early). */
        fun of(delayMinutes: Int?): Punctuality = when {
            delayMinutes == null -> Unknown
            delayMinutes >= DELAY_THRESHOLD_MINUTES -> Late(delayMinutes)
            delayMinutes <= -DELAY_THRESHOLD_MINUTES -> Early(-delayMinutes)
            else -> OnTime
        }
    }
}

/** The plain-language sentence under the status word ("Running 25 min late"). */
sealed interface StatusLine {
    /** Nothing live yet: the times shown are the timetable. */
    data object ScheduleOnly : StatusLine

    data class BeforeDeparture(val departure: Punctuality) : StatusLine
    data class CheckInOpen(val departure: Punctuality) : StatusLine
    data class Boarding(val gate: String?, val departure: Punctuality) : StatusLine
    data class GateClosed(val departure: Punctuality) : StatusLine

    /** Airborne. [arrival] is preferred; [departure] is the fallback while no arrival estimate exists. */
    data class InAir(val arrival: Punctuality, val departure: Punctuality) : StatusLine

    /** On the ground at the destination; [atGate] once the aircraft is parked. */
    data class Landed(val arrival: Punctuality, val atGate: Boolean) : StatusLine
    data object Cancelled : StatusLine

    /** The backend may or may not know the new airport yet, so no airport is named. */
    data class Diverted(val landed: Boolean) : StatusLine
}

/** What the flight is counting down to. */
sealed interface Countdown {
    /** Takes off within 24 hours: "in 35 min", "in 3 hr". */
    data class Departs(val at: RelativeTime) : Countdown

    /** Takes off later than that: "Tomorrow", "in 3 days" (calendar days at the departure airport). */
    data class DepartsOn(val day: RelativeDay) : Countdown

    /** Airborne: "in 35 min" until touchdown. */
    data class Lands(val at: RelativeTime) : Countdown
}

/** A place detail that can become known or change during the day. */
enum class FlightField { DEP_TERMINAL, DEP_GATE, CHECK_IN_DESK, ARR_TERMINAL, ARR_GATE, BAGGAGE_BELT }

/**
 * One terminal/gate/belt value.
 * @property value null while the airport has not announced it
 * @property isNew the value appeared or changed a short while ago and should stand out
 */
data class FlightInfoUiModel(
    val field: FlightField,
    val value: String?,
    val isNew: Boolean = false,
)

/**
 * A departure or arrival time, already formatted in the airport's zone.
 * @property time the best known time, "18:50"
 * @property kind how sure [time] is
 * @property replacedTime the timetable time it replaces, set only when the shift is large enough to
 *   call the flight late or early; shown struck through
 * @property date calendar date of [time] at the airport
 * @property dayOffset arrival only: calendar days after the departure date ("+1")
 */
data class FlightTimeUiModel(
    val time: String,
    val kind: TimeKind,
    val replacedTime: String?,
    val date: LocalDate,
    val dayOffset: Int = 0,
)

/** Everything the flight card shows. Built by [toCardUiModel]. */
data class FlightCardUiModel(
    val flightId: String,
    /** "6E 2341". */
    val designator: String,
    val airlineName: String?,
    val depCode: String,
    val arrCode: String,
    /** City when the airport is known, else the IATA code. */
    val depCity: String,
    val arrCity: String,
    val phase: FlightPhase,
    val statusWord: FlightStatusWord,
    val tone: FlightStatusTone,
    val statusLine: StatusLine,
    val departure: FlightTimeUiModel?,
    val arrival: FlightTimeUiModel?,
    /** Calendar date of the best known departure at the departure airport. */
    val departureDate: LocalDate,
    /** [departureDate] relative to today at the departure airport. */
    val departureDay: RelativeDay,
    val duration: Duration?,
    /** Known values that matter in the current phase: departure side until take-off, arrival side after. */
    val info: List<FlightInfoUiModel>,
    val countdown: Countdown?,
    /** Fraction flown (0..1) while airborne, else null. */
    val progress: Float?,
    /** When the backend last checked or changed this flight. */
    val updatedAt: Instant?,
    /** The "now" this model was computed for; relative texts are relative to it. */
    val asOf: Instant,
)
