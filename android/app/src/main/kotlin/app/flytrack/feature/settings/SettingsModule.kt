package app.flytrack.feature.settings

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {
    @Binds
    @Singleton
    abstract fun bindDefaultAlerts(impl: DataStoreDefaultAlertPreferences): DefaultAlertPreferences

    @Binds
    @Singleton
    abstract fun bindGoogleSignIn(impl: CredentialManagerGoogleSignIn): GoogleIdTokenRequester

    @Binds
    @Singleton
    abstract fun bindAppVersion(impl: BuildAppVersion): AppVersion
}
