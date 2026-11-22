package app.flytrack.core.model

/** Mirrors FLIGHT_STATUSES in supabase/functions/_shared/types.ts; [wire] is the exact server value. */
enum class FlightStatus(val wire: String) {
    UNKNOWN("unknown"),
    SCHEDULED("scheduled"),
    CHECK_IN("check_in"),
    BOARDING("boarding"),
    GATE_CLOSED("gate_closed"),
    DEPARTED("departed"),
    EN_ROUTE("en_route"),
    APPROACHING("approaching"),
    LANDED("landed"),
    ARRIVED("arrived"),
    CANCELLED("cancelled"),
    DIVERTED("diverted");

    companion object {
        /** A status this app version does not know yet must not crash the UI, so it degrades to [UNKNOWN]. */
        fun fromWire(value: String?): FlightStatus = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** Coarse stage of the journey, derived from status and actual times (see [phase]). */
enum class FlightPhase {
    UPCOMING,
    CHECK_IN,
    BOARDING,
    IN_AIR,
    LANDED,
    CANCELLED,
    DIVERTED,
}

/** Which of the theme's flight-status colour pairs a flight should be shown in (see [statusTone]). */
enum class FlightStatusTone {
    ON_TIME,
    DELAYED,
    CANCELLED,

    /** Boarding or in the air. */
    ACTIVE,

    /** No live data: the times shown are the schedule only. */
    ESTIMATED,
}
