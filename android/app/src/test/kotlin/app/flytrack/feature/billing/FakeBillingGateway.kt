package app.flytrack.feature.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Scripted [BillingGateway] for JVM tests. */
class FakeBillingGateway(override var isAvailable: Boolean = true) : BillingGateway {
    val store = MutableStateFlow<StoreEntitlement?>(null)
    override val storeEntitlement: StateFlow<StoreEntitlement?> = store

    var offer: OfferResult = OfferResult.NotAvailable
    var purchaseOutcome: PurchaseOutcome = PurchaseOutcome.Purchased
    var restoreOutcome: RestoreOutcome = RestoreOutcome.Restored(hasPro = true)

    /** Applied to [store] when a purchase succeeds, like RevenueCat's CustomerInfo after a purchase. */
    var entitlementAfterPurchase: StoreEntitlement? = null

    var startCalls = 0
    val purchases = mutableListOf<PlanKind>()
    var restoreCalls = 0

    override fun start() {
        startCalls++
    }

    override suspend fun loadOffer(): OfferResult = offer

    override suspend fun purchase(host: PurchaseHost, plan: PlanKind): PurchaseOutcome {
        purchases += plan
        if (purchaseOutcome == PurchaseOutcome.Purchased) entitlementAfterPurchase?.let { store.value = it }
        return purchaseOutcome
    }

    override suspend fun restore(): RestoreOutcome {
        restoreCalls++
        return restoreOutcome
    }
}

/** Never touched by the fake gateway. */
object TestPurchaseHost : PurchaseHost {
    override val activity: Activity get() = error("not used in JVM tests")
}
