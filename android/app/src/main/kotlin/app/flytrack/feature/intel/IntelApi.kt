package app.flytrack.feature.intel

import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.SupabaseClientProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Backend calls of the P3 flight-intelligence features (timeline, predictions, taxi times, aircraft,
 * connections, airport trends). Kept apart from the foundation's BackendApi so its fakes do not change.
 *
 * Every function throws [BackendException] and nothing else (cancellation aside).
 */
interface IntelApi {
    /** The RPC [name] with [args]; [JsonNull] for SQL null. Names and argument names are those of the contract. */
    suspend fun rpc(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement

    /** `aircraft_icao_type` and `aircraft_age_years` of the flight's `flight_view` row; [JsonNull] when not readable. */
    suspend fun aircraftColumns(flightId: String): JsonElement

    /** The flight's AIRCRAFT_SWAP rows of `flight_events` (payload, old/new value, created_at) as a JSON array. */
    suspend fun aircraftSwapEvents(flightId: String): JsonElement
}

class SupabaseIntelApi(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : IntelApi {

    override suspend fun rpc(name: String, args: JsonObject): JsonElement = call { client ->
        val body = client.postgrest.rpc(name, args).data
        if (body.isBlank()) JsonNull else json.parseToJsonElement(body)
    }

    override suspend fun aircraftColumns(flightId: String): JsonElement = call { client ->
        val result = client.postgrest.from(FLIGHT_VIEW).select(Columns.list(AIRCRAFT_COLUMNS)) { filter { eq("id", flightId) } }
        (json.parseToJsonElement(result.data) as? JsonArray)?.firstOrNull() ?: JsonNull
    }

    override suspend fun aircraftSwapEvents(flightId: String): JsonElement = call { client ->
        val result = client.postgrest.from(FLIGHT_EVENTS).select(Columns.list(EVENT_COLUMNS)) {
            filter {
                eq("flight_id", flightId)
                eq("type", AIRCRAFT_SWAP)
            }
        }
        json.parseToJsonElement(result.data)
    }

    /** Same error conversion as the other Supabase APIs. */
    private suspend fun <T> call(block: suspend (SupabaseClient) -> T): T {
        val client = provider.client ?: throw BackendException.NotConfigured()
        return try {
            block(client)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            throw e
        } catch (e: PostgrestRestException) {
            throw BackendException.Http(status = e.statusCode, code = e.code, message = e.error, hint = e.hint)
        } catch (e: RestException) {
            throw BackendException.Http(status = e.statusCode, code = null, message = e.error)
        } catch (e: SerializationException) {
            throw BackendException.Malformed(e)
        } catch (e: IllegalArgumentException) {
            // parseToJsonElement on a body that is not JSON
            throw BackendException.Malformed(e)
        } catch (e: IOException) {
            throw BackendException.Network(e)
        }
    }

    private companion object {
        const val FLIGHT_VIEW = "flight_view"
        const val FLIGHT_EVENTS = "flight_events"
        const val AIRCRAFT_SWAP = "AIRCRAFT_SWAP"
        val AIRCRAFT_COLUMNS = listOf("aircraft_icao_type", "aircraft_age_years")
        val EVENT_COLUMNS = listOf("payload", "old_value", "new_value", "created_at")
    }
}
