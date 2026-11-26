package app.flytrack.feature.family

import app.flytrack.data.repo.AuthRepository
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import app.flytrack.feature.friends.normalizeInviteCode
import app.flytrack.feature.social.FamilyGrant
import app.flytrack.feature.social.SocialStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a family command: done, a typed refusal, or a transport problem. */
sealed interface FamilyResult<out T> {
    data class Ok<T>(val value: T) : FamilyResult<T>
    data class Refused(val error: FamilyError) : FamilyResult<Nothing>
    data object Offline : FamilyResult<Nothing>
    data object NotConfigured : FamilyResult<Nothing>

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : FamilyResult<Nothing>
}

/** Family sharing RPCs (P3 B13). Every call is typed and never throws. */
interface FamilyRepository {
    /** `my_family()`: null when the user is in no family. Also refreshes the cached family Pro ([FamilyGrant]). */
    suspend fun myFamily(): FamilyResult<FamilyInfo?>

    /** `create_family_invite()`: the owner's open code, or a new one (creates the family on first use). */
    suspend fun createInvite(): FamilyResult<String>

    /** `accept_family_invite(p_code)`. Text that cannot be a code is [FamilyError.INVALID_CODE] without a call. */
    suspend fun acceptInvite(code: String): FamilyResult<JoinedFamily>

    /** `leave_family()`: a member leaves; the owner leaving ends the family for everyone. */
    suspend fun leave(): FamilyResult<Unit>

    /** `remove_family_member(p_user)`: owner only. */
    suspend fun removeMember(userId: String): FamilyResult<Unit>
}

@Singleton
class SupabaseFamilyRepository @Inject constructor(
    private val caller: Wave2Caller,
    private val auth: AuthRepository,
    private val store: SocialStore,
) : FamilyRepository {

    override suspend fun myFamily(): FamilyResult<FamilyInfo?> {
        val result = caller.call { api, _ -> parseFamily(api.rpc(RPC_MY_FAMILY)) }.toFamilyResult()
        if (result is FamilyResult.Ok) syncGrant(result.value)
        return result
    }

    override suspend fun createInvite(): FamilyResult<String> = caller.call { api, _ ->
        parseInviteCode(api.rpc(RPC_CREATE_INVITE)) ?: throw IllegalArgumentException("empty family invite code")
    }.toFamilyResult()

    override suspend fun acceptInvite(code: String): FamilyResult<JoinedFamily> {
        val normalized = normalizeInviteCode(code) ?: return FamilyResult.Refused(FamilyError.INVALID_CODE)
        val result = caller.call { api, _ -> parseJoinedFamily(api.rpc(RPC_ACCEPT_INVITE, buildJsonObject { put("p_code", normalized) })) }
            .toFamilyResult()
        // Joining makes the user Pro through the owner: read the family once so the whole app knows at once.
        if (result is FamilyResult.Ok) myFamily()
        return result
    }

    override suspend fun leave(): FamilyResult<Unit> {
        val result = caller.call { api, _ ->
            api.rpc(RPC_LEAVE)
            Unit
        }.toFamilyResult()
        if (result is FamilyResult.Ok) store.setFamilyGrant(null)
        return result
    }

    override suspend fun removeMember(userId: String): FamilyResult<Unit> = caller.call { api, _ ->
        api.rpc(RPC_REMOVE_MEMBER, buildJsonObject { put("p_user", userId) })
        Unit
    }.toFamilyResult()

    private suspend fun syncGrant(family: FamilyInfo?) {
        val userId = auth.currentUserId ?: return
        store.setFamilyGrant(if (family != null && family.proViaFamily) FamilyGrant(userId, family.ownerName) else null)
    }

    internal companion object {
        const val RPC_MY_FAMILY = "my_family"
        const val RPC_CREATE_INVITE = "create_family_invite"
        const val RPC_ACCEPT_INVITE = "accept_family_invite"
        const val RPC_LEAVE = "leave_family"
        const val RPC_REMOVE_MEMBER = "remove_family_member"
    }
}

internal fun <T> RemoteResult<T>.toFamilyResult(): FamilyResult<T> = when (this) {
    is RemoteResult.Ok -> FamilyResult.Ok(value)
    RemoteResult.Offline -> FamilyResult.Offline
    RemoteResult.NotConfigured -> FamilyResult.NotConfigured
    is RemoteResult.Failed -> FamilyResult.Failed(message)
    is RemoteResult.Refused -> familyError(code, message)?.let { FamilyResult.Refused(it) } ?: FamilyResult.Failed(message ?: code)
}
