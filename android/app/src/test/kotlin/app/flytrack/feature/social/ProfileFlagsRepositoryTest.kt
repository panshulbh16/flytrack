package app.flytrack.feature.social

import app.flytrack.data.remote.BackendException
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProfileFlagsRepositoryTest {
    private val scope = TestScope()
    private val api = FakeProfileFlagsApi()
    private val auth = FakeAuthRepository()
    private val store = InMemorySocialStore()
    private val repo = SupabaseProfileFlagsRepository(api, auth, store, UnconfinedTestDispatcher(scope.testScheduler))

    @Test
    fun `refresh reads both flags and caches crew mode for this user`() = scope.runTest {
        api.row = ProfileFlags(crewMode = true, sharePassportWithFriends = false)

        val result = repo.refresh()

        assertEquals(RemoteResult.Ok(ProfileFlags(crewMode = true, sharePassportWithFriends = false)), result)
        assertEquals(CrewModeFlag(TestData.USER_ID, true), store.crew.value)
        assertTrue(repo.crewMode.first())
    }

    @Test
    fun `crew mode patches only its own column and updates the cache`() = scope.runTest {
        repo.setCrewMode(true)
        assertEquals(setOf("crew_mode"), api.patches.single().keys)
        assertTrue(api.patches.single()["crew_mode"]!!.jsonPrimitive.boolean)
        assertTrue(repo.crewMode.first())

        repo.setCrewMode(false)
        assertFalse(repo.crewMode.first())
    }

    @Test
    fun `passport sharing patches only its own column`() = scope.runTest {
        val result = repo.setSharePassport(false)
        assertEquals(setOf("share_passport_with_friends"), api.patches.single().keys)
        assertFalse((result as RemoteResult.Ok).value.sharePassportWithFriends)
    }

    @Test
    fun `another user's cached crew flag never applies`() = scope.runTest {
        store.crew.value = CrewModeFlag("someone-else", true)
        assertFalse(repo.crewMode.first())
        auth.setSession(SessionState.SignedIn("someone-else", isAnonymous = false, email = null))
        assertTrue(repo.crewMode.first())
    }

    @Test
    fun `errors are typed and leave the cache alone`() = scope.runTest {
        store.crew.value = CrewModeFlag(TestData.USER_ID, true)
        api.error = BackendException.Network(IOException("down"))
        assertEquals(RemoteResult.Offline, repo.setCrewMode(false))
        api.error = BackendException.Http(403, "42501", "permission denied")
        assertEquals(RemoteResult.Refused("42501", "permission denied"), repo.refresh())
        api.error = null
        api.row = null
        assertTrue(repo.refresh() is RemoteResult.Refused)
        assertTrue("cache unchanged", repo.crewMode.first())
    }

    @Test
    fun `no backend keys`() = scope.runTest {
        auth.setSession(SessionState.NotConfigured)
        assertEquals(RemoteResult.NotConfigured, repo.refresh())
    }
}
