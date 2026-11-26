package app.flytrack.feature.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import app.flytrack.core.model.TrackedFlightDetails
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * P3 B9: keeps the Wearable Data Layer item `/next_flight` in step with Room. Called by the live
 * surfaces ([app.flytrack.feature.live.LiveCoordinator]) on every Room / sync / tick update; writes
 * only when the watch would draw something different. Never throws.
 */
interface NextFlightPublisher {
    suspend fun publish(tracked: List<TrackedFlightDetails>, now: Instant)
}

/**
 * The two Data Layer writes the publisher needs. [DataLayerWearWriter] talks to Play services;
 * tests use a fake. Either call may throw or hang: [WearNextFlightSync] bounds and contains it.
 */
interface WearDataWriter {
    suspend fun put(bytes: ByteArray)
    suspend fun delete()
}

/** Why a publish did not reach the Data Layer (for the log only). */
enum class WearPublishFailure { TIMEOUT, API_UNAVAILABLE, API_ERROR, OTHER }

/**
 * The publish logic, independent of Play services: select the flight, ask the [WearPublishGate],
 * write with a [timeout]. Never throws except to propagate the caller's own cancellation.
 *
 * Important: a `withTimeout` failure is a `CancellationException`. It must not be rethrown, or it
 * would silently end the caller's coroutine (the live surfaces' Room collector).
 */
class WearNextFlightSync(
    private val writer: WearDataWriter,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val classify: (Exception) -> WearPublishFailure = { WearPublishFailure.OTHER },
    private val onFailure: (WearPublishFailure) -> Unit = {},
) : NextFlightPublisher {

    private val gate = WearPublishGate()
    private val mutex = Mutex()

    /** False after the Wearable API reported it is missing (no Play services for Wear): stop trying until restart. */
    @Volatile var available: Boolean = true
        private set

    override suspend fun publish(tracked: List<TrackedFlightDetails>, now: Instant) {
        if (!available) return
        mutex.withLock {
            try {
                val next = WearNextFlight.select(tracked, now)
                val written = when (val action = gate.decide(next)) {
                    WearPublishAction.Skip -> return
                    is WearPublishAction.Put -> withTimeoutOrNull(timeout) { writer.put(WearNextFlight.encode(action.payload)) }
                    WearPublishAction.Delete -> withTimeoutOrNull(timeout) { writer.delete() }
                }
                if (written == null) {
                    // Timed out: not committed, so the next update tries again.
                    onFailure(WearPublishFailure.TIMEOUT)
                    return
                }
                gate.committed(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = classify(e)
                if (failure == WearPublishFailure.API_UNAVAILABLE) available = false
                onFailure(failure)
            }
        }
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 10.seconds
    }
}

/** Play services' [com.google.android.gms.wearable.DataClient]. */
class DataLayerWearWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : WearDataWriter {

    private val dataClient by lazy { Wearable.getDataClient(context) }

    override suspend fun put(bytes: ByteArray) {
        // Urgent: the Tile and the complication should change with the phone, not minutes later.
        val request = PutDataRequest.create(WearNextFlight.PATH).setData(bytes).setUrgent()
        dataClient.putDataItem(request).await()
    }

    override suspend fun delete() {
        // No host: removes this path whichever node wrote it.
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearNextFlight.PATH).build()
        dataClient.deleteDataItems(uri).await()
    }
}

/** The app's publisher: [WearNextFlightSync] over [DataLayerWearWriter], logging failures. */
@Singleton
class DataLayerNextFlightPublisher @Inject constructor(
    writer: DataLayerWearWriter,
) : NextFlightPublisher by WearNextFlightSync(
    writer = writer,
    classify = { e ->
        when {
            e is ApiException && e.statusCode == ConnectionResult.API_UNAVAILABLE -> WearPublishFailure.API_UNAVAILABLE
            e is ApiException -> WearPublishFailure.API_ERROR
            else -> WearPublishFailure.OTHER
        }
    },
    onFailure = { Log.w(WEAR_LOG_TAG, "wear publish failed: $it") },
)

private const val WEAR_LOG_TAG = "FlyTrackWear"

@Module
@InstallIn(SingletonComponent::class)
abstract class WearModule {
    @Binds
    @Singleton
    abstract fun bindNextFlightPublisher(impl: DataLayerNextFlightPublisher): NextFlightPublisher
}
