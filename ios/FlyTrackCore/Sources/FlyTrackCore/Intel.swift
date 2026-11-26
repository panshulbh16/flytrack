import Foundation

// P3 flight intelligence (docs/P3_CONTRACTS.md A5, B1, B2, B3, B4, B7 and section E): parsers for the Pro RPCs and
// the card states the detail screen shows. Same model as Android's prediction / gate / taxi / trends / aircraft cards.

/// A Pro card: nothing to show, the teaser for a user without Pro, or the value.
public enum ProCard<Value: Hashable & Sendable>: Hashable, Sendable {
    case hidden
    case locked
    case ready(Value)

    public var value: Value? {
        if case .ready(let value) = self { return value }
        return nil
    }
}

/// The server's answer for callers without Pro (`pro_required`, 42501) on the P3 Pro RPCs.
public func isProRequired<Value: Sendable>(_ result: RemoteResult<Value>) -> Bool {
    if case .refused(let code, let message) = result {
        return message == "pro_required" || (code == "42501" && message != "not_allowed")
    }
    return false
}

public enum IntelParseError: Error, Equatable {
    case malformed
}

// MARK: - B1 delay prediction

public enum PredictionConfidence: String, CaseIterable, Sendable, Equatable {
    case low, medium, high

    public static func fromWire(_ value: String?) -> PredictionConfidence {
        value.flatMap(PredictionConfidence.init(rawValue:)) ?? .low
    }
}

/// `history`, `inbound`, `weather`, `time_of_day`, `airport`; unknown keys are kept (the label is the server's).
public struct PredictionFactor: Hashable, Sendable {
    public var key: String
    public var effectMin: Int
    /// Already in the language asked for (`p_lang`).
    public var label: String

    public init(key: String, effectMin: Int, label: String) {
        self.key = key
        self.effectMin = effectMin
        self.label = label
    }
}

/// Answer of `delay_prediction(p_flight_id, p_lang)`.
public struct DelayPrediction: Hashable, Sendable {
    public var pDelay15: Double
    public var pDelay60: Double
    public var expectedDelayMin: Int
    public var factors: [PredictionFactor]
    public var confidence: PredictionConfidence
    /// Route legs behind the estimate; nil when the server did not say.
    public var samples: Int?
    /// "rules-v1"; names the method so ML can replace it.
    public var model: String
    public var computedAt: Date?

    public init(
        pDelay15: Double, pDelay60: Double, expectedDelayMin: Int, factors: [PredictionFactor],
        confidence: PredictionConfidence, samples: Int?, model: String, computedAt: Date?
    ) {
        self.pDelay15 = pDelay15
        self.pDelay60 = pDelay60
        self.expectedDelayMin = expectedDelayMin
        self.factors = factors
        self.confidence = confidence
        self.samples = samples
        self.model = model
        self.computedAt = computedAt
    }
}

/// Nil for SQL null (outside the 72 h window, departed, cancelled); throws on a body that is not an object.
public func parseDelayPrediction(_ data: Data) throws -> DelayPrediction? {
    if rpcIsNull(data) { return nil }
    guard let object = JSON.object(from: data) else { throw IntelParseError.malformed }
    return parseDelayPrediction(object)
}

public func parseDelayPrediction(_ object: [String: Any]) -> DelayPrediction? {
    guard let p15 = JSON.double(object["p_delay_15"]) else { return nil }
    let p60 = min(p15, JSON.double(object["p_delay_60"]) ?? 0)
    let factors = (object["factors"] as? [Any] ?? []).compactMap { value -> PredictionFactor? in
        guard let f = value as? [String: Any], let key = JSON.string(f["key"]) else { return nil }
        return PredictionFactor(key: key, effectMin: JSON.int(f["effect_min"]) ?? 0, label: JSON.string(f["label"]) ?? "")
    }
    return DelayPrediction(
        pDelay15: clampProbability(p15),
        pDelay60: clampProbability(p60),
        expectedDelayMin: max(0, JSON.int(object["expected_delay_min"]) ?? 0),
        factors: factors,
        confidence: PredictionConfidence.fromWire(JSON.string(object["confidence"])),
        samples: JSON.int(object["samples"]),
        model: JSON.string(object["model"]) ?? "rules-v1",
        computedAt: parseInstant(JSON.string(object["computed_at"]))
    )
}

