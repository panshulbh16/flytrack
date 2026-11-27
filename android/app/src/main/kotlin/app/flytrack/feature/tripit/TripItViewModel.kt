package app.flytrack.feature.tripit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.data.repo.FlightRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The TripIt row in Settings. */
sealed interface TripItRowState {
    data object Loading : TripItRowState

    /** The server has no TripIt key yet (or the backend is not configured): the row is not shown. */
    data object Hidden : TripItRowState

    data object Disconnected : TripItRowState

    /** [needsReconnect]: the user revoked access in TripIt. */
    data class Connected(val lastSyncAt: Instant?, val needsReconnect: Boolean, val tripItTrouble: Boolean) : TripItRowState

    /** Status could not be read (offline, server error); the row offers a retry. */
    data object Unknown : TripItRowState
}

enum class TripItMessage {
    CONNECTED, CONNECT_DENIED, CONNECT_EXPIRED, CONNECT_FAILED, TRIPIT_DOWN,
    SYNCED_NOTHING_NEW, OFFLINE, FAILED, DISCONNECTED, REVOKED, NOT_CONNECTED,

    /** `not_your_link`: the TripIt link was started from another HeyGate account; nothing was connected. */
    NOT_YOUR_LINK,

    /** `cooldown`: "Sync now" was used less than a minute ago; [TripItUiState.cooldownSeconds] says how long. */
    SYNC_COOLDOWN,
}

/** A result to show after a sync: how many flights were added. */
data class TripItSyncNotice(val added: Int, val notFound: Int)

data class TripItUiState(
    val row: TripItRowState = TripItRowState.Loading,
    val isConnecting: Boolean = false,
    val isSyncing: Boolean = false,
    val isDisconnecting: Boolean = false,
    val message: TripItMessage? = null,
    /** With [TripItMessage.SYNC_COOLDOWN]: seconds until "Sync now" works again. */
    val cooldownSeconds: Int? = null,
    val syncNotice: TripItSyncNotice? = null,
    /** One-shot: open this TripIt page in a Custom Tab, then call [TripItViewModel.authorizeOpened]. */
    val openUrl: String? = null,
    /** `pro_required`: the screen opens the paywall. */
    val showPaywall: Boolean = false,
) {
    val isBusy: Boolean get() = isConnecting || isSyncing || isDisconnecting
}

