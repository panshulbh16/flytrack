import Foundation

// P3 B10 (compare Passports with friends) and B12 (crew mode) on iOS: models, wire parsing and the screen models.
// Backend: supabase/migrations/20261126000028_social.sql. Same rules as Android's feature/social.

// MARK: - B10 friend Passport

public let comparedTopAirlines = 3

/// The numbers both sides of the comparison have.
public struct PassportSide: Hashable, Sendable {
    public var flights: Int
    public var distanceKm: Int
    public var airports: Int
    public var countries: Int
    /// Top three, most flown first.
    public var airlines: [CountedAirline]
    public var longest: LongestFlight?

    public init(flights: Int, distanceKm: Int, airports: Int, countries: Int, airlines: [CountedAirline], longest: LongestFlight?) {
        self.flights = flights
        self.distanceKm = distanceKm
        self.airports = airports
        self.countries = countries
        self.airlines = airlines
        self.longest = longest
    }
}

extension Passport {
    /// The caller's own Passport reduced to the compared numbers.
    public var side: PassportSide {
        PassportSide(
            flights: flights, distanceKm: distanceKm, airports: airports.count, countries: countries.count,
            airlines: Array(airlines.prefix(comparedTopAirlines)), longest: longest
        )
    }
}

/// `friend_passport(p_friend, p_year)`: a friend's reduced Passport, under the caller's year rules.
public struct FriendPassport: Hashable, Sendable {
    public var friendId: String
    public var displayName: String?
    public var scope: PassportScope
    public var year: Int
    /// The caller is free and the answer covers only the last 12 months (same rule as their own Passport).
    public var proLimited: Bool
    public var side: PassportSide
    /// ISO country codes, sorted.
    public var countryCodes: [String]

    public init(friendId: String, displayName: String?, scope: PassportScope, year: Int, proLimited: Bool, side: PassportSide, countryCodes: [String]) {
        self.friendId = friendId
        self.displayName = displayName
        self.scope = scope
        self.year = year
        self.proLimited = proLimited
        self.side = side
        self.countryCodes = countryCodes
    }
}

/// Reads a `friend_passport` answer. Nil for SQL null, a non-object or a missing `friend_id`.
public func parseFriendPassport(_ data: Data, fallbackYear: Int) -> FriendPassport? {
    guard let o = JSON.object(from: data) else { return nil }
    return parseFriendPassport(o, fallbackYear: fallbackYear)
}

