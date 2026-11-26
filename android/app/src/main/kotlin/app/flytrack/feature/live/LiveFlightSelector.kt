package app.flytrack.feature.live

import app.flytrack.core.model.Flight
import app.flytrack.core.model.FlightStatus
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.model.bestDeparture
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Picks the one flight the live notification and the widget show. Pure Kotlin.
 */
object LiveFlightSelector {

    /**
     * The single most relevant flight for the live notification: active (not archived), allowed
     * ([isEligible]: Pro, or the free trial flight), not dismissed by the user, and inside its live
     * window (T-3h until landed + 45 min). A flight in the air wins, then the next departure
     * (boarding before check-in), then the most recent landing.
     */
    fun selectLive(
        tracked: List<TrackedFlightDetails>,
        now: Instant,
        isEligible: (flightId: String) -> Boolean,
        dismissed: Set<String> = emptySet(),
    ): Flight? = mostRelevantInWindow(
        tracked.filter { !it.tracking.isArchived }.map { it.flight }.filter { it.id !in dismissed && isEligible(it.id) },
        now,
    )

    /**
     * The widget's flight (free for everyone): the current flight when one is inside its live
     * window (same order as [selectLive]), else the next departure. Cancelled flights stay visible
     * until an hour after their planned departure, so the widget never hides a cancellation.
     */
    fun selectWidget(tracked: List<TrackedFlightDetails>, now: Instant): Flight? {
        val active = tracked.filter { !it.tracking.isArchived }.map { it.flight }
        mostRelevantInWindow(active, now)?.let { return it }
        return active
            .filter { relevantUntil(it)?.let { end -> end > now } == true }
            .minByOrNull { it.bestDeparture ?: Instant.DISTANT_FUTURE }
    }

    /** Until when a flight is worth showing at all; null when it has no times. */
    fun relevantUntil(flight: Flight): Instant? = when {
        flight.status == FlightStatus.CANCELLED ->
            flight.bestDeparture?.plus(LiveRules.CANCELLED_VISIBLE_AFTER_DEPARTURE)
        else -> flight.liveTimes()?.windowEnd ?: flight.bestDeparture?.plus(FALLBACK_RELEVANCE)
    }

    /** The next moment a live window opens for one of [tracked], after [now]; null when none. */
    fun nextWindowStart(
        tracked: List<TrackedFlightDetails>,
        now: Instant,
        isEligible: (flightId: String) -> Boolean,
    ): Instant? = tracked.asSequence()
        .filter { !it.tracking.isArchived && isEligible(it.flight.id) }
        .mapNotNull { it.flight.liveWindow()?.windowStart }
        .filter { it > now }
        .minOrNull()

    private fun mostRelevantInWindow(flights: List<Flight>, now: Instant): Flight? = flights
        .mapNotNull { flight ->
            val window = flight.liveWindow() ?: return@mapNotNull null
            val progress = liveProgress(flight, now) ?: return@mapNotNull null
            if (now in window) flight to progress else null
        }
        .minWithOrNull(liveOrder(now))
        ?.first

    private fun liveOrder(now: Instant): Comparator<Pair<Flight, LiveProgress>> =
        compareBy<Pair<Flight, LiveProgress>> { stageRank(it.second.stage) }
            .thenBy { (flight, progress) ->
                val times = flight.liveTimes()!!
                // Upcoming: soonest departure first. Landed: most recent landing first.
                if (progress.stage == LiveStage.LANDED) now - times.arrival else times.departure - now
            }

    private fun stageRank(stage: LiveStage): Int = when (stage) {
        LiveStage.AIRBORNE -> 0
        LiveStage.BOARDING -> 1
        LiveStage.CHECK_IN -> 2
        LiveStage.LANDED -> 3
    }

    /** A flight with a departure but no usable arrival is kept for this long after departure. */
    private val FALLBACK_RELEVANCE = 4.hours
}