@HiltViewModel
class TripItViewModel internal constructor(
    private val tripIt: TripItRepository,
    private val flights: FlightRepository,
    private val links: TripItLinkStore,
) : ViewModel() {

    @Inject constructor(tripIt: TripItRepository, flights: FlightRepository) : this(tripIt, flights, TripItDeepLinks)

    private val state = MutableStateFlow(TripItUiState())
    val uiState: StateFlow<TripItUiState> = state.asStateFlow()

    /** How long the server's first background sync usually takes before the flight list is refreshed. */
    internal var firstSyncWait: Duration = 6.seconds

    init {
        refresh()
        viewModelScope.launch {
            links.pending.collect { link -> if (link != null) onLink(links.consume() ?: link) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val row = when (val result = tripIt.status()) {
                is TripItResult.Ok -> rowFor(result.value)
                TripItResult.NotOffered, TripItResult.BackendNotConfigured -> TripItRowState.Hidden
                else -> if (state.value.row is TripItRowState.Loading) TripItRowState.Unknown else state.value.row
            }
            state.update { it.copy(row = row) }
        }
    }

    fun connect() {
        if (state.value.isBusy) return
        state.update { it.copy(isConnecting = true, message = null) }
        viewModelScope.launch {
            when (val result = tripIt.connect()) {
                is TripItResult.Ok -> state.update { it.copy(isConnecting = false, openUrl = result.value) }
                TripItResult.ProRequired -> state.update { it.copy(isConnecting = false, showPaywall = true) }
                TripItResult.NotOffered, TripItResult.BackendNotConfigured -> state.update { it.copy(isConnecting = false, row = TripItRowState.Hidden) }
                TripItResult.TripItDown -> state.update { it.copy(isConnecting = false, message = TripItMessage.TRIPIT_DOWN) }
                TripItResult.Offline -> state.update { it.copy(isConnecting = false, message = TripItMessage.OFFLINE) }
                else -> state.update { it.copy(isConnecting = false, message = TripItMessage.CONNECT_FAILED) }
            }
        }
    }

    /** The Custom Tab was opened (or could not be); the URL is not opened twice. */
    fun authorizeOpened() = state.update { it.copy(openUrl = null) }

    fun paywallShown() = state.update { it.copy(showPaywall = false) }

    fun sync() {
        if (state.value.isBusy) return
        state.update { it.copy(isSyncing = true, message = null, syncNotice = null) }
        viewModelScope.launch {
            when (val result = tripIt.sync()) {
                is TripItResult.Ok -> {
                    val summary = result.value
                    if (summary.added > 0) flights.refresh()
                    state.update {
                        it.copy(
                            isSyncing = false,
                            syncNotice = if (summary.added > 0 || summary.notFound > 0) TripItSyncNotice(summary.added, summary.notFound) else null,
                            message = if (summary.added == 0 && summary.notFound == 0) TripItMessage.SYNCED_NOTHING_NEW else null,
                        )
                    }
                    refresh()
                }
                TripItResult.ProRequired -> state.update { it.copy(isSyncing = false, showPaywall = true) }
                TripItResult.Revoked -> state.update {
                    it.copy(isSyncing = false, message = TripItMessage.REVOKED, row = TripItRowState.Connected(lastSyncAt(it.row), needsReconnect = true, tripItTrouble = false))
                }
                TripItResult.NotConnected -> state.update { it.copy(isSyncing = false, message = TripItMessage.NOT_CONNECTED, row = TripItRowState.Disconnected) }
                TripItResult.TripItDown -> state.update { it.copy(isSyncing = false, message = TripItMessage.TRIPIT_DOWN) }
                TripItResult.NotOffered, TripItResult.BackendNotConfigured -> state.update { it.copy(isSyncing = false, row = TripItRowState.Hidden) }
                TripItResult.Offline -> state.update { it.copy(isSyncing = false, message = TripItMessage.OFFLINE) }
                is TripItResult.Cooldown -> state.update {
                    it.copy(isSyncing = false, message = TripItMessage.SYNC_COOLDOWN, cooldownSeconds = result.retryAfterSeconds)
                }
                is TripItResult.Failed, TripItResult.NotYourLink, TripItResult.Expired, TripItResult.Denied ->
                    state.update { it.copy(isSyncing = false, message = TripItMessage.FAILED) }
            }
        }
    }

    fun disconnect() {
        if (state.value.isBusy) return
        state.update { it.copy(isDisconnecting = true, message = null, syncNotice = null) }
        viewModelScope.launch {
            when (tripIt.disconnect()) {
                is TripItResult.Ok, TripItResult.NotConnected ->
                    state.update { it.copy(isDisconnecting = false, row = TripItRowState.Disconnected, message = TripItMessage.DISCONNECTED) }
                TripItResult.Offline -> state.update { it.copy(isDisconnecting = false, message = TripItMessage.OFFLINE) }
                else -> state.update { it.copy(isDisconnecting = false, message = TripItMessage.FAILED) }
            }
        }
    }

    fun messageShown() = state.update { it.copy(message = null, cooldownSeconds = null) }

    fun syncNoticeShown() = state.update { it.copy(syncNotice = null) }

    private fun onLink(link: TripItLink) {
        when (link) {
            TripItLink.Connected -> connected()
            is TripItLink.Code -> complete(link.code)
            is TripItLink.Failed -> state.update {
                it.copy(
                    message = when (link.reason) {
                        TripItLinkError.DENIED -> TripItMessage.CONNECT_DENIED
                        TripItLinkError.EXPIRED -> TripItMessage.CONNECT_EXPIRED
                        TripItLinkError.TRIPIT_ERROR -> TripItMessage.TRIPIT_DOWN
                        TripItLinkError.NOT_CONFIGURED, TripItLinkError.INTERNAL -> TripItMessage.CONNECT_FAILED
                    },
                )
            }
        }
    }

    /**
     * `heygate://tripit/callback?code=...`: finish the connect as the signed-in user. The server exchanges the
     * token only when this account started the link; the code is single use either way.
     */
    private fun complete(code: String) {
        state.update { it.copy(isConnecting = true, message = null, syncNotice = null) }
        viewModelScope.launch {
            val result = tripIt.complete(code)
            state.update { it.copy(isConnecting = false) }
            when (result) {
                is TripItResult.Ok -> connected()
                else -> state.update { completeFailure(result)?.let { m -> it.copy(message = m) } ?: it.copy(row = TripItRowState.Hidden) }
            }
        }
    }

    private fun connected() {
        state.update { it.copy(message = TripItMessage.CONNECTED) }
        refresh()
        // The server starts the first sync in the background right after connecting.
        viewModelScope.launch {
            delay(firstSyncWait)
            flights.refresh()
            refresh()
        }
    }

    companion object {
        /** The message for a failed `tripit-complete`; null when the TripIt row should be hidden instead. */
        fun completeFailure(result: TripItResult<*>): TripItMessage? = when (result) {
            TripItResult.NotYourLink -> TripItMessage.NOT_YOUR_LINK
            TripItResult.Expired -> TripItMessage.CONNECT_EXPIRED
            TripItResult.Denied -> TripItMessage.CONNECT_DENIED
            TripItResult.TripItDown -> TripItMessage.TRIPIT_DOWN
            TripItResult.Offline -> TripItMessage.OFFLINE
            TripItResult.NotOffered, TripItResult.BackendNotConfigured -> null
            else -> TripItMessage.CONNECT_FAILED
        }

        fun rowFor(status: TripItStatus): TripItRowState =
            if (!status.connected) {
                TripItRowState.Disconnected
            } else {
                TripItRowState.Connected(
                    lastSyncAt = status.lastSyncAt,
                    needsReconnect = status.lastError == TripItLastError.REVOKED,
                    tripItTrouble = status.lastError == TripItLastError.TRIPIT_ERROR,
                )
            }

        private fun lastSyncAt(row: TripItRowState): Instant? = (row as? TripItRowState.Connected)?.lastSyncAt
    }
}
