import Foundation

// P3 A3 calendar import (Pro): scan the next 365 days of the device calendar for flight-like events and list them as
// suggestions. "Add" uses the normal search-and-track path with `source = 'calendar'`. Same rules as Android's
// CalendarSuggestions.kt / CalendarImportViewModel.kt. The EventKit reader lives in the app (`DeviceCalendar`).

/// How a suggestion becomes a tracked flight.
public enum CalendarSuggestionAction: Hashable, Sendable {
    /// Search this flight number on this date (Add flight, flight-number tab).
    case byNumber(flight: String, date: LocalDate)
    /// No flight number: search the day's departures from `from` (Add flight, "By route" tab).
    case byRoute(from: String, to: String?, date: LocalDate)
    /// Not enough to search: no flight number and no departure airport, or no date.
    case needsDetails
}

public struct CalendarSuggestion: Hashable, Sendable, Identifiable {
    /// The parser's source id ("device:<event>:<start>", plus ":legN"): stable across scans.
    public var id: String
    /// "6E 2341" (normalised), or nil when the event names none.
    public var flight: String?
    public var from: CatalogAirport?
    public var to: CatalogAirport?
    /// Local departure date at the origin (or the event's date when the origin is unknown).
    public var date: LocalDate?
    /// "06:00" at the departure airport, when the event states it.
    public var departTime: String?
    public var issues: [CalendarReviewIssue]
    /// "Leg 1 of 2" of a multi-leg event.
    public var leg: Int?
    public var legCount: Int?
    public var action: CalendarSuggestionAction
    /// The user already tracks a flight that matches (same number and date, or same route and date).
    public var alreadyTracked: Bool

    public init(
        id: String, flight: String?, from: CatalogAirport?, to: CatalogAirport?, date: LocalDate?, departTime: String?,
        issues: [CalendarReviewIssue], leg: Int?, legCount: Int?, action: CalendarSuggestionAction, alreadyTracked: Bool
    ) {
        self.id = id
        self.flight = flight
        self.from = from
        self.to = to
        self.date = date
        self.departTime = departTime
        self.issues = issues
        self.leg = leg
        self.legCount = legCount
        self.action = action
        self.alreadyTracked = alreadyTracked
    }

    /// What Add flight opens with for this suggestion (`source = calendar`); nil when it needs details first.
    public var prefill: AddFlightPrefill? {
        switch action {
        case .byNumber(let flight, let date):
            return AddFlightPrefill(flight: flight, date: date.iso, source: .calendar)
        case .byRoute(let from, let to, let date):
            return AddFlightPrefill(date: date.iso, source: .calendar, routeFrom: from, routeTo: to)
        case .needsDetails:
            return nil
        }
    }
}

/// The time zone the import falls back to for dates when the origin is unknown (the app is India-first).
public let calendarHomeZone = "Asia/Kolkata"

/// Suggestions for `candidates` (already split into legs): from `today` on, one per flight (the same number and date,
/// or the same route and date, appear once), soonest first. Candidates without a flight number and without any
/// airport are dropped: a "Flight" reminder with nothing else is not worth a row.
public func buildCalendarSuggestions(_ candidates: [CalendarCandidate], tracked: [Flight], today: LocalDate) -> [CalendarSuggestion] {
    var seen = Set<String>()
    var out: [CalendarSuggestion] = []
    for c in candidates {
        let flight = c.number.isEmpty ? nil : parseFlightNumber(c.number)?.display
        if flight == nil && c.from == nil && c.to == nil { continue }
        let date = calendarCandidateDate(c)
        if let date, date < today { continue }
        let action: CalendarSuggestionAction
        if let date, let flight {
            action = .byNumber(flight: flight, date: date)
        } else if let date, let from = c.from {
            action = .byRoute(from: from.iata, to: c.to.flatMap { $0.iata == from.iata ? nil : $0.iata }, date: date)
        } else {
            action = .needsDetails
        }
        let key: String
        if let flight, let date {
            key = "n:\(flight):\(date.iso)"
        } else {
            key = "r:\(c.from?.iata ?? "null"):\(c.to?.iata ?? "null"):\(date?.iso ?? "null"):\(date == nil ? c.sourceId : "null")"
        }
        if !seen.insert(key).inserted { continue }
        let departUnits = c.departLocal.utf16.count
        let departTime: String? = departUnits >= 16 ? (c.departLocal as NSString).substring(with: NSRange(location: 11, length: 5)) : nil
        out.append(CalendarSuggestion(
            id: c.sourceId,
            flight: flight,
            from: c.from,
            to: c.to,
            date: date,
            departTime: departTime,
            issues: c.issues,
            leg: c.leg,
            legCount: c.legCount,
            action: action,
            alreadyTracked: date.map { d in tracked.contains { $0.matchesCalendar(flight: flight, from: c.from?.iata, to: c.to?.iata, date: d) } } ?? false
        ))
    }
    return out.sorted { l, r in
        switch (l.date, r.date) {
        case let (a?, b?) where a != b: return a < b
        case (nil, _?): return false
        case (_?, nil): return true
        default: break
        }
        let lt = l.departTime ?? "99:99"
        let rt = r.departTime ?? "99:99"
        if lt != rt { return lt < rt }
        return l.id < r.id
    }
}

/// The local departure date: from the stated departure time, else the all-day date, else the start instant.
public func calendarCandidateDate(_ c: CalendarCandidate) -> LocalDate? {
    if c.departLocal.utf16.count >= 10 { return LocalDate.parse((c.departLocal as NSString).substring(to: 10)) }
    if let day = c.allDayDate { return LocalDate.parse(day) }
    guard let start = c.startInstant else { return nil }
    let zone = c.from?.tz ?? calendarHomeZone
    guard TimeZone(identifier: zone) != nil else { return nil }
    return FlightTimeFormat.localDate(start, tz: zone)
}

