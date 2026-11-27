import Foundation

// P4 section 1 (docs/P4_CONTRACTS.md, notes in section 7): ground and proximity radar, Pro only. The `radar` Edge
// Function is the only source; this file holds its names, the answer parser, the error mapping and the screen's state
// machine (refresh every 10 s while visible, coarse location asked for on tap only). Same rules as Android's
// `feature/radar`.

public enum RadarContract {
    /// `GET /functions/v1/radar?mode=ground&iata=DEL` or `?mode=proximity&lat=28.6&lon=77.2`, user JWT.
    public static let function = "radar"
    public static let groundRadiusKm = 5.0
    public static let proximityRadiusKm = 75.0
    /// The screen refreshes this often while visible (also the server's cache time).
    public static let refreshInterval: TimeInterval = 10
    /// After `rate_limited` (60 requests per user per 10 minutes) the screen waits this long before asking again.
    public static let rateLimitedRetry: TimeInterval = 60
    /// The server rounds proximity coordinates to this cell; the app rounds before sending as well, so the precise
    /// location never leaves the phone.
    public static let cellDegrees = 0.05
    /// A coarse fix older than this is asked for again on the next refresh.
    public static let locationMaxAge: TimeInterval = 5 * 60
}

/// Rounds to the nearest 0.05° with a clean decimal (28.6123 → 28.6, 77.2249 → 77.2), as `roundToCell` on the server.
public func roundToRadarCell(_ value: Double) -> Double {
    let step = RadarContract.cellDegrees
    return ((value / step).rounded() * step * 100).rounded() / 100
}

public enum RadarMode: String, Sendable, Equatable {
    case ground, proximity
}

/// One request to the `radar` function.
public enum RadarRequest: Hashable, Sendable {
    /// Aircraft on the ground within 5 km of the airport (IATA, upper-case).
    case ground(iata: String)
    /// Aircraft within 75 km of a point; the point is rounded to the 0.05° cell here.
    case proximity(GeoPoint)

    public var mode: RadarMode {
        switch self {
        case .ground: return .ground
        case .proximity: return .proximity
        }
    }

    /// The query string items in a stable order. Nil for an IATA code that is not three letters or a point off the
    /// globe: the request is never sent (the server would answer `bad_input`).
    public var queryItems: [URLQueryItem]? {
        switch self {
        case .ground(let iata):
            let code = iata.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard code.count == 3, code.allSatisfy({ $0.isASCII && $0.isLetter }) else { return nil }
            return [URLQueryItem(name: "mode", value: "ground"), URLQueryItem(name: "iata", value: code)]
        case .proximity(let point):
            guard point.lat.isFinite, point.lon.isFinite, abs(point.lat) <= 90, abs(point.lon) <= 180 else { return nil }
            return [
                URLQueryItem(name: "mode", value: "proximity"),
                URLQueryItem(name: "lat", value: radarCoordinateText(roundToRadarCell(point.lat))),
                URLQueryItem(name: "lon", value: radarCoordinateText(roundToRadarCell(point.lon))),
            ]
        }
    }
}

/// "28.6", "77.25", "-0.05": at most two decimals, no trailing zeros, never "-0".
func radarCoordinateText(_ value: Double) -> String {
    let hundredths = Int((value * 100).rounded())
    if hundredths == 0 { return "0" }
    let sign = hundredths < 0 ? "-" : ""
    let whole = abs(hundredths) / 100
    let fraction = abs(hundredths) % 100
    if fraction == 0 { return "\(sign)\(whole)" }
    if fraction % 10 == 0 { return "\(sign)\(whole).\(fraction / 10)" }
    return "\(sign)\(whole).\(fraction < 10 ? "0" : "")\(fraction)"
}

