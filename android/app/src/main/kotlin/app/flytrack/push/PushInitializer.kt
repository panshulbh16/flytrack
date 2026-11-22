package app.flytrack.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything push needs at process start: notification channels (`flight_critical`,
 * `flight_updates`, same ids as the backend) and registering the FCM token.
 * Called once from FlyTrackApp.onCreate on the main thread, so it must return quickly.
 */
interface PushInitializer {
    fun onAppStart()
}

/** STUB (foundation): replaced by the push feature, which owns this package and this binding. */
class NoOpPushInitializer @Inject constructor() : PushInitializer {
    override fun onAppStart() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PushModule {
    @Binds
    @Singleton
    abstract fun bindPushInitializer(impl: NoOpPushInitializer): PushInitializer
}
