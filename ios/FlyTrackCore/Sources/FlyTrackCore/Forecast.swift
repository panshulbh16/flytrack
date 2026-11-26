import Foundation

// Arrival forecast and on-time history (Pro), P2 wave 2 contract section 4. Same model as Android's feature/forecast.

/// `flight_forecast.forecast.confidence`. Unknown values read as low.
public enum ForecastConfidence: String, CaseIterable, Sendable, Equatable {
    case low, medium, high

    public static func fromWire(_ value: String?) -> ForecastConfidence {
        value.flatMap(ForecastConfidence.init(rawValue:)) ?? .low
    }
}

/// `forecast.method`: from past legs, or only from the timetable. Unknown values read as schedule.
public enum ForecastMethod: String, CaseIterable, Sendable, Equatable {
    case history, schedule

    public static func fromWire(_ value: String?) -> ForecastMethod {
        value.flatMap(ForecastMethod.init(rawValue:)) ?? .schedule
    }
}

/// On-time history of the flight (or its route) over the last `windowDays` days.
public struct ForecastHistory: Hashable, Sendable {
    public var samples: Int
    public var windowDays: Int
    /// Departures within 15 min of schedule, of flown legs; nil when unknown.
    public var onTimePct: Int?
    public var avgDepDelayMin: Int?
    public var medianArrDelayMin: Int?
    public var cancelled: Int

    public init(samples: Int, windowDays: Int, onTimePct: Int?, avgDepDelayMin: Int?, medianArrDelayMin: Int?, cancelled: Int) {
        self.samples = samples
        self.windowDays = windowDays
        self.onTimePct = onTimePct
        self.avgDepDelayMin = avgDepDelayMin
        self.medianArrDelayMin = medianArrDelayMin
        self.cancelled = cancelled
    }
}

/// Likely arrival: half of past legs arrived by `p50`, four in five by `p80`.
public struct ArrivalForecast: Hashable, Sendable {
    public var p50: Date
    /// Nil when missing or earlier than `p50`.
    public var p80: Date?
    public var method: ForecastMethod
    public var confidence: ForecastConfidence

    public init(p50: Date, p80: Date?, method: ForecastMethod, confidence: ForecastConfidence) {
        self.p50 = p50
        self.p80 = p80
        self.method = method
        self.confidence = confidence
    }
}

/// Answer of `flight_forecast(p_flight_id)`.
public struct FlightForecast: Hashable, Sendable {
    public var history: ForecastHistory?
    public var forecast: ArrivalForecast?
    /// The caller is not Pro for this flight: `history` and `forecast` are nil, show the teaser.
    public var detailLocked: Bool

    public init(history: ForecastHistory?, forecast: ArrivalForecast?, detailLocked: Bool) {
        self.history = history
        self.forecast = forecast
        self.detailLocked = detailLocked
    }

    /// Nothing worth a card (no history samples and no forecast).
    public var isEmpty: Bool { !detailLocked && forecast == nil && (history?.samples ?? 0) == 0 }
}

public enum ForecastParseError: Error, Equatable {
    case malformed
}

/// Reads a `flight_forecast` answer: nil for SQL null; throws on a body that is not an object.
public func parseFlightForecast(_ data: Data) throws -> FlightForecast? {
    if rpcIsNull(data) { return nil }
    guard let object = JSON.object(from: data) else { throw ForecastParseError.malformed }
    return parseFlightForecast(object)
}

public func parseFlightForecast(_ object: [String: Any]) -> FlightForecast {
    if JSON.bool(object["detail_locked"]) == true {
        return FlightForecast(history: nil, forecast: nil, detailLocked: true)
    }
    let history = JSON.object(object["history"]).map { h in
        ForecastHistory(
            samples: max(0, JSON.int(h["samples"]) ?? 0),
            windowDays: JSON.int(h["window_days"]) ?? 60,
            onTimePct: JSON.int(h["on_time_pct"]).map { min(100, max(0, $0)) },
            avgDepDelayMin: JSON.int(h["avg_dep_delay_min"]),
            medianArrDelayMin: JSON.int(h["median_arr_delay_min"]),
            cancelled: max(0, JSON.int(h["cancelled"]) ?? 0)
        )
    }
    let forecast: ArrivalForecast? = JSON.object(object["forecast"]).flatMap { f in
        guard let p50 = parseInstant(JSON.string(f["arr_p50"])) else { return nil }
        let p80 = parseInstant(JSON.string(f["arr_p80"])).flatMap { $0 >= p50 ? $0 : nil }
        return ArrivalForecast(
            p50: p50, p80: p80,
            method: ForecastMethod.fromWire(JSON.string(f["method"])),
            confidence: ForecastConfidence.fromWire(JSON.string(f["confidence"]))
        )
    }
    return FlightForecast(history: history, forecast: forecast, detailLocked: false)
}

