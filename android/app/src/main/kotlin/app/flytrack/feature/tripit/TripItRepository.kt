package app.flytrack.feature.tripit

import app.flytrack.data.remote.BackendApi
import app.flytrack.feature.friends.RemoteResult
import app.flytrack.feature.friends.Wave2Caller
import javax.inject.Inject

/** TripIt connection and sync. Never throws (cancellation aside). */
interface TripItRepository {
    suspend fun status(): TripItResult<TripItStatus>

    /** The TripIt page to open in a browser / Custom Tab. */
    suspend fun connect(): TripItResult<String>

    /** `tripit-complete` with the code from `heygate://tripit/callback?code=...`, as the signed-in user. */
    suspend fun complete(code: String): TripItResult<Unit>
    suspend fun sync(): TripItResult<TripItSyncSummary>
    suspend fun disconnect(): TripItResult<Unit>
}

class SupabaseTripItRepository @Inject constructor(
    private val caller: Wave2Caller,
    private val backend: BackendApi,
) : TripItRepository {

    override suspend fun status(): TripItResult<TripItStatus> =
        caller.call { api, _ -> TripItParser.status(api.rpc(TripItContract.RPC_STATUS)) }.toTripIt()

    override suspend fun connect(): TripItResult<String> =
        caller.call { _, _ -> TripItParser.authorizeUrl(backend.invokeFunction(TripItContract.FUNCTION_CONNECT, "{}")) }.toTripIt()

    override suspend fun complete(code: String): TripItResult<Unit> =
        caller.call { _, _ -> backend.invokeFunction(TripItContract.FUNCTION_COMPLETE, TripItParser.completeBody(code)); Unit }.toTripIt()

    override suspend fun sync(): TripItResult<TripItSyncSummary> =
        caller.call { _, _ -> TripItParser.syncSummary(backend.invokeFunction(TripItContract.FUNCTION_SYNC, "{}")) }.toTripIt()

    override suspend fun disconnect(): TripItResult<Unit> =
        caller.call { _, _ -> backend.invokeFunction(TripItContract.FUNCTION_DISCONNECT, "{}"); Unit }.toTripIt()
}

internal fun <T> RemoteResult<T>.toTripIt(): TripItResult<T> = when (this) {
    is RemoteResult.Ok -> TripItResult.Ok(value)
    RemoteResult.Offline -> TripItResult.Offline
    RemoteResult.NotConfigured -> TripItResult.BackendNotConfigured
    is RemoteResult.Refused -> TripItParser.refused(code, message)
    is RemoteResult.Failed -> TripItResult.Failed(message)
}
