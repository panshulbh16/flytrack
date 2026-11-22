package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightPhase
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.bestArrival
import app.flytrack.core.model.bestDeparture
import app.flytrack.core.model.hasLanded
import app.flytrack.core.time.zoneOf
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The card at the top of the list: the flight in the air, else the next one to take off. */
data class NextFlightUiModel(
    val card: FlightCardUiModel,
    val countdown: Countdown,
)

/** The home list, grouped. */
data class FlightSections(
    val next: NextFlightUiModel?,
    /** Soonest departure first. Includes flights in the air and those that landed a short while ago. */
    val upcoming: List<FlightCardUiModel>,
    /** Most recent first. */
    val past: List<FlightCardUiModel>,
) {
    val isEmpty: Boolean get() = upcoming.isEmpty() && past.isEmpty()
}

/** A landed flight stays under "Upcoming" this long, so its baggage belt is still one glance away. */
internal val JUST_LANDED_WINDOW = 2.hours

/** Without any landing data, a flight counts as over this long after its last known arrival time. */
internal val STALE_AFTER_ARRIVAL = 12.hours

/** Used instead of [STALE_AFTER_ARRIVAL] when only a departure time is known. */
internal val STALE_AFTER_DEPARTURE = 24.hours

/** True once the journey is over for the traveller: it belongs under "Past". */
fun Flight.isPast(now: Instant): Boolean {
    val arrival = bestArrival
    val departure = bestDeparture
    return when {
        // A cancelled flight stays visible up front until the day it was meant to leave is over.
        status == FlightStatus.CANCELLED -> now >= endOfFlightDate()
        hasLanded -> arrival == null || now - arrival >= JUST_LANDED_WINDOW
        arrival != null -> now - arrival >= STALE_AFTER_ARRIVAL
        departure != null -> now - departure >= STALE_AFTER_DEPARTURE
        else -> now >= endOfFlightDate() + STALE_AFTER_ARRIVAL
    }
}

/** Midnight after the flight date at the departure airport. */
private fun Flight.endOfFlightDate(): Instant {
    val nextDay = java.time.LocalDate.parse(flightDate.toString()).plusDays(1)
    return Instant.fromEpochSeconds(nextDay.atStartOfDay(zoneOf(depTz)).toEpochSecond())
}

/**
 * Groups the tracked flights for the home list.
 * @param newFields per flight id, the fields to emphasise (see [FlightChangeTracker])
 */
fun buildFlightSections(
    tracked: List<TrackedFlightDetails>,
    now: Instant,
    newFields: (Flight) -> Set<FlightField> = { emptySet() },
): FlightSections {
    val (past, upcoming) = tracked.map { it.flight }.partition { it.isPast(now) }
    val upcomingCards = upcoming
        .sortedWith(compareBy<Flight, Instant?>(nullsLast(naturalOrder())) { it.bestDeparture })
        .map { it.toCardUiModel(now, newFields(it)) }
    val pastCards = past
        .sortedWith(compareBy<Flight, Instant?>(nullsLast(reverseOrder())) { it.bestDeparture })
        .map { it.toCardUiModel(now, newFields(it)) }
    return FlightSections(next = nextFlight(upcomingCards), upcoming = upcomingCards, past = pastCards)
}

/** A flight in the air comes first; otherwise the soonest one that still has a take-off ahead. */
private fun nextFlight(upcoming: List<FlightCardUiModel>): NextFlightUiModel? {
    val candidates = upcoming.filter { it.countdown != null }
    val pick = candidates.firstOrNull { it.phase == FlightPhase.IN_AIR } ?: candidates.firstOrNull()
    return pick?.let { NextFlightUiModel(card = it, countdown = checkNotNull(it.countdown)) }
}
