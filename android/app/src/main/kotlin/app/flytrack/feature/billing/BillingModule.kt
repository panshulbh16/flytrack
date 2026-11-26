package app.flytrack.feature.billing

import app.flytrack.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingConfig(): BillingConfig = BillingConfig(revenueCatAndroidKey = BuildConfig.REVENUECAT_ANDROID_KEY)

    /** RevenueCat only with a key; otherwise the SDK is never touched. */
    @Provides
    @Singleton
    fun provideBillingGateway(
        config: BillingConfig,
        revenueCat: Provider<RevenueCatBilling>,
        noop: NoopBilling,
    ): BillingGateway = if (config.isConfigured) revenueCat.get() else noop
}
