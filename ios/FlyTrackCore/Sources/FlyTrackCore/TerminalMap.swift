import Foundation

// P4 section 4 (docs/P4_CONTRACTS.md): terminal maps from OpenStreetMap, bundled per airport as GeoJSON
// (`data/terminal-maps/{IATA}.geojson`, copied to `ios/FlyTrack/Resources/TerminalMaps/`; format in
// `data/terminal-maps/README.md`). The app draws the geometry with MKGeoJSONDecoder; this file reads the same bytes
// for what the screen needs besides drawing: the user's gate and belt, the terminal, the POI list and its search.
// Works offline. Same rules as Android's `feature/terminalmap`.

public enum TerminalMapContract {
    /// Shown on every map (ODbL attribution).
    public static let attribution = "© OpenStreetMap contributors (ODbL)"
    public static let copyrightURL = "https://www.openstreetmap.org/copyright"
    /// Airports the build script covers. A missing file means "Terminal map not available yet".
    public static let airports = ["DEL", "BOM", "BLR", "HYD", "MAA", "CCU", "COK", "AMD"]
    /// `Bundle.main.url(forResource: iata, withExtension: fileExtension, subdirectory: resourceDirectory)`.
    public static let resourceDirectory = "TerminalMaps"
    public static let fileExtension = "geojson"
}

/// `properties.kind`.
public enum TerminalFeatureKind: String, Sendable, Equatable, CaseIterable {
    case terminal, apron, runway, taxiway
    case jetBridge = "jet_bridge"
    case baggageClaim = "baggage_claim"
    case gate
    case parkingPosition = "parking_position"
    case toilets, food, atm, lounge
    case prayerRoom = "prayer_room"
    case pharmacy, charging, shop

    public var poiCategory: POICategory? {
        switch self {
        case .toilets: return .toilets
        case .food: return .food
        case .atm: return .atm
        case .lounge: return .lounge
        case .prayerRoom: return .prayerRoom
        case .pharmacy: return .pharmacy
        case .charging: return .charging
        case .shop: return .shop
        default: return nil
        }
    }
}

/// The POI list's groups, in list order.
public enum POICategory: String, Sendable, Equatable, CaseIterable {
    case food, toilets, lounge, atm, prayerRoom, pharmacy, charging, shop
}

public enum TerminalGeometryType: String, Sendable, Equatable {
    case point = "Point"
    case lineString = "LineString"
    case polygon = "Polygon"
    case multiPolygon = "MultiPolygon"
    case multiLineString = "MultiLineString"
}

public struct GeoBounds: Hashable, Sendable {
    public var minLat: Double
    public var minLon: Double
    public var maxLat: Double
    public var maxLon: Double

    public init(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) {
        self.minLat = minLat
        self.minLon = minLon
        self.maxLat = maxLat
        self.maxLon = maxLon
    }

    public var center: GeoPoint { GeoPoint(lat: (minLat + maxLat) / 2, lon: (minLon + maxLon) / 2) }

    public func union(_ other: GeoBounds) -> GeoBounds {
        GeoBounds(
            minLat: min(minLat, other.minLat), minLon: min(minLon, other.minLon),
            maxLat: max(maxLat, other.maxLat), maxLon: max(maxLon, other.maxLon)
        )
    }

    static func of(_ points: [GeoPoint]) -> GeoBounds? {
        guard let first = points.first else { return nil }
        var b = GeoBounds(minLat: first.lat, minLon: first.lon, maxLat: first.lat, maxLon: first.lon)
        for p in points.dropFirst() {
            b.minLat = min(b.minLat, p.lat)
            b.minLon = min(b.minLon, p.lon)
            b.maxLat = max(b.maxLat, p.lat)
            b.maxLon = max(b.maxLon, p.lon)
        }
        return b
    }
}

public struct TerminalFeature: Hashable, Sendable, Identifiable {
    /// "way/123", "node/45".
    public var id: String
    public var kind: TerminalFeatureKind
    public var ref: String?
    public var name: String?
    public var level: String?
    public var geometry: TerminalGeometryType
    /// Where a label or a pin goes: the point itself, the middle vertex of a line, the centre of a polygon's box.
    public var anchor: GeoPoint
    public var bounds: GeoBounds

