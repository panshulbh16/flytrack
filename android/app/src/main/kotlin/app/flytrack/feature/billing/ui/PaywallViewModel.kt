package app.flytrack.feature.billing.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.flytrack.feature.billing.OfferResult
import app.flytrack.feature.billing.PlanKind
import app.flytrack.feature.billing.PlanOption
import app.flytrack.feature.billing.PlanPricing
import app.flytrack.feature.billing.ProRepository
import app.flytrack.feature.billing.ProState
import app.flytrack.feature.billing.PurchaseHost
import app.flytrack.feature.billing.PurchaseOutcome
import app.flytrack.feature.billing.RestoreOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** What the plan area of the paywall shows. */
sealed interface PaywallContent {
    data object Loading : PaywallContent

    /** No RevenueCat key, or no products in the store yet. */
    data object ComingSoon : PaywallContent
    data object Offline : PaywallContent
    data object Failed : PaywallContent
    data class Plans(val plans: List<PlanRow>) : PaywallContent
}

/**
 * One plan as shown. [price] is the store's own text; [perMonth] and [savePercent] are derived
 * from store prices (annual only).
 */
data class PlanRow(val kind: PlanKind, val price: String, val perMonth: String? = null, val savePercent: Int? = null)

enum class PaywallMessage {
    PURCHASED, PENDING, ALREADY_OWNED, FAILED, OFFLINE, NOT_AVAILABLE,
    RESTORED, NOTHING_RESTORED, RESTORE_FAILED,
}

data class PaywallUiState(
    val content: PaywallContent = PaywallContent.Loading,
    val selected: PlanKind? = null,
    val pro: ProState = ProState.Free,
    val isPurchasing: Boolean = false,
    val isRestoring: Boolean = false,
    val message: PaywallMessage? = null,
) {
    val isBusy: Boolean get() = isPurchasing || isRestoring
}

@HiltViewModel
class PaywallViewModel @Inject constructor(
    private val pro: ProRepository,
) : ViewModel() {

    private data class Local(
        val content: PaywallContent = PaywallContent.Loading,
        val selected: PlanKind? = null,
        val isPurchasing: Boolean = false,
        val isRestoring: Boolean = false,
        val message: PaywallMessage? = null,
    )

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<PaywallUiState> = combine(local, pro.observe()) { l, proState ->
        PaywallUiState(l.content, l.selected, proState, l.isPurchasing, l.isRestoring, l.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaywallUiState())

    init {
        pro.start()
        load()
    }

    fun retry() = load()

    fun select(kind: PlanKind) {
        local.update { it.copy(selected = kind) }
    }

    fun purchase(host: PurchaseHost) {
        val state = local.value
        val plan = state.selected ?: return
        if (state.isPurchasing || state.isRestoring) return
        local.update { it.copy(isPurchasing = true, message = null) }
        viewModelScope.launch {
            val message = when (pro.purchase(host, plan)) {
                PurchaseOutcome.Purchased -> PaywallMessage.PURCHASED
                PurchaseOutcome.Cancelled -> null
                PurchaseOutcome.Pending -> PaywallMessage.PENDING
                PurchaseOutcome.AlreadyOwned -> PaywallMessage.ALREADY_OWNED
                PurchaseOutcome.NotAvailable -> PaywallMessage.NOT_AVAILABLE
                PurchaseOutcome.Offline -> PaywallMessage.OFFLINE
                is PurchaseOutcome.Failed -> PaywallMessage.FAILED
            }
            local.update { it.copy(isPurchasing = false, message = message) }
        }
    }

    fun restore() {
        val state = local.value
        if (state.isPurchasing || state.isRestoring) return
        local.update { it.copy(isRestoring = true, message = null) }
        viewModelScope.launch {
            val message = when (val outcome = pro.restore()) {
                is RestoreOutcome.Restored -> if (outcome.hasPro) PaywallMessage.RESTORED else PaywallMessage.NOTHING_RESTORED
                RestoreOutcome.NotAvailable -> PaywallMessage.NOT_AVAILABLE
                RestoreOutcome.Offline -> PaywallMessage.OFFLINE
                is RestoreOutcome.Failed -> PaywallMessage.RESTORE_FAILED
            }
            local.update { it.copy(isRestoring = false, message = message) }
        }
    }

    fun messageShown() {
        local.update { it.copy(message = null) }
    }

    private fun load() {
        if (!pro.isBillingAvailable) {
            local.update { it.copy(content = PaywallContent.ComingSoon) }
            return
        }
        local.update { it.copy(content = PaywallContent.Loading) }
        viewModelScope.launch {
            val content = when (val offer = pro.loadOffer()) {
                is OfferResult.Available -> PaywallContent.Plans(planRows(offer.plans))
                OfferResult.NotAvailable -> PaywallContent.ComingSoon
                OfferResult.Offline -> PaywallContent.Offline
                is OfferResult.Failed -> PaywallContent.Failed
            }
            local.update { l ->
                val kinds = (content as? PaywallContent.Plans)?.plans?.map { it.kind }.orEmpty()
                val selected = l.selected?.takeIf { it in kinds } ?: defaultSelection(kinds)
                l.copy(content = content, selected = selected)
            }
        }
    }

    companion object {
        /** Annual first (the best value), then monthly, then the trip pass. */
        fun defaultSelection(kinds: List<PlanKind>): PlanKind? = PlanKind.entries.firstOrNull { it in kinds }

        fun planRows(plans: List<PlanOption>, locale: Locale = Locale.getDefault()): List<PlanRow> {
            val monthly = plans.firstOrNull { it.kind == PlanKind.MONTHLY }
            return plans.sortedBy { it.kind.ordinal }.map { plan ->
                when (plan.kind) {
                    PlanKind.ANNUAL -> PlanRow(
                        kind = plan.kind,
                        price = plan.price.formatted,
                        perMonth = PlanPricing.monthlyEquivalent(plan.price, locale),
                        savePercent = monthly?.let { PlanPricing.annualSavingPercent(plan.price, it.price) },
                    )
                    PlanKind.MONTHLY, PlanKind.TRIP_PASS -> PlanRow(kind = plan.kind, price = plan.price.formatted)
                }
            }
        }
    }
}
