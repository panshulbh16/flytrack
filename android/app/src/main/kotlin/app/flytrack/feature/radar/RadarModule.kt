package app.flytrack.feature.radar

import app.flytrack.data.remote.SupabaseClientProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Bindings of the radar feature (P4 section 1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RadarModule {
    @Binds
    abstract fun bindRadarRepository(impl: DefaultRadarRepository): RadarRepository

    @Binds
    abstract fun bindCoarseLocation(impl: AndroidCoarseLocationProvider): CoarseLocationProvider

    companion object {
        @Provides
        @Singleton
        fun provideRadarApi(provider: SupabaseClientProvider): RadarApi = SupabaseRadarApi(provider)
    }
}