    public init(
        id: String, kind: TerminalFeatureKind, ref: String?, name: String?, level: String?, geometry: TerminalGeometryType,
        anchor: GeoPoint, bounds: GeoBounds
    ) {
        self.id = id
        self.kind = kind
        self.ref = ref
        self.name = name
        self.level = level
        self.geometry = geometry
        self.anchor = anchor
        self.bounds = bounds
    }
}

public struct TerminalMap: Hashable, Sendable {
    public var airport: String
    public var attribution: String
    public var features: [TerminalFeature]
    /// The box around the terminals (all features when the file has none), for the first camera.
    public var bounds: GeoBounds?

    public var gates: [TerminalFeature] { features.filter { $0.kind == .gate } }
    public var terminals: [TerminalFeature] { features.filter { $0.kind == .terminal } }
}

/// Reads one bundled file. Features of an unknown kind or without usable coordinates are skipped. The attribution is
/// always the contract's, whatever the file says. Nil when the body is not a FeatureCollection.
public func parseTerminalMap(_ data: Data, iata: String) -> TerminalMap? {
    guard let o = JSON.object(from: data), JSON.string(o["type"]) == "FeatureCollection",
          let list = o["features"] as? [Any] else { return nil }
    var features: [TerminalFeature] = []
    for (index, item) in list.enumerated() {
        guard let f = item as? [String: Any], let props = JSON.object(f["properties"]),
              let kind = JSON.string(props["kind"]).flatMap(TerminalFeatureKind.init(rawValue:)),
              let geometry = JSON.object(f["geometry"]),
              let type = JSON.string(geometry["type"]).flatMap(TerminalGeometryType.init(rawValue:)),
              let shape = terminalShape(type: type, coordinates: geometry["coordinates"]) else { continue }
        let id = JSON.string(f["id"]) ?? JSON.int(f["id"]).map(String.init) ?? "feature/\(index)"
        features.append(TerminalFeature(
            id: id, kind: kind,
            ref: cleanText(props["ref"]), name: cleanText(props["name"]), level: cleanText(props["level"]),
            geometry: type, anchor: shape.anchor, bounds: shape.bounds
        ))
    }
    let terminalBounds = features.filter { $0.kind == .terminal }.map(\.bounds)
    let all = terminalBounds.isEmpty ? features.map(\.bounds) : terminalBounds
    let bounds = all.dropFirst().reduce(all.first) { acc, b in acc?.union(b) }
    let airport = JSON.string(o["airport"])?.uppercased() ?? iata.uppercased()
    return TerminalMap(airport: airport, attribution: TerminalMapContract.attribution, features: features, bounds: bounds)
}

private func cleanText(_ value: Any?) -> String? {
    let text = (JSON.string(value) ?? JSON.int(value).map(String.init))?.trimmingCharacters(in: .whitespacesAndNewlines)
    return (text?.isEmpty ?? true) ? nil : text
}

/// GeoJSON positions are [lon, lat].
private func position(_ value: Any?) -> GeoPoint? {
    guard let pair = value as? [Any], pair.count >= 2, let lon = JSON.double(pair[0]), let lat = JSON.double(pair[1]),
          lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else { return nil }
    return GeoPoint(lat: lat, lon: lon)
}

private func positions(_ value: Any?) -> [GeoPoint] { (value as? [Any] ?? []).compactMap(position) }

private func terminalShape(type: TerminalGeometryType, coordinates: Any?) -> (anchor: GeoPoint, bounds: GeoBounds)? {
    switch type {
    case .point:
        guard let p = position(coordinates) else { return nil }
        return (p, GeoBounds(minLat: p.lat, minLon: p.lon, maxLat: p.lat, maxLon: p.lon))
    case .lineString:
        let line = positions(coordinates)
        guard let bounds = GeoBounds.of(line) else { return nil }
        return (line[line.count / 2], bounds)
    case .multiLineString:
        let lines = (coordinates as? [Any] ?? []).map(positions).filter { !$0.isEmpty }
        guard let longest = lines.max(by: { $0.count < $1.count }), let bounds = GeoBounds.of(lines.flatMap { $0 }) else { return nil }
        return (longest[longest.count / 2], bounds)
    case .polygon:
        // The exterior ring only.
        guard let outer = (coordinates as? [Any])?.first, let bounds = GeoBounds.of(positions(outer)) else { return nil }
        return (bounds.center, bounds)
    case .multiPolygon:
        let outers = (coordinates as? [Any] ?? []).compactMap { ($0 as? [Any])?.first }.map(positions).filter { !$0.isEmpty }
        guard let bounds = GeoBounds.of(outers.flatMap { $0 }) else { return nil }
        // The largest part holds the label.
        let largest = outers.compactMap(GeoBounds.of).max { area($0) < area($1) }
        return ((largest ?? bounds).center, bounds)
    }
}

