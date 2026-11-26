package app.flytrack.core.model

import kotlin.time.Instant

/** `profiles.plan`. */
enum class Plan(val wire: String) {
    FREE("free"),
    PRO("pro");

    companion object {
        fun fromWire(value: String?): Plan = entries.firstOrNull { it.wire == value } ?: FREE
    }
}

/** One row of `public.profiles` (1:1 with the auth user). */
data class UserProfile(
    val id: String,
    val displayName: String?,
    val plan: Plan,
    /** null with [Plan.PRO] means a lifetime or manual grant. */
    val proUntil: Instant?,
    /**
     * `profiles.trial_flight_id`: the one flight a free user gets Pro on for free (their first
     * tracked flight). null until that flight is tracked, and for users who were Pro first.
     */
    val trialFlightId: String? = null,
) {
    /** Same rule as the server's `is_pro()`. */
    fun isPro(now: Instant): Boolean = plan == Plan.PRO && (proUntil == null || proUntil > now)

    /** Same rule as the server's `is_pro_for_flight()`: Pro, or this is the free trial flight. */
    fun isProForFlight(now: Instant, flightId: String): Boolean = isPro(now) || trialFlightId == flightId

    /**
     * Active tracked flights allowed by `enforce_tracking_limit()`. Since P3 (A1) tracking is unlimited as a
     * product; this is only the anti-abuse cap (`tracking_cap`), and the apps never sell against it.
     */
    fun trackingLimit(now: Instant): Int = if (isPro(now)) PRO_TRACKING_LIMIT else FREE_TRACKING_LIMIT

    companion object {
        const val FREE_TRACKING_LIMIT = 100
        const val PRO_TRACKING_LIMIT = 300
    }
}
