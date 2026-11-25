package app.flytrack.feature.gmailimport

import app.flytrack.data.repo.SettingsStore
import app.flytrack.feature.addflight.utcMillisToLocalDate
import app.flytrack.feature.importticket.ImportToday
import app.flytrack.feature.importticket.parseTicketText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import kotlin.time.Clock

/** Flight number and date of every tracked flight, to avoid suggesting what is already tracked. */
fun interface TrackedKeysSource {
    suspend fun current(): Set<TrackedKey>
}

/** Reads a PDF ticket's text on the phone. */
fun interface PdfTextReader {
    suspend fun read(bytes: ByteArray): String
}

/** Phone notifications for suggestions (channel `ticket_suggestions`). */
interface GmailNotifier {
    fun notifyNew(suggestions: List<GmailSuggestion>)
    fun cancel(ids: List<String>)
    fun cancelAll()
}

enum class ScanResult { Completed, Partial, Offline, NeedsReconnect, NotConnected }

data class ScanOutcome(val result: ScanResult, val newSuggestions: List<GmailSuggestion>)

/** Unseen emails read in one scan at most; the rest wait for the next scan. */
const val GMAIL_MAX_READ_PER_SCAN = 50

/** Larger PDF attachments are not downloaded. */
const val GMAIL_MAX_PDF_BYTES = 5_000_000L

/**
 * Runs one Gmail scan on the phone (spec "Scanning"): lists booking emails, reads up to
 * [GMAIL_MAX_READ_PER_SCAN] unseen ones, turns them into suggestions and stores the new ones.
 * Email bodies are never stored or logged. Callers run [cleanup] before [scan].
 */
