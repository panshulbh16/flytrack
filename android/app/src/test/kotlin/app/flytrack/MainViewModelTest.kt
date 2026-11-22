package app.flytrack

import app.cash.turbine.test
import app.flytrack.core.push.PushContract
import app.flytrack.core.result.AuthResult
import app.flytrack.core.share.SharedContent
import app.flytrack.core.share.SharedContentHolder
import app.flytrack.data.repo.SessionState
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import app.flytrack.ui.nav.IncomingIntent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val auth = FakeAuthRepository.signedOut()
    private val flights = FakeFlightRepository()
    private val profile = FakeProfileRepository()
    private val shared = SharedContentHolder()

    private fun viewModel() = MainViewModel(auth, flights, profile, shared)

    @Test
    fun `launch signs in and then syncs flights and profile once`() = runTest {
        val vm = viewModel()

        assertEquals(1, auth.ensureSignedInCalls)
        assertEquals(SessionState.SignedIn(TestData.USER_ID, isAnonymous = true, email = null), vm.session.value)
        assertEquals(1, flights.refreshCalls)
        assertEquals(1, profile.refreshCalls)
    }

    @Test
    fun `nothing is synced when sign-in is impossible`() = runTest {
        auth.nextResult = AuthResult.Offline
        viewModel()
        assertEquals(0, flights.refreshCalls)

        val notConfigured = MainViewModel(FakeAuthRepository.notConfigured(), flights, profile, shared)
        assertEquals(SessionState.NotConfigured, notConfigured.session.value)
        assertEquals(0, flights.refreshCalls)
    }

    @Test
    fun `tapped notification navigates to the flight and refreshes it`() = runTest {
        val vm = viewModel()

        vm.destinations.test {
            vm.onIntent(IncomingIntent(action = PushContract.ACTION_OPEN_FLIGHT, flightId = TestData.FLIGHT_ID))
            assertEquals(ShellDestination.Flight(TestData.FLIGHT_ID), awaitItem())
        }
        assertEquals(listOf<Pair<String, String?>>(TestData.FLIGHT_ID to null), flights.syncCalls)
    }

    @Test
    fun `share hands the content to the holder and navigates to the importer`() = runTest {
        val vm = viewModel()
        val intent = IncomingIntent(
            action = "android.intent.action.SEND",
            mimeType = "application/pdf",
            streamUris = listOf("content://mail/ticket.pdf"),
        )

        vm.destinations.test {
            vm.onIntent(intent)
            assertEquals(ShellDestination.ImportTicket, awaitItem())
        }
        assertEquals(SharedContent(uris = listOf("content://mail/ticket.pdf"), mimeType = "application/pdf"), shared.consume())
    }

    @Test
    fun `an intent received before the UI collects is not lost`() = runTest {
        val vm = viewModel()
        vm.onIntent(IncomingIntent(action = PushContract.ACTION_OPEN_FLIGHT, flightId = TestData.FLIGHT_ID))

        vm.destinations.test {
            assertEquals(ShellDestination.Flight(TestData.FLIGHT_ID), awaitItem())
        }
    }

    @Test
    fun `a plain launch navigates nowhere`() = runTest {
        val vm = viewModel()
        vm.onIntent(IncomingIntent(action = "android.intent.action.MAIN"))

        vm.destinations.test { expectNoEvents() }
        assertNull(shared.pending.value)
    }
}
