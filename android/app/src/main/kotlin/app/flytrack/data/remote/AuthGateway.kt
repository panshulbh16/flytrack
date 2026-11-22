package app.flytrack.data.remote

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The signed-in user, reduced to what the app needs. */
data class RemoteSession(val userId: String, val isAnonymous: Boolean, val email: String?)

sealed interface RemoteAuthState {
    /** The stored session is still being loaded. */
    data object Initializing : RemoteAuthState

    /** There is definitely no session on this phone. Only now may a new anonymous user be created. */
    data object SignedOut : RemoteAuthState

    /**
     * @property refreshPending the stored session's access token has expired and could not be
     *   renewed yet (typically: the app was opened offline). The user is still signed in, but
     *   backend calls fail until [AuthGateway.refreshSession] succeeds.
     */
    data class SignedIn(val session: RemoteSession, val refreshPending: Boolean = false) : RemoteAuthState
}

/**
 * The few auth operations the app uses, without supabase-kt types, so AuthRepository is
 * JVM-testable. All suspend functions throw [BackendException].
 */
interface AuthGateway {
    val isConfigured: Boolean

    /** Emits the current state first. Emits only [RemoteAuthState.SignedOut] when not configured. */
    val state: Flow<RemoteAuthState>

    /**
     * Waits (briefly) until the stored session has been read and returns the state; never
     * [RemoteAuthState.Initializing]. Throws [BackendException.Network] when that is not possible
     * in time, so that a caller can never mistake "not loaded yet" for "signed out".
     */
    suspend fun awaitState(): RemoteAuthState

    /** The user of the last known session, also while its refresh is pending. */
    fun currentSession(): RemoteSession?

    /** Creates a NEW anonymous user. Only call when [awaitState] returned [RemoteAuthState.SignedOut]. */
    suspend fun signInAnonymously()

    /** Renews the access token of the stored session (state `refreshPending`). */
    suspend fun refreshSession()

    /** Attaches a Google identity to the current (anonymous) user; the user id stays the same. */
    suspend fun linkGoogleIdToken(idToken: String, nonce: String?)

    /** Signs in as the user owning this Google identity; the user id may change. */
    suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?)

    suspend fun signOut()

    companion object {
        /** [BackendException.Http.code] when the Google account is already linked to another user. */
        const val CODE_IDENTITY_ALREADY_EXISTS = "identity_already_exists"
    }
}

class SupabaseAuthGateway(
    private val provider: SupabaseClientProvider,
    /** How long [awaitState] waits for the stored session before reporting a network problem. */
    private val initTimeout: Duration = 10.seconds,
) : AuthGateway {

    // supabase-kt reports no user while a refresh is failing, although the session is still stored.
    // Remembering the user keeps `currentSession()` (not suspending) truthful in that state.
    @Volatile
    private var lastKnown: RemoteSession? = null

    override val isConfigured: Boolean get() = provider.isConfigured

    override val state: Flow<RemoteAuthState>
        get() {
            val auth = provider.client?.auth ?: return flowOf(RemoteAuthState.SignedOut)
            return auth.sessionStatus.map { resolve(auth, it) }
        }

    override suspend fun awaitState(): RemoteAuthState = call {
        val auth = auth()
        val status = withTimeoutOrNull(initTimeout) { auth.sessionStatus.first { it !is SessionStatus.Initializing } }
            ?: throw BackendException.Network(IOException("the stored session is still loading"))
        val state = resolve(auth, status)
        // A failing refresh without a readable stored session must never look like "signed out":
        // signing in anonymously then would replace the user's account with an empty one.
        if (status is SessionStatus.RefreshFailure && state !is RemoteAuthState.SignedIn) {
            throw BackendException.Network(IOException("session refresh is failing"))
        }
        state
    }

    private suspend fun resolve(auth: Auth, status: SessionStatus): RemoteAuthState = when (status) {
        is SessionStatus.Initializing -> RemoteAuthState.Initializing
        is SessionStatus.Authenticated -> signedIn(status.session.user.toRemoteSession(), refreshPending = false)
        is SessionStatus.RefreshFailure -> {
            val stored = runCatching { auth.sessionManager.loadSessionOrNull() }.getOrNull()?.user.toRemoteSession()
            signedIn(stored ?: lastKnown, refreshPending = true)
        }
        is SessionStatus.NotAuthenticated -> {
            lastKnown = null
            RemoteAuthState.SignedOut
        }
    }

    private fun signedIn(session: RemoteSession?, refreshPending: Boolean): RemoteAuthState {
        if (session == null) return RemoteAuthState.SignedOut
        lastKnown = session
        return RemoteAuthState.SignedIn(session, refreshPending)
    }

    override fun currentSession(): RemoteSession? {
        val auth = provider.client?.auth ?: return null
        return when (auth.sessionStatus.value) {
            is SessionStatus.Authenticated -> auth.currentUserOrNull().toRemoteSession()?.also { lastKnown = it }
            is SessionStatus.RefreshFailure -> lastKnown
            else -> null
        }
    }

    override suspend fun signInAnonymously() = call { auth().signInAnonymously() }

    override suspend fun refreshSession() = call {
        val auth = auth()
        // In the refresh-failure state supabase-kt exposes no current session, so the refresh token
        // comes from storage. Importing the renewed session makes the client authenticated again.
        val refreshToken = auth.currentSessionOrNull()?.refreshToken
            ?: auth.sessionManager.loadSessionOrNull()?.refreshToken
            ?: throw BackendException.Http(401, "session_missing", "no stored session to refresh")
        auth.importSession(auth.refreshSession(refreshToken))
    }

    override suspend fun linkGoogleIdToken(idToken: String, nonce: String?) = call {
        auth().linkIdentityWithIdToken(Google, idToken) { this.nonce = nonce }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String, nonce: String?) = call {
        auth().signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
            this.nonce = nonce
        }
    }

    override suspend fun signOut() = call { auth().signOut() }

    private fun UserInfo?.toRemoteSession(): RemoteSession? = this?.let {
        RemoteSession(userId = it.id, isAnonymous = it.isAnonymous == true, email = it.email?.takeIf(String::isNotBlank))
    }

    private fun auth(): Auth = provider.client?.auth ?: throw BackendException.NotConfigured()

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackendException) {
        throw e
    } catch (e: AuthRestException) {
        throw BackendException.Http(status = e.statusCode, code = e.errorCode?.value ?: e.error, message = e.errorDescription)
    } catch (e: RestException) {
        throw BackendException.Http(status = e.statusCode, code = e.error, message = e.description)
    } catch (e: IOException) {
        throw BackendException.Network(e)
    }
}
