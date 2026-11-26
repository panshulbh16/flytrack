package app.flytrack.feature.friends

import app.flytrack.data.remote.BackendException
import app.flytrack.data.remote.Wave2Api
import app.flytrack.testing.FakeAuthRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** [Wave2Api] for tests: answers per RPC name, records every call. */
class FakeWave2Api : Wave2Api {
    /** RPC name to answer; a [BackendException] value is thrown instead. */
    val answers = mutableMapOf<String, Any>()
    val calls = mutableListOf<Pair<String, JsonObject>>()

    /** user_flights.share_with_friends by flight id; missing = not tracked. */
    val shares = mutableMapOf<String, Boolean>()
    var shareError: BackendException? = null

    override suspend fun rpc(name: String, args: JsonObject): JsonElement {
        calls += name to args
        return when (val answer = answers[name]) {
            null -> JsonNull
            is BackendException -> throw answer
            is JsonElement -> answer
            else -> error("unexpected fake answer $answer")
        }
    }

    override suspend fun shareWithFriends(userId: String, flightId: String): Boolean? {
        shareError?.let { throw it }
        return shares[flightId]
    }

    override suspend fun setShareWithFriends(userId: String, flightId: String, share: Boolean): Boolean? {
        shareError?.let { throw it }
        if (flightId !in shares) return null
        shares[flightId] = share
        return share
    }
}

fun TestScope.caller(api: Wave2Api, auth: FakeAuthRepository = FakeAuthRepository()) =
    Wave2Caller(api, auth, UnconfinedTestDispatcher(testScheduler))
