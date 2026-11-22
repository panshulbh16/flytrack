import Foundation

public enum FlightsMessage: Equatable, Sendable {
    case archived(String)
    case untracked(String)
    case offline
    case refreshFailed
    case actionFailed
    case trackingLimitReached
}

public struct FlightsState: Equatable, Sendable {
    public var isLoading: Bool
    public var isRefreshing: Bool
    public var isBackendConfigured: Bool
    public var next: NextFlight?
    public var upcoming: [FlightCard]
    public var past: [FlightCard]
    public var message: FlightsMessage?
    public var isEmpty: Bool { !isLoading && upcoming.isEmpty && past.isEmpty }

    public init(
        isLoading: Bool = true, isRefreshing: Bool = false, isBackendConfigured: Bool = true,
        next: NextFlight? = nil, upcoming: [FlightCard] = [], past: [FlightCard] = [], message: FlightsMessage? = nil
    ) {
        self.isLoading = isLoading
        self.isRefreshing = isRefreshing
        self.isBackendConfigured = isBackendConfigured
        self.next = next
        self.upcoming = upcoming
        self.past = past
        self.message = message
    }
}

@MainActor
public final class FlightsModel {
    public private(set) var state = FlightsState()
    public var onChange: ((FlightsState) -> Void)?

    private let flights: FlightRepository
    private let clock: FlyClock
    private let changes: FlightChangeTracker
    private var lastRefresh: Date
    private var untracked: [String: TrackedFlight] = [:]

    public init(flights: FlightRepository, clock: FlyClock, changes: FlightChangeTracker = FlightChangeTracker()) {
        self.flights = flights
        self.clock = clock
        self.changes = changes
        lastRefresh = clock.now()
    }

    public func reload(session: SessionState) async {
        let tracked = await flights.trackedFlights()
        let now = clock.now()
        let sections = flightSections(tracked, now: now) { changes.record($0, now: now) }
        state.isLoading = false
        state.isBackendConfigured = session != .notConfigured
        state.next = sections.next
        state.upcoming = sections.upcoming
        state.past = sections.past
        publish()
    }

    public func refresh(session: SessionState) async {
        if state.isRefreshing { return }
        state.isRefreshing = true
        publish()
        let result = await flights.refresh()
        lastRefresh = clock.now()
        state.isRefreshing = false
        switch result {
        case .success, .notConfigured:
            break
        case .offline:
            state.message = .offline
        default:
            state.message = .refreshFailed
        }
        await reload(session: session)
    }

    /// Quiet refresh when the list comes back, unless one just happened.
    public func resume(session: SessionState) async {
        let now = clock.now()
        if now.timeIntervalSince(lastRefresh) < 60 { return }
        lastRefresh = now
        _ = await flights.refresh()
        await reload(session: session)
    }

    public func archive(flightId: String, session: SessionState) async {
        let result = await flights.archive(flightId: flightId, archived: true)
        state.message = result.isSuccess ? .archived(flightId) : failure(result)
        await reload(session: session)
    }

    public func untrack(flightId: String, session: SessionState) async {
        let before = await flights.trackedFlights().first { $0.flight.id == flightId }?.tracking
        let result = await flights.untrack(flightId: flightId)
        if result.isSuccess {
            if let before { untracked[flightId] = before }
            state.message = .untracked(flightId)
        } else {
            state.message = failure(result)
        }
        await reload(session: session)
    }

    public func undo(_ message: FlightsMessage, session: SessionState) async {
        switch message {
        case .archived(let flightId):
            let result = await flights.archive(flightId: flightId, archived: false)
            if !result.isSuccess { state.message = failure(result) }
        case .untracked(let flightId):
            await trackAgain(flightId)
        default:
            return
        }
        await reload(session: session)
    }

    public func clearMessage() {
        state.message = nil
        publish()
    }

    private func trackAgain(_ flightId: String) async {
        let before = untracked.removeValue(forKey: flightId)
        let extras = before.map { TrackExtras(pnr: $0.pnr, seat: $0.seat, cabin: $0.cabin, source: $0.source, note: $0.note) } ?? TrackExtras()
        switch await flights.track(flightId: flightId, extras: extras) {
        case .tracked, .alreadyTracked:
            if let prefs = before?.notifyPrefs, !prefs.isEmpty {
                if await flights.updateNotifyPrefs(flightId: flightId, prefs: prefs).isSuccess == false {
                    state.message = .actionFailed
                }
            }
        case .trackingLimitReached:
            state.message = .trackingLimitReached
        case .offline:
            state.message = .offline
        case .notConfigured:
            break
        default:
            state.message = .actionFailed
        }
    }

