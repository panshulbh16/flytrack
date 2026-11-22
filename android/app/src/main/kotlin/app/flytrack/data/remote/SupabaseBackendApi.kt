package app.flytrack.data.remote

import app.flytrack.data.remote.dto.AirportDto
import app.flytrack.data.remote.dto.FlightViewDto
import app.flytrack.data.remote.dto.FunctionErrorDto
import app.flytrack.data.remote.dto.ProfileDto
import app.flytrack.data.remote.dto.RegisterDeviceParams
import app.flytrack.data.remote.dto.SearchFlightRequest
import app.flytrack.data.remote.dto.SearchFlightResponse
import app.flytrack.data.remote.dto.UnregisterDeviceParams
import app.flytrack.data.remote.dto.UserFlightDto
import app.flytrack.data.remote.dto.UserFlightInsertDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** [BackendApi] on supabase-kt. Stateless: the session lives in the client's Auth plugin. */
class SupabaseBackendApi(
    private val provider: SupabaseClientProvider,
    private val json: Json,
) : BackendApi {

    override suspend fun invokeFunction(name: String, jsonBody: String): String = call { client ->
        val response = client.functions.invoke(name) {
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw functionError(response.status.value, text)
        text
    }

    override suspend fun searchFlight(flight: String, date: String): List<FlightViewDto> {
        val body = json.encodeToString(SearchFlightRequest.serializer(), SearchFlightRequest(flight, date))
        val text = invokeFunction(SEARCH_FLIGHT, body)
        return decodeOrMalformed(SearchFlightResponse.serializer(), text).flights
    }

    override suspend fun flightViews(ids: Collection<String>): List<FlightViewDto> = call { client ->
        // Chunked so the `id=in.(...)` query string stays far below URL length limits.
        ids.distinct().chunked(ID_CHUNK).flatMap { chunk ->
            val result = client.postgrest.from(FLIGHT_VIEW).select { filter { isIn("id", chunk) } }
            decode(ListSerializer(FlightViewDto.serializer()), result.data)
        }
    }

    override suspend fun userFlights(): List<UserFlightDto> = call { client ->
        decode(USER_FLIGHTS_LIST, client.postgrest.from(USER_FLIGHTS).select().data)
    }

    override suspend fun userFlightByFlightId(flightId: String): UserFlightDto? = call { client ->
        val result = client.postgrest.from(USER_FLIGHTS).select { filter { eq("flight_id", flightId) } }
        decode(USER_FLIGHTS_LIST, result.data).firstOrNull()
    }

    override suspend fun insertUserFlight(body: UserFlightInsertDto): UserFlightDto = call { client ->
        val row = json.encodeToJsonElement(UserFlightInsertDto.serializer(), body)
        val result = client.postgrest.from(USER_FLIGHTS).insert(buildJsonArray { add(row) }) { select() }
        decode(USER_FLIGHTS_LIST, result.data).firstOrNull()
            ?: throw BackendException.Malformed(IllegalStateException("insert returned no row"))
    }

    override suspend fun updateUserFlight(id: String, patch: JsonObject): UserFlightDto? = call { client ->
        val result = client.postgrest.from(USER_FLIGHTS).update(patch) {
            filter { eq("id", id) }
            select()
        }
        decode(USER_FLIGHTS_LIST, result.data).firstOrNull()
    }

    override suspend fun deleteUserFlight(id: String): Unit = call { client ->
        client.postgrest.from(USER_FLIGHTS).delete { filter { eq("id", id) } }
    }

    override suspend fun airports(iatas: Collection<String>): List<AirportDto> = call { client ->
        iatas.distinct().chunked(ID_CHUNK).flatMap { chunk ->
            val result = client.postgrest.from(AIRPORTS).select { filter { isIn("iata", chunk) } }
            decode(ListSerializer(AirportDto.serializer()), result.data)
        }
    }

    override suspend fun profile(userId: String): ProfileDto? = call { client ->
        val result = client.postgrest.from(PROFILES).select { filter { eq("id", userId) } }
        decode(ListSerializer(ProfileDto.serializer()), result.data).firstOrNull()
    }

    override suspend fun registerDevice(params: RegisterDeviceParams): String = call { client ->
        val args = json.encodeToJsonElement(RegisterDeviceParams.serializer(), params).jsonObject
        decode(String.serializer(), client.postgrest.rpc(RPC_REGISTER_DEVICE, args).data)
    }

    override suspend fun unregisterDevice(token: String): Unit = call { client ->
        val args = json.encodeToJsonElement(UnregisterDeviceParams.serializer(), UnregisterDeviceParams(token)).jsonObject
        client.postgrest.rpc(RPC_UNREGISTER_DEVICE, args)
    }

    override suspend fun setDeviceNotificationsEnabled(token: String, enabled: Boolean): Boolean = call { client ->
        val result = client.postgrest.from(DEVICES).update(buildJsonObject { put("notifications_enabled", enabled) }) {
            filter { eq("fcm_token", token) }
            select()
        }
        decode(JsonArray.serializer(), result.data).isNotEmpty()
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    private fun <T> decodeOrMalformed(serializer: KSerializer<T>, text: String): T = try {
        decode(serializer, text)
    } catch (e: SerializationException) {
        throw BackendException.Malformed(e)
    }

    /** Edge Functions answer errors as `{ error, message }`; anything else keeps just the status. */
    private fun functionError(status: Int, body: String?): BackendException.Http {
        val dto = body?.let { runCatching { json.decodeFromString(FunctionErrorDto.serializer(), it) }.getOrNull() }
        return BackendException.Http(status = status, code = dto?.error, message = dto?.message)
    }

    /** Runs [block] against the client and converts every failure into a [BackendException]. */
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
            // The response body was buffered by Ktor, so it can be read again after supabase-kt did.
            val body = runCatching { e.response.bodyAsText() }.getOrNull() ?: e.error
            throw functionError(e.statusCode, body)
        } catch (e: SerializationException) {
            throw BackendException.Malformed(e)
        } catch (e: IOException) {
            throw BackendException.Network(e)
        }
    }

    private companion object {
        const val SEARCH_FLIGHT = "search-flight"
        const val FLIGHT_VIEW = "flight_view"
        const val USER_FLIGHTS = "user_flights"
        const val AIRPORTS = "airports"
        const val PROFILES = "profiles"
        const val DEVICES = "devices"
        const val RPC_REGISTER_DEVICE = "register_device"
        const val RPC_UNREGISTER_DEVICE = "unregister_device"
        const val ID_CHUNK = 100
        val USER_FLIGHTS_LIST = ListSerializer(UserFlightDto.serializer())
    }
}
