package app.flytrack.feature.flights.presentation

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.TrackedFlight

/**
 * The per-flight alert switches. The backend stores one boolean per event type in
 * `user_flights.notify_prefs` (keys = [AlertType.wire]; only an explicit `false` mutes), which is
 * too fine-grained for a settings list, so each switch covers the event types of one topic.
 * Every [AlertType] belongs to exactly one group (checked by a unit test).
 */
enum class AlertGroup(val types: List<AlertType>) {
    DELAYS(listOf(AlertType.DELAY, AlertType.DELAY_REDUCED, AlertType.SCHEDULE_CHANGE)),
    GATE(listOf(AlertType.GATE_ASSIGNED, AlertType.GATE_CHANGE, AlertType.TERMINAL_CHANGE)),
    BOARDING(listOf(AlertType.BOARDING)),
    TAKEOFF_AND_LANDING(listOf(AlertType.DEPARTED, AlertType.LANDED)),
    BAGGAGE(listOf(AlertType.BELT_ASSIGNED, AlertType.BELT_CHANGE)),
    DISRUPTION(listOf(AlertType.CANCELLED, AlertType.DIVERTED, AlertType.REINSTATED, AlertType.DIVERTED_DEST)),
    CHECK_IN(listOf(AlertType.CHECKIN_OPEN, AlertType.CHECKIN_CLOSING)),
    /** Fog and low visibility at either airport (free). */
    WEATHER(listOf(AlertType.FOG_ALERT)),
    INBOUND_AIRCRAFT(listOf(AlertType.INBOUND_LATE));

    /** The server sends these alerts to Pro users only. */
    val proOnly: Boolean get() = types.all { it.proOnly }

    /** The `notify_prefs` entries that switch the whole group on or off. */
    fun prefs(enabled: Boolean): Map<AlertType, Boolean> = types.associateWith { enabled }

    /** On while at least one of the group's alerts is still sent. */
    fun isEnabled(tracking: TrackedFlight): Boolean = types.any { tracking.notifies(it) }
}

/**
 * One alert switch.
 * @property locked a Pro-only alert shown to a free user: visible, but it cannot be switched
 */
data class AlertToggleUiModel(
    val group: AlertGroup,
    val enabled: Boolean,
    val locked: Boolean = false,
)
