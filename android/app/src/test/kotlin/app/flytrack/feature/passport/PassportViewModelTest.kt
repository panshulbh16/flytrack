package app.flytrack.feature.passport

import app.flytrack.core.result.AuthResult
import app.flytrack.data.remote.BackendException
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeClock
import app.flytrack.testing.Fixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PassportViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val api = FakeTravelApi().apply {
        val base = Json.parseToJsonElement(Fixtures.read("passport_pro_all.json")).jsonObject
        passportAnswer = { year ->
            if (year == null) base else kotlinx.serialization.json.JsonObject(base + ("scope" to JsonPrimitive("year")))
        }
    }
    private val auth = FakeAuthRepository()

    private fun vm() = PassportViewModel(SupabasePassportRepository(api, auth, UnconfinedTestDispatcher()), FakeClock())

    @Test
    fun `opens on the overview and loads it once`() = runTest {
        val vm = vm()
        val state = vm.uiState.value
        assertEquals(PassportTab.OVERVIEW, state.tab)
        assertEquals(2026, state.currentYear)
        assertTrue(state.content is PassportContent.Loaded)
        assertTrue("the server said all time", state.overviewIsAllTime)
        assertNull("no card outside Year in Review", state.yearCard)
        assertEquals(listOf("myPassport(null)"), api.calls)
    }

    @Test
    fun `year tab asks for the current year, shows the card, and is cached`() = runTest {
        val vm = vm()
        vm.selectTab(PassportTab.YEAR)
        assertEquals(PassportTab.YEAR, vm.uiState.value.tab)
        assertNotNull(vm.uiState.value.yearCard)
        vm.selectTab(PassportTab.OVERVIEW)
        vm.selectTab(PassportTab.YEAR)
        assertEquals(listOf("myPassport(null)", "myPassport(2026)"), api.calls)
    }

    @Test
    fun `a failed refresh keeps what is on screen`() = runTest {
        val vm = vm()
        api.failure = BackendException.Network(IOException("down"))
        vm.refresh()
        val state = vm.uiState.value
        assertTrue(state.content is PassportContent.Loaded)
        assertFalse(state.refreshing)
    }

    @Test
    fun `offline and not configured are typed states`() = runTest {
        api.failure = BackendException.Network(IOException("down"))
        assertEquals(PassportContent.Offline, vm().uiState.value.content)

        api.failure = null
        auth.nextResult = AuthResult.NotConfigured
        assertEquals(PassportContent.NotConfigured, vm().uiState.value.content)

        auth.nextResult = null
        api.failure = BackendException.Http(500, null, "boom")
        assertEquals(PassportContent.Failed, vm().uiState.value.content)
    }

    @Test
    fun `an unreadable answer is a failure, not a crash`() = runTest {
        api.passportAnswer = { Json.parseToJsonElement("""{"flights": "many"}""").jsonObject }
        assertEquals(PassportContent.Failed, vm().uiState.value.content)
    }
}
