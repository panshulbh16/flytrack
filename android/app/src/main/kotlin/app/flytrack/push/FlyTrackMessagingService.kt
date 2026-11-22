package app.flytrack.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Receives FCM. Alert messages are shown by Play services when the app is in the background;
 * in the foreground this service posts the same channel and tag itself. Sync messages are
 * data-only and are written to Room by [FlightSyncWorker].
 */
@AndroidEntryPoint
class FlyTrackMessagingService : FirebaseMessagingService() {

    @Inject lateinit var scheduler: WorkManagerSyncScheduler

    @Inject lateinit var notifier: SystemFlightNotifier

    @Inject lateinit var devices: app.flytrack.data.repo.DeviceRepository

    @Inject lateinit var auth: app.flytrack.data.repo.AuthRepository

    @Inject lateinit var facts: DeviceFactsProvider

    override fun onMessageReceived(message: RemoteMessage) {
        when (val incoming = parseIncomingPush(message.data)) {
            is IncomingPush.Sync -> scheduler.enqueue(incoming.flightId, incoming.flightJson)
            is IncomingPush.Alert -> notifier.show(incoming)
            IncomingPush.Ignored -> Unit
        }
    }

    override fun onNewToken(token: String) {
        // Called on a background thread. Block it until the RPC finishes so the process is not
        // killed between "token rotated" and "the server knows the new token".
        runBlocking {
            registerNewToken(token, auth, devices, facts.current())
        }
    }
}
