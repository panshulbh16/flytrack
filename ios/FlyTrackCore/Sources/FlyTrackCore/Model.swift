import Foundation

/// Mirrors FLIGHT_STATUSES. An unknown wire value becomes `.unknown` so a newer server cannot crash the UI.
public enum FlightStatus: String, CaseIterable, Sendable, Equatable {
    case unknown
    case scheduled
    case checkIn = "check_in"
    case boarding
    case gateClosed = "gate_closed"
    case departed
    case enRoute = "en_route"
    case approaching
    case landed
    case arrived
    case cancelled
    case diverted

    public var wire: String { rawValue }

    public static func fromWire(_ value: String?) -> FlightStatus {
        guard let value else { return .unknown }
        return FlightStatus(rawValue: value) ?? .unknown
    }
}

public enum FlightPhase: String, Sendable, Equatable {
    case upcoming, checkIn, boarding, inAir, landed, cancelled, diverted
}

public enum FlightStatusTone: String, Sendable, Equatable {
    case onTime, delayed, cancelled, active, estimated
}

/// Mirrors EVENT_TYPES. `critical` matches the CRITICAL list in notify.ts.
public enum AlertType: String, CaseIterable, Sendable, Equatable, Hashable {
    case delay = "DELAY"
    case delayReduced = "DELAY_REDUCED"
    case scheduleChange = "SCHEDULE_CHANGE"
    case gateAssigned = "GATE_ASSIGNED"
    case gateChange = "GATE_CHANGE"
    case terminalChange = "TERMINAL_CHANGE"
    case boarding = "BOARDING"
    case departed = "DEPARTED"
    case landed = "LANDED"
    case beltAssigned = "BELT_ASSIGNED"
    case beltChange = "BELT_CHANGE"
    case cancelled = "CANCELLED"
    case diverted = "DIVERTED"
    case reinstated = "REINSTATED"
    case inboundLate = "INBOUND_LATE"

    public var wire: String { rawValue }

    public var critical: Bool {
        switch self {
        case .delayReduced, .gateAssigned, .departed, .landed:
            return false
        default:
            return true
        }
    }

    public var proOnly: Bool { self == .inboundLate }

    public static func fromWire(_ value: String?) -> AlertType? {
        guard let value else { return nil }
        return AlertType(rawValue: value)
    }
}

public enum Cabin: String, CaseIterable, Sendable, Equatable {
    case economy
    case premiumEconomy = "premium_economy"
    case business
    case first

    public var wire: String { rawValue }

    public static func fromWire(_ value: String?) -> Cabin? {
        guard let value else { return nil }
        return Cabin(rawValue: value)
    }
}

public enum TrackSource: String, CaseIterable, Sendable, Equatable {
    case manual, pdf, share, screenshot

    public var wire: String { rawValue }

    public static func fromWire(_ value: String?) -> TrackSource {
        guard let value, let source = TrackSource(rawValue: value) else { return .manual }
        return source
    }
}

public enum Plan: String, Sendable, Equatable {
    case free, pro

    public var wire: String { rawValue }

    public static func fromWire(_ value: String?) -> Plan {
        guard let value, let plan = Plan(rawValue: value) else { return .free }
        return plan
    }
}

public struct Airport: Hashable, Sendable {
    public var iata: String
    public var name: String
    public var city: String?
    public var tz: String
    public var lat: Double
    public var lon: Double

    public init(iata: String, name: String, city: String?, tz: String, lat: Double, lon: Double) {
        self.iata = iata
        self.name = name
        self.city = city
        self.tz = tz
        self.lat = lat
        self.lon = lon
    }

    public var displayCity: String {
        if let city, !city.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return city }
        return name
    }
}

public struct LivePosition: Hashable, Sendable {
    public var lat: Double
    public var lon: Double
    public var altFt: Int?
    public var headingDeg: Double?
    public var speedKts: Double?
    public var at: Date?

    public init(lat: Double, lon: Double, altFt: Int?, headingDeg: Double?, speedKts: Double?, at: Date?) {
        self.lat = lat
        self.lon = lon
        self.altFt = altFt
        self.headingDeg = headingDeg
        self.speedKts = speedKts
        self.at = at
    }
}

