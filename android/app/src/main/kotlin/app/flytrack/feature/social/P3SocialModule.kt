package app.flytrack.feature.social

import app.flytrack.feature.airports.AirportCatalog
import app.flytrack.feature.airports.AssetAirportCatalog
import app.flytrack.feature.calendarimport.AndroidDeviceCalendar
import app.flytrack.feature.calendarimport.DeviceCalendar
import app.flytrack.feature.family.FamilyRepository
import app.flytrack.feature.family.SupabaseFamilyRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Bindings of the P3 search and social features: calendar import, route pickers, Passport compare, crew mode, family. */
@Module
@InstallIn(SingletonComponent::class)
abstract class P3SocialModule {

    @Binds
    abstract fun bindSocialStore(impl: DataStoreSocialStore): SocialStore

    @Binds
    abstract fun bindProfileFlagsApi(impl: SupabaseProfileFlagsApi): ProfileFlagsApi

    @Binds
    abstract fun bindProfileFlagsRepository(impl: SupabaseProfileFlagsRepository): ProfileFlagsRepository

    @Binds
    abstract fun bindFriendPassportRepository(impl: SupabaseFriendPassportRepository): FriendPassportRepository

    @Binds
    abstract fun bindFamilyRepository(impl: SupabaseFamilyRepository): FamilyRepository

    @Binds
    abstract fun bindAirportCatalog(impl: AssetAirportCatalog): AirportCatalog

    @Binds
    abstract fun bindDeviceCalendar(impl: AndroidDeviceCalendar): DeviceCalendar
}
