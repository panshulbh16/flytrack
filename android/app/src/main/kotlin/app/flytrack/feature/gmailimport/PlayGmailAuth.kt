package app.flytrack.feature.gmailimport

import android.accounts.Account
import android.content.Context
import android.content.Intent
import app.flytrack.data.remote.BackendConfig
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GmailAuth] on Google Identity's AuthorizationClient (play-services-auth 21.5.0+). Hands out a
 * short-lived gmail.readonly access token; the app stores no token, only the account address.
 */
@Singleton
class PlayGmailAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: BackendConfig,
) : GmailAuth {

    private val client: AuthorizationClient by lazy { Identity.getAuthorizationClient(context) }

    /** Always shows the account picker: the Gmail account need not be the one linked to HeyGate. */
    override suspend fun connect(): GmailAuthResult =
        authorize { setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT) }

    override suspend fun silentToken(email: String): GmailAuthResult =
        authorize { setAccount(Account(email, GOOGLE_ACCOUNT_TYPE)) }

    override fun resultFromIntent(data: Intent?): GmailAuthResult {
        if (data == null) return GmailAuthResult.Failed
        return try {
            toResult(client.getAuthorizationResultFromIntent(data))
        } catch (e: ApiException) {
            toResult(e)
        }
    }

    override suspend fun clearToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (_: ApiException) {
            // ponytail: a stale cached token only costs one more 401, which the scanner already handles.
        }
    }

    override suspend fun revoke(email: String) {
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, GOOGLE_ACCOUNT_TYPE))
            .setScopes(listOf(Scope(GMAIL_READONLY)))
            .build()
        try {
            client.revokeAccess(request).await()
        } catch (_: ApiException) {
            // Disconnect and sign-out still clear local state; the grant can be removed at myaccount.google.com.
        }
    }

    private suspend fun authorize(configure: AuthorizationRequest.Builder.() -> Unit): GmailAuthResult {
        if (config.googleWebClientId.isBlank()) return GmailAuthResult.NotConfigured
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GMAIL_READONLY)))
            .apply(configure)
            .build()
        return try {
            toResult(client.authorize(request).await())
        } catch (e: ApiException) {
            toResult(e)
        }
    }

    private fun toResult(result: AuthorizationResult): GmailAuthResult {
        if (result.hasResolution()) return GmailAuthResult.NeedsUser(result.pendingIntent)
        val token: String? = result.accessToken
        // A grant without the Gmail scope gets a 403 insufficientPermissions from Gmail -> AccessLost.
        return if (token.isNullOrBlank()) GmailAuthResult.Failed else GmailAuthResult.Granted(token)
    }

    private fun toResult(e: ApiException): GmailAuthResult =
        if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) GmailAuthResult.Offline else GmailAuthResult.Failed

    private companion object {
        const val GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
