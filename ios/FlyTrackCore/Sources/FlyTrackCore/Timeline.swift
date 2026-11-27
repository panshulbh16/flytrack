import Foundation

// Full timeline (docs/P3_CONTRACTS.md A2 and section E): `flight_timeline(p_flight_id)` and an offline fallback built
// from the cached flight row. Same model as Android's timeline screen.

/// `flight_timeline` keys, in the server's order.
public enum TimelineKey: String, CaseIterable, Sendable, Equatable {
    case scheduled
    case checkinOpen = "checkin_open"
    case gateAssigned = "gate_assigned"
    case boarding
    case gateClosed = "gate_closed"
    case pushback
    case takeoff
    case diverted
    case landing
    case atGate = "at_gate"
    case belt
    case cancelled

    public var wire: String { rawValue }

    /// Milestones at the arrival airport are shown in its time zone, the rest in the departure airport's.
    public var isArrivalSide: Bool {
        switch self {
        case .landing, .atGate, .belt: return true
        default: return false
        }
    }
}

public enum TimelineState: String, Sendable, Equatable {
    case done, current, next

    /// Unknown values read as next (nothing claimed).
    public static func fromWire(_ value: String?) -> TimelineState {
        value.flatMap(TimelineState.init(rawValue:)) ?? .next
    }
}

/// Pro `detail` of one milestone (section E). Every field is optional; which ones are set depends on the key.
public struct TimelineDetail: Hashable, Sendable {
    public var schedDep: Date?
    public var schedArr: Date?
    public var closesAt: Date?
    public var gate: String?
    public var terminal: String?
    public var delayMin: Int?
    public var taxiOutMin: Int?
    public var taxiInMin: Int?
    public var fromArr: String?
    public var toArr: String?
    public var belt: String?

    public init(
        schedDep: Date? = nil, schedArr: Date? = nil, closesAt: Date? = nil, gate: String? = nil, terminal: String? = nil,
        delayMin: Int? = nil, taxiOutMin: Int? = nil, taxiInMin: Int? = nil, fromArr: String? = nil, toArr: String? = nil,
        belt: String? = nil
    ) {
        self.schedDep = schedDep
        self.schedArr = schedArr
        self.closesAt = closesAt
        self.gate = gate
        self.terminal = terminal
        self.delayMin = delayMin
        self.taxiOutMin = taxiOutMin
        self.taxiInMin = taxiInMin
        self.fromArr = fromArr
        self.toArr = toArr
        self.belt = belt
    }
}

public struct TimelineItem: Hashable, Sendable {
    public var key: TimelineKey
    /// When it happened (done / current), or the expected time (next); nil when unknown.
    public var at: Date?
    public var state: TimelineState
    /// Pro only; nil for everyone else and for the offline fallback.
    public var detail: TimelineDetail?

    public init(key: TimelineKey, at: Date?, state: TimelineState, detail: TimelineDetail? = nil) {
        self.key = key
        self.at = at
        self.state = state
        self.detail = detail
    }
}

public enum TimelineParseError: Error, Equatable {
    case malformed
}

/// Reads a `flight_timeline` answer. Items with a key this version does not know are skipped. SQL null is an empty list.
public func parseFlightTimeline(_ data: Data) throws -> [TimelineItem] {
    if rpcIsNull(data) { return [] }
    guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { throw TimelineParseError.malformed }
    return rows.compactMap { row in
        guard let object = row as? [String: Any], let key = JSON.string(object["key"]).flatMap(TimelineKey.init(rawValue:)) else {
            return nil
        }
        return TimelineItem(
            key: key,
            at: parseInstant(JSON.string(object["at"])),
            state: TimelineState.fromWire(JSON.string(object["state"])),
            detail: JSON.object(object["detail"]).map(parseTimelineDetail)
        )
    }
}

