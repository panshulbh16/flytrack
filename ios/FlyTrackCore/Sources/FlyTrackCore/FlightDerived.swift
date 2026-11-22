import Foundation

public enum TimeKind: String, Sendable, Equatable {
    case scheduled, estimated, actual
}

public struct BestTime: Hashable, Sendable {
    public var instant: Date
    public var kind: TimeKind

    public init(instant: Date, kind: TimeKind) {
        self.instant = instant
        self.kind = kind
    }
}

private func best(actual: Date?, estimated: Date?, scheduled: Date?) -> BestTime? {
    if let actual { return BestTime(instant: actual, kind: .actual) }
    if let estimated { return BestTime(instant: estimated, kind: .estimated) }
    if let scheduled { return BestTime(instant: scheduled, kind: .scheduled) }
    return nil
}

extension Flight {
    public var bestDepartureTime: BestTime? { best(actual: actDep, estimated: estDep, scheduled: schedDep) }
    public var bestArrivalTime: BestTime? { best(actual: actArr, estimated: estArr, scheduled: schedArr) }
    public var bestDeparture: Date? { bestDepartureTime?.instant }
    public var bestArrival: Date? { bestArrivalTime?.instant }

    /// Minutes behind schedule. Negative means early. Nil when the schedule or a newer time is missing.
    /// Rounded the same way as the server's `minutesBetween` (half away from zero).
    public var departureDelayMinutes: Int? {
        guard let scheduled = schedDep, let current = actDep ?? estDep else { return nil }
        return minutesBetween(later: current, earlier: scheduled)
    }

    public var arrivalDelayMinutes: Int? {
        guard let scheduled = schedArr, let current = actArr ?? estArr else { return nil }
        return minutesBetween(later: current, earlier: scheduled)
    }

    public var duration: TimeInterval? {
        guard let dep = bestDeparture, let arr = bestArrival, arr > dep else { return nil }
        return arr.timeIntervalSince(dep)
    }

    public var hasDeparted: Bool {
        actDep != nil || Self.departedStatuses.contains(status)
    }

    public var hasLanded: Bool {
        actArr != nil || status == .landed || status == .arrived
    }

    public var phase: FlightPhase {
        if status == .cancelled { return .cancelled }
        if status == .diverted { return .diverted }
        if hasLanded { return .landed }
        if hasDeparted { return .inAir }
        if status == .boarding || status == .gateClosed { return .boarding }
        if status == .checkIn { return .checkIn }
        return .upcoming
    }

    public var statusTone: FlightStatusTone {
        let delay = hasLanded ? arrivalDelayMinutes : departureDelayMinutes
        if status == .cancelled || status == .diverted { return .cancelled }
        if (delay ?? 0) >= delayThresholdMinutes { return .delayed }
        if phase == .boarding || phase == .inAir { return .active }
        if status == .unknown { return .estimated }
        return .onTime
    }

    public func progress(now: Date) -> Double? {
        if !hasDeparted { return nil }
        if hasLanded { return 1 }
        guard let dep = bestDeparture, let arr = bestArrival else { return nil }
        let total = arr.timeIntervalSince(dep)
        if total <= 0 { return nil }
        let flown = now.timeIntervalSince(dep) / total
        return min(1, max(0, flown))
    }

    public var distanceKm: Int? {
        guard let a = depAirport, let b = arrAirport else { return nil }
        let lat1 = a.lat * .pi / 180
        let lat2 = b.lat * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (b.lon - a.lon) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return Int((2 * 6371.0 * asin(sqrt(h))).rounded())
    }

    private static let departedStatuses: Set<FlightStatus> = [
        .departed, .enRoute, .approaching, .landed, .arrived, .diverted,
    ]
}

public let delayThresholdMinutes = 15

private func minutesBetween(later: Date, earlier: Date) -> Int {
    Int((later.timeIntervalSince(earlier) / 60).rounded(.toNearestOrAwayFromZero))
}