private func clampProbability(_ value: Double) -> Double { min(1, max(0, value)) }

/// What the "Prediction" card shows. Percentages are whole numbers.
public struct DelayPredictionCard: Hashable, Sendable {
    public var percentLate15: Int
    public var percentLate60: Int
    public var expectedDelayMin: Int
    public var confidence: PredictionConfidence
    /// Factors that move the estimate, largest effect first; zero effects are left out.
    public var factors: [PredictionFactor]

    public init(percentLate15: Int, percentLate60: Int, expectedDelayMin: Int, confidence: PredictionConfidence, factors: [PredictionFactor]) {
        self.percentLate15 = percentLate15
        self.percentLate60 = percentLate60
        self.expectedDelayMin = expectedDelayMin
        self.confidence = confidence
        self.factors = factors
    }
}

public func delayPredictionCard(_ prediction: DelayPrediction) -> DelayPredictionCard {
    let factors = prediction.factors
        .filter { $0.effectMin != 0 && !$0.label.isEmpty }
        .sorted { abs($0.effectMin) > abs($1.effectMin) }
    return DelayPredictionCard(
        percentLate15: Int((prediction.pDelay15 * 100).rounded()),
        percentLate60: Int((prediction.pDelay60 * 100).rounded()),
        expectedDelayMin: prediction.expectedDelayMin,
        confidence: prediction.confidence,
        factors: factors
    )
}

extension Flight {
    /// The server predicts from 72 h before the best known departure until departure (B1).
    public func wantsDelayPrediction(now: Date) -> Bool {
        guard status != .cancelled, status != .diverted, !hasDeparted, depOffblockAt == nil,
              let departure = estDep ?? schedDep else { return false }
        return now < departure && now >= departure.addingTimeInterval(-72 * 3600)
    }

    /// The gate prediction is useful only while no real gate is assigned and the flight has not left (B2).
    public var wantsGatePrediction: Bool {
        (depGate ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !hasDeparted && status != .cancelled && status != .diverted
    }
}

/// The language the server writes factor labels in: Hindi when the app runs in Hindi, else English.
public func predictionLanguage(preferredLanguages: [String]) -> String {
    let first = preferredLanguages.first?.lowercased() ?? "en"
    return first == "hi" || first.hasPrefix("hi-") || first.hasPrefix("hi_") ? "hi" : "en"
}

// MARK: - B2 gate prediction

public struct GateAlternative: Hashable, Sendable {
    public var gate: String
    public var share: Double

    public init(gate: String, share: Double) {
        self.gate = gate
        self.share = share
    }
}

/// Answer of `gate_prediction(p_flight_id)`.
public struct GatePrediction: Hashable, Sendable {
    public var gate: String
    public var terminal: String?
    public var share: Double
    /// Legs at the top gate.
    public var count: Int
    /// Legs with a gate in the window.
    public var samples: Int
    public var alternatives: [GateAlternative]