    private func failure(_ result: ActionResult) -> FlightsMessage? {
        switch result {
        case .success, .notConfigured: return nil
        case .offline: return .offline
        case .trackingLimitReached: return .trackingLimitReached
        default: return .actionFailed
        }
    }

    private func publish() { onChange?(state) }
}

public enum FlightDetailMessage: String, Sendable, Equatable {
    case offline, refreshFailed, actionFailed, trackingLimitReached
}

public struct BookingEditor: Equatable, Sendable {
    public var pnr: String
    public var seat: String
    public var error: TrackField?
    public var isSaving: Bool
}

public struct FlightDetailState: Equatable, Sendable {
    public var isLoading: Bool
    public var detail: FlightDetailModel?
    public var isRefreshing: Bool
    public var isWorking: Bool
    public var editor: BookingEditor?
    public var message: FlightDetailMessage?
    public var isClosed: Bool

    public init(
        isLoading: Bool = true, detail: FlightDetailModel? = nil, isRefreshing: Bool = false,
        isWorking: Bool = false, editor: BookingEditor? = nil, message: FlightDetailMessage? = nil, isClosed: Bool = false
    ) {
        self.isLoading = isLoading
        self.detail = detail
        self.isRefreshing = isRefreshing
        self.isWorking = isWorking
        self.editor = editor
        self.message = message
        self.isClosed = isClosed
    }
}

@MainActor
public final class FlightDetailScreenModel {
    public private(set) var state = FlightDetailState()
    public var onChange: ((FlightDetailState) -> Void)?
    public let flightId: String

    private let flights: FlightRepository
    private let profiles: ProfileRepository
    private let clock: FlyClock
    private let changes: FlightChangeTracker

    public init(flightId: String, flights: FlightRepository, profiles: ProfileRepository, clock: FlyClock, changes: FlightChangeTracker = FlightChangeTracker()) {
        self.flightId = flightId
        self.flights = flights
        self.profiles = profiles
        self.clock = clock
        self.changes = changes
    }

    public func load() async {
        _ = await flights.applyServerSync(flightId: flightId, compactFlightJSON: nil)
        await render()
        state.isLoading = false
        publish()
    }

    public func refresh() async {
        state.isRefreshing = true
        publish()
        let result = await flights.refresh()
        state.isRefreshing = false
        if case .offline = result { state.message = .offline }
        else if case .failed = result { state.message = .refreshFailed }
        await render()
    }

    public func archive(_ archived: Bool) async {
        state.isWorking = true
        publish()
        let result = await flights.archive(flightId: flightId, archived: archived)
        state.isWorking = false
        if case .trackingLimitReached = result { state.message = .trackingLimitReached }
        else if !result.isSuccess, case .notConfigured = result { }
        else if !result.isSuccess { state.message = .actionFailed }
        await render()
    }

    public func untrack() async {
        state.isWorking = true
        publish()
        let result = await flights.untrack(flightId: flightId)
        state.isWorking = false
        if result.isSuccess { state.isClosed = true }
        else { state.message = .actionFailed }
        publish()
    }

    public func setAlert(_ group: AlertGroup, enabled: Bool) async {
        if group.proOnly, state.detail?.alerts.first(where: { $0.group == group })?.locked == true { return }
        _ = await flights.updateNotifyPrefs(flightId: flightId, prefs: group.prefs(enabled: enabled))
        await render()
    }

    public func beginEditing() async {
        let tracking = await flights.trackedFlight(flightId: flightId)?.tracking
        state.editor = BookingEditor(pnr: tracking?.pnr ?? "", seat: tracking?.seat ?? "", error: nil, isSaving: false)
        publish()
    }

    public func setEditorPnr(_ text: String) {
        state.editor?.pnr = sanitizePnrInput(text)
        state.editor?.error = nil
        publish()
    }

    public func setEditorSeat(_ text: String) {
        state.editor?.seat = sanitizeSeatInput(text)
        state.editor?.error = nil
        publish()
    }

