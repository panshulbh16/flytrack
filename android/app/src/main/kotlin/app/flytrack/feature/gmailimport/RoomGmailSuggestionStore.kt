package app.flytrack.feature.gmailimport

import app.flytrack.data.local.GmailDao
import app.flytrack.data.local.GmailSeenMessageEntity
import app.flytrack.data.local.GmailSuggestionEntity
import app.flytrack.data.local.TransactionRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import javax.inject.Inject

/** Gmail suggestions and seen message ids in Room. Local-only: nothing here reaches the server. */
class RoomGmailSuggestionStore @Inject constructor(
    private val dao: GmailDao,
    private val transactions: TransactionRunner,
) : GmailSuggestionStore {
    override fun observeAll(): Flow<List<GmailSuggestion>> =
        dao.observeSuggestions().map { rows -> rows.map { it.toModel() } }

    override suspend fun all(): List<GmailSuggestion> = dao.suggestions().map { it.toModel() }

    override suspend fun get(id: String): GmailSuggestion? = dao.suggestion(id)?.toModel()

    /** Ids already stored keep their row and status. */
    override suspend fun insert(suggestions: List<GmailSuggestion>) =
        dao.insertSuggestions(suggestions.map { it.toEntity() })

    override suspend fun setStatus(ids: List<String>, status: SuggestionStatus) = dao.setStatus(ids, status.wire)

    override suspend fun delete(ids: List<String>) = dao.deleteSuggestions(ids)

    override suspend fun seen(messageIds: List<String>): Set<String> = dao.seen(messageIds).toSet()

    override suspend fun markSeen(messageIds: List<String>) =
        dao.markSeen(messageIds.map(::GmailSeenMessageEntity))

    override suspend fun clear() = transactions.inTransaction {
        dao.clearSuggestions()
        dao.clearSeen()
    }
}

internal fun GmailSuggestionEntity.toModel() = GmailSuggestion(
    id = id,
    messageId = messageId,
    flight = flight,
    date = LocalDate.parse(date),
    fromIata = fromIata,
    toIata = toIata,
    pnr = pnr,
    subject = subject,
    status = SuggestionStatus.fromWire(status),
    foundAtEpochMs = foundAt,
)

internal fun GmailSuggestion.toEntity() = GmailSuggestionEntity(
    id = id,
    messageId = messageId,
    flight = flight,
    date = date.toString(),
    fromIata = fromIata,
    toIata = toIata,
    pnr = pnr,
    subject = subject,
    status = status.wire,
    foundAt = foundAtEpochMs,
)
