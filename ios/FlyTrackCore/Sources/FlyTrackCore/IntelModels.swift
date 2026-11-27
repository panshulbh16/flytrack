import Foundation

// State machines behind the P3 detail-screen cards and the connections / airport-trends screens. Views only hold
// these models. Every load is an extra on top of the cached flight: errors are silent and keep the earlier answer.

/// Everything the P3 block of the detail screen shows.
public struct FlightIntelState: Hashable, Sendable {
    /// The full timeline; the offline fallback until the server answers.
    public var timeline: [TimelineRow]
    /// True while `timeline` comes from the cached row only (no events, no Pro detail).
    public var timelineIsLocal: Bool
    public var delay: ProCard<DelayPredictionCard>
    public var gate: ProCard<GatePrediction>
    public var taxi: TaxiCard?
    public var aircraft: AircraftCard?
    /// Connections into or out of this flight (Pro).
    public var connections: [FlightConnection]

    public init(
        timeline: [TimelineRow] = [], timelineIsLocal: Bool = true, delay: ProCard<DelayPredictionCard> = .hidden,
        gate: ProCard<GatePrediction> = .hidden, taxi: TaxiCard? = nil, aircraft: AircraftCard? = nil,
        connections: [FlightConnection] = []
    ) {
        self.timeline = timeline
        self.timelineIsLocal = timelineIsLocal
        self.delay = delay
        self.gate = gate
        self.taxi = taxi
        self.aircraft = aircraft
        self.connections = connections
    }
}

/// Loads and refreshes the P3 cards of one flight. Call `update` with the cached flight on open, on refresh and on
/// the minute tick; it asks the server again only when something the answer depends on changed.
@MainActor
public final class FlightIntelModel {
    /// The delay prediction is cached 15 min on the server; asking more often only returns the same answer.
    public nonisolated static let predictionRefresh: TimeInterval = 15 * 60
    /// Connections depend on other flights too; ask again at most this often.
    public nonisolated static let connectionsRefresh: TimeInterval = 5 * 60

    public private(set) var state = FlightIntelState()
    public var onChange: ((FlightIntelState) -> Void)?

    private let flightId: String
    private let source: FlightIntelSource
    private let language: String

    private var timelineItems: [TimelineItem]?
    private var timelineKey: [String?]?
    private var prediction: DelayPrediction?
    private var predictionLocked = false
    private var predictionAskedAt: Date?
    private var predictionKey: Date??
    private var gateAnswer: GatePrediction?
    private var gateLocked = false
    private var gateAsked = false
    private var taxiByIata: [String: TaxiStats] = [:]
    private var taxiAsked: Set<String> = []
    private var swaps: [AircraftSwap] = []
    private var swapsKey: String??
    private var connectionList: [FlightConnection] = []
    private var connectionsAskedAt: Date?

    /// `language`: the label language for `delay_prediction` ("en" or "hi"), see `predictionLanguage`.
    public init(flightId: String, source: FlightIntelSource, language: String = "en") {
        self.flightId = flightId
        self.source = source
        self.language = language
    }

    /// `pro`: Pro for this flight (plan, family or the trial flight); nil while the screen has not worked it out (only
    /// the offline timeline and the aircraft type show then).
    /// `tracked`: the user tracks this flight (connections only exist between tracked flights).
    public func update(flight: Flight?, pro: Bool?, tracked: Bool, now: Date) async {
        guard let flight else {
            publish(FlightIntelState())
            return
        }
        // While the screen has not worked out Pro, the cached row's timeline shows: asking now would ask twice
        // (the Pro answer carries `detail`, the free one not).
        if let pro { await loadTimeline(flight, pro: pro) }
        if pro == true {
            await loadPrediction(flight, now: now)
            await loadGate(flight)
            await loadTaxi(flight)
            await loadSwaps(flight)
            if tracked { await loadConnections(now: now) }
        }
        publish(makeState(flight: flight, pro: pro, now: now))
    }

