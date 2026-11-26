package app.flytrack.feature.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** `REVENUECAT_ANDROID_KEY` is blank: nothing is sold, the paywall says "Pro coming soon". */
class NoopBilling @Inject constructor() : BillingGateway {
    override val isAvailable: Boolean = false
    override val storeEntitlement: StateFlow<StoreEntitlement?> = MutableStateFlow<StoreEntitlement?>(null).asStateFlow()
    override fun start() = Unit
    override suspend fun loadOffer(): OfferResult = OfferResult.NotAvailable
    override suspend fun purchase(host: PurchaseHost, plan: PlanKind): PurchaseOutcome = PurchaseOutcome.NotAvailable
    override suspend fun restore(): RestoreOutcome = RestoreOutcome.NotAvailable
}

/** The RevenueCat public SDK key baked into the build (`BuildConfig.REVENUECAT_ANDROID_KEY`). */
data class BillingConfig(val revenueCatAndroidKey: String) {
    val isConfigured: Boolean get() = revenueCatAndroidKey.isNotBlank()
}
