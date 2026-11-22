package app.flytrack.data.repo

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.AuthGateway
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.RemoteAuthState
import app.flytrack.di.ApplicationScope
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed interface SessionState {
    /** The stored session is still being read. */
    data object Loading : SessionState

    /** The build has no backend keys: show the "backend not configured" state. */
    data object NotConfigured : SessionState

    /** No session yet (first launch offline) or the user was signed out. */
    data object SignedOut : SessionState

    data class SignedIn(
        val userId: String,
        /** True until a Google account is linked. */
        val isAnonymous: Boolean,
        val email: String?,
    ) : SessionState
}

interface AuthRepository {
    val session: StateFlow<SessionState>

    /** The signed-in user's id, null when signed out or not configured. */
    val currentUserId: String?

    /**
     * Makes sure there is a usable session: signs in anonymously on first launch, and renews an
     * expired access token (returns [AuthResult.Offline] when that needs a connection that is not
     * there; the user stays signed in). Cheap when already signed in; safe to call concurrently
     * and before every network operation.
     */
    suspend fun ensureSignedIn(): AuthResult

    /**
     * Attaches the Google account behind [idToken] (from Credential Manager) to the current
     * anonymous user, keeping the user id and all tracked flights.
     * Returns [AuthResult.GoogleAccountInUse] when that Google account already owns another
     * FlyTrack user; offer [signInWithGoogle] then.
     */
    suspend fun linkGoogle(idToken: String, nonce: String? = null): AuthResult

    /** Switches to the user owning the Google account. Local data of the previous user is wiped. */
    suspend fun signInWithGoogle(idToken: String, nonce: String? = null): AuthResult

    /**
     * Signs out, wipes all locally cached user data and starts a fresh anonymous session, so the
     * app always has a user. Unregister the device token (DeviceRepository) BEFORE calling this.
     */
    suspend fun signOut(): AuthResult
}

/** Wipes everything cached for the signed-in user (tracked flights, profile, flight cache). */
fun interface LocalUserData {
    suspend fun clear()
}

class AuthRepositoryImpl @Inject constructor(
    private val gateway: AuthGateway,
    private val localUserData: LocalUserData,
    @ApplicationScope scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AuthRepository {

    // Serialises sign-in so two callers at app start cannot create two anonymous users.
    private val mutex = Mutex()

    override val session: StateFlow<SessionState> =
        if (!gateway.isConfigured) {
            MutableStateFlow(SessionState.NotConfigured)
        } else {
            gateway.state.map { state ->
                when (state) {
                    RemoteAuthState.Initializing -> SessionState.Loading
                    RemoteAuthState.SignedOut -> SessionState.SignedOut
                    // Also while a token refresh is pending: the user and the cached data are still theirs.
                    is RemoteAuthState.SignedIn ->
                        SessionState.SignedIn(state.session.userId, state.session.isAnonymous, state.session.email)
                }
            }.stateIn(scope, SharingStarted.Eagerly, SessionState.Loading)
        }

    override val currentUserId: String? get() = gateway.currentSession()?.userId

    override suspend fun ensureSignedIn(): AuthResult = command {
        mutex.withLock {
            when (val state = gateway.awaitState()) {
                // A new anonymous user is created ONLY when there is certainly no session. Doing it while a
                // stored session merely fails to refresh would silently replace the user's account.
                RemoteAuthState.SignedOut -> gateway.signInAnonymously()
                is RemoteAuthState.SignedIn -> if (state.refreshPending) gateway.refreshSession()
                RemoteAuthState.Initializing -> throw BackendException.Network(IOException("session not loaded"))
            }
        }
    }

    override suspend fun linkGoogle(idToken: String, nonce: String?): AuthResult = command {
        gateway.linkGoogleIdToken(idToken, nonce)
    }

    override suspend fun signInWithGoogle(idToken: String, nonce: String?): AuthResult = command {
        mutex.withLock {
            val previousUser = gateway.currentSession()?.userId
            gateway.signInWithGoogleIdToken(idToken, nonce)
            if (gateway.currentSession()?.userId != previousUser) localUserData.clear()
        }
    }

    override suspend fun signOut(): AuthResult = command {
        mutex.withLock {
            gateway.signOut()
            localUserData.clear()
            gateway.signInAnonymously()
        }
    }

    private suspend fun command(block: suspend () -> Unit): AuthResult {
        if (!gateway.isConfigured) return AuthResult.NotConfigured
        return withContext(io) {
            try {
                block()
                AuthResult.Success
            } catch (e: BackendException) {
                e.toAuthResult()
            }
        }
    }

    private fun BackendException.toAuthResult(): AuthResult = when (this) {
        is BackendException.NotConfigured -> AuthResult.NotConfigured
        is BackendException.Network -> AuthResult.Offline
        is BackendException.Http ->
            if (code == AuthGateway.CODE_IDENTITY_ALREADY_EXISTS) AuthResult.GoogleAccountInUse else AuthResult.Failed(message)
        is BackendException.Malformed -> AuthResult.Failed(message)
    }
}
