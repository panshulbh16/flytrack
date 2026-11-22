package app.flytrack.push

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.flytrack.core.result.ActionResult
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.DeviceRepository
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.SessionState
import app.flytrack.data.repo.SettingsStore
import app.flytrack.di.ApplicationScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Firebase is absent when the build has no google-services.json. Callers must check [isConfigured]. */
@Singleton
class FirebaseTokenSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isConfigured(): Boolean = try {
        FirebaseApp.getApps(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** Null when Firebase is not configured or the token cannot be fetched. Never throws. */
    suspend fun token(): String? {
        if (!isConfigured()) return null
        return try {
            FirebaseMessaging.getInstance().token.await()
        } catch (_: Exception) {
            null
        }
    }
}

/** What the settings screen shows when push cannot be set up on this build. */
sealed interface PushAvailability {
    data object Checking : PushAvailability

    /** No Firebase app. The rest of the app works; alerts will not arrive. */
    data object NotConfigured : PushAvailability

    data object Ready : PushAvailability
}

/** Schedules the Room write for a data-only sync so the process can die after [enqueue] returns. */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(flightId: String, flightJson: String?) {
        val data = Data.Builder().putString(FlightSyncWorker.KEY_FLIGHT_ID, flightId)
        syncPayload(flightJson)?.let { data.putString(FlightSyncWorker.KEY_FLIGHT, it) }
        val request = OneTimeWorkRequestBuilder<FlightSyncWorker>()
            .setInputData(data.build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "flight-sync:$flightId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

@Singleton
class FirebasePushInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokens: FirebaseTokenSource,
    private val devices: DeviceRepository,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val facts: DeviceFactsProvider,
    @ApplicationScope private val scope: CoroutineScope,
) : PushInitializer {

    private val _availability = MutableStateFlow<PushAvailability>(PushAvailability.Checking)
    override val availability: StateFlow<PushAvailability> = _availability

    private val registrar by lazy { PushRegistrar(devices, settings, facts.current(), tokens::token) }

    override fun onAppStart() {
        // Channels are created even without Firebase, so a later config drop-in has somewhere to post.
        NotificationChannels.create(context)
        if (!tokens.isConfigured()) {
            _availability.value = PushAvailability.NotConfigured
            return
        }
        _availability.value = PushAvailability.Ready
        scope.launch {
            auth.session
                .map { (it as? SessionState.SignedIn)?.userId }
                .distinctUntilChanged()
                .collect { userId ->
                    if (userId != null) registrar.syncForUser(userId)
                }
        }
    }

    override suspend fun beforeSignOut(): ActionResult? {
        if (!tokens.isConfigured()) return null
        return registrar.beforeSignOut()
    }
}

/** The token [FirebaseMessagingService.onNewToken] just minted, registered for the signed-in user. */
internal suspend fun registerNewToken(
    token: String,
    auth: AuthRepository,
    devices: DeviceRepository,
    facts: DeviceFacts,
) {
    if (auth.currentUserId == null) {
        // No session yet (first launch). The initializer registers once sign-in finishes.
        return
    }
    devices.registerDevice(token, facts.appVersion, facts.osVersion, facts.locale, facts.timezone)
}
