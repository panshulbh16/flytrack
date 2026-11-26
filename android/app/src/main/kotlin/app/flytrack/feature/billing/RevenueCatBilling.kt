package app.flytrack.feature.billing

import android.content.Context
import android.util.Log
import app.flytrack.BuildConfig
import app.flytrack.core.result.AuthResult
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.di.ApplicationScope
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.interfaces.UpdatedCustomerInfoListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * [BillingGateway] on the RevenueCat SDK. The RevenueCat app user id is always the Supabase user
 * id: the SDK is configured with it when a session exists and follows every later session change
 * with `logIn` (anonymous users included; linking Google keeps the id, so nothing changes then).
 * A purchase only starts once RevenueCat is logged in as the current Supabase user, so it can
 * never land on a stale or anonymous RevenueCat id.
 */
@Singleton
class RevenueCatBilling @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: BillingConfig,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : BillingGateway {

    override val isAvailable: Boolean get() = config.isConfigured

    private val store = MutableStateFlow<StoreEntitlement?>(null)
    override val storeEntitlement: StateFlow<StoreEntitlement?> = store.asStateFlow()

    private val started = AtomicBoolean(false)
    private val loginMutex = Mutex()

    /** The Supabase user id RevenueCat is logged in as; null until the first logIn succeeded. */
    @Volatile private var loggedInAs: String? = null

    override fun start() {
        if (!isAvailable || !started.compareAndSet(false, true)) return
        val userId = (auth.session.value as? SessionState.SignedIn)?.userId
        Purchases.logLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN
        val builder = PurchasesConfiguration.Builder(context, config.revenueCatAndroidKey)
        if (userId != null) builder.appUserID(userId)
        Purchases.configure(builder.build())
        if (userId != null) loggedInAs = userId
        Purchases.sharedInstance.updatedCustomerInfoListener = UpdatedCustomerInfoListener { info ->
            publish(info, Purchases.sharedInstance.appUserID)
        }
        scope.launch {
            auth.session
                .map { (it as? SessionState.SignedIn)?.userId }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { ensureLoggedIn(it) }
        }
    }

    override suspend fun loadOffer(): OfferResult {
        if (!isReady()) return OfferResult.NotAvailable
        return try {
            val offering = currentOffering() ?: return OfferResult.NotAvailable
            val plans = PlanKind.entries.mapNotNull { kind -> offering.packageFor(kind)?.toPlanOption(kind) }
            if (plans.isEmpty()) OfferResult.NotAvailable else OfferResult.Available(plans)
        } catch (e: PurchasesException) {
            when {
                e.code.isNetwork() -> OfferResult.Offline
                e.code == PurchasesErrorCode.ConfigurationError -> OfferResult.NotAvailable
                else -> OfferResult.Failed(e.message)
            }
        }
    }

