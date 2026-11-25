package app.flytrack.feature.gmailimport

import app.flytrack.feature.importticket.ImportToday
import app.flytrack.testing.FakeClock
import app.flytrack.testing.FakeGmailApi
import app.flytrack.testing.FakeGmailAuth
import app.flytrack.testing.FakeGmailNotifier
import app.flytrack.testing.FakeGmailSuggestionStore
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.FakeWorkScheduler
import app.flytrack.testing.GmailFixtures
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GmailImportViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val today = LocalDate.parse("2026-11-22")
    private val api = FakeGmailApi() // an empty mailbox unless a test queues a failure
    private val auth = FakeGmailAuth()
    private val store = FakeGmailSuggestionStore()
    private val settings = FakeSettingsStore()
    private val notifier = FakeGmailNotifier()
    private val work = FakeWorkScheduler()
    private var tracked: Set<TrackedKey> = emptySet()

    private fun viewModel(): GmailImportViewModel {
        val trackedKeys = TrackedKeysSource { tracked }
        val importToday = ImportToday { today }
        val scanner = GmailScanner(api, auth, store, settings, trackedKeys, PdfTextReader { "" }, importToday, FakeClock())
        val connection = GmailConnection(auth, api, store, settings, notifier, work)
        return GmailImportViewModel(auth, connection, scanner, settings, store, trackedKeys, importToday, notifier)
    }

    private fun suggestion(flight: String, date: String, status: SuggestionStatus = SuggestionStatus.PENDING) =
        GmailFixtures.suggestion(flight, LocalDate.parse(date), status)

    private suspend fun connected() = settings.setGmailAccountEmail("me@example.com")

    private val GmailImportViewModel.state get() = uiState.value

    @Test
    fun `without a connected account it asks to connect and reads nothing`() = runTest {
        val vm = viewModel()

        assertEquals(GmailImportUiState(GmailScanState.NotConnected), vm.state)
        assertTrue(api.listCalls.isEmpty())
    }

    @Test
    fun `a connected account is scanned on open and lists upcoming pending untracked suggestions by date`() = runTest {
        connected()
        val upcoming = suggestion("6E 2341", "2026-12-03")
        val later = suggestion("AI 101", "2026-12-20")
        store.insert(
            listOf(
                later,
                upcoming,
                suggestion("UK 955", "2026-12-05", SuggestionStatus.DISMISSED),
                suggestion("QP 1101", "2026-12-06", SuggestionStatus.ADDED),
                suggestion("6E 500", "2026-12-07"),
            ),
        )
        tracked = setOf(TrackedKey("6E500", LocalDate.parse("2026-12-07")))

        val vm = viewModel()

        assertEquals(1, api.listCalls.size)
        assertEquals(GmailScanState.Results, vm.state.scan)
        assertEquals(listOf(upcoming, later), vm.state.suggestions)
    }

    @Test
    fun `scanning cleans up past and tracked suggestions and their notifications`() = runTest {
        connected()
        store.insert(listOf(suggestion("6E 1", "2026-11-21"), suggestion("6E 500", "2026-12-07")))
        tracked = setOf(TrackedKey("6E500", LocalDate.parse("2026-12-07")))

        viewModel()

        assertNull(store.get("6E1_2026-11-21"))
        assertEquals(SuggestionStatus.ADDED, store.get("6E500_2026-12-07")?.status)
        assertEquals(setOf("6E1_2026-11-21", "6E500_2026-12-07"), notifier.cancelled.toSet())
    }

    @Test
    fun `a scan with nothing pending says so`() = runTest {
        connected()

        val vm = viewModel()

        assertEquals(GmailScanState.NoPending, vm.state.scan)
        assertTrue(vm.state.suggestions.isEmpty())
    }

    @Test
    fun `a suggestion added on Add flight leaves the list`() = runTest {
        connected()
        val found = suggestion("6E 2341", "2026-12-03")
        store.insert(listOf(found))
        val vm = viewModel()
        assertEquals(GmailScanState.Results, vm.state.scan)

        store.setStatus(listOf(found.id), SuggestionStatus.ADDED)

        assertEquals(GmailScanState.NoPending, vm.state.scan)
    }

    @Test
    fun `lost access asks to reconnect without scanning`() = runTest {
        connected()
        settings.setGmailNeedsReconnect(true)

        val vm = viewModel()

        assertEquals(GmailScanState.NeedsReconnect, vm.state.scan)
        assertTrue(api.listCalls.isEmpty())
    }

    @Test
    fun `offline shows the notice and try again scans again`() = runTest {
        connected()
        api.fail("list", GmailFailure.Offline)
        val vm = viewModel()
        assertEquals(GmailScanState.Offline, vm.state.scan)

        vm.onRetry()

        assertEquals(GmailScanState.NoPending, vm.state.scan)
        assertEquals(2, api.listCalls.size)
    }

    @Test
    fun `a scan stopped early with nothing pending offers scan again instead of no flights`() = runTest {
        connected()
        // Ends the scan as ScanResult.Partial, the same result the 50-email read cap gives (GmailScannerTest covers the cap).
        api.fail("list", GmailFailure.RateLimited)
        val vm = viewModel()
        assertEquals(GmailScanState.MoreToRead, vm.state.scan)

        val found = suggestion("6E 2341", "2026-12-03")
        store.insert(listOf(found))
        assertEquals(GmailScanState.Results, vm.state.scan) // a stopped scan still lists what it has
        store.setStatus(listOf(found.id), SuggestionStatus.DISMISSED)
        assertEquals(GmailScanState.MoreToRead, vm.state.scan)

        vm.onRetry() // "Scan again"

        assertEquals(GmailScanState.NoPending, vm.state.scan)
        assertEquals(2, api.listCalls.size)
    }

    @Test
    fun `connecting saves the account and scans`() = runTest {
        auth.connectResult = GmailAuthResult.Granted("token")
        val vm = viewModel()

        vm.onConnect()

        assertEquals(api.profileEmail, settings.gmailAccountEmail.first())
        assertEquals(GmailScanState.NoPending, vm.state.scan)
        assertEquals(1, api.listCalls.size)
    }

    @Test
    fun `consent from google's picker connects and scans`() = runTest {
        auth.connectResult = GmailAuthResult.Granted("token") // FakeGmailAuth.resultFromIntent returns connectResult
        val vm = viewModel()

        vm.onResolution(ok = true, data = null)

        assertEquals(api.profileEmail, settings.gmailAccountEmail.first())
        assertEquals(GmailScanState.NoPending, vm.state.scan)
    }

    @Test
    fun `connect failures return to connect with a reason`() = runTest {
        val vm = viewModel()

        auth.connectResult = GmailAuthResult.NeedsUser(null)
        vm.onConnect()
        assertEquals(GmailImportUiState(GmailScanState.NotConnected, notice = GmailConnectNotice.Failed), vm.state)

        auth.connectResult = GmailAuthResult.NotConfigured
        vm.onConnect()
        assertEquals(GmailConnectNotice.NotConfigured, vm.state.notice)

        auth.connectResult = GmailAuthResult.Granted("token")
        api.fail("profile", GmailFailure.Offline)
        vm.onConnect()
        assertEquals(GmailImportUiState(GmailScanState.NotConnected, notice = GmailConnectNotice.Offline), vm.state)
        assertNull(settings.gmailAccountEmail.first())
        assertTrue(api.listCalls.isEmpty())
    }

    @Test
    fun `a failed reconnect stays on the reconnect screen`() = runTest {
        connected()
        settings.setGmailNeedsReconnect(true)
        auth.connectResult = GmailAuthResult.Failed
        val vm = viewModel()

        vm.onConnect()

        assertEquals(GmailImportUiState(GmailScanState.NeedsReconnect, notice = GmailConnectNotice.Failed), vm.state)
    }
}