class GmailScanner @Inject constructor(
    private val api: GmailApi,
    private val auth: GmailAuth,
    private val store: GmailSuggestionStore,
    private val settings: SettingsStore,
    private val tracked: TrackedKeysSource,
    private val pdf: PdfTextReader,
    private val today: ImportToday,
    private val clock: Clock,
) {
    suspend fun scan(): ScanOutcome {
        val email = settings.gmailAccountEmail.first() ?: return outcome(ScanResult.NotConnected)
        val startedAt = clock.now().epochSeconds
        val token = when (val granted = auth.silentToken(email)) {
            is GmailAuthResult.Granted -> granted.token
            is GmailAuthResult.NeedsUser -> {
                // A disconnect during silentToken has already wiped the flag: leave it off.
                if (settings.gmailAccountEmail.first() != email) return outcome(ScanResult.NotConnected)
                settings.setGmailNeedsReconnect(true)
                return outcome(ScanResult.NeedsReconnect)
            }
            GmailAuthResult.Offline -> return outcome(ScanResult.Offline)
            GmailAuthResult.NotConfigured -> return outcome(ScanResult.NotConnected)
            GmailAuthResult.Failed -> return outcome(ScanResult.Partial)
        }
        val session = Session(email, token)
        val query = GmailQuery.build(settings.gmailLastCheckEpochSeconds.first())
        val day = today.today()
        val found = mutableListOf<GmailSuggestion>()
        val seenIds = mutableListOf<String>()

        val result = try {
            readAll(session, query, day, found, seenIds)
        } catch (e: GmailApiException) {
            when (e.failure) {
                GmailFailure.Unauthorized, GmailFailure.AccessLost -> ScanResult.NeedsReconnect
                GmailFailure.Offline -> ScanResult.Offline
                GmailFailure.RateLimited, GmailFailure.Other -> ScanResult.Partial
            }
        }

        // Disconnect, sign-out or a user switch ran while this scan read mail: the wipe is done, so write nothing.
        // ponytail: one check before the writes; a wipe landing between it and the writes (no network) is not covered.
        if (settings.gmailAccountEmail.first() != email) return outcome(ScanResult.NotConnected)
        if (result == ScanResult.NeedsReconnect) settings.setGmailNeedsReconnect(true)

        // Whatever was found before a stop is kept; seen ids stop the next scan re-reading it.
        val fresh = GmailSuggestions.newOnly(found, store.all().map { it.id }.toSet(), tracked.current())
        if (fresh.isNotEmpty()) store.insert(fresh)
        if (seenIds.isNotEmpty()) store.markSeen(seenIds)
        if (result == ScanResult.Completed) settings.setGmailLastCheckEpochSeconds(startedAt)
        return ScanOutcome(result, fresh)
    }

    /** Lifecycle cleanup: drops past suggestions, marks ones tracked another way as added, cancels both notifications. */
    suspend fun cleanup(notifier: GmailNotifier?) {
        val plan = GmailSuggestions.cleanup(store.all(), today.today(), tracked.current())
        if (plan.deleteIds.isNotEmpty()) store.delete(plan.deleteIds)
        if (plan.markAddedIds.isNotEmpty()) store.setStatus(plan.markAddedIds, SuggestionStatus.ADDED)
        val cancelled = plan.deleteIds + plan.markAddedIds
        if (cancelled.isNotEmpty()) notifier?.cancel(cancelled)
    }

    /** Pages to the end of the listing; [ScanResult.Partial] when the read cap stops it first. */
    private suspend fun readAll(
        session: Session,
        query: String,
        day: LocalDate,
        found: MutableList<GmailSuggestion>,
        seenIds: MutableList<String>,
    ): ScanResult {
        var pageToken: String? = null
        var read = 0
        do {
            val page = session.call { api.list(it, query, pageToken) }
            val ids = page.messages.map { it.id }.distinct().filterNot { it in seenIds }
            val unseen = ids - store.seen(ids)
            for (id in unseen) {
                if (read == GMAIL_MAX_READ_PER_SCAN) return ScanResult.Partial
                read++
                // Duplicate legs are merged by GmailSuggestions.newOnly: the newest email (by its own date, not
                // list order) keeps its PNR and subject (spec "Filter").
                found += readMessage(session, id, day)
                seenIds += id
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return ScanResult.Completed
    }

    /** Suggestions from one email; empty when it is undecodable, a cancellation, or names no upcoming flight. */
    private suspend fun readMessage(session: Session, id: String, day: LocalDate): List<GmailSuggestion> {
        val message = skipOnOther { session.call { api.message(it, id) } } ?: return emptyList()
        val content = try {
            message.content()
        } catch (e: Exception) {
            return emptyList()
        }
        if (GmailSuggestions.isCancellation(content.subject)) return emptyList()
        // A date with no year is read against the email's own date, not today: "15 Jul" in a June email is
        // last July (dropped below as past), not next July. The legs are still filtered by today.
        val sent = message.internalDate?.toLongOrNull()?.let(::utcMillisToLocalDate)
        val reference = sent?.coerceAtMost(day) ?: day
        var ticket = parseTicketText(content.text, reference)
        val attachmentId = content.pdfAttachmentId
        if (ticket.flights.isEmpty() && attachmentId != null && content.pdfSize <= GMAIL_MAX_PDF_BYTES) {
            val bytes = skipOnOther { session.call { api.attachment(it, id, attachmentId) } }
            if (bytes != null) ticket = parseTicketText(readPdf(bytes), reference)
        }
        val foundAt = message.internalDate?.toLongOrNull() ?: clock.now().toEpochMilliseconds()
        return GmailSuggestions.fromTicket(ticket, id, content.subject, day, foundAt)
    }

    private suspend fun readPdf(bytes: ByteArray): String = try {
        pdf.read(bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "" // a broken PDF is skipped like an undecodable email
    }

    /** A 404 or other one-off failure skips this email or attachment instead of stopping the scan. */
    private inline fun <T> skipOnOther(block: () -> T): T? = try {
        block()
    } catch (e: GmailApiException) {
        if (e.failure == GmailFailure.Other) null else throw e
    }

    private fun outcome(result: ScanResult) = ScanOutcome(result, emptyList())

    /** One scan's token. A 401 gets one fresh token per scan; another 401 means access is lost. */
    private inner class Session(private val email: String, private var token: String) {
        private var refreshed = false

        suspend fun <T> call(request: suspend (String) -> T): T = try {
            request(token)
        } catch (e: GmailApiException) {
            if (e.failure != GmailFailure.Unauthorized || refreshed) throw e
            refreshed = true
            auth.clearToken(token)
            token = when (val again = auth.silentToken(email)) {
                is GmailAuthResult.Granted -> again.token
                is GmailAuthResult.NeedsUser -> throw GmailApiException(GmailFailure.AccessLost)
                GmailAuthResult.Offline -> throw GmailApiException(GmailFailure.Offline)
                // Not Other: skipOnOther would mark the email seen unread. RateLimited ends the scan as Partial,
                // so the email is read next time and needs-reconnect is left alone.
                GmailAuthResult.NotConfigured, GmailAuthResult.Failed ->
                    throw GmailApiException(GmailFailure.RateLimited, "token refresh failed")
            }
            request(token)
        }
    }
}
