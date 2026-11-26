import Foundation

// iOS widgets (docs/P3_CONTRACTS.md A4). The app writes a small snapshot of the user's next flights into the App Group
// `group.app.flytrack` on every sync; the WidgetKit extension `FlyTrackWidget` reads it and never calls the network.
// Pure Foundation so the choice of flight, the wording inputs and the refresh times are tested on Linux.

public enum WidgetContract {
    /// Shared container of the app and the widget extension (entitlement `com.apple.security.application-groups`).
    public static let appGroup = "group.app.flytrack"
    /// `UserDefaults(suiteName: appGroup)` key holding the encoded `WidgetSnapshot`.
    public static let snapshotKey = "widget_snapshot_v1"
    /// The widget's `kind`, used by `WidgetCenter.reloadTimelines(ofKind:)`.
    public static let kind = "FlyTrackWidget"
    /// Flights kept in the snapshot: enough for the widget to roll over to the next one without the app.
    public static let maxFlights = 4
    /// A landed flight stays on the widget this long (walk to the belt), then the next one takes over.
    public static let landedLinger: TimeInterval = 60 * 60
    /// A cancelled flight stays until its scheduled departure plus this.
    public static let cancelledLinger: TimeInterval = 2 * 60 * 60
    /// Without milestones the widget still re-reads the snapshot this often.
    public static let idleRefresh: TimeInterval = 60 * 60
}

/// One flight as the widget needs it. Times are absolute; the widget formats them in the airport's zone.
public struct WidgetFlight: Codable, Hashable, Sendable {
    public var id: String
    /// "6E 2134"
    public var designator: String
    public var dep: String
    public var arr: String
    public var depCity: String?
    public var arrCity: String?
    public var depTz: String?
    public var arrTz: String?
    /// `FlightStatus` wire value.
    public var status: String
    public var schedDep: Date?
    public var estDep: Date?
    public var actDep: Date?
    public var schedArr: Date?
    public var estArr: Date?
    public var actArr: Date?
    public var depTerminal: String?
    public var depGate: String?
    public var arrTerminal: String?
    public var arrGate: String?
    public var belt: String?
    public var offblockAt: Date?
    public var takeoffAt: Date?
    public var touchdownAt: Date?
    public var onblockAt: Date?

    public init(
        id: String, designator: String, dep: String, arr: String, depCity: String? = nil, arrCity: String? = nil,
        depTz: String? = nil, arrTz: String? = nil, status: String, schedDep: Date? = nil, estDep: Date? = nil,
        actDep: Date? = nil, schedArr: Date? = nil, estArr: Date? = nil, actArr: Date? = nil, depTerminal: String? = nil,
        depGate: String? = nil, arrTerminal: String? = nil, arrGate: String? = nil, belt: String? = nil,
        offblockAt: Date? = nil, takeoffAt: Date? = nil, touchdownAt: Date? = nil, onblockAt: Date? = nil
    ) {
        self.id = id
        self.designator = designator
        self.dep = dep
        self.arr = arr
        self.depCity = depCity
        self.arrCity = arrCity
        self.depTz = depTz
        self.arrTz = arrTz
        self.status = status
        self.schedDep = schedDep
        self.estDep = estDep
        self.actDep = actDep
        self.schedArr = schedArr
        self.estArr = estArr
        self.actArr = actArr
        self.depTerminal = depTerminal
        self.depGate = depGate
        self.arrTerminal = arrTerminal
        self.arrGate = arrGate
        self.belt = belt
        self.offblockAt = offblockAt
        self.takeoffAt = takeoffAt
        self.touchdownAt = touchdownAt
        self.onblockAt = onblockAt
    }

    public init(_ flight: Flight) {
        self.init(
            id: flight.id, designator: flight.designator, dep: flight.depIata, arr: flight.arrIata,
            depCity: flight.depAirport?.displayCity, arrCity: flight.arrAirport?.displayCity,
            depTz: flight.depTz, arrTz: flight.arrTz, status: flight.status.wire,
            schedDep: flight.schedDep, estDep: flight.estDep, actDep: flight.actDep,
            schedArr: flight.schedArr, estArr: flight.estArr, actArr: flight.actArr,
            depTerminal: flight.depTerminal, depGate: flight.depGate, arrTerminal: flight.arrTerminal,
            arrGate: flight.arrGate, belt: flight.baggageBelt,
            offblockAt: flight.depOffblockAt, takeoffAt: flight.depRunwayAt,
            touchdownAt: flight.arrRunwayAt, onblockAt: flight.arrOnblockAt
        )
    }

    public var flightStatus: FlightStatus { FlightStatus.fromWire(status) }
    public var bestDeparture: Date? { actDep ?? offblockAt ?? estDep ?? schedDep }
    public var bestArrival: Date? { actArr ?? touchdownAt ?? estArr ?? schedArr }

    public var hasDeparted: Bool {
        if actDep != nil || offblockAt != nil || takeoffAt != nil { return true }
        switch flightStatus {
        case .departed, .enRoute, .approaching, .landed, .arrived, .diverted: return true
        default: return false
        }
    }