/// One aircraft of the answer (`AircraftPosition & { flight? }`).
public struct RadarAircraft: Hashable, Sendable, Identifiable {
    /// 24-bit ICAO address, lower-case hex.
    public var icao24: String
    /// Trimmed ATC callsign ("IGO2341"), "" when the aircraft sends none.
    public var callsign: String
    public var reg: String?
    public var position: GeoPoint
    public var altitudeFt: Int
    public var groundSpeedKts: Int
    /// Track over ground, 0..<360, north = 0.
    public var trackDeg: Double
    public var onGround: Bool
    public var verticalRateFpm: Int?
    public var seenAt: Date?
    /// Our flight when the server matched the callsign ("6E2341", no space).
    public var flight: String?

    public var id: String { icao24 }

    public init(
        icao24: String, callsign: String, reg: String? = nil, position: GeoPoint, altitudeFt: Int, groundSpeedKts: Int,
        trackDeg: Double, onGround: Bool, verticalRateFpm: Int? = nil, seenAt: Date? = nil, flight: String? = nil
    ) {
        self.icao24 = icao24
        self.callsign = callsign
        self.reg = reg
        self.position = position
        self.altitudeFt = altitudeFt
        self.groundSpeedKts = groundSpeedKts
        self.trackDeg = trackDeg
        self.onGround = onGround
        self.verticalRateFpm = verticalRateFpm
        self.seenAt = seenAt
        self.flight = flight
    }

    /// The matched flight as the app writes it ("6E 2341"), nil when unmatched or unreadable.
    public var flightDisplay: String? { flight.flatMap(parseFlightNumber)?.display }

    /// The map label: our flight, else the callsign, else the registration, else the ICAO address.
    public var label: String {
        if let flightDisplay { return flightDisplay }
        if !callsign.isEmpty { return callsign }
        if let reg, !reg.isEmpty { return reg }
        return icao24.uppercased()
    }
}

/// The `radar` answer.
public struct RadarSnapshot: Hashable, Sendable {
    public var center: GeoPoint
    public var radiusKm: Double
    public var aircraft: [RadarAircraft]
    /// "opensky" | "adsbx".
    public var feed: String
    public var asOf: Date?

    public init(center: GeoPoint, radiusKm: Double, aircraft: [RadarAircraft], feed: String, asOf: Date?) {
        self.center = center
        self.radiusKm = radiusKm
        self.aircraft = aircraft
        self.feed = feed
        self.asOf = asOf
    }
}

/// Nil when the body has no centre or no aircraft list. Aircraft without an address or a position are skipped;
/// tracks are normalised to 0..<360.
public func parseRadarResponse(_ data: Data) -> RadarSnapshot? {
    guard let o = JSON.object(from: data), let center = JSON.object(o["center"]),
          let lat = JSON.double(center["lat"]), let lon = JSON.double(center["lon"]),
          let list = o["aircraft"] as? [Any] else { return nil }
    let aircraft = list.compactMap { $0 as? [String: Any] }.compactMap(parseRadarAircraft)
    return RadarSnapshot(
        center: GeoPoint(lat: lat, lon: lon),
        radiusKm: JSON.double(o["radius_km"]) ?? RadarContract.proximityRadiusKm,
        aircraft: aircraft,
        feed: JSON.string(o["feed"]) ?? "",
        asOf: parseInstant(JSON.string(o["as_of"]))
    )
}

func parseRadarAircraft(_ o: [String: Any]) -> RadarAircraft? {
    guard let icao = JSON.string(o["icao24"])?.trimmingCharacters(in: .whitespaces).lowercased(), !icao.isEmpty,
          let lat = JSON.double(o["lat"]), let lon = JSON.double(o["lon"]),
          lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
    let track = JSON.double(o["track_deg"]) ?? 0
    let normalized = track.isFinite ? (track.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) : 0
    func whole(_ key: String) -> Int? { JSON.double(o[key]).flatMap { $0.isFinite ? Int($0.rounded()) : nil } }
    let reg = JSON.string(o["reg"])?.trimmingCharacters(in: .whitespaces)
    let flight = JSON.string(o["flight"])?.trimmingCharacters(in: .whitespaces)
    return RadarAircraft(
        icao24: icao,
        callsign: (JSON.string(o["callsign"]) ?? "").trimmingCharacters(in: .whitespaces),
        reg: (reg?.isEmpty ?? true) ? nil : reg,
        position: GeoPoint(lat: lat, lon: lon),
        altitudeFt: max(0, whole("alt_ft") ?? 0),
        groundSpeedKts: max(0, whole("gs_kts") ?? 0),
        trackDeg: normalized,
        onGround: JSON.bool(o["on_ground"]) ?? false,
        verticalRateFpm: whole("vrate_fpm"),
        seenAt: parseInstant(JSON.string(o["seen_at"])),
        flight: (flight?.isEmpty ?? true) ? nil : flight
    )
}

