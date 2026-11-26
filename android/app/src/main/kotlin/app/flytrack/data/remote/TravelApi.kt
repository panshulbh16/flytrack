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

/** The quiet-hours and alert-language columns of `profiles` (P2 contract, sections 3 and 4). */
@Serializable
data class AlertPrefsDto(
    @SerialName("quiet_hours_enabled") val quietHoursEnabled: Boolean = false,
    /** Postgres `time`: "23:00:00". */
    @SerialName("quiet_start") val quietStart: String? = null,
    @SerialName("quiet_end") val quietEnd: String? = null,
    @SerialName("quiet_tz") val quietTz: String? = null,
    @SerialName("alert_language") val alertLanguage: String? = null,
)

/**
 * Backend calls of the P2 passport, airport-health and alert-settings features. Kept apart from
 * [BackendApi] (like [ShareCheckinApi]) so the foundation's interface and its fakes do not change.
 *
 * Every function throws [BackendException] and nothing else (cancellation aside).
 */
interface TravelApi {
    /** `my_passport(p_year)` RPC: the raw jsonb object. */
    suspend fun myPassport(year: Int?): JsonObject

    /** `get_airport_health(p_iata)` RPC: the raw jsonb object, or null when the airport is unknown. */
    suspend fun airportHealth(iata: String): JsonObject?

    /** The caller's own alert settings (RLS returns only their row); null when the row is missing. */
    suspend fun alertPrefs(userId: String): AlertPrefsDto?

    /**
     * PATCH of the user-editable alert columns (column grants). [patch] holds only the columns to change.
     * Returns the row after the update, null when no row matched.
     */
    suspend fun updateAlertPrefs(userId: String, patch: JsonObject): AlertPrefsDto?
}

class SupabaseTravelApi(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : TravelApi {

    override suspend fun myPassport(year: Int?): JsonObject = call { client ->
        val args = buildJsonObject { if (year != null) put("p_year", year) }
        val element = json.parseToJsonElement(client.postgrest.rpc(RPC_MY_PASSPORT, args).data)
        element as? JsonObject ?: throw BackendException.Malformed(IllegalStateException("my_passport returned ${element::class.simpleName}"))
    }

    override suspend fun airportHealth(iata: String): JsonObject? = call { client ->
        val args = buildJsonObject { put("p_iata", iata) }
        when (val element: JsonElement = json.parseToJsonElement(client.postgrest.rpc(RPC_AIRPORT_HEALTH, args).data)) {
            is JsonObject -> element
            JsonNull -> null
            else -> throw BackendException.Malformed(IllegalStateException("get_airport_health returned ${element::class.simpleName}"))
        }
    }

    override suspend fun alertPrefs(userId: String): AlertPrefsDto? = call { client ->
        val result = client.postgrest.from(PROFILES).select(Columns.list(ALERT_COLUMNS)) { filter { eq("id", userId) } }
        json.decodeFromString(ListSerializer(AlertPrefsDto.serializer()), result.data).firstOrNull()
    }

    override suspend fun updateAlertPrefs(userId: String, patch: JsonObject): AlertPrefsDto? = call { client ->
        val result = client.postgrest.from(PROFILES).update(patch) {
            filter { eq("id", userId) }
            select(Columns.list(ALERT_COLUMNS))
        }
        json.decodeFromString(ListSerializer(AlertPrefsDto.serializer()), result.data).firstOrNull()
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
        const val RPC_MY_PASSPORT = "my_passport"
        const val RPC_AIRPORT_HEALTH = "get_airport_health"
        const val PROFILES = "profiles"
        val ALERT_COLUMNS = listOf("quiet_hours_enabled", "quiet_start", "quiet_end", "quiet_tz", "alert_language")
    }
}
