package app.flytrack.feature.share

import app.flytrack.BuildConfig
import app.flytrack.data.remote.ShareCheckinApi
import app.flytrack.data.remote.SupabaseClientProvider
import app.flytrack.data.remote.SupabaseShareCheckinApi
import app.flytrack.feature.checkin.CheckinRulesRepository
import app.flytrack.feature.checkin.SupabaseCheckinRulesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/** Bindings of the share and check-in features (one backend door, two repositories). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShareCheckinModule {

    @Binds
    abstract fun bindShareRepository(impl: SupabaseShareRepository): ShareRepository

    @Binds
    abstract fun bindCheckinRulesRepository(impl: SupabaseCheckinRulesRepository): CheckinRulesRepository

    companion object {
        @Provides
        @Singleton
        fun provideShareCheckinApi(provider: SupabaseClientProvider, json: Json): ShareCheckinApi =
            SupabaseShareCheckinApi(provider, json)

        @Provides
        fun provideShareConfig(): ShareConfig = ShareConfig(BuildConfig.SHARE_BASE_URL.ifBlank { DEFAULT_SHARE_BASE_URL })
    }
}
