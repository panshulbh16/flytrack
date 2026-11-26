package app.flytrack.feature.friends

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.Wave2Api
import app.flytrack.data.repo.AuthRepository
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of one P2 wave 2 backend call (friends, forwarding address, calendar feed, forecast).
 * Callers turn [Refused] codes into their own typed errors.
 */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>

    /** No connection: the request may never have reached the server. */
    data object Offline : RemoteResult<Nothing>

    /** The build has no backend keys (the app shell already shows a banner). */
    data object NotConfigured : RemoteResult<Nothing>

    /**
     * The server answered with an error.
     * @property code the Postgres error code ("P0002", "23505", ...)
     * @property message the raised message ("invalid_code", ...): technical, never shown
     */
    data class Refused(val code: String?, val message: String?) : RemoteResult<Nothing>

    /** Unreadable answer or sign-in failure. [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : RemoteResult<Nothing>
}

/** The value, or null for every error. */
fun <T> RemoteResult<T>.valueOrNull(): T? = (this as? RemoteResult.Ok<T>)?.value

/**
 * Runs [Wave2Api] calls after making sure the user is signed in, and turns every exception into a
 * [RemoteResult]. Never throws (cancellation aside).
 */
@Singleton
class Wave2Caller @Inject constructor(
    private val api: Wave2Api,
    private val auth: AuthRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** [block] gets the API and the signed-in user's id. */
    suspend fun <T> call(block: suspend (api: Wave2Api, userId: String) -> T): RemoteResult<T> = withContext(io) {
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success, AuthResult.GoogleAccountInUse -> Unit
            AuthResult.NotConfigured -> return@withContext RemoteResult.NotConfigured
            AuthResult.Offline -> return@withContext RemoteResult.Offline
            is AuthResult.Failed -> return@withContext RemoteResult.Failed(signedIn.message)
        }
        val userId = auth.currentUserId ?: return@withContext RemoteResult.Failed("no user id after sign-in")
        try {
            RemoteResult.Ok(block(api, userId))
        } catch (e: BackendException) {
            when (e) {
                is BackendException.NotConfigured -> RemoteResult.NotConfigured
                is BackendException.Network -> RemoteResult.Offline
                is BackendException.Malformed -> RemoteResult.Failed(e.message)
                is BackendException.Http -> RemoteResult.Refused(e.code, e.message)
            }
        } catch (e: SerializationException) {
            RemoteResult.Failed(e.message)
        } catch (e: IllegalArgumentException) {
            // a JSON value of the wrong kind (jsonPrimitive on an object, ...)
            RemoteResult.Failed(e.message)
        }
    }
}
