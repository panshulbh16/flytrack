import Foundation

/// One airline's web check-in rule, a row of `public.checkin_rules` (P1 contract section 3).
public struct CheckinRule: Hashable, Sendable {
    public var carrierIata: String
    public var opensHoursBefore: Int
    /// Domestic close, minutes before departure.
    public var closesMinutesBefore: Int
    public var closesMinutesBeforeIntl: Int
    /// Nil or not https: the card shows the window but no button.
    public var checkinUrl: String?
    /// False: the times are the airline's usual ones, not confirmed for this carrier.
    public var verified: Bool

    public init(
        carrierIata: String, opensHoursBefore: Int, closesMinutesBefore: Int, closesMinutesBeforeIntl: Int,
        checkinUrl: String?, verified: Bool
    ) {
        self.carrierIata = carrierIata
        self.opensHoursBefore = opensHoursBefore
        self.closesMinutesBefore = closesMinutesBefore
        self.closesMinutesBeforeIntl = closesMinutesBeforeIntl
        self.checkinUrl = checkinUrl
        self.verified = verified
    }
}

/// Where the flight stands in its web check-in window.
public enum CheckinWindow: Hashable, Sendable {
    /// "Web check-in opens in 6 hr".
    case notOpenYet(opensAt: Date, closesAt: Date)
    /// "Check in now →". `closingSoon` once the close is at most 30 min away (the CHECKIN_CLOSING alert).
    case open(opensAt: Date, closesAt: Date, closingSoon: Bool)
    /// Web check-in is over; only the airport counter is left.
    case closed(opensAt: Date, closesAt: Date)

    /// Same lead as the server's CHECKIN_CLOSING event: `closes_at − 30 min`.
    public static let closingSoonLead: TimeInterval = 30 * 60

    public var opensAt: Date {
        switch self {
        case .notOpenYet(let opensAt, _), .open(let opensAt, _, _), .closed(let opensAt, _): return opensAt
        }
    }

    public var closesAt: Date {
        switch self {
        case .notOpenYet(_, let closesAt), .open(_, let closesAt, _), .closed(_, let closesAt): return closesAt
        }
    }

    public var isOpen: Bool {
        if case .open = self { return true }
        return false
    }
}

extension Flight {
    /// Check-in counts from the estimated departure when there is one, else the timetable (same as the SQL trigger).
    public var checkinBaseDeparture: Date? { estDep ?? schedDep }

    /// International when the two airports are in different time zones. The app has no airport country, and for
    /// India-first traffic every Indian airport is Asia/Kolkata. An unknown zone counts as international, which only
    /// makes the close earlier.
    public var isInternationalForCheckin: Bool {
        guard let dep = depAirport?.tz, let arr = arrAirport?.tz else { return true }
        return dep != arr
    }
}

/// The window of `flight` under `rule` at `now`, or nil when there is nothing to show: no rule, no departure time,
/// or the flight is cancelled, diverted or already gone.
public func checkinWindow(rule: CheckinRule?, flight: Flight, now: Date) -> CheckinWindow? {
    guard let rule else { return nil }
    if flight.status == .cancelled || flight.status == .diverted || flight.hasDeparted { return nil }
    guard let departure = flight.checkinBaseDeparture, now < departure else { return nil }
    let closeMinutes = flight.isInternationalForCheckin ? rule.closesMinutesBeforeIntl : rule.closesMinutesBefore
    let opensAt = departure.addingTimeInterval(-TimeInterval(max(0, rule.opensHoursBefore)) * 3600)
    let closesAt = departure.addingTimeInterval(-TimeInterval(max(0, closeMinutes)) * 60)
    if now < opensAt { return .notOpenYet(opensAt: opensAt, closesAt: closesAt) }
    if now < closesAt {
        return .open(opensAt: opensAt, closesAt: closesAt, closingSoon: closesAt.timeIntervalSince(now) <= CheckinWindow.closingSoonLead)
    }
    return .closed(opensAt: opensAt, closesAt: closesAt)
}

