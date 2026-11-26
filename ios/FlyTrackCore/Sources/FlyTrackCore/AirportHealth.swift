import Foundation

/// `get_airport_health.level` (P2 contract section 1), mildest first.
public enum HealthLevel: String, CaseIterable, Sendable, Equatable, Comparable {
    case normal, busy, disrupted, severe

    public var wire: String { rawValue }

    /// Status tone of the level pill. The level word always goes with it, so colour is never the only cue.
    public var tone: FlightStatusTone {
        switch self {
        case .normal: return .onTime
        case .busy, .disrupted: return .delayed
        case .severe: return .cancelled
        }
    }

    /// Nil for a level this app version does not know.
    public static func fromWire(_ value: String?) -> HealthLevel? {
        value.flatMap(HealthLevel.init(rawValue:))
    }

    public static func < (lhs: HealthLevel, rhs: HealthLevel) -> Bool {
        let order = allCases
        return order.firstIndex(of: lhs)! < order.firstIndex(of: rhs)!
    }
}

/// METAR flight category.
public enum FlightCategory: String, CaseIterable, Sendable, Equatable {
    case vfr = "VFR", mvfr = "MVFR", ifr = "IFR", lifr = "LIFR"
}

public struct AirportWeather: Hashable, Sendable {
    public var visibilityM: Int?
    public var category: FlightCategory?
    public var fog: Bool
    public var windKts: Int?
    /// Present weather, e.g. "FG", "BR", "HZ", "TSRA".
    public var wx: String?
    public var observedAt: Date?

    public init(visibilityM: Int?, category: FlightCategory?, fog: Bool, windKts: Int?, wx: String?, observedAt: Date?) {
        self.visibilityM = visibilityM
        self.category = category
        self.fog = fog
        self.windKts = windKts
        self.wx = wx
        self.observedAt = observedAt
    }
}

/// The Pro part of the answer; nil for free users.
public struct HealthDetail: Hashable, Sendable {
    /// The server applies delay rules only from this many departures in the last 3 h.
    public static let minDelaySample = 8

    public var avgDepDelayMin: Int?
    public var delayedPct: Int?
    public var cancelledToday: Int
    public var sampleSize: Int
    public var weather: AirportWeather?

    public init(avgDepDelayMin: Int?, delayedPct: Int?, cancelledToday: Int, sampleSize: Int, weather: AirportWeather?) {
        self.avgDepDelayMin = avgDepDelayMin
        self.delayedPct = delayedPct
        self.cancelledToday = cancelledToday
        self.sampleSize = sampleSize
        self.weather = weather
    }

    public var hasDelaySample: Bool { sampleSize >= Self.minDelaySample }
}

public struct AirportHealth: Hashable, Sendable {
    public var iata: String
    public var level: HealthLevel
    /// The server's English line; the app shows `typedHeadline` localised and falls back to this.
    public var headline: String
    public var updatedAt: Date?
    public var detail: HealthDetail?

    public init(iata: String, level: HealthLevel, headline: String, updatedAt: Date?, detail: HealthDetail?) {
        self.iata = iata
        self.level = level
        self.headline = headline
        self.updatedAt = updatedAt
        self.detail = detail
    }
}

/// Reads a `get_airport_health` answer. Nil when the level is missing or unknown to this app version (the card is
/// then left out rather than guessing), or when the JSON is not an object.
public func parseAirportHealth(_ data: Data, requestedIata: String) -> AirportHealth? {
    guard let object = JSON.object(from: data) else { return nil }
    return parseAirportHealth(object, requestedIata: requestedIata)
}

public func parseAirportHealth(_ object: [String: Any], requestedIata: String) -> AirportHealth? {
    guard let level = HealthLevel.fromWire(JSON.string(object["level"])) else { return nil }
    let detail: HealthDetail? = JSON.object(object["detail"]).map { d in
        let weather: AirportWeather? = JSON.object(d["weather"]).map { w in
            AirportWeather(
                visibilityM: JSON.int(w["visibility_m"]),
                category: JSON.string(w["category"]).flatMap(FlightCategory.init(rawValue:)),
                fog: JSON.bool(w["fog"]) ?? false,
                windKts: JSON.int(w["wind_kts"]),
                wx: JSON.string(w["wx"])?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
                observedAt: parseInstant(JSON.string(w["observed_at"]))
            )
        }
        return HealthDetail(
            avgDepDelayMin: JSON.int(d["avg_dep_delay_min"]),
            delayedPct: JSON.int(d["delayed_pct"]),
            cancelledToday: max(0, JSON.int(d["cancelled_today"]) ?? 0),
            sampleSize: max(0, JSON.int(d["sample_size"]) ?? 0),
            weather: weather
        )
    }
    let iata = JSON.string(object["iata"])?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? requestedIata
    return AirportHealth(
        iata: iata,
        level: level,
        headline: JSON.string(object["headline"]) ?? "",
        updatedAt: parseInstant(JSON.string(object["updated_at"])),
        detail: detail
    )
}

// MARK: - localised headline

/// The server's `headline` as a typed value, so the app can say it in the UI language. The server writes a small,
/// fixed set of English sentences (migration 13); anything else becomes `.other` and is shown as sent.
public enum HealthHeadline: Hashable, Sendable {
    case normal
    case fog(visibilityM: Int?)
    case thunderstorms
    case lowVisibility(Int)
    case lowCloud(Int)
    case poorWeather
    case cancellations(Int)
    case runningLate(Int)
    case delayedShare(Int)
    case other(String)
}

