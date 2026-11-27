package app.flytrack.feature.gift

import app.flytrack.core.result.AuthResult
import app.flytrack.data.repo.AuthRepository
import app.flytrack.feature.billing.BillingConfig
import app.flytrack.feature.billing.BillingGateway
import app.flytrack.feature.billing.PurchaseHost
import app.flytrack.feature.billing.StorePrice
import com.revenuecat.purchases.ProductType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.models.StoreProduct
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** One gift product as the store sells it; [price] is the store's own text. */
data class GiftOption(val product: GiftProduct, val price: StorePrice)

sealed interface GiftOfferResult {
    /** Sorted year, then trip. */
    data class Available(val options: List<GiftOption>) : GiftOfferResult

    /** No RevenueCat key, or the gift products are not in the store yet. */
    data object NotAvailable : GiftOfferResult
    data object Offline : GiftOfferResult
    data class Failed(val message: String?) : GiftOfferResult
}

sealed interface GiftPurchaseOutcome {
    /** Paid; the code arrives through RevenueCat's webhook (`my_gifts()`). */
    data class Purchased(val transactionId: String?) : GiftPurchaseOutcome
    data object Cancelled : GiftPurchaseOutcome

    /** Slow payment (UPI collect): the code appears in "My gifts" once Play completes it. */
    data object Pending : GiftPurchaseOutcome
    data object NotAvailable : GiftPurchaseOutcome
    data object Offline : GiftPurchaseOutcome
    data class Failed(val message: String?) : GiftPurchaseOutcome
}

/**
 * The gift products in the store. They are consumables outside the `default` offering and outside the
 * `pro` entitlement, so they are fetched by product id rather than through [BillingGateway]'s offering.
 */
interface GiftStore {
    val isAvailable: Boolean
    suspend fun loadOptions(): GiftOfferResult
    suspend fun purchase(host: PurchaseHost, product: GiftProduct): GiftPurchaseOutcome
}

class NoopGiftStore @Inject constructor() : GiftStore {
    override val isAvailable: Boolean = false
    override suspend fun loadOptions(): GiftOfferResult = GiftOfferResult.NotAvailable
    override suspend fun purchase(host: PurchaseHost, product: GiftProduct): GiftPurchaseOutcome = GiftPurchaseOutcome.NotAvailable
}

/**
 * [GiftStore] on RevenueCat. The SDK is configured by [BillingGateway.start]; before buying, RevenueCat must
 * be logged in as the Supabase user, so the webhook credits the code to the right purchaser.
 */
@Singleton
class RevenueCatGiftStore @Inject constructor(
    private val billing: BillingGateway,
    private val auth: AuthRepository,
) : GiftStore {
    override val isAvailable: Boolean get() = billing.isAvailable

    override suspend fun loadOptions(): GiftOfferResult {
        if (!ready()) return GiftOfferResult.NotAvailable
        return try {
            val products = Purchases.sharedInstance.awaitGetProducts(GiftProduct.entries.map { it.productId }, ProductType.INAPP)
            val options = GiftProduct.entries.mapNotNull { gift ->
                products.firstOrNull { GiftProduct.fromProductId(it.id) == gift }?.let { GiftOption(gift, it.storePrice()) }
            }
            if (options.isEmpty()) GiftOfferResult.NotAvailable else GiftOfferResult.Available(options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesException) {
            if (e.code == PurchasesErrorCode.NetworkError) GiftOfferResult.Offline else GiftOfferResult.Failed(e.message)
        }
    }

    override suspend fun purchase(host: PurchaseHost, product: GiftProduct): GiftPurchaseOutcome {
        if (!ready()) return GiftPurchaseOutcome.NotAvailable
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success -> Unit
            AuthResult.Offline -> return GiftPurchaseOutcome.Offline
            AuthResult.NotConfigured -> return GiftPurchaseOutcome.NotAvailable
            AuthResult.GoogleAccountInUse, is AuthResult.Failed -> return GiftPurchaseOutcome.Failed("sign-in: $signedIn")
        }
        val userId = auth.currentUserId ?: return GiftPurchaseOutcome.Failed("no user")
        return try {
            val purchases = Purchases.sharedInstance
            // Never buy on a stale or RevenueCat-anonymous id: the webhook would not find the purchaser.
            if (purchases.appUserID != userId) purchases.awaitLogIn(userId)
            val storeProduct = purchases.awaitGetProducts(listOf(product.productId), ProductType.INAPP)
                .firstOrNull { GiftProduct.fromProductId(it.id) == product }
                ?: return GiftPurchaseOutcome.NotAvailable
            val result = purchases.awaitPurchase(PurchaseParams.Builder(host.activity, storeProduct).build())
            GiftPurchaseOutcome.Purchased(result.storeTransaction.orderId ?: result.storeTransaction.purchaseToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) GiftPurchaseOutcome.Cancelled else e.code.toGiftOutcome(e.message)
        } catch (e: PurchasesException) {
            e.code.toGiftOutcome(e.message)
        }
    }

    private fun ready(): Boolean {
        if (!billing.isAvailable) return false
        billing.start()
        return Purchases.isConfigured
    }
}

private fun StoreProduct.storePrice() = StorePrice(price.formatted, price.amountMicros, price.currencyCode)

private fun PurchasesErrorCode.toGiftOutcome(message: String?): GiftPurchaseOutcome = when (this) {
    PurchasesErrorCode.PurchaseCancelledError -> GiftPurchaseOutcome.Cancelled
    PurchasesErrorCode.PaymentPendingError -> GiftPurchaseOutcome.Pending
    PurchasesErrorCode.NetworkError -> GiftPurchaseOutcome.Offline
    PurchasesErrorCode.ConfigurationError,
    PurchasesErrorCode.ProductNotAvailableForPurchaseError,
    PurchasesErrorCode.PurchaseNotAllowedError,
    -> GiftPurchaseOutcome.NotAvailable
    else -> GiftPurchaseOutcome.Failed(message)
}

@Module
@InstallIn(SingletonComponent::class)
object GiftStoreModule {
    /** RevenueCat only with a key, like [app.flytrack.feature.billing.BillingModule]. */
    @Provides
    @Singleton
    fun provideGiftStore(config: BillingConfig, revenueCat: Provider<RevenueCatGiftStore>, noop: NoopGiftStore): GiftStore =
        if (config.isConfigured) revenueCat.get() else noop
}
