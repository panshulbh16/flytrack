import Foundation

// Offline position estimate (docs/P2_CONTRACTS.md section 5). The same formulas and fixtures as the Android
// app's `core/estimate` (GreatCircle.kt, PositionEstimate.kt, FlightPosition.kt), so both apps put the plane at
// the same spot. Fixture: Tests/FlyTrackCoreTests/Fixtures/estimate_cases.json (a copy of Android's).

/// A position in degrees (WGS 84).
public struct GeoPoint: Hashable, Sendable {
    public var lat: Double
    public var lon: Double

    public init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }
}

/// Spherical geometry on a sphere of radius `earthRadiusKm`: distance, slerp interpolation and bearings.
public enum GreatCircle {
    public static let earthRadiusKm = 6371.0
    public static let defaultSegments = 64

    /// Central angle between `a` and `b`, in radians (haversine).
    public static func angle(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let lat1 = radians(a.lat)
        let lat2 = radians(b.lat)
        let dLat = lat2 - lat1
        let dLon = radians(b.lon - a.lon)
        let s1 = sin(dLat / 2)
        let s2 = sin(dLon / 2)
        let h = s1 * s1 + cos(lat1) * cos(lat2) * s2 * s2
        return 2 * asin(sqrt(min(1, max(0, h))))
    }

    public static func distanceKm(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        angle(a, b) * earthRadiusKm
    }

    /// The point a `fraction` (0...1, clamped) of the way from `a` to `b` along the great circle
    /// (spherical linear interpolation of the two unit vectors). The ends return the airports themselves.
    public static func interpolate(_ a: GeoPoint, _ b: GeoPoint, fraction: Double) -> GeoPoint {
        let f = min(1, max(0, fraction))
        if f == 0 { return a }
        if f == 1 { return b }
        let d = angle(a, b)
        if d < 1e-12 { return a }
        let lat1 = radians(a.lat)
        let lon1 = radians(a.lon)
        let lat2 = radians(b.lat)
        let lon2 = radians(b.lon)
        let wa = sin((1 - f) * d) / sin(d)
        let wb = sin(f * d) / sin(d)
        let x = wa * cos(lat1) * cos(lon1) + wb * cos(lat2) * cos(lon2)
        let y = wa * cos(lat1) * sin(lon1) + wb * cos(lat2) * sin(lon2)
        let z = wa * sin(lat1) + wb * sin(lat2)
        return GeoPoint(lat: degrees(atan2(z, sqrt(x * x + y * y))), lon: degrees(atan2(y, x)))
    }

    /// Initial bearing (forward azimuth) from `a` towards `b`, degrees clockwise from north in 0..<360.
    public static func bearing(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let lat1 = radians(a.lat)
        let lat2 = radians(b.lat)
        let dLon = radians(b.lon - a.lon)
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(degrees(atan2(y, x)))
    }

    /// The heading a `fraction` of the way along the route: the bearing from that point towards `b`, or at
    /// the very end the final bearing (the course on arrival). 0 for a zero-length route.
    public static func bearingAt(_ a: GeoPoint, _ b: GeoPoint, fraction: Double) -> Double {
        let f = min(1, max(0, fraction))
        if angle(a, b) < 1e-12 { return 0 }
        if f >= 1 { return normalizeDegrees(bearing(b, a) + 180) }
        return bearing(interpolate(a, b, fraction: f), b)
    }

    /// How far along the route from `a` to `b` the point `p` is (0...1): the share of the distance already
    /// covered, which suits a live fix a little off the ideal great circle.
    public static func fractionAlong(_ a: GeoPoint, _ b: GeoPoint, _ p: GeoPoint) -> Double {
        let flown = angle(a, p)
        let left = angle(p, b)
        let sum = flown + left
        if sum < 1e-12 { return 0 }
        return min(1, max(0, flown / sum))
    }

    /// `segments` + 1 points along the great circle, both ends included, with unwrapped longitudes.
    public static func path(_ a: GeoPoint, _ b: GeoPoint, segments: Int = GreatCircle.defaultSegments) -> [GeoPoint] {
        let count = max(1, segments)
        return unwrap((0...count).map { interpolate(a, b, fraction: Double($0) / Double(count)) })
    }