private func area(_ b: GeoBounds) -> Double { (b.maxLat - b.minLat) * (b.maxLon - b.minLon) }

// MARK: - gate, belt and terminal

/// The forms a gate or belt is written in, for matching: "Gate 042B" → ["42B"], "T3-42B" → ["T342B", "42B"],
/// "A 12" → ["A12"]. Upper-case, without "GATE"/"BELT", spaces, dashes or leading zeros of a number.
public func gateKeys(_ text: String?) -> Set<String> {
    guard var raw = text?.uppercased().trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return [] }
    for word in ["GATE", "BELT", "CAROUSEL", "BAGGAGE CLAIM", "BAGGAGE", "NO.", "NO"] {
        if raw.hasPrefix(word + " ") || raw.hasPrefix(word + "-") || raw == word { raw = String(raw.dropFirst(word.count)) }
    }
    let compact = raw.filter { $0.isLetter || $0.isNumber }
    guard !compact.isEmpty else { return [] }
    var keys: Set<String> = [stripLeadingZeros(compact)]
    // A terminal prefix ("T3-42B", "T1 12") is also matched without it.
    let tokens = raw.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "/" }).map(String.init)
    if tokens.count >= 2, let first = tokens.first, first.count <= 3, first.hasPrefix("T"), first.dropFirst().allSatisfy(\.isNumber) {
        let rest = tokens.dropFirst().joined().filter { $0.isLetter || $0.isNumber }
        if !rest.isEmpty { keys.insert(stripLeadingZeros(rest)) }
    }
    return keys
}

/// "042B" → "42B", "A07" → "A7", "0" → "0".
private func stripLeadingZeros(_ text: String) -> String {
    var out = ""
    var previousIsDigit = false
    var pendingZeros = 0
    for character in text {
        if character == "0", !previousIsDigit {
            pendingZeros += 1
            continue
        }
        if pendingZeros > 0 {
            if !character.isNumber { out.append("0") }
            pendingZeros = 0
        }
        out.append(character)
        previousIsDigit = character.isNumber
    }
    if pendingZeros > 0 { out.append("0") }
    return out
}

/// The gate features for the user's gate (by `ref`, else by `name`). Empty when the gate is unknown or not mapped.
/// When two terminals reuse a number, the gates inside the flight's terminal (`within`) win.
public func matchGate(_ gate: String?, in map: TerminalMap, within terminals: [TerminalFeature] = []) -> [TerminalFeature] {
    let wanted = gateKeys(gate)
    guard !wanted.isEmpty else { return [] }
    var found = map.gates.filter { !gateKeys($0.ref).isDisjoint(with: wanted) }
    if found.isEmpty { found = map.gates.filter { $0.ref == nil && !gateKeys($0.name).isDisjoint(with: wanted) } }
    guard found.count > 1, !terminals.isEmpty else { return found }
    let inside = found.filter { gate in terminals.contains { near(gate.anchor, $0.bounds) } }
    return inside.isEmpty ? found : inside
}

/// Inside the box or within about 150 m of it (gates sit on the terminal's edge, often just outside the outline).
private func near(_ p: GeoPoint, _ b: GeoBounds) -> Bool {
    let pad = 0.0015
    return p.lat >= b.minLat - pad && p.lat <= b.maxLat + pad && p.lon >= b.minLon - pad && p.lon <= b.maxLon + pad
}

