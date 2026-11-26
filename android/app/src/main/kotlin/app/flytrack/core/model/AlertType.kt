package app.flytrack.core.model

/**
 * Mirrors EVENT_TYPES in supabase/functions/_shared/types.ts. The enum name is the wire value
 * (FCM data key `event_type`, and the keys of `user_flights.notify_prefs`).
 *
 * [critical] mirrors the CRITICAL list in notify.ts: critical alerts arrive on the
 * `flight_critical` channel, the rest on `flight_updates`.
 */
enum class AlertType(val critical: Boolean, val proOnly: Boolean = false) {
    DELAY(critical = true),
    DELAY_REDUCED(critical = false),
    SCHEDULE_CHANGE(critical = true),
    GATE_ASSIGNED(critical = false),
    GATE_CHANGE(critical = true),
    TERMINAL_CHANGE(critical = true),
    BOARDING(critical = true),
    DEPARTED(critical = false),
    LANDED(critical = false),
    BELT_ASSIGNED(critical = true),
    BELT_CHANGE(critical = true),
    CANCELLED(critical = true),
    DIVERTED(critical = true),
    REINSTATED(critical = true),
    INBOUND_LATE(critical = true, proOnly = true),
    CHECKIN_OPEN(critical = false),
    CHECKIN_CLOSING(critical = true);

    val wire: String get() = name

    companion object {
        fun fromWire(value: String?): AlertType? = entries.firstOrNull { it.name == value }
    }
}