// MARK: - the card

/// What the arrival forecast block shows.
public enum ForecastCardState: Hashable, Sendable {
    /// Nothing to show: loading, offline, landed, cancelled, or the server has no data.
    case hidden
    /// Not Pro for this flight: the teaser.
    case locked
    case ready(ForecastCardModel)
}

public struct ForecastCardModel: Hashable, Sendable {
    /// "15:16" at the arrival airport; nil without a forecast (history only).
    public var p50Time: String?
    public var p80Time: String?
    /// p50 against the scheduled arrival in whole minutes (positive = later); nil without both.
    public var p50VsScheduleMin: Int?
    public var method: ForecastMethod?
    public var confidence: ForecastConfidence?
    public var history: ForecastHistory?

    public init(
        p50Time: String?, p80Time: String?, p50VsScheduleMin: Int?, method: ForecastMethod?,
        confidence: ForecastConfidence?, history: ForecastHistory?
    ) {
        self.p50Time = p50Time
        self.p80Time = p80Time
        self.p50VsScheduleMin = p50VsScheduleMin
        self.method = method
        self.confidence = confidence
        self.history = history
    }
}

extension Flight {
    /// A forecast is worth asking for until the flight lands, is cancelled or diverted.
    public var wantsForecast: Bool {
        !hasLanded && status != .cancelled && status != .diverted
    }
}

/// The card for `flight` with the server's `answer`.
public func forecastCardState(flight: Flight?, answer: FlightForecast?) -> ForecastCardState {
    guard let flight, flight.wantsForecast, let answer else { return .hidden }
    if answer.detailLocked { return .locked }
    if answer.isEmpty { return .hidden }
    let tz = flight.arrTz
    let forecast = answer.forecast
    let delta: Int? = forecast.flatMap { f in
        flight.schedArr.map { Int((f.p50.timeIntervalSince($0) / 60).rounded(.toNearestOrAwayFromZero)) }
    }
    return .ready(ForecastCardModel(
        p50Time: forecast.map { FlightTimeFormat.time($0.p50, tz: tz) },
        p80Time: forecast?.p80.map { FlightTimeFormat.time($0, tz: tz) },
        p50VsScheduleMin: delta,
        method: forecast?.method,
        confidence: forecast?.confidence,
        history: answer.history.flatMap { $0.samples > 0 ? $0 : nil }
    ))
}

/// `flight_forecast(p_flight_id)`.
@MainActor
public protocol ForecastSource: AnyObject {
    /// `.ok(nil)` when the server has nothing. Never throws.
    func forecast(flightId: String) async -> RemoteResult<FlightForecast?>
}

/// The forecast block of the detail screen. It asks again only when the times the forecast starts from change (a
/// delay, the take-off) or the last ask failed for want of a connection; errors are silent and keep an earlier answer
/// (the block is an extra).
@MainActor
public final class FlightForecastModel {
    public private(set) var state: ForecastCardState = .hidden
    public var onChange: ((ForecastCardState) -> Void)?
    private let flightId: String
    private let source: ForecastSource
    private var answer: FlightForecast?
    private var askedFor: [Date?]?

    public init(flightId: String, source: ForecastSource) {
        self.flightId = flightId
        self.source = source
    }

    /// Call with the flight as it is now (on open, on refresh, every minute).
    public func update(flight: Flight?) async {
        if let flight, flight.wantsForecast {
            let key = [flight.estDep, flight.actDep, flight.estArr]
            if key != askedFor {
                askedFor = key
                switch await source.forecast(flightId: flightId) {
                case .ok(let value): answer = value
                // No connection or an unreadable answer: ask again on the next update (the minute tick).
                case .offline, .failed: askedFor = nil
                case .notConfigured, .refused: break
                }
            }
        }
        let next = forecastCardState(flight: flight, answer: answer)
        if next != state {
            state = next
            onChange?(state)
        }
    }

    /// After a purchase: ask again even though the times did not change.
    public func invalidate() { askedFor = nil }
}