    public init(gate: String, terminal: String?, share: Double, count: Int, samples: Int, alternatives: [GateAlternative]) {
        self.gate = gate
        self.terminal = terminal
        self.share = share
        self.count = count
        self.samples = samples
        self.alternatives = alternatives
    }
}

/// Nil for SQL null (fewer than 4 samples); throws on a body that is not an object.
public func parseGatePrediction(_ data: Data) throws -> GatePrediction? {
    if rpcIsNull(data) { return nil }
    guard let object = JSON.object(from: data) else { throw IntelParseError.malformed }
    return parseGatePrediction(object)
}

public func parseGatePrediction(_ object: [String: Any]) -> GatePrediction? {
    guard let gate = JSON.string(object["gate"])?.trimmingCharacters(in: .whitespacesAndNewlines), !gate.isEmpty else { return nil }
    let samples = max(0, JSON.int(object["samples"]) ?? 0)
    let share = clampProbability(JSON.double(object["share"]) ?? 0)
    // `count` is in section E; an older answer without it is rebuilt from the share.
    let count = min(samples, max(0, JSON.int(object["count"]) ?? Int((share * Double(samples)).rounded())))
    let alternatives = (object["alternatives"] as? [Any] ?? []).compactMap { value -> GateAlternative? in
        guard let a = value as? [String: Any], let g = JSON.string(a["gate"]), !g.isEmpty else { return nil }
        return GateAlternative(gate: g, share: clampProbability(JSON.double(a["share"]) ?? 0))
    }
    return GatePrediction(
        gate: gate,
        terminal: JSON.string(object["terminal"]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty },
        share: share, count: count, samples: samples, alternatives: Array(alternatives.prefix(3))
    )
}

// MARK: - B3 taxi times

public struct TaxiTimes: Hashable, Sendable {
    public var p50: Int?
    public var p80: Int?
    public var samples: Int

    public init(p50: Int?, p80: Int?, samples: Int) {
        self.p50 = p50
        self.p80 = p80
        self.samples = samples
    }
}

/// Answer of `taxi_stats(p_iata)`.
public struct TaxiStats: Hashable, Sendable {
    public var iata: String
    public var windowDays: Int
    public var taxiOut: TaxiTimes
    public var taxiIn: TaxiTimes

    public init(iata: String, windowDays: Int, taxiOut: TaxiTimes, taxiIn: TaxiTimes) {
        self.iata = iata
        self.windowDays = windowDays
        self.taxiOut = taxiOut
        self.taxiIn = taxiIn
    }
}

public func parseTaxiStats(_ data: Data) throws -> TaxiStats? {
    if rpcIsNull(data) { return nil }
    guard let object = JSON.object(from: data) else { throw IntelParseError.malformed }
    func times(_ value: Any?) -> TaxiTimes {
        let t = JSON.object(value) ?? [:]
        return TaxiTimes(p50: JSON.int(t["p50"]), p80: JSON.int(t["p80"]), samples: max(0, JSON.int(t["samples"]) ?? 0))
    }
    return TaxiStats(
        iata: JSON.string(object["iata"]) ?? "",
        windowDays: JSON.int(object["window_days"]) ?? 30,
        taxiOut: times(object["taxi_out"]),
        taxiIn: times(object["taxi_in"])
    )
}

/// Fewer legs than this make a "typical" taxi time noise; the line is left out.
public let taxiMinSamples = 5

/// "Typical taxi out at DEL: 18 min" and "Typical taxi in at BOM: 9 min".
public struct TaxiCard: Hashable, Sendable {
    public var depIata: String
    public var taxiOutMin: Int?
    public var arrIata: String
    public var taxiInMin: Int?

    public init(depIata: String, taxiOutMin: Int?, arrIata: String, taxiInMin: Int?) {
        self.depIata = depIata
        self.taxiOutMin = taxiOutMin
        self.arrIata = arrIata
        self.taxiInMin = taxiInMin
    }

    public var isEmpty: Bool { taxiOutMin == nil && taxiInMin == nil }
}

public func taxiCard(flight: Flight, dep: TaxiStats?, arr: TaxiStats?) -> TaxiCard? {
    func typical(_ times: TaxiTimes?) -> Int? {
        guard let times, times.samples >= taxiMinSamples else { return nil }
        return times.p50
    }
    let card = TaxiCard(depIata: flight.depIata, taxiOutMin: typical(dep?.taxiOut), arrIata: flight.arrIata, taxiInMin: typical(arr?.taxiIn))
    return card.isEmpty ? nil : card
}

// MARK: - A5 aircraft

/// One `AIRCRAFT_SWAP` event (Pro): the tail changed before departure.
public struct AircraftSwap: Hashable, Sendable {
    public var at: Date?
    public var oldReg: String?
    public var newReg: String?
    public var oldModel: String?
    public var newModel: String?