/// Why a radar request gave no aircraft.
public enum RadarFailure: Equatable, Sendable {
    /// `pro_required` 402.
    case proRequired
    /// `feed_unavailable` / `feed_unlicensed` 503: "Radar is coming soon", never an error.
    case comingSoon
    /// `feed_error` 502: the feed failed this time; the same friendly state, retried on the next tick.
    case feedError
    /// `rate_limited` 429.
    case rateLimited
    /// `not_found` 404: an airport the server does not know.
    case unknownAirport
    /// `bad_input` 400 or `unauthorized` 401, and any answer the app cannot read.
    case failed
    case offline
    case notConfigured
}

/// Maps an HTTP status and the `{ "error": "..." }` body to a failure. The body wins over the status.
public func radarFailure(status: Int, body: Data?) -> RadarFailure {
    let code = body.flatMap(JSON.object(from:)).flatMap { JSON.string($0["error"]) }
    switch code {
    case "pro_required": return .proRequired
    case "feed_unavailable", "feed_unlicensed": return .comingSoon
    case "feed_error": return .feedError
    case "rate_limited": return .rateLimited
    case "not_found": return .unknownAirport
    case .some: return .failed
    case nil: break
    }
    switch status {
    case 402: return .proRequired
    case 429: return .rateLimited
    case 404: return .unknownAirport
    case 502: return .feedError
    case 503: return .comingSoon
    default: return .failed
    }
}

public enum RadarFetchResult: Equatable, Sendable {
    case ok(RadarSnapshot)
    case failure(RadarFailure)
}

/// The `radar` function (app: `LiveRadar`).
@MainActor
public protocol RadarFetching: AnyObject {
    func radar(_ request: RadarRequest) async -> RadarFetchResult
}

// MARK: - coarse location

public enum CoarseLocationAuthorization: Sendable, Equatable {
    case notDetermined
    /// When in use (reduced accuracy is enough).
    case authorized
    case denied
    /// Parental controls or MDM.
    case restricted
}

public enum CoarseLocationResult: Equatable, Sendable {
    case located(GeoPoint)
    case denied
    /// No fix (airplane mode indoors, a timeout, location services off).
    case unavailable
}

/// When-in-use, reduced-accuracy location (app: `CoarseLocation` on CLLocationManager). Never asked before a tap.
@MainActor
public protocol CoarseLocating: AnyObject {
    func authorization() -> CoarseLocationAuthorization
    /// Asks for permission when not determined yet, then for one coarse fix.
    func requestCoarseLocation() async -> CoarseLocationResult
}

// MARK: - screen

/// What the radar screen shows planes around.
public enum RadarTarget: Hashable, Sendable {
    /// "Ground radar" from an airport card.
    case airport(String)
    /// "Planes near me": the user's coarse location, asked for on tap.
    case nearMe
    /// Planes near a flight's live or estimated position (no location permission needed).
    case point(GeoPoint)

    public var mode: RadarMode {
        if case .airport = self { return .ground }
        return .proximity
    }
}

