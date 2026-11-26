import Foundation

// "When to leave" (Pro; computed on the device), P2 wave 2 contract section 5. The numbers, the plan and the
// reminder rules are the same as Android's feature/leave; both apps test them against `leave_by_cases.json`.

/// The numbers of "When to leave". Identical in the Android app (`LeaveRules`).
public enum LeaveRules {
    public static let baseDomesticMin = 120
    public static let baseInternationalMin = 180
    /// Fog, or Airport Health `disrupted` or worse, at the departure airport.
    public static let fogExtraMin = 30
    /// Departure in a peak hour at the departure airport.
    public static let peakExtraMin = 20
    /// Travel time to an airport the user never set.
    public static let defaultTravelMin = 60
    public static let minTravelMin = 0
    public static let maxTravelMin = 600
    /// The local notification fires this long before `leaveBy`.
    public static let reminderBeforeMin = 15
    /// Peak hours, departure airport local time, half-open: [05:00, 09:00) and [17:00, 21:00).
    public static let peakHours: [Range<Int>] = [5..<9, 17..<21]
    /// Airport Health is only asked this close to departure (the weather is "now").
    public static let healthWindow: TimeInterval = 24 * 3600
}

/// Minutes kept before departure for the airport itself (check-in, security, boarding).
public func leaveBufferMinutes(international: Bool, fogAtDep: Bool, peak: Bool) -> Int {
    (international ? LeaveRules.baseInternationalMin : LeaveRules.baseDomesticMin)
        + (fogAtDep ? LeaveRules.fogExtraMin : 0)
        + (peak ? LeaveRules.peakExtraMin : 0)
}

/// `leaveBy = depTime − buffer − travelMinutes` (contract section 5). A negative travel time counts as 0.
public func leaveBy(depTime: Date, international: Bool, travelMinutes: Int, fogAtDep: Bool, peak: Bool) -> Date {
    let minutes = leaveBufferMinutes(international: international, fogAtDep: fogAtDep, peak: peak) + max(0, travelMinutes)
    return depTime.addingTimeInterval(-TimeInterval(minutes) * 60)
}

/// Departure in `LeaveRules.peakHours` at the departure airport (`depTz`; unknown = India).
public func isPeakHour(_ depTime: Date, depTz: String?) -> Bool {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = timeZone(for: depTz)
    let hour = calendar.component(.hour, from: depTime)
    return LeaveRules.peakHours.contains { $0.contains(hour) }
}

/// Travel minutes as stored: within `LeaveRules.minTravelMin ... maxTravelMin`.
public func clampTravelMinutes(_ minutes: Int) -> Int {
    min(LeaveRules.maxTravelMin, max(LeaveRules.minTravelMin, minutes))
}

extension AirportHealth {
    /// Airport Health slows the way to the gate: fog (detail or headline), or level `disrupted` or `severe`.
    public var slowsDeparture: Bool {
        if level >= .disrupted || detail?.weather?.fog == true { return true }
        if case .fog = typedHeadline { return true }
        return false
    }
}

/// What the card shows for one flight.
public struct LeavePlan: Hashable, Sendable {
    public var leaveBy: Date
    /// The departure the plan counts from: estimated, else scheduled.
    public var departure: Date
    public var depTz: String?
    public var travelMinutes: Int
    public var bufferMinutes: Int
    public var international: Bool
    public var fog: Bool
    public var peak: Bool

    public init(leaveBy: Date, departure: Date, depTz: String?, travelMinutes: Int, bufferMinutes: Int, international: Bool, fog: Bool, peak: Bool) {
        self.leaveBy = leaveBy
        self.departure = departure
        self.depTz = depTz
        self.travelMinutes = travelMinutes
        self.bufferMinutes = bufferMinutes
        self.international = international
        self.fog = fog
        self.peak = peak
    }

    /// When the "time to leave" notification fires.
    public var reminderAt: Date { leaveBy.addingTimeInterval(-TimeInterval(LeaveRules.reminderBeforeMin) * 60) }

    /// "05:40" at the departure airport.
    public var leaveByTime: String { FlightTimeFormat.time(leaveBy, tz: depTz) }
    public var departureTime: String { FlightTimeFormat.time(departure, tz: depTz) }
}

/// The plan for `flight`, or nil when there is nothing to plan: no departure time, the flight has left, was
/// cancelled or diverted, or its departure is already past.
public func leavePlan(flight: Flight, travelMinutes: Int, fogAtDep: Bool, now: Date) -> LeavePlan? {
    if flight.status == .cancelled || flight.status == .diverted || flight.hasDeparted { return nil }
    guard let departure = flight.estDep ?? flight.schedDep, departure > now else { return nil }
    let international = flight.isInternationalForCheckin
    let peak = isPeakHour(departure, depTz: flight.depTz)
    let travel = clampTravelMinutes(travelMinutes)
    return LeavePlan(
        leaveBy: leaveBy(depTime: departure, international: international, travelMinutes: travel, fogAtDep: fogAtDep, peak: peak),
        departure: departure,
        depTz: flight.depTz,
        travelMinutes: travel,
        bufferMinutes: leaveBufferMinutes(international: international, fogAtDep: fogAtDep, peak: peak),
        international: international,
        fog: fogAtDep,
        peak: peak
    )
}

