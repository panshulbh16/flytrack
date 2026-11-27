package app.flytrack.feature.tripit

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Bindings of TripIt sync (P4 section 2). */
@Module
@InstallIn(SingletonComponent::class)
abstract class TripItModule {
    @Binds
    abstract fun bindTripItRepository(impl: SupabaseTripItRepository): TripItRepository
}
