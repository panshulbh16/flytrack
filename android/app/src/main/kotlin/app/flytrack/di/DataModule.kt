package app.flytrack.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.flytrack.data.local.AirportDao
import app.flytrack.data.local.FlightDao
import app.flytrack.data.local.FlyTrackDatabase
import app.flytrack.data.local.GmailDao
import app.flytrack.data.local.ProfileDao
import app.flytrack.data.local.RoomTransactionRunner
import app.flytrack.data.local.TrackedFlightDao
import app.flytrack.data.local.TransactionRunner
import app.flytrack.data.remote.AuthGateway
import app.flytrack.data.remote.BackendApi
import app.flytrack.data.remote.BackendConfig
import app.flytrack.data.remote.SupabaseAuthGateway
import app.flytrack.data.remote.SupabaseBackendApi
import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.repo.AirportRepository
import app.flytrack.data.repo.AirportRepositoryImpl
import app.flytrack.data.repo.AuthRepository
import app.flytrack.data.repo.AuthRepositoryImpl
import app.flytrack.data.repo.DataStoreSettingsStore
import app.flytrack.data.repo.DeviceRepository
import app.flytrack.data.repo.DeviceRepositoryImpl
import app.flytrack.data.repo.FlightRepository
import app.flytrack.data.repo.FlightRepositoryImpl
import app.flytrack.data.repo.LocalUserData
import app.flytrack.data.repo.ProfileRepository
import app.flytrack.data.repo.ProfileRepositoryImpl
import app.flytrack.data.repo.RoomLocalUserData
import app.flytrack.data.repo.SettingsStore
import app.flytrack.feature.gmailimport.GmailSuggestionStore
import app.flytrack.feature.gmailimport.RoomGmailSuggestionStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FlyTrackDatabase =
        // No destructive fallback: the Gmail tables are local-only state (dismissed suggestions, seen
        // mail) that no refresh rebuilds, so a schema change without a migration must fail loudly.
        Room.databaseBuilder(context, FlyTrackDatabase::class.java, FlyTrackDatabase.NAME).build()

    @Provides fun provideFlightDao(db: FlyTrackDatabase): FlightDao = db.flightDao()

    @Provides fun provideTrackedFlightDao(db: FlyTrackDatabase): TrackedFlightDao = db.trackedFlightDao()

    @Provides fun provideAirportDao(db: FlyTrackDatabase): AirportDao = db.airportDao()

    @Provides fun provideProfileDao(db: FlyTrackDatabase): ProfileDao = db.profileDao()

    @Provides fun provideGmailDao(db: FlyTrackDatabase): GmailDao = db.gmailDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(io + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("settings") },
    )
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideSupabaseClientProvider(config: BackendConfig, json: Json): SupabaseClientProvider =
        SupabaseClientProvider(config, json)

    @Provides
    @Singleton
    fun provideBackendApi(provider: SupabaseClientProvider, json: Json): BackendApi = SupabaseBackendApi(provider, json)

    @Provides
    @Singleton
    fun provideAuthGateway(provider: SupabaseClientProvider): AuthGateway = SupabaseAuthGateway(provider)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds @Singleton abstract fun bindFlightRepository(impl: FlightRepositoryImpl): FlightRepository

    @Binds @Singleton abstract fun bindDeviceRepository(impl: DeviceRepositoryImpl): DeviceRepository

    @Binds @Singleton abstract fun bindAirportRepository(impl: AirportRepositoryImpl): AirportRepository

    @Binds @Singleton abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds @Singleton abstract fun bindSettingsStore(impl: DataStoreSettingsStore): SettingsStore

    @Binds abstract fun bindLocalUserData(impl: RoomLocalUserData): LocalUserData

    @Binds abstract fun bindGmailSuggestionStore(impl: RoomGmailSuggestionStore): GmailSuggestionStore

    @Binds abstract fun bindTransactionRunner(impl: RoomTransactionRunner): TransactionRunner
}
