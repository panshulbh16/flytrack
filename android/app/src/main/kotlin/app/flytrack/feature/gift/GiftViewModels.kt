package app.flytrack.feature.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.feature.billing.PurchaseHost
import app.flytrack.feature.friends.RemoteResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Gift Pro tab of the paywall -------------------------------------------------------------------------

sealed interface GiftShopContent {
    data object Loading : GiftShopContent
    data object ComingSoon : GiftShopContent
    data object Offline : GiftShopContent
    data object Failed : GiftShopContent
    data class Options(val options: List<GiftOption>) : GiftShopContent
}

/** After a purchase. */
sealed interface GiftPurchaseState {
    data object Idle : GiftPurchaseState
    data object Purchasing : GiftPurchaseState

    /** Paid; waiting for the code from the server. */
    data object WaitingForCode : GiftPurchaseState
    data class CodeReady(val gift: Gift) : GiftPurchaseState

    /** Paid, but the code has not arrived yet: it will show in "My gifts". */
    data object CodeLater : GiftPurchaseState
}

enum class GiftShopMessage { PENDING, OFFLINE, NOT_AVAILABLE, FAILED }

data class GiftShopUiState(
    val content: GiftShopContent = GiftShopContent.Loading,
    val selected: GiftProduct? = null,
    val purchase: GiftPurchaseState = GiftPurchaseState.Idle,
    val message: GiftShopMessage? = null,
) {
    val isBusy: Boolean get() = purchase == GiftPurchaseState.Purchasing || purchase == GiftPurchaseState.WaitingForCode
}