    public var hasLanded: Bool {
        actArr != nil || touchdownAt != nil || onblockAt != nil || flightStatus == .landed || flightStatus == .arrived
    }

    /// Minutes behind schedule at departure (before take-off) or arrival (after landing); nil without both times.
    public var delayMinutes: Int? {
        let pair: (Date?, Date?) = hasLanded ? (schedArr, actArr ?? touchdownAt ?? estArr) : (schedDep, actDep ?? estDep)
        guard let scheduled = pair.0, let current = pair.1 else { return nil }
        return Int((current.timeIntervalSince(scheduled) / 60).rounded(.toNearestOrAwayFromZero))
    }
}

public struct WidgetSnapshot: Codable, Hashable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var writtenAt: Date
    /// Upcoming and current flights, by best departure.
    public var flights: [WidgetFlight]

    public init(version: Int = WidgetSnapshot.currentVersion, writtenAt: Date, flights: [WidgetFlight]) {
        self.version = version
        self.writtenAt = writtenAt
        self.flights = flights
    }

    public static let empty = WidgetSnapshot(writtenAt: Date(timeIntervalSince1970: 0), flights: [])
}

/// The snapshot for the user's tracked (not archived) flights at `now`: flights still worth showing, soonest first.
public func widgetSnapshot(flights: [Flight], now: Date) -> WidgetSnapshot {
    let items = flights
        .map { WidgetFlight($0) }
        .filter { widgetVisibleUntil($0).map { $0 > now } ?? true }
        .sorted { ($0.bestDeparture ?? .distantFuture, $0.id) < ($1.bestDeparture ?? .distantFuture, $1.id) }
    return WidgetSnapshot(writtenAt: now, flights: Array(items.prefix(WidgetContract.maxFlights)))
}

/// When the flight stops being "next or current": an hour after landing, two hours after a cancelled departure.
/// Nil when that cannot be known yet (no times at all): the flight stays.
public func widgetVisibleUntil(_ flight: WidgetFlight) -> Date? {
    switch flight.flightStatus {
    case .cancelled:
        return (flight.schedDep ?? flight.bestDeparture).map { $0.addingTimeInterval(WidgetContract.cancelledLinger) }
    default:
        if let landed = flight.onblockAt ?? flight.actArr ?? flight.touchdownAt {
            return landed.addingTimeInterval(WidgetContract.landedLinger)
        }
        if flight.hasLanded, let arrival = flight.bestArrival {
            return arrival.addingTimeInterval(WidgetContract.landedLinger)
        }
        // Not landed: keep it until well past the expected arrival, in case the data stopped coming.
        return flight.bestArrival.map { $0.addingTimeInterval(6 * 3600) }
    }
}

/// The flight the widget shows at `now`: the first one still visible (the snapshot is ordered by departure). A flight
/// in the air wins over an earlier one that is somehow still listed.
public func widgetFlight(in snapshot: WidgetSnapshot, at now: Date) -> WidgetFlight? {
    let visible = snapshot.flights.filter { widgetVisibleUntil($0).map { $0 > now } ?? true }
    if let airborne = visible.first(where: { $0.hasDeparted && !$0.hasLanded && $0.flightStatus != .cancelled }) {
        return airborne
    }
    return visible.first
}

/// What the widget headlines.
public enum WidgetPhase: String, Sendable, Equatable {
    case upcoming, checkIn, boarding, departed, inAir, landed, cancelled, diverted
}

public func widgetPhase(_ flight: WidgetFlight, at now: Date) -> WidgetPhase {
    switch flight.flightStatus {
    case .cancelled: return .cancelled
    case .diverted: return .diverted
    default: break
    }
    if flight.hasLanded { return .landed }
    if flight.takeoffAt != nil { return .inAir }
    switch flight.flightStatus {
    case .enRoute, .approaching: return .inAir
    case .departed: return .departed
    default: break
    }
    if flight.hasDeparted { return .departed }
    switch flight.flightStatus {
    case .boarding, .gateClosed: return .boarding
    case .checkIn: return .checkIn
    default: return .upcoming
    }
}

/// What a widget entry shows; the views word it.
public struct WidgetDisplay: Hashable, Sendable {
    public var flight: WidgetFlight
    public var phase: WidgetPhase
    /// "18:50" at the departure airport (best known).
    public var depTime: String?
    public var arrTime: String?
    /// The departure moved from the schedule: the scheduled time, for a struck-through label.
    public var schedDepTime: String?
    /// Positive minutes late; nil on time or unknown.
    public var lateMin: Int?
    /// The next moment worth counting down to: departure before take-off, arrival in the air.
    public var countdownTarget: Date?
    /// "Gate 42B · T1" before departure; the belt after landing.
    public var gate: String?
    public var terminal: String?
    public var belt: String?
    /// 0...1 between departure and arrival while in the air.
    public var progress: Double?

