package app.flytrack.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the app's single [SupabaseClient] (Auth + Postgrest + Functions on the Ktor OkHttp engine).
 * [client] is null when the build has no backend keys; callers must treat that as "not configured".
 */
class SupabaseClientProvider(
    private val config: BackendConfig,
    private val json: Json,
    /** Overridable for tests (ktor-client-mock). */
    private val engineFactory: () -> HttpClientEngine = { OkHttp.create() },
    /** Tests of Postgrest/Functions alone switch Auth off; requests then carry the anon key. */
    private val installAuth: Boolean = true,
    /** Overridable for tests: Auth's defaults (session storage, lifecycle callbacks) need Android. */
    private val configureAuth: AuthConfig.() -> Unit = {},
) {
    val isConfigured: Boolean get() = config.isConfigured

    val client: SupabaseClient? by lazy {
        if (!config.isConfigured) return@lazy null
        createSupabaseClient(config.supabaseUrl, config.supabaseAnonKey) {
            httpEngine = engineFactory()
            defaultSerializer = KotlinXSerializer(json)
            // search-flight may wait for the flight-data provider; the default 10 s is too tight on 2G/3G.
            requestTimeout = 30.seconds
            if (installAuth) install(Auth, configureAuth)
            install(Postgrest)
            install(Functions)
        }
    }
}
