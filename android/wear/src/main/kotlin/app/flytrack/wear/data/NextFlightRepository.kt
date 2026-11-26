package app.flytrack.wear.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Reads the phone's `/next_flight` item from the local Data Layer store (no Bluetooth round trip). */
class NextFlightRepository(context: Context) {
    private val dataClient = Wearable.getDataClient(context.applicationContext)

    /** The newest decodable payload, or null when the phone wrote none (or it was deleted). Never throws. */
    suspend fun load(): NextFlightPayload? = try {
        withTimeoutOrNull(TIMEOUT) {
            // No host: the item of whichever node wrote it (normally the one paired phone).
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(NextFlightPayload.PATH).build()
            val items = dataClient.getDataItems(uri).await()
            try {
                items.asSequence()
                    .filter { it.uri.path == NextFlightPayload.PATH }
                    .mapNotNull { NextFlightPayload.decode(it.data) }
                    .firstOrNull()
            } finally {
                items.release()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "next flight read failed: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "FlyTrackWear"
        val TIMEOUT = 5.seconds
    }
}
