import Foundation

// P4 section 2 (docs/P4_CONTRACTS.md, notes in section 7): TripIt sync, Pro only. The app opens the `authorize_url`
// from `tripit-connect` in ASWebAuthenticationSession with the callback scheme `heygate`; `tripit-callback` ends at
// `heygate://tripit/connected` or `heygate://tripit/failed?error=...`. Settings shows the status, "Sync now" and
// "Disconnect". Same rules as Android's `feature/tripit`.

public enum TripItContract {
    public static let functionConnect = "tripit-connect"
    public static let functionSync = "tripit-sync"
    public static let functionDisconnect = "tripit-disconnect"
    public static let rpcStatus = "tripit_status"
    /// ASWebAuthenticationSession's `callbackURLScheme`.
    public static let callbackScheme = "heygate"
    public static let callbackHost = "tripit"
    /// Only TripIt's own sign-in page is opened.
    public static let authorizeHost = "www.tripit.com"
}

/// `tripit_status()`.
public struct TripItStatus: Hashable, Sendable {
    public var connected: Bool
    public var lastSyncAt: Date?
    /// `tripit_revoked` | `tripit_error` from the last sync, nil when it worked.
    public var lastError: TripItSyncProblem?

    public init(connected: Bool, lastSyncAt: Date? = nil, lastError: TripItSyncProblem? = nil) {
        self.connected = connected
        self.lastSyncAt = lastSyncAt
        self.lastError = lastError
    }

    public static let disconnected = TripItStatus(connected: false)
}

/// What went wrong with TripIt itself (not with the phone).
public enum TripItSyncProblem: String, Sendable, Equatable {
    /// The user revoked access in TripIt: offer "Connect again".
    case revoked = "tripit_revoked"
    case tripitError = "tripit_error"
}

public func parseTripItStatus(_ data: Data) -> TripItStatus? {
    if rpcIsNull(data) { return .disconnected }
    guard let o = JSON.object(from: data) else { return nil }
    let connected = JSON.bool(o["connected"]) ?? false
    return TripItStatus(
        connected: connected,
        lastSyncAt: connected ? parseInstant(JSON.string(o["last_sync_at"])) : nil,
        lastError: connected ? JSON.string(o["last_error"]).flatMap(TripItSyncProblem.init(rawValue:)) : nil
    )
}

/// `tripit-sync`'s `{segments, added, existing, not_found, skipped, errors}`.
public struct TripItSyncSummary: Hashable, Sendable {
    public var segments: Int
    public var added: Int
    public var existing: Int
    public var notFound: Int
    public var skipped: Int
    public var errors: Int

    public init(segments: Int, added: Int, existing: Int, notFound: Int, skipped: Int, errors: Int) {
        self.segments = segments
        self.added = added
        self.existing = existing
        self.notFound = notFound
        self.skipped = skipped
        self.errors = errors
    }
}

public func parseTripItSyncSummary(_ data: Data) -> TripItSyncSummary? {
    guard let o = JSON.object(from: data), let segments = JSON.int(o["segments"]) else { return nil }
    return TripItSyncSummary(
        segments: segments,
        added: JSON.int(o["added"]) ?? 0,
        existing: JSON.int(o["existing"]) ?? 0,
        notFound: JSON.int(o["not_found"]) ?? 0,
        skipped: JSON.int(o["skipped"]) ?? 0,
        errors: JSON.int(o["errors"]) ?? 0
    )
}

/// `tripit-connect`'s `authorize_url`. Nil unless it is an https URL on TripIt's host: the app never opens anything
/// else in the sign-in sheet.
public func parseTripItAuthorizeURL(_ data: Data) -> URL? {
    guard let o = JSON.object(from: data), let text = JSON.string(o["authorize_url"]),
          let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
          url.scheme?.lowercased() == "https", url.host?.lowercased() == TripItContract.authorizeHost else { return nil }
    return url
}

/// Errors of the TripIt functions (body `{ "error": ... }`).
public enum TripItFailure: Error, Equatable, Sendable {
    case proRequired
    case notConnected
    case revoked
    case tripitError
    /// No consumer key on the server yet: hide or disable the row.
    case notConfigured
    case offline
    /// The build has no backend keys.
    case backendMissing
    case failed
}

