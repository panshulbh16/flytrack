package app.flytrack.feature.settings

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.flytrack.data.remote.BackendConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What Credential Manager returned. No Android types, so the settings logic can be unit-tested. */
sealed interface GoogleSignInAttempt {
    data class Token(val idToken: String) : GoogleSignInAttempt
    data object Cancelled : GoogleSignInAttempt
    data object NoCredential : GoogleSignInAttempt
    data object NotConfigured : GoogleSignInAttempt
    data class Failed(val message: String?) : GoogleSignInAttempt
}

/** Asks the user for a Google ID token. [activity] is the window Credential Manager attaches to. */
fun interface GoogleIdTokenRequester {
    suspend fun request(activity: Activity): GoogleSignInAttempt
}

@Singleton
class CredentialManagerGoogleSignIn @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: BackendConfig,
) : GoogleIdTokenRequester {

    override suspend fun request(activity: Activity): GoogleSignInAttempt {
        val clientId = config.googleWebClientId
        if (!config.isGoogleSignInConfigured || clientId.isBlank()) return GoogleSignInAttempt.NotConfigured
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return try {
            val response = CredentialManager.create(context).getCredential(activity, request)
            val credential = response.credential
            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleSignInAttempt.NoCredential
            } else {
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                if (token.isBlank()) GoogleSignInAttempt.NoCredential else GoogleSignInAttempt.Token(token)
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInAttempt.Cancelled
        } catch (_: NoCredentialException) {
            GoogleSignInAttempt.NoCredential
        } catch (e: GetCredentialException) {
            GoogleSignInAttempt.Failed(e.message)
        }
    }
}
