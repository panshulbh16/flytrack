package app.flytrack.feature.gmailimport

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class GmailModule {
    @Binds
    @Singleton
    abstract fun bindGmailAuth(impl: PlayGmailAuth): GmailAuth

    companion object {
        /** Gmail's own HttpClient, never shared with SupabaseClientProvider, so no backend header can leak to Google. */
        @Provides
        @Singleton
        fun provideGmailApi(json: Json): GmailApi = KtorGmailApi(HttpClient(OkHttp), json)
    }
}
