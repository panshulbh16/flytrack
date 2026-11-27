import Foundation

// P3 B5 (search by route or airport) and B6 (past flight search) on iOS. Same rules as Android's
// RouteSearchQuery.kt / SearchWindows.kt and the server's supabase/functions/_shared/route-search.ts and
// past-search.ts.

/// "That day's departures from `dep`", optionally only those to `arr` and on `airline` (body of `search-route`).
public struct RouteSearchQuery: Hashable, Sendable {
    /// IATA code of the departure airport, "DEL".
    public var dep: String
    /// IATA code of the arrival airport, or nil for every destination.
    public var arr: String?
    /// Local departure date at `dep`.
    public var date: LocalDate
    /// Two-character airline code ("6E"), or nil for every airline.
    public var airline: String?

    public init(dep: String, arr: String? = nil, date: LocalDate, airline: String? = nil) {
        self.dep = dep
        self.arr = arr
        self.date = date
        self.airline = airline
    }

    /// Upper-cased and trimmed, blank optional values dropped.
    public func normalized() -> RouteSearchQuery {
        func clean(_ text: String?) -> String? {
            let value = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            return value.isEmpty ? nil : value
        }
        return RouteSearchQuery(dep: clean(dep) ?? "", arr: clean(arr), date: date, airline: clean(airline))
    }

    /// The server's `normalizeRouteQuery` rules, on normalised values.
    public var isValid: Bool {
        Self.isCode(dep, length: 3) && (arr.map { Self.isCode($0, length: 3) && $0 != dep } ?? true)
            && (airline.map { Self.isCode($0, length: 2) } ?? true)
    }

    private static func isCode(_ text: String, length: Int) -> Bool {
        text.unicodeScalars.count == length && text.unicodeScalars.allSatisfy { ("A"..."Z").contains($0) || ("0"..."9").contains($0) }
    }

    /// A typed airline code: up to two letters or digits, upper-cased.
    public static func sanitizeAirlineInput(_ input: String) -> String {
        var out = ""
        for scalar in input.uppercased().unicodeScalars where ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar) {
            out.unicodeScalars.append(scalar)
            if out.unicodeScalars.count == 2 { break }
        }
        return out
    }
}

/// The dates `search-route` accepts: 00:00 UTC of the date between two days ago and 30 days ahead.
public enum RouteSearchWindow {
    public static let maxDaysAhead = 30

    public static func contains(_ date: LocalDate, now: Date) -> Bool {
        let day = date.startOfDayUTC()
        return day >= now.addingTimeInterval(-2 * 86_400) && day <= now.addingTimeInterval(Double(maxDaysAhead) * 86_400)
    }

    public static func dates(now: Date) -> ClosedRange<LocalDate> {
        let firstCandidate = FlightTimeFormat.localDate(now.addingTimeInterval(-2 * 86_400), tz: "UTC")
        let first = contains(firstCandidate, now: now) ? firstCandidate : firstCandidate.adding(days: 1)
        let lastCandidate = FlightTimeFormat.localDate(now.addingTimeInterval(Double(maxDaysAhead) * 86_400), tz: "UTC")
        let last = contains(lastCandidate, now: now) ? lastCandidate : lastCandidate.adding(days: -1)
        return first...max(first, last)
    }
}

/// The dates `search-flight` accepts with `mode: "past"`: from 365 days before today (UTC) up to tomorrow (UTC).
public enum PastSearchWindow {
    public static let maxDaysBack = 365

    public static func contains(_ date: LocalDate, now: Date) -> Bool { dates(now: now).contains(date) }

    public static func dates(now: Date) -> ClosedRange<LocalDate> {
        let today = FlightTimeFormat.localDate(now, tz: "UTC")
        return today.adding(days: -maxDaysBack)...today.adding(days: 1)
    }
}

/// `search-flight` with `mode: "past"` and the archived insert of B6. Optional on `AddFlightModel`.
@MainActor
public protocol PastFlightSearching: AnyObject {
    /// `search-flight` with `mode = "past"` (dates up to 365 days back).
    func searchPast(flightText: String, date: LocalDate) async -> SearchResult
    /// Inserts `user_flights` with `source = 'manual'` and `archived_at = now()`, so it lands straight in Passport.
    func trackPast(flightId: String, extras: TrackExtras) async -> TrackResult
}