/// `text` when it may be opened in a browser: an absolute https link with a host and no whitespace. Everything that
/// reaches the app from outside (a push, a table row) goes through this before `UIApplication.open`.
public func safeCheckinURL(_ text: String?) -> URL? {
    let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if trimmed.isEmpty || trimmed.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) }) { return nil }
    guard let url = URL(string: trimmed), url.scheme?.lowercased() == "https",
          let host = url.host, !host.isEmpty else { return nil }
    return url
}

/// The check-in card on the detail screen. Times are at the departure airport.
public struct CheckinCard: Hashable, Sendable {
    public var window: CheckinWindow
    /// Safe https link, or nil: then no button.
    public var url: URL?
    public var verified: Bool
    /// "Tue, 24 Nov" and "12:55" of the opening, departure airport time.
    public var opensDay: String
    public var opensTime: String
    public var closesTime: String
    /// Relative time to the opening (not open yet) or the close (open).
    public var countdown: RelativeTime?

    /// "Check in now →" only while the window is open and a link is known.
    public var canCheckInNow: Bool { window.isOpen && url != nil }
}

/// `checkinWindow` packed for the screen, or nil when the card is hidden.
public func checkinCard(rule: CheckinRule?, flight: Flight, now: Date) -> CheckinCard? {
    guard let window = checkinWindow(rule: rule, flight: flight, now: now) else { return nil }
    let countdown: RelativeTime?
    switch window {
    case .notOpenYet(let opensAt, _): countdown = relativeTime(target: opensAt, now: now)
    case .open(_, let closesAt, _): countdown = relativeTime(target: closesAt, now: now)
    case .closed: countdown = nil
    }
    return CheckinCard(
        window: window,
        url: safeCheckinURL(rule?.checkinUrl),
        verified: rule?.verified == true,
        opensDay: FlightTimeFormat.weekdayDayMonth(window.opensAt, tz: flight.depTz),
        opensTime: FlightTimeFormat.time(window.opensAt, tz: flight.depTz),
        closesTime: FlightTimeFormat.time(window.closesAt, tz: flight.depTz),
        countdown: countdown
    )
}

/// Rows of `select * from checkin_rules`. Rows missing a carrier or a time are skipped.
public func checkinRules(fromJSON data: Data) -> [CheckinRule] {
    guard let rows = JSON.array(from: data) else { return [] }
    return rows.compactMap { row in
        guard let carrier = JSON.string(row["carrier_iata"])?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(),
              !carrier.isEmpty,
              let opens = JSON.int(row["opens_hours_before"]),
              let closes = JSON.int(row["closes_minutes_before"]) else { return nil }
        return CheckinRule(
            carrierIata: carrier,
            opensHoursBefore: opens,
            closesMinutesBefore: closes,
            closesMinutesBeforeIntl: JSON.int(row["closes_minutes_before_intl"]) ?? closes,
            checkinUrl: JSON.string(row["checkin_url"]),
            verified: JSON.bool(row["verified"]) ?? false
        )
    }
}

/// The check-in rules the detail screen reads. The app caches the table; tests use a fake.
@MainActor
public protocol CheckinRuleSource: AnyObject {
    func rule(carrier: String) async -> CheckinRule?
}

/// What a notification tap opens.
public enum NotificationTap: Equatable, Sendable {
    /// A check-in reminder: the airline's page in the browser, with the flight opened behind it when known.
    case checkin(url: URL, flightId: String?)
    case openFlight(String)
    case ignore
}

/// Routes a tap on one of our notifications from its userInfo (FCM data keys sit at the top level of an APNs
/// payload, and the app's own foreground alerts use the same keys).
public func notificationTap(userInfo: [String: String]) -> NotificationTap {
    let flightId = userInfo[PushContract.keyFlightId]?.trimmingCharacters(in: .whitespacesAndNewlines)
    let validFlight = flightId.flatMap { isFlightUUID($0) ? $0 : nil }
    if let url = safeCheckinURL(userInfo[PushContract.keyCheckinUrl]) {
        return .checkin(url: url, flightId: validFlight)
    }
    if let validFlight { return .openFlight(validFlight) }
    return .ignore
}
