package app.flytrack.feature.friends

import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.remote.SupabaseWave2Api
import app.flytrack.data.remote.Wave2Api
import app.flytrack.feature.calendar.CalendarFeedRepository
import app.flytrack.feature.calendar.SupabaseCalendarFeedRepository
import app.flytrack.feature.forecast.ForecastRepository
import app.flytrack.feature.forecast.SupabaseForecastRepository
import app.flytrack.feature.forwarding.ForwardingRepository
import app.flytrack.feature.forwarding.SupabaseForwardingRepository
import app.flytrack.feature.leave.DataStoreTravelTimeStore
import app.flytrack.feature.leave.LeaveReminderScheduler
import app.flytrack.feature.leave.TravelTimeStore
import app.flytrack.feature.leave.WorkManagerLeaveReminderScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Bindings of the P2 wave 2 Android features: friends, email forwarding, calendar, forecast, when to leave. */
@Module
@InstallIn(SingletonComponent::class)
abstract class Wave2Module {

    @Binds
    abstract fun bindFriendsRepository(impl: SupabaseFriendsRepository): FriendsRepository

    @Binds
    abstract fun bindForwardingRepository(impl: SupabaseForwardingRepository): ForwardingRepository

    @Binds
    abstract fun bindCalendarFeedRepository(impl: SupabaseCalendarFeedRepository): CalendarFeedRepository

    @Binds
    abstract fun bindForecastRepository(impl: SupabaseForecastRepository): ForecastRepository

    @Binds
    abstract fun bindTravelTimeStore(impl: DataStoreTravelTimeStore): TravelTimeStore

    @Binds
    abstract fun bindLeaveReminderScheduler(impl: WorkManagerLeaveReminderScheduler): LeaveReminderScheduler

    companion object {
        @Provides
        @Singleton
        fun provideWave2Api(provider: SupabaseClientProvider, json: Json): Wave2Api = SupabaseWave2Api(provider, json)
    }
}
