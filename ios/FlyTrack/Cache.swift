import FlyTrackCore
import Foundation
import SwiftData

@Model
final class CachedFlightRecord {
    @Attribute(.unique) var id: String
    var payload: String

    init(id: String, payload: String) {
        self.id = id
        self.payload = payload
    }
}

@Model
final class CachedTrackingRecord {
    @Attribute(.unique) var id: String
    var flightId: String
    var payload: String

    init(id: String, flightId: String, payload: String) {
        self.id = id
        self.flightId = flightId
        self.payload = payload
    }
}

@Model
final class CachedProfileRecord {
    @Attribute(.unique) var id: String
    var payload: String

    init(id: String, payload: String) {
        self.id = id
        self.payload = payload
    }
}

/// A Gmail find. Local-only state (not a cache of server data): flight, date, route, PNR and subject, never the body.
@Model
final class GmailSuggestionRecord {
    @Attribute(.unique) var id: String
    var messageId: String
    var flight: String
    var date: String
    var fromIata: String?
    var toIata: String?
    var pnr: String?
    var subject: String
    var status: String
    var foundAt: Int64

    init(_ suggestion: GmailSuggestion) {
        id = suggestion.id
        messageId = suggestion.messageId
        flight = suggestion.flight
        date = suggestion.date.iso
        fromIata = suggestion.fromIata
        toIata = suggestion.toIata
        pnr = suggestion.pnr
        subject = suggestion.subject
        status = suggestion.status.rawValue
        foundAt = suggestion.foundAt
    }

    var suggestion: GmailSuggestion? {
        guard let day = LocalDate.parse(date) else { return nil }
        return GmailSuggestion(
            id: id, messageId: messageId, flight: flight, date: day, fromIata: fromIata, toIata: toIata, pnr: pnr,
            subject: subject, status: GmailSuggestionStatus(rawValue: status) ?? .pending, foundAt: foundAt
        )
    }
}

/// A Gmail message already read (or skipped), so later scans do not fetch it again.
@Model
final class GmailSeenMessageRecord {
    @Attribute(.unique) var messageId: String

    init(messageId: String) { self.messageId = messageId }
}

enum LocalCache {
    @MainActor
    static func save(flight: Flight, in context: ModelContext) {
        let payload = flightViewJSON(flight)
        if let existing = record(CachedFlightRecord.self, id: flight.id, in: context) {
            existing.payload = payload
        } else {
            context.insert(CachedFlightRecord(id: flight.id, payload: payload))
        }
        try? context.save()
    }

    @MainActor
    static func flight(id: String, in context: ModelContext) -> Flight? {
        guard let payload = record(CachedFlightRecord.self, id: id, in: context)?.payload,
              let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return FlyTrackCore.flight(from: object)
    }

    @MainActor
    static func save(tracking: TrackedFlight, in context: ModelContext) {
        let payload = trackingJSON(tracking)
        if let existing = record(CachedTrackingRecord.self, id: tracking.id, in: context) {
            existing.flightId = tracking.flightId
            existing.payload = payload
        } else {
            context.insert(CachedTrackingRecord(id: tracking.id, flightId: tracking.flightId, payload: payload))
        }
        try? context.save()
    }

    @MainActor
    static func trackingRows(in context: ModelContext) -> [TrackedFlight] {
        (try? context.fetch(FetchDescriptor<CachedTrackingRecord>()))?.compactMap { row in
            guard let data = row.payload.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            return trackedFlight(from: object)
        } ?? []
    }

    @MainActor
    static func deleteTracking(id: String, in context: ModelContext) {
        if let row = record(CachedTrackingRecord.self, id: id, in: context) {
            context.delete(row)
            try? context.save()
        }
    }

    @MainActor
    static func save(profile: UserProfile, in context: ModelContext) {
        var object: [String: Any] = ["id": profile.id, "plan": profile.plan.wire]
        if let name = profile.displayName { object["display_name"] = name }
        if let until = profile.proUntil {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime]
            object["pro_until"] = formatter.string(from: until)
        }
        let payload = String(data: (try? JSONSerialization.data(withJSONObject: object)) ?? Data(), encoding: .utf8) ?? "{}"
        if let existing = record(CachedProfileRecord.self, id: profile.id, in: context) {
            existing.payload = payload
        } else {
            context.insert(CachedProfileRecord(id: profile.id, payload: payload))
        }
        try? context.save()
    }

    @MainActor
    static func profile(in context: ModelContext) -> UserProfile? {
        let rows = (try? context.fetch(FetchDescriptor<CachedProfileRecord>())) ?? []
        guard let payload = rows.first?.payload, let data = payload.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return FlyTrackCore.profile(from: object)
    }

    /// Sign-out or a switch to another HeyGate user. Gmail suggestions and seen ids belong to that user too.
    @MainActor
    static func wipeUserData(in context: ModelContext) {
        wipeGmail(in: context)
        for row in (try? context.fetch(FetchDescriptor<CachedTrackingRecord>())) ?? [] { context.delete(row) }
        for row in (try? context.fetch(FetchDescriptor<CachedFlightRecord>())) ?? [] { context.delete(row) }
        for row in (try? context.fetch(FetchDescriptor<CachedProfileRecord>())) ?? [] { context.delete(row) }
        try? context.save()
    }

    @MainActor
    static func wipeGmail(in context: ModelContext) {
        for row in (try? context.fetch(FetchDescriptor<GmailSuggestionRecord>())) ?? [] { context.delete(row) }
        for row in (try? context.fetch(FetchDescriptor<GmailSeenMessageRecord>())) ?? [] { context.delete(row) }
        try? context.save()
    }

    /// Every tracked flight as flight number + date, archived included: Gmail should not suggest those again.
    @MainActor
    static func trackedKeys(in context: ModelContext) -> Set<TrackedKey> {
        Set(trackingRows(in: context).compactMap { tracking in
            flight(id: tracking.flightId, in: context).map {
                TrackedKey(flightCompact: GmailSuggestions.compact($0.carrierIata + $0.flightNumber), date: $0.flightDate)
            }
        })
    }

    @MainActor
    private static func record<T: PersistentModel>(_ type: T.Type, id: String, in context: ModelContext) -> T? {
        let rows = (try? context.fetch(FetchDescriptor<T>())) ?? []
        return rows.first { item in
            if let flight = item as? CachedFlightRecord { return flight.id == id }
            if let tracking = item as? CachedTrackingRecord { return tracking.id == id }
            if let profile = item as? CachedProfileRecord { return profile.id == id }
            return false
        }
    }
}

private func trackingJSON(_ tracking: TrackedFlight) -> String {
    var prefs: [String: Bool] = [:]
    for (type, enabled) in tracking.notifyPrefs { prefs[type.wire] = enabled }
    var object: [String: Any] = [
        "id": tracking.id,
        "user_id": tracking.userId,
        "flight_id": tracking.flightId,
        "source": tracking.source.wire,
        "notify_prefs": prefs,
    ]
    if let pnr = tracking.pnr { object["pnr"] = pnr }
    if let seat = tracking.seat { object["seat"] = seat }
    if let cabin = tracking.cabin { object["cabin"] = cabin.wire }
    if let note = tracking.note { object["note"] = note }
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    if let archived = tracking.archivedAt { object["archived_at"] = formatter.string(from: archived) }
    if let created = tracking.createdAt { object["created_at"] = formatter.string(from: created) }
    let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    return String(data: data, encoding: .utf8) ?? "{}"
}
