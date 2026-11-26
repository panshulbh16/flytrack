package app.flytrack.feature.family

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

// P3 B13 family sharing: the models of `my_family()` and the typed errors of the family RPCs
// (supabase/migrations/20261126000029_family.sql). Pure Kotlin, JVM-tested.

enum class FamilyRole { OWNER, MEMBER }

data class FamilyMember(val userId: String, val displayName: String?, val joinedAt: Instant?)

/** The owner's open invite (8 characters, like friend codes). */
data class FamilyInvite(val code: String, val expiresAt: Instant?)

/** `my_family()`: the family the caller owns or belongs to. */
data class FamilyInfo(
    val familyId: String,
    val role: FamilyRole,
    val ownerId: String,
    val ownerName: String?,
    /** The owner has Pro of their own (the family's Pro is active). */
    val ownerPro: Boolean,
    /** The caller is Pro only through this family: show "Pro via {owner}'s family". */
    val proViaFamily: Boolean,
    /** Everyone except the owner, oldest first. */
    val members: List<FamilyMember>,
    val maxMembers: Int,
    /** The owner can hand out an invite now (yearly or lifetime Pro, a free seat). */
    val canInvite: Boolean,
    /** Owner only. */
    val invite: FamilyInvite?,
) {
    val isOwner: Boolean get() = role == FamilyRole.OWNER
    val seatsLeft: Int get() = (maxMembers - members.size).coerceAtLeast(0)
}

/** Why a family RPC refused (SQLSTATE plus the raised message, as in the migration's header). */
enum class FamilyError {
    /** `family_requires_yearly` (42501): only a yearly or lifetime Pro owner can create a family. */
    REQUIRES_YEARLY,

    /** `already_in_family` (23505). */
    ALREADY_IN_FAMILY,

    /** `invalid_code` (P0002): unknown, used or expired. */
    INVALID_CODE,

    /** `own_code` (22023). */
    OWN_CODE,

    /** `family_full` (P0001): five members besides the owner. */
    FULL,

    /** `family_inactive` (P0001): the owner's Pro has ended. */
    INACTIVE,

    /** `not_member` (P0002). */
    NOT_MEMBER,

    /** `not_owner` (42501). */
    NOT_OWNER,
}

/** Maps a refusal to a [FamilyError]; the raised message decides first, the SQLSTATE only where it is unique. */
fun familyError(code: String?, message: String?): FamilyError? {
    val text = message.orEmpty()
    return when {
        "family_requires_yearly" in text -> FamilyError.REQUIRES_YEARLY
        "already_in_family" in text -> FamilyError.ALREADY_IN_FAMILY
        "invalid_code" in text -> FamilyError.INVALID_CODE
        "own_code" in text -> FamilyError.OWN_CODE
        "family_full" in text -> FamilyError.FULL
        "family_inactive" in text -> FamilyError.INACTIVE
        "not_member" in text -> FamilyError.NOT_MEMBER
        "not_owner" in text -> FamilyError.NOT_OWNER
        code == "23505" -> FamilyError.ALREADY_IN_FAMILY
        code == "22023" -> FamilyError.OWN_CODE
        else -> null
    }
}

/** `accept_family_invite` succeeded. */
data class JoinedFamily(val familyId: String, val ownerId: String, val ownerName: String?)

// ---- wire format -------------------------------------------------------------------------------------

private val FamilyJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class FamilyDto(
    @SerialName("family_id") val familyId: String,
    val role: String? = null,
    val owner: OwnerDto? = null,
    @SerialName("owner_pro") val ownerPro: Boolean = false,
    @SerialName("pro_via_family") val proViaFamily: Boolean = false,
    val members: List<MemberDto>? = null,
    @SerialName("max_members") val maxMembers: Int = DEFAULT_MAX_MEMBERS,
    @SerialName("can_invite") val canInvite: Boolean = false,
    val invite: InviteDto? = null,
)

@Serializable
private data class OwnerDto(@SerialName("user_id") val userId: String, @SerialName("display_name") val displayName: String? = null)

@Serializable
private data class MemberDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
)

@Serializable
private data class InviteDto(val code: String, @SerialName("expires_at") val expiresAt: String? = null)

@Serializable
private data class JoinedDto(
    @SerialName("family_id") val familyId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("owner_name") val ownerName: String? = null,
)

const val DEFAULT_MAX_MEMBERS = 5

private fun instantOrNull(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun String?.nameOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** `my_family()`: null when the caller is in no family. Throws SerializationException on a wrong shape. */
fun parseFamily(json: JsonElement): FamilyInfo? {
    if (json is JsonNull) return null
    val dto = FamilyJson.decodeFromJsonElement(FamilyDto.serializer(), json)
    val owner = dto.owner ?: throw kotlinx.serialization.SerializationException("my_family without owner")
    return FamilyInfo(
        familyId = dto.familyId,
        role = if (dto.role == "owner") FamilyRole.OWNER else FamilyRole.MEMBER,
        ownerId = owner.userId,
        ownerName = owner.displayName.nameOrNull(),
        ownerPro = dto.ownerPro,
        proViaFamily = dto.proViaFamily,
        members = dto.members.orEmpty().map { FamilyMember(it.userId, it.displayName.nameOrNull(), instantOrNull(it.joinedAt)) },
        maxMembers = dto.maxMembers,
        canInvite = dto.canInvite,
        invite = dto.invite?.let { FamilyInvite(it.code, instantOrNull(it.expiresAt)) },
    )
}

/** `accept_family_invite`. */
fun parseJoinedFamily(json: JsonElement): JoinedFamily {
    val dto = FamilyJson.decodeFromJsonElement(JoinedDto.serializer(), json)
    return JoinedFamily(dto.familyId, dto.ownerId, dto.ownerName.nameOrNull())
}

/** `create_family_invite()` answers a bare JSON string. */
fun parseInviteCode(json: JsonElement): String? = (json as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
