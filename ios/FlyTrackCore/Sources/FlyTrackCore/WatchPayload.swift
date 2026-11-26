import Foundation

// P3 B9 Apple Watch: what the phone sends with WatchConnectivity `updateApplicationContext`. Each flight uses the
// compact flight shape of the sync push (`compactFlight` in supabase/functions/_shared/notify.ts: same short keys
// and meanings, + `ofb` / `rwd` / `rwa` / `onb` block times) plus what only the phone knows (the airports' zones and
// cities). Same keys as Android's Wear payload (`WearNextFlight`). The watch turns them into `WidgetFlight`s so the
// watch app and its complication choose and word the flight exactly like the phone widget.
// Live position and `updated_at` are left out on purpose: they change on every poll, and the watch should only wake
// when something it draws changes.

public enum WatchContract {
    /// `applicationContext` key; the value is the JSON text of a `WatchPayload`.
    public static let contextKey = "next_flight"
    public static let version = 1
    /// Upcoming flights sent, so the watch rolls over to the next one without the phone.
    public static let maxFlights = WidgetContract.maxFlights
    /// The watch app writes the last payload into this App Group (on the watch) for its complication. Same group id as
    /// the phone widget, so no second group has to be registered.
    public static let watchAppGroup = WidgetContract.appGroup
    public static let storedPayloadKey = "watch_payload_v1"
    /// WidgetKit kind of the complication.
    public static let complicationKind = "FlyTrackWatchComplication"
}

/// One flight in the compact shape.
public struct WatchFlight: Codable, Hashable, Sendable {
    public var id: String
    /// Carrier IATA, "6E".
    public var c: String
    /// Flight number, "2341".
    public var n: String
    /// Local departure date at the origin, "2026-11-25".
    public var d: String
    public var dep: String
    public var arr: String
    /// `FlightStatus` wire value.
    public var st: String
    public var sd: Date?
    public var ed: Date?
    public var ad: Date?
    public var sa: Date?
    public var ea: Date?
    public var aa: Date?
    public var dt: String?
    public var dg: String?
    public var at: String?
    public var ag: String?
    public var bb: String?
    /// Off-block, takeoff, touchdown, on-block (P3 A2).
    public var ofb: Date?
    public var rwd: Date?
    public var rwa: Date?
    public var onb: Date?
    /// IANA zones of the airports; nil when unknown (the watch then uses its own zone).
    public var dtz: String?
    public var atz: String?
    /// Display cities.
    public var dc: String?
    public var ac: String?

    public init(_ flight: Flight) {
        id = flight.id
        c = flight.carrierIata
        n = flight.flightNumber
        d = flight.flightDate.iso
        dep = flight.depIata
        arr = flight.arrIata
        st = flight.status.wire
        sd = flight.schedDep
        ed = flight.estDep
        ad = flight.actDep
        sa = flight.schedArr
        ea = flight.estArr
        aa = flight.actArr
        dt = flight.depTerminal
        dg = flight.depGate
        at = flight.arrTerminal
        ag = flight.arrGate
        bb = flight.baggageBelt
        ofb = flight.depOffblockAt
        rwd = flight.depRunwayAt
        rwa = flight.arrRunwayAt
        onb = flight.arrOnblockAt
        dtz = flight.depTz
        atz = flight.arrTz
        dc = flight.depAirport?.displayCity
        ac = flight.arrAirport?.displayCity
    }

    /// The widget's flight model, so the watch reuses `widgetFlight(in:at:)` and `widgetDisplay`.
    public var widgetFlight: WidgetFlight {
        WidgetFlight(
            id: id, designator: "\(c) \(n)", dep: dep, arr: arr, depCity: dc, arrCity: ac, depTz: dtz, arrTz: atz,
            status: st, schedDep: sd, estDep: ed, actDep: ad, schedArr: sa, estArr: ea, actArr: aa,
            depTerminal: dt, depGate: dg, arrTerminal: at, arrGate: ag, belt: bb,
            offblockAt: ofb, takeoffAt: rwd, touchdownAt: rwa, onblockAt: onb
        )
    }
}

public struct WatchPayload: Codable, Hashable, Sendable {
    public var v: Int
    /// When the phone wrote it.
    public var at: Date
    /// Upcoming and current flights, soonest first (the phone widget's choice).
    public var flights: [WatchFlight]

    public init(v: Int = WatchContract.version, at: Date, flights: [WatchFlight]) {
        self.v = v
        self.at = at
        self.flights = flights
    }

    /// The snapshot the watch draws from.
    public var widgetSnapshot: WidgetSnapshot {
        WidgetSnapshot(writtenAt: at, flights: flights.map(\.widgetFlight))
    }
}

/// The payload for the user's tracked flights: the same flights, in the same order, as the phone widget's snapshot.
public func watchPayload(flights: [Flight], now: Date) -> WatchPayload {
    var byId: [String: Flight] = [:]
    for flight in flights where byId[flight.id] == nil { byId[flight.id] = flight }
    // The widget snapshot's own order (its choice and sort), so the phone widget and the watch never disagree.
    let ordered = widgetSnapshot(flights: flights, now: now).flights.compactMap { byId[$0.id] }
    return WatchPayload(at: now, flights: ordered.prefix(WatchContract.maxFlights).map { WatchFlight($0) })
}

public enum WatchPayloadCodec {
    /// Times as ISO-8601 UTC ("2026-11-25T13:20:00Z"), like the sync push.
    public static func encode(_ payload: WatchPayload) -> String? {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(payload) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Nil for missing, unreadable or newer-version payloads (the watch keeps what it had).
    public static func decode(_ text: String?) -> WatchPayload? {
        guard let data = text?.data(using: .utf8) else { return nil }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let payload = try? decoder.decode(WatchPayload.self, from: data), payload.v <= WatchContract.version else { return nil }
        return payload
    }

    /// True when the watch would draw something different (the phone skips identical sends).
    public static func differs(_ old: WatchPayload?, _ new: WatchPayload) -> Bool {
        old?.flights != new.flights
    }
}

/// The watch side keeps the last payload text in its App Group so the complication can read it.
public enum WatchPayloadStorage {
    public static func save(_ text: String, in defaults: UserDefaults?) {
        defaults?.set(text, forKey: WatchContract.storedPayloadKey)
    }

    public static func load(from defaults: UserDefaults?) -> WatchPayload? {
        WatchPayloadCodec.decode(defaults?.string(forKey: WatchContract.storedPayloadKey))
    }
}