    /// After a purchase: ask again for everything Pro.
    public func invalidate() {
        predictionAskedAt = nil
        predictionKey = nil
        predictionLocked = false
        gateAsked = false
        gateLocked = false
        taxiAsked = []
        swapsKey = nil
        connectionsAskedAt = nil
        timelineKey = nil
    }

    // MARK: loads

    private func loadTimeline(_ flight: Flight, pro: Bool) async {
        // Pro adds `detail` to every milestone: a purchase (or an expiry) must ask again whatever order the screen
        // reports the new access and the Pro change in.
        let key: [String?] = [
            pro ? "pro" : "free", flight.status.wire, flight.depGate, flight.baggageBelt,
            flight.estDep.map(stamp), flight.actDep.map(stamp), flight.estArr.map(stamp), flight.actArr.map(stamp),
            flight.depOffblockAt.map(stamp), flight.depRunwayAt.map(stamp), flight.arrRunwayAt.map(stamp),
            flight.arrOnblockAt.map(stamp), flight.updatedAt.map(stamp),
        ]
        guard key != timelineKey else { return }
        timelineKey = key
        switch await source.timeline(flightId: flightId) {
        case .ok(let items): timelineItems = items.isEmpty ? nil : items
        // Ask again on the next update (the minute tick).
        case .offline, .failed: timelineKey = nil
        case .notConfigured, .refused: break
        }
    }

    private func loadPrediction(_ flight: Flight, now: Date) async {
        guard flight.wantsDelayPrediction(now: now) else {
            prediction = nil
            return
        }
        let key: Date? = flight.estDep
        let stale = predictionAskedAt.map { now.timeIntervalSince($0) >= Self.predictionRefresh } ?? true
        guard stale || predictionKey != .some(key) else { return }
        predictionAskedAt = now
        predictionKey = .some(key)
        let result = await source.delayPrediction(flightId: flightId, language: language)
        switch result {
        case .ok(let value):
            prediction = value
            predictionLocked = false
        case .offline, .failed:
            predictionAskedAt = nil
        case .refused:
            if isProRequired(result) { predictionLocked = true }
        case .notConfigured:
            break
        }
    }

    private func loadGate(_ flight: Flight) async {
        guard flight.wantsGatePrediction, !gateAsked else { return }
        gateAsked = true
        let result = await source.gatePrediction(flightId: flightId)
        switch result {
        case .ok(let value): gateAnswer = value
        case .offline, .failed: gateAsked = false
        case .refused: if isProRequired(result) { gateLocked = true }
        case .notConfigured: break
        }
    }

    private func loadTaxi(_ flight: Flight) async {
        // Arrival taxi-in stays useful until the plane is at the gate; departure taxi-out until takeoff.
        var wanted: [String] = []
        if flight.depRunwayAt == nil && !flight.hasLanded { wanted.append(flight.depIata) }
        if flight.arrOnblockAt == nil && flight.status != .cancelled { wanted.append(flight.arrIata) }
        for iata in wanted where !taxiAsked.contains(iata) {
            taxiAsked.insert(iata)
            switch await source.taxiStats(iata: iata) {
            case .ok(let stats?): taxiByIata[iata] = stats
            case .ok(nil): break
            case .offline, .failed: taxiAsked.remove(iata)
            case .notConfigured, .refused: break
            }
        }
    }

    private func loadSwaps(_ flight: Flight) async {
        let key: String? = flight.aircraftReg
        guard swapsKey != .some(key) else { return }
        swapsKey = .some(key)
        switch await source.aircraftSwaps(flightId: flightId) {
        case .ok(let list): swaps = list
        case .offline, .failed: swapsKey = nil
        case .notConfigured, .refused: break
        }
    }

    private func loadConnections(now: Date) async {
        if let asked = connectionsAskedAt, now.timeIntervalSince(asked) < Self.connectionsRefresh { return }
        connectionsAskedAt = now
        switch await source.connections() {
        case .ok(let list): connectionList = list
        case .offline, .failed: connectionsAskedAt = nil
        case .notConfigured, .refused: break
        }
    }

    // MARK: state

