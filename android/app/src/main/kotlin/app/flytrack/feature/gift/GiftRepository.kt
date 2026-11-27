package app.flytrack.feature.gift

import app.flytrack.data.repo.ProfileRepository
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `my_gifts()` and `redeem_gift()`. Never throws (cancellation aside). */
interface GiftRepository {
    suspend fun myGifts(): RemoteResult<List<Gift>>

    /** Redeems [code]; on success the profile is refreshed so Pro shows everywhere at once. */
    suspend fun redeem(code: String): RedeemOutcome

    /**
     * After a purchase: asks `my_gifts()` a few times until a code appears that was not in [before]
     * (RevenueCat's webhook usually arrives within seconds). Null when it has not arrived yet.
     */
    suspend fun awaitNewCode(before: Set<String>, product: GiftProduct): Gift?
}

class SupabaseGiftRepository @Inject constructor(
    private val caller: Wave2Caller,
    private val profiles: ProfileRepository,
) : GiftRepository {

    /** Delays between the polls after a purchase: about 25 s in all. */
    internal var pollDelays: List<Duration> = listOf(1.seconds, 2.seconds, 3.seconds, 4.seconds, 5.seconds, 5.seconds, 5.seconds)

    override suspend fun myGifts(): RemoteResult<List<Gift>> =
        caller.call { api, _ -> GiftParser.gifts(api.rpc(GiftContract.RPC_MY_GIFTS)) }

    override suspend fun redeem(code: String): RedeemOutcome {
        val canonical = normalizeGiftCode(code) ?: return RedeemOutcome.InvalidCode
        val result = caller.call { api, _ ->
            GiftParser.redeem(api.rpc(GiftContract.RPC_REDEEM, buildJsonObject { put(GiftContract.ARG_CODE, canonical) }))
        }
        val outcome = when (result) {
            is RemoteResult.Ok -> result.value
            RemoteResult.Offline -> RedeemOutcome.Offline
            RemoteResult.NotConfigured -> RedeemOutcome.NotConfigured
            // PostgREST puts the raised text in `message` and the SQLSTATE in `code`.
            is RemoteResult.Refused -> GiftParser.redeemError(result.message, result.code)
            is RemoteResult.Failed -> RedeemOutcome.Failed(result.message)
        }
        if (outcome is RedeemOutcome.Redeemed) profiles.refresh()
        return outcome
    }

    override suspend fun awaitNewCode(before: Set<String>, product: GiftProduct): Gift? {
        for (wait in pollDelays) {
            delay(wait)
            val gifts = (myGifts() as? RemoteResult.Ok)?.value ?: continue
            GiftParser.newCode(before, gifts, product)?.let { return it }
        }
        return null
    }
}
