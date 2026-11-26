package app.flytrack.feature.forwarding

import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import app.flytrack.feature.friends.textAnswer
import javax.inject.Inject

/** The user's email forwarding address (P2 wave 2, section 2). */
interface ForwardingRepository {
    /** `my_forwarding_address()`: "trips+{token}@{domain}", created on the first call. Never throws. */
    suspend fun address(): RemoteResult<String>
}

class SupabaseForwardingRepository @Inject constructor(
    private val caller: Wave2Caller,
) : ForwardingRepository {
    override suspend fun address(): RemoteResult<String> = caller.call { api, _ ->
        textAnswer(api.rpc(RPC_MY_FORWARDING_ADDRESS))?.takeIf(::isForwardingAddress)
            ?: throw IllegalArgumentException("not a forwarding address")
    }

    private companion object {
        const val RPC_MY_FORWARDING_ADDRESS = "my_forwarding_address"
    }
}

/** A plausible `local@domain` address without spaces; anything else from the server is not shown. */
fun isForwardingAddress(value: String): Boolean = FORWARDING_ADDRESS.matches(value)

private val FORWARDING_ADDRESS = Regex("^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")
