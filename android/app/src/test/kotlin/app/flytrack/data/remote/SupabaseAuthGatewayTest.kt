package app.flytrack.data.remote

import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the real supabase-kt Auth plugin against ktor-client-mock with in-memory session storage.
 * Real time is used (runBlocking) because Auth works on its own dispatcher and timers.
 */
class SupabaseAuthGatewayTest {
    private data class Recorded(val path: String, val query: String, val body: String, val authorization: String?)

    private val requests = mutableListOf<Recorded>()
    private val storage = MemorySessionManager()
    private var online = true
    private var tokenStatus = HttpStatusCode.OK

    private fun sessionJson(userId: String, anonymous: Boolean, access: String = "access-$userId") = """
        {"access_token":"$access","refresh_token":"refresh-$userId","token_type":"bearer","expires_in":3600,
         "user":{"id":"$userId","aud":"authenticated","role":"authenticated","email":"","is_anonymous":$anonymous}}
    """.trimIndent()

    private fun gateway(): SupabaseAuthGateway {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requests += Recorded(path, request.url.encodedQuery, request.body.toByteArray().decodeToString(), request.headers[HttpHeaders.Authorization])
            if (!online) throw IOException("Unable to resolve host")
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                path.endsWith("/signup") -> respond(sessionJson("anon-user", anonymous = true), HttpStatusCode.OK, json)
                path.endsWith("/token") && request.url.parameters["grant_type"] == "refresh_token" ->
                    if (tokenStatus == HttpStatusCode.OK) {
                        respond(sessionJson("stored-user", anonymous = true, access = "fresh-access"), HttpStatusCode.OK, json)
                    } else {
                        respond("""{"code":"refresh_token_not_found","message":"Invalid Refresh Token"}""", tokenStatus, json)
                    }
                path.endsWith("/token") && request.url.parameters["grant_type"] == "id_token" -> when {
                    // GoTrue's answer when the Google identity already belongs to another user
                    requests.last().body.contains("taken-id-token") -> respond(
                        """{"code":422,"error_code":"identity_already_exists","msg":"Identity is already linked to another user"}""",
                        HttpStatusCode.UnprocessableEntity, json,
                    )
                    requests.last().body.contains("link_identity") ->
                        respond(sessionJson("anon-user", anonymous = false), HttpStatusCode.OK, json)
                    else -> respond(sessionJson("google-user", anonymous = false), HttpStatusCode.OK, json)
                }
                path.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                else -> respond("""{"code":"unexpected","message":"unexpected $path"}""", HttpStatusCode.NotFound, json)
            }
        }
        val provider = SupabaseClientProvider(
            BackendConfig("https://demo.supabase.co", "anon-key", ""),
            FlyTrackJson,
            engineFactory = { engine },
            configureAuth = {
                sessionManager = storage
                codeVerifierCache = MemoryCodeVerifierCache()
                enableLifecycleCallbacks = false
                retryDelay = 60.seconds // keep the plugin's own retry out of the way of the assertions
            },
        )
        return SupabaseAuthGateway(provider, initTimeout = 5.seconds)
    }

    private fun storedSession(expired: Boolean) = UserSession(
        accessToken = "old-access",
        refreshToken = "old-refresh",
        expiresIn = 3600,
        tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = "stored-user", isAnonymous = true),
        expiresAt = if (expired) Clock.System.now() - 2.hours else Clock.System.now() + 1.hours,
    )

    @Test
    fun `first launch is signed out and anonymous sign-in creates a session`() = runBlocking {
        val gateway = gateway()

        assertEquals(RemoteAuthState.SignedOut, gateway.awaitState())
        assertNull(gateway.currentSession())

        gateway.signInAnonymously()

        assertEquals("/auth/v1/signup", requests.single().path)
        assertEquals(RemoteSession("anon-user", isAnonymous = true, email = null), gateway.currentSession())
        assertEquals(RemoteAuthState.SignedIn(RemoteSession("anon-user", true, null)), gateway.awaitState())
        assertEquals(RemoteAuthState.SignedIn(RemoteSession("anon-user", true, null)), gateway.state.first { it is RemoteAuthState.SignedIn })
        assertEquals("the session is persisted", "anon-user", storage.loadSession().user?.id)
    }

    @Test
    fun `a stored valid session is restored without any request`() = runBlocking {
        storage.saveSession(storedSession(expired = false))
        val gateway = gateway()

        assertEquals(RemoteAuthState.SignedIn(RemoteSession("stored-user", true, null)), gateway.awaitState())
        assertTrue(requests.none { it.path.endsWith("/signup") })
    }

    @Test
    fun `an expired stored session offline stays signed in with a pending refresh`() = runBlocking {
        storage.saveSession(storedSession(expired = true))
        online = false
        val gateway = gateway()

        val state = gateway.awaitState()

        assertEquals(RemoteAuthState.SignedIn(RemoteSession("stored-user", true, null), refreshPending = true), state)
        assertEquals("the user is still known", "stored-user", gateway.currentSession()?.userId)
        assertTrue(requests.none { it.path.endsWith("/signup") })

        try {
            gateway.refreshSession()
            fail("expected Network")
        } catch (e: BackendException.Network) {
            assertEquals("stored-user", storage.loadSession().user?.id)
        }
    }

    @Test
    fun `a pending refresh succeeds once the network is back`() = runBlocking {
        storage.saveSession(storedSession(expired = true))
        online = false
        val gateway = gateway()
        assertTrue((gateway.awaitState() as RemoteAuthState.SignedIn).refreshPending)

        online = true
        gateway.refreshSession()

        val refresh = requests.last { it.path.endsWith("/token") }
        assertTrue(refresh.query.contains("grant_type=refresh_token"))
        assertTrue(refresh.body.contains("old-refresh"))
        assertEquals(RemoteAuthState.SignedIn(RemoteSession("stored-user", true, null), refreshPending = false), gateway.awaitState())
        assertEquals("fresh-access", storage.loadSession().accessToken)
    }

    @Test
    fun `Google id token sign-in and sign-out`() = runBlocking {
        val gateway = gateway()
        gateway.signInAnonymously()

        gateway.signInWithGoogleIdToken("google-id-token", nonce = "n-1")

        val request = requests.last { it.path.endsWith("/token") }
        assertTrue(request.query.contains("grant_type=id_token"))
        assertTrue(request.body.contains(""""id_token":"google-id-token""""))
        assertTrue(request.body.contains(""""provider":"google""""))
        assertTrue(request.body.contains(""""nonce":"n-1""""))
        assertEquals(RemoteSession("google-user", isAnonymous = false, email = null), gateway.currentSession())

        gateway.signOut()
        assertEquals(RemoteAuthState.SignedOut, gateway.awaitState())
        assertNull(gateway.currentSession())
    }

    @Test
    fun `linking Google sends the id token as the current user and keeps the user id`() = runBlocking {
        val gateway = gateway()
        gateway.signInAnonymously()

        gateway.linkGoogleIdToken("google-id-token", nonce = "n-1")

        val request = requests.last()
        assertEquals("/auth/v1/token", request.path)
        assertTrue(request.query.contains("grant_type=id_token"))
        assertTrue(request.body, request.body.contains(""""id_token":"google-id-token""""))
        assertTrue(request.body, request.body.contains(""""link_identity":true"""))
        assertEquals("the link must be made as the anonymous user", "Bearer access-anon-user", request.authorization)
        assertEquals(RemoteSession("anon-user", isAnonymous = false, email = null), gateway.currentSession())
    }

    @Test
    fun `a Google account that already has a user is reported with its error code`() = runBlocking {
        val gateway = gateway()
        gateway.signInAnonymously()
        try {
            gateway.linkGoogleIdToken("taken-id-token", null)
            fail("expected Http")
        } catch (e: BackendException.Http) {
            assertEquals(422, e.status)
            assertEquals(AuthGateway.CODE_IDENTITY_ALREADY_EXISTS, e.code)
        }
        assertEquals("the anonymous session is untouched", "anon-user", gateway.currentSession()?.userId)
    }

    @Test
    fun `network errors are typed`() = runBlocking {
        val gateway = gateway()
        online = false
        try {
            gateway.signInAnonymously()
            fail("expected Network")
        } catch (e: BackendException.Network) {
            assertNull(gateway.currentSession())
        }
    }

    @Test
    fun `not configured`() = runBlocking {
        val gateway = SupabaseAuthGateway(SupabaseClientProvider(BackendConfig("", "", ""), FlyTrackJson))
        assertFalse(gateway.isConfigured)
        assertEquals(RemoteAuthState.SignedOut, gateway.state.first())
        assertNull(gateway.currentSession())
        try {
            gateway.awaitState()
            fail("expected NotConfigured")
        } catch (e: BackendException.NotConfigured) {
            assertTrue(requests.isEmpty())
        }
    }
}