/// Ask Airport Health for fog only within `LeaveRules.healthWindow` before departure.
public func leaveNeedsHealth(flight: Flight, now: Date) -> Bool {
    guard let departure = flight.estDep ?? flight.schedDep else { return false }
    return departure > now && departure.timeIntervalSince(now) <= LeaveRules.healthWindow
}

// MARK: - directions

/// What the maps apps search for: the airport name with terminal and city, else its coordinates, else
/// "<IATA> airport".
public func directionsDestination(airport: Airport?, iata: String, terminal: String?) -> String {
    if let airport, !airport.name.trimmingCharacters(in: .whitespaces).isEmpty {
        var text = airport.name
        if let terminal = terminal?.trimmingCharacters(in: .whitespacesAndNewlines), !terminal.isEmpty { text += " Terminal \(terminal)" }
        if let city = airport.city?.trimmingCharacters(in: .whitespacesAndNewlines), !city.isEmpty { text += ", \(city)" }
        return text
    }
    if let airport { return "\(airport.lat),\(airport.lon)" }
    return "\(iata) airport"
}

private func queryEncoded(_ text: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return text.addingPercentEncoding(withAllowedCharacters: allowed) ?? text
}

/// Google Maps directions (a universal link: the app when installed, the browser otherwise).
public func googleMapsDirectionsURL(airport: Airport?, iata: String, terminal: String?) -> URL? {
    URL(string: "https://www.google.com/maps/dir/?api=1&destination=" + queryEncoded(directionsDestination(airport: airport, iata: iata, terminal: terminal)))
}

/// Apple Maps driving directions to the airport. With coordinates the pin is exact and the name is its label.
public func appleMapsDirectionsURL(airport: Airport?, iata: String, terminal: String?) -> URL? {
    let name = queryEncoded(directionsDestination(airport: airport, iata: iata, terminal: terminal))
    if let airport {
        return URL(string: "https://maps.apple.com/?daddr=\(airport.lat),\(airport.lon)&dirflg=d&q=\(name)")
    }
    return URL(string: "https://maps.apple.com/?daddr=\(name)&dirflg=d")
}

// MARK: - the card and the reminder

/// The "When to leave" card.
public enum WhenToLeaveState: Hashable, Sendable {
    /// No card: not tracked (or archived), already gone, cancelled, or Pro state not known yet.
    case hidden
    /// Free user on a flight without the trial: the teaser.
    case locked
    case ready(WhenToLeaveCard)
}

public struct WhenToLeaveCard: Hashable, Sendable {
    public var plan: LeavePlan
    public var depIata: String
    /// City (or code) of the departure airport, for "to reach Delhi airport".
    public var depCity: String
    public var googleMapsURL: URL?
    public var appleMapsURL: URL?
    /// The notification is (or will be) scheduled: its time is still ahead.
    public var reminderAhead: Bool

    public init(plan: LeavePlan, depIata: String, depCity: String, googleMapsURL: URL?, appleMapsURL: URL?, reminderAhead: Bool) {
        self.plan = plan
        self.depIata = depIata
        self.depCity = depCity
        self.googleMapsURL = googleMapsURL
        self.appleMapsURL = appleMapsURL
        self.reminderAhead = reminderAhead
    }
}

/// The card for `flight` (nil: not tracked) at `now`. `proAccess` nil: not known yet.
public func whenToLeaveState(flight: Flight?, travelMinutes: Int, fogAtDep: Bool, proAccess: Bool?, now: Date) -> WhenToLeaveState {
    guard let flight, let proAccess,
          let plan = leavePlan(flight: flight, travelMinutes: travelMinutes, fogAtDep: fogAtDep, now: now) else { return .hidden }
    if !proAccess { return .locked }
    return .ready(WhenToLeaveCard(
        plan: plan,
        depIata: flight.depIata,
        depCity: flight.depAirport?.displayCity ?? flight.depIata,
        googleMapsURL: googleMapsDirectionsURL(airport: flight.depAirport, iata: flight.depIata, terminal: flight.depTerminal),
        appleMapsURL: appleMapsDirectionsURL(airport: flight.depAirport, iata: flight.depIata, terminal: flight.depTerminal),
        reminderAhead: plan.reminderAt > now
    ))
}

/// What to do with the flight's pending reminder.
public enum ReminderAction: Hashable, Sendable {
    case schedule(at: Date)
    case cancel
    /// The time has come (or the Pro state is not known): leave a pending reminder alone.
    case keep
}

/// Schedule while the reminder time is ahead; cancel when there is no card for a known Pro state; else keep.
public func reminderAction(_ state: WhenToLeaveState, proKnown: Bool) -> ReminderAction {
    switch state {
    case .ready(let card): return card.reminderAhead ? .schedule(at: card.plan.reminderAt) : .keep
    case .locked: return .cancel
    case .hidden: return proKnown ? .cancel : .keep
    }
}