    public func saveEditor() async {
        guard var editor = state.editor else { return }
        if !isPnrInputAcceptable(editor.pnr) {
            editor.error = .pnr
            state.editor = editor
            publish()
            return
        }
        editor.isSaving = true
        state.editor = editor
        publish()
        let extras = TrackExtras(pnr: editor.pnr.isEmpty ? nil : editor.pnr, seat: editor.seat.isEmpty ? nil : editor.seat)
        switch await flights.updateTrackingDetails(flightId: flightId, extras: extras) {
        case .success:
            state.editor = nil
        case .invalidInput(let field):
            editor.isSaving = false
            editor.error = field
            state.editor = editor
        default:
            editor.isSaving = false
            state.editor = editor
            state.message = .actionFailed
        }
        await render()
    }

    public func clearMessage() {
        state.message = nil
        publish()
    }

    private func render() async {
        let now = clock.now()
        let profile = await profiles.profile()
        let isPro = profile?.isPro(now: now) == true
        if let details = await flights.trackedFlight(flightId: flightId) {
            let fields = changes.record(details.flight, now: now)
            state.detail = details.flight.detail(tracking: details.tracking, isPro: isPro, now: now, newFields: fields)
        } else if let flight = await flights.flight(id: flightId) {
            state.detail = flight.detail(tracking: nil, isPro: isPro, now: now)
        } else {
            state.detail = nil
        }
        state.isLoading = false
        publish()
    }

    private func publish() { onChange?(state) }
}

public enum AccountKind: Equatable, Sendable {
    case loading
    case notConfigured
    case signedOut
    case anonymous(userId: String)
    case google(email: String?)
}

public enum SettingsMessage: String, Sendable, Equatable {
    case noGoogleCredential, googleNotConfigured, googleInUse, linked, offline, failed, signedOut
}

public struct SettingsState: Equatable, Sendable {
    public var account: AccountKind
    public var linking: Bool
    public var googleConfigured: Bool
    public var alerts: [AlertToggle]
    public var cloudConsent: Bool
    public var health: NotificationHealth
    public var push: PushAvailability
    public var versionName: String
    public var message: SettingsMessage?
    public var pendingGoogleToken: String?

    public init(
        account: AccountKind = .loading, linking: Bool = false, googleConfigured: Bool = false,
        alerts: [AlertToggle] = [], cloudConsent: Bool = false, health: NotificationHealth = .unknown,
        push: PushAvailability = .checking, versionName: String = "", message: SettingsMessage? = nil,
        pendingGoogleToken: String? = nil
    ) {
        self.account = account
        self.linking = linking
        self.googleConfigured = googleConfigured
        self.alerts = alerts
        self.cloudConsent = cloudConsent
        self.health = health
        self.push = push
        self.versionName = versionName
        self.message = message
        self.pendingGoogleToken = pendingGoogleToken
    }
}

@MainActor
public final class SettingsModel {
    public private(set) var state: SettingsState
    public var onChange: ((SettingsState) -> Void)?

    private let auth: AuthRepository
    private let profiles: ProfileRepository
    private let settings: SettingsStore
    private let alerts: DefaultAlertPreferences
    private let healthSource: NotificationHealthSource
    private let push: PushControlling
    private let clock: FlyClock
    private let googleConfigured: Bool

    public init(
        auth: AuthRepository, profiles: ProfileRepository, settings: SettingsStore,
        alerts: DefaultAlertPreferences, health: NotificationHealthSource, push: PushControlling,
        clock: FlyClock, googleConfigured: Bool, versionName: String
    ) {
        self.auth = auth
        self.profiles = profiles
        self.settings = settings
        self.alerts = alerts
        self.healthSource = health
        self.push = push
        self.clock = clock
        self.googleConfigured = googleConfigured
        state = SettingsState(googleConfigured: googleConfigured, versionName: versionName)
    }

    public func load() async {
        _ = await profiles.refresh()
        await render()
    }

    public func refreshHealth() async {
        state.health = await healthSource.read()
        state.push = await push.availability()
        publish()
    }

