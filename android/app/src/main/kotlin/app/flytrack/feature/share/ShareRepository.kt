package app.flytrack.feature.share

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.ShareCheckinApi
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Result of asking the backend for a public link to a flight. */
sealed interface ShareResult {
    data class Shared(val url: String) : ShareResult

    /** The RPC refused: the caller does not track this flight. */
    data object NotTracked : ShareResult
    data object Offline : ShareResult
    data object NotConfigured : ShareResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : ShareResult
}

/** Where shared links point: `SHARE_BASE_URL` from BuildConfig. */
data class ShareConfig(val baseUrl: String)

interface ShareRepository {
    /** `create_flight_share`: the existing active link of this user's flight, or a new one. Never throws. */
    suspend fun createShare(flightId: String): ShareResult
}

class SupabaseShareRepository @Inject constructor(
    private val api: ShareCheckinApi,
    private val auth: AuthRepository,
    private val config: ShareConfig,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ShareRepository {

    override suspend fun createShare(flightId: String): ShareResult = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext ShareResult.NotConfigured
            AuthResult.Offline -> return@withContext ShareResult.Offline
            is AuthResult.Failed -> return@withContext ShareResult.Failed(signedIn.message)
        }
        try {
            ShareResult.Shared(shareUrl(config.baseUrl, api.createFlightShare(flightId)))
        } catch (e: BackendException) {
            e.toShareResult()
        }
    }

    private fun BackendException.toShareResult(): ShareResult = when (this) {
        is BackendException.NotConfigured -> ShareResult.NotConfigured
        is BackendException.Network -> ShareResult.Offline
        is BackendException.Malformed -> ShareResult.Failed(message)
        is BackendException.Http -> if (isNotTracking) ShareResult.NotTracked else ShareResult.Failed(message)
    }

    private val BackendException.Http.isNotTracking: Boolean
        get() = code == PG_INSUFFICIENT_PRIVILEGE || code == PG_NO_DATA_FOUND || message?.contains("not_tracking") == true

    private companion object {
        const val PG_INSUFFICIENT_PRIVILEGE = "42501"
        const val PG_NO_DATA_FOUND = "P0002"
    }
}