    /// Shifts longitudes by multiples of 360 so consecutive points are never more than 180 apart.
    public static func unwrap(_ points: [GeoPoint]) -> [GeoPoint] {
        guard points.count >= 2 else { return points }
        var out: [GeoPoint] = [points[0]]
        out.reserveCapacity(points.count)
        for index in 1..<points.count {
            var lon = points[index].lon
            let previous = out[index - 1].lon
            while lon - previous > 180 { lon -= 360 }
            while lon - previous < -180 { lon += 360 }
            out.append(GeoPoint(lat: points[index].lat, lon: lon))
        }
        return out
    }

    static func radians(_ degrees: Double) -> Double { degrees * .pi / 180 }
    static func degrees(_ radians: Double) -> Double { radians * 180 / .pi }

    static func normalizeDegrees(_ value: Double) -> Double {
        let r = value.truncatingRemainder(dividingBy: 360)
        let n = r < 0 ? r + 360 : r
        return abs(n - 360) < 1e-9 ? 0 : n
    }
}

/// Stage of the flight according to the clock alone. Raw values are the contract's wire names.
public enum EstimatePhase: String, CaseIterable, Sendable, Equatable {
    case preDeparture = "pre_departure"
    case airborne
    case arrived

    public var wire: String { rawValue }
}

/// Where the plane should be if it flies the great circle at a steady pace. `progress` is 0...1.
public struct PositionEstimate: Hashable, Sendable {
    public var lat: Double
    public var lon: Double
    public var progress: Double
    public var phase: EstimatePhase

    public init(lat: Double, lon: Double, progress: Double, phase: EstimatePhase) {
        self.lat = lat
        self.lon = lon
        self.progress = progress
        self.phase = phase
    }

    public var point: GeoPoint { GeoPoint(lat: lat, lon: lon) }
}

/// The shared estimate algorithm (docs/P2_CONTRACTS.md section 5; identical in Kotlin).
///
/// * Before `depTime`: `pre_departure`, progress 0, at the departure airport.
/// * From `arrTime` on: `arrived`, progress 1, at the arrival airport.
/// * In between: `airborne`, progress = elapsed / planned block time, position = slerp at that progress.
///
/// A non-positive duration (`arrTime` <= `depTime`) never yields `airborne`.
/// Callers pick `depTime` = act_dep ?? est_dep ?? sched_dep and `arrTime` = est_arr ?? sched_arr
/// (`Flight.estimateTimes`).
public func estimatePosition(dep: GeoPoint, arr: GeoPoint, depTime: Date, arrTime: Date, now: Date) -> PositionEstimate {
    let phase: EstimatePhase
    if now < depTime {
        phase = .preDeparture
    } else if now >= arrTime {
        phase = .arrived
    } else {
        phase = .airborne
    }
    let progress: Double
    switch phase {
    case .preDeparture: progress = 0
    case .arrived: progress = 1
    case .airborne:
        let total = arrTime.timeIntervalSince(depTime)
        progress = min(1, max(0, now.timeIntervalSince(depTime) / total))
    }
    let point = GreatCircle.interpolate(dep, arr, fraction: progress)
    return PositionEstimate(lat: point.lat, lon: point.lon, progress: progress, phase: phase)
}

/// Where a position comes from; the UI labels it "Live" or "Estimated".
public enum PositionSource: String, Sendable, Equatable {
    case live, estimated
}

public enum EstimateRules {
    /// A live fix younger than this is shown as "Live" (contract section 5).
    public static let liveFreshFor: TimeInterval = 20 * 60
    /// A fix stamped this far in the future (clock skew) still counts as fresh.
    public static let liveClockSkew: TimeInterval = 5 * 60
    /// Flight data the backend has not confirmed for longer than this is no longer trusted to say
    /// "not departed yet": past the departure time the card then shows the estimate (airplane mode).
    public static let dataStaleAfter: TimeInterval = 30 * 60
}

/// The two times the estimate runs between.
public struct EstimateTimes: Hashable, Sendable {
    public var departure: Date
    public var arrival: Date

    public init(departure: Date, arrival: Date) {
        self.departure = departure
        self.arrival = arrival
    }
}

