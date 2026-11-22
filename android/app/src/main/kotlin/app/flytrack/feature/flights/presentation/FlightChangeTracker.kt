package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.Flight
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Remembers which terminal/gate/belt values became known or changed while the app is running, so
 * the list and the detail screen can make the same value stand out for a while.
 *
 * The first sighting of a flight is its baseline and marks nothing. In practice that baseline is
 * the row cached in Room, and the refresh that follows app start (or a push sync) brings the
 * change. The state lives in memory only: there is no alert history on the device to derive it
 * from after a process death.
 */
@Singleton
class FlightChangeTracker @Inject constructor() {
    private val lastValues = HashMap<String, Map<FlightField, String?>>()
    private val changedAt = HashMap<String, MutableMap<FlightField, Instant>>()

    /** Records the flight's current values and returns the fields that are still "new" at [now]. */
    @Synchronized
    fun record(flight: Flight, now: Instant): Set<FlightField> {
        val current = FlightField.entries.associateWith { flight.valueOf(it) }
        val previous = lastValues.put(flight.id, current)
        val changes = changedAt.getOrPut(flight.id) { mutableMapOf() }
        if (previous != null) {
            for ((field, value) in current) {
                when {
                    value == null -> changes.remove(field)
                    value != previous[field] -> changes[field] = now
                }
            }
        }
        changes.values.removeAll { now - it >= HIGHLIGHT_WINDOW }
        return changes.keys.toSet()
    }

    companion object {
        /** How long a new value stands out. */
        val HIGHLIGHT_WINDOW: Duration = 30.minutes
    }
}