    /// False when Google sign-in is not configured: the caller must not open the Google sheet.
    public func beginGoogleLink() -> Bool {
        if !googleConfigured {
            state.message = .googleNotConfigured
            publish()
            return false
        }
        if state.linking { return false }
        state.linking = true
        state.message = nil
        publish()
        return true
    }

    public func cancelGoogleLink() {
        state.linking = false
        publish()
    }

    public func googleLinkFailed(missingCredential: Bool) {
        state.linking = false
        state.message = missingCredential ? .noGoogleCredential : .failed
        publish()
    }

    public func linkGoogle(idToken: String) async {
        let result = await auth.linkGoogle(idToken: idToken, nonce: nil)
        state.linking = false
        state.pendingGoogleToken = result == .googleAccountInUse ? idToken : nil
        state.message = message(for: result, success: .linked)
        await render()
    }

    public func signInWithPendingGoogle() async {
        guard let token = state.pendingGoogleToken else { return }
        state.linking = true
        publish()
        await push.beforeSignOut()
        let result = await auth.signInWithGoogle(idToken: token, nonce: nil)
        state.linking = false
        state.pendingGoogleToken = nil
        state.message = message(for: result, success: .linked)
        await render()
    }

    public func signOut() async {
        if state.linking { return }
        state.linking = true
        publish()
        await push.beforeSignOut()
        let result = await auth.signOut()
        state.linking = false
        state.message = result.isSuccess ? .signedOut : message(for: result, success: .signedOut)
        await render()
    }

    public func setAlert(_ group: AlertGroup, enabled: Bool) async {
        if group.proOnly, state.alerts.first(where: { $0.group == group })?.locked == true { return }
        await alerts.setMuted(Set(group.types), muted: !enabled)
        await render()
    }

    public func setCloudConsent(_ granted: Bool) async {
        await settings.setCloudTicketParsingConsent(granted)
        await render()
    }

    public func clearMessage() {
        state.message = nil
        publish()
    }

    private func render() async {
        let session = await auth.session()
        let profile = await profiles.profile()
        let consent = await settings.cloudTicketParsingConsent()
        let muted = await alerts.muted()
        state.account = account(session)
        state.googleConfigured = googleConfigured
        state.alerts = alertToggles(muted: muted, isPro: profile?.isPro(now: clock.now()) == true)
        state.cloudConsent = consent
        state.health = await healthSource.read()
        state.push = await push.availability()
        publish()
    }

    private func account(_ session: SessionState) -> AccountKind {
        switch session {
        case .loading: return .loading
        case .notConfigured: return .notConfigured
        case .signedOut: return .signedOut
        case .signedIn(let userId, true, _): return .anonymous(userId: userId)
        case .signedIn(_, false, let email): return .google(email: email)
        }
    }

    private func message(for result: AuthResult, success: SettingsMessage) -> SettingsMessage {
        switch result {
        case .success: return success
        case .offline: return .offline
        case .notConfigured: return .googleNotConfigured
        case .googleAccountInUse: return .googleInUse
        case .failed: return .failed
        }
    }

    private func publish() { onChange?(state) }
}

public enum ImportPhase: String, Sendable, Equatable { case idle, reading, choose, empty }

public enum ImportNotice: String, Sendable, Equatable {
    case truncated, cloudFailed, rateLimited, offline, notConfigured, nothingFound
}

public struct ImportState: Sendable {
    public var phase: ImportPhase
    public var flights: [ParsedFlight]
    public var selected: Int
    public var pnr: String?
    public var source: TrackSource
    public var consent: Bool
    public var busy: Bool
    public var notice: ImportNotice?
    public var parsed: (flight: String?, date: String?, pnr: String?, source: String?)?

    public var selectedFlight: ParsedFlight? { flights.indices.contains(selected) ? flights[selected] : nil }

    public init(
        phase: ImportPhase = .idle, flights: [ParsedFlight] = [], selected: Int = 0, pnr: String? = nil,
        source: TrackSource = .share, consent: Bool = false, busy: Bool = false, notice: ImportNotice? = nil,
        parsed: (flight: String?, date: String?, pnr: String?, source: String?)? = nil
    ) {
        self.phase = phase
        self.flights = flights
        self.selected = selected
        self.pnr = pnr
        self.source = source
        self.consent = consent
        self.busy = busy
        self.notice = notice
        self.parsed = parsed
    }
}

