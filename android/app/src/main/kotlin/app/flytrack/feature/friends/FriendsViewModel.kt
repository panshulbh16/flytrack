package app.flytrack.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.feature.share.ShareTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off feedback of the friends screens, shown as a snackbar. */
enum class FriendsMessage { OFFLINE, FAILED, REMOVED }

/** How the list itself loaded. */
enum class FriendsLoad { LOADING, LOADED, OFFLINE, NOT_CONFIGURED, FAILED }

/** A code is ready: the screen builds the localised invite text and hands it to [target] (null = copy only). */
data class InviteShareRequest(val code: String, val target: ShareTarget?)

data class FriendsUiState(
    val load: FriendsLoad = FriendsLoad.LOADING,
    val friends: List<Friend> = emptyList(),
    /** The user's open invite code, once created. */
    val inviteCode: String? = null,
    val isCreatingInvite: Boolean = false,
    val inviteShare: InviteShareRequest? = null,
    /** Friends whose remove or alert command is running: their controls are disabled. */
    val busy: Set<String> = emptySet(),
    val message: FriendsMessage? = null,
    val isRefreshing: Boolean = false,
)

/** Friends screen: invite, list with alert switches, remove. */
@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val repository: FriendsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    init {
        load(initial = true)
    }

    /** Pull-to-refresh and coming back from the accept screen. */
    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = !initial) }
        viewModelScope.launch {
            val result = repository.friends()
            _uiState.update { state ->
                when (result) {
                    is RemoteResult.Ok -> state.copy(load = FriendsLoad.LOADED, friends = result.value, isRefreshing = false)
                    // Keep a list that is already on screen; report the problem instead.
                    RemoteResult.Offline -> if (state.load == FriendsLoad.LOADED) {
                        state.copy(isRefreshing = false, message = FriendsMessage.OFFLINE)
                    } else {
                        state.copy(load = FriendsLoad.OFFLINE, isRefreshing = false)
                    }
                    RemoteResult.NotConfigured -> state.copy(load = FriendsLoad.NOT_CONFIGURED, isRefreshing = false)
                    is RemoteResult.Refused, is RemoteResult.Failed -> if (state.load == FriendsLoad.LOADED) {
                        state.copy(isRefreshing = false, message = FriendsMessage.FAILED)
                    } else {
                        state.copy(load = FriendsLoad.FAILED, isRefreshing = false)
                    }
                }
            }
        }
    }

    /**
     * "Invite on WhatsApp", "Share…" or "Copy code": creates the code when there is none yet (the server reuses
     * an open one), then [FriendsUiState.inviteShare] carries it to the screen.
     */
    fun invite(target: ShareTarget?) {
        val state = _uiState.value
        if (state.isCreatingInvite) return
        state.inviteCode?.let { code ->
            _uiState.update { it.copy(inviteShare = InviteShareRequest(code, target)) }
            return
        }
        _uiState.update { it.copy(isCreatingInvite = true) }
        viewModelScope.launch {
            val result = repository.createInvite()
            _uiState.update {
                when (result) {
                    is RemoteResult.Ok -> it.copy(
                        isCreatingInvite = false,
                        inviteCode = result.value,
                        inviteShare = InviteShareRequest(result.value, target),
                    )
                    RemoteResult.Offline -> it.copy(isCreatingInvite = false, message = FriendsMessage.OFFLINE)
                    RemoteResult.NotConfigured -> it.copy(isCreatingInvite = false)
                    is RemoteResult.Refused, is RemoteResult.Failed -> it.copy(isCreatingInvite = false, message = FriendsMessage.FAILED)
                }
            }
        }
    }

    /** The screen handed [request] on. */
    fun inviteHandled(request: InviteShareRequest) {
        _uiState.update { if (it.inviteShare == request) it.copy(inviteShare = null) else it }
    }

    /** Alerts for [friendId]'s flights on or off; the switch moves at once and goes back if the server refuses. */
    fun setAlerts(friendId: String, enabled: Boolean) {
        val before = _uiState.value.friends.firstOrNull { it.id == friendId } ?: return
        if (friendId in _uiState.value.busy || before.alertsEnabled == enabled) return
        _uiState.update { s -> s.copy(friends = s.friends.withAlerts(friendId, enabled), busy = s.busy + friendId) }
        viewModelScope.launch {
            val result = repository.setFriendAlerts(friendId, enabled)
            _uiState.update { s ->
                if (result is RemoteResult.Ok) {
                    s.copy(busy = s.busy - friendId)
                } else {
                    s.copy(friends = s.friends.withAlerts(friendId, before.alertsEnabled), busy = s.busy - friendId, message = result.toMessage())
                }
            }
        }
    }

    /** Ends the friendship (both ways). The screen confirms first. */
    fun remove(friendId: String) {
        if (friendId in _uiState.value.busy) return
        _uiState.update { it.copy(busy = it.busy + friendId) }
        viewModelScope.launch {
            val result = repository.removeFriend(friendId)
            _uiState.update { s ->
                if (result is RemoteResult.Ok) {
                    s.copy(friends = s.friends.filterNot { it.id == friendId }, busy = s.busy - friendId, message = FriendsMessage.REMOVED)
                } else {
                    s.copy(busy = s.busy - friendId, message = result.toMessage())
                }
            }
        }
    }

    fun messageShown(message: FriendsMessage) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }

    private fun List<Friend>.withAlerts(friendId: String, enabled: Boolean) =
        map { if (it.id == friendId) it.copy(alertsEnabled = enabled) else it }

    private fun RemoteResult<*>.toMessage(): FriendsMessage? = when (this) {
        is RemoteResult.Ok, RemoteResult.NotConfigured -> null
        RemoteResult.Offline -> FriendsMessage.OFFLINE
        is RemoteResult.Refused, is RemoteResult.Failed -> FriendsMessage.FAILED
    }
}