extension AirportHealth {
    /// Typed form of `headline`. When the sentence is unknown, the Pro weather fields (when present) still give a
    /// localised line before falling back to the English text.
    public var typedHeadline: HealthHeadline {
        let text = headline.trimmingCharacters(in: .whitespacesAndNewlines)
        func number(_ pattern: String) -> Int? {
            guard let match = RE.matches(pattern, in: text).first else { return nil }
            let (ns, result) = match
            guard result.numberOfRanges > 1, result.range(at: 1).location != NSNotFound else { return nil }
            return Int(ns.substring(with: result.range(at: 1)))
        }
        if let value = number(#"^Fog: visibility (\d+) m$"#) { return .fog(visibilityM: value) }
        if let value = number(#"^Low visibility: (\d+) m$"#) { return .lowVisibility(value) }
        if let value = number(#"^Low cloud: (\d+) ft$"#) { return .lowCloud(value) }
        if let value = number(#"^(\d+) flights cancelled today$"#) { return .cancellations(value) }
        if let value = number(#"^Departures running (\d+) min late$"#) { return .runningLate(value) }
        if let value = number(#"^(\d+)% of departures delayed$"#) { return .delayedShare(value) }
        switch text {
        case "Operating normally": return .normal
        case "Fog": return .fog(visibilityM: nil)
        case "Thunderstorms at the airport": return .thunderstorms
        case "Poor flying weather": return .poorWeather
        default: break
        }
        let weather = detail?.weather
        if weather?.fog == true { return .fog(visibilityM: weather?.visibilityM) }
        if weather?.wx?.contains("TS") == true { return .thunderstorms }
        if text.isEmpty && level == .normal { return .normal }
        return .other(text)
    }
}

// MARK: - which airports the detail screen shows

public enum HealthSide: String, Sendable, Equatable {
    case departure, arrival
}

/// Airports whose conditions still matter: both before take-off, only the destination in the air, none once
/// the flight has landed or was cancelled.
public func healthSides(_ phase: FlightPhase) -> [HealthSide] {
    switch phase {
    case .upcoming, .checkIn, .boarding: return [.departure, .arrival]
    case .inAir, .diverted: return [.arrival]
    case .landed, .cancelled: return []
    }
}

// MARK: - METAR present weather

/// The present-weather groups the card names in words, most important first.
public enum PresentWeather: String, Sendable, Equatable {
    case thunderstorm, fog, smoke, dust, haze, mist, rain
}

/// Main phenomenon of a METAR `wxString` such as "FG", "-RA BR", "TSRA", "HZ". Nil when empty or none of
/// `PresentWeather` (the raw string is then shown as it is).
public func presentWeather(_ wx: String?) -> PresentWeather? {
    let groups = (wx ?? "").uppercased().split(separator: " ").map(String.init).filter { !$0.isEmpty }
    if groups.isEmpty { return nil }
    func has(_ code: String) -> Bool { groups.contains { $0.contains(code) } }
    if has("TS") { return .thunderstorm }
    // "FG" includes MIFG/BCFG/PRFG/FZFG; "VCFG" (in the vicinity) still reads as fog for travellers.
    if has("FG") { return .fog }
    if has("FU") { return .smoke }
    if has("DU") || has("SA") || has("DS") || has("SS") { return .dust }
    if has("HZ") { return .haze }
    if has("BR") { return .mist }
    if has("RA") || has("DZ") || has("SH") { return .rain }
    return nil
}

// MARK: - loading

public enum AirportHealthResult: Sendable, Equatable {
    case health(AirportHealth)
    /// The server has no usable answer (unknown airport, unknown level).
    case unavailable
    case offline
    case notConfigured
    case failed
}

/// `get_airport_health(p_iata)`.
@MainActor
public protocol AirportHealthSource: AnyObject {
    func health(iata: String) async -> AirportHealthResult
}

/// One airport row of the Airport Health card.
public struct AirportHealthRow: Hashable, Sendable {
    public var side: HealthSide
    public var iata: String
    public var city: String
    public var health: AirportHealth

    public init(side: HealthSide, iata: String, city: String, health: AirportHealth) {
        self.side = side
        self.iata = iata
        self.city = city
        self.health = health
    }
}

/// The rows the card shows for `flight`, loaded in side order. Airports without a usable answer are left out.
/// The same airport is asked once even when it is both ends (a return to the gate).
@MainActor
public func loadAirportHealth(for flight: Flight, source: AirportHealthSource) async -> [AirportHealthRow] {
    var rows: [AirportHealthRow] = []
    var cache: [String: AirportHealthResult] = [:]
    for side in healthSides(flight.phase) {
        let iata = side == .departure ? flight.depIata : flight.arrIata
        let city = (side == .departure ? flight.depAirport : flight.arrAirport)?.displayCity ?? iata
        let code = iata.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard code.count == 3 else { continue }
        let result: AirportHealthResult
        if let cached = cache[code] {
            result = cached
        } else {
            result = await source.health(iata: code)
            cache[code] = result
        }
        if case .health(let health) = result {
            rows.append(AirportHealthRow(side: side, iata: code, city: city, health: health))
        }
    }
    return rows
}
