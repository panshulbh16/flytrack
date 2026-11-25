import Foundation

/// How a Gmail call failed. The app's `GmailAPIClient` maps HTTP results onto these:
/// 401 -> unauthorized; revoked, scope missing, or 403 insufficientPermissions/forbidden -> accessLost;
/// 429, 403 rateLimitExceeded/userRateLimitExceeded/dailyLimitExceeded, or 5xx -> rateLimited;
/// no connection -> offline; anything else (404, undecodable JSON) -> other.
public enum GmailFailure: Error, Equatable {
    case unauthorized, accessLost, rateLimited, offline, other
}

/// The four Gmail GETs. Throws `GmailFailure`. `message` uses `format=full`, which includes `internalDate`.
public protocol GmailAPI: AnyObject, Sendable {
    func profile(token: String) async throws -> GmailProfile
    func list(token: String, query: String, pageToken: String?) async throws -> GmailListResponse
    func message(token: String, id: String) async throws -> GmailMessage
    func attachment(token: String, messageId: String, attachmentId: String) async throws -> Data
}

public enum GmailTokenResult: Equatable, Sendable {
    case granted(String), needsUser, notConfigured, offline, failed
}

/// Hands out a Gmail access token without showing UI. The app stores no token.
@MainActor
public protocol GmailTokenProviding: AnyObject {
    func silentToken() async -> GmailTokenResult
    /// A fresh token after a 401 (iOS: `refreshTokensIfNeeded`).
    func refresh() async -> GmailTokenResult
}

@MainActor
public protocol GmailSuggestionStoring: AnyObject {
    func all() async -> [GmailSuggestion]
    func get(_ id: String) async -> GmailSuggestion?
    func insert(_ suggestions: [GmailSuggestion]) async
    func setStatus(_ ids: [String], _ status: GmailSuggestionStatus) async
    func delete(_ ids: [String]) async
    /// The subset of `messageIds` already marked seen.
    func seen(_ messageIds: [String]) async -> Set<String>
    func markSeen(_ messageIds: [String]) async
    func clear() async
}

@MainActor
public protocol GmailSettingsStoring: AnyObject {
    func gmailAccountEmail() async -> String?
    func setGmailAccountEmail(_ email: String?) async
    func gmailAutoCheck() async -> Bool
    func setGmailAutoCheck(_ enabled: Bool) async
    /// Epoch seconds of the last completed scan's start.
    func gmailLastCheck() async -> Int64?
    func setGmailLastCheck(_ value: Int64?) async
    func gmailNeedsReconnect() async -> Bool
    func setGmailNeedsReconnect(_ value: Bool) async
    func gmailLastAppOpenCheck() async -> Int64?
    func setGmailLastAppOpenCheck(_ value: Int64?) async
}

@MainActor
public protocol TrackedKeysProviding: AnyObject {
    func trackedKeys() async -> Set<TrackedKey>
}

/// Reads a PDF attachment with the on-device reader. Empty text when it cannot be read.
@MainActor
public protocol PDFTextReading: AnyObject {
    func read(_ data: Data) async -> String
}

public enum GmailScanResult: Equatable, Sendable {
    case completed, partial, offline, needsReconnect, notConnected
}

public struct GmailScanOutcome: Equatable, Sendable {
    public var result: GmailScanResult
    public var newSuggestions: [GmailSuggestion]

    public init(result: GmailScanResult, newSuggestions: [GmailSuggestion]) {
        self.result = result
        self.newSuggestions = newSuggestions
    }
}
