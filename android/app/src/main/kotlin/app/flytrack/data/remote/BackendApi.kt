package app.flytrack.data.remote

import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.dto.UserFlightDto
import app.flytrack.data.remote.dto.UserFlightInsertDto
import kotlinx.serialization.json.JsonObject

/**
 * Every backend call the app makes, as plain DTOs. The only implementation talks to Supabase;
 * repositories depend on this interface so they can be unit-tested on the JVM with a fake.
 *
 * All functions throw [BackendException] and nothing else (cancellation aside).
 */
interface BackendApi {
    /**
     * `POST /functions/v1/<name>` with a JSON body, as the signed-in user. Returns the response
     * body text. A non-2xx answer becomes [BackendException.Http] whose `code` and `message` are the
     * function's `{ "error", "message" }` fields (null when the body has another shape).
     *
     * This is the generic door for Edge Functions that have no dedicated method here (for example
     * `parse-ticket`): keep the request/response DTOs next to the feature that owns the function.
     */
    suspend fun invokeFunction(name: String, jsonBody: String): String

    /** `POST /functions/v1/search-flight`. Error codes arrive as [BackendException.Http.code]. */
    suspend fun searchFlight(flight: String, date: String): List<FlightViewDto>

    /** `flight_view` rows for the given flight ids. */
    suspend fun flightViews(ids: Collection<String>): List<FlightViewDto>

    /** All `user_flights` rows of the signed-in user (RLS), archived ones included. */
    suspend fun userFlights(): List<UserFlightDto>

    /** The signed-in user's row for one flight, or null. */
    suspend fun userFlightByFlightId(flightId: String): UserFlightDto?

    suspend fun insertUserFlight(body: UserFlightInsertDto): UserFlightDto

    /** PATCH of the user-editable columns (pnr, seat, cabin, notify_prefs, note, archived_at). Null when the row is gone. */
    suspend fun updateUserFlight(id: String, patch: JsonObject): UserFlightDto?

    suspend fun deleteUserFlight(id: String)

    /** `public.airports` rows for the given IATA codes. */
    suspend fun airports(iatas: Collection<String>): List<AirportDto>

    /** The `profiles` row of [userId] (RLS only ever returns the caller's own). */
    suspend fun profile(userId: String): ProfileDto?

    /** `register_device` RPC. Returns the device row's id. */
    suspend fun registerDevice(params: RegisterDeviceParams): String

    /** `unregister_device` RPC. */
    suspend fun unregisterDevice(token: String)

    /** Sets `devices.notifications_enabled` for this phone's row. False when no such row exists. */
    suspend fun setDeviceNotificationsEnabled(token: String, enabled: Boolean): Boolean
}