public func tripItFailure(status: Int, body: Data?) -> TripItFailure {
    let code = body.flatMap(JSON.object(from:)).flatMap { JSON.string($0["error"]) }
    switch code {
    case "pro_required": return .proRequired
    case "not_connected": return .notConnected
    case "tripit_revoked": return .revoked
    case "tripit_error": return .tripitError
    case "not_configured": return .notConfigured
    case .some: return .failed
    case nil: break
    }
    switch status {
    case 402: return .proRequired
    case 409: return .notConnected
    case 502: return .tripitError
    case 503: return .notConfigured
    default: return .failed
    }
}

/// How `tripit-callback` sent the user back.
public enum TripItCallback: Equatable, Sendable {
    case connected
    case failed(TripItCallbackError)
}

public enum TripItCallbackError: String, Sendable, Equatable {
    /// The user said no in TripIt.
    case denied
    /// The request token timed out: start again.
    case expired
    case tripitError = "tripit_error"
    case notConfigured = "not_configured"
    case internalError = "internal"
}

/// `heygate://tripit/connected` or `heygate://tripit/failed?error=denied|expired|tripit_error|not_configured|internal`.
/// Nil for any other URL. An unknown error value reads as `internal`.
public func parseTripItCallback(_ url: URL) -> TripItCallback? {
    guard url.scheme?.lowercased() == TripItContract.callbackScheme,
          url.host?.lowercased() == TripItContract.callbackHost else { return nil }
    let step = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
    switch step {
    case "connected": return .connected
    case "failed":
        let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "error" })?.value?.lowercased()
        return .failed(value.flatMap(TripItCallbackError.init(rawValue:)) ?? .internalError)
    default: return nil
    }
}

/// The TripIt backend (app: `LiveTripIt`).
@MainActor
public protocol TripItRepository: AnyObject {
    func status() async -> Result<TripItStatus, TripItFailure>
    func authorizeURL() async -> Result<URL, TripItFailure>
    func sync() async -> Result<TripItSyncSummary, TripItFailure>
    func disconnect() async -> Result<Void, TripItFailure>
}

/// Opens TripIt's sign-in page and waits for the redirect back to the app (app: ASWebAuthenticationSession with
/// callback scheme `heygate`, an ephemeral session not needed: TripIt's own cookies help the user).
public enum TripItAuthorizationResult: Equatable, Sendable {
    case callback(URL)
    /// The user closed the sheet.
    case cancelled
    case failed
}

@MainActor
public protocol TripItAuthorizing: AnyObject {
    func authorize(url: URL, callbackScheme: String) async -> TripItAuthorizationResult
}

public enum TripItPhase: Equatable, Sendable {
    case loading
    /// The server has no TripIt consumer key: the row is disabled ("TripIt is coming soon").
    case unavailable
    case disconnected
    case connected
    /// Free user: the row leads to the paywall.
    case proRequired
    /// The status could not be read (offline or failed) and nothing is known yet.
    case unknown
}

public enum TripItMessage: Equatable, Sendable {
    case connected
    case synced(TripItSyncSummary)
    case disconnected
    case denied
    case expired
    case revoked
    case tripitError
    /// A connected user whose Pro ended tapped "Sync now": the row stays (Disconnect works without Pro).
    case proRequired
    case offline
    case failed
}

public struct TripItState: Equatable, Sendable {
    public var phase: TripItPhase = .loading
    public var status: TripItStatus = .disconnected
    public var busy = false
    public var syncing = false
    public var message: TripItMessage?

    public init() {}

    /// "Connect again" instead of the sync button: TripIt said the access was revoked.
    public var needsReconnect: Bool { phase == .connected && status.lastError == .revoked }
}

/// Settings → TripIt. Pro is checked by the server (`pro_required`); `isPro` only avoids a pointless call for free users.
@MainActor
public final class TripItModel {
    public private(set) var state = TripItState()
    public var onChange: ((TripItState) -> Void)?

    private let repository: TripItRepository
    private let authorizer: TripItAuthorizing
    private let isPro: @MainActor () async -> Bool
    /// After a connect, the first sync runs on the server in the background: the status is asked again after these
    /// delays so "Last synced" appears without a manual refresh.
    private let followUpDelays: [TimeInterval]
    private let sleep: @Sendable (TimeInterval) async -> Void