/// The two ends of the route.
public struct RouteEnds: Hashable, Sendable {
    public var dep: GeoPoint
    public var arr: GeoPoint

    public init(dep: GeoPoint, arr: GeoPoint) {
        self.dep = dep
        self.arr = arr
    }
}

/// The plane's position for the map. `bearingDeg` is clockwise from north; `remaining` is nil once arrived or
/// when unknown; `observedAt` is set for a live fix only.
public struct FlightPosition: Hashable, Sendable {
    public var point: GeoPoint
    public var bearingDeg: Double
    public var progress: Double
    public var phase: EstimatePhase
    public var source: PositionSource
    public var remaining: TimeInterval?
    public var observedAt: Date?

    public init(
        point: GeoPoint, bearingDeg: Double, progress: Double, phase: EstimatePhase, source: PositionSource,
        remaining: TimeInterval?, observedAt: Date?
    ) {
        self.point = point
        self.bearingDeg = bearingDeg
        self.progress = progress
        self.phase = phase
        self.source = source
        self.remaining = remaining
        self.observedAt = observedAt
    }
}

/// The progress line of the flight card and detail screen. `arrival` is the touchdown the remaining time counts to.
public struct ProgressFix: Hashable, Sendable {
    public var progress: Double
    public var source: PositionSource
    public var arrival: Date?

    public init(progress: Double, source: PositionSource, arrival: Date?) {
        self.progress = progress
        self.source = source
        self.arrival = arrival
    }

    /// Time left until `arrival` at `now`; nil when unknown, never negative.
    public func remaining(now: Date) -> TimeInterval? {
        arrival.map { max(0, $0.timeIntervalSince(now)) }
    }
}

extension Flight {
    /// `depTime` = act_dep ?? est_dep ?? sched_dep and `arrTime` = est_arr ?? sched_arr; nil when either is unknown.
    public var estimateTimes: EstimateTimes? {
        guard let dep = actDep ?? estDep ?? schedDep, let arr = estArr ?? schedArr else { return nil }
        return EstimateTimes(departure: dep, arrival: arr)
    }

    /// The airports' coordinates; nil while either airport is unknown.
    public var routeEnds: RouteEnds? {
        guard let a = depAirport, let b = arrAirport else { return nil }
        return RouteEnds(dep: GeoPoint(lat: a.lat, lon: a.lon), arr: GeoPoint(lat: b.lat, lon: b.lon))
    }

    /// The live fix when it is fresher than `EstimateRules.liveFreshFor` and has sane coordinates, else nil.
    public func freshLivePosition(now: Date) -> LivePosition? {
        guard let fix = live, let at = fix.at else { return nil }
        guard (-90.0...90.0).contains(fix.lat), (-180.0...180.0).contains(fix.lon) else { return nil }
        if fix.lat == 0 && fix.lon == 0 { return nil } // "null island": a provider's missing value
        let age = now.timeIntervalSince(at)
        guard age <= EstimateRules.liveFreshFor, age >= -EstimateRules.liveClockSkew else { return nil }
        return fix
    }

    /// True when the backend has not confirmed the flight for `EstimateRules.dataStaleAfter` (or never).
    public func isDataStale(now: Date) -> Bool {
        guard let dataAt = [lastPolledAt, updatedAt].compactMap({ $0 }).max() else { return true }
        return now.timeIntervalSince(dataAt) > EstimateRules.dataStaleAfter
    }