@HiltViewModel
class GiftShopViewModel @Inject constructor(
    private val store: GiftStore,
    private val gifts: GiftRepository,
) : ViewModel() {
    private val state = MutableStateFlow(GiftShopUiState())
    val uiState: StateFlow<GiftShopUiState> = state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    fun select(product: GiftProduct) = state.update { it.copy(selected = product) }

    fun purchase(host: PurchaseHost) {
        val s = state.value
        val product = s.selected ?: return
        if (s.isBusy) return
        state.update { it.copy(purchase = GiftPurchaseState.Purchasing, message = null) }
        viewModelScope.launch {
            // The codes the user already has, so the new one can be told apart. Unknown (the list could not be
            // read) means an older unused code could look new: then the code is only shown in "My gifts".
            val before = (gifts.myGifts() as? RemoteResult.Ok)?.value?.map { it.code }?.toSet()
            when (store.purchase(host, product)) {
                is GiftPurchaseOutcome.Purchased -> {
                    if (before == null) {
                        state.update { it.copy(purchase = GiftPurchaseState.CodeLater) }
                        return@launch
                    }
                    state.update { it.copy(purchase = GiftPurchaseState.WaitingForCode) }
                    val gift = gifts.awaitNewCode(before, product)
                    state.update { it.copy(purchase = gift?.let { g -> GiftPurchaseState.CodeReady(g) } ?: GiftPurchaseState.CodeLater) }
                }
                GiftPurchaseOutcome.Cancelled -> state.update { it.copy(purchase = GiftPurchaseState.Idle) }
                GiftPurchaseOutcome.Pending -> state.update { it.copy(purchase = GiftPurchaseState.Idle, message = GiftShopMessage.PENDING) }
                GiftPurchaseOutcome.NotAvailable -> state.update { it.copy(purchase = GiftPurchaseState.Idle, message = GiftShopMessage.NOT_AVAILABLE) }
                GiftPurchaseOutcome.Offline -> state.update { it.copy(purchase = GiftPurchaseState.Idle, message = GiftShopMessage.OFFLINE) }
                is GiftPurchaseOutcome.Failed -> state.update { it.copy(purchase = GiftPurchaseState.Idle, message = GiftShopMessage.FAILED) }
            }
        }
    }

    /** "Buy another gift" after a code was shown. */
    fun startOver() = state.update { it.copy(purchase = GiftPurchaseState.Idle) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun load() {
        if (!store.isAvailable) {
            state.update { it.copy(content = GiftShopContent.ComingSoon) }
            return
        }
        state.update { it.copy(content = GiftShopContent.Loading) }
        viewModelScope.launch {
            val content = when (val offer = store.loadOptions()) {
                is GiftOfferResult.Available -> GiftShopContent.Options(offer.options)
                GiftOfferResult.NotAvailable -> GiftShopContent.ComingSoon
                GiftOfferResult.Offline -> GiftShopContent.Offline
                is GiftOfferResult.Failed -> GiftShopContent.Failed
            }
            state.update { s ->
                val products = (content as? GiftShopContent.Options)?.options?.map { it.product }.orEmpty()
                s.copy(content = content, selected = s.selected?.takeIf { it in products } ?: products.firstOrNull())
            }
        }
    }
}

// ---- Redeem a gift code -----------------------------------------------------------------------------------

data class RedeemGiftUiState(
    val input: String = "",
    val isRedeeming: Boolean = false,
    val outcome: RedeemOutcome? = null,
) {
    /** The canonical code, or null while the input cannot be one. */
    val code: String? get() = normalizeGiftCode(input)
    val canRedeem: Boolean get() = code != null && !isRedeeming
}

@HiltViewModel
class RedeemGiftViewModel @Inject constructor(
    private val gifts: GiftRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RedeemGiftUiState())
    val uiState: StateFlow<RedeemGiftUiState> = state.asStateFlow()

    fun prefill(code: String?) {
        if (code.isNullOrBlank() || state.value.input.isNotEmpty()) return
        state.update { it.copy(input = extractGiftCode(code) ?: code.trim()) }
    }

    /** A pasted WhatsApp message becomes just its code. */
    fun onInput(value: String) {
        val extracted = if (value.length > GIFT_CODE_INPUT_MAX) extractGiftCode(value) else null
        state.update { it.copy(input = (extracted ?: value).take(GIFT_CODE_INPUT_MAX), outcome = null) }
    }

    fun redeem() {
        val s = state.value
        val code = s.code ?: return
        if (s.isRedeeming) return
        state.update { it.copy(isRedeeming = true, outcome = null) }
        viewModelScope.launch {
            val outcome = gifts.redeem(code)
            state.update { it.copy(isRedeeming = false, outcome = outcome, input = if (outcome is RedeemOutcome.Redeemed) "" else it.input) }
        }
    }

    private companion object {
        /** "ABCD-EFGH-JKMN" plus a little room for spaces. */
        const val GIFT_CODE_INPUT_MAX = 20
    }
}

// ---- My gifts ----------------------------------------------------------------------------------------------

sealed interface MyGiftsContent {
    data object Loading : MyGiftsContent
    data class Loaded(val gifts: List<Gift>) : MyGiftsContent
    data object Offline : MyGiftsContent
    data object NotConfigured : MyGiftsContent
    data object Failed : MyGiftsContent
}

@HiltViewModel
class MyGiftsViewModel @Inject constructor(
    private val gifts: GiftRepository,
) : ViewModel() {
    private val state = MutableStateFlow<MyGiftsContent>(MyGiftsContent.Loading)
    val uiState: StateFlow<MyGiftsContent> = state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (state.value !is MyGiftsContent.Loaded) state.value = MyGiftsContent.Loading
        viewModelScope.launch {
            state.value = when (val result = gifts.myGifts()) {
                is RemoteResult.Ok -> MyGiftsContent.Loaded(result.value)
                RemoteResult.Offline -> (state.value as? MyGiftsContent.Loaded) ?: MyGiftsContent.Offline
                RemoteResult.NotConfigured -> MyGiftsContent.NotConfigured
                is RemoteResult.Refused, is RemoteResult.Failed -> (state.value as? MyGiftsContent.Loaded) ?: MyGiftsContent.Failed
            }
        }
    }
}
