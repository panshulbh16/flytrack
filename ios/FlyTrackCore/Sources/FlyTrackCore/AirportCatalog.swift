import Foundation

// The bundled airport catalog (P3 A3 calendar import, B5 route pickers). The app ships the same file as Android
// (`android/app/src/main/assets/airports.json`, copied to `ios/FlyTrack/airports.json`): the airports with scheduled
// service from OurAirports (public domain), with HeyTurbulence's display cities and time zones. The calendar parser
// resolves names against it and the route pickers list it, so both work offline and agree with Android.

/// One airport of the bundled catalog.
public struct CatalogAirport: Hashable, Sendable, Identifiable {
    public var iata: String
    public var name: String
    public var city: String
    public var country: String
    public var lat: Double
    public var lon: Double
    /// IANA zone, "Asia/Kolkata".
    public var tz: String

    public var id: String { iata }

    public init(iata: String, name: String, city: String, country: String, lat: Double, lon: Double, tz: String) {
        self.iata = iata
        self.name = name
        self.city = city
        self.country = country
        self.lat = lat
        self.lon = lon
        self.tz = tz
    }
}

/// The bundled airports, sorted by IATA code, with constant-time lookup by code.
public struct AirportList: Sendable, Equatable {
    public let airports: [CatalogAirport]
    private let byCode: [String: CatalogAirport]

    public init(_ airports: [CatalogAirport]) {
        self.airports = airports
        var map: [String: CatalogAirport] = [:]
        for airport in airports where map[airport.iata] == nil { map[airport.iata] = airport }
        byCode = map
    }

    public static let empty = AirportList([])

    public subscript(iata: String) -> CatalogAirport? { byCode[iata] }

    public var count: Int { airports.count }
    public var isEmpty: Bool { airports.isEmpty }

    /// Reads the compact rows `[iata, name, city, country, lat, lon, tz]`. Rows of another shape are skipped. Nil when
    /// the data is not a JSON array (same rule as Android's `AirportList.parse`).
    public static func parse(_ data: Data) -> AirportList? {
        guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
        let airports: [CatalogAirport] = rows.compactMap { row in
            guard let cells = row as? [Any], cells.count >= 7,
                  let iata = cells[0] as? String, iata.utf16.count == 3,
                  let name = cells[1] as? String, let city = cells[2] as? String, let country = cells[3] as? String,
                  let lat = JSON.double(cells[4]), let lon = JSON.double(cells[5]), let tz = cells[6] as? String
            else { return nil }
            return CatalogAirport(iata: iata, name: name, city: city, country: country, lat: lat, lon: lon, tz: tz)
        }
        return AirportList(airports.sorted { $0.iata < $1.iata })
    }

    /// The country the pickers list first (the app is India-first).
    public static let homeCountry = "India"

    /// Airports for a picker query: an exact IATA code first, then airports whose city starts with the query, then
    /// whose name starts with it, then whose city or name contains it, then (short queries) whose code starts with
    /// it; Indian airports first within each group. A blank query lists the Indian airports by city. Same ranking as
    /// Android's `AirportList.search`.
    public func search(_ query: String, limit: Int = 30) -> [CatalogAirport] {
        let q = foldForSearch(query.trimmingCharacters(in: .whitespacesAndNewlines))
        if q.isEmpty {
            return Array(airports.filter { $0.country == Self.homeCountry }.sorted { $0.city < $1.city }.prefix(limit))
        }
        let exact: CatalogAirport? = q.utf16.count == 3 ? self[q.uppercased()] : nil
        let upper = q.uppercased()
        var ranked: [(rank: Int, home: Int, airport: CatalogAirport)] = []
        for airport in airports where airport.iata != exact?.iata {
            let city = foldForSearch(airport.city)
            let name = foldForSearch(airport.name)
            let rank: Int
            if city.hasPrefix(q) {
                rank = 0
            } else if name.hasPrefix(q) {
                rank = 1
            } else if city.contains(q) || name.contains(q) {
                rank = 2
            } else if q.utf16.count <= 3 && airport.iata.hasPrefix(upper) {
                rank = 3
            } else {
                continue
            }
            ranked.append((rank, airport.country == Self.homeCountry ? 0 : 1, airport))
        }
        ranked.sort { l, r in
            if l.rank != r.rank { return l.rank < r.rank }
            if l.home != r.home { return l.home < r.home }
            if l.airport.city != r.airport.city { return l.airport.city < r.airport.city }
            return l.airport.iata < r.airport.iata
        }
        let room = max(0, limit - (exact == nil ? 0 : 1))
        return (exact.map { [$0] } ?? []) + ranked.prefix(room).map(\.airport)
    }
}

/// Lower-case with accents removed: "São Paulo" → "sao paulo".
public func foldForSearch(_ text: String) -> String {
    calendarNormalize(text)
}

/// Loads the bundled catalog once. The app reads `airports.json` from its bundle; tests pass a fixed list.
@MainActor
public protocol AirportCatalogLoading: AnyObject {
    /// The whole list; empty only when the file is missing or broken (never throws).
    func load() async -> AirportList
}