    public init(
        flight: WidgetFlight, phase: WidgetPhase, depTime: String?, arrTime: String?, schedDepTime: String?, lateMin: Int?,
        countdownTarget: Date?, gate: String?, terminal: String?, belt: String?, progress: Double?
    ) {
        self.flight = flight
        self.phase = phase
        self.depTime = depTime
        self.arrTime = arrTime
        self.schedDepTime = schedDepTime
        self.lateMin = lateMin
        self.countdownTarget = countdownTarget
        self.gate = gate
        self.terminal = terminal
        self.belt = belt
        self.progress = progress
    }
}

public func widgetDisplay(_ flight: WidgetFlight, at now: Date) -> WidgetDisplay {
    let phase = widgetPhase(flight, at: now)
    let dep = flight.bestDeparture
    let arr = flight.bestArrival
    let late = flight.delayMinutes.flatMap { $0 >= delayThresholdMinutes ? $0 : nil }
    let moved: Bool = {
        guard let scheduled = flight.schedDep, let dep else { return false }
        return abs(dep.timeIntervalSince(scheduled)) >= 60
    }()
    let countdown: Date?
    switch phase {
    case .upcoming, .checkIn, .boarding: countdown = dep.flatMap { $0 > now ? $0 : nil }
    case .departed, .inAir: countdown = arr.flatMap { $0 > now ? $0 : nil }
    case .landed, .cancelled, .diverted: countdown = nil
    }
    var progress: Double?
    if phase == .departed || phase == .inAir, let start = flight.takeoffAt ?? dep, let end = arr, end > start {
        progress = min(1, max(0, now.timeIntervalSince(start) / end.timeIntervalSince(start)))
    }
    let beforeDeparture = phase == .upcoming || phase == .checkIn || phase == .boarding
    return WidgetDisplay(
        flight: flight,
        phase: phase,
        depTime: dep.map { FlightTimeFormat.time($0, tz: flight.depTz) },
        arrTime: arr.map { FlightTimeFormat.time($0, tz: flight.arrTz) },
        schedDepTime: moved && beforeDeparture ? flight.schedDep.map { FlightTimeFormat.time($0, tz: flight.depTz) } : nil,
        lateMin: phase == .cancelled ? nil : late,
        countdownTarget: countdown,
        gate: beforeDeparture ? flight.depGate?.nonEmpty : (phase == .landed ? flight.arrGate?.nonEmpty : nil),
        terminal: beforeDeparture ? flight.depTerminal?.nonEmpty : (phase == .landed ? flight.arrTerminal?.nonEmpty : nil),
        belt: phase == .landed ? flight.belt?.nonEmpty : nil,
        progress: progress
    )
}

/// The moments the widget's look changes for this flight, after `now` and within `horizon`: boarding (40 min before
/// departure), departure, arrival, and when the flight leaves the widget. Timeline entries are made at these times
/// (contract: "refreshed at the milestone times").
public func widgetMilestones(_ flight: WidgetFlight, after now: Date, horizon: TimeInterval = 24 * 3600) -> [Date] {
    var dates: [Date] = []
    if let dep = flight.bestDeparture, !flight.hasDeparted {
        dates.append(dep.addingTimeInterval(-40 * 60))
        dates.append(dep)
    }
    if let arr = flight.bestArrival, !flight.hasLanded {
        dates.append(arr)
    }
    if let until = widgetVisibleUntil(flight) { dates.append(until) }
    let end = now.addingTimeInterval(horizon)
    return Array(Set(dates.filter { $0 > now && $0 <= end })).sorted()
}

/// Entry dates for a widget timeline: now, then every milestone of the flights in the snapshot (so a landed flight
/// rolls over to the next one), capped to `limit` entries.
public func widgetEntryDates(_ snapshot: WidgetSnapshot, now: Date, limit: Int = 12) -> [Date] {
    let milestones = snapshot.flights.flatMap { widgetMilestones($0, after: now) }
    let unique = Array(Set(milestones)).sorted()
    return [now] + unique.prefix(max(0, limit - 1))
}

/// When the widget should ask for a new timeline when the app has not pushed one: after the last entry, but at most
/// `WidgetContract.idleRefresh` from now.
public func widgetReloadDate(entries: [Date], now: Date) -> Date {
    let cap = now.addingTimeInterval(WidgetContract.idleRefresh)
    guard let last = entries.last, last > now else { return cap }
    return min(last, cap)
}

// MARK: - codec

public enum WidgetSnapshotCodec {
    public static func encode(_ snapshot: WidgetSnapshot) -> Data? {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return try? encoder.encode(snapshot)
    }

    /// Nil for missing, unreadable or newer-version data (the widget then shows its empty state).
    public static func decode(_ data: Data?) -> WidgetSnapshot? {
        guard let data else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        guard let snapshot = try? decoder.decode(WidgetSnapshot.self, from: data),
              snapshot.version <= WidgetSnapshot.currentVersion else { return nil }
        return snapshot
    }
}
