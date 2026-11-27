package app.flytrack.feature.intel

import app.flytrack.data.remote.SupabaseClientProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Bindings of the P3 flight-intelligence features (timeline, predictions, aircraft, connections, trends). */
@Module
@InstallIn(SingletonComponent::class)
abstract class IntelModule {

    @Binds
    abstract fun bindIntelRepository(impl: SupabaseIntelRepository): IntelRepository

    companion object {
        @Provides
        @Singleton
        fun provideIntelApi(provider: SupabaseClientProvider, json: Json): IntelApi = SupabaseIntelApi(provider, json)
    }
}
