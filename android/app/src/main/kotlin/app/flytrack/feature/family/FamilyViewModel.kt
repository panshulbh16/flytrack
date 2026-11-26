package app.flytrack.feature.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.feature.friends.extractInviteCode
import app.flytrack.feature.friends.normalizeInviteCode
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FamilyLoad { LOADING, LOADED, OFFLINE, NOT_CONFIGURED, FAILED }

/** A running command; the matching button shows progress, the others wait. */
enum class FamilyAction { CREATE_INVITE, JOIN, LEAVE, REMOVE }

/** One-shot feedback after a command. */
sealed interface FamilyMessage {
    data class Joined(val ownerName: String?) : FamilyMessage
    data object Left : FamilyMessage
    data object Ended : FamilyMessage
    data object Removed : FamilyMessage
    data class Refused(val error: FamilyError) : FamilyMessage
    data object Offline : FamilyMessage
    data object Failed : FamilyMessage
}

/** A ready invite to hand to the share sheet (or the clipboard when [copyOnly]). */
data class FamilyInviteShare(val code: String, val copyOnly: Boolean, val id: Long)

data class FamilyUiState(
    val load: FamilyLoad = FamilyLoad.LOADING,
    val family: FamilyInfo? = null,
    val codeInput: String = "",
    /** The typed text cannot be a code (checked on Join). */
    val codeInvalid: Boolean = false,
    val running: FamilyAction? = null,
    /** The member whose removal runs. */
    val removingUserId: String? = null,
    val message: FamilyMessage? = null,
    val inviteShare: FamilyInviteShare? = null,
    val isRefreshing: Boolean = false,
) {
    val isBusy: Boolean get() = running != null
}

/** Family sharing (P3 B13): create or join, members, leave or remove. */
@HiltViewModel(assistedFactory = FamilyViewModel.Factory::class)
class FamilyViewModel @AssistedInject constructor(
    @Assisted prefillCode: String?,
    private val repository: FamilyRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(prefillCode: String?): FamilyViewModel
    }

    private val _uiState = MutableStateFlow(FamilyUiState(codeInput = prefillCode?.let { extractInviteCode(it) ?: it.take(MAX_CODE_INPUT) }.orEmpty()))
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()
    private var shareId = 0L

    init {
        refresh()
    }

    fun refresh() {
        val loaded = _uiState.value.load == FamilyLoad.LOADED
        _uiState.update { it.copy(isRefreshing = loaded, load = if (loaded) it.load else FamilyLoad.LOADING) }
        viewModelScope.launch { reload(keepOnError = loaded) }
    }

    private suspend fun reload(keepOnError: Boolean) {
        val result = repository.myFamily()
        _uiState.update { state ->
            when (result) {
                is FamilyResult.Ok -> state.copy(load = FamilyLoad.LOADED, family = result.value, isRefreshing = false)
                FamilyResult.Offline -> if (keepOnError) state.copy(isRefreshing = false) else state.copy(load = FamilyLoad.OFFLINE, isRefreshing = false)
                FamilyResult.NotConfigured -> state.copy(load = FamilyLoad.NOT_CONFIGURED, isRefreshing = false)
                is FamilyResult.Refused, is FamilyResult.Failed ->
                    if (keepOnError) state.copy(isRefreshing = false) else state.copy(load = FamilyLoad.FAILED, isRefreshing = false)
            }
        }
    }

    fun onCodeInputChange(text: String) {
        val code = extractInviteCode(text) ?: text.take(MAX_CODE_INPUT)
        _uiState.update { it.copy(codeInput = code, codeInvalid = false) }
    }

    /** Owner: share the open invite, creating it (and the family) on first use. */
    fun shareInvite(copyOnly: Boolean = false) {
        if (_uiState.value.isBusy) return
        val open = _uiState.value.family?.invite?.code
        if (open != null) {
            _uiState.update { it.copy(inviteShare = FamilyInviteShare(open, copyOnly, ++shareId)) }
            return
        }
        run(FamilyAction.CREATE_INVITE) {
            when (val result = repository.createInvite()) {
                is FamilyResult.Ok -> {
                    reload(keepOnError = true)
                    _uiState.update { it.copy(inviteShare = FamilyInviteShare(result.value, copyOnly, ++shareId)) }
                    null
                }
                else -> result.toMessage()
            }
        }
    }

    fun inviteHandled(share: FamilyInviteShare) {
        _uiState.update { if (it.inviteShare?.id == share.id) it.copy(inviteShare = null) else it }
    }

    fun join() {
        val state = _uiState.value
        if (state.isBusy) return
        if (normalizeInviteCode(state.codeInput) == null) {
            _uiState.update { it.copy(codeInvalid = true) }
            return
        }
        run(FamilyAction.JOIN) {
            when (val result = repository.acceptInvite(state.codeInput)) {
                is FamilyResult.Ok -> {
                    _uiState.update { it.copy(codeInput = "") }
                    reload(keepOnError = true)
                    FamilyMessage.Joined(result.value.ownerName)
                }
                else -> result.toMessage()
            }
        }
    }

    /** A member leaves; the owner ends the family for everyone (the screen confirms first). */
    fun leave() {
        if (_uiState.value.isBusy) return
        val wasOwner = _uiState.value.family?.isOwner == true
        run(FamilyAction.LEAVE) {
            when (val result = repository.leave()) {
                is FamilyResult.Ok -> {
                    reload(keepOnError = true)
                    if (wasOwner) FamilyMessage.Ended else FamilyMessage.Left
                }
                else -> result.toMessage()
            }
        }
    }

    fun remove(userId: String) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(removingUserId = userId) }
        run(FamilyAction.REMOVE) {
            when (val result = repository.removeMember(userId)) {
                is FamilyResult.Ok -> {
                    reload(keepOnError = true)
                    FamilyMessage.Removed
                }
                else -> result.toMessage()
            }
        }
    }

    fun messageShown(message: FamilyMessage) {
        _uiState.update { if (it.message == message) it.copy(message = null) else it }
    }

    private fun run(action: FamilyAction, block: suspend () -> FamilyMessage?) {
        _uiState.update { it.copy(running = action, message = null) }
        viewModelScope.launch {
            val message = block()
            _uiState.update { it.copy(running = null, removingUserId = null, message = message ?: it.message) }
        }
    }

    private fun FamilyResult<*>.toMessage(): FamilyMessage = when (this) {
        is FamilyResult.Ok -> FamilyMessage.Failed
        is FamilyResult.Refused -> FamilyMessage.Refused(error)
        FamilyResult.Offline -> FamilyMessage.Offline
        FamilyResult.NotConfigured, is FamilyResult.Failed -> FamilyMessage.Failed
    }

    private companion object {
        /** Room for "ABCD-EFGH" with spaces. */
        const val MAX_CODE_INPUT = 12
    }
}
