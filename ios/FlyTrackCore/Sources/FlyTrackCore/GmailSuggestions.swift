import Foundation

public struct GmailCleanup: Equatable, Sendable {
    public var deleteIds: [String]
    public var markAddedIds: [String]

    public init(deleteIds: [String], markAddedIds: [String]) {
        self.deleteIds = deleteIds
        self.markAddedIds = markAddedIds
    }
}

/// Turns parsed booking emails into suggestions and applies the suggestion lifecycle rules.
public enum GmailSuggestions {
    /// "6E2341_2026-12-03".
    public static func suggestionId(flightCompact: String, date: LocalDate) -> String {
        "\(flightCompact)_\(date.iso)"
    }

    /// "6E 2341" or "6e-2341" -> "6E2341".
    public static func compact(_ flightDisplay: String) -> String {
        flightDisplay.filter { !$0.isWhitespace && $0 != "-" }.uppercased()
    }

    /// Backstop to the query: a subject mentioning a cancellation or refund is never a booking.
    public static func isCancellation(_ subject: String) -> Bool {
        let lower = subject.lowercased()
        return lower.contains("cancel") || lower.contains("refund")
    }

    /// One pending suggestion per leg dated today or later. Undated legs cannot be shown to be upcoming and are dropped.
    public static func fromTicket(_ ticket: ParsedTicket, messageId: String, subject: String, today: LocalDate, foundAt: Int64) -> [GmailSuggestion] {
        if isCancellation(subject) { return [] }
        var ids = Set<String>()
        return ticket.flights.compactMap { leg in
            guard let date = leg.date, date >= today else { return nil }
            let flight = leg.flight.display
            let id = suggestionId(flightCompact: compact(flight), date: date)
            // The first copy of a repeated leg wins: the plain part's, which comes before the HTML part's.
            guard ids.insert(id).inserted else { return nil }
            return GmailSuggestion(
                id: id, messageId: messageId, flight: flight, date: date,
                fromIata: leg.fromIata, toIata: leg.toIata, pnr: ticket.pnr, subject: subject, status: .pending, foundAt: foundAt
            )
        }
    }

    /// Merges finds with the same id, keeping the newest (on a tie, the later one), then drops tracked
    /// flights and ids already stored in any status. Keeps first-seen order.
    public static func newOnly(_ found: [GmailSuggestion], existingIds: Set<String>, tracked: Set<TrackedKey>) -> [GmailSuggestion] {
        var byId: [String: GmailSuggestion] = [:]
        var order: [String] = []
        for suggestion in found {
            if let kept = byId[suggestion.id] {
                if suggestion.foundAt >= kept.foundAt { byId[suggestion.id] = suggestion }
            } else {
                byId[suggestion.id] = suggestion
                order.append(suggestion.id)
            }
        }
        return order.compactMap { byId[$0] }.filter { !existingIds.contains($0.id) && !tracked.contains(key($0)) }
    }

    /// What the card and the Gmail screen show: pending, today or later, not tracked, soonest first.
    public static func visible(_ all: [GmailSuggestion], today: LocalDate, tracked: Set<TrackedKey>) -> [GmailSuggestion] {
        all.filter { $0.status == .pending && $0.date >= today && !tracked.contains(key($0)) }
            .sorted { ($0.date, $0.flight) < ($1.date, $1.flight) }
    }

    /// Delete everything dated before today; mark added any pending suggestion now tracked another way.
    public static func cleanup(_ all: [GmailSuggestion], today: LocalDate, tracked: Set<TrackedKey>) -> GmailCleanup {
        GmailCleanup(
            deleteIds: all.filter { $0.date < today }.map(\.id),
            markAddedIds: all.filter { $0.status == .pending && $0.date >= today && tracked.contains(key($0)) }.map(\.id)
        )
    }

    private static func key(_ suggestion: GmailSuggestion) -> TrackedKey {
        TrackedKey(flightCompact: compact(suggestion.flight), date: suggestion.date)
    }
}