public enum RadarPhase: Equatable, Sendable {
    /// Near me, before the tap: "Show planes near me" (the permission is asked only then).
    case needsLocation
    case locating
    case loading
    /// Aircraft on screen (possibly none).
    case ready
    /// Feed unavailable, unlicensed or failing: "Radar is coming soon".
    case comingSoon
    case proRequired
    /// Location denied or restricted: explain and offer the Settings app.
    case locationDenied
    /// No fix: "Couldn't find where you are".
    case locationUnavailable
    case unknownAirport
    /// Nothing shown yet and the phone is offline.
    case offline
    case failed
    case notConfigured
}

/// A short line over a map that still shows the last answer.
public enum RadarNotice: Equatable, Sendable {
    case offline
    case rateLimited
    case feedError
}

/// One plane as the map and the list draw it.
public struct RadarRow: Hashable, Sendable, Identifiable {
    public var id: String
    public var label: String
    public var position: GeoPoint
    /// Icon rotation, north-up map.
    public var rotationDeg: Double
    public var altitudeFt: Int
    public var groundSpeedKts: Int
    public var onGround: Bool
    public var distanceKm: Double
    /// Matched flight ("6E 2341") for "Track this flight", nil when unmatched.
    public var flight: String?
    /// The callsign when the label is the flight (shown underneath).
    public var callsign: String?
}

public struct RadarState: Equatable, Sendable {
    public var target: RadarTarget
    public var phase: RadarPhase
    public var center: GeoPoint?
    public var radiusKm: Double
    public var rows: [RadarRow]
    public var notice: RadarNotice?
    public var asOf: Date?
    public var feed: String?

    public init(target: RadarTarget) {
        self.target = target
        phase = target == .nearMe ? .needsLocation : .loading
        radiusKm = target.mode == .ground ? RadarContract.groundRadiusKm : RadarContract.proximityRadiusKm
        rows = []
        if case .point(let point) = target { center = point }
    }

    /// True while a map with planes (or an empty sky) is on screen.
    public var showsMap: Bool { phase == .ready }
}

/// The rows of a snapshot: ground mode keeps aircraft on the ground only; nearest first, then by label.
public func radarRows(_ snapshot: RadarSnapshot, mode: RadarMode) -> [RadarRow] {
    snapshot.aircraft
        .filter { mode == .proximity || $0.onGround }
        .map { aircraft in
            RadarRow(
                id: aircraft.icao24,
                label: aircraft.label,
                position: aircraft.position,
                rotationDeg: aircraft.trackDeg,
                altitudeFt: aircraft.onGround ? 0 : aircraft.altitudeFt,
                groundSpeedKts: aircraft.groundSpeedKts,
                onGround: aircraft.onGround,
                distanceKm: GreatCircle.distanceKm(snapshot.center, aircraft.position),
                flight: aircraft.flightDisplay,
                callsign: aircraft.flightDisplay != nil && !aircraft.callsign.isEmpty ? aircraft.callsign : nil
            )
        }
        .sorted { a, b in
            if abs(a.distanceKm - b.distanceKm) > 0.0001 { return a.distanceKm < b.distanceKm }
            return a.label < b.label
        }
}

/// Drives the radar screen. The view calls `start()` when it appears, then loops `await refresh()` every
/// `nextRefreshDelay` while it is visible and the app is active; `nil` stops the loop.
@MainActor
public final class RadarModel {
    public private(set) var state: RadarState
    public var onChange: ((RadarState) -> Void)?

    private let fetcher: RadarFetching
    private let location: CoarseLocating
    private let clock: FlyClock
    private var fix: (point: GeoPoint, at: Date)?
    private var inFlight = false

    public init(target: RadarTarget, fetcher: RadarFetching, location: CoarseLocating, clock: FlyClock) {
        state = RadarState(target: target)
        self.fetcher = fetcher
        self.location = location
        self.clock = clock
    }

    /// On appear. Near me starts by itself only when permission was already given; otherwise it waits for the tap.
    public func start() async {
        if state.target == .nearMe {
            switch location.authorization() {
            case .authorized: await locateAndLoad()
            case .denied, .restricted:
                state.phase = .locationDenied
                publish()
            case .notDetermined:
                state.phase = .needsLocation
                publish()
            }
            return
        }
        await refresh()
    }

