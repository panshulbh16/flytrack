package app.flytrack.feature.billing

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * RevenueCat and backend names from docs/P1_CONTRACTS.md section 1. They must match the
 * RevenueCat dashboard, the Play Console and the backend exactly.
 *
 * Prices are deliberately absent: they live in the Play Console and reach the app through
 * RevenueCat offerings, already formatted for the user's storefront.
 */
object BillingContract {
    /** RevenueCat entitlement granted by both subscriptions (not by the trip pass). */
    const val ENTITLEMENT_PRO = "pro"

    const val OFFERING_DEFAULT = "default"

    const val PACKAGE_ANNUAL = "\$rc_annual"
    const val PACKAGE_MONTHLY = "\$rc_monthly"
    const val PACKAGE_TRIP_PASS = "trip_pass"

    const val PRODUCT_ANNUAL = "heygate_pro_yearly"
    const val PRODUCT_MONTHLY = "heygate_pro_monthly"

    /** Consumable; each purchase grants Pro for [TRIP_PASS_DURATION] from its purchase date. */
    const val PRODUCT_TRIP_PASS = "heygate_trip_pass"
    val TRIP_PASS_DURATION: Duration = 7.days

    /** Edge Function the app calls after a purchase or restore (user JWT, POST). */
    const val FUNCTION_SYNC_ENTITLEMENT = "sync-entitlement"
}
