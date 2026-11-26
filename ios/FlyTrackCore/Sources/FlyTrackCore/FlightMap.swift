import Foundation

/// The plane icon: where it is and where its nose points (degrees clockwise from north).
public struct PlaneMarker: Hashable, Sendable {
    public var point: GeoPoint
    public var bearingDeg: Double

    public init(point: GeoPoint, bearingDeg: Double) {
        self.point = point
        self.bearingDeg = bearingDeg
    }
}

/// What the plane is doing, for the panel under the map.
public enum MapFlightState: String, Sendable, Equatable {
    case notDeparted, airborne, arrived, cancelled, positionUnknown
}

/// Everything the flight map draws (contract section 5). Same rules as Android's `FlightMapModel.kt`.
///
/// The route is drawn as a geodesic, so `flownEnds` / `remainingEnds` are the two ends of each part: MapKit's
/// `MKGeodesicPolyline` draws the great circle between them, and the split point is the plane itself, so the
/// solid and the dashed part meet under the icon. `flownPath` / `remainingPath` are the same parts as points
/// along the great circle (unwrapped longitudes), for a drawing without MapKit (the share image, tests).
public struct FlightMapModel: Hashable, Sendable {
    public var flightId: String
    public var designator: String
    public var depCode: String
    public var arrCode: String
    public var depCity: String
    public var arrCity: String
    public var dep: GeoPoint
    public var arr: GeoPoint
    /// Solid part, empty before take-off. Two points (start, end) or none.
    public var flownEnds: [GeoPoint]
    /// Dashed part, empty once arrived. Two points (start, end) or none.
    public var remainingEnds: [GeoPoint]
    public var flownPath: [GeoPoint]
    public var remainingPath: [GeoPoint]
    /// Nil unless the plane is in the air.
    public var plane: PlaneMarker?
    public var state: MapFlightState
    /// "Live" or "Estimated"; nil without a plane.
    public var source: PositionSource?
    /// Share flown 0...1, nil when unknown.
    public var progress: Double?
    /// Time to the expected touchdown while airborne.
    public var remaining: TimeInterval?
    public var liveObservedAt: Date?
    public var asOf: Date

    /// Points the camera keeps in view: both airports and the plane (unwrapped).
    public var cameraPoints: [GeoPoint] {
        GreatCircle.unwrap([dep] + (plane.map { [$0.point] } ?? []) + [arr])
    }
}

/// South-west / north-east corners for the camera, in degrees (east may exceed 180 after unwrapping).
public struct MapBounds: Hashable, Sendable {
    public var south: Double
    public var west: Double
    public var north: Double
    public var east: Double

    public init(south: Double, west: Double, north: Double, east: Double) {
        self.south = south
        self.west = west
        self.north = north
        self.east = east
    }

    public var center: GeoPoint {
        var lon = (west + east) / 2
        if lon > 180 { lon -= 360 }
        if lon < -180 { lon += 360 }
        return GeoPoint(lat: (south + north) / 2, lon: lon)
    }

    /// The bounds grown by `fraction` of their size on every side, at least `minimumDegrees` tall and wide.
    public func padded(_ fraction: Double, minimumDegrees: Double = 2) -> MapBounds {
        let height = max(north - south, minimumDegrees)
        let width = max(east - west, minimumDegrees)
        let midLat = (south + north) / 2
        let midLon = (west + east) / 2
        let halfH = height * (0.5 + fraction)
        let halfW = width * (0.5 + fraction)
        return MapBounds(
            south: max(-85, midLat - halfH), west: midLon - halfW,
            north: min(85, midLat + halfH), east: midLon + halfW
        )
    }
}

public func mapBounds(_ points: [GeoPoint]) -> MapBounds? {
    guard let first = points.first else { return nil }
    var bounds = MapBounds(south: first.lat, west: first.lon, north: first.lat, east: first.lon)
    for point in points.dropFirst() {
        bounds.south = min(bounds.south, point.lat)
        bounds.north = max(bounds.north, point.lat)
        bounds.west = min(bounds.west, point.lon)
        bounds.east = max(bounds.east, point.lon)
    }
    return bounds
}

extension Flight {
    /// The map model at `now`; nil while either airport's coordinates are unknown.
    public func mapModel(now: Date, segments: Int = GreatCircle.defaultSegments) -> FlightMapModel? {
        guard let ends = routeEnds else { return nil }
        let position = position(now: now)
        let state: MapFlightState
        if status == .cancelled {
            state = .cancelled
        } else if let position {
            switch position.phase {
            case .preDeparture: state = .notDeparted
            case .arrived: state = .arrived
            case .airborne: state = .airborne
            }
        } else {
            state = .positionUnknown
        }
        let flying = state == .airborne ? position : nil
        let flownEnds: [GeoPoint]
        let remainingEnds: [GeoPoint]
        let flownPath: [GeoPoint]
        let remainingPath: [GeoPoint]
        if let flying {
            flownEnds = [ends.dep, flying.point]
            remainingEnds = [flying.point, ends.arr]
            let split = splitRoute(dep: ends.dep, arr: ends.arr, fraction: flying.progress, at: flying.point, segments: segments)
            flownPath = split.flown
            remainingPath = split.remaining
        } else if state == .arrived {
            flownEnds = [ends.dep, ends.arr]
            remainingEnds = []
            flownPath = GreatCircle.path(ends.dep, ends.arr, segments: segments)
            remainingPath = []
        } else {
            flownEnds = []
            remainingEnds = [ends.dep, ends.arr]
            flownPath = []
            remainingPath = GreatCircle.path(ends.dep, ends.arr, segments: segments)
        }
        return FlightMapModel(
            flightId: id,
            designator: designator,
            depCode: depIata,
            arrCode: arrIata,
            depCity: depAirport?.displayCity ?? depIata,
            arrCity: arrAirport?.displayCity ?? arrIata,
            dep: ends.dep,
            arr: ends.arr,
            flownEnds: flownEnds,
            remainingEnds: remainingEnds,
            flownPath: flownPath,
            remainingPath: remainingPath,
            plane: flying.map { PlaneMarker(point: $0.point, bearingDeg: $0.bearingDeg) },
            state: state,
            source: flying?.source,
            progress: position?.progress,
            remaining: flying?.remaining,
            liveObservedAt: flying?.observedAt,
            asOf: now
        )
    }
}

/// Splits the great circle at `fraction`. The split point is `at` (the plane, which a live fix may put a little
/// off the ideal line), so both parts meet under the icon. Both halves are unwrapped together.
public func splitRoute(
    dep: GeoPoint, arr: GeoPoint, fraction: Double, at: GeoPoint, segments: Int = GreatCircle.defaultSegments
) -> (flown: [GeoPoint], remaining: [GeoPoint]) {
    let f = min(1, max(0, fraction))
    let count = max(1, segments)
    let steps = (0...count).map { Double($0) / Double(count) }
    let before = steps.filter { $0 < f }.map { GreatCircle.interpolate(dep, arr, fraction: $0) }
    let after = steps.filter { $0 > f }.map { GreatCircle.interpolate(dep, arr, fraction: $0) }
    let flown = before + [at]
    let rest = [at] + after
    let joined = GreatCircle.unwrap(flown + rest)
    return (Array(joined[0..<flown.count]), Array(joined[flown.count...]))
}