    /// "Show planes near me" (and "Try again" after a failed fix).
    public func useMyLocation() async {
        guard state.target == .nearMe else { return }
        fix = nil
        await locateAndLoad()
    }

    /// Seconds until the next refresh, or nil when refreshing cannot change anything (no Pro, no feed, no location yet).
    public var nextRefreshDelay: TimeInterval? {
        switch state.phase {
        case .ready, .offline, .failed, .loading:
            return state.notice == .rateLimited ? RadarContract.rateLimitedRetry : RadarContract.refreshInterval
        case .comingSoon:
            // feed_error retries on the next tick; an unavailable or unlicensed feed does not come back by itself.
            return state.notice == .feedError ? RadarContract.refreshInterval : nil
        case .needsLocation, .locating, .proRequired, .locationDenied, .locationUnavailable, .unknownAirport, .notConfigured:
            return nil
        }
    }

    /// One request. Overlapping calls are dropped.
    public func refresh() async {
        guard !inFlight else { return }
        let request: RadarRequest
        switch state.target {
        case .airport(let iata): request = .ground(iata: iata)
        case .point(let point): request = .proximity(point)
        case .nearMe:
            guard let fix else { return }
            if clock.now().timeIntervalSince(fix.at) > RadarContract.locationMaxAge, location.authorization() == .authorized {
                if case .located(let point) = await location.requestCoarseLocation() { self.fix = (point, clock.now()) }
            }
            request = .proximity(self.fix?.point ?? fix.point)
        }
        inFlight = true
        defer { inFlight = false }
        // A spinner only before the first answer: a retry from "coming soon", offline or failed keeps that card on
        // screen instead of flashing a spinner every 10 s.
        if state.phase == .locating {
            state.phase = .loading
            publish()
        }
        apply(await fetcher.radar(request))
    }

    private func locateAndLoad() async {
        state.phase = .locating
        publish()
        switch await location.requestCoarseLocation() {
        case .located(let point):
            fix = (point, clock.now())
            state.center = GeoPoint(lat: roundToRadarCell(point.lat), lon: roundToRadarCell(point.lon))
            await refresh()
        case .denied:
            state.phase = .locationDenied
            publish()
        case .unavailable:
            state.phase = .locationUnavailable
            publish()
        }
    }

    private func apply(_ result: RadarFetchResult) {
        switch result {
        case .ok(let snapshot):
            state.center = snapshot.center
            state.radiusKm = snapshot.radiusKm
            state.rows = radarRows(snapshot, mode: state.target.mode)
            state.asOf = snapshot.asOf ?? clock.now()
            state.feed = snapshot.feed.isEmpty ? nil : snapshot.feed
            state.notice = nil
            state.phase = .ready
        case .failure(let failure):
            applyFailure(failure)
        }
        publish()
    }

    private func applyFailure(_ failure: RadarFailure) {
        let showing = state.phase == .ready
        switch failure {
        case .proRequired:
            state.rows = []
            state.notice = nil
            state.phase = .proRequired
        case .comingSoon:
            state.rows = []
            state.notice = nil
            state.phase = .comingSoon
        case .feedError:
            // A map already on screen keeps its planes with a short note; otherwise the friendly empty state.
            state.notice = .feedError
            if !showing { state.phase = .comingSoon }
        case .rateLimited:
            state.notice = .rateLimited
            if !showing { state.phase = .failed }
        case .offline:
            state.notice = .offline
            if !showing { state.phase = .offline }
        case .unknownAirport:
            state.rows = []
            state.notice = nil
            state.phase = .unknownAirport
        case .notConfigured:
            state.notice = nil
            state.phase = .notConfigured
        case .failed:
            if !showing { state.phase = .failed }
        }
    }

    private func publish() { onChange?(state) }
}