    /// The best position at `now`: a fresh live fix ("Live"), otherwise the shared estimate ("Estimated"),
    /// reconciled with what the status already says for certain:
    ///
    /// * cancelled, or the route unknown: nil;
    /// * landed: arrived at the destination;
    /// * diverted: only a live fix (the estimate would fly to the wrong airport);
    /// * departed while the clock says not yet (a late take-off not yet in the times): airborne at 0;
    /// * fresh data that says "not departed" past the departure time: still at the gate (pre-departure);
    /// * fresh data that says "in the air" past the planned arrival: airborne over the destination.
    public func position(now: Date) -> FlightPosition? {
        if status == .cancelled { return nil }
        guard let ends = routeEnds else { return nil }
        let times = estimateTimes
        let remaining = times.map { max(0, $0.arrival.timeIntervalSince(now)) }

        if !hasLanded, let fix = freshLivePosition(now: now) {
            let point = GeoPoint(lat: fix.lat, lon: fix.lon)
            let heading = fix.headingDeg.flatMap { $0.isFinite ? GreatCircle.normalizeDegrees($0) : nil }
            return FlightPosition(
                point: point,
                bearingDeg: heading ?? GreatCircle.bearing(point, ends.arr),
                progress: GreatCircle.fractionAlong(ends.dep, ends.arr, point),
                phase: .airborne,
                source: .live,
                remaining: remaining,
                observedAt: fix.at
            )
        }
        if status == .diverted { return nil }
        if hasLanded {
            return FlightPosition(
                point: ends.arr,
                bearingDeg: GreatCircle.bearingAt(ends.dep, ends.arr, fraction: 1),
                progress: 1,
                phase: .arrived,
                source: .estimated,
                remaining: nil,
                observedAt: nil
            )
        }
        // A new name: `guard let times` would redeclare the local `times` in the same scope (a compile error).
        guard let planned = times else { return nil }
        var estimate = estimatePosition(dep: ends.dep, arr: ends.arr, depTime: planned.departure, arrTime: planned.arrival, now: now)
        let trusted = !isDataStale(now: now)
        if hasDeparted && estimate.phase == .preDeparture {
            estimate.phase = .airborne
        } else if !hasDeparted && trusted && estimate.phase != .preDeparture {
            // Fresh data still says "not departed" (a late departure not yet in the times): the plane is at the
            // gate, matching the card, which shows no progress line (`progressFix`). Same rule as Android.
            estimate = PositionEstimate(lat: ends.dep.lat, lon: ends.dep.lon, progress: 0, phase: .preDeparture)
        } else if hasDeparted && trusted && estimate.phase == .arrived {
            // Fresh data says still in the air past the planned arrival: over the destination, not landed.
            estimate.phase = .airborne
        }
        return FlightPosition(
            point: estimate.point,
            bearingDeg: GreatCircle.bearingAt(ends.dep, ends.arr, fraction: estimate.progress),
            progress: estimate.progress,
            phase: estimate.phase,
            source: .estimated,
            remaining: estimate.phase == .arrived ? nil : remaining,
            observedAt: nil
        )
    }

    /// Progress for the card and detail screen at `now`, nil when no progress line belongs there.
    ///
    /// * In the air (by status): a fresh live fix gives the share of the distance flown ("Live"), otherwise the
    ///   share of the planned time ("Estimated"; needs no airport coordinates).
    /// * Not departed by status, but the data is stale and the clock is between departure and arrival: the
    ///   offline estimate ("Estimated"), so airplane mode still shows the flight moving.
    /// * Anything else (landed, cancelled, diverted, before take-off): nil.
    public func progressFix(now: Date) -> ProgressFix? {
        let times = estimateTimes
        switch phase {
        case .inAir:
            if let fix = freshLivePosition(now: now), let ends = routeEnds {
                let progress = GreatCircle.fractionAlong(ends.dep, ends.arr, GeoPoint(lat: fix.lat, lon: fix.lon))
                return ProgressFix(progress: progress, source: .live, arrival: times?.arrival)
            }
            guard let times, let progress = timeProgress(times, now: now) else { return nil }
            return ProgressFix(progress: progress, source: .estimated, arrival: times.arrival)
        case .upcoming, .checkIn, .boarding:
            guard let times, isDataStale(now: now) else { return nil }
            if now < times.departure || now >= times.arrival { return nil }
            guard let progress = timeProgress(times, now: now) else { return nil }
            return ProgressFix(progress: progress, source: .estimated, arrival: times.arrival)
        case .landed, .cancelled, .diverted:
            return nil
        }
    }
}

private func timeProgress(_ times: EstimateTimes, now: Date) -> Double? {
    let total = times.arrival.timeIntervalSince(times.departure).rounded(.down)
    if total <= 0 { return nil }
    return min(1, max(0, now.timeIntervalSince(times.departure).rounded(.towardZero) / total))
}
