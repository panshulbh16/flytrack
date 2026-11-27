package app.flytrack.feature.gift

import app.flytrack.feature.friends.INVITE_ALPHABET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant

/** Gift Pro (docs/P4_CONTRACTS.md section 3 and the notes in section 7). Pure Kotlin, JVM-tested. */
object GiftContract {
    /** Consumable, 365 days of Pro for someone else. Not attached to the `pro` entitlement. */
    const val PRODUCT_YEAR = "heygate_gift_year"

    /** Consumable, 7 days of Pro for someone else. */
    const val PRODUCT_TRIP = "heygate_gift_trip"

    const val RPC_MY_GIFTS = "my_gifts"
    const val RPC_REDEEM = "redeem_gift"
    const val ARG_CODE = "p_code"

    const val CODE_LENGTH = 12
}

/** The two gift products, with the days they give. */
enum class GiftProduct(val productId: String, val days: Int) {
    YEAR(GiftContract.PRODUCT_YEAR, 365),
    TRIP(GiftContract.PRODUCT_TRIP, 7),
    ;

    companion object {
        fun fromProductId(id: String?): GiftProduct? {
            // Play subscription ids can carry ":base-plan"; consumables do not, but accept it anyway.
            val base = id?.substringBefore(':')
            return entries.firstOrNull { it.productId == base }
        }
    }
}

// ---- codes --------------------------------------------------------------------------------------------

/**
 * A typed or pasted code in its canonical form "ABCD-EFGH-JKMN", or null when it cannot be a gift code.
 * Case, spaces and dashes are ignored, like the server's `redeem_gift`.
 */
fun normalizeGiftCode(input: String): String? {
    val raw = input.uppercase().filterNot { it.isWhitespace() || it == '-' }
    if (raw.length != GiftContract.CODE_LENGTH || !raw.all { it in INVITE_ALPHABET }) return null
    return formatGiftCode(raw)
}

/** "ABCDEFGHJKMN" → "ABCD-EFGH-JKMN"; anything else unchanged. */
fun formatGiftCode(code: String): String {
    val raw = code.uppercase().filterNot { it == '-' || it.isWhitespace() }
    return if (raw.length == GiftContract.CODE_LENGTH) "${raw.take(4)}-${raw.substring(4, 8)}-${raw.drop(8)}" else code
}

/** Finds a gift code in pasted text, such as the whole WhatsApp message. */
fun extractGiftCode(text: String): String? {
    normalizeGiftCode(text)?.let { return it }
    val upper = text.uppercase()
    return DASHED_GIFT_CODE.find(upper)?.value?.let(::normalizeGiftCode)
}

private val DASHED_GIFT_CODE = Regex(
    "(?<![A-Z0-9-])[$INVITE_ALPHABET]{4}[-\\s]?[$INVITE_ALPHABET]{4}[-\\s]?[$INVITE_ALPHABET]{4}(?![A-Z0-9-])",
)

// ---- my_gifts -----------------------------------------------------------------------------------------

enum class GiftStatus { UNUSED, REDEEMED, VOIDED }

data class Gift(
    val code: String,
    val product: GiftProduct?,
    val productId: String,
    val days: Int,
    val status: GiftStatus,
    val createdAt: Instant?,
    val redeemedAt: Instant?,
)

@Serializable
private data class GiftDto(
    val code: String = "",
    @SerialName("product_id") val productId: String = "",
    val days: Int = 0,
    val status: String = "unused",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("redeemed_at") val redeemedAt: String? = null,
)

private val GiftJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

// ---- redeem_gift --------------------------------------------------------------------------------------

/** Typed answer of `redeem_gift`. */
sealed interface RedeemOutcome {
    data class Redeemed(val proUntil: Instant?, val days: Int) : RedeemOutcome
    data object InvalidCode : RedeemOutcome
    data object AlreadyRedeemed : RedeemOutcome
    data object Voided : RedeemOutcome
    data object OwnGift : RedeemOutcome

    /** 10 failed tries in the last hour. */
    data object RateLimited : RedeemOutcome

    /** Already Pro without an end date: the code is kept for someone else. */
    data object LifetimePro : RedeemOutcome
    data object Offline : RedeemOutcome
    data object NotConfigured : RedeemOutcome

    /** [message] is technical English. */
    data class Failed(val message: String?) : RedeemOutcome
}

object GiftParser {
    fun gifts(element: JsonElement): List<Gift> {
        if (element is JsonNull) return emptyList()
        return GiftJson.decodeFromJsonElement(ListSerializer(GiftDto.serializer()), element)
            .filter { it.code.isNotBlank() }
            .map { dto ->
                Gift(
                    code = formatGiftCode(dto.code),
                    product = GiftProduct.fromProductId(dto.productId),
                    productId = dto.productId,
                    days = dto.days,
                    status = when (dto.status) {
                        "redeemed" -> GiftStatus.REDEEMED
                        "voided" -> GiftStatus.VOIDED
                        else -> GiftStatus.UNUSED
                    },
                    createdAt = dto.createdAt?.let(::instantOrNull),
                    redeemedAt = dto.redeemedAt?.let(::instantOrNull),
                )
            }
    }

    fun redeem(element: JsonElement): RedeemOutcome {
        val obj = element as? JsonObject ?: return RedeemOutcome.Failed("redeem_gift: not an object")
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        if (error != null) return redeemError(error)
        val until = (obj["pro_until"] as? JsonPrimitive)?.contentOrNull?.let(::instantOrNull)
        val days = (obj["days"] as? JsonPrimitive)?.intOrNull ?: 0
        return RedeemOutcome.Redeemed(until, days)
    }

    /** The `{error}` codes, and the Postgres errors of an older server that raised instead (P0002, 23505, 22023). */
    fun redeemError(code: String?, sqlState: String? = null): RedeemOutcome = when (code) {
        "invalid_code" -> RedeemOutcome.InvalidCode
        "already_redeemed" -> RedeemOutcome.AlreadyRedeemed
        "voided" -> RedeemOutcome.Voided
        "own_gift" -> RedeemOutcome.OwnGift
        "rate_limited" -> RedeemOutcome.RateLimited
        "lifetime_pro" -> RedeemOutcome.LifetimePro
        else -> when (sqlState) {
            "P0002" -> RedeemOutcome.InvalidCode
            "23505" -> RedeemOutcome.AlreadyRedeemed
            "P0001" -> RedeemOutcome.RateLimited
            else -> RedeemOutcome.Failed("redeem_gift: ${code ?: sqlState}")
        }
    }

    /** The newest unused code that was not in [before] (the list before the purchase). */
    fun newCode(before: Set<String>, after: List<Gift>, product: GiftProduct?): Gift? =
        after.firstOrNull { it.code !in before && (product == null || it.product == product) && it.status == GiftStatus.UNUSED }

    private fun instantOrNull(text: String): Instant? = try {
        Instant.parse(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
