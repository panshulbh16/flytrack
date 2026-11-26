import Foundation

/// What `my_passport` counted (P2 contract section 2).
public enum PassportScope: String, Sendable, Equatable {
    /// Pro, all time.
    case all
    /// Free users without a year: the last 12 months.
    case last12
    /// One calendar year (Year in Review).
    case year

    public static func fromWire(_ value: String?) -> PassportScope {
        value.flatMap(PassportScope.init(rawValue:)) ?? .last12
    }
}

public struct CountedAirport: Hashable, Sendable {
    public var iata: String
    public var count: Int
    public init(iata: String, count: Int) { self.iata = iata; self.count = count }
}

public struct CountedAirline: Hashable, Sendable {
    public var carrier: String
    public var name: String?
    public var count: Int
    public init(carrier: String, name: String?, count: Int) { self.carrier = carrier; self.name = name; self.count = count }
}

public struct CountedAircraft: Hashable, Sendable {
    public var model: String
    public var count: Int
    public init(model: String, count: Int) { self.model = model; self.count = count }
}

public struct LongestFlight: Hashable, Sendable {
    public var flightId: String?
    public var carrier: String
    public var number: String
    public var dep: String
    public var arr: String
    public var distanceKm: Int
}

public struct TopRoute: Hashable, Sendable {
    public var dep: String
    public var arr: String
    public var count: Int
}

public struct WorstDelay: Hashable, Sendable {
    public var carrier: String
    public var number: String
    public var depDelayMin: Int
}

public struct DelayStats: Hashable, Sendable {
    public var totalMin: Int
    public var onTimePct: Int?
    public var worst: WorstDelay?
}

/// A calendar month, e.g. 2026-01.
public struct YearMonth: Hashable, Comparable, Sendable {
    public var year: Int
    public var month: Int

    public init(year: Int, month: Int) {
        self.year = year
        self.month = month
    }

    /// "2026-01"; nil for any other shape or a month outside 1...12.
    public static func parse(_ text: String) -> YearMonth? {
        let parts = text.split(separator: "-")
        guard parts.count == 2, parts[0].count == 4, let year = Int(parts[0]), let month = Int(parts[1]), (1...12).contains(month) else {
            return nil
        }
        return YearMonth(year: year, month: month)
    }

    public func adding(months: Int) -> YearMonth {
        let index = year * 12 + (month - 1) + months
        let y = Int((Double(index) / 12).rounded(.down))
        return YearMonth(year: y, month: index - y * 12 + 1)
    }

    public static func < (lhs: YearMonth, rhs: YearMonth) -> Bool { (lhs.year, lhs.month) < (rhs.year, rhs.month) }
}

/// One entry of `by_month`.
public struct MonthCount: Hashable, Sendable {
    public var month: String
    public var flights: Int
    public init(month: String, flights: Int) { self.month = month; self.flights = flights }
}

/// The caller's passport, as `my_passport` returns it. Lists are already sorted by the server (most first).
public struct Passport: Hashable, Sendable {
    public var scope: PassportScope
    public var year: Int
    /// A free user whose answer was cut to the last 12 months: show the Pro upsell.
    public var proLimited: Bool
    public var flights: Int
    public var distanceKm: Int
    public var airborneMinutes: Int
    public var airports: [CountedAirport]
    public var countries: [String]
    public var airlines: [CountedAirline]
    public var aircraft: [CountedAircraft]
    public var longest: LongestFlight?
    public var topRoute: TopRoute?
    public var delay: DelayStats
    /// Economy, per passenger, distance-based: always shown as an estimate.
    public var co2Kg: Int
    public var co2Method: String
    public var byMonth: [MonthCount]
    /// ISO date "2025-12-03", nil when there are no flights.
    public var firstFlightDate: LocalDate?

    public var isEmpty: Bool { flights == 0 }
}

/// `co2_method` of the distance-band estimate in the contract.
public let co2MethodV1 = "estimate-v1"

/// Reads a `my_passport` answer. Entries the app cannot use (no IATA code, no model) are dropped instead of failing
/// the whole screen. Nil when the JSON is not an object.
public func parsePassport(_ data: Data, fallbackYear: Int) -> Passport? {
    JSON.object(from: data).map { parsePassport($0, fallbackYear: fallbackYear) }
}

