package app.flytrack.push

import app.flytrack.core.result.ActionResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Singleton

/**
 * Everything push needs at process start: notification channels (`flight_critical`,
 * `flight_updates`, same ids as the backend) and registering the FCM token.
 * Called once from FlyTrackApp.onCreate on the main thread, so [onAppStart] must return quickly.
 */
interface PushInitializer {
    val availability: StateFlow<PushAvailability>

    fun onAppStart()

    /**
     * Unregisters this phone's FCM token. Call it before [app.flytrack.data.repo.AuthRepository.signOut],
     * while that user's session can still authorise the RPC.
     */
    suspend fun beforeSignOut(): ActionResult?
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    @Singleton
    abstract fun bindPushInitializer(impl: FirebasePushInitializer): PushInitializer

    @Binds
    @Singleton
    abstract fun bindNotificationHealth(impl: AndroidNotificationHealthSource): NotificationHealthSource
}
