package app.flytrack.data.repo

import app.flytrack.core.model.AlertType
import app.flytrack.core.model.Flight
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.model.TrackedFlightDetails
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.SearchResult
import app.flytrack.core.result.TrackResult
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

/**
 * Flights and the user's tracking of them. Room is the single source of truth: the `observe*`
 * flows read Room only, every command writes its network result into Room, and the flows re-emit.
 *
 * Flights are always addressed by [Flight.id] (`flights.id`), which is also what push messages
 * and the FlightDetail route carry. Commands never throw; they return typed results.
 */
interface FlightRepository {
    /** Non-archived tracked flights with flight and airports, soonest departure first. */
    fun observeTrackedFlights(): Flow<List<TrackedFlightDetails>>

    /** One tracked flight (archived included). Emits null while it is not tracked or not cached yet. */
    fun observeTrackedFlight(flightId: String): Flow<TrackedFlightDetails?>

    /** Any cached flight, tracked or not (search results, the inbound aircraft's flight). */
    fun observeFlight(flightId: String): Flow<Flight?>

    /**
     * Looks a flight up through the `search-flight` Edge Function. [flightText] is free text
     * ("6e2341", "AI-101") and is validated with the same parser as the server before any
     * network call. Found legs are cached in Room, so [observeFlight] works for them.
     */
    suspend fun search(flightText: String, date: LocalDate): SearchResult

    /**
     * Starts tracking (inserts into `user_flights`). A flight that was archived earlier is
     * un-archived instead. [extras] are validated first (PNR `^[A-Z0-9]{5,8}$`, seat up to 5
     * characters, note up to 200).
     */
    suspend fun track(flightId: String, extras: TrackExtras = TrackExtras()): TrackResult

    /** Stops tracking for good (deletes the `user_flights` row). */
    suspend fun untrack(flightId: String): ActionResult

    /**
     * Archives (hidden from the list, no alerts, frees a slot of the tracking limit) or un-archives.
     * Un-archiving can return [ActionResult.TrackingLimitReached].
     */
    suspend fun archive(flightId: String, archived: Boolean = true): ActionResult

    /**
     * Mutes or un-mutes alert types for one flight. Only the given entries change (`false` = muted);
     * other stored preferences are kept.
     */
    suspend fun updateNotifyPrefs(flightId: String, prefs: Map<AlertType, Boolean>): ActionResult

    /**
     * Replaces PNR, seat, cabin and note of a tracked flight (null clears a field); `source` is
     * ignored. Returns [ActionResult.InvalidInput] for a value the database would reject.
     */
    suspend fun updateTrackingDetails(flightId: String, extras: TrackExtras): ActionResult

    /** Pulls `user_flights` and their `flight_view` rows into Room. Use for app start and pull-to-refresh. */
    suspend fun refresh(): ActionResult

    /**
     * Push-triggered update of one flight. When [compactFlightJson] (the `flight` value of an FCM
     * sync message) is given, it is merged into Room first, without any network; then the full
     * `flight_view` row is fetched. Succeeds when either step updated Room.
     */
    suspend fun applyServerSync(flightId: String, compactFlightJson: String? = null): ActionResult
}
