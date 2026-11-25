import Foundation

public enum GmailSuggestionStatus: String, Sendable, Codable {
    case pending, dismissed, added
}

/// A flight found in the user's Gmail, waiting for them to track or dismiss it. Kept on the phone only.
public struct GmailSuggestion: Hashable, Sendable {
    /// "6E2341_2026-12-03": see `GmailSuggestions.suggestionId`.
    public var id: String
    public var messageId: String
    /// Display form, "6E 2341".
    public var flight: String
    public var date: LocalDate
    public var fromIata: String?
    public var toIata: String?
    public var pnr: String?
    public var subject: String
    public var status: GmailSuggestionStatus
    /// Epoch milliseconds.
    public var foundAt: Int64

    public init(
        id: String, messageId: String, flight: String, date: LocalDate, fromIata: String?, toIata: String?,
        pnr: String?, subject: String, status: GmailSuggestionStatus, foundAt: Int64
    ) {
        self.id = id
        self.messageId = messageId
        self.flight = flight
        self.date = date
        self.fromIata = fromIata
        self.toIata = toIata
        self.pnr = pnr
        self.subject = subject
        self.status = status
        self.foundAt = foundAt
    }
}

/// A flight the user already tracks: compact flight number ("6E2341") and date.
public struct TrackedKey: Hashable, Sendable {
    public var flightCompact: String
    public var date: LocalDate

    public init(flightCompact: String, date: LocalDate) {
        self.flightCompact = flightCompact
        self.date = date
    }
}

// Gmail API JSON: only the fields the import reads. Unknown keys are ignored by JSONDecoder.
// The decoders live in extensions so the memberwise initialisers stay available to tests.

public struct GmailListResponse: Decodable, Sendable {
    public var messages: [GmailMessageRef]
    public var nextPageToken: String?

    private enum CodingKeys: String, CodingKey { case messages, nextPageToken }
}

public struct GmailMessageRef: Decodable, Sendable {
    public var id: String
}

public struct GmailMessage: Decodable, Sendable {
    public var id: String
    /// When Gmail received the email: epoch ms as a decimal string. Task 10's scanner reads it.
    public var internalDate: String?
    public var payload: GmailPart?
}

public struct GmailPart: Decodable, Sendable {
    public var mimeType: String?
    public var filename: String?
    public var headers: [GmailHeader]
    public var body: GmailBody?
    public var parts: [GmailPart]

    private enum CodingKeys: String, CodingKey { case mimeType, filename, headers, body, parts }
}

public struct GmailHeader: Decodable, Sendable {
    public var name: String
    public var value: String
}

public struct GmailBody: Decodable, Sendable {
    public var size: Int64
    public var data: String?
    public var attachmentId: String?

    private enum CodingKeys: String, CodingKey { case size, data, attachmentId }
}

public struct GmailProfile: Decodable, Sendable {
    public var emailAddress: String
}

extension GmailListResponse {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        messages = try container.decodeIfPresent([GmailMessageRef].self, forKey: .messages) ?? []
        nextPageToken = try container.decodeIfPresent(String.self, forKey: .nextPageToken)
    }
}

extension GmailPart {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        mimeType = try container.decodeIfPresent(String.self, forKey: .mimeType)
        filename = try container.decodeIfPresent(String.self, forKey: .filename)
        headers = try container.decodeIfPresent([GmailHeader].self, forKey: .headers) ?? []
        body = try container.decodeIfPresent(GmailBody.self, forKey: .body)
        parts = try container.decodeIfPresent([GmailPart].self, forKey: .parts) ?? []
    }
}

extension GmailBody {
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        size = try container.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        data = try container.decodeIfPresent(String.self, forKey: .data)
        attachmentId = try container.decodeIfPresent(String.self, forKey: .attachmentId)
    }
}