/// `search-route` (B5).
@MainActor
public protocol RouteFlightSearching: AnyObject {
    func searchRoute(_ query: RouteSearchQuery) async -> SearchResult
}

// MARK: - "By route" tab

/// Why the form cannot search yet.
public enum RouteFormError: String, Sendable, Equatable {
    case missingFrom, sameAirports, badAirline
}

public enum RouteSearchUi: Equatable, Sendable {
    case idle
    case searching(RouteSearchQuery)
    case results(RouteSearchQuery, [Flight])
    case notFound(RouteSearchQuery)
    case rateLimited
    case providerError
    case offline
    case notConfigured
    case rejected
}

public struct RouteSearchState: Equatable, Sendable {
    /// IATA codes picked from the bundled airports.
    public var from: String?
    public var to: String?
    public var airlineInput: String
    public var date: LocalDate
    public var firstSelectableDate: LocalDate
    public var lastSelectableDate: LocalDate
    public var formError: RouteFormError?
    public var search: RouteSearchUi
    public var trackedFlightIds: Set<String>
    public var trackingFlightId: String?
    public var trackNotice: TrackNotice?
    public var finishedFlightId: String?
    public var isBackendConfigured: Bool

    public var isSearching: Bool { if case .searching = search { return true }; return false }
    public var isTracking: Bool { trackingFlightId != nil }
    public var canSearch: Bool { from != nil && !isSearching && !isTracking }
}

/// The "By route" tab of Add flight: from / to airport pickers (the bundled airports), a date and an optional
/// airline; results reuse the add-flight list. Each search costs 2 units of the daily search quota (server side).
@MainActor
public final class RouteSearchModel {
    public private(set) var state: RouteSearchState
    public var onChange: ((RouteSearchState) -> Void)?

    private let routes: RouteFlightSearching
    private let flights: FlightRepository
    private let clock: FlyClock
    private let defaultAlerts: DefaultAlertPreferences?
    private let source: TrackSource
    private let autoSearch: Bool
    private var generation = 0
    private var trackedByFlightId: [String: TrackedFlight] = [:]

