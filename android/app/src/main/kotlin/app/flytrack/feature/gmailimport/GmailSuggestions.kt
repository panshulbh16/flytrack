package app.flytrack.feature.gmailimport

import app.flytrack.feature.importticket.ParsedTicket
import kotlinx.datetime.LocalDate

/** Turns parsed booking emails into suggestions and keeps the stored ones tidy. Pure: no I/O, no clock. */
object GmailSuggestions {
    /** "6E2341_2026-12-03". */
    fun suggestionId(flightCompact: String, date: LocalDate): String = "${flightCompact}_$date"

    /** "6E 2341" or "6e-2341" -> "6E2341". */
    fun compact(flightDisplay: String): String = flightDisplay.filter { it.isLetterOrDigit() }.uppercase()

    /** Backstop to the query's -subject terms: Gmail matches whole words, this catches "Cancelled:" and the like. */
    fun isCancellation(subject: String): Boolean =
        subject.contains("cancel", ignoreCase = true) || subject.contains("refund", ignoreCase = true)

    /**
     * One pending suggestion per leg dated [today] or later. Undated legs are dropped (they cannot be
     * shown to be upcoming); a leg with no route is kept with null airports.
     */
    fun fromTicket(
        ticket: ParsedTicket,
        messageId: String,
        subject: String,
        today: LocalDate,
        foundAtEpochMs: Long,
    ): List<GmailSuggestion> =
        ticket.flights.mapNotNull { leg ->
            val date = leg.date?.takeIf { it >= today } ?: return@mapNotNull null
            val flight = leg.flight.display
            GmailSuggestion(
                id = suggestionId(compact(flight), date),
                messageId = messageId,
                flight = flight,
                date = date,
                fromIata = leg.fromIata,
                toIata = leg.toIata,
                pnr = ticket.pnr,
                subject = subject,
                status = SuggestionStatus.PENDING,
                foundAtEpochMs = foundAtEpochMs,
            )
        }.distinctBy { it.id }

    /**
     * The finds worth storing: not already tracked, not already a suggestion in any status, and one per
     * id. Of duplicates the highest [GmailSuggestion.foundAtEpochMs] wins; on a tie, the later in [found].
     */
    fun newOnly(found: List<GmailSuggestion>, existingIds: Set<String>, tracked: Set<TrackedKey>): List<GmailSuggestion> =
        found.filter { it.id !in existingIds && it.key() !in tracked }
            .groupBy { it.id }
            .map { (_, same) -> same.reduce { kept, next -> if (next.foundAtEpochMs >= kept.foundAtEpochMs) next else kept } }

    /** What the card and the Gmail screen show: pending, dated today or later, not tracked, soonest first. */
    fun visible(all: List<GmailSuggestion>, today: LocalDate, tracked: Set<TrackedKey>): List<GmailSuggestion> =
        all.filter { it.status == SuggestionStatus.PENDING && it.date >= today && it.key() !in tracked }
            .sortedWith(compareBy({ it.date }, { it.flight }))

    /** [deleteIds]: dated before today, any status. [markAddedIds]: pending ones the user tracked another way. */
    data class Cleanup(val deleteIds: List<String>, val markAddedIds: List<String>)

    fun cleanup(all: List<GmailSuggestion>, today: LocalDate, tracked: Set<TrackedKey>): Cleanup =
        Cleanup(
            deleteIds = all.filter { it.date < today }.map { it.id },
            markAddedIds = all.filter { it.status == SuggestionStatus.PENDING && it.date >= today && it.key() in tracked }
                .map { it.id },
        )

    private fun GmailSuggestion.key() = TrackedKey(compact(flight), date)
}
