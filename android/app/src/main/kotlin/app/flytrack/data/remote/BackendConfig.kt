package app.flytrack.data.remote

/**
 * Backend keys baked into the build from android/local.properties or environment variables
 * (see app/build.gradle.kts). All blank in a fresh checkout: the app must still start then, and
 * every repository reports `NotConfigured` instead of crashing.
 */
data class BackendConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    /** OAuth web client id for Google sign-in with Credential Manager; blank disables "Link Google". */
    val googleWebClientId: String,
) {
    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val isGoogleSignInConfigured: Boolean get() = isConfigured && googleWebClientId.isNotBlank()
}
