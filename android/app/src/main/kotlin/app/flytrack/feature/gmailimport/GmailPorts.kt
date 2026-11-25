package app.flytrack.feature.gmailimport

import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.flow.Flow

/** The four Gmail GETs. Throws [GmailApiException]. */
interface GmailApi {
    suspend fun profile(token: String): GmailProfile
    suspend fun list(token: String, query: String, pageToken: String?): GmailListResponse
    suspend fun message(token: String, id: String): GmailMessage
    suspend fun attachment(token: String, messageId: String, attachmentId: String): ByteArray
}

sealed interface GmailAuthResult {
    data class Granted(val token: String) : GmailAuthResult
    data class NeedsUser(val resolution: PendingIntent?) : GmailAuthResult
    data object NotConfigured : GmailAuthResult
    data object Offline : GmailAuthResult
    data object Failed : GmailAuthResult
}

interface GmailAuth {
    suspend fun connect(): GmailAuthResult
    suspend fun silentToken(email: String): GmailAuthResult
    fun resultFromIntent(data: Intent?): GmailAuthResult
    suspend fun clearToken(token: String)
    suspend fun revoke(email: String)
}

/** Local-only Gmail suggestions and seen message ids. [clear] empties both. */
interface GmailSuggestionStore {
    fun observeAll(): Flow<List<GmailSuggestion>>
    suspend fun all(): List<GmailSuggestion>
    suspend fun get(id: String): GmailSuggestion?
    suspend fun insert(suggestions: List<GmailSuggestion>)
    suspend fun setStatus(ids: List<String>, status: SuggestionStatus)
    suspend fun delete(ids: List<String>)
    suspend fun seen(messageIds: List<String>): Set<String>
    suspend fun markSeen(messageIds: List<String>)
    suspend fun clear()
}
