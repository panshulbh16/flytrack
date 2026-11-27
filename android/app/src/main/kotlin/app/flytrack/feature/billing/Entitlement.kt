package app.flytrack.feature.billing

import app.flytrack.core.model.UserProfile
import app.flytrack.feature.social.FamilyGrant
import kotlin.time.Instant

/**
 * What the store (RevenueCat CustomerInfo) says about one RevenueCat user. Pure Kotlin so the
 * entitlement rules can be unit-tested; `RevenueCatBilling` builds it from the SDK types.
 *
 * @property appUserId the RevenueCat app user id this snapshot belongs to (= Supabase user id)
 * @property proEntitlementActive the `pro` entitlement is active right now
 * @property proExpiresAt expiry of the `pro` entitlement; null while active means lifetime
 * @property nonSubscriptionPurchases every one-time purchase (`nonSubscriptionTransactions`)
 */
data class StoreEntitlement(
    val appUserId: String,
    val proEntitlementActive: Boolean,
    val proExpiresAt: Instant?,
    val nonSubscriptionPurchases: List<StorePurchase> = emptyList(),
)

/** One `nonSubscriptionTransactions` entry. */
data class StorePurchase(val productId: String, val purchasedAt: Instant)

/** Where the user's Pro comes from. */
enum class ProSource {
    /** Not Pro. */
    NONE,

    /** `profiles.plan` / `pro_until` (the server's truth; also manual grants). */
    SERVER,

    /** An active `pro` entitlement in CustomerInfo the server may not have seen yet. */
    SUBSCRIPTION,

    /** A trip pass bought less than seven days ago. */
    TRIP_PASS,

    /** P3 B13: a member of a family whose owner is Pro ("Pro via {owner}'s family"). Never passed on further. */
    FAMILY,
}

/**
 * The Pro state the UI shows.
 *
 * @property isPro Pro on every flight
 * @property proUntil when Pro ends; null while [isPro] means no end (lifetime or manual grant)
 * @property source the grant that lasts longest
 * @property confirmedByServer false while Pro is only optimistic (store says Pro, profile not yet).
 *   Server features (inbound prediction, Pro alerts) only start once this is true.
 * @property trialFlightId the free-trial flight (`profiles.trial_flight_id`), for non-Pro users
 * @property subscriptionActive the store reports an active `pro` subscription for this user (any
 *   store: RevenueCat entitlements are shared across platforms). The paywall then sells nothing,
 *   because a second subscription product would be billed on top of the first.
 */
data class ProState(
    val isPro: Boolean,
    val proUntil: Instant?,
    val source: ProSource,
    val confirmedByServer: Boolean,
    val trialFlightId: String?,
    val subscriptionActive: Boolean = false,
    /** P3 B13: the family owner's name while [source] is [ProSource.FAMILY] (null when they set none). */
    val familyOwnerName: String? = null,
) {
    /** "Pro via {owner}'s family" applies. */
    val isViaFamily: Boolean get() = isPro && source == ProSource.FAMILY

    /** Pro access on one flight: everyone on Pro, or the trial flight for free users. */
    fun accessFor(flightId: String): FlightProAccess = when {
        isPro -> FlightProAccess.PRO
        trialFlightId != null && trialFlightId == flightId -> FlightProAccess.TRIAL
        else -> FlightProAccess.LOCKED
    }

    companion object {
        val Free = ProState(isPro = false, proUntil = null, source = ProSource.NONE, confirmedByServer = true, trialFlightId = null)
    }
}

/** Pro on one flight. [TRIAL] shows the "Pro free for this flight" badge. */
enum class FlightProAccess { PRO, TRIAL, LOCKED }

/** The entitlement rules shared by the paywall, settings and the flight screens. */
object EntitlementRules {

    /**
     * Combines the server profile with the store's optimistic view.
     *
     * A store snapshot for another RevenueCat user (the moment between a sign-in switch and
     * `logIn` finishing) is ignored, so one account's purchase never shows on another.
     */
    fun resolve(
        profile: UserProfile?,
        store: StoreEntitlement?,
        currentUserId: String?,
        now: Instant,
        family: FamilyGrant? = null,
    ): ProState {
        val ownStore = store?.takeIf { currentUserId != null && it.appUserId == currentUserId }
        val subscriptionActive = ownStore != null && ownStore.proEntitlementActive &&
            (ownStore.proExpiresAt == null || ownStore.proExpiresAt > now)
        val grants = buildList {
            if (profile != null && profile.isPro(now)) add(Grant(ProSource.SERVER, profile.proUntil))
            if (ownStore != null) {
                if (subscriptionActive) add(Grant(ProSource.SUBSCRIPTION, ownStore.proExpiresAt))
                tripPassUntil(ownStore.nonSubscriptionPurchases, now)?.let { add(Grant(ProSource.TRIP_PASS, it)) }
            }
        }
        val trial = profile?.trialFlightId
        if (grants.isEmpty()) {
            // Own Pro always wins; a family's Pro (cached from my_family / sync-entitlement) only fills the gap.
            // The server checks family Pro itself (is_pro), so this only decides what the app shows.
            val ownFamily = family?.takeIf { currentUserId != null && it.userId == currentUserId }
            return if (ownFamily != null) {
                ProState(
                    isPro = true,
                    proUntil = null,
                    source = ProSource.FAMILY,
                    confirmedByServer = true,
                    trialFlightId = trial,
                    familyOwnerName = ownFamily.ownerName,
                )
            } else {
                ProState.Free.copy(trialFlightId = trial)
            }
        }

        // The grant that lasts longest wins; null (no end) beats any date. Ties prefer the server.
        val best = grants.maxWith(compareBy<Grant> { it.until == null }.thenBy { it.until }.thenBy { it.source == ProSource.SERVER })
        return ProState(
            isPro = true,
            proUntil = best.until,
            source = best.source,
            confirmedByServer = grants.any { it.source == ProSource.SERVER },
            trialFlightId = trial,
            subscriptionActive = subscriptionActive,
        )
    }

    /**
     * End of the latest trip pass, or null when none is active. Same rule as the backend:
     * the latest `heygate_trip_pass` purchase date + 7 days.
     */
    fun tripPassUntil(purchases: List<StorePurchase>, now: Instant): Instant? {
        val latest = purchases.filter { isTripPass(it.productId) }.maxOfOrNull { it.purchasedAt } ?: return null
        val until = latest + BillingContract.TRIP_PASS_DURATION
        return until.takeIf { it > now }
    }

    /** Google Play ids can carry a `:base-plan` suffix; the trip pass is a one-time product without one. */
    fun isTripPass(productId: String): Boolean = productId.substringBefore(':') == BillingContract.PRODUCT_TRIP_PASS

    private data class Grant(val source: ProSource, val until: Instant?)
}
