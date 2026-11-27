package app.flytrack.core.model

import kotlin.time.Instant

/** `user_flights.cabin`. */
enum class Cabin(val wire: String) {
    ECONOMY("economy"),
    PREMIUM_ECONOMY("premium_economy"),
    BUSINESS("business"),
    FIRST("first");

    companion object {
        fun fromWire(value: String?): Cabin? = entries.firstOrNull { it.wire == value }
    }
}

/** `user_flights.source`: how the flight was added. */
enum class TrackSource(val wire: String) {
    MANUAL("manual"),
    PDF("pdf"),
    SHARE("share"),
    SCREENSHOT("screenshot"),
    GMAIL("gmail"),

    /** A boarding-pass barcode read by the camera (P1 contract, section 5). */
    SCAN("scan"),

    /** Added by forwarding a booking email to the user's HeyGate address (P2 wave 2, section 2). */
    EMAIL("email"),

    /** Added from a device-calendar suggestion (P3 A3). */
    CALENDAR("calendar"),

    /** Added by TripIt sync (P4 section 2); the server inserts these rows. */
    TRIPIT("tripit");

    companion object {
        fun fromWire(value: String?): TrackSource = entries.firstOrNull { it.wire == value } ?: MANUAL
    }
}

/** One row of `public.user_flights`: the signed-in user tracks [flightId]. */
data class TrackedFlight(
    val id: String,
    val userId: String,
    val flightId: String,
    val pnr: String?,
    val seat: String?,
    val cabin: Cabin?,
    val source: TrackSource,
    /** Only explicit entries are stored; `false` mutes that alert type, anything else means "notify". */
    val notifyPrefs: Map<AlertType, Boolean>,
    val note: String?,
    val archivedAt: Instant?,
    val createdAt: Instant?,
) {
    val isArchived: Boolean get() = archivedAt != null

    /** Same rule as the server's fan-out: an alert is sent unless its pref is exactly `false`. */
    fun notifies(type: AlertType): Boolean = notifyPrefs[type] != false
}

/** A tracked flight together with its flight data, as shown in the list and on the detail screen. */
data class TrackedFlightDetails(
    val tracking: TrackedFlight,
    val flight: Flight,
)

/** Optional details the user can attach when tracking a flight (all validated by the repository). */
data class TrackExtras(
    val pnr: String? = null,
    val seat: String? = null,
    val cabin: Cabin? = null,
    val source: TrackSource = TrackSource.MANUAL,
    val note: String? = null,
    /**
     * P3 B6: a past flight added for Passport. It is inserted with `archived_at = now()`, so it is never an
     * active flight (no alerts, no tracking cap) and an existing row is never un-archived for it.
     */
    val archived: Boolean = false,
) {
    companion object {
        const val MAX_SEAT_LENGTH = 5
        const val MAX_NOTE_LENGTH = 200
    }
}

private val PNR_REGEX = Regex("^[A-Z0-9]{5,8}$")

/**
 * Upper-cases and strips spaces/dashes, then applies the database rule `^[A-Z0-9]{5,8}$`.
 * Returns null when the text cannot be a PNR.
 */
fun normalizePnr(input: String?): String? {
    val cleaned = input?.filterNot { it.isWhitespace() || it == '-' }?.uppercase().orEmpty()
    return cleaned.takeIf { PNR_REGEX.matches(it) }
}
