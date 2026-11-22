package app.flytrack.feature.importticket

import app.cash.turbine.test
import app.flytrack.core.model.TrackSource
import app.flytrack.core.share.SharedContent
import app.flytrack.core.share.SharedContentHolder
import app.flytrack.testing.FakeSettingsStore
import app.flytrack.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportTicketViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val today = LocalDate(2026, 11, 22)

    @Test
    fun `shared text is parsed on the phone and a choice continues with the source`() = runTest {
        val holder = SharedContentHolder()
        holder.offer(SharedContent(text = "IndiGo\n6E 2341\n25 Nov 2026\nPNR ABC123"))
        val cloud = RecordingCloud()
        val vm = ImportTicketViewModel(
            shared = holder,
            extractor = object : TicketTextExtractor {
                override suspend fun extract(request: ExtractRequest) = ExtractedTicket(
                    text = request.text.orEmpty(),
                    source = TrackSource.SHARE,
                    truncated = false,
                )
            },
            cloud = cloud,
            settings = FakeSettingsStore(),
            today = ImportToday { today },
        )

        val state = vm.uiState.value
        assertEquals(ImportPhase.Choose, state.phase)
        assertEquals("6E 2341", state.selectedFlight?.flight?.display)
        assertEquals("ABC123", state.pnr)
        assertTrue(cloud.calls.isEmpty())

        vm.events.test {
            vm.onContinue()
            assertEquals(ImportEvent.Parsed("6E 2341", "2026-11-25", "ABC123", "share"), awaitItem())
        }
    }

    @Test
    fun `cloud runs only with consent and only when nothing was found locally`() = runTest {
        val holder = SharedContentHolder()
        val cloud = RecordingCloud()
        val settings = FakeSettingsStore(cloudTicketParsingConsent = true)
        val vm = ImportTicketViewModel(
            shared = holder,
            extractor = object : TicketTextExtractor {
                override suspend fun extract(request: ExtractRequest) = ExtractedTicket("no flight here", TrackSource.PDF, false)
            },
            cloud = cloud,
            settings = settings,
            today = ImportToday { today },
        )
        vm.onPicked(listOf("content://ticket"), "application/pdf")
        assertEquals(listOf("no flight here"), cloud.calls)
        assertEquals(ImportPhase.Choose, vm.uiState.value.phase)
        assertEquals(TrackSource.PDF, vm.uiState.value.source)
    }
}

private class RecordingCloud : CloudTicketParser(
    api = app.flytrack.testing.FakeBackendApi(),
    auth = app.flytrack.testing.FakeAuthRepository(),
    json = app.flytrack.data.remote.FlyTrackJson,
) {
    val calls = mutableListOf<String>()
    override suspend fun parse(text: String): CloudParseResult {
        calls += text
        return CloudParseResult.Parsed(
            ParsedTicket(
                flights = listOf(
                    ParsedFlight(
                        flight = app.flytrack.core.flightnumber.parseFlightNumber("6E 1")!!,
                        date = LocalDate(2026, 11, 25),
                        fromIata = "DEL",
                        toIata = "BOM",
                    ),
                ),
                pnr = null,
            ),
        )
    }
}
