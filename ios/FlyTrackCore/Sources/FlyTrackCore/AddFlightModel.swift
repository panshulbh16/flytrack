import Foundation

public struct AddFlightPrefill: Hashable, Sendable {
    public var flight: String?
    public var date: String?
    public var pnr: String?
    public var source: TrackSource?
    public var seat: String?
    /// Set when Add flight was opened from a Gmail suggestion; marked added once the flight is tracked.
    public var gmailSuggestionId: String?

    public init(
        flight: String? = nil, date: String? = nil, pnr: String? = nil, source: TrackSource? = nil, seat: String? = nil,
        gmailSuggestionId: String? = nil
    ) {
        self.flight = flight
        self.date = date
        self.pnr = pnr
        self.source = source
        self.seat = seat
        self.gmailSuggestionId = gmailSuggestionId
    }

    public var isEmpty: Bool {
        (flight?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            && (date?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            && (pnr?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }
}

public struct SearchQuery: Hashable, Sendable {
    public var flight: String
    public var date: LocalDate
    public init(flight: String, date: LocalDate) {
        self.flight = flight
        self.date = date
    }
}

public enum FlightInputError: String, Sendable, Equatable { case required, notAFlightNumber }

public enum PrefillNotice: Equatable, Sendable {
    case checkDetails
    case flightUnreadable
    case dateUnreadable
    case dateOutOfRange(LocalDate)
}

public enum SearchUiState: Equatable, Sendable {
    case idle
    case searching(SearchQuery)
    case results(SearchQuery, [Flight])
    case notFound(SearchQuery)
    case rateLimited
    case providerError
    case offline
    case notConfigured
    case rejected
}

public enum TrackProblem: Equatable, Sendable {
    case limitReached(limit: Int, proLimit: Int?)
    case offline
    case flightGone
    case notConfigured
    case invalidDetails
    case detailsNotSaved
    case failed
}

public struct TrackNotice: Equatable, Sendable {
    public var flightId: String
    public var problem: TrackProblem
}

public struct AddFlightState: Equatable, Sendable {
    public var flightInput: String
    public var normalisedFlight: String?
    public var flightError: FlightInputError?
    public var date: LocalDate
    public var today: LocalDate
    public var firstSelectableDate: LocalDate
    public var lastSelectableDate: LocalDate
    public var dateRejected: Bool
    public var pnrInput: String
    public var pnrInvalid: Bool
    public var seatInput: String
    public var seatInvalid: Bool
    public var prefillNotice: PrefillNotice?
    public var isFromTicket: Bool
    public var search: SearchUiState
    public var trackedFlightIds: Set<String>
    public var trackingFlightId: String?
    public var trackNotice: TrackNotice?
    public var isBackendConfigured: Bool
    public var canRaiseSearchLimit: Bool
    public var finishedFlightId: String?

    public var isSearching: Bool { if case .searching = search { return true }; return false }
    public var isTracking: Bool { trackingFlightId != nil }

    public func isSelectable(_ day: LocalDate) -> Bool {
        day >= firstSelectableDate && day <= lastSelectableDate
    }
}

public struct ResolvedPrefill: Equatable, Sendable {
    public var flightInput: String
    public var date: LocalDate?
    public var pnrInput: String
    public var notice: PrefillNotice?
    public var autoSearch: Bool
}

public func resolvePrefill(_ prefill: AddFlightPrefill, selectable: ClosedRange<LocalDate>) -> ResolvedPrefill {
    if prefill.isEmpty {
        return ResolvedPrefill(flightInput: "", date: nil, pnrInput: "", notice: nil, autoSearch: false)
    }
    let flightText = String((prefill.flight ?? "").trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxFlightInputLength))
    let flightOk = parseFlightNumber(flightText) != nil
    let ticketDate = prefill.date?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty.flatMap(LocalDate.parse)
    let dateOk = ticketDate.map { selectable.contains($0) } ?? false
    let notice: PrefillNotice
    if !flightOk {
        notice = .flightUnreadable
    } else if ticketDate == nil {
        notice = .dateUnreadable
    } else if !dateOk, let ticketDate {
        notice = .dateOutOfRange(ticketDate)
    } else {
        notice = .checkDetails
    }
    return ResolvedPrefill(
        flightInput: flightText,
        date: dateOk ? ticketDate : nil,
        pnrInput: sanitizePnrInput(prefill.pnr ?? ""),
        notice: notice,
        autoSearch: flightOk && dateOk
    )
}

public func sanitizePnrInput(_ input: String) -> String {
    String(input.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(8))
}

public func isPnrInputAcceptable(_ input: String) -> Bool {
    input.isEmpty || normalizePnr(input) != nil
}

public func sanitizeSeatInput(_ input: String) -> String {
    String(input.uppercased().filter { !$0.isWhitespace }.prefix(TrackExtras.maxSeatLength))
}

@MainActor
public final class AddFlightModel {
    public private(set) var state: AddFlightState
    public var onChange: ((AddFlightState) -> Void)?

    private let flights: FlightRepository
    private let clock: FlyClock
    private let defaultAlerts: DefaultAlertPreferences
    private let source: TrackSource
    private let gmailSuggestionId: String?
    private let gmailStore: GmailSuggestionStoring?
    private let gmailSystem: GmailSystem?
    private var trackedByFlightId: [String: TrackedFlight] = [:]
    private var profile: UserProfile?
    private var searchGeneration = 0

    public init(
        prefill: AddFlightPrefill = AddFlightPrefill(),
        flights: FlightRepository,
        clock: FlyClock,
        homeTimeZone: String,
        backendConfigured: Bool,
        googleConfigured: Bool,
        isAnonymous: Bool,
        defaultAlerts: DefaultAlertPreferences,
        gmailStore: GmailSuggestionStoring? = nil,
        gmailSystem: GmailSystem? = nil
    ) {
        self.flights = flights
        self.clock = clock
        self.defaultAlerts = defaultAlerts
        gmailSuggestionId = prefill.gmailSuggestionId
        self.gmailStore = gmailStore
        self.gmailSystem = gmailSystem
        source = prefill.source ?? (prefill.isEmpty ? .manual : .share)
        let today = clock.today(tz: homeTimeZone)
        let selectable = selectableDates(today: today, now: clock.now())
        let resolved = resolvePrefill(prefill, selectable: selectable)
        let date = resolved.date ?? today
        let clamped = min(max(date, selectable.lowerBound), selectable.upperBound)
        state = AddFlightState(
            flightInput: resolved.flightInput,
            normalisedFlight: parseFlightNumber(resolved.flightInput)?.display,
            flightError: checkFlightInput(resolved.flightInput) == .invalid ? .notAFlightNumber : nil,
            date: clamped,
            today: today,
            firstSelectableDate: selectable.lowerBound,
            lastSelectableDate: selectable.upperBound,
            dateRejected: false,
            pnrInput: resolved.pnrInput,
            pnrInvalid: false,
            seatInput: sanitizeSeatInput(prefill.seat ?? ""),
            seatInvalid: false,
            prefillNotice: resolved.notice,
            isFromTicket: !prefill.isEmpty,
            search: .idle,
            trackedFlightIds: [],
            trackingFlightId: nil,
            trackNotice: nil,
            isBackendConfigured: backendConfigured,
            canRaiseSearchLimit: isAnonymous && googleConfigured,
            finishedFlightId: nil
        )
        if resolved.autoSearch {
            // The caller awaits `search()` after init; auto-search is started by `start()`.
        }
        self.autoSearch = resolved.autoSearch
    }

    private let autoSearch: Bool

    public func start() async {
        await reloadTracked()
        if autoSearch { await search() }
    }

    public func setFlightInput(_ text: String) {
        let input = String(text.prefix(maxFlightInputLength))
        if state.isTracking || input == state.flightInput { return }
        state.search = .idle
        state.trackNotice = nil
        state.prefillNotice = nil
        applyFlightInput(input)
        publish()
    }

    public func setDate(_ date: LocalDate) {
        if state.isTracking || !state.isSelectable(date) { return }
        if date == state.date {
            state.dateRejected = false
            publish()
            return
        }
        state.search = .idle
        state.trackNotice = nil
        state.date = date
        state.dateRejected = false
        state.prefillNotice = nil
        publish()
    }

    public func setPnr(_ text: String) {
        state.pnrInput = sanitizePnrInput(text)
        state.pnrInvalid = false
        clearDetailsNotice()
        publish()
    }

    public func setSeat(_ text: String) {
        state.seatInput = sanitizeSeatInput(text)
        state.seatInvalid = false
        clearDetailsNotice()
        publish()
    }

    public func search() async {
        if state.isSearching || state.isTracking { return }
        let number = parseFlightNumber(state.flightInput)
        let pnrOk = isPnrInputAcceptable(state.pnrInput)
        if number == nil || !pnrOk {
            state.flightError = number != nil ? nil : (checkFlightInput(state.flightInput) == .invalid ? .notAFlightNumber : .required)
            state.pnrInvalid = !pnrOk
            publish()
            return
        }
        if !state.isBackendConfigured {
            state.search = .notConfigured
            publish()
            return
        }
        let query = SearchQuery(flight: number!.display, date: state.date)
        state.search = .searching(query)
        state.trackNotice = nil
        state.dateRejected = false
        publish()
        searchGeneration += 1
        let generation = searchGeneration
        let result = await flights.search(flightText: query.flight, date: query.date)
        if generation != searchGeneration { return }
        apply(result, query: query)
        publish()
    }

    public func track(flightId: String) async {
        if state.isTracking { return }
        guard let extras = validatedExtras(flightId: flightId) else { publish(); return }
        state.trackingFlightId = flightId
        state.trackNotice = nil
        publish()
        switch await flights.track(flightId: flightId, extras: extras) {
        case .tracked:
            await applyDefaultMutes(flightId)
            await finish(flightId)
        case .alreadyTracked(let tracking):
            await saveDetailsAndFinish(tracking, extras)
        case .trackingLimitReached:
            fail(flightId, limitReached())
        case .invalidInput(let field):
            reject(flightId, field)
        case .flightNotFound:
            fail(flightId, .flightGone)
        case .offline:
            fail(flightId, .offline)
        case .notConfigured:
            fail(flightId, .notConfigured)
        case .failed:
            fail(flightId, .failed)
        }
    }

    public func openTracked(flightId: String) async {
        if state.isTracking { return }
        guard let existing = trackedByFlightId[flightId] else {
            await track(flightId: flightId)
            return
        }
        guard let extras = validatedExtras(flightId: flightId) else { publish(); return }
        state.trackingFlightId = flightId
        state.trackNotice = nil
        publish()
        await saveDetailsAndFinish(existing, extras)
    }

    public func noteSession(configured: Bool, anonymous: Bool, googleConfigured: Bool) {
        state.isBackendConfigured = configured
        state.canRaiseSearchLimit = anonymous && googleConfigured
        publish()
    }

    public func noteProfile(_ profile: UserProfile?) { self.profile = profile }

    public func reloadTracked() async {
        let list = await flights.trackedFlights()
        trackedByFlightId = Dictionary(uniqueKeysWithValues: list.map { ($0.flight.id, $0.tracking) })
        state.trackedFlightIds = Set(trackedByFlightId.keys)
        publish()
    }

    private func apply(_ result: SearchResult, query: SearchQuery) {
        switch result {
        case .found(let legs):
            let sorted = legs.sorted { ($0.bestDeparture ?? .distantFuture) < ($1.bestDeparture ?? .distantFuture) }
            state.search = sorted.isEmpty ? .notFound(query) : .results(query, sorted)
        case .notFound:
            state.search = .notFound(query)
        case .rateLimited:
            state.search = .rateLimited
        case .providerError:
            state.search = .providerError
        case .offline:
            state.search = .offline
        case .notConfigured:
            state.search = .notConfigured
        case .badInput(.flightNumber):
            state.search = .idle
            state.flightError = .notAFlightNumber
        case .badInput(.date):
            state.search = .idle
            state.dateRejected = true
        case .badInput(.other):
            state.search = .rejected
        }
    }

    private func validatedExtras(flightId: String) -> TrackExtras? {
        if !isPnrInputAcceptable(state.pnrInput) {
            state.pnrInvalid = true
            state.trackNotice = TrackNotice(flightId: flightId, problem: .invalidDetails)
            return nil
        }
        return TrackExtras(
            pnr: state.pnrInput.isEmpty ? nil : state.pnrInput,
            seat: state.seatInput.isEmpty ? nil : state.seatInput,
            source: source
        )
    }

    private func saveDetailsAndFinish(_ existing: TrackedFlight, _ typed: TrackExtras) async {
        let pnr = typed.pnr ?? existing.pnr
        let seat = typed.seat ?? existing.seat
        if pnr == existing.pnr && seat == existing.seat {
            await finish(existing.flightId)
            return
        }
        let merged = TrackExtras(pnr: pnr, seat: seat, cabin: existing.cabin, source: existing.source, note: existing.note)
        switch await flights.updateTrackingDetails(flightId: existing.flightId, extras: merged) {
        case .success:
            await finish(existing.flightId)
        case .invalidInput(let field):
            reject(existing.flightId, field)
        default:
            fail(existing.flightId, .detailsNotSaved)
        }
    }

    private func applyDefaultMutes(_ flightId: String) async {
        let muted = await defaultAlerts.muted()
        if muted.isEmpty { return }
        var prefs: [AlertType: Bool] = [:]
        for type in muted { prefs[type] = false }
        _ = await flights.updateNotifyPrefs(flightId: flightId, prefs: prefs)
    }

    /// The flight is tracked. A Gmail suggestion that opened this screen is now added and its notification goes,
    /// but only when the tracked flight is the suggestion's own (same flight and date). After an edit it stays
    /// pending; the scanner's cleanup marks it added if that flight is tracked later.
    private func finish(_ flightId: String) async {
        if let id = gmailSuggestionId, let gmailStore, trackedSuggestionId(flightId) == id {
            await gmailStore.setStatus([id], .added)
            await gmailSystem?.cancelNotifications([id])
        }
        state.finishedFlightId = flightId
        publish()
    }

    /// The suggestion id of the tracked leg: from the search result that holds it, else from the form.
    private func trackedSuggestionId(_ flightId: String) -> String {
        if case .results(_, let legs) = state.search, let leg = legs.first(where: { $0.id == flightId }) {
            return GmailSuggestions.suggestionId(flightCompact: GmailSuggestions.compact(leg.carrierIata + leg.flightNumber), date: leg.flightDate)
        }
        let flight = parseFlightNumber(state.flightInput)?.display ?? state.flightInput
        return GmailSuggestions.suggestionId(flightCompact: GmailSuggestions.compact(flight), date: state.date)
    }

    private func fail(_ flightId: String, _ problem: TrackProblem) {
        state.trackingFlightId = nil
        state.trackNotice = TrackNotice(flightId: flightId, problem: problem)
        publish()
    }

    private func reject(_ flightId: String, _ field: TrackField) {
        state.trackingFlightId = nil
        state.pnrInvalid = state.pnrInvalid || field == .pnr
        state.seatInvalid = state.seatInvalid || field == .seat
        state.trackNotice = TrackNotice(flightId: flightId, problem: field == .note ? .failed : .invalidDetails)
        publish()
    }

    private func limitReached() -> TrackProblem {
        let now = clock.now()
        let isPro = profile?.isPro(now: now) == true
        return .limitReached(
            limit: profile?.trackingLimit(now: now) ?? UserProfile.freeTrackingLimit,
            proLimit: isPro ? nil : UserProfile.proTrackingLimit
        )
    }

    private func applyFlightInput(_ input: String) {
        let check = checkFlightInput(input)
        state.flightInput = input
        state.normalisedFlight = parseFlightNumber(input)?.display
        state.flightError = check == .invalid ? .notAFlightNumber : nil
    }

    private func clearDetailsNotice() {
        if state.trackNotice?.problem == .invalidDetails || state.trackNotice?.problem == .detailsNotSaved {
            state.trackNotice = nil
        }
    }

    private func publish() { onChange?(state) }
}