public struct InboundAircraft: Hashable, Sendable {
    public var flight: String
    public var fromIata: String
    public var status: FlightStatus
    public var schedArr: Date?
    public var estArr: Date?
    public var actArr: Date?
    public var live: LivePosition?

    public init(flight: String, fromIata: String, status: FlightStatus, schedArr: Date?, estArr: Date?, actArr: Date?, live: LivePosition?) {
        self.flight = flight
        self.fromIata = fromIata
        self.status = status
        self.schedArr = schedArr
        self.estArr = estArr
        self.actArr = actArr
        self.live = live
    }
}

public struct InboundPrediction: Hashable, Sendable {
    public var computedAt: Date?
    public var predictedDep: Date?
    public var lateMin: Int
    public var reason: String?
    public var inbound: InboundAircraft?

    public init(computedAt: Date?, predictedDep: Date?, lateMin: Int, reason: String?, inbound: InboundAircraft?) {
        self.computedAt = computedAt
        self.predictedDep = predictedDep
        self.lateMin = lateMin
        self.reason = reason
        self.inbound = inbound
    }
}

public struct Flight: Hashable, Sendable {
    public var id: String
    public var carrierIata: String
    public var flightNumber: String
    public var flightDate: LocalDate
    public var depIata: String
    public var arrIata: String
    public var airlineName: String?
    public var status: FlightStatus
    public var schedDep: Date?
    public var estDep: Date?
    public var actDep: Date?
    public var depTerminal: String?
    public var depGate: String?
    public var depCheckin: String?
    public var schedArr: Date?
    public var estArr: Date?
    public var actArr: Date?
    public var arrTerminal: String?
    public var arrGate: String?
    public var baggageBelt: String?
    public var aircraftModel: String?
    public var aircraftReg: String?
    public var delayReason: String?
    public var live: LivePosition?
    public var lastPolledAt: Date?
    public var updatedAt: Date?
    public var depAirport: Airport?
    public var arrAirport: Airport?
    public var inboundFlightId: String?
    public var prediction: InboundPrediction?
    public var hasProInsight: Bool

    public init(
        id: String,
        carrierIata: String,
        flightNumber: String,
        flightDate: LocalDate,
        depIata: String,
        arrIata: String,
        airlineName: String? = nil,
        status: FlightStatus = .scheduled,
        schedDep: Date? = nil,
        estDep: Date? = nil,
        actDep: Date? = nil,
        depTerminal: String? = nil,
        depGate: String? = nil,
        depCheckin: String? = nil,
        schedArr: Date? = nil,
        estArr: Date? = nil,
        actArr: Date? = nil,
        arrTerminal: String? = nil,
        arrGate: String? = nil,
        baggageBelt: String? = nil,
        aircraftModel: String? = nil,
        aircraftReg: String? = nil,
        delayReason: String? = nil,
        live: LivePosition? = nil,
        lastPolledAt: Date? = nil,
        updatedAt: Date? = nil,
        depAirport: Airport? = nil,
        arrAirport: Airport? = nil,
        inboundFlightId: String? = nil,
        prediction: InboundPrediction? = nil,
        hasProInsight: Bool = false
    ) {
        self.id = id
        self.carrierIata = carrierIata
        self.flightNumber = flightNumber
        self.flightDate = flightDate
        self.depIata = depIata
        self.arrIata = arrIata
        self.airlineName = airlineName
        self.status = status
        self.schedDep = schedDep
        self.estDep = estDep
        self.actDep = actDep
        self.depTerminal = depTerminal
        self.depGate = depGate
        self.depCheckin = depCheckin
        self.schedArr = schedArr
        self.estArr = estArr
        self.actArr = actArr
        self.arrTerminal = arrTerminal
        self.arrGate = arrGate
        self.baggageBelt = baggageBelt
        self.aircraftModel = aircraftModel
        self.aircraftReg = aircraftReg
        self.delayReason = delayReason
        self.live = live
        self.lastPolledAt = lastPolledAt
        self.updatedAt = updatedAt
        self.depAirport = depAirport
        self.arrAirport = arrAirport
        self.inboundFlightId = inboundFlightId
        self.prediction = prediction
        self.hasProInsight = hasProInsight
    }

