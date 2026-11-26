package app.flytrack.feature.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** The three things the paywall sells. */
enum class PlanKind(val packageId: String) {
    ANNUAL(BillingContract.PACKAGE_ANNUAL),
    MONTHLY(BillingContract.PACKAGE_MONTHLY),
    TRIP_PASS(BillingContract.PACKAGE_TRIP_PASS),
}

/**
 * A price from the store. [formatted] is the store's own text ("₹4,999.00") and is what the UI
 * shows; the micros are only for derived figures (per-month price, saving).
 */
data class StorePrice(val formatted: String, val amountMicros: Long, val currencyCode: String)

/** One purchasable package of the `default` offering. */
data class PlanOption(val kind: PlanKind, val productId: String, val price: StorePrice)

/** Result of loading the `default` offering. */
sealed interface OfferResult {
    /** At least one of the three packages exists. Sorted annual, monthly, trip pass. */
    data class Available(val plans: List<PlanOption>) : OfferResult

    /** No RevenueCat key in this build, or the store has no products yet: "Pro coming soon". */
    data object NotAvailable : OfferResult

    data object Offline : OfferResult

    /** [message] is technical English for logs. */
    data class Failed(val message: String?) : OfferResult
}

sealed interface PurchaseOutcome {
    data object Purchased : PurchaseOutcome
    data object Cancelled : PurchaseOutcome

    /** Slow payment (UPI collect, cash): Play finishes it later and RevenueCat's webhook updates the profile. */
    data object Pending : PurchaseOutcome

    /** The Play account already owns this subscription (maybe under another HeyGate user): offer restore. */
    data object AlreadyOwned : PurchaseOutcome
    data object NotAvailable : PurchaseOutcome
    data object Offline : PurchaseOutcome
    data class Failed(val message: String?) : PurchaseOutcome
}

sealed interface RestoreOutcome {
    /** [hasPro]: the restored purchases grant Pro (subscription or a trip pass still running). */
    data class Restored(val hasPro: Boolean) : RestoreOutcome
    data object NotAvailable : RestoreOutcome
    data object Offline : RestoreOutcome
    data class Failed(val message: String?) : RestoreOutcome
}

/**
 * What a purchase needs from the screen: Google Play Billing shows its sheet over an Activity.
 * An interface so ViewModels can be tested without an Activity.
 */
interface PurchaseHost {
    val activity: Activity
}

class ActivityPurchaseHost(override val activity: Activity) : PurchaseHost

/**
 * The store, behind an interface: [RevenueCatBilling] in a build with `REVENUECAT_ANDROID_KEY`,
 * [NoopBilling] without one. Commands never throw.
 */
interface BillingGateway {
    /** False without a RevenueCat key: every command returns its NotAvailable result. */
    val isAvailable: Boolean

    /** The latest CustomerInfo of the logged-in RevenueCat user; null before the first one. */
    val storeEntitlement: StateFlow<StoreEntitlement?>

    /** Configures the SDK and keeps its user equal to the Supabase user. Idempotent; returns immediately. */
    fun start()

    suspend fun loadOffer(): OfferResult
    suspend fun purchase(host: PurchaseHost, plan: PlanKind): PurchaseOutcome
    suspend fun restore(): RestoreOutcome
}
