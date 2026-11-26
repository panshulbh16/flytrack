package app.flytrack.feature.flights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.core.model.Cabin
import app.flytrack.core.model.Flight
import app.flytrack.core.model.TrackExtras
import app.flytrack.core.result.ActionResult
import app.flytrack.core.result.TrackField
import app.flytrack.core.result.TrackResult
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.feature.checkin.CheckinRule
import app.flytrack.feature.checkin.CheckinRulesRepository
import app.flytrack.feature.checkin.CheckinUiModel
import app.flytrack.feature.checkin.checkinUiModel
import app.flytrack.feature.share.ShareFlight
import app.flytrack.feature.share.ShareRepository
import app.flytrack.feature.share.ShareRequest
import app.flytrack.feature.share.ShareResult
import app.flytrack.feature.share.ShareTarget
import app.flytrack.feature.flights.presentation.AlertGroup
import app.flytrack.feature.flights.presentation.FlightChangeTracker
import app.flytrack.feature.flights.presentation.FlightDetailUiModel
import app.flytrack.feature.flights.presentation.TrackingState
import app.flytrack.feature.flights.presentation.toDetailUiModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import app.flytrack.core.model.UserProfile
import app.flytrack.feature.social.NoSocialStore
import app.flytrack.feature.social.SocialStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** One-off feedback of the detail screen, shown as a snackbar. */
enum class FlightDetailMessage { OFFLINE, REFRESH_FAILED, ACTION_FAILED, TRACKING_LIMIT_REACHED, SHARE_FAILED, SHARE_NOT_TRACKED }

/**
 * The "your booking" editor (PNR, seat and cabin).
 * @property cabin null = not set
 * @property error the field the database rule rejects, null while the input is fine
 */
data class BookingEditorState(
    val pnr: String,
    val seat: String,
    val cabin: Cabin? = null,
    val error: TrackField? = null,
    val isSaving: Boolean = false,
)

data class FlightDetailUiState(
    /** True until the flight is known, or it is certain that it is not. */
    val isLoading: Boolean = true,
    /** null with [isLoading] false: the flight is neither tracked nor cached. */
    val detail: FlightDetailUiModel? = null,
    val isRefreshing: Boolean = false,
    /** A track/untrack/archive command is running: its buttons are disabled. */
    val isWorking: Boolean = false,
    val editor: BookingEditorState? = null,
    val message: FlightDetailMessage? = null,
    /** The flight is no longer tracked from here: leave the screen. */
    val isClosed: Boolean = false,
    /** Web check-in card; null hides it (no rule for the airline, flight gone, cancelled). */
    val checkin: CheckinUiModel? = null,
    /** `create_flight_share` is running. */
    val isSharing: Boolean = false,
    /** A link to hand to the share target; the screen calls [FlightDetailViewModel.shareHandled] after. */
    val share: ShareRequest? = null,
) {
    /** The RPC only shares flights the user tracks (archived rows included). */
    val canShare: Boolean get() = detail != null && detail.trackingState != TrackingState.NOT_TRACKED
}