extension Flight {
    /// Same flight number and date, or (without a number) same departure airport, date and, when given, arrival.
    func matchesCalendar(flight: String?, from: String?, to: String?, date: LocalDate) -> Bool {
        if flightDate != date { return false }
        if let flight { return designator == flight }
        guard let from else { return false }
        return depIata == from && (to == nil || arrIata == to)
    }
}

// MARK: - device port

/// EventKit's authorization, reduced to what the screen needs.
public enum CalendarAuthorization: Sendable, Equatable {
    /// Never asked: the intro explains, and the request comes only on "Scan my calendar" (contract: on tap).
    case notDetermined
    case fullAccess
    /// Write-only access (iOS 17+) cannot read events: treated like a refusal, Settings can change it.
    case writeOnly
    case denied
    /// Screen Time or a profile blocks calendars.
    case restricted
}

public enum DeviceCalendarRead: Sendable, Equatable {
    /// `truncated`: the reader stopped at its cap.
    case events([CalendarEvent], truncated: Bool)
    case noPermission
    case unavailable
}

@MainActor
public protocol DeviceCalendarReading: AnyObject {
    func authorization() -> CalendarAuthorization
    /// Asks for full access (the system prompt). True when granted.
    func requestFullAccess() async -> Bool
    /// Events overlapping [from, to), recurring events expanded, ids unique per occurrence.
    func events(from: Date, to: Date) async -> DeviceCalendarRead
}

// MARK: - screen model

public enum CalendarImportState: Equatable, Sendable {
    /// Nothing read yet: the screen explains and offers "Scan my calendar" (which asks for access).
    case intro
    /// The user said no (or only write access, or restricted): only Settings can change it now.
    case permissionDenied(restricted: Bool)
    case scanning
    /// `eventsRead` events were read; `suggestions` are the flight-like ones (may be empty).
    case results(suggestions: [CalendarSuggestion], eventsRead: Int, truncated: Bool)
    case unavailable
}

/// P3 A3 on iOS. Free users see the Pro upsell on the screen; the model itself does not gate.
@MainActor
public final class CalendarImportModel {
    /// The contract's window: the next 365 days (from yesterday, so a flight later today is not missed).
    public static let scanDays = 365

    public private(set) var state: CalendarImportState = .intro
    public var onChange: ((CalendarImportState) -> Void)?

    private let calendar: DeviceCalendarReading
    private let airports: AirportCatalogLoading
    private let flights: FlightRepository
    private let clock: FlyClock
    private let homeTimeZone: String

    public init(calendar: DeviceCalendarReading, airports: AirportCatalogLoading, flights: FlightRepository, clock: FlyClock, homeTimeZone: String) {
        self.calendar = calendar
        self.airports = airports
        self.flights = flights
        self.clock = clock
        self.homeTimeZone = homeTimeZone
    }

    /// On open: with access already granted, scan at once; otherwise show the intro (no prompt before the tap).
    public func start() async {
        switch calendar.authorization() {
        case .fullAccess:
            await scan()
        case .notDetermined:
            set(.intro)
        case .writeOnly, .denied:
            set(.permissionDenied(restricted: false))
        case .restricted:
            set(.permissionDenied(restricted: true))
        }
    }

    /// "Scan my calendar": asks for full access when it was never asked, then scans.
    public func scanTapped() async {
        if case .scanning = state { return }
        switch calendar.authorization() {
        case .fullAccess:
            await scan()
        case .notDetermined:
            if await calendar.requestFullAccess() {
                await scan()
            } else {
                set(.permissionDenied(restricted: false))
            }
        case .writeOnly, .denied:
            set(.permissionDenied(restricted: false))
        case .restricted:
            set(.permissionDenied(restricted: true))
        }
    }

    public func scan() async {
        if case .scanning = state { return }
        set(.scanning)
        let now = clock.now()
        let read = await calendar.events(from: now.addingTimeInterval(-86_400), to: now.addingTimeInterval(Double(Self.scanDays) * 86_400))
        switch read {
        case .noPermission:
            set(.permissionDenied(restricted: false))
        case .unavailable:
            set(.unavailable)
        case .events(let events, let truncated):
            let catalog = await airports.load()
            let tracked = await flights.trackedFlights().map(\.flight)
            let today = clock.today(tz: homeTimeZone)
            let suggestions = await Task.detached(priority: .userInitiated) {
                let parser = CalendarFlightParser(catalog: catalog)
                return buildCalendarSuggestions(events.flatMap(parser.candidates), tracked: tracked, today: today)
            }.value
            set(.results(suggestions: suggestions, eventsRead: events.count, truncated: truncated))
        }
    }

    /// Back on screen (e.g. after a suggestion was added): marks newly tracked flights.
    public func refreshTracked() async {
        guard case .results(let suggestions, let read, let truncated) = state else { return }
        let tracked = await flights.trackedFlights().map(\.flight)
        let updated = suggestions.map { s -> CalendarSuggestion in
            guard !s.alreadyTracked, let date = s.date else { return s }
            var copy = s
            copy.alreadyTracked = tracked.contains { $0.matchesCalendar(flight: s.flight, from: s.from?.iata, to: s.to?.iata, date: date) }
            return copy
        }
        guard case .results = state else { return }
        set(.results(suggestions: updated, eventsRead: read, truncated: truncated))
    }

    private func set(_ next: CalendarImportState) {
        state = next
        onChange?(next)
    }
}