    override suspend fun purchase(host: PurchaseHost, plan: PlanKind): PurchaseOutcome {
        if (!isReady()) return PurchaseOutcome.NotAvailable
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success -> Unit
            AuthResult.Offline -> return PurchaseOutcome.Offline
            AuthResult.NotConfigured -> return PurchaseOutcome.NotAvailable
            AuthResult.GoogleAccountInUse, is AuthResult.Failed -> return PurchaseOutcome.Failed("sign-in: $signedIn")
        }
        val userId = auth.currentUserId ?: return PurchaseOutcome.Failed("no user")
        if (!ensureLoggedIn(userId)) return PurchaseOutcome.Failed("RevenueCat logIn failed")
        return try {
            val pkg = currentOffering()?.packageFor(plan) ?: return PurchaseOutcome.NotAvailable
            val result = Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(host.activity, pkg).build())
            publish(result.customerInfo, userId)
            PurchaseOutcome.Purchased
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseOutcome.Cancelled else e.code.toPurchaseOutcome(e.message)
        } catch (e: PurchasesException) {
            e.code.toPurchaseOutcome(e.message)
        }
    }

    override suspend fun restore(): RestoreOutcome {
        if (!isReady()) return RestoreOutcome.NotAvailable
        // Same as purchase: restored purchases must land on the Supabase user, never on a
        // RevenueCat-anonymous id that sync-entitlement cannot see.
        when (val signedIn = auth.ensureSignedIn()) {
            AuthResult.Success -> Unit
            AuthResult.Offline -> return RestoreOutcome.Offline
            AuthResult.NotConfigured -> return RestoreOutcome.NotAvailable
            AuthResult.GoogleAccountInUse, is AuthResult.Failed -> return RestoreOutcome.Failed("sign-in: $signedIn")
        }
        val userId = auth.currentUserId ?: return RestoreOutcome.Failed("no user")
        if (!ensureLoggedIn(userId)) return RestoreOutcome.Failed("RevenueCat logIn failed")
        return try {
            val info = Purchases.sharedInstance.awaitRestore()
            val snapshot = publish(info, userId)
            val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            val hasPro = EntitlementRules.resolve(null, snapshot, snapshot.appUserId, now).isPro
            RestoreOutcome.Restored(hasPro)
        } catch (e: PurchasesException) {
            if (e.code.isNetwork()) RestoreOutcome.Offline else RestoreOutcome.Failed(e.message)
        }
    }

    private fun isReady(): Boolean {
        if (!isAvailable) return false
        start()
        return Purchases.isConfigured
    }

    /** True when RevenueCat is logged in as [userId] afterwards. */
    private suspend fun ensureLoggedIn(userId: String): Boolean = loginMutex.withLock {
        try {
            val purchases = Purchases.sharedInstance
            if (purchases.appUserID == userId) {
                loggedInAs = userId
                publish(purchases.awaitCustomerInfo(), userId)
            } else {
                // A different (or RevenueCat-anonymous) id: switch. The old user's snapshot is dropped first.
                store.value = null
                val result = purchases.awaitLogIn(userId)
                loggedInAs = userId
                publish(result.customerInfo, userId)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: PurchasesException) {
            Log.w(TAG, "RevenueCat logIn/customerInfo failed: ${e.code}")
            // Offline: the SDK keeps its cached user; only trust it when it already is this user.
            loggedInAs == userId && Purchases.sharedInstance.appUserID == userId
        }
    }

    private suspend fun currentOffering(): Offering? {
        val offerings = Purchases.sharedInstance.awaitOfferings()
        return offerings[BillingContract.OFFERING_DEFAULT] ?: offerings.current
    }

    private fun publish(info: CustomerInfo, appUserId: String): StoreEntitlement {
        val snapshot = info.toStoreEntitlement(appUserId)
        store.value = snapshot
        return snapshot
    }

    private companion object {
        const val TAG = "RevenueCatBilling"
    }
}

private fun Offering.packageFor(kind: PlanKind): Package? = when (kind) {
    PlanKind.ANNUAL -> annual ?: get(kind.packageId)
    PlanKind.MONTHLY -> monthly ?: get(kind.packageId)
    PlanKind.TRIP_PASS -> get(kind.packageId)
}

private fun Package.toPlanOption(kind: PlanKind): PlanOption = PlanOption(
    kind = kind,
    productId = product.id,
    price = StorePrice(
        formatted = product.price.formatted,
        amountMicros = product.price.amountMicros,
        currencyCode = product.price.currencyCode,
    ),
)

private fun Date.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(time)

internal fun CustomerInfo.toStoreEntitlement(appUserId: String): StoreEntitlement {
    val pro = entitlements[BillingContract.ENTITLEMENT_PRO]
    return StoreEntitlement(
        appUserId = appUserId,
        proEntitlementActive = pro?.isActive == true,
        proExpiresAt = pro?.expirationDate?.toKotlinInstant(),
        nonSubscriptionPurchases = nonSubscriptionTransactions.map {
            StorePurchase(productId = it.productIdentifier, purchasedAt = it.purchaseDate.toKotlinInstant())
        },
    )
}

private fun PurchasesErrorCode.isNetwork(): Boolean = this == PurchasesErrorCode.NetworkError

private fun PurchasesErrorCode.toPurchaseOutcome(message: String?): PurchaseOutcome = when (this) {
    PurchasesErrorCode.PurchaseCancelledError -> PurchaseOutcome.Cancelled
    PurchasesErrorCode.PaymentPendingError -> PurchaseOutcome.Pending
    PurchasesErrorCode.ProductAlreadyPurchasedError, PurchasesErrorCode.ReceiptAlreadyInUseError -> PurchaseOutcome.AlreadyOwned
    PurchasesErrorCode.NetworkError -> PurchaseOutcome.Offline
    PurchasesErrorCode.ConfigurationError,
    PurchasesErrorCode.ProductNotAvailableForPurchaseError,
    PurchasesErrorCode.PurchaseNotAllowedError,
    -> PurchaseOutcome.NotAvailable
    else -> PurchaseOutcome.Failed(message)
}
