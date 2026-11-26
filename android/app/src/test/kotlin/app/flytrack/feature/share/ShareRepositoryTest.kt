package app.flytrack.feature.share

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.feature.checkin.FakeShareCheckinApi
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.TestData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ShareRepositoryTest {
    private val api = FakeShareCheckinApi()
    private val auth = FakeAuthRepository()

    private fun kotlinx.coroutines.test.TestScope.repo() =
        SupabaseShareRepository(api, auth, ShareConfig("https://heygate.app/f/"), UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `returns the public link of the token`() = runTest {
        assertEquals(ShareResult.Shared("https://heygate.app/f/#tok_abcdefghijklmnopqrstuv"), repo().createShare(TestData.FLIGHT_ID))
        assertEquals(listOf("createFlightShare(${TestData.FLIGHT_ID})"), api.calls)
    }

    @Test
    fun `failures become typed results`() = runTest {
        api.failure = BackendException.Network(IOException())
        assertEquals(ShareResult.Offline, repo().createShare(TestData.FLIGHT_ID))
        api.failure = BackendException.NotConfigured()
        assertEquals(ShareResult.NotConfigured, repo().createShare(TestData.FLIGHT_ID))
        api.failure = BackendException.Http(403, "42501", "not tracking this flight")
        assertEquals(ShareResult.NotTracked, repo().createShare(TestData.FLIGHT_ID))
        api.failure = BackendException.Http(500, "XX000", "boom")
        assertTrue(repo().createShare(TestData.FLIGHT_ID) is ShareResult.Failed)
    }

    @Test
    fun `no call without a session`() = runTest {
        auth.nextResult = AuthResult.Offline
        assertEquals(ShareResult.Offline, repo().createShare(TestData.FLIGHT_ID))
        assertEquals(emptyList<String>(), api.calls)
    }
}
