package app.flytrack.feature.gmailimport

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.data.repo.SettingsStore
import app.flytrack.feature.importticket.ImportToday
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Gmail screen shows. */
enum class GmailScanState {
    /** No Gmail account: the disclosure and "Connect Gmail". */
    NotConnected,

    /** Google's account picker or consent screen is being shown. */
    Connecting,
    Scanning,

    /** At least one pending suggestion. */
    Results,

    /** A scan finished and nothing is pending. */
    NoPending,

    /** A scan stopped early (the 50-email read cap) and nothing is pending: "Scan again", not "no flights". */
    MoreToRead,
    Offline,

    /** Access was revoked or expired: "Reconnect Gmail". */
    NeedsReconnect,
}

/** Why connecting did not work; shown under the Connect button. */
enum class GmailConnectNotice { Offline, NotConfigured, Failed }

data class GmailImportUiState(
    val scan: GmailScanState = GmailScanState.Scanning,
    /** Pending suggestions dated today or later that match no tracked flight, sorted by date. */
    val suggestions: List<GmailSuggestion> = emptyList(),
    val notice: GmailConnectNotice? = null,
)

/** The Gmail screen: connect (always with Google's account picker), scan, list pending suggestions. */
@HiltViewModel
class GmailImportViewModel @Inject constructor(
    private val auth: GmailAuth,
    private val connection: GmailConnection,
    private val scanner: GmailScanner,
    private val settings: SettingsStore,
    store: GmailSuggestionStore,
    tracked: TrackedKeysSource,
    today: ImportToday,
    private val notifier: GmailNotifier,
) : ViewModel() {

    // Results means "a scan finished" and MoreToRead "a scan stopped early"; uiState shows Results for
    // either while the list has rows, and NoPending or MoreToRead while it is empty.
    private val step = MutableStateFlow(GmailScanState.Scanning)
    private val notice = MutableStateFlow<GmailConnectNotice?>(null)
    private var beforeConnect = GmailScanState.NotConnected

    // Re-checked on every store change, so an add, a dismiss or a newly tracked flight hides the row.
    private val visible = store.observeAll().map { GmailSuggestions.visible(it, today.today(), tracked.current()) }

    val uiState: StateFlow<GmailImportUiState> = combine(step, visible, notice) { s, list, n ->
        val shown = when {
            s == GmailScanState.Results && list.isEmpty() -> GmailScanState.NoPending
            s == GmailScanState.MoreToRead && list.isNotEmpty() -> GmailScanState.Results
            else -> s
        }
        GmailImportUiState(shown, list, n)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GmailImportUiState())

    private val _resolutions = Channel<PendingIntent>(Channel.BUFFERED)

    /** Google needs the user (account picker, consent): the route launches it and reports back to [onResolution]. */
    val resolutions: Flow<PendingIntent> = _resolutions.receiveAsFlow()

    init {
        viewModelScope.launch {
            when {
                settings.gmailAccountEmail.first() == null -> step.value = GmailScanState.NotConnected
                settings.gmailNeedsReconnect.first() -> step.value = GmailScanState.NeedsReconnect
                else -> scan()
            }
        }
    }

    /** "Try again" on the offline notice, and "Scan again" after a scan stopped early. */
    fun onRetry() {
        if (step.value != GmailScanState.Offline && step.value != GmailScanState.MoreToRead) return
        viewModelScope.launch { scan() }
    }

    /** "Connect Gmail" and "Reconnect Gmail". */
    fun onConnect() {
        val from = step.value
        if (from != GmailScanState.NotConnected && from != GmailScanState.NeedsReconnect) return
        beforeConnect = from
        step.value = GmailScanState.Connecting
        notice.value = null
        viewModelScope.launch {
            when (val result = auth.connect()) {
                is GmailAuthResult.Granted -> finishConnect(result.token)
                is GmailAuthResult.NeedsUser -> {
                    val pending = result.resolution
                    if (pending != null) _resolutions.send(pending) else connectFailed(GmailConnectNotice.Failed)
                }
                GmailAuthResult.NotConfigured -> connectFailed(GmailConnectNotice.NotConfigured)
                GmailAuthResult.Offline -> connectFailed(GmailConnectNotice.Offline)
                GmailAuthResult.Failed -> connectFailed(GmailConnectNotice.Failed)
            }
        }
    }

    /** Google's picker or consent screen closed. Also handled after a recreation, when no connect was running here. */
    fun onResolution(ok: Boolean, data: Intent?) {
        if (!ok) {
            // The user backed out: no notice, just the screen they came from.
            if (step.value == GmailScanState.Connecting) connectFailed(null)
            return
        }
        viewModelScope.launch {
            when (val result = auth.resultFromIntent(data)) {
                is GmailAuthResult.Granted -> finishConnect(result.token)
                GmailAuthResult.Offline -> connectFailed(GmailConnectNotice.Offline)
                else -> connectFailed(GmailConnectNotice.Failed)
            }
        }
    }

    private suspend fun finishConnect(token: String) {
        try {
            connection.completeConnect(token)
        } catch (e: GmailApiException) {
            return connectFailed(if (e.failure == GmailFailure.Offline) GmailConnectNotice.Offline else GmailConnectNotice.Failed)
        }
        scan()
    }

    private fun connectFailed(reason: GmailConnectNotice?) {
        step.value = beforeConnect
        notice.value = reason
    }

    private suspend fun scan() {
        step.value = GmailScanState.Scanning
        // The spec's lifecycle cleanup runs with every scan: past suggestions go, tracked-elsewhere ones become added,
        // and the notifications of both are cancelled.
        scanner.cleanup(notifier)
        step.value = when (scanner.scan().result) {
            ScanResult.Completed -> GmailScanState.Results
            // Unread booking emails remain, so an empty list must not read as "no upcoming flights".
            ScanResult.Partial -> GmailScanState.MoreToRead
            ScanResult.Offline -> GmailScanState.Offline
            ScanResult.NeedsReconnect -> GmailScanState.NeedsReconnect
            ScanResult.NotConnected -> GmailScanState.NotConnected
        }
    }
}
