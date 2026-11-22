package app.flytrack.data.repo

import app.cash.turbine.test
import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.testing.FakeAuthGateway
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest {
    private val scope = TestScope()
    private var localDataWipes = 0

    private fun repo(gateway: FakeAuthGateway) = AuthRepositoryImpl(
        gateway = gateway,
        localUserData = { localDataWipes++ },
        scope = scope.backgroundScope,
        io = UnconfinedTestDispatcher(scope.testScheduler),
    )

    @Test
    fun `first launch signs in anonymously exactly once`() = scope.runTest {
        val gateway = FakeAuthGateway()
        val repo = repo(gateway)
        assertNull(repo.currentUserId)

        val results = List(3) { async { repo.ensureSignedIn() } }.awaitAll()

        assertEquals(List(3) { AuthResult.Success }, results)
        assertEquals(1, gateway.anonymousSignIns)
        assertEquals("anon-1", repo.currentUserId)
    }

    @Test
    fun `an existing session is reused`() = scope.runTest {
        val gateway = FakeAuthGateway().signedIn()
        val repo = repo(gateway)

        assertEquals(AuthResult.Success, repo.ensureSignedIn())
        assertEquals(0, gateway.anonymousSignIns)
        assertEquals(TestData.USER_ID, repo.currentUserId)
    }

    @Test
    fun `an expired session that cannot be refreshed offline is never replaced by a new anonymous user`() = scope.runTest {
        // The usual offline start: the access token expired while the app was closed.
        val gateway = FakeAuthGateway().signedIn(refreshPending = true)
        gateway.failure = BackendException.Network(IOException("offline"))
        val repo = repo(gateway)

        assertEquals(AuthResult.Offline, repo.ensureSignedIn())

        assertEquals("creating a new user here would orphan every tracked flight", 0, gateway.anonymousSignIns)
        assertEquals(TestData.USER_ID, repo.currentUserId)
        runCurrent() // let the session StateFlow pick up the gateway's state
        assertEquals(SessionState.SignedIn(TestData.USER_ID, isAnonymous = true, email = null), repo.session.value)
    }

    @Test
    fun `an expired session is refreshed once the network is back`() = scope.runTest {
        val gateway = FakeAuthGateway().signedIn(refreshPending = true)
        val repo = repo(gateway)

        assertEquals(AuthResult.Success, repo.ensureSignedIn())

        assertEquals(1, gateway.refreshes)
        assertEquals(0, gateway.anonymousSignIns)
        assertEquals(TestData.USER_ID, repo.currentUserId)

        assertEquals(AuthResult.Success, repo.ensureSignedIn())
        assertEquals("a valid session needs no further refresh", 1, gateway.refreshes)
    }

    @Test
    fun `a session that is still loading is reported as offline, not as signed out`() = scope.runTest {
        val gateway = FakeAuthGateway().apply { stillInitializing = true }
        val repo = repo(gateway)

        assertEquals(AuthResult.Offline, repo.ensureSignedIn())
        assertEquals(0, gateway.anonymousSignIns)
    }

    @Test
    fun `session state follows the gateway`() = scope.runTest {
        val gateway = FakeAuthGateway()
        val repo = repo(gateway)

        repo.session.test {
            // Loading is the initial value; the eager collector replaces it with the gateway's state.
            var state = awaitItem()
            if (state == SessionState.Loading) state = awaitItem()
            assertEquals(SessionState.SignedOut, state)

            repo.ensureSignedIn()
            assertEquals(SessionState.SignedIn("anon-1", isAnonymous = true, email = null), awaitItem())

            repo.linkGoogle("id-token")
            assertEquals(SessionState.SignedIn("anon-1", isAnonymous = false, email = "user@example.com"), awaitItem())
        }
    }

    @Test
    fun `a build without backend keys reports NotConfigured and never touches the gateway`() = scope.runTest {
        val gateway = FakeAuthGateway(isConfigured = false)
        val repo = repo(gateway)

        assertEquals(SessionState.NotConfigured, repo.session.value)
        assertEquals(AuthResult.NotConfigured, repo.ensureSignedIn())
        assertEquals(AuthResult.NotConfigured, repo.linkGoogle("id-token"))
        assertEquals(AuthResult.NotConfigured, repo.signOut())
        assertEquals(0, gateway.anonymousSignIns)
        assertNull(repo.currentUserId)
    }

    @Test
    fun `network and server failures are typed`() = scope.runTest {
        val gateway = FakeAuthGateway()
        val repo = repo(gateway)

        gateway.failure = BackendException.Network(IOException("timeout"))
        assertEquals(AuthResult.Offline, repo.ensureSignedIn())

        gateway.failure = BackendException.Http(422, "anonymous_provider_disabled", "Anonymous sign-ins are disabled")
        assertEquals(AuthResult.Failed("Anonymous sign-ins are disabled"), repo.ensureSignedIn())
        assertNull(repo.currentUserId)

        gateway.failure = null
        assertEquals("a later attempt succeeds", AuthResult.Success, repo.ensureSignedIn())
    }

    @Test
    fun `linking Google keeps the user and reports an account that is already in use`() = scope.runTest {
        val gateway = FakeAuthGateway().signedIn()
        val repo = repo(gateway)

        assertEquals(AuthResult.Success, repo.linkGoogle("id-token", nonce = "n"))
        assertEquals(TestData.USER_ID, repo.currentUserId)
        assertEquals(0, localDataWipes)

        gateway.googleAccountInUse = true
        assertEquals(AuthResult.GoogleAccountInUse, repo.linkGoogle("id-token"))
    }

    @Test
    fun `signing in with Google wipes local data only when the user changes`() = scope.runTest {
        val gateway = FakeAuthGateway().signedIn()
        val repo = repo(gateway)

        assertEquals(AuthResult.Success, repo.signInWithGoogle("id-token"))
        assertEquals("google-user", repo.currentUserId)
        assertEquals(1, localDataWipes)

        assertEquals(AuthResult.Success, repo.signInWithGoogle("id-token"))
        assertEquals("same user again: nothing to wipe", 1, localDataWipes)
    }

    @Test
    fun `sign out wipes local data and starts a fresh anonymous session`() = scope.runTest {
        val gateway = FakeAuthGateway().signedIn()
        val repo = repo(gateway)

        assertEquals(AuthResult.Success, repo.signOut())

        assertEquals(1, gateway.signOuts)
        assertEquals(1, localDataWipes)
        assertEquals("anon-1", repo.currentUserId)
    }
}