@HiltViewModel(assistedFactory = FlightDetailViewModel.Factory::class)
class FlightDetailViewModel @AssistedInject constructor(
    @Assisted private val flightId: String,
    private val flights: FlightRepository,
    profiles: ProfileRepository,
    private val clock: Clock,
    private val changes: FlightChangeTracker,
    private val shares: ShareRepository,
    private val checkinRules: CheckinRulesRepository,
    /** P3 B13: a family member is Pro on the server (is_pro) while their own `plan` stays free. */
    social: SocialStore = NoSocialStore,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(flightId: String): FlightDetailViewModel
    }

    private data class Local(
        /** The fetch that runs when the screen opens; until it ends "not found" is not certain. */
        val isFirstSyncRunning: Boolean = true,
        val isRefreshing: Boolean = false,
        val isWorking: Boolean = false,
        /** Switch positions the user chose that the backend has not confirmed yet. */
        val pendingAlerts: Map<AlertGroup, Boolean> = emptyMap(),
        val editor: BookingEditorState? = null,
        val message: FlightDetailMessage? = null,
        val isClosed: Boolean = false,
        val checkinRule: CheckinRule? = null,
        val isSharing: Boolean = false,
        val share: ShareRequest? = null,
    )

    private val local = MutableStateFlow(Local())

    private data class ProfileAccess(val profile: UserProfile?, val viaFamily: Boolean)

    val uiState: StateFlow<FlightDetailUiState> = combine(
        flights.observeTrackedFlight(flightId),
        flights.observeFlight(flightId),
        combine(profiles.observeProfile(), social.familyGrant) { profile, family ->
            ProfileAccess(profile, viaFamily = profile != null && family?.userId == profile.id)
        },
        clock.ticks(),
        local,
    ) { tracked, cached, access, now, local ->
        val flight = tracked?.flight ?: cached
        val profile = access.profile
        val detail = flight?.toDetailUiModel(
            tracking = tracked?.tracking,
            // the trial flight gets Pro data too, and so does a family member (P3 B13)
            isPro = profile?.isProForFlight(now, flightId) == true || access.viaFamily,
            now = now,
            newFields = changes.record(flight, now),
        )?.let { model ->
            model.copy(
                alerts = model.alerts.map { toggle ->
                    local.pendingAlerts[toggle.group]?.takeUnless { toggle.locked }?.let { toggle.copy(enabled = it) } ?: toggle
                },
            )
        }
        FlightDetailUiState(
            isLoading = detail == null && local.isFirstSyncRunning,
            detail = detail,
            isRefreshing = local.isRefreshing,
            isWorking = local.isWorking,
            editor = local.editor,
            message = local.message,
            isClosed = local.isClosed,
            checkin = flight?.let { checkinUiModel(local.checkinRule, it, now) },
            isSharing = local.isSharing,
            share = local.share,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FlightDetailUiState())

    init {
        // Quiet: the cached flight is already on screen, and a failure only means it stays as it was.
        viewModelScope.launch {
            flights.applyServerSync(flightId)
            local.update { it.copy(isFirstSyncRunning = false) }
        }
        // The airline's check-in rule, once the carrier is known (a flight never changes carrier).
        viewModelScope.launch {
            val carrier = merge(flights.observeTrackedFlight(flightId).map { it?.flight }, flights.observeFlight(flightId))
                .filterNotNull()
                .first()
                .carrierIata
            val rule = checkinRules.ruleFor(carrier)
            local.update { it.copy(checkinRule = rule) }
        }
    }

    /** "Share flight": asks the backend for the public link, then [FlightDetailUiState.share] carries it to the screen. */
    fun share(target: ShareTarget) {
        if (local.value.isSharing) return
        local.update { it.copy(isSharing = true) }
        viewModelScope.launch {
            val flight = currentFlight()
            val result = if (flight == null) ShareResult.NotTracked else shares.createShare(flightId)
            local.update {
                when (result) {
                    is ShareResult.Shared -> it.copy(isSharing = false, share = ShareRequest(flight!!.toShareFlight(), result.url, target))
                    ShareResult.NotTracked -> it.copy(isSharing = false, message = FlightDetailMessage.SHARE_NOT_TRACKED)
                    ShareResult.Offline -> it.copy(isSharing = false, message = FlightDetailMessage.OFFLINE)
                    // The app shell already shows a banner for a build without backend keys.
                    ShareResult.NotConfigured -> it.copy(isSharing = false)
                    is ShareResult.Failed -> it.copy(isSharing = false, message = FlightDetailMessage.SHARE_FAILED)
                }
            }
        }
    }

    /** The screen handed [request] to the share target (or could not). */
    fun shareHandled(request: ShareRequest) {
        local.update { if (it.share == request) it.copy(share = null) else it }
    }

    private suspend fun currentFlight(): Flight? =
        flights.observeTrackedFlight(flightId).first()?.flight ?: flights.observeFlight(flightId).first()

    private fun Flight.toShareFlight() = ShareFlight(carrierIata, flightNumber, depIata, arrIata)

    /** Pull-to-refresh: reports problems. */
    fun refresh() {
        if (local.value.isRefreshing) return
        local.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            val result = flights.applyServerSync(flightId)
            local.update {
                it.copy(
                    isRefreshing = false,
                    message = when (result) {
                        ActionResult.Success, ActionResult.NotConfigured -> it.message
                        ActionResult.Offline -> FlightDetailMessage.OFFLINE
                        else -> FlightDetailMessage.REFRESH_FAILED
                    },
                )
            }
        }
    }

    /** Mutes or un-mutes every alert type of [group] for this flight. */
    fun setAlertEnabled(group: AlertGroup, enabled: Boolean) {
        val toggle = uiState.value.detail?.alerts?.firstOrNull { it.group == group }
        if (toggle == null || toggle.locked) return
        local.update { it.copy(pendingAlerts = it.pendingAlerts + (group to enabled)) }
        viewModelScope.launch {
            val result = flights.updateNotifyPrefs(flightId, group.prefs(enabled))
            if (result.isSuccess) {
                // Keep the chosen position until Room shows it, so the switch does not flick back.
                withTimeoutOrNull(2.seconds) {
                    flights.observeTrackedFlight(flightId).first { it == null || group.isEnabled(it.tracking) == enabled }
                }
            }
            local.update { it.copy(pendingAlerts = it.pendingAlerts - group, message = result.toMessage() ?: it.message) }
        }
    }

    /** Tracks a flight that is only cached, e.g. one opened from an old notification after tracking was stopped. */
    fun track() = command {
        when (flights.track(flightId)) {
            is TrackResult.Tracked, is TrackResult.AlreadyTracked, TrackResult.NotConfigured -> null
            is TrackResult.TrackingLimitReached -> FlightDetailMessage.TRACKING_LIMIT_REACHED
            TrackResult.Offline -> FlightDetailMessage.OFFLINE
            is TrackResult.InvalidInput, TrackResult.FlightNotFound, is TrackResult.Failed -> FlightDetailMessage.ACTION_FAILED
        }
    }

    /** Archives the flight (hidden from the list, no alerts) or brings it back. */
    fun setArchived(archived: Boolean) = command {
        flights.archive(flightId, archived).toMessage()
    }

    /** Stops tracking for good and closes the screen. */
    fun untrack() = command {
        val result = flights.untrack(flightId)
        // "Not found" means the row is already gone, which is what the user asked for.
        if (result.isSuccess || result == ActionResult.NotFound) {
            local.update { it.copy(isClosed = true) }
            null
        } else {
            result.toMessage()
        }
    }

    fun openBookingEditor() {
        val booking = uiState.value.detail?.booking ?: return
        local.update {
            it.copy(editor = BookingEditorState(pnr = booking.pnr.orEmpty(), seat = booking.seat.orEmpty(), cabin = booking.cabin))
        }
    }

    fun onPnrChange(value: String) = editEditor { it.copy(pnr = value.uppercase(), error = it.error.takeUnless { e -> e == TrackField.PNR }) }

    fun onSeatChange(value: String) = editEditor {
        it.copy(seat = value.uppercase().take(TrackExtras.MAX_SEAT_LENGTH), error = it.error.takeUnless { e -> e == TrackField.SEAT })
    }

    /** null = not set. */
    fun onCabinChange(value: Cabin?) = editEditor { it.copy(cabin = value) }

    fun dismissBookingEditor() {
        local.update { if (it.editor?.isSaving == true) it else it.copy(editor = null) }
    }

    fun saveBooking() {
        val editor = local.value.editor ?: return
        if (editor.isSaving) return
        editEditor { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            // updateTrackingDetails replaces all four values, so the note is passed through.
            val current = flights.observeTrackedFlight(flightId).first()?.tracking
            val result = flights.updateTrackingDetails(
                flightId,
                TrackExtras(
                    pnr = editor.pnr.trim().ifEmpty { null },
                    seat = editor.seat.trim().ifEmpty { null },
                    cabin = editor.cabin,
                    note = current?.note,
                ),
            )
            local.update {
                when (result) {
                    ActionResult.Success -> it.copy(editor = null)
                    is ActionResult.InvalidInput -> it.copy(editor = it.editor?.copy(isSaving = false, error = result.field))
                    else -> it.copy(editor = it.editor?.copy(isSaving = false), message = result.toMessage() ?: it.message)
                }
            }
        }
    }

    /** The snackbar of [message] is gone. */
    fun messageShown(message: FlightDetailMessage) {
        local.update { if (it.message == message) it.copy(message = null) else it }
    }

    private fun editEditor(change: (BookingEditorState) -> BookingEditorState) {
        local.update { state -> state.copy(editor = state.editor?.let(change)) }
    }

    /** Runs one track/untrack/archive command at a time and shows the message it returns. */
    private fun command(block: suspend () -> FlightDetailMessage?) {
        if (local.value.isWorking) return
        local.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            val message = block()
            local.update { it.copy(isWorking = false, message = message ?: it.message) }
        }
    }

    private fun ActionResult.toMessage(): FlightDetailMessage? = when (this) {
        // The app shell already shows a banner for a build without backend keys.
        ActionResult.Success, ActionResult.NotConfigured -> null
        ActionResult.Offline -> FlightDetailMessage.OFFLINE
        ActionResult.TrackingLimitReached -> FlightDetailMessage.TRACKING_LIMIT_REACHED
        ActionResult.NotFound, is ActionResult.InvalidInput, is ActionResult.Failed -> FlightDetailMessage.ACTION_FAILED
    }
}