    public init(at: Date?, oldReg: String?, newReg: String?, oldModel: String?, newModel: String?) {
        self.at = at
        self.oldReg = oldReg
        self.newReg = newReg
        self.oldModel = oldModel
        self.newModel = newModel
    }
}

/// `flight_events` rows (`created_at`, `payload`) of type AIRCRAFT_SWAP, oldest first.
public func parseAircraftSwaps(_ data: Data) throws -> [AircraftSwap] {
    if rpcIsNull(data) { return [] }
    guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { throw IntelParseError.malformed }
    let swaps = rows.compactMap { value -> AircraftSwap? in
        guard let row = value as? [String: Any] else { return nil }
        let payload = JSON.object(row["payload"]) ?? [:]
        func text(_ key: String) -> String? {
            JSON.string(payload[key]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
        }
        let swap = AircraftSwap(
            at: parseInstant(JSON.string(row["created_at"])),
            oldReg: text("old_reg"), newReg: text("new_reg"), oldModel: text("old_model"), newModel: text("new_model")
        )
        return swap.oldReg == nil && swap.newReg == nil ? nil : swap
    }
    return swaps.sorted { ($0.at ?? .distantPast) < ($1.at ?? .distantPast) }
}

/// The aircraft block: the type for everyone, the age and swap history for Pro.
public struct AircraftCard: Hashable, Sendable {
    /// "Airbus A320neo", else the ICAO type ("A20N").
    public var type: String
    /// "A20N" when the model name is shown and differs from it.
    public var icaoType: String?
    public var reg: String?
    /// Years with one decimal ("8.4"); Pro only.
    public var ageYears: String?
    public var swaps: [AircraftSwap]
    /// Free users see a hint that Pro adds the age.
    public var ageLocked: Bool

    public init(type: String, icaoType: String?, reg: String?, ageYears: String?, swaps: [AircraftSwap], ageLocked: Bool) {
        self.type = type
        self.icaoType = icaoType
        self.reg = reg
        self.ageYears = ageYears
        self.swaps = swaps
        self.ageLocked = ageLocked
    }
}

/// Nil when neither a model nor a type is known. `pro`: Pro for this flight (nil while unknown).
public func aircraftCard(flight: Flight, pro: Bool?, swaps: [AircraftSwap]) -> AircraftCard? {
    let model = flight.aircraftModel?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    let icao = flight.aircraftIcaoType?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
    guard let type = model ?? icao else { return nil }
    let age = pro == true ? flight.aircraftAgeYears.map(formatAgeYears) : nil
    return AircraftCard(
        type: type,
        icaoType: model != nil && icao != nil && icao != model ? icao : nil,
        reg: flight.aircraftReg?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
        ageYears: age,
        swaps: pro == true ? swaps : [],
        ageLocked: pro == false
    )
}

/// "8.4" with a dot in every language (Latin digits, as the rest of the app).
public func formatAgeYears(_ years: Double) -> String {
    String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), max(0, years))
}

// MARK: - B4 connections

public enum ConnectionRisk: String, CaseIterable, Sendable, Equatable {
    case ok, tight
    case atRisk = "at_risk"
    case missed

    public var wire: String { rawValue }

    /// Unknown values read as tight: never claim a safe connection the server did not.
    public static func fromWire(_ value: String?) -> ConnectionRisk {
        value.flatMap(ConnectionRisk.init(rawValue:)) ?? .tight
    }

    public var tone: FlightStatusTone {
        switch self {
        case .ok: return .onTime
        case .tight: return .delayed
        case .atRisk, .missed: return .cancelled
        }
    }