/// The arrival belt area: the baggage claim with that belt's ref, else every baggage claim when the belt is known but
/// not mapped by number. Empty when no belt is known or the map has no baggage claim.
public func matchBeltArea(_ belt: String?, in map: TerminalMap) -> [TerminalFeature] {
    let wanted = gateKeys(belt)
    guard !wanted.isEmpty else { return [] }
    let claims = map.features.filter { $0.kind == .baggageClaim }
    let exact = claims.filter { !gateKeys($0.ref).isDisjoint(with: wanted) || !gateKeys($0.name).isDisjoint(with: wanted) }
    return exact.isEmpty ? claims : exact
}

/// "3", "T3", "Terminal 3", "T-3" → "3"; "1D" stays "1D". Nil for blank text.
public func terminalKey(_ text: String?) -> String? {
    guard var raw = text?.uppercased().trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
    if raw.hasPrefix("TERMINAL") { raw = String(raw.dropFirst("TERMINAL".count)) }
    raw = raw.filter { $0.isLetter || $0.isNumber }
    if raw.count >= 2, raw.hasPrefix("T"), raw.dropFirst().first?.isNumber == true { raw = String(raw.dropFirst()) }
    return raw.isEmpty ? nil : stripLeadingZeros(raw)
}

/// The terminal outline for the flight's terminal, by `ref` or `name`. Empty when unknown.
public func matchTerminal(_ terminal: String?, in map: TerminalMap) -> [TerminalFeature] {
    guard let wanted = terminalKey(terminal) else { return [] }
    return map.terminals.filter { terminalKey($0.ref) == wanted || terminalKey($0.name) == wanted }
}

// MARK: - POIs

public struct TerminalPOI: Hashable, Sendable, Identifiable {
    public var id: String
    public var category: POICategory
    public var name: String?
    public var level: String?
    public var position: GeoPoint
}

/// Every POI, grouped in `POICategory` order, then named before unnamed, then by name.
public func terminalPOIs(_ map: TerminalMap) -> [TerminalPOI] {
    let order = Dictionary(uniqueKeysWithValues: POICategory.allCases.enumerated().map { ($1, $0) })
    return map.features.compactMap { f in
        f.kind.poiCategory.map { TerminalPOI(id: f.id, category: $0, name: f.name, level: f.level, position: f.anchor) }
    }
    .sorted { a, b in
        if a.category != b.category { return order[a.category, default: 0] < order[b.category, default: 0] }
        switch (a.name, b.name) {
        case let (x?, y?):
            let c = x.localizedCaseInsensitiveCompare(y)
            return c == .orderedSame ? a.id < b.id : c == .orderedAscending
        case (_?, nil): return true
        case (nil, _?): return false
        case (nil, nil): return a.id < b.id
        }
    }
}

/// The search filter: every word of the query must appear in the POI's name, its category label (in the app
/// language, from `label`) or its level. Case and diacritics are ignored. `category` narrows to one group.
public func filterPOIs(_ pois: [TerminalPOI], query: String, category: POICategory? = nil, label: (POICategory) -> String) -> [TerminalPOI] {
    let words = query.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        .split(whereSeparator: { $0.isWhitespace }).map(String.init)
    return pois.filter { poi in
        if let category, poi.category != category { return false }
        guard !words.isEmpty else { return true }
        let haystack = [poi.name, label(poi.category), poi.level.map { "level \($0)" }, poi.category.rawValue]
            .compactMap { $0 }.joined(separator: " ")
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        return words.allSatisfy { haystack.contains($0) }
    }
}

// MARK: - screen

/// Which airport and what to highlight: the departure card passes the gate and terminal, the arrival card the belt
/// (and its terminal).
public struct TerminalMapRequest: Hashable, Sendable {
    public var iata: String
    public var gate: String?
    public var belt: String?
    public var terminal: String?

    public init(iata: String, gate: String? = nil, belt: String? = nil, terminal: String? = nil) {
        self.iata = iata.uppercased()
        self.gate = gate
        self.belt = belt
        self.terminal = terminal
    }

    public static func departure(_ flight: Flight) -> TerminalMapRequest {
        TerminalMapRequest(iata: flight.depIata, gate: flight.depGate, terminal: flight.depTerminal)
    }