    /// `prefill.routeFrom` / `routeTo` / `date` (a calendar suggestion) fill the form and search at `start()`.
    public init(
        prefill: AddFlightPrefill = AddFlightPrefill(),
        routes: RouteFlightSearching,
        flights: FlightRepository,
        clock: FlyClock,
        homeTimeZone: String,
        backendConfigured: Bool,
        defaultAlerts: DefaultAlertPreferences? = nil
    ) {
        self.routes = routes
        self.flights = flights
        self.clock = clock
        self.defaultAlerts = defaultAlerts
        source = prefill.source ?? .manual
        let now = clock.now()
        let window = RouteSearchWindow.dates(now: now)
        let today = clock.today(tz: homeTimeZone)
        let wanted = prefill.date.flatMap { LocalDate.parse($0.trimmingCharacters(in: .whitespacesAndNewlines)) } ?? today
        let date = min(max(wanted, window.lowerBound), window.upperBound)
        func code(_ text: String?) -> String? {
            let value = (text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            return value.utf16.count == 3 ? value : nil
        }
        let from = code(prefill.routeFrom)
        let to = code(prefill.routeTo).flatMap { $0 == from ? nil : $0 }
        state = RouteSearchState(
            from: from, to: to, airlineInput: "", date: date,
            firstSelectableDate: window.lowerBound, lastSelectableDate: window.upperBound,
            formError: nil, search: .idle, trackedFlightIds: [], trackingFlightId: nil, trackNotice: nil,
            finishedFlightId: nil, isBackendConfigured: backendConfigured
        )
        autoSearch = from != nil && prefill.date != nil && wanted == date
    }

    public func start() async {
        await reloadTracked()
        if autoSearch { await search() }
    }

    public func setFrom(_ iata: String?) {
        if state.isTracking { return }
        state.from = iata
        if iata != nil && iata == state.to { state.to = nil }
        formChanged()
    }

    public func setTo(_ iata: String?) {
        if state.isTracking { return }
        state.to = iata
        if iata != nil && iata == state.from { state.from = nil }
        formChanged()
    }

    /// Swaps from and to (the ⇅ button).
    public func swap() {
        if state.isTracking { return }
        let from = state.from
        state.from = state.to
        state.to = from
        formChanged()
    }

    public func setAirline(_ text: String) {
        let clean = RouteSearchQuery.sanitizeAirlineInput(text)
        if clean == state.airlineInput { return }
        state.airlineInput = clean
        formChanged()
    }

    public func setDate(_ date: LocalDate) {
        if state.isTracking || date < state.firstSelectableDate || date > state.lastSelectableDate || date == state.date { return }
        state.date = date
        formChanged()
    }

    public func noteSession(configured: Bool) {
        state.isBackendConfigured = configured
        publish()
    }

    public func search() async {
        if state.isSearching || state.isTracking { return }
        guard let from = state.from else {
            state.formError = .missingFrom
            publish()
            return
        }
        let query = RouteSearchQuery(
            dep: from, arr: state.to, date: state.date, airline: state.airlineInput.isEmpty ? nil : state.airlineInput
        ).normalized()
        if !query.isValid {
            if query.arr != nil && query.arr == query.dep {
                state.formError = .sameAirports
            } else if let airline = query.airline, airline.unicodeScalars.count != 2 {
                state.formError = .badAirline
            } else {
                state.formError = .missingFrom
            }
            publish()
            return
        }
        if !state.isBackendConfigured {
            state.search = .notConfigured
            publish()
            return
        }
        state.formError = nil
        state.trackNotice = nil
        state.search = .searching(query)
        publish()
        generation += 1
        let mine = generation
        let result = await routes.searchRoute(query)
        guard mine == generation else { return }
        switch result {
        case .found(let legs):
            let sorted = legs.sorted { ($0.bestDeparture ?? .distantFuture, $0.id) < ($1.bestDeparture ?? .distantFuture, $1.id) }
            state.search = sorted.isEmpty ? .notFound(query) : .results(query, sorted)
        case .notFound: state.search = .notFound(query)
        case .rateLimited: state.search = .rateLimited
        case .providerError: state.search = .providerError
        case .offline: state.search = .offline
        case .notConfigured: state.search = .notConfigured
        case .badInput: state.search = .rejected
        }
        publish()
    }

    /// Tracks one result the normal way (`source` of the prefill: `calendar` from a calendar suggestion, else manual).
    public func track(flightId: String) async {
        if state.isTracking { return }
        if trackedByFlightId[flightId] != nil {
            finish(flightId)
            return
        }
        state.trackingFlightId = flightId
        state.trackNotice = nil
        publish()
        switch await flights.track(flightId: flightId, extras: TrackExtras(source: source)) {
        case .tracked:
            await applyDefaultMutes(flightId)
            finish(flightId)
        case .alreadyTracked:
            finish(flightId)
        case .trackingLimitReached:
            fail(flightId, .limitReached(limit: UserProfile.freeTrackingLimit, proLimit: nil))
        case .invalidInput:
            fail(flightId, .failed)
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

    public func reloadTracked() async {
        let list = await flights.trackedFlights()
        trackedByFlightId = Dictionary(list.map { ($0.flight.id, $0.tracking) }, uniquingKeysWith: { first, _ in first })
        state.trackedFlightIds = Set(trackedByFlightId.keys)
        publish()
    }

    private func applyDefaultMutes(_ flightId: String) async {
        guard let defaultAlerts else { return }
        let muted = await defaultAlerts.muted()
        if muted.isEmpty { return }
        var prefs: [AlertType: Bool] = [:]
        for type in muted { prefs[type] = false }
        _ = await flights.updateNotifyPrefs(flightId: flightId, prefs: prefs)
    }

    private func formChanged() {
        state.formError = nil
        state.trackNotice = nil
        state.search = .idle
        generation += 1
        publish()
    }

    private func finish(_ flightId: String) {
        state.trackingFlightId = nil
        state.finishedFlightId = flightId
        publish()
    }

    private func fail(_ flightId: String, _ problem: TrackProblem) {
        state.trackingFlightId = nil
        state.trackNotice = TrackNotice(flightId: flightId, problem: problem)
        publish()
    }

    private func publish() { onChange?(state) }
}
