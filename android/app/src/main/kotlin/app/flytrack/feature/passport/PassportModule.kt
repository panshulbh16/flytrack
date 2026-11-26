package app.flytrack.feature.passport

import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.remote.SupabaseTravelApi
import app.flytrack.data.remote.TravelApi
import app.flytrack.feature.airporthealth.AirportHealthRepository
import app.flytrack.feature.airporthealth.SupabaseAirportHealthRepository
import app.flytrack.feature.settings.AlertPrefsRepository
import app.flytrack.feature.settings.DeviceLocale
import app.flytrack.feature.settings.SystemDeviceLocale
import app.flytrack.feature.settings.SupabaseAlertPrefsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Bindings of the P2 passport, airport-health and alert-settings features (one backend door, three repositories). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TravelModule {

    @Binds
    abstract fun bindPassportRepository(impl: SupabasePassportRepository): PassportRepository

    @Binds
    abstract fun bindAirportHealthRepository(impl: SupabaseAirportHealthRepository): AirportHealthRepository

    @Binds
    abstract fun bindAlertPrefsRepository(impl: SupabaseAlertPrefsRepository): AlertPrefsRepository

    @Binds
    abstract fun bindDeviceLocale(impl: SystemDeviceLocale): DeviceLocale

    companion object {
        @Provides
        @Singleton
        fun provideTravelApi(provider: SupabaseClientProvider, json: Json): TravelApi = SupabaseTravelApi(provider, json)
    }
}