public func parseFriendPassport(_ o: [String: Any], fallbackYear: Int) -> FriendPassport? {
    func text(_ value: Any?) -> String? { JSON.string(value)?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
    guard let friendId = text(o["friend_id"]) else { return nil }
    let longest = JSON.object(o["longest"]).flatMap { l -> LongestFlight? in
        guard let carrier = text(l["carrier"]), let number = text(l["number"]), let dep = text(l["dep"]),
              let arr = text(l["arr"]), let km = JSON.int(l["distance_km"]) else { return nil }
        return LongestFlight(flightId: nil, carrier: carrier, number: number, dep: dep, arr: arr, distanceKm: km)
    }
    let airlines = (o["airlines"] as? [Any] ?? []).compactMap { $0 as? [String: Any] }.compactMap { a -> CountedAirline? in
        text(a["carrier"]).map { CountedAirline(carrier: $0, name: text(a["name"]), count: JSON.int(a["count"]) ?? 0) }
    }
    return FriendPassport(
        friendId: friendId,
        displayName: text(o["display_name"]),
        scope: PassportScope.fromWire(JSON.string(o["scope"])),
        year: JSON.int(o["year"]) ?? fallbackYear,
        proLimited: JSON.bool(o["pro_limited"]) ?? false,
        side: PassportSide(
            flights: max(0, JSON.int(o["flights"]) ?? 0),
            distanceKm: max(0, JSON.int(o["distance_km"]) ?? 0),
            airports: max(0, JSON.int(o["airports"]) ?? 0),
            countries: max(0, JSON.int(o["countries"]) ?? 0),
            airlines: Array(airlines.prefix(comparedTopAirlines)),
            longest: longest
        ),
        countryCodes: (o["country_codes"] as? [Any] ?? []).compactMap { text($0) }
    )
}

/// One compared number, in display order.
public enum CompareKind: String, CaseIterable, Sendable, Equatable {
    case flights, distanceKm, airports, countries, longestKm
}

/// Who has more of one number; nil on a tie.
public enum CompareLeader: String, Sendable, Equatable { case me, friend }

public struct CompareRow: Hashable, Sendable {
    public var kind: CompareKind
    public var mine: Int
    public var friend: Int

    public init(kind: CompareKind, mine: Int, friend: Int) {
        self.kind = kind
        self.mine = mine
        self.friend = friend
    }

    public var leader: CompareLeader? {
        if mine > friend { return .me }
        if friend > mine { return .friend }
        return nil
    }

    /// 0...1 bar lengths relative to the larger value (both 0 when nobody has any).
    public var mineFraction: Double { fraction(mine) }
    public var friendFraction: Double { fraction(friend) }

    private func fraction(_ value: Int) -> Double {
        let top = max(mine, friend)
        return top <= 0 ? 0 : Double(value) / Double(top)
    }
}

/// The side-by-side rows of the compare screen, in display order.
public func comparePassports(mine: PassportSide, friend: PassportSide) -> [CompareRow] {
    [
        CompareRow(kind: .flights, mine: mine.flights, friend: friend.flights),
        CompareRow(kind: .distanceKm, mine: mine.distanceKm, friend: friend.distanceKm),
        CompareRow(kind: .airports, mine: mine.airports, friend: friend.airports),
        CompareRow(kind: .countries, mine: mine.countries, friend: friend.countries),
        CompareRow(kind: .longestKm, mine: mine.longest?.distanceKm ?? 0, friend: friend.longest?.distanceKm ?? 0),
    ]
}

/// Countries both have flown to (ISO codes, sorted).
public func sharedCountries(mine: [String], friend: [String]) -> [String] {
    Set(mine).intersection(friend).sorted()
}

/// Airlines both flew (by carrier code), most flown by the caller first.
public func sharedAirlines(mine: [CountedAirline], friend: [CountedAirline]) -> [String] {
    let theirs = Set(friend.map(\.carrier))
    var seen = Set<String>()
    return mine.map(\.carrier).filter { theirs.contains($0) && seen.insert($0).inserted }
}

public enum FriendPassportResult: Sendable, Equatable {
    case loaded(FriendPassport)
    /// Not an accepted friend, or they turned Passport sharing off (`not_shared`, 42501): the same on purpose.
    case notShared
    case offline
    case notConfigured
    /// Technical English: log it, do not show it.
    case failed(String?)
}

/// Maps a `friend_passport` remote answer (the app parses inside the call).
public func friendPassportResult(_ result: RemoteResult<FriendPassport>) -> FriendPassportResult {
    switch result {
    case .ok(let passport): return .loaded(passport)
    case .offline: return .offline
    case .notConfigured: return .notConfigured
    case .failed(let message): return .failed(message)
    case .refused(let code, let message):
        if (message ?? "").contains("not_shared") || code == "42501" { return .notShared }
        return .failed(message ?? code)
    }
}

@MainActor
public protocol FriendPassportSource: AnyObject {
    /// `year` nil = the caller's overview rules (all time for Pro, last 12 months free); a year = that year.
    func friendPassport(friendId: String, year: Int?) async -> FriendPassportResult
}

public enum FriendCompareProblem: String, Sendable, Equatable {
    case notShared, offline, notConfigured, failed
}

public struct FriendCompareState: Equatable, Sendable {
    public var friendName: String?
    public var tab: PassportTab
    public var isLoading: Bool
    public var mine: Passport?
    public var friend: FriendPassport?
    public var problem: FriendCompareProblem?
    public var currentYear: Int

    public init(friendName: String?, tab: PassportTab, isLoading: Bool = true, mine: Passport? = nil, friend: FriendPassport? = nil, problem: FriendCompareProblem? = nil, currentYear: Int) {
        self.friendName = friendName
        self.tab = tab
        self.isLoading = isLoading
        self.mine = mine
        self.friend = friend
        self.problem = problem
        self.currentYear = currentYear
    }

    /// Rows once both sides are in.
    public var rows: [CompareRow] {
        guard let mine, let friend else { return [] }
        return comparePassports(mine: mine.side, friend: friend.side)
    }

    public var sharedCountries: [String] {
        guard let mine, let friend else { return [] }
        return FlyTrackCore.sharedCountries(mine: mine.countries, friend: friend.countryCodes)
    }

    /// The caller is free: the comparison covers the last 12 months only (Pro shows all time).
    public var limited: Bool { (mine?.proLimited ?? false) || (friend?.proLimited ?? false) }

    /// The friend's name from the answer, else the one the friends list had.
    public var displayName: String? { friend?.displayName ?? friendName }
}

/// "You vs Asha": both Passports under the caller's year rules, on the overview or the current year.
@MainActor
public final class FriendCompareModel {
    public private(set) var state: FriendCompareState
    public var onChange: ((FriendCompareState) -> Void)?

    private let friendId: String
    private let mine: PassportSource
    private let friends: FriendPassportSource
    private let clock: FlyClock
    private var generation = 0

    public init(friendId: String, friendName: String?, mine: PassportSource, friends: FriendPassportSource, clock: FlyClock, tab: PassportTab = .overview) {
        self.friendId = friendId
        self.mine = mine
        self.friends = friends
        self.clock = clock
        state = FriendCompareState(friendName: friendName, tab: tab, currentYear: passportToday(clock.now()).year)
    }

    public func load() async {
        generation += 1
        let token = generation
        let year = passportToday(clock.now()).year
        state.currentYear = year
        state.isLoading = true
        state.problem = nil
        publish()
        let argument = state.tab.yearArgument(currentYear: year)
        let theirs = await friends.friendPassport(friendId: friendId, year: argument)
        let own = await mine.passport(year: argument)
        guard token == generation else { return }
        state.isLoading = false
        switch theirs {
        case .loaded(let passport): state.friend = passport
        case .notShared: state.friend = nil; state.problem = .notShared
        case .offline: state.problem = .offline
        case .notConfigured: state.problem = .notConfigured
        case .failed: state.problem = .failed
        }
        switch own {
        case .passport(let passport): state.mine = passport
        case .offline: state.problem = state.problem ?? .offline
        case .notConfigured: state.problem = state.problem ?? .notConfigured
        case .failed: state.problem = state.problem ?? .failed
        }
        publish()
    }

    public func select(_ tab: PassportTab) async {
        guard tab != state.tab else { return }
        state.tab = tab
        state.mine = nil
        state.friend = nil
        await load()
    }

    private func publish() { onChange?(state) }
}

// MARK: - B12 crew mode and the P3 profile switches

/// The user-editable P3 columns of `profiles` (migration 28, column grants).
public struct ProfileFlags: Hashable, Sendable {
    /// B12: mutes check-in, boarding and when-to-leave alerts, hides upsells, shows block times.
    public var crewMode: Bool
    /// B10: accepted friends may compare Passports with this user.
    public var sharePassportWithFriends: Bool

    public init(crewMode: Bool = false, sharePassportWithFriends: Bool = true) {
        self.crewMode = crewMode
        self.sharePassportWithFriends = sharePassportWithFriends
    }

    public static let columns = "crew_mode,share_passport_with_friends"
    public static let crewModeColumn = "crew_mode"
    public static let sharePassportColumn = "share_passport_with_friends"
}

/// A `profiles` select of `ProfileFlags.columns` (an array of rows). Nil when no row came back.
public func parseProfileFlags(_ data: Data) -> ProfileFlags? {
    guard let rows = JSON.array(from: data), let row = rows.first else { return nil }
    return ProfileFlags(
        crewMode: JSON.bool(row["crew_mode"]) ?? false,
        sharePassportWithFriends: JSON.bool(row["share_passport_with_friends"]) ?? true
    )
}

/// Block time (off-block to on-block) of a flight, for the crew timeline and log. Nil until both are known.
public func blockMinutes(_ flight: Flight) -> Int? {
    guard let off = flight.depOffblockAt ?? flight.actDep, let on = flight.arrOnblockAt ?? flight.actArr, on > off else { return nil }
    return Int((on.timeIntervalSince(off) / 60).rounded(.toNearestOrAwayFromZero))
}

/// Hours shown for crew (block minutes rounded to the nearest hour).
public func blockHours(minutes: Int) -> Int {
    Int((Double(max(0, minutes)) / 60).rounded(.toNearestOrAwayFromZero))
}

@MainActor
public protocol ProfileFlagsRepository: AnyObject {
    /// Crew mode as last known on this device for the signed-in user (no network).
    func cachedCrewMode() -> Bool
    func refresh() async -> RemoteResult<ProfileFlags>
    func setCrewMode(_ enabled: Bool) async -> RemoteResult<ProfileFlags>
    func setSharePassport(_ enabled: Bool) async -> RemoteResult<ProfileFlags>
}

public enum ProfileFlagsProblem: String, Sendable, Equatable { case offline, notConfigured, failed }

public struct ProfileFlagsState: Equatable, Sendable {
    public var flags: ProfileFlags
    /// False until the server answered once (the switches show the cached values meanwhile).
    public var loaded: Bool
    public var saving: Bool
    public var problem: ProfileFlagsProblem?

    public init(flags: ProfileFlags, loaded: Bool = false, saving: Bool = false, problem: ProfileFlagsProblem? = nil) {
        self.flags = flags
        self.loaded = loaded
        self.saving = saving
        self.problem = problem
    }
}

/// The two switches in Settings. Optimistic: a refused save rolls back to the value before it.
@MainActor
public final class ProfileFlagsModel {
    public private(set) var state: ProfileFlagsState
    public var onChange: ((ProfileFlagsState) -> Void)?
    private let repository: ProfileFlagsRepository

    public init(repository: ProfileFlagsRepository) {
        self.repository = repository
        state = ProfileFlagsState(flags: ProfileFlags(crewMode: repository.cachedCrewMode()))
    }

    public func load() async {
        apply(await repository.refresh(), rollback: nil)
    }

    public func setCrewMode(_ on: Bool) async {
        guard on != state.flags.crewMode, !state.saving else { return }
        let before = state.flags
        state.flags.crewMode = on
        state.saving = true
        publish()
        apply(await repository.setCrewMode(on), rollback: before)
    }

    public func setSharePassport(_ on: Bool) async {
        guard on != state.flags.sharePassportWithFriends, !state.saving else { return }
        let before = state.flags
        state.flags.sharePassportWithFriends = on
        state.saving = true
        publish()
        apply(await repository.setSharePassport(on), rollback: before)
    }

    private func apply(_ result: RemoteResult<ProfileFlags>, rollback: ProfileFlags?) {
        state.saving = false
        switch result {
        case .ok(let flags):
            state.flags = flags
            state.loaded = true
            state.problem = nil
        case .offline:
            if let rollback { state.flags = rollback }
            state.problem = .offline
        case .notConfigured:
            if let rollback { state.flags = rollback }
            state.problem = .notConfigured
        case .refused, .failed:
            if let rollback { state.flags = rollback }
            state.problem = .failed
        }
        publish()
    }

    private func publish() { onChange?(state) }
}
