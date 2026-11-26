package app.flytrack.feature.checkin

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.CheckinRuleDto
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class CheckinRulesRepositoryTest {
    private val api = FakeShareCheckinApi().apply {
        rules = listOf(
            CheckinRuleDto("6e", 24, 45, 60, "https://www.goindigo.in/web-check-in.html", verified = true),
            CheckinRuleDto("XX", 48, 60, 75, "javascript:alert(1)"),
        )
    }
    private val auth = FakeAuthRepository()
    private val clock = FakeClock()

    private fun repo(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = SupabaseCheckinRulesRepository(api, auth, clock, dispatcher)

    @Test
    fun `reads the table once and keeps it`() = runTest {
        val repo = repo(UnconfinedTestDispatcher(testScheduler))
        assertEquals(CheckinRule("6E", 24, 45, 60, "https://www.goindigo.in/web-check-in.html", verified = true), repo.ruleFor("6E"))
        assertEquals(24, repo.ruleFor(" 6e ")?.opensHoursBefore)
        assertEquals(listOf("checkinRules"), api.calls)
    }

    @Test
    fun `the server list wins over the seed, and unsafe links are dropped`() = runTest {
        val repo = repo(UnconfinedTestDispatcher(testScheduler))
        assertNull(repo.ruleFor("SG")) // not in the server's list
        assertNull(repo.ruleFor("XX")?.checkinUrl)
    }

    @Test
    fun `offline the seed stands in and the next call tries again`() = runTest {
        api.failure = BackendException.Network(IOException("offline"))
        val repo = repo(UnconfinedTestDispatcher(testScheduler))
        assertEquals(48, repo.ruleFor("6E")?.opensHoursBefore)
        api.failure = null
        assertEquals(24, repo.ruleFor("6E")?.opensHoursBefore)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `not signed in uses the seed without calling the backend`() = runTest {
        auth.nextResult = AuthResult.NotConfigured
        val repo = repo(UnconfinedTestDispatcher(testScheduler))
        assertEquals(BUILT_IN_RULES.getValue("QP"), repo.ruleFor("QP"))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `the list is fetched again after half a day`() = runTest {
        val repo = repo(UnconfinedTestDispatcher(testScheduler))
        repo.ruleFor("6E")
        clock.advance(11.hours)
        repo.ruleFor("6E")
        assertEquals(1, api.calls.size)
        clock.advance(2.hours)
        repo.ruleFor("6E")
        assertEquals(2, api.calls.size)
    }
}