/** Why a code was not accepted; null while the input is fine. */
enum class AcceptError { INVALID_CODE, OWN_CODE, ALREADY_FRIENDS, FRIEND_LIMIT, OFFLINE, FAILED }

data class AcceptInviteUiState(
    val input: String = "",
    val isSubmitting: Boolean = false,
    val error: AcceptError? = null,
    /** Set once the friendship exists: the screen says so and offers to go back. */
    val accepted: AcceptedFriend? = null,
) {
    /** The input holds something that can be a code (pasted messages included). */
    val canSubmit: Boolean get() = !isSubmitting && accepted == null && extractInviteCode(input) != null
}

/** "Enter a friend code" screen. */
@HiltViewModel
class AcceptInviteViewModel @Inject constructor(
    private val repository: FriendsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AcceptInviteUiState())
    val uiState: StateFlow<AcceptInviteUiState> = _uiState.asStateFlow()

    /** A code from outside (the route argument); used once, while the field is empty. */
    fun prefill(code: String?) {
        if (code.isNullOrBlank() || _uiState.value.input.isNotEmpty()) return
        onInput(code)
    }

    fun onInput(value: String) {
        // A pasted invite message is shrunk to the code it contains.
        val code = if (value.length > INVITE_CODE_LENGTH + 1) extractInviteCode(value)?.let(::formatInviteCode) else null
        _uiState.update { it.copy(input = (code ?: value.uppercase()).take(MAX_INPUT), error = null) }
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) {
            if (!state.isSubmitting && state.accepted == null) _uiState.update { it.copy(error = AcceptError.INVALID_CODE) }
            return
        }
        val code = extractInviteCode(state.input) ?: return
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            val result = repository.acceptInvite(code)
            _uiState.update {
                when (result) {
                    is AcceptInviteResult.Accepted -> it.copy(isSubmitting = false, accepted = result.friend)
                    AcceptInviteResult.InvalidCode -> it.copy(isSubmitting = false, error = AcceptError.INVALID_CODE)
                    AcceptInviteResult.OwnCode -> it.copy(isSubmitting = false, error = AcceptError.OWN_CODE)
                    AcceptInviteResult.AlreadyFriends -> it.copy(isSubmitting = false, error = AcceptError.ALREADY_FRIENDS)
                    AcceptInviteResult.FriendLimit -> it.copy(isSubmitting = false, error = AcceptError.FRIEND_LIMIT)
                    AcceptInviteResult.Offline -> it.copy(isSubmitting = false, error = AcceptError.OFFLINE)
                    AcceptInviteResult.NotConfigured -> it.copy(isSubmitting = false)
                    is AcceptInviteResult.Failed -> it.copy(isSubmitting = false, error = AcceptError.FAILED)
                }
            }
        }
    }

    private companion object {
        /** "ABCD-EFGH" plus a little slack for stray spaces. */
        const val MAX_INPUT = 12
    }
}
