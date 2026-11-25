package app.flytrack

import app.cash.turbine.test
import app.flytrack.core.push.PushContract
import app.flytrack.core.result.AuthResult
import app.flytrack.core.share.SharedContent
import app.flytrack.core.share.SharedContentHolder
import app.flytrack.data.repo.SessionState
import app.flytrack.feature.gmailimport.SuggestionStatus
import app.flytrack.testing.FakeAuthRepository
import app.flytrack.testing.FakeFlightRepository
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeProfileRepository
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.MainDispatcherRule
import app.flytrack.testing.TestData
import app.flytrack.ui.nav.IncomingIntent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
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
    private val gmailStore = FakeGmailSuggestionStore()
    private val gmailNotifier = FakeGmailNotifier()

    private fun viewModel() = MainViewModel(auth, flights, profile, shared, gmailStore, gmailNotifier)

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

        val notConfigured = MainViewModel(FakeAuthRepository.notConfigured(), flights, profile, shared, gmailStore, gmailNotifier)
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

    @Test
    fun `gmail Track opens add flight from the stored suggestion and removes its notification`() = runTest {
        val suggestion = gmailSuggestion(SuggestionStatus.PENDING)
        gmailStore.insert(listOf(suggestion))
        val vm = viewModel()

        vm.destinations.test {
            vm.onIntent(IncomingIntent(gmailSuggestionId = suggestion.id))
            assertEquals(ShellDestination.AddFromGmail(suggestion), awaitItem())
        }
        assertEquals(listOf(suggestion.id), gmailNotifier.cancelled)
    }

    @Test
    fun `gmail Track for a dismissed or deleted suggestion only removes the notification`() = runTest {
        gmailStore.insert(listOf(gmailSuggestion(SuggestionStatus.DISMISSED)))
        val vm = viewModel()

        vm.onIntent(IncomingIntent(gmailSuggestionId = "6E2341_2026-12-03"))
        vm.onIntent(IncomingIntent(gmailSuggestionId = "AI101_2026-12-04"))

        vm.destinations.test { expectNoEvents() }
        assertEquals(listOf("6E2341_2026-12-03", "AI101_2026-12-04"), gmailNotifier.cancelled)
    }
}

// id "6E2341_2026-12-03"
private fun gmailSuggestion(status: SuggestionStatus) =
    GmailFixtures.suggestion(flight = "6E 2341", date = LocalDate(2026, 12, 3), status = status)
