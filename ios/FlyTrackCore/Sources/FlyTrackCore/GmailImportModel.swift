import Foundation

/// What the Gmail screen shows.
public enum GmailScanState: Equatable, Sendable {
    case notConnected
    case scanning
    case results
    /// Connected, but no pending suggestion is left to show.
    case noSuggestions
    /// The last scan stopped early (the 50-email read cap) and nothing is pending: "Scan again", not "no flights".
    case moreToRead
    case offline
    case needsReconnect
}

public struct GmailImportState: Equatable, Sendable {
    public var scan: GmailScanState
    public var email: String?
    public var autoCheck: Bool
    /// Pending, dated today or later, not already tracked; soonest first.
    public var suggestions: [GmailSuggestion]
    /// Connecting or disconnecting.
    public var busy: Bool
    public var connectFailed: Bool

    public init(
        scan: GmailScanState = .notConnected, email: String? = nil, autoCheck: Bool = true,
        suggestions: [GmailSuggestion] = [], busy: Bool = false, connectFailed: Bool = false
    ) {
        self.scan = scan
        self.email = email
        self.autoCheck = autoCheck
        self.suggestions = suggestions
        self.busy = busy
        self.connectFailed = connectFailed
    }
}

@MainActor
public protocol GmailScanning: AnyObject {
    func scan() async -> GmailScanOutcome
    func cleanup() async -> GmailCleanup
}

extension GmailScanner: GmailScanning {}

/// The phone-side pieces the Gmail UI drives: Google's consent sheet, local notifications and the background check.
@MainActor
public protocol GmailSystem: AnyObject {
    /// Shows Google's account picker and consent. `.granted` once the connected address is saved in settings.
    func connect() async -> GmailTokenResult
    /// Withdraws the Gmail grant from Google.
    func revoke() async
    func cancelNotifications(_ ids: [String]) async
    func cancelAllNotifications() async
    func setBackgroundChecks(_ on: Bool)
}

/// The Gmail screen, the Found in Gmail card on Flights and the Gmail section in Settings.
@MainActor
public final class GmailImportModel {
    public private(set) var state = GmailImportState()
    public var onChange: ((GmailImportState) -> Void)?

    private let scanner: GmailScanning
    private let store: GmailSuggestionStoring
    private let settings: GmailSettingsStoring
    private let tracked: TrackedKeysProviding
    private let system: GmailSystem
    private let today: () -> LocalDate
    /// The last scan ended `.partial`: unread booking emails remain.
    private var stoppedEarly = false

    public init(
        scanner: GmailScanning, store: GmailSuggestionStoring, settings: GmailSettingsStoring,
        tracked: TrackedKeysProviding, system: GmailSystem, today: @escaping () -> LocalDate
    ) {
        self.scanner = scanner
        self.store = store
        self.settings = settings
        self.tracked = tracked
        self.system = system
        self.today = today
    }

    /// Gmail screen: shows what is stored, then reads new mail. A scan from this screen always runs.
    public func start() async {
        await refresh()
        if state.scan == .results || state.scan == .noSuggestions || state.scan == .moreToRead { await scan() }
    }

    /// Reloads the connection and the pending suggestions without reading Gmail.
    public func refresh() async {
        let needsReconnect = await load()
        if state.scan != .scanning { state.scan = settled(needsReconnect: needsReconnect) }
        publish()
    }

    public func scan() async {
        if state.email == nil || state.scan == .scanning { return }
        state.scan = .scanning
        publish()
        let cleaned = await scanner.cleanup()
        let stale = cleaned.deleteIds + cleaned.markAddedIds
        if !stale.isEmpty { await system.cancelNotifications(stale) }
        let outcome = await scanner.scan()
        stoppedEarly = outcome.result == .partial
        let needsReconnect = await load() || outcome.result == .needsReconnect
        state.scan = outcome.result == .offline && state.email != nil ? .offline : settled(needsReconnect: needsReconnect)
        publish()
    }

    /// Connect, or Reconnect after access was lost.
    public func connect() async {
        if state.busy { return }
        state.busy = true
        state.connectFailed = false
        publish()
        let result = await system.connect()
        state.busy = false
        switch result {
        case .granted:
            await settings.setGmailNeedsReconnect(false)
            await refresh()
            system.setBackgroundChecks(state.autoCheck)
            await scan()
        case .needsUser:
            // The user closed Google's sheet.
            publish()
        case .notConfigured, .offline, .failed:
            state.connectFailed = true
            publish()
        }
    }

    public func dismiss(_ id: String) async {
        await store.setStatus([id], .dismissed)
        await system.cancelNotifications([id])
        state.suggestions.removeAll { $0.id == id }
        if state.scan == .results && state.suggestions.isEmpty { state.scan = settled(needsReconnect: false) }
        publish()
    }

    public func setAutoCheck(_ on: Bool) async {
        await settings.setGmailAutoCheck(on)
        state.autoCheck = on
        system.setBackgroundChecks(on && state.email != nil && state.scan != .needsReconnect)
        publish()
    }

    /// Revokes access and deletes everything Gmail-derived on this phone.
    public func disconnect() async {
        if state.busy { return }
        state.busy = true
        publish()
        await system.revoke()
        system.setBackgroundChecks(false)
        await system.cancelAllNotifications()
        // Before the store is wiped, so a scan still running fails its account check and writes nothing.
        await settings.setGmailAccountEmail(nil)
        await store.clear()
        await settings.setGmailLastCheck(nil)
        await settings.setGmailLastAppOpenCheck(nil)
        await settings.setGmailNeedsReconnect(false)
        await settings.setGmailAutoCheck(true)
        stoppedEarly = false
        state = GmailImportState()
        publish()
    }

    /// Returns whether access was lost.
    private func load() async -> Bool {
        state.email = await settings.gmailAccountEmail()
        state.autoCheck = await settings.gmailAutoCheck()
        if state.email == nil {
            state.suggestions = []
            return false
        }
        state.suggestions = GmailSuggestions.visible(await store.all(), today: today(), tracked: await tracked.trackedKeys())
        return await settings.gmailNeedsReconnect()
    }

    private func settled(needsReconnect: Bool) -> GmailScanState {
        if state.email == nil { return .notConnected }
        if needsReconnect { return .needsReconnect }
        if !state.suggestions.isEmpty { return .results }
        // Unread booking emails remain after a stopped scan, so "no upcoming flights" would be wrong.
        return stoppedEarly ? .moreToRead : .noSuggestions
    }

    private func publish() { onChange?(state) }
}
