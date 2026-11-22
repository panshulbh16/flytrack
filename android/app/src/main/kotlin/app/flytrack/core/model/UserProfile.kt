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
) {
    /** Same rule as the server's `is_pro()`. */
    fun isPro(now: Instant): Boolean = plan == Plan.PRO && (proUntil == null || proUntil > now)

    /** Active tracked flights allowed by `enforce_tracking_limit()`. */
    fun trackingLimit(now: Instant): Int = if (isPro(now)) PRO_TRACKING_LIMIT else FREE_TRACKING_LIMIT

    companion object {
        const val FREE_TRACKING_LIMIT = 10
        const val PRO_TRACKING_LIMIT = 50
    }
}
