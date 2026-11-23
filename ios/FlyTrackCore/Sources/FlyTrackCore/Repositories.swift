import Foundation

@MainActor
public protocol FlightRepository: AnyObject {
    func trackedFlights() async -> [TrackedFlightDetails]
    func trackedFlight(flightId: String) async -> TrackedFlightDetails?
    func flight(id: String) async -> Flight?
    func search(flightText: String, date: LocalDate) async -> SearchResult
    func track(flightId: String, extras: TrackExtras) async -> TrackResult
    func untrack(flightId: String) async -> ActionResult
    func archive(flightId: String, archived: Bool) async -> ActionResult
    func updateNotifyPrefs(flightId: String, prefs: [AlertType: Bool]) async -> ActionResult
    func updateTrackingDetails(flightId: String, extras: TrackExtras) async -> ActionResult
    func refresh() async -> ActionResult
    func applyServerSync(flightId: String, compactFlightJSON: String?) async -> ActionResult
}

@MainActor
public protocol AuthRepository: AnyObject {
    func session() async -> SessionState
    func ensureSignedIn() async -> AuthResult
    func linkGoogle(idToken: String, nonce: String?) async -> AuthResult
    func signInWithGoogle(idToken: String, nonce: String?) async -> AuthResult
    func signOut() async -> AuthResult
}

@MainActor
public protocol ProfileRepository: AnyObject {
    func profile() async -> UserProfile?
    func refresh() async -> ActionResult
}

@MainActor
public protocol SettingsStore: AnyObject {
    func cloudTicketParsingConsent() async -> Bool
    func setCloudTicketParsingConsent(_ granted: Bool) async
    func notificationPermissionAsked() async -> Bool
    func setNotificationPermissionAsked(_ asked: Bool) async
}

@MainActor
public protocol DefaultAlertPreferences: AnyObject {
    func muted() async -> Set<AlertType>
    func setMuted(_ types: Set<AlertType>, muted: Bool) async
}

@MainActor
public protocol PushControlling: AnyObject {
    func availability() async -> PushAvailability
    func beforeSignOut() async
}

public enum PushAvailability: String, Sendable, Equatable {
    case checking, notConfigured, ready
}

public struct NotificationHealth: Equatable, Sendable {
    public enum Authorization: String, Sendable, Equatable {
        case notDetermined, denied, authorized, provisional
    }

    public var authorization: Authorization
    /// Time Sensitive permission. This is the iOS stand-in for the flight_critical channel.
    public var timeSensitiveAllowed: Bool
    public var alertsAllowed: Bool

    public init(authorization: Authorization, timeSensitiveAllowed: Bool, alertsAllowed: Bool) {
        self.authorization = authorization
        self.timeSensitiveAllowed = timeSensitiveAllowed
        self.alertsAllowed = alertsAllowed
    }

    public static let unknown = NotificationHealth(authorization: .notDetermined, timeSensitiveAllowed: false, alertsAllowed: false)
}

@MainActor
public protocol NotificationHealthSource: AnyObject {
    func read() async -> NotificationHealth
}

public struct ExtractRequest: Equatable, Sendable {
    public var uris: [String]
    public var mimeType: String?
    public var text: String?

    public init(uris: [String] = [], mimeType: String? = nil, text: String? = nil) {
        self.uris = uris
        self.mimeType = mimeType
        self.text = text
    }
}

public struct ExtractedTicket: Equatable, Sendable {
    public var text: String
    public var source: TrackSource
    public var truncated: Bool

    public init(text: String, source: TrackSource, truncated: Bool) {
        self.text = text
        self.source = source
        self.truncated = truncated
    }
}

@MainActor
public protocol TicketTextExtracting: AnyObject {
    func extract(_ request: ExtractRequest) async throws -> ExtractedTicket
}

@MainActor
public protocol CloudTicketParsing: AnyObject {
    func parse(text: String) async -> CloudParseResult
}

public struct SharedContent: Equatable, Sendable {
    public var uris: [String]
    public var mimeType: String?
    public var text: String?

    public init(uris: [String] = [], mimeType: String? = nil, text: String? = nil) {
        self.uris = uris
        self.mimeType = mimeType
        self.text = text
    }

    public var isEmpty: Bool {
        uris.allSatisfy { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } && (text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }
}

@MainActor
public final class SharedContentHolder {
    public private(set) var pending: SharedContent?

    public init() {}

    public func offer(_ content: SharedContent) { pending = content }

    public func consume() -> SharedContent? {
        defer { pending = nil }
        return pending
    }
}

public enum LaunchRequest: Equatable, Sendable {
    case openFlight(String)
    case importShared(SharedContent)
}

private let uuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

public func isFlightUUID(_ value: String) -> Bool {
    value.range(of: uuidPattern, options: .regularExpression) != nil
}

/// A push tap (`flight_id`) or `heygate://flight/<uuid>` opens that flight. A share opens ticket import.
public func resolveLaunch(url: URL?, flightId: String?, shared: SharedContent?) -> LaunchRequest? {
    if let flightId = flightId?.trimmingCharacters(in: .whitespacesAndNewlines), isFlightUUID(flightId) {
        return .openFlight(flightId)
    }
    if let url, let id = flightIdFrom(url: url) {
        return .openFlight(id)
    }
    if let shared, !shared.isEmpty {
        return .importShared(shared)
    }
    return nil
}

public func flightIdFrom(url: URL) -> String? {
    guard url.scheme?.lowercased() == "heygate" else { return nil }
    let host = url.host?.lowercased()
    let parts = url.path.split(separator: "/").map(String.init)
    let candidate: String?
    if host == "flight" {
        candidate = parts.first
    } else if parts.first == "flight" {
        candidate = parts.dropFirst().first
    } else {
        candidate = nil
    }
    guard let candidate, isFlightUUID(candidate) else { return nil }
    return candidate
}