    public static func arrival(_ flight: Flight) -> TerminalMapRequest {
        TerminalMapRequest(iata: flight.arrIata, belt: flight.baggageBelt, terminal: flight.arrTerminal)
    }
}

/// True when this build can have a map for the airport (the file may still be missing until the data step ran).
public func terminalMapCovers(_ iata: String) -> Bool { TerminalMapContract.airports.contains(iata.uppercased()) }

/// The bundled file (app: `BundledTerminalMaps`). Nil when the airport has no file.
@MainActor
public protocol TerminalMapLoading: AnyObject {
    func geojson(iata: String) async -> Data?
}

public enum TerminalMapPhase: Equatable, Sendable {
    case loading
    /// "Terminal map not available yet".
    case notAvailable
    case ready
}

public struct TerminalMapState: Equatable, Sendable {
    public var request: TerminalMapRequest
    public var phase: TerminalMapPhase = .loading
    public var map: TerminalMap?
    /// The file's bytes, for MKGeoJSONDecoder.
    public var geojson: Data?
    public var gateFeatures: [TerminalFeature] = []
    public var beltFeatures: [TerminalFeature] = []
    public var terminalFeatures: [TerminalFeature] = []
    public var pois: [TerminalPOI] = []
    public var query = ""
    public var category: POICategory?
    /// A POI tapped in the list: the map centres on it.
    public var selectedPOI: String?

    public init(request: TerminalMapRequest) { self.request = request }

    /// The gate is known but the map has no gate with that number.
    public var gateNotMapped: Bool { gateKeys(request.gate).isEmpty == false && phase == .ready && gateFeatures.isEmpty }
    public var highlightedIds: Set<String> { Set((gateFeatures + beltFeatures + terminalFeatures).map(\.id)) }
    /// Categories present on this map, in list order (the filter chips).
    public var categories: [POICategory] { POICategory.allCases.filter { c in pois.contains { $0.category == c } } }

    /// Where the camera starts: the gate, else the belt area, else the flight's terminal, else every terminal.
    public var focus: GeoBounds? {
        for group in [gateFeatures, beltFeatures, terminalFeatures] where !group.isEmpty {
            if let first = group.first?.bounds { return group.dropFirst().reduce(first) { $0.union($1.bounds) } }
        }
        return map?.bounds
    }
}

@MainActor
public final class TerminalMapModel {
    public private(set) var state: TerminalMapState
    public var onChange: ((TerminalMapState) -> Void)?

    private let loader: TerminalMapLoading

    public init(request: TerminalMapRequest, loader: TerminalMapLoading) {
        state = TerminalMapState(request: request)
        self.loader = loader
    }

    public func load() async {
        guard let data = await loader.geojson(iata: state.request.iata),
              let map = parseTerminalMap(data, iata: state.request.iata), !map.features.isEmpty else {
            state.phase = .notAvailable
            publish()
            return
        }
        state.map = map
        state.geojson = data
        state.terminalFeatures = matchTerminal(state.request.terminal, in: map)
        state.gateFeatures = matchGate(state.request.gate, in: map, within: state.terminalFeatures)
        state.beltFeatures = matchBeltArea(state.request.belt, in: map)
        state.pois = terminalPOIs(map)
        state.phase = .ready
        publish()
    }

    /// The gate or belt changed while the screen is open (a gate change push).
    public func update(gate: String?, belt: String?) {
        state.request.gate = gate
        state.request.belt = belt
        if let map = state.map {
            state.gateFeatures = matchGate(gate, in: map, within: state.terminalFeatures)
            state.beltFeatures = matchBeltArea(belt, in: map)
        }
        publish()
    }

    public func setQuery(_ query: String) {
        state.query = query
        publish()
    }

    public func setCategory(_ category: POICategory?) {
        state.category = state.category == category ? nil : category
        publish()
    }

    public func select(_ poiId: String?) {
        state.selectedPOI = poiId
        publish()
    }

    public func visiblePOIs(label: (POICategory) -> String) -> [TerminalPOI] {
        filterPOIs(state.pois, query: state.query, category: state.category, label: label)
    }

    private func publish() { onChange?(state) }
}