    /// The contract's rule from the buffer (layover − MCT). `missed` needs the times, see `connectionRisk(...)`.
    public static func fromBuffer(_ bufferMin: Int) -> ConnectionRisk {
        if bufferMin >= 30 { return .ok }
        if bufferMin >= 0 { return .tight }
        return .atRisk
    }
}

/// One item of `my_connections()`.
public struct FlightConnection: Hashable, Sendable {
    public var inFlightId: String
    public var outFlightId: String
    public var airport: String
    /// "6E 512"
    public var inFlight: String
    public var outFlight: String
    public var inArr: Date?
    public var outDep: Date?
    public var layoverMin: Int
    public var mctMin: Int
    public var bufferMin: Int
    public var risk: ConnectionRisk
    /// "T1→T3"
    public var terminalChange: String?
    /// The server's English note about the transfer (seed data); shown as sent.
    public var transferNote: String?

    public init(
        inFlightId: String, outFlightId: String, airport: String, inFlight: String, outFlight: String, inArr: Date?,
        outDep: Date?, layoverMin: Int, mctMin: Int, bufferMin: Int, risk: ConnectionRisk, terminalChange: String?,
        transferNote: String?
    ) {
        self.inFlightId = inFlightId
        self.outFlightId = outFlightId
        self.airport = airport
        self.inFlight = inFlight
        self.outFlight = outFlight
        self.inArr = inArr
        self.outDep = outDep
        self.layoverMin = layoverMin
        self.mctMin = mctMin
        self.bufferMin = bufferMin
        self.risk = risk
        self.terminalChange = terminalChange
        self.transferNote = transferNote
    }

    public var id: String { "\(inFlightId):\(outFlightId)" }

    public func involves(_ flightId: String) -> Bool { inFlightId == flightId || outFlightId == flightId }
}

/// Reads `my_connections()`; rows without both flight ids are skipped. SQL null is an empty list.
public func parseConnections(_ data: Data) throws -> [FlightConnection] {
    if rpcIsNull(data) { return [] }
    guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { throw IntelParseError.malformed }
    return rows.compactMap { value in
        guard let c = value as? [String: Any],
              let inId = JSON.string(c["in_flight_id"]), let outId = JSON.string(c["out_flight_id"]) else { return nil }
        let layover = JSON.int(c["layover_min"]) ?? 0
        let mct = JSON.int(c["mct_min"]) ?? 0
        let buffer = JSON.int(c["buffer_min"]) ?? (layover - mct)
        return FlightConnection(
            inFlightId: inId,
            outFlightId: outId,
            airport: JSON.string(c["airport"]) ?? "",
            inFlight: JSON.string(c["in_flight"]) ?? "",
            outFlight: JSON.string(c["out_flight"]) ?? "",
            inArr: parseInstant(JSON.string(c["in_arr"])),
            outDep: parseInstant(JSON.string(c["out_dep"])),
            layoverMin: layover,
            mctMin: mct,
            bufferMin: buffer,
            risk: JSON.string(c["risk"]).map { ConnectionRisk.fromWire($0) } ?? ConnectionRisk.fromBuffer(buffer),
            terminalChange: JSON.string(c["terminal_change"]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty },
            transferNote: JSON.string(c["transfer_note"]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty }
        )
    }
}

/// The risk the app shows from cached times (between server answers): the out flight leaving before the in flight
/// arrives is `missed`; otherwise the contract's buffer rule with the given MCT.
public func connectionRisk(inArr: Date?, outDep: Date?, mctMin: Int) -> ConnectionRisk? {
    guard let inArr, let outDep else { return nil }
    if outDep <= inArr { return .missed }
    let layover = Int((outDep.timeIntervalSince(inArr) / 60).rounded(.toNearestOrAwayFromZero))
    return ConnectionRisk.fromBuffer(layover - mctMin)
}

// MARK: - B7 airport trends

public struct HourTrend: Hashable, Sendable {
    /// 0...23, airport-local.
    public var hour: Int
    public var avgDepDelay: Int?
    public var delayedPct: Int?
    public var samples: Int