    private func makeState(flight: Flight, pro: Bool?, now: Date) -> FlightIntelState {
        let items = timelineItems ?? localTimeline(flight)
        var next = FlightIntelState(timeline: timelineRows(items, flight: flight), timelineIsLocal: timelineItems == nil)

        let inWindow = flight.wantsDelayPrediction(now: now)
        switch pro {
        case .none:
            next.delay = .hidden
        case .some(false):
            next.delay = inWindow ? .locked : .hidden
        case .some(true):
            if predictionLocked { next.delay = inWindow ? .locked : .hidden }
            else if inWindow, let prediction { next.delay = .ready(delayPredictionCard(prediction)) }
        }

        if pro == true && flight.wantsGatePrediction {
            if gateLocked { next.gate = .locked }
            else if let gateAnswer { next.gate = .ready(gateAnswer) }
        }

        if pro == true {
            next.taxi = taxiCard(flight: flight, dep: taxiByIata[flight.depIata], arr: taxiByIata[flight.arrIata])
            next.connections = connectionList.filter { $0.involves(flightId) }
        }
        next.aircraft = aircraftCard(flight: flight, pro: pro, swaps: swaps)
        return next
    }

    private func publish(_ next: FlightIntelState) {
        guard next != state else { return }
        state = next
        onChange?(state)
    }
}

private func stamp(_ date: Date) -> String { String(Int64(date.timeIntervalSince1970)) }

// MARK: - connections screen

public enum ConnectionsState: Hashable, Sendable {
    case loading
    /// Not Pro: the teaser.
    case locked
    case offline
    case failed
    case notConfigured
    /// Ordered by the departing leg (server order).
    case ready([FlightConnection])
}

/// The connection assistant screen: every connection between the user's tracked flights.
@MainActor
public final class ConnectionsModel {
    public private(set) var state: ConnectionsState = .loading
    public var onChange: ((ConnectionsState) -> Void)?
    private let source: FlightIntelSource

    public init(source: FlightIntelSource) { self.source = source }

    /// `pro`: false shows the teaser without asking the server.
    public func load(pro: Bool?) async {
        if pro == false {
            set(.locked)
            return
        }
        if case .ready = state {} else { set(.loading) }
        let result = await source.connections()
        switch result {
        case .ok(let list): set(.ready(list))
        case .offline: if case .ready = state {} else { set(.offline) }
        case .failed: if case .ready = state {} else { set(.failed) }
        case .notConfigured: set(.notConfigured)
        case .refused: set(isProRequired(result) ? .locked : .failed)
        }
    }

    private func set(_ next: ConnectionsState) {
        guard next != state else { return }
        state = next
        onChange?(state)
    }
}

// MARK: - airport trends screen

public enum AirportTrendsState: Hashable, Sendable {
    case loading
    case locked
    case offline
    case failed
    case notConfigured
    /// The server has no history for this airport yet.
    case empty
    case ready(AirportTrends)
}

@MainActor
public final class AirportTrendsModel {
    public nonisolated static let defaultDays = 14
    public private(set) var state: AirportTrendsState = .loading
    public var onChange: ((AirportTrendsState) -> Void)?
    public let iata: String
    public private(set) var days: Int
    private let source: FlightIntelSource

    public init(iata: String, source: FlightIntelSource, days: Int = AirportTrendsModel.defaultDays) {
        self.iata = iata.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        self.source = source
        self.days = min(90, max(1, days))
    }

    public func load(pro: Bool?, days: Int? = nil) async {
        if let days { self.days = min(90, max(1, days)) }
        if pro == false {
            set(.locked)
            return
        }
        set(.loading)
        let result = await source.airportTrends(iata: iata, days: self.days)
        switch result {
        case .ok(let trends?): set(trends.isEmpty ? .empty : .ready(trends))
        case .ok(nil): set(.empty)
        case .offline: set(.offline)
        case .failed: set(.failed)
        case .notConfigured: set(.notConfigured)
        case .refused: set(isProRequired(result) ? .locked : .failed)
        }
    }

    private func set(_ next: AirportTrendsState) {
        guard next != state else { return }
        state = next
        onChange?(state)
    }
}
