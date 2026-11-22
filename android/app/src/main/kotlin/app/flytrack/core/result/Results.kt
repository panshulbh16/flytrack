package app.flytrack.core.result

import app.flytrack.core.model.Flight
import app.flytrack.core.model.TrackedFlight

/** Outcome of a command or sync that returns no data. */
sealed interface ActionResult {
    data object Success : ActionResult

    /** No connection, or the request timed out. Safe to retry. */
    data object Offline : ActionResult

    /** SUPABASE_URL / SUPABASE_ANON_KEY are missing from the build (see BackendConfig). */
    data object NotConfigured : ActionResult

    /** The row to act on does not exist (any more). */
    data object NotFound : ActionResult

    /** Un-archiving would exceed the plan's limit of active flights. */
    data object TrackingLimitReached : ActionResult

    /** updateTrackingDetails only: a value breaks a database rule (see TrackExtras). Nothing was sent. */
    data class InvalidInput(val field: TrackField) : ActionResult

    /** Anything else. [message] is a technical English text for logs, never for the UI. */
    data class Failed(val message: String? = null) : ActionResult

    val isSuccess: Boolean get() = this is Success
}

/** Result of `FlightRepository.search`. */
sealed interface SearchResult {
    /** One entry per leg; multi-leg flights share a flight number and differ by departure airport. */
    data class Found(val legs: List<Flight>) : SearchResult

    /** The provider knows no such flight on that date. */
    data object NotFound : SearchResult

    /** The per-user daily search limit was hit. */
    data object RateLimited : SearchResult

    data class BadInput(val reason: BadInputReason) : SearchResult

    /** The flight-data provider or our backend failed. Worth retrying later. */
    data object ProviderError : SearchResult

    data object Offline : SearchResult

    data object NotConfigured : SearchResult
}

enum class BadInputReason {
    /** Not a flight number such as "6E 2341". */
    FLIGHT_NUMBER,

    /** Outside the window the backend accepts: two days back to one year ahead. */
    DATE,

    /** The server rejected the input for a reason this app version does not know. */
    OTHER,
}

/** Result of `FlightRepository.track`. */
sealed interface TrackResult {
    data class Tracked(val tracking: TrackedFlight) : TrackResult

    /** The user already tracks this flight; nothing was changed. */
    data class AlreadyTracked(val tracking: TrackedFlight) : TrackResult

    /** Free: 10 active flights, Pro: 50. [limitHint] is the server's English hint, for logs only. */
    data class TrackingLimitReached(val limitHint: String? = null) : TrackResult

    data class InvalidInput(val field: TrackField) : TrackResult

    /** The flight row no longer exists on the server. Search again. */
    data object FlightNotFound : TrackResult

    data object Offline : TrackResult

    data object NotConfigured : TrackResult

    data class Failed(val message: String? = null) : TrackResult
}

enum class TrackField { PNR, SEAT, NOTE }

/** Result of the AuthRepository commands. */
sealed interface AuthResult {
    data object Success : AuthResult

    data object Offline : AuthResult

    data object NotConfigured : AuthResult

    /** linkGoogle only: this Google account already belongs to another FlyTrack user. */
    data object GoogleAccountInUse : AuthResult

    data class Failed(val message: String? = null) : AuthResult

    val isSuccess: Boolean get() = this is Success
}