/// iOS cannot re-check a plan when a scheduled notification fires (Android's worker does), so the app re-plans every
/// pending reminder whenever it hears about the flight: a sync push, an alert, the app coming to the foreground.
/// The fog flag the plan used travels in the pending request, as Airport Health is not asked from the background.
public enum LeaveReminderContract {
    /// Notification identifier: one pending reminder per flight, replaced when the time changes.
    public static func identifier(flightId: String) -> String { "\(flightId):leave" }
    public static let identifierSuffix = ":leave"
    public static let keyFog = "leave_fog"
    public static let kindLeave = "leave"

    /// The flight id of a reminder identifier, or nil for any other notification.
    public static func flightId(fromIdentifier identifier: String) -> String? {
        guard identifier.hasSuffix(identifierSuffix) else { return nil }
        let id = String(identifier.dropLast(identifierSuffix.count))
        return isFlightUUID(id) ? id : nil
    }
}

/// The re-plan of one pending reminder. `flight` nil: no longer tracked. `pro`: Pro or the trial on this flight.
public func replanReminder(flight: Flight?, travelMinutes: Int, fog: Bool, pro: Bool, now: Date) -> ReminderAction {
    reminderAction(whenToLeaveState(flight: flight, travelMinutes: travelMinutes, fogAtDep: fog, proAccess: pro, now: now), proKnown: true)
}

/// How long the user takes to reach each airport, entered once per airport (device-local).
@MainActor
public protocol TravelTimeStore: AnyObject {
    /// Minutes to `iata`; `LeaveRules.defaultTravelMin` until the user sets it.
    func travelMinutes(iata: String) -> Int
    /// Remembers `minutes` for `iata`, clamped to the allowed range.
    func setTravelMinutes(iata: String, minutes: Int)
}

/// Schedules the local "time to leave" notification at `leaveBy − 15 min`. Nothing reaches a server.
@MainActor
public protocol LeaveReminderScheduling: AnyObject {
    func schedule(flightId: String, plan: LeavePlan, designator: String, depCity: String) async
    func cancel(flightId: String) async
}

/// The card on the detail screen. The screen passes the flight and the Pro access in; the model asks Airport Health
/// for fog near departure, works out the card, and schedules or cancels the reminder while the screen is open.
@MainActor
public final class WhenToLeaveModel {
    public private(set) var state: WhenToLeaveState = .hidden
    public var onChange: ((WhenToLeaveState) -> Void)?
    private let flightId: String
    private let travel: TravelTimeStore
    private let scheduler: LeaveReminderScheduling
    private let health: AirportHealthSource?
    private let clock: FlyClock
    private var flight: Flight?
    private var pro: Bool?
    private var fog = false
    private var fogAskedFor: String?
    private var lastAction: ReminderAction?

    public init(flightId: String, travel: TravelTimeStore, scheduler: LeaveReminderScheduling, health: AirportHealthSource?, clock: FlyClock) {
        self.flightId = flightId
        self.travel = travel
        self.scheduler = scheduler
        self.health = health
        self.clock = clock
    }

    /// `flight`: the tracked, unarchived flight (nil otherwise). `pro`: Pro or the trial on it; nil while unknown.
    public func update(flight: Flight?, pro: Bool?) async {
        self.flight = flight
        self.pro = pro
        if let flight { await askHealthIfNeeded(flight) }
        await render()
    }

    /// "Change" on the card: the travel time to this flight's departure airport, remembered for next time.
    public func setTravelMinutes(_ minutes: Int) async {
        guard case .ready(let card) = state else { return }
        travel.setTravelMinutes(iata: card.depIata, minutes: minutes)
        await render()
    }

    public var travelMinutes: Int {
        flight.map { travel.travelMinutes(iata: $0.depIata) } ?? LeaveRules.defaultTravelMin
    }

    private func askHealthIfNeeded(_ flight: Flight) async {
        guard let health, leaveNeedsHealth(flight: flight, now: clock.now()),
              let departure = flight.estDep ?? flight.schedDep else { return }
        let key = "\(flight.depIata):\(Int(departure.timeIntervalSince1970) / 3600)"
        if key == fogAskedFor { return }
        fogAskedFor = key
        switch await health.health(iata: flight.depIata) {
        case .health(let value):
            fog = value.slowsDeparture
        case .unavailable, .notConfigured:
            fog = false
        case .offline, .failed:
            // A network blip must not drop fog for the rest of the visit: keep what is known, ask on the next update.
            fogAskedFor = nil
        }
    }

    private func render() async {
        let now = clock.now()
        let next = whenToLeaveState(flight: flight, travelMinutes: travelMinutes, fogAtDep: fog, proAccess: pro, now: now)
        if next != state {
            state = next
            onChange?(state)
        }
        let action = reminderAction(next, proKnown: pro != nil)
        guard action != lastAction else { return }
        lastAction = action
        switch action {
        case .schedule:
            if case .ready(let card) = next, let flight {
                await scheduler.schedule(flightId: flightId, plan: card.plan, designator: flight.designator, depCity: card.depCity)
            }
        case .cancel:
            await scheduler.cancel(flightId: flightId)
        case .keep:
            break
        }
    }
}