extension ImportState: Equatable {
    public static func == (lhs: ImportState, rhs: ImportState) -> Bool {
        lhs.phase == rhs.phase && lhs.flights == rhs.flights && lhs.selected == rhs.selected && lhs.pnr == rhs.pnr
            && lhs.source == rhs.source && lhs.consent == rhs.consent && lhs.busy == rhs.busy && lhs.notice == rhs.notice
            && lhs.parsed?.flight == rhs.parsed?.flight && lhs.parsed?.date == rhs.parsed?.date
            && lhs.parsed?.pnr == rhs.parsed?.pnr && lhs.parsed?.source == rhs.parsed?.source
    }
}

@MainActor
public final class ImportTicketModel {
    public private(set) var state = ImportState()
    public var onChange: ((ImportState) -> Void)?

    private let shared: SharedContentHolder
    private let extractor: TicketTextExtracting
    private let cloud: CloudTicketParsing
    private let settings: SettingsStore
    private let today: LocalDate
    private var extractedText = ""

    public init(
        shared: SharedContentHolder, extractor: TicketTextExtracting, cloud: CloudTicketParsing,
        settings: SettingsStore, today: LocalDate
    ) {
        self.shared = shared
        self.extractor = extractor
        self.cloud = cloud
        self.settings = settings
        self.today = today
    }

    public func start() async {
        state.consent = await settings.cloudTicketParsingConsent()
        publish()
        if let pending = shared.consume(), !pending.isEmpty {
            await read(ExtractRequest(uris: pending.uris, mimeType: pending.mimeType, text: pending.text))
        }
    }

    public func picked(uris: [String], mimeType: String?) async {
        if uris.isEmpty || state.phase == .reading { return }
        await read(ExtractRequest(uris: uris, mimeType: mimeType))
    }

    public func select(_ index: Int) {
        guard state.flights.indices.contains(index) else { return }
        state.selected = index
        publish()
    }

    public func setConsent(_ granted: Bool) async {
        await settings.setCloudTicketParsingConsent(granted)
        state.consent = granted
        publish()
    }

    public func tryCloud() async {
        if !state.consent || state.busy || extractedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return }
        if !state.flights.isEmpty { return }
        await readCloud()
    }

    public func continueWithSelection() {
        guard let flight = state.selectedFlight else { return }
        state.parsed = (flight.flight.display, flight.date?.iso, state.pnr, state.source.wire)
        publish()
    }

    private func read(_ request: ExtractRequest) async {
        state.phase = .reading
        state.busy = true
        state.notice = nil
        state.flights = []
        publish()
        let extracted: ExtractedTicket
        do {
            extracted = try await extractor.extract(request)
        } catch {
            state.phase = .empty
            state.busy = false
            state.notice = .nothingFound
            publish()
            return
        }
        extractedText = extracted.text
        let ticket = parseTicketText(extracted.text, today: today)
        show(ticket, source: extracted.source, truncated: extracted.truncated, fromCloud: false)
        if ticket.flights.isEmpty && state.consent && !extracted.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            await readCloud()
        }
    }

    private func readCloud() async {
        state.busy = true
        publish()
        switch await cloud.parse(text: String(extractedText.prefix(maxTicketCharacters))) {
        case .parsed(let ticket):
            show(ticket, source: state.source, truncated: false, fromCloud: true)
        case .rateLimited:
            state.busy = false
            state.notice = .rateLimited
            publish()
        case .notConfigured:
            state.busy = false
            state.notice = .notConfigured
            publish()
        case .offline:
            state.busy = false
            state.notice = .offline
            publish()
        case .failed:
            state.busy = false
            state.notice = .cloudFailed
            publish()
        }
    }

    private func show(_ ticket: ParsedTicket, source: TrackSource, truncated: Bool, fromCloud: Bool) {
        let notice: ImportNotice?
        if ticket.flights.isEmpty {
            notice = fromCloud ? .nothingFound : nil
        } else if truncated {
            notice = .truncated
        } else {
            notice = nil
        }
        state.phase = ticket.flights.isEmpty ? .empty : .choose
        state.flights = ticket.flights
        state.selected = 0
        state.pnr = ticket.pnr ?? state.pnr
        state.source = source
        state.busy = false
        state.notice = notice
        publish()
    }

    private func publish() { onChange?(state) }
}