    public init(hour: Int, avgDepDelay: Int?, delayedPct: Int?, samples: Int) {
        self.hour = hour
        self.avgDepDelay = avgDepDelay
        self.delayedPct = delayedPct
        self.samples = samples
    }
}

public struct DayTrend: Hashable, Sendable {
    public var date: LocalDate
    public var avgDepDelay: Int?
    public var cancelled: Int
    public var samples: Int

    public init(date: LocalDate, avgDepDelay: Int?, cancelled: Int, samples: Int) {
        self.date = date
        self.avgDepDelay = avgDepDelay
        self.cancelled = cancelled
        self.samples = samples
    }
}

/// Answer of `airport_trends(p_iata, p_days)`.
public struct AirportTrends: Hashable, Sendable {
    public var iata: String
    public var days: Int
    public var byHour: [HourTrend]
    public var byDay: [DayTrend]

    public init(iata: String, days: Int, byHour: [HourTrend], byDay: [DayTrend]) {
        self.iata = iata
        self.days = days
        self.byHour = byHour
        self.byDay = byDay
    }

    public var isEmpty: Bool { byHour.isEmpty && byDay.isEmpty }

    /// The hour with the highest average delay among hours with at least `minSamples` legs.
    public func worstHour(minSamples: Int = 5) -> HourTrend? {
        byHour.filter { $0.samples >= minSamples && $0.avgDepDelay != nil }
            .max { ($0.avgDepDelay ?? 0, -$0.hour) < ($1.avgDepDelay ?? 0, -$1.hour) }
    }
}

public func parseAirportTrends(_ data: Data) throws -> AirportTrends? {
    if rpcIsNull(data) { return nil }
    guard let object = JSON.object(from: data) else { throw IntelParseError.malformed }
    let hours = (object["by_hour"] as? [Any] ?? []).compactMap { value -> HourTrend? in
        guard let h = value as? [String: Any], let hour = JSON.int(h["hour"]), (0...23).contains(hour) else { return nil }
        return HourTrend(
            hour: hour, avgDepDelay: JSON.int(h["avg_dep_delay"]),
            delayedPct: JSON.int(h["delayed_pct"]).map { min(100, max(0, $0)) }, samples: max(0, JSON.int(h["samples"]) ?? 0)
        )
    }.sorted { $0.hour < $1.hour }
    let days = (object["by_day"] as? [Any] ?? []).compactMap { value -> DayTrend? in
        guard let d = value as? [String: Any], let date = JSON.string(d["date"]).flatMap(LocalDate.parse) else { return nil }
        return DayTrend(
            date: date, avgDepDelay: JSON.int(d["avg_dep_delay"]),
            cancelled: max(0, JSON.int(d["cancelled"]) ?? 0), samples: max(0, JSON.int(d["samples"]) ?? 0)
        )
    }.sorted { $0.date < $1.date }
    return AirportTrends(iata: JSON.string(object["iata"]) ?? "", days: JSON.int(object["days"]) ?? 14, byHour: hours, byDay: days)
}

// MARK: - backend port

/// The P3 intelligence RPCs. Every call returns a `RemoteResult` and never throws; `pro_required` arrives as
/// `.refused(code: "42501", message: "pro_required")`.
@MainActor
public protocol FlightIntelSource: AnyObject {
    func timeline(flightId: String) async -> RemoteResult<[TimelineItem]>
    func delayPrediction(flightId: String, language: String) async -> RemoteResult<DelayPrediction?>
    func gatePrediction(flightId: String) async -> RemoteResult<GatePrediction?>
    func taxiStats(iata: String) async -> RemoteResult<TaxiStats?>
    func aircraftSwaps(flightId: String) async -> RemoteResult<[AircraftSwap]>
    func connections() async -> RemoteResult<[FlightConnection]>
    func airportTrends(iata: String, days: Int) async -> RemoteResult<AirportTrends?>
}
