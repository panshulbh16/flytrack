package app.flytrack.testing

import android.content.Intent
import app.flytrack.feature.gmailimport.GmailApi
import app.flytrack.feature.gmailimport.GmailApiException
import app.flytrack.feature.gmailimport.GmailAuth
import app.flytrack.feature.gmailimport.GmailAuthResult
import app.flytrack.feature.gmailimport.GmailBody
import app.flytrack.feature.gmailimport.GmailFailure
import app.flytrack.feature.gmailimport.GmailHeader
import app.flytrack.feature.gmailimport.GmailListResponse
import app.flytrack.feature.gmailimport.GmailMessage
import app.flytrack.feature.gmailimport.GmailMessageRef
import app.flytrack.feature.gmailimport.GmailNotifier
import app.flytrack.feature.gmailimport.GmailPart
import app.flytrack.feature.gmailimport.GmailProfile
import app.flytrack.feature.gmailimport.GmailSuggestion
import app.flytrack.feature.gmailimport.GmailSuggestionStore
import app.flytrack.feature.gmailimport.GmailSuggestions
import app.flytrack.feature.gmailimport.GmailWorkScheduler
import app.flytrack.feature.gmailimport.SuggestionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import java.util.Base64

// In-memory Gmail fakes for scanner, connection and view model tests, in the style of FakeRepositories.kt.

/**
 * Serves [messages] through [pages] (lists of message ids; page n > 0 has the token "page-n").
 * When [acceptedToken] is set, any other token gets a 401. [fail] queues failures for one call
 * kind: "profile", "list", "message:<id>" or "attachment:<attachmentId>", used up in order.
 */
class FakeGmailApi : GmailApi {
    var profileEmail = "traveller@gmail.com"
    var pages: List<List<String>> = emptyList()
    val messages = mutableMapOf<String, GmailMessage>()
    val attachments = mutableMapOf<String, ByteArray>()
    var acceptedToken: String? = null

    /** Runs at the start of every call, e.g. to advance a clock. */
    var onCall: () -> Unit = {}

    data class ListCall(val query: String, val pageToken: String?)

    val listCalls = mutableListOf<ListCall>()
    val messageCalls = mutableListOf<String>()
    val attachmentCalls = mutableListOf<String>()
    val tokensUsed = mutableListOf<String>()
    private val failures = mutableMapOf<String, ArrayDeque<GmailFailure>>()

    fun fail(key: String, vararg failure: GmailFailure) {
        failures.getOrPut(key) { ArrayDeque() }.addAll(failure)
    }

    override suspend fun profile(token: String): GmailProfile {
        check("profile", token)
        return GmailProfile(profileEmail)
    }

    override suspend fun list(token: String, query: String, pageToken: String?): GmailListResponse {
        listCalls += ListCall(query, pageToken)
        check("list", token)
        val index = pageToken?.removePrefix("page-")?.toInt() ?: 0
        return GmailListResponse(
            messages = pages.getOrElse(index) { emptyList() }.map { GmailMessageRef(it) },
            nextPageToken = if (index + 1 < pages.size) "page-${index + 1}" else null,
        )
    }

    override suspend fun message(token: String, id: String): GmailMessage {
        messageCalls += id
        check("message:$id", token)
        return messages.getValue(id)
    }

    override suspend fun attachment(token: String, messageId: String, attachmentId: String): ByteArray {
        attachmentCalls += attachmentId
        check("attachment:$attachmentId", token)
        return attachments.getValue(attachmentId)
    }

    private fun check(key: String, token: String) {
        onCall()
        tokensUsed += token
        failures[key]?.removeFirstOrNull()?.let { throw GmailApiException(it) }
        if (acceptedToken != null && token != acceptedToken) throw GmailApiException(GmailFailure.Unauthorized)
    }
}

/** The n-th [silentToken] call returns `silentResults[n]`; the last entry repeats. */
class FakeGmailAuth : GmailAuth {
    /** Runs at the start of every [silentToken] call, e.g. to land a disconnect mid-scan. */
    var onSilentToken: suspend () -> Unit = {}

    var silentResults: List<GmailAuthResult> = listOf(GmailAuthResult.Granted("token-1"))
    var connectResult: GmailAuthResult = GmailAuthResult.Granted("token-1")
    var revokeError: Exception? = null
    /** Runs at the start of every [revoke] call, e.g. to check what was cleared first or to hang forever. */
    var onRevoke: suspend () -> Unit = {}

    val silentEmails = mutableListOf<String>()
    val clearedTokens = mutableListOf<String>()
    val revokedEmails = mutableListOf<String>()
    var connectCalls = 0

    override suspend fun connect(): GmailAuthResult {
        connectCalls++
        return connectResult
    }

    override suspend fun silentToken(email: String): GmailAuthResult {
        onSilentToken()
        silentEmails += email
        return silentResults[minOf(silentEmails.size - 1, silentResults.lastIndex)]
    }

