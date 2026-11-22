package app.flytrack.feature.importticket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.TrackSource
import app.flytrack.core.share.SharedContentHolder
import app.flytrack.core.time.today
import app.flytrack.data.repo.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import kotlin.time.Clock

data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Idle,
    val flights: List<ParsedFlight> = emptyList(),
    val selected: Int = 0,
    val pnr: String? = null,
    val source: TrackSource = TrackSource.SHARE,
    val consent: Boolean = false,
    val busy: Boolean = false,
    val notice: ImportNotice? = null,
) {
    val selectedFlight: ParsedFlight? get() = flights.getOrNull(selected)
}

enum class ImportPhase { Idle, Reading, Choose, Empty }

enum class ImportNotice { Truncated, CloudFailed, RateLimited, Offline, NotConfigured, NothingFound }

sealed interface ImportEvent {
    data class Parsed(
        val flight: String?,
        val date: String?,
        val pnr: String?,
        val source: String?,
    ) : ImportEvent
}

/** Today's date where the phone is. Injected so tests don't depend on the device zone. */
fun interface ImportToday {
    fun today(): LocalDate
}

class ClockImportToday @Inject constructor(private val clock: Clock) : ImportToday {
    override fun today(): LocalDate = clock.today(java.time.ZoneId.systemDefault().id)
}

@HiltViewModel
class ImportTicketViewModel @Inject constructor(
    private val shared: SharedContentHolder,
    private val extractor: TicketTextExtractor,
    private val cloud: CloudTicketParser,
    private val settings: SettingsStore,
    private val today: ImportToday,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val eventsChannel = Channel<ImportEvent>(Channel.BUFFERED)
    val events: Flow<ImportEvent> = eventsChannel.receiveAsFlow()

    private var extractedText: String = ""

    init {
        viewModelScope.launch {
            settings.cloudTicketParsingConsent.collect { granted ->
                _uiState.update { it.copy(consent = granted) }
            }
        }
        val pending = shared.consume()
        if (pending != null && !pending.isEmpty) {
            read(ExtractRequest(uris = pending.uris, mimeType = pending.mimeType, text = pending.text))
        }
    }

    fun onPicked(uris: List<String>, mimeType: String?) {
        if (uris.isEmpty() || _uiState.value.phase == ImportPhase.Reading) return
        read(ExtractRequest(uris = uris, mimeType = mimeType))
    }

    fun onSelect(index: Int) {
        if (index !in _uiState.value.flights.indices) return
        _uiState.update { it.copy(selected = index) }
    }

    fun onCloudConsent(granted: Boolean) {
        viewModelScope.launch { settings.setCloudTicketParsingConsent(granted) }
    }

    /** Cloud is only used when the on-device parser found no flight, and only with consent. */
    fun onTryCloud() {
        val state = _uiState.value
        if (!state.consent || state.busy || extractedText.isBlank()) return
        if (state.flights.isNotEmpty()) return
        viewModelScope.launch { readCloud() }
    }

    fun onContinue() {
        val state = _uiState.value
        val flight = state.selectedFlight ?: return
        viewModelScope.launch {
            eventsChannel.send(
                ImportEvent.Parsed(
                    flight = flight.flight.display,
                    date = flight.date?.toString(),
                    pnr = state.pnr,
                    source = state.source.wire,
                ),
            )
        }
    }

    private fun read(request: ExtractRequest) {
        viewModelScope.launch {
            _uiState.update { it.copy(phase = ImportPhase.Reading, busy = true, notice = null, flights = emptyList()) }
            val extracted = try {
                extractor.extract(request)
            } catch (_: Exception) {
                _uiState.update { it.copy(phase = ImportPhase.Empty, busy = false, notice = ImportNotice.NothingFound) }
                return@launch
            }
            extractedText = extracted.text
            val ticket = parseTicketText(extracted.text, today.today())
            show(ticket, extracted.source, truncated = extracted.truncated, fromCloud = false)
            val consent = settings.cloudTicketParsingConsent.first()
            if (ticket.flights.isEmpty() && consent && extracted.text.isNotBlank()) {
                readCloud()
            }
        }
    }

    private suspend fun readCloud() {
        _uiState.update { it.copy(busy = true) }
        when (val result = cloud.parse(extractedText)) {
            is CloudParseResult.Parsed -> show(result.ticket, _uiState.value.source, truncated = false, fromCloud = true)
            CloudParseResult.RateLimited -> _uiState.update { it.copy(busy = false, notice = ImportNotice.RateLimited) }
            CloudParseResult.NotConfigured -> _uiState.update { it.copy(busy = false, notice = ImportNotice.NotConfigured) }
            CloudParseResult.Offline -> _uiState.update { it.copy(busy = false, notice = ImportNotice.Offline) }
            CloudParseResult.Failed -> _uiState.update { it.copy(busy = false, notice = ImportNotice.CloudFailed) }
        }
    }

    private fun show(ticket: ParsedTicket, source: TrackSource, truncated: Boolean, fromCloud: Boolean) {
        val notice = when {
            ticket.flights.isEmpty() -> if (fromCloud) ImportNotice.NothingFound else _uiState.value.notice
            truncated -> ImportNotice.Truncated
            else -> null
        }
        _uiState.update {
            it.copy(
                phase = if (ticket.flights.isEmpty()) ImportPhase.Empty else ImportPhase.Choose,
                flights = ticket.flights,
                selected = 0,
                pnr = ticket.pnr ?: it.pnr,
                source = source,
                busy = false,
                notice = if (ticket.flights.isEmpty() && !fromCloud) null else notice,
            )
        }
    }
}
