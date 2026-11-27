package app.flytrack.feature.radar

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.inject.Inject

/**
 * `GET /functions/v1/radar` with the user's JWT. The foundation's `BackendApi.invokeFunction` only POSTs,
 * so this feature has its own small client. Throws [BackendException] and nothing else (cancellation aside);
 * an error answer is [BackendException.Http] with the body's `error` as `code`.
 */
interface RadarApi {
    suspend fun fetch(query: RadarQuery): String
}

class SupabaseRadarApi(private val provider: SupabaseClientProvider) : RadarApi {
    override suspend fun fetch(query: RadarQuery): String {
        val client = provider.client ?: throw BackendException.NotConfigured()
        return try {
            val response = client.functions.invoke(RadarContract.FUNCTION) {
                method = HttpMethod.Get
                query.parameters().forEach { (key, value) -> url.parameters.append(key, value) }
            }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) throw httpError(response.status.value, text)
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendException) {
            throw e
        } catch (e: RestException) {
            val body = runCatching { e.response.bodyAsText() }.getOrNull()
            throw httpError(e.statusCode, body)
        } catch (e: IOException) {
            throw BackendException.Network(e)
        }
    }

    private fun httpError(status: Int, body: String?): BackendException.Http {
        val dto = body?.let { runCatching { RadarJson.decodeFromString(ErrorBody.serializer(), it) }.getOrNull() }
        return BackendException.Http(status = status, code = dto?.error, message = dto?.message)
    }

    @Serializable
    private data class ErrorBody(@SerialName("error") val error: String? = null, val message: String? = null)
}

/** Radar requests as typed results. Never throws (cancellation aside). */
interface RadarRepository {
    suspend fun nearby(query: RadarQuery): RadarResult
}

class DefaultRadarRepository @Inject constructor(
    private val api: RadarApi,
    private val auth: AuthRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RadarRepository {
    override suspend fun nearby(query: RadarQuery): RadarResult = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext RadarResult.NotConfigured
            AuthResult.Offline -> return@withContext RadarResult.Offline
            is AuthResult.Failed -> return@withContext RadarResult.Failed(signedIn.message)
        }
        try {
            RadarResult.Ok(RadarParser.parse(api.fetch(query)))
        } catch (e: BackendException) {
            when (e) {
                is BackendException.NotConfigured -> RadarResult.NotConfigured
                is BackendException.Network -> RadarResult.Offline
                is BackendException.Malformed -> RadarResult.Failed(e.message)
                is BackendException.Http -> RadarParser.error(e.status, e.code)
            }
        } catch (e: SerializationException) {
            RadarResult.Failed("radar: unreadable answer")
        } catch (e: IllegalArgumentException) {
            RadarResult.Failed("radar: ${e.message}")
        }
    }
}
