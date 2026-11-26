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
    CHECKIN_CLOSING(critical = true),

    /** Fog or visibility < 1000 m at the departure or arrival airport (P2 contract, section 1). */
    FOG_ALERT(critical = true),

    /** After [DIVERTED]: the diversion airport became known (P2 contract, section 3). */
    DIVERTED_DEST(critical = true),

    // P3 (docs/P3_CONTRACTS.md section C): appended in exactly the order of EVENT_TYPES.

    /** Status `gate_closed` (A2, info). */
    GATE_CLOSED(critical = false),

    /** Runway time known: the aircraft is airborne (A2, info). */
    TAKEOFF(critical = false),

    /** On-block time known: the aircraft reached the arrival gate (A2, info). */
    AT_GATE(critical = false),

    /** The tail number changed before departure (A5, Pro). */
    AIRCRAFT_SWAP(critical = true, proOnly = true),

    /** A connection from this flight went from ok or tight to at risk (B4, Pro). */
    CONNECTION_AT_RISK(critical = true, proOnly = true);

    val wire: String get() = name

    companion object {
        fun fromWire(value: String?): AlertType? = entries.firstOrNull { it.name == value }
    }
}
