import Foundation

// Calendar (free), P2 wave 2 contract section 3, and the email forwarding address (section 2).

/// The feed URL the server returns: absolute https with a host and no whitespace. Anything else is not offered to
/// other apps.
public func isFeedURL(_ text: String) -> Bool {
    if text.contains(where: \.isWhitespace) { return false }
    guard let url = URL(string: text), url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty else { return false }
    return true
}

/// The same feed as `webcal://`: Apple Calendar subscribes from it (contract: "`webcal://` for iOS").
public func webcalURL(feedURL: String) -> URL? {
    guard isFeedURL(feedURL), let range = feedURL.range(of: "://") else { return nil }
    return URL(string: "webcal://" + feedURL[range.upperBound...])
}

/// Google Calendar's "add by URL" page for the feed; the calendar then syncs to the phone.
public func googleCalendarSubscribeURL(feedURL: String) -> URL? {
    guard let webcal = webcalURL(feedURL: feedURL)?.absoluteString else { return nil }
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    guard let encoded = webcal.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
    return URL(string: "https://calendar.google.com/calendar/r?cid=" + encoded)
}

/// One device calendar event ("Add to calendar" on the detail screen).
public struct CalendarEventSpec: Hashable, Sendable {
    public var title: String
    public var start: Date
    public var end: Date
    public var location: String?
    public var notes: String
    /// IANA zone of the departure airport, for the event's display zone.
    public var timeZone: String?

    public init(title: String, start: Date, end: Date, location: String?, notes: String, timeZone: String?) {
        self.title = title
        self.start = start
        self.end = end
        self.location = location
        self.notes = notes
        self.timeZone = timeZone
    }
}

/// "✈ 6E 2341 DEL→BOM": the same SUMMARY as the calendar feed.
public func calendarEventTitle(_ flight: Flight) -> String {
    "✈ \(flight.designator) \(flight.depIata)→\(flight.arrIata)"
}

/// An event without a usable arrival lasts this long.
public let calendarDefaultEventLength: TimeInterval = 2 * 3600

/// The event for `flight`: best known departure to best known arrival, or nil without a departure time. A missing or
/// impossible arrival becomes departure + 2 h, so the event always has a length.
///
/// - Parameters:
///   - terminalLocation: "Indira Gandhi International, Terminal 1" from (airport name, terminal), localised by the app.
///   - notes: the localised description (status line and app hint).
public func calendarEvent(
    for flight: Flight, terminalLocation: (_ airportName: String, _ terminal: String) -> String, notes: String
) -> CalendarEventSpec? {
    guard let start = flight.bestDeparture else { return nil }
    let end = flight.bestArrival.flatMap { $0 > start ? $0 : nil } ?? start.addingTimeInterval(calendarDefaultEventLength)
    let airportName = flight.depAirport?.name ?? flight.depIata
    let terminal = flight.depTerminal?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let location = terminal.isEmpty ? airportName : terminalLocation(airportName, terminal)
    return CalendarEventSpec(
        title: calendarEventTitle(flight), start: start, end: end, location: location, notes: notes, timeZone: flight.depTz
    )
}

/// `my_calendar_url()` / `rotate_calendar_token()`.
@MainActor
public protocol CalendarFeedSource: AnyObject {
    /// The https feed URL, created on the first call. Never throws.
    func feedURL() async -> RemoteResult<String>
    /// A new URL; the old one stops working at once. Never throws.
    func rotate() async -> RemoteResult<String>
}

// MARK: - forwarding address

/// A plausible `local@domain` address without spaces; anything else from the server is not shown.
public func isForwardingAddress(_ value: String) -> Bool {
    RE.contains(#"^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$"#, in: value)
}

/// `my_forwarding_address()`: "trips+{token}@{domain}", created on the first call. Never throws.
@MainActor
public protocol ForwardingAddressSource: AnyObject {
    func address() async -> RemoteResult<String>
}

/// A screen that shows one server-made link (forwarding address, calendar feed).
public enum LinkLoad: Equatable, Sendable {
    case loading
    case ready(String)
    case offline
    case notConfigured
    case failed
}

public func linkLoad(_ result: RemoteResult<String>) -> LinkLoad {
    switch result {
    case .ok(let value): return .ready(value)
    case .offline: return .offline
    case .notConfigured: return .notConfigured
    case .refused, .failed: return .failed
    }
}
