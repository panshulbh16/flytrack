package app.flytrack.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** One row of `public.checkin_rules` (P1 contract, section 3). */
@Serializable
data class CheckinRuleDto(
    @SerialName("carrier_iata") val carrierIata: String,
    @SerialName("opens_hours_before") val opensHoursBefore: Int,
    @SerialName("closes_minutes_before") val closesMinutesBefore: Int,
    @SerialName("closes_minutes_before_intl") val closesMinutesBeforeIntl: Int,
    @SerialName("checkin_url") val checkinUrl: String? = null,
    val verified: Boolean = false,
    val notes: String? = null,
)

/**
 * Backend calls of the share and check-in features (P1 contract, sections 2 and 3). Kept apart from
 * [BackendApi] so the foundation's interface and its fakes do not change.
 *
 * Every function throws [BackendException] and nothing else (cancellation aside).
 */
interface ShareCheckinApi {
    /** `create_flight_share(p_flight_id)` RPC: the caller's active share token for that flight (created when missing). */
    suspend fun createFlightShare(flightId: String): String

    /** All rows of `checkin_rules` (a handful; readable by authenticated users). */
    suspend fun checkinRules(): List<CheckinRuleDto>
}

class SupabaseShareCheckinApi(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : ShareCheckinApi {

    override suspend fun createFlightShare(flightId: String): String = call { client ->
        val args = buildJsonObject { put("p_flight_id", flightId) }
        val token = json.decodeFromString(String.serializer(), client.postgrest.rpc(RPC_CREATE_FLIGHT_SHARE, args).data)
        if (token.isBlank()) throw BackendException.Malformed(IllegalStateException("empty share token"))
        token
    }

    override suspend fun checkinRules(): List<CheckinRuleDto> = call { client ->
        json.decodeFromString(ListSerializer(CheckinRuleDto.serializer()), client.postgrest.from(CHECKIN_RULES).select().data)
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
        } catch (e: IOException) {
            throw BackendException.Network(e)
        }
    }

    private companion object {
        const val RPC_CREATE_FLIGHT_SHARE = "create_flight_share"
        const val CHECKIN_RULES = "checkin_rules"
    }
}
