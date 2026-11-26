package app.flytrack.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** `user_flights.share_with_friends` of one row (P2 wave 2, section 1). */
@Serializable
data class ShareWithFriendsDto(
    @SerialName("share_with_friends") val shareWithFriends: Boolean = true,
)

/**
 * Backend calls of the P2 wave 2 features: friends, email forwarding, calendar feed, arrival forecast
 * (docs/P2_WAVE2_CONTRACTS.md sections 1 to 4). Kept apart from [BackendApi] (like [TravelApi]) so the
 * foundation's interface and its fakes do not change.
 *
 * Every function throws [BackendException] and nothing else (cancellation aside).
 */
interface Wave2Api {
    /**
     * Calls the RPC [name] with [args] and returns its JSON answer: [JsonNull] for `void` functions and SQL
     * nulls. The RPC names and argument names are those of the contract.
     */
    suspend fun rpc(name: String, args: JsonObject = JsonObject(emptyMap())): JsonElement

    /** The caller's own row for [flightId] (RLS); null when the user does not track it. */
    suspend fun shareWithFriends(userId: String, flightId: String): Boolean?

    /** PATCH of `share_with_friends` (column grant). Returns the value after the update; null when no row matched. */
    suspend fun setShareWithFriends(userId: String, flightId: String, share: Boolean): Boolean?
}

class SupabaseWave2Api(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : Wave2Api {

    override suspend fun rpc(name: String, args: JsonObject): JsonElement = call { client ->
        val body = client.postgrest.rpc(name, args).data
        if (body.isBlank()) JsonNull else json.parseToJsonElement(body)
    }

    override suspend fun shareWithFriends(userId: String, flightId: String): Boolean? = call { client ->
        val result = client.postgrest.from(USER_FLIGHTS).select(Columns.list(SHARE_COLUMN)) {
            filter {
                eq("user_id", userId)
                eq("flight_id", flightId)
            }
        }
        json.decodeFromString(ListSerializer(ShareWithFriendsDto.serializer()), result.data).firstOrNull()?.shareWithFriends
    }

    override suspend fun setShareWithFriends(userId: String, flightId: String, share: Boolean): Boolean? = call { client ->
        val result = client.postgrest.from(USER_FLIGHTS).update(buildJsonObject { put(SHARE_COLUMN, share) }) {
            filter {
                eq("user_id", userId)
                eq("flight_id", flightId)
            }
            select(Columns.list(SHARE_COLUMN))
        }
        json.decodeFromString(ListSerializer(ShareWithFriendsDto.serializer()), result.data).firstOrNull()?.shareWithFriends
    }

    /** Same error conversion as [SupabaseBackendApi]. */
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
        const val USER_FLIGHTS = "user_flights"
        const val SHARE_COLUMN = "share_with_friends"
    }
}