private func parseTimelineDetail(_ d: [String: Any]) -> TimelineDetail {
    func text(_ key: String) -> String? {
        JSON.string(d[key]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
    }
    return TimelineDetail(
        schedDep: parseInstant(JSON.string(d["sched_dep"])),
        schedArr: parseInstant(JSON.string(d["sched_arr"])),
        closesAt: parseInstant(JSON.string(d["closes_at"])),
        gate: text("gate"),
        terminal: text("terminal"),
        delayMin: JSON.int(d["delay_min"]),
        taxiOutMin: JSON.int(d["taxi_out_min"]),
        taxiInMin: JSON.int(d["taxi_in_min"]),
        fromArr: text("from_arr"),
        toArr: text("to_arr"),
        belt: text("belt")
    )
}

// MARK: - offline fallback

/// The timeline the cached flight row alone can prove (no events, no history, no detail). Used while offline or
/// before the first answer, so the screen is never empty. Same ordering and state rules as the server.
public func localTimeline(_ flight: Flight) -> [TimelineItem] {
    let rank: Int
    switch flight.status {
    case .checkIn: rank = 1
    case .boarding: rank = 2
    case .gateClosed: rank = 3
    case .departed: rank = 4
    case .enRoute, .diverted: rank = 5
    case .approaching: rank = 6
    case .landed: rank = 7
    case .arrived: rank = 8
    default: rank = 0
    }
    let departed = flight.actDep != nil || flight.depOffblockAt != nil || flight.depRunwayAt != nil || rank >= 4
    let landed = flight.actArr != nil || flight.arrRunwayAt != nil || flight.arrOnblockAt != nil || rank >= 7

    struct Step {
        var key: TimelineKey
        var done: Bool
        /// Left out when a later milestone is done and this one never happened.
        var optional: Bool
        var at: Date?
        var expected: Date?
    }
    var steps: [Step] = [
        Step(key: .scheduled, done: true, optional: false, at: flight.schedDep, expected: flight.schedDep),
        Step(key: .checkinOpen, done: (1...3).contains(rank), optional: true, at: nil, expected: nil),
        Step(key: .gateAssigned, done: flight.depGate != nil, optional: true, at: nil, expected: nil),
        Step(key: .boarding, done: rank == 2 || rank == 3, optional: false, at: nil, expected: nil),
        Step(key: .gateClosed, done: rank == 3, optional: false, at: nil, expected: nil),
        Step(
            key: .pushback, done: departed, optional: false,
            at: flight.depOffblockAt ?? (flight.depRunwayAt == nil ? flight.actDep : nil),
            expected: departed ? nil : (flight.estDep ?? flight.schedDep)
        ),
        Step(key: .takeoff, done: flight.depRunwayAt != nil || rank >= 5, optional: false, at: flight.depRunwayAt, expected: nil),
    ]
    if flight.status == .diverted {
        steps.append(Step(key: .diverted, done: true, optional: false, at: nil, expected: nil))
    }
    steps.append(Step(
        key: .landing, done: landed, optional: false,
        at: landed ? (flight.arrRunwayAt ?? flight.actArr) : nil,
        expected: landed ? nil : (flight.estArr ?? flight.schedArr)
    ))
    steps.append(Step(key: .atGate, done: flight.arrOnblockAt != nil || rank == 8, optional: false, at: flight.arrOnblockAt, expected: nil))
    steps.append(Step(key: .belt, done: flight.baggageBelt != nil, optional: true, at: nil, expected: nil))

    if flight.status == .cancelled {
        var items = steps.filter(\.done).map { TimelineItem(key: $0.key, at: $0.at, state: .done) }
        items.append(TimelineItem(key: .cancelled, at: nil, state: .current))
        return items
    }

    let last = steps.lastIndex(where: \.done) ?? 0
    var items: [TimelineItem] = []
    for (index, step) in steps.enumerated() {
        if index < last && !step.done && step.optional { continue }
        let state: TimelineState = index < last ? .done : (index == last ? .current : .next)
        items.append(TimelineItem(key: step.key, at: state == .next ? step.expected : step.at, state: state))
    }
    return items
}

// MARK: - presentation

/// One extra line under a milestone (Pro detail). The app words each case.
public enum TimelineNote: Hashable, Sendable {
    /// "Gate 42B, Terminal 1"
    case gate(gate: String, terminal: String?)
    /// Minutes against schedule at this step (positive = late, negative = early).
    case delay(minutes: Int)
    case taxiOut(minutes: Int)
    case taxiIn(minutes: Int)
    case belt(String)
    /// Check-in closes at this local time.
    case checkinCloses(time: String)
    /// Diverted from the planned arrival to another airport.
    case divertedTo(from: String?, to: String?)
}

public struct TimelineRow: Hashable, Sendable {
    public var key: TimelineKey
    public var state: TimelineState
    /// "18:50" at the airport the milestone happens at; nil when unknown.
    public var time: String?
    /// The time is an expectation (a `next` milestone), not a fact.
    public var expected: Bool
    public var notes: [TimelineNote]

    public init(key: TimelineKey, state: TimelineState, time: String?, expected: Bool, notes: [TimelineNote]) {
        self.key = key
        self.state = state
        self.time = time
        self.expected = expected
        self.notes = notes
    }
}

/// Turns timeline items into rows for `flight` (time zones come from the flight's airports).
public func timelineRows(_ items: [TimelineItem], flight: Flight) -> [TimelineRow] {
    items.map { item in
        let tz = item.key.isArrivalSide ? flight.arrTz : flight.depTz
        return TimelineRow(
            key: item.key,
            state: item.state,
            time: item.at.map { FlightTimeFormat.time($0, tz: tz) },
            expected: item.state == .next && item.at != nil,
            notes: timelineNotes(item, flight: flight)
        )
    }
}

private func timelineNotes(_ item: TimelineItem, flight: Flight) -> [TimelineNote] {
    guard let d = item.detail else { return [] }
    var notes: [TimelineNote] = []
    switch item.key {
    case .gateAssigned, .boarding, .gateClosed, .atGate:
        if let gate = d.gate { notes.append(.gate(gate: gate, terminal: d.terminal)) }
        if item.key == .atGate, let taxi = d.taxiInMin, taxi >= 0 { notes.append(.taxiIn(minutes: taxi)) }
    case .checkinOpen:
        if let closes = d.closesAt { notes.append(.checkinCloses(time: FlightTimeFormat.time(closes, tz: flight.depTz))) }
    case .pushback, .landing:
        if let delay = d.delayMin, delay != 0 { notes.append(.delay(minutes: delay)) }
    case .takeoff:
        if let taxi = d.taxiOutMin, taxi >= 0 { notes.append(.taxiOut(minutes: taxi)) }
    case .belt:
        if let belt = d.belt { notes.append(.belt(belt)) }
    case .diverted:
        notes.append(.divertedTo(from: d.fromArr, to: d.toArr))
    case .scheduled, .cancelled:
        break
    }
    return notes
}