    public init(
        repository: TripItRepository, authorizer: TripItAuthorizing, isPro: @escaping @MainActor () async -> Bool,
        followUpDelays: [TimeInterval] = [3, 10],
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) }
    ) {
        self.repository = repository
        self.authorizer = authorizer
        self.isPro = isPro
        self.followUpDelays = followUpDelays
        self.sleep = sleep
    }

    public func load() async {
        switch await repository.status() {
        case .success(let status):
            state.status = status
            if status.connected {
                state.phase = .connected
            } else {
                let pro = await isPro()
                state.phase = pro ? .disconnected : .proRequired
            }
        case .failure(let failure):
            switch failure {
            case .notConfigured: state.phase = .unavailable
            case .proRequired: state.phase = .proRequired
            default: if state.phase == .loading { state.phase = .unknown }
            }
        }
        publish()
    }

    /// "Connect TripIt" / "Connect again".
    public func connect() async {
        guard begin() else { return }
        switch await repository.authorizeURL() {
        case .failure(let failure):
            finish(failureMessage(failure))
            return
        case .success(let url):
            switch await authorizer.authorize(url: url, callbackScheme: TripItContract.callbackScheme) {
            case .cancelled:
                finish(nil)
            case .failed:
                finish(.failed)
            case .callback(let callbackURL):
                await handleCallback(callbackURL, ownBusy: true)
            }
        }
    }

    /// The deep link `heygate://tripit/...`, from the sign-in sheet or opened by the system (a browser that handed
    /// the redirect to the app). Returns false for other URLs.
    @discardableResult
    public func handle(_ url: URL) async -> Bool {
        guard parseTripItCallback(url) != nil else { return false }
        await handleCallback(url, ownBusy: false)
        return true
    }

    public func syncNow() async {
        guard begin() else { return }
        state.syncing = true
        publish()
        let result = await repository.sync()
        state.syncing = false
        switch result {
        case .success(let summary):
            await reloadStatus()
            finish(.synced(summary))
        case .failure(let failure):
            if failure == .revoked { state.status.lastError = .revoked }
            if failure == .notConnected {
                state.status = .disconnected
                state.phase = .disconnected
            }
            finish(failureMessage(failure))
        }
    }

    public func disconnect() async {
        guard begin() else { return }
        switch await repository.disconnect() {
        case .success:
            state.status = .disconnected
            let pro = await isPro()
            state.phase = pro ? .disconnected : .proRequired
            finish(.disconnected)
        case .failure(let failure):
            finish(failureMessage(failure))
        }
    }

    public func messageShown() {
        state.message = nil
        publish()
    }

    private func handleCallback(_ url: URL, ownBusy: Bool) async {
        if !ownBusy { state.busy = true }
        switch parseTripItCallback(url) {
        case .connected?:
            await reloadStatus()
            finish(.connected)
            for delay in followUpDelays where state.status.lastSyncAt == nil && state.phase == .connected {
                await sleep(delay)
                await reloadStatus()
                publish()
            }
        case .failed(let error)?:
            switch error {
            case .denied: finish(.denied)
            case .expired: finish(.expired)
            case .tripitError: finish(.tripitError)
            case .notConfigured:
                state.phase = .unavailable
                finish(nil)
            case .internalError: finish(.failed)
            }
        case nil:
            finish(.failed)
        }
    }

    private func reloadStatus() async {
        if case .success(let status) = await repository.status() {
            state.status = status
            if status.connected { state.phase = .connected } else if state.phase == .connected { state.phase = .disconnected }
        }
    }

    private func failureMessage(_ failure: TripItFailure) -> TripItMessage? {
        switch failure {
        case .proRequired:
            // `tripit-disconnect` works without Pro: a connected user keeps the row and its Disconnect button.
            if state.phase == .connected { return .proRequired }
            state.phase = .proRequired
            return nil
        case .notConfigured:
            state.phase = .unavailable
            return nil
        case .revoked: return .revoked
        case .tripitError: return .tripitError
        case .offline: return .offline
        case .notConnected, .backendMissing, .failed: return .failed
        }
    }

    private func begin() -> Bool {
        if state.busy { return false }
        state.busy = true
        state.message = nil
        publish()
        return true
    }

    private func finish(_ message: TripItMessage?) {
        state.busy = false
        state.message = message
        publish()
    }

    private func publish() { onChange?(state) }
}
