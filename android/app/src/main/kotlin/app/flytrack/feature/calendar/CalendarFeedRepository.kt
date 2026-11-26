package app.flytrack.feature.calendar

import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import app.flytrack.feature.friends.textAnswer
import javax.inject.Inject

/** The user's calendar feed (P2 wave 2, section 3). */
interface CalendarFeedRepository {
    /** `my_calendar_url()`: the https feed URL, created on the first call. Never throws. */
    suspend fun feedUrl(): RemoteResult<String>

    /** `rotate_calendar_token()`: a new URL; the old one stops working at once. Never throws. */
    suspend fun rotate(): RemoteResult<String>
}

class SupabaseCalendarFeedRepository @Inject constructor(
    private val caller: Wave2Caller,
) : CalendarFeedRepository {
    override suspend fun feedUrl(): RemoteResult<String> = url(RPC_MY_CALENDAR_URL)

    override suspend fun rotate(): RemoteResult<String> = url(RPC_ROTATE_CALENDAR_TOKEN)

    private suspend fun url(rpc: String): RemoteResult<String> = caller.call { api, _ ->
        textAnswer(api.rpc(rpc))?.takeIf(::isFeedUrl) ?: throw IllegalArgumentException("not a feed url")
    }

    private companion object {
        const val RPC_MY_CALENDAR_URL = "my_calendar_url"
        const val RPC_ROTATE_CALENDAR_TOKEN = "rotate_calendar_token"
    }
}
