import Foundation

/// Delivery receipts (P2 contract section 3): the app tells `ack-push` when an alert reached the phone or when it
/// was opened, whichever happens first. Fire-and-forget; every failure is ignored.
public enum PushAckContract {
    public static let function = "ack-push"
}

/// `flight_events.id` is a positive bigint; ack-push accepts at most 15 digits. Nil for anything else.
public func parsePushEventId(_ value: String?) -> Int64? {
    guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), (1...15).contains(text.count),
          text.allSatisfy({ $0.isASCII && $0.isNumber }), let id = Int64(text), id > 0 else { return nil }
    return id
}

/// Body of `POST /functions/v1/ack-push`: `{ "event_id": 123, "received_at": iso }`.
public struct PushAckRequest: Hashable, Sendable, Encodable {
    public var eventId: Int64
    public var receivedAt: Date

    public init(eventId: Int64, receivedAt: Date) {
        self.eventId = eventId
        self.receivedAt = receivedAt
    }

    public var receivedAtISO: String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: receivedAt)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(eventId, forKey: .eventId)
        try container.encode(receivedAtISO, forKey: .receivedAt)
    }

    private enum CodingKeys: String, CodingKey {
        case eventId = "event_id"
        case receivedAt = "received_at"
    }
}

/// Remembers which events were already acknowledged in this process, so "arrived" and "opened" send one request
/// between them. Bounded; the oldest is forgotten first.
public final class AckedEvents: @unchecked Sendable {
    private let lock = NSLock()
    private let capacity: Int
    private var order: [Int64] = []
    private var seen: Set<Int64> = []

    public init(capacity: Int = 256) {
        self.capacity = max(1, capacity)
    }

    /// True the first time `eventId` is offered.
    public func firstTime(_ eventId: Int64) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard seen.insert(eventId).inserted else { return false }
        order.append(eventId)
        if order.count > capacity {
            let oldest = order.removeFirst()
            seen.remove(oldest)
        }
        return true
    }
}

/// The receipts to send for a batch of alerts: the first sighting of each valid event id, with the earliest time
/// it was seen. `items` are (`event_id` from the payload, when the alert arrived).
public func pushAcks(for items: [(eventId: String?, receivedAt: Date)], acked: AckedEvents) -> [PushAckRequest] {
    var earliest: [Int64: Date] = [:]
    var order: [Int64] = []
    for item in items {
        guard let id = parsePushEventId(item.eventId) else { continue }
        if let known = earliest[id] {
            earliest[id] = min(known, item.receivedAt)
        } else {
            earliest[id] = item.receivedAt
            order.append(id)
        }
    }
    return order.compactMap { id in
        guard let at = earliest[id], acked.firstTime(id) else { return nil }
        return PushAckRequest(eventId: id, receivedAt: at)
    }
}

/// Sends receipts. The app's implementation calls the Edge Function with the user's JWT and ignores failures.
@MainActor
public protocol PushAckSending: AnyObject {
    func ack(_ requests: [PushAckRequest]) async
}