public func parsePassport(_ o: [String: Any], fallbackYear: Int) -> Passport {
    func text(_ value: Any?) -> String? { JSON.string(value)?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
    func list(_ key: String) -> [[String: Any]] { (o[key] as? [Any] ?? []).compactMap { $0 as? [String: Any] } }
    let delay = JSON.object(o["delay"])
    let worst = JSON.object(delay?["worst"])
    let longest = JSON.object(o["longest"])
    let top = JSON.object(o["top_route"])
    return Passport(
        scope: PassportScope.fromWire(JSON.string(o["scope"])),
        year: JSON.int(o["year"]) ?? fallbackYear,
        proLimited: JSON.bool(o["pro_limited"]) ?? false,
        flights: max(0, JSON.int(o["flights"]) ?? 0),
        distanceKm: max(0, JSON.int(o["distance_km"]) ?? 0),
        airborneMinutes: max(0, JSON.int(o["airborne_minutes"]) ?? 0),
        airports: list("airports").compactMap { a in text(a["iata"]).map { CountedAirport(iata: $0, count: JSON.int(a["count"]) ?? 0) } },
        countries: (o["countries"] as? [Any] ?? []).compactMap { text($0) },
        airlines: list("airlines").compactMap { a in
            text(a["carrier"]).map { CountedAirline(carrier: $0, name: text(a["name"]), count: JSON.int(a["count"]) ?? 0) }
        },
        aircraft: list("aircraft").compactMap { a in text(a["model"]).map { CountedAircraft(model: $0, count: JSON.int(a["count"]) ?? 0) } },
        longest: longest.flatMap { l in
            guard let carrier = text(l["carrier"]), let number = text(l["number"]), let dep = text(l["dep"]),
                  let arr = text(l["arr"]), let km = JSON.int(l["distance_km"]) else { return nil }
            return LongestFlight(flightId: text(l["flight_id"]), carrier: carrier, number: number, dep: dep, arr: arr, distanceKm: km)
        },
        topRoute: top.flatMap { r in
            guard let dep = text(r["dep"]), let arr = text(r["arr"]) else { return nil }
            return TopRoute(dep: dep, arr: arr, count: JSON.int(r["count"]) ?? 0)
        },
        delay: DelayStats(
            totalMin: max(0, JSON.int(delay?["total_min"]) ?? 0),
            onTimePct: JSON.int(delay?["on_time_pct"]).map { min(100, max(0, $0)) },
            worst: worst.flatMap { w in
                guard let carrier = text(w["carrier"]), let number = text(w["number"]), let minutes = JSON.int(w["dep_delay_min"]) else {
                    return nil
                }
                return WorstDelay(carrier: carrier, number: number, depDelayMin: minutes)
            }
        ),
        co2Kg: max(0, JSON.int(o["co2_kg"]) ?? 0),
        co2Method: text(o["co2_method"]) ?? co2MethodV1,
        byMonth: list("by_month").compactMap { m in text(m["month"]).map { MonthCount(month: $0, flights: JSON.int(m["flights"]) ?? 0) } },
        firstFlightDate: text(o["first_flight_date"]).flatMap(LocalDate.parse)
    )
}

// MARK: - presentation

/// The two views of the passport screen.
public enum PassportTab: String, CaseIterable, Sendable, Equatable {
    /// `p_year` null: all time for Pro, the last 12 months for free users.
    case overview
    /// `p_year` = the current year: Year in Review, free for everyone.
    case year

    public func yearArgument(currentYear: Int) -> Int? {
        self == .year ? currentYear : nil
    }
}

/// `my_passport` counts years and months in Indian time; the app uses the same calendar.
public let passportTimeZone = "Asia/Kolkata"

public func passportToday(_ now: Date) -> LocalDate {
    FlightTimeFormat.localDate(now, tz: passportTimeZone)
}

/// One bar of the by-month chart. `fraction` is relative to the busiest month (0 when nothing was flown).
public struct MonthBar: Hashable, Sendable {
    public var month: YearMonth
    public var flights: Int
    public var fraction: Double
}

/// Big numbers of the stats grid, in display order.
public enum StatKind: String, CaseIterable, Sendable, Equatable {
    case flights, distanceKm, hours, airports, countries, airlines
}

public struct StatTile: Hashable, Sendable {
    public var kind: StatKind
    public var value: Int
}

/// Equatorial circumference, for the "times around the Earth" line.
public let earthCircumferenceKm = 40_075.0

/// Longest lists the screen shows; the server already sorts them, most flown first.
public let passportListLimit = 8

extension Passport {
    /// Twelve bars: January to December for Year in Review, otherwise the twelve months that end with `today`'s
    /// month. Months without flights are zero bars, so gaps stay visible.
    public func monthBars(today: LocalDate) -> [MonthBar] {
        let months: [YearMonth]
        if scope == .year {
            months = (1...12).map { YearMonth(year: year, month: $0) }
        } else {
            let last = YearMonth(year: today.year, month: today.month)
            months = (0..<12).reversed().map { last.adding(months: -$0) }
        }
        var counts: [YearMonth: Int] = [:]
        for entry in byMonth {
            guard let month = YearMonth.parse(entry.month) else { continue }
            counts[month, default: 0] += max(0, entry.flights)
        }
        let busiest = months.map { counts[$0] ?? 0 }.max() ?? 0
        return months.map { month in
            let n = counts[month] ?? 0
            return MonthBar(month: month, flights: n, fraction: busiest == 0 ? 0 : Double(n) / Double(busiest))
        }
    }

    /// Whole hours in the air, rounded to the nearest hour.
    public var hoursInAir: Int { Int((Double(airborneMinutes) / 60).rounded(.toNearestOrAwayFromZero)) }

    /// Distance flown as laps of the Earth, one decimal; nil below a tenth of a lap (not worth a line).
    public var earthLaps: Double? {
        let laps = (Double(distanceKm) / earthCircumferenceKm * 10).rounded(.toNearestOrAwayFromZero) / 10
        return laps >= 0.1 ? laps : nil
    }

    public var statTiles: [StatTile] {
        [
            StatTile(kind: .flights, value: flights),
            StatTile(kind: .distanceKm, value: distanceKm),
            StatTile(kind: .hours, value: hoursInAir),
            StatTile(kind: .airports, value: airports.count),
            StatTile(kind: .countries, value: countries.count),
            StatTile(kind: .airlines, value: airlines.count),
        ]
    }

    /// The Year in Review card; only for a non-empty year passport.
    public func yearInReviewCard(today: LocalDate) -> YearInReviewCardModel? {
        guard scope == .year else { return nil }
        return shareCard(today: today)
    }

    /// The share image for any non-empty passport: Year in Review for a year, otherwise the passport card
    /// (all time or the last 12 months, named by `scope`).
    public func shareCard(today: LocalDate) -> YearInReviewCardModel? {
        guard !isEmpty else { return nil }
        return YearInReviewCardModel(
            scope: scope,
            year: year,
            flights: flights,
            distanceKm: distanceKm,
            hours: hoursInAir,
            airports: airports.count,
            countries: countries.count,
            topRoute: topRoute.flatMap { $0.count > 1 ? $0 : nil },
            longest: longest,
            topAirline: airlines.first.map { $0.name ?? $0.carrier },
            bars: monthBars(today: today)
        )
    }
}

/// What the share card shows (Year in Review, or the passport for `all` / `last12`).
public struct YearInReviewCardModel: Hashable, Sendable {
    public var scope: PassportScope
    public var year: Int
    public var flights: Int
    public var distanceKm: Int
    public var hours: Int
    public var airports: Int
    public var countries: Int
    public var topRoute: TopRoute?
    public var longest: LongestFlight?
    /// Airline name, or its code when the name is unknown.
    public var topAirline: String?
    public var bars: [MonthBar]
}

// MARK: - loading

public enum PassportResult: Sendable, Equatable {
    case passport(Passport)
    case offline
    case notConfigured
    case failed
}

/// `my_passport(p_year)`.
@MainActor
public protocol PassportSource: AnyObject {
    func passport(year: Int?) async -> PassportResult
}

public enum PassportProblem: String, Sendable, Equatable {
    case offline, notConfigured, failed
}

public struct PassportState: Equatable, Sendable {
    public var tab: PassportTab
    public var isLoading: Bool
    public var passport: Passport?
    public var problem: PassportProblem?
    public var currentYear: Int
    public var today: LocalDate

    public init(tab: PassportTab = .overview, isLoading: Bool = true, passport: Passport? = nil, problem: PassportProblem? = nil, currentYear: Int, today: LocalDate) {
        self.tab = tab
        self.isLoading = isLoading
        self.passport = passport
        self.problem = problem
        self.currentYear = currentYear
        self.today = today
    }

    public var bars: [MonthBar] { passport?.monthBars(today: today) ?? [] }
    public var yearCard: YearInReviewCardModel? { passport?.yearInReviewCard(today: today) }
    /// The share image for the tab on screen; nil while there is nothing to share.
    public var shareCard: YearInReviewCardModel? { passport?.shareCard(today: today) }
}

/// The Passport screen: an overview (all time for Pro, last 12 months for free) and the current year's Year in
/// Review (always free). Answers are kept per tab for this screen's lifetime, so switching back is instant.
@MainActor
public final class PassportModel {
    public private(set) var state: PassportState
    public var onChange: ((PassportState) -> Void)?

    private let source: PassportSource
    private let clock: FlyClock
    private var cache: [PassportTab: Passport] = [:]
    private var generation = 0

    public init(source: PassportSource, clock: FlyClock, tab: PassportTab = .overview) {
        self.source = source
        self.clock = clock
        let today = passportToday(clock.now())
        state = PassportState(tab: tab, currentYear: today.year, today: today)
    }

    public func load() async { await fetch(force: false) }

    public func refresh() async { await fetch(force: true) }

    public func select(_ tab: PassportTab) async {
        guard tab != state.tab else { return }
        state.tab = tab
        state.problem = nil
        state.passport = cache[tab]
        publish()
        await fetch(force: false)
    }

    private func fetch(force: Bool) async {
        let today = passportToday(clock.now())
        state.today = today
        state.currentYear = today.year
        let tab = state.tab
        if !force, let cached = cache[tab] {
            state.passport = cached
            state.isLoading = false
            publish()
            return
        }
        generation += 1
        let mine = generation
        state.isLoading = true
        publish()
        let result = await source.passport(year: tab.yearArgument(currentYear: today.year))
        if case .passport(let passport) = result { cache[tab] = passport }
        // A newer request (another tab, a refresh) owns the screen now.
        guard mine == generation, tab == state.tab else { return }
        state.isLoading = false
        switch result {
        case .passport(let passport):
            state.passport = passport
            state.problem = nil
        case .offline:
            state.problem = .offline
        case .notConfigured:
            state.problem = .notConfigured
        case .failed:
            state.problem = .failed
        }
        publish()
    }

    private func publish() { onChange?(state) }
}
