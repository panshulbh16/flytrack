package app.flytrack.feature.friends

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Result of `accept_friend_invite` (P2 wave 2, section 1). */
sealed interface AcceptInviteResult {
    data class Accepted(val friend: AcceptedFriend) : AcceptInviteResult

    /** Unknown, used or expired code (`invalid_code`, P0002), or text that cannot be a code. */
    data object InvalidCode : AcceptInviteResult

    /** The user typed their own code (`own_code`, 22023). */
    data object OwnCode : AcceptInviteResult

    /** `already_friends` (23505). */
    data object AlreadyFriends : AcceptInviteResult

    /** One of the two already has 50 friends (`friend_limit`, P0001). */
    data object FriendLimit : AcceptInviteResult
    data object Offline : AcceptInviteResult
    data object NotConfigured : AcceptInviteResult

    /** [message] is technical English: log it, do not show it. */
    data class Failed(val message: String?) : AcceptInviteResult
}

/** Friends RPCs. Every call is typed and never throws. */
interface FriendsRepository {
    /** `create_friend_invite()`: the user's open code (reused for 7 days) or a new one. */
    suspend fun createInvite(): RemoteResult<String>

    /** `accept_friend_invite(p_code)`. [code] is normalised first; text that cannot be a code is [AcceptInviteResult.InvalidCode]. */
    suspend fun acceptInvite(code: String): AcceptInviteResult

    /** `my_friends()`. */
    suspend fun friends(): RemoteResult<List<Friend>>

    /** `remove_friend(p_friend)`: ends the friendship both ways. */
    suspend fun removeFriend(friendId: String): RemoteResult<Unit>

    /** `set_friend_alerts(p_friend, p_enabled)`: the alerts I get for that friend's flights. */
    suspend fun setFriendAlerts(friendId: String, enabled: Boolean): RemoteResult<Unit>

    /** `friends_flights()`. */
    suspend fun friendsFlights(): RemoteResult<List<FriendFlight>>

    /** `user_flights.share_with_friends` of the user's own flight; Ok(null) when the user does not track it. */
    suspend fun shareWithFriends(flightId: String): RemoteResult<Boolean?>

    /** Sets `share_with_friends`; Ok(null) when the user does not track the flight. */
    suspend fun setShareWithFriends(flightId: String, share: Boolean): RemoteResult<Boolean?>
}

class SupabaseFriendsRepository @Inject constructor(
    private val caller: Wave2Caller,
) : FriendsRepository {

    override suspend fun createInvite(): RemoteResult<String> = caller.call { api, _ ->
        textAnswer(api.rpc(RPC_CREATE_INVITE)) ?: throw IllegalArgumentException("empty invite code")
    }

    override suspend fun acceptInvite(code: String): AcceptInviteResult {
        val normalized = normalizeInviteCode(code) ?: return AcceptInviteResult.InvalidCode
        val result = caller.call { api, _ ->
            parseAcceptedFriend(api.rpc(RPC_ACCEPT_INVITE, buildJsonObject { put("p_code", normalized) }))
        }
        return when (result) {
            is RemoteResult.Ok -> AcceptInviteResult.Accepted(result.value)
            RemoteResult.Offline -> AcceptInviteResult.Offline
            RemoteResult.NotConfigured -> AcceptInviteResult.NotConfigured
            is RemoteResult.Failed -> AcceptInviteResult.Failed(result.message)
            is RemoteResult.Refused -> acceptError(result.code, result.message)
        }
    }

    override suspend fun friends(): RemoteResult<List<Friend>> = caller.call { api, _ -> parseFriends(api.rpc(RPC_MY_FRIENDS)) }

    override suspend fun removeFriend(friendId: String): RemoteResult<Unit> = caller.call { api, _ ->
        api.rpc(RPC_REMOVE_FRIEND, buildJsonObject { put("p_friend", friendId) })
        Unit
    }

    override suspend fun setFriendAlerts(friendId: String, enabled: Boolean): RemoteResult<Unit> = caller.call { api, _ ->
        api.rpc(
            RPC_SET_FRIEND_ALERTS,
            buildJsonObject {
                put("p_friend", friendId)
                put("p_enabled", enabled)
            },
        )
        Unit
    }

    override suspend fun friendsFlights(): RemoteResult<List<FriendFlight>> =
        caller.call { api, _ -> parseFriendsFlights(api.rpc(RPC_FRIENDS_FLIGHTS)) }

    override suspend fun shareWithFriends(flightId: String): RemoteResult<Boolean?> =
        caller.call { api, userId -> api.shareWithFriends(userId, flightId) }

    override suspend fun setShareWithFriends(flightId: String, share: Boolean): RemoteResult<Boolean?> =
        caller.call { api, userId -> api.setShareWithFriends(userId, flightId, share) }

    internal companion object {
        const val RPC_CREATE_INVITE = "create_friend_invite"
        const val RPC_ACCEPT_INVITE = "accept_friend_invite"
        const val RPC_MY_FRIENDS = "my_friends"
        const val RPC_REMOVE_FRIEND = "remove_friend"
        const val RPC_SET_FRIEND_ALERTS = "set_friend_alerts"
        const val RPC_FRIENDS_FLIGHTS = "friends_flights"
    }
}

/** Maps an `accept_friend_invite` error (contract codes, with the raised message as a second clue). */
internal fun acceptError(code: String?, message: String?): AcceptInviteResult {
    val text = message.orEmpty()
    return when {
        code == "P0002" || "invalid_code" in text -> AcceptInviteResult.InvalidCode
        code == "22023" || "own_code" in text -> AcceptInviteResult.OwnCode
        code == "23505" || "already_friends" in text -> AcceptInviteResult.AlreadyFriends
        // P0001 is also any plain `raise exception`: only the contract's message (or none) means the limit.
        "friend_limit" in text || (code == "P0001" && text.isBlank()) -> AcceptInviteResult.FriendLimit
        else -> AcceptInviteResult.Failed(message ?: code)
    }
}
