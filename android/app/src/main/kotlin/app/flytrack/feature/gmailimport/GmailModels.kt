package app.flytrack.feature.gmailimport

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Where a Gmail suggestion stands. Only [PENDING] ones are shown or notified. */
enum class SuggestionStatus(val wire: String) {
    PENDING("pending"),
    DISMISSED("dismissed"),
    ADDED("added");

    companion object {
        /** An unknown value reads as dismissed, so a row the app cannot place is never offered again. */
        fun fromWire(v: String): SuggestionStatus = entries.firstOrNull { it.wire == v } ?: DISMISSED
    }
}

/**
 * One flight leg found in the user's Gmail. [id] is "<flight compact>_<yyyy-MM-dd>" (see
 * [GmailSuggestions.suggestionId]); [flight] is the display form "6E 2341". Stored on the phone only.
 */
data class GmailSuggestion(
    val id: String,
    val messageId: String,
    val flight: String,
    val date: LocalDate,
    val fromIata: String?,
    val toIata: String?,
    val pnr: String?,
    val subject: String,
    val status: SuggestionStatus,
    val foundAtEpochMs: Long,
)

/** A flight the user already tracks: [flightCompact] is "6E2341", [date] the departure date. */
data class TrackedKey(val flightCompact: String, val date: LocalDate)

// Gmail API JSON (users.messages.list / get / attachments.get, users.getProfile). Only the fields the scanner reads.

@Serializable
data class GmailListResponse(val messages: List<GmailMessageRef> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class GmailMessageRef(val id: String)

/** [internalDate] is when Gmail received the email, in epoch ms as a decimal string. The scanner reads dates with no year against it. */
@Serializable
data class GmailMessage(val id: String, val payload: GmailPart? = null, val internalDate: String? = null)

@Serializable
data class GmailPart(
    val mimeType: String? = null,
    val filename: String? = null,
    val headers: List<GmailHeader> = emptyList(),
    val body: GmailBody? = null,
    val parts: List<GmailPart> = emptyList(),
)

@Serializable
data class GmailHeader(val name: String, val value: String)

@Serializable
data class GmailBody(val size: Long = 0, val data: String? = null, val attachmentId: String? = null)

@Serializable
data class GmailProfile(val emailAddress: String)

@Serializable
data class GmailAttachment(val size: Long = 0, val data: String? = null)

/** Why a Gmail call failed, as the scanner acts on it (see the spec's Errors table). */
enum class GmailFailure { Unauthorized, AccessLost, RateLimited, Offline, Other }

class GmailApiException(val failure: GmailFailure, message: String? = null) : Exception(message)