    override fun resultFromIntent(data: Intent?): GmailAuthResult = connectResult

    override suspend fun clearToken(token: String) {
        clearedTokens += token
    }

    override suspend fun revoke(email: String) {
        revokedEmails += email
        onRevoke()
        revokeError?.let { throw it }
    }
}

/** Insert keeps an existing row with the same id (like Room's OnConflictStrategy.IGNORE). */
class FakeGmailSuggestionStore : GmailSuggestionStore {
    val suggestions = MutableStateFlow<Map<String, GmailSuggestion>>(emptyMap())
    val seenIds = mutableSetOf<String>()
    var clearCalls = 0

    override fun observeAll(): Flow<List<GmailSuggestion>> = suggestions.map { it.values.toList() }

    override suspend fun all(): List<GmailSuggestion> = suggestions.value.values.toList()

    override suspend fun get(id: String): GmailSuggestion? = suggestions.value[id]

    override suspend fun insert(suggestions: List<GmailSuggestion>) {
        this.suggestions.update { current -> current + suggestions.filterNot { it.id in current }.associateBy { it.id } }
    }

    override suspend fun setStatus(ids: List<String>, status: SuggestionStatus) {
        suggestions.update { current -> current.mapValues { (id, s) -> if (id in ids) s.copy(status = status) else s } }
    }

    override suspend fun delete(ids: List<String>) {
        suggestions.update { it - ids.toSet() }
    }

    override suspend fun seen(messageIds: List<String>): Set<String> = messageIds.filter { it in seenIds }.toSet()

    override suspend fun markSeen(messageIds: List<String>) {
        seenIds += messageIds
    }

    override suspend fun clear() {
        clearCalls++
        suggestions.value = emptyMap()
        seenIds.clear()
    }
}

class FakeGmailNotifier : GmailNotifier {
    val notified = mutableListOf<List<GmailSuggestion>>()
    val cancelled = mutableListOf<String>()
    var cancelAllCalls = 0

    override fun notifyNew(suggestions: List<GmailSuggestion>) {
        notified += suggestions
    }

    override fun cancel(ids: List<String>) {
        cancelled += ids
    }

    override fun cancelAll() {
        cancelAllCalls++
    }
}

/** Counts schedule and cancel calls. */
class FakeWorkScheduler : GmailWorkScheduler {
    var scheduled = 0
    var cancelled = 0

    override fun schedule() {
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}

/** Synthetic Gmail messages shaped like the API's format=full responses. */
object GmailFixtures {
    fun base64Url(text: String): String = Base64.getUrlEncoder().encodeToString(text.encodeToByteArray())

    /** Air India-style ticket text the on-device parser reads (same shape as TicketParserTest). */
    fun ticket(flight: String = "AI-101", date: String = "25 Nov 2026", pnr: String = "A1B2C3"): String =
        "Air India\nPNR: $pnr\n$flight\n$date\nDEL → BOM"

    /** A text/plain body inside multipart/alternative, Subject on the root part. */
    fun textMessage(id: String, subject: String, body: String): GmailMessage = GmailMessage(
        id = id,
        payload = GmailPart(
            mimeType = "multipart/alternative",
            headers = listOf(GmailHeader("Subject", subject)),
            parts = listOf(textPart(body)),
        ),
    )

    /** A short text/plain body plus a PDF attachment of [size] bytes stored as [attachmentId]. */
    fun pdfMessage(
        id: String,
        subject: String,
        attachmentId: String,
        size: Long,
        body: String = "Your e-ticket is attached.",
    ): GmailMessage = GmailMessage(
        id = id,
        payload = GmailPart(
            mimeType = "multipart/mixed",
            headers = listOf(GmailHeader("Subject", subject)),
            parts = listOf(
                textPart(body),
                GmailPart(
                    mimeType = "application/pdf",
                    filename = "ticket.pdf",
                    body = GmailBody(size = size, attachmentId = attachmentId),
                ),
            ),
        ),
    )

    fun suggestion(
        flight: String = "AI 101",
        date: LocalDate = LocalDate(2026, 11, 25),
        status: SuggestionStatus = SuggestionStatus.PENDING,
        messageId: String = "m-old",
    ): GmailSuggestion = GmailSuggestion(
        id = GmailSuggestions.suggestionId(GmailSuggestions.compact(flight), date),
        messageId = messageId,
        flight = flight,
        date = date,
        fromIata = "DEL",
        toIata = "BOM",
        pnr = null,
        subject = "Earlier booking",
        status = status,
        foundAtEpochMs = 0,
    )

    private fun textPart(body: String) = GmailPart(
        mimeType = "text/plain",
        body = GmailBody(size = body.encodeToByteArray().size.toLong(), data = base64Url(body)),
    )
}
