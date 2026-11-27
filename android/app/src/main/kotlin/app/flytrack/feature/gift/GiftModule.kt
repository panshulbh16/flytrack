package app.flytrack.feature.gift

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Bindings of Gift Pro (P4 section 3). The store binding is in [GiftStoreModule]. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GiftModule {
    @Binds
    abstract fun bindGiftRepository(impl: SupabaseGiftRepository): GiftRepository
}
