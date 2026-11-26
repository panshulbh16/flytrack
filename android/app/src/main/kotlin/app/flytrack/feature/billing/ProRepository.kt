package app.flytrack.feature.billing

import app.flytrack.core.result.ActionResult
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendException
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.data.repo.toActionResult
import app.flytrack.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Response of `sync-entitlement` (contract section 1). */
@Serializable
data class SyncEntitlementResponse(
    val plan: String = "free",
    @SerialName("pro_until") val proUntil: String? = null,
    @SerialName("trial_flight_id") val trialFlightId: String? = null,
)

/**
 * Pro for the whole app: the server profile (`plan`, `pro_until`, `trial_flight_id`) combined with
 * the store's CustomerInfo for an optimistic "you're Pro" right after a purchase. It also runs the
 * purchase and restore flows, which always end with `sync-entitlement` and a profile refresh.
 */
@Singleton
class ProRepository @Inject constructor(
    private val billing: BillingGateway,
    private val profiles: ProfileRepository,
    private val auth: AuthRepository,
    private val api: BackendApi,
    private val json: Json,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val isBillingAvailable: Boolean get() = billing.isAvailable

    /** Configures the store SDK and starts following the signed-in user. Idempotent. */
    fun start() = billing.start()

    /** Re-evaluated whenever the profile or CustomerInfo changes, and every minute so expiries apply. */
    fun observe(tick: Duration = 1.minutes): Flow<ProState> = combine(
        profiles.observeProfile(),
        billing.storeEntitlement,
        auth.session,
        ticker(tick),
    ) { profile, store, session, _ ->
        EntitlementRules.resolve(profile, store, (session as? SessionState.SignedIn)?.userId, clock.now())
    }.distinctUntilChanged()

    suspend fun loadOffer(): OfferResult = billing.loadOffer()

    /** Buys [plan]; on success the server is asked to re-read RevenueCat. A failed sync is not an error for the user. */
    suspend fun purchase(host: PurchaseHost, plan: PlanKind): PurchaseOutcome {
        // A second subscription product (or a trip pass) on top of an active subscription would be
        // billed in addition to it; Play does not stop that across different products.
        if (observe().first().subscriptionActive) return PurchaseOutcome.AlreadyOwned
        val outcome = billing.purchase(host, plan)
        if (outcome == PurchaseOutcome.Purchased) syncEntitlement()
        return outcome
    }

    suspend fun restore(): RestoreOutcome {
        val outcome = billing.restore()
        if (outcome is RestoreOutcome.Restored) syncEntitlement()
        return outcome
    }

    /**
     * `POST sync-entitlement`, then a profile refresh so Room (and every screen) sees the new plan.
     * Without the backend the profile is still refreshed; the RevenueCat webhook catches up later.
     */
    suspend fun syncEntitlement(): ActionResult = withContext(io) {
        val signedIn = auth.ensureSignedIn().toActionResult()
        if (signedIn != ActionResult.Success) return@withContext signedIn
        val synced = try {
            json.decodeFromString(SyncEntitlementResponse.serializer(), api.invokeFunction(BillingContract.FUNCTION_SYNC_ENTITLEMENT, "{}"))
            ActionResult.Success
        } catch (e: BackendException) {
            e.toActionResult()
        } catch (e: SerializationException) {
            ActionResult.Failed("sync-entitlement: unreadable response")
        } catch (e: IllegalArgumentException) {
            ActionResult.Failed("sync-entitlement: ${e.message}")
        }
        val refreshed = profiles.refresh()
        if (synced == ActionResult.Success) refreshed else synced
    }

    private fun ticker(period: Duration): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(period)
        }
    }
}