    public var designator: String { "\(carrierIata) \(flightNumber)" }
    public var depTz: String? { depAirport?.tz }
    public var arrTz: String? { arrAirport?.tz }
}

public struct TrackedFlight: Hashable, Sendable {
    public var id: String
    public var userId: String
    public var flightId: String
    public var pnr: String?
    public var seat: String?
    public var cabin: Cabin?
    public var source: TrackSource
    public var notifyPrefs: [AlertType: Bool]
    public var note: String?
    public var archivedAt: Date?
    public var createdAt: Date?

    public init(
        id: String,
        userId: String,
        flightId: String,
        pnr: String? = nil,
        seat: String? = nil,
        cabin: Cabin? = nil,
        source: TrackSource = .manual,
        notifyPrefs: [AlertType: Bool] = [:],
        note: String? = nil,
        archivedAt: Date? = nil,
        createdAt: Date? = nil
    ) {
        self.id = id
        self.userId = userId
        self.flightId = flightId
        self.pnr = pnr
        self.seat = seat
        self.cabin = cabin
        self.source = source
        self.notifyPrefs = notifyPrefs
        self.note = note
        self.archivedAt = archivedAt
        self.createdAt = createdAt
    }

    public var isArchived: Bool { archivedAt != nil }

    /// An alert is sent unless its preference is exactly false. Same rule as the server fan-out.
    public func notifies(_ type: AlertType) -> Bool { notifyPrefs[type] != false }
}

public struct TrackedFlightDetails: Hashable, Sendable {
    public var tracking: TrackedFlight
    public var flight: Flight

    public init(tracking: TrackedFlight, flight: Flight) {
        self.tracking = tracking
        self.flight = flight
    }
}

public struct TrackExtras: Hashable, Sendable {
    public static let maxSeatLength = 5
    public static let maxNoteLength = 200

    public var pnr: String?
    public var seat: String?
    public var cabin: Cabin?
    public var source: TrackSource
    public var note: String?

    public init(pnr: String? = nil, seat: String? = nil, cabin: Cabin? = nil, source: TrackSource = .manual, note: String? = nil) {
        self.pnr = pnr
        self.seat = seat
        self.cabin = cabin
        self.source = source
        self.note = note
    }
}

private let pnrPattern = try! NSRegularExpression(pattern: "^[A-Z0-9]{5,8}$")

/// Upper-cases and strips spaces and dashes, then applies `^[A-Z0-9]{5,8}$`. Nil when it cannot be a PNR.
public func normalizePnr(_ input: String?) -> String? {
    let cleaned = (input ?? "").unicodeScalars.filter { scalar in
        scalar != "-" && !CharacterSet.whitespacesAndNewlines.contains(scalar)
    }
    let text = String(String.UnicodeScalarView(cleaned)).uppercased()
    let range = NSRange(location: 0, length: (text as NSString).length)
    guard pnrPattern.firstMatch(in: text, options: [], range: range) != nil else { return nil }
    return text
}

public struct UserProfile: Hashable, Sendable {
    public static let freeTrackingLimit = 10
    public static let proTrackingLimit = 50

    public var id: String
    public var displayName: String?
    public var plan: Plan
    public var proUntil: Date?

    public init(id: String, displayName: String?, plan: Plan, proUntil: Date?) {
        self.id = id
        self.displayName = displayName
        self.plan = plan
        self.proUntil = proUntil
    }

    /// Same rule as SQL `is_pro()`.
    public func isPro(now: Date) -> Bool {
        plan == .pro && (proUntil == nil || (proUntil ?? now) > now)
    }

    public func trackingLimit(now: Date) -> Int {
        isPro(now: now) ? Self.proTrackingLimit : Self.freeTrackingLimit
    }
}

/// Keeps alert types this app version does not know when a prefs object is patched.
public func mergeNotifyPrefs(_ existing: [String: Bool], changes: [AlertType: Bool]) -> [String: Bool] {
    var merged = existing
    for (type, enabled) in changes {
        merged[type.wire] = enabled
    }
    return merged
}
