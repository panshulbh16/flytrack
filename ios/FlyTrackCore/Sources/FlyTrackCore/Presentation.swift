import Foundation

public enum FlightStatusWord: String, Sendable, Equatable {
    case scheduleOnly, scheduled, onTime, delayed, early, checkInOpen, boarding, gateClosed
    case departed, inTheAir, landingSoon, landed, arrived, cancelled, diverted
}

public enum Punctuality: Hashable, Sendable {
    case late(Int)
    case early(Int)
    case onTime
    case unknown

    public static func of(_ delayMinutes: Int?) -> Punctuality {
        guard let delayMinutes else { return .unknown }
        if delayMinutes >= delayThresholdMinutes { return .late(delayMinutes) }
        if delayMinutes <= -delayThresholdMinutes { return .early(-delayMinutes) }
        return .onTime
    }
}

public enum StatusLine: Hashable, Sendable {
    case scheduleOnly
    case beforeDeparture(Punctuality)
    case checkInOpen(Punctuality)
    case boarding(gate: String?, departure: Punctuality)
    case gateClosed(Punctuality)
    case inAir(arrival: Punctuality, departure: Punctuality)
    case landed(arrival: Punctuality, atGate: Bool)
    case cancelled
    case diverted(landed: Bool)
}

public enum Countdown: Hashable, Sendable {
    case departs(RelativeTime)
    case departsOn(RelativeDay)
    case lands(RelativeTime)
}

public enum FlightField: String, CaseIterable, Sendable, Equatable {
    case depTerminal, depGate, checkInDesk, arrTerminal, arrGate, baggageBelt
}

public struct FlightInfo: Hashable, Sendable {
    public var field: FlightField
    public var value: String?
    public var isNew: Bool

    public init(field: FlightField, value: String?, isNew: Bool = false) {
        self.field = field
        self.value = value
        self.isNew = isNew
    }
}

public struct FlightTimeModel: Hashable, Sendable {
    public var time: String
    public var kind: TimeKind
    public var replacedTime: String?
    public var date: LocalDate
    public var dayOffset: Int

    public init(time: String, kind: TimeKind, replacedTime: String?, date: LocalDate, dayOffset: Int = 0) {
        self.time = time
        self.kind = kind
        self.replacedTime = replacedTime
        self.date = date
        self.dayOffset = dayOffset
    }
}

public struct FlightCard: Hashable, Sendable {
    public var flightId: String
    public var designator: String
    public var airlineName: String?
    public var depCode: String
    public var arrCode: String
    public var depCity: String
    public var arrCity: String
    public var phase: FlightPhase
    public var statusWord: FlightStatusWord
    public var tone: FlightStatusTone
    public var statusLine: StatusLine
    public var departure: FlightTimeModel?
    public var arrival: FlightTimeModel?
    public var departureDate: LocalDate
    public var departureDay: RelativeDay
    public var duration: TimeInterval?
    public var info: [FlightInfo]
    public var countdown: Countdown?
    /// Share flown (0...1) while airborne, or estimated airborne while offline (`Flight.progressFix`); else nil.
    public var progress: Double?
    public var updatedAt: Date?
    public var asOf: Date
    /// Where `progress` comes from: a fresh live fix ("Live") or the estimate ("Estimated"). Nil without progress.
    public var progressSource: PositionSource?
    /// The expected touchdown `progress` runs to; the remaining time counts down to it.
    public var progressArrival: Date?

    public init(
        flightId: String, designator: String, airlineName: String?, depCode: String, arrCode: String,
        depCity: String, arrCity: String, phase: FlightPhase, statusWord: FlightStatusWord, tone: FlightStatusTone,
        statusLine: StatusLine, departure: FlightTimeModel?, arrival: FlightTimeModel?, departureDate: LocalDate,
        departureDay: RelativeDay, duration: TimeInterval?, info: [FlightInfo], countdown: Countdown?,
        progress: Double?, updatedAt: Date?, asOf: Date, progressSource: PositionSource? = nil, progressArrival: Date? = nil
    ) {
        self.progressSource = progressSource
        self.progressArrival = progressArrival
        self.flightId = flightId
        self.designator = designator
        self.airlineName = airlineName
        self.depCode = depCode
        self.arrCode = arrCode
        self.depCity = depCity
        self.arrCity = arrCity
        self.phase = phase
        self.statusWord = statusWord
        self.tone = tone
        self.statusLine = statusLine
        self.departure = departure
        self.arrival = arrival
        self.departureDate = departureDate
        self.departureDay = departureDay
        self.duration = duration
        self.info = info
        self.countdown = countdown
        self.progress = progress
        self.updatedAt = updatedAt
        self.asOf = asOf
    }
}

extension Flight {
    public func value(of field: FlightField) -> String? {
        let raw: String? = switch field {
        case .depTerminal: depTerminal
        case .depGate: depGate
        case .checkInDesk: depCheckin
        case .arrTerminal: arrTerminal
        case .arrGate: arrGate
        case .baggageBelt: baggageBelt
        }
        let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed?.isEmpty == false) ? trimmed : nil
    }

    public func card(now: Date, newFields: Set<FlightField> = []) -> FlightCard {
        let departurePunctuality = Punctuality.of(departureDelayMinutes)
        let arrivalPunctuality = Punctuality.of(arrivalDelayMinutes)
        let bestDep = bestDepartureTime
        let bestArr = bestArrivalTime
        let departureDate = bestDep.map { FlightTimeFormat.localDate($0.instant, tz: depTz) } ?? flightDate
        // Estimate.swift: a fresh live fix ("Live"), else the shared time estimate ("Estimated"), which also
        // covers a flight whose stale data still says "not departed" (airplane mode).
        let fix = progressFix(now: now)
        let arrival: FlightTimeModel? = bestArr.map { arrival in
            let offset = bestDep.map { FlightTimeFormat.arrivalDayOffset(dep: $0.instant, depTz: depTz, arr: arrival.instant, arrTz: arrTz) } ?? 0
            return timeModel(arrival, scheduled: schedArr, tz: arrTz, punctuality: arrivalPunctuality, dayOffset: offset)
        }
        return FlightCard(
            flightId: id,
            designator: designator,
            airlineName: airlineName.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 },
            depCode: depIata,
            arrCode: arrIata,
            depCity: depAirport?.displayCity ?? depIata,
            arrCity: arrAirport?.displayCity ?? arrIata,
            phase: phase,
            statusWord: statusWord(departurePunctuality),
            tone: cardTone(),
            statusLine: statusLine(departurePunctuality, arrivalPunctuality),
            departure: bestDep.map { timeModel($0, scheduled: schedDep, tz: depTz, punctuality: departurePunctuality, dayOffset: 0) },
            arrival: arrival,
            departureDate: departureDate,
            departureDay: relativeDay(target: departureDate, today: FlightTimeFormat.localDate(now, tz: depTz)),
            duration: duration,
            info: cardInfo(newFields),
            countdown: countdown(now),
            progress: fix?.progress,
            updatedAt: [lastPolledAt, updatedAt].compactMap { $0 }.max(),
            asOf: now,
            progressSource: fix?.source,
            progressArrival: fix?.arrival
        )
    }

    private func statusWord(_ departure: Punctuality) -> FlightStatusWord {
        if status == .cancelled { return .cancelled }
        if status == .diverted { return .diverted }
        if hasLanded { return status == .arrived ? .arrived : .landed }
        if hasDeparted {
            switch status {
            case .approaching: return .landingSoon
            case .enRoute: return .inTheAir
            default: return .departed
            }
        }
        if status == .boarding { return .boarding }
        if status == .gateClosed { return .gateClosed }
        if case .late = departure { return .delayed }
        if case .early = departure { return .early }
        if status == .checkIn { return .checkInOpen }
        if case .onTime = departure { return .onTime }
        if status == .unknown { return .scheduleOnly }
        return .scheduled
    }

    private func cardTone() -> FlightStatusTone {
        let tone = statusTone
        if phase == .inAir, let arrivalDelay = arrivalDelayMinutes {
            return arrivalDelay >= delayThresholdMinutes ? .delayed : .active
        }
        if tone == .estimated, departureDelayMinutes != nil { return .onTime }
        return tone
    }

    private func statusLine(_ departure: Punctuality, _ arrival: Punctuality) -> StatusLine {
        switch phase {
        case .cancelled: return .cancelled
        case .diverted: return .diverted(landed: hasLanded)
        case .landed: return .landed(arrival: arrival, atGate: status == .arrived)
        case .inAir: return .inAir(arrival: arrival, departure: departure)
        case .boarding:
            if status == .gateClosed { return .gateClosed(departure) }
            return .boarding(gate: value(of: .depGate), departure: departure)
        case .checkIn: return .checkInOpen(departure)
        case .upcoming:
            if status == .unknown, case .unknown = departure { return .scheduleOnly }
            return .beforeDeparture(departure)
        }
    }

    private func cardInfo(_ newFields: Set<FlightField>) -> [FlightInfo] {
        let fields: [FlightField]
        if status == .cancelled {
            fields = []
        } else if hasDeparted {
            fields = [.arrTerminal, .arrGate, .baggageBelt]
        } else {
            fields = [.depTerminal, .depGate]
        }
        return fields.compactMap { field in
            value(of: field).map { FlightInfo(field: field, value: $0, isNew: newFields.contains(field)) }
        }
    }

    private func countdown(_ now: Date) -> Countdown? {
        switch phase {
        case .cancelled, .diverted, .landed:
            return nil
        case .inAir:
            guard let arrival = bestArrivalTime?.instant, arrival > now else { return nil }
            return .lands(relativeTime(target: arrival, now: now))
        case .upcoming, .checkIn, .boarding:
            guard let dep = bestDepartureTime?.instant, dep > now else { return nil }
            if dep.timeIntervalSince(now) < 24 * 3600 { return .departs(relativeTime(target: dep, now: now)) }
            return .departsOn(relativeDay(target: dep, now: now, tz: depTz))
        }
    }
}

private func timeModel(_ best: BestTime, scheduled: Date?, tz: String?, punctuality: Punctuality, dayOffset: Int) -> FlightTimeModel {
    let shifted: Bool
    switch punctuality {
    case .late, .early: shifted = true
    default: shifted = false
    }
    let replaced = (shifted && best.kind != .scheduled) ? scheduled.map { FlightTimeFormat.time($0, tz: tz) } : nil
    return FlightTimeModel(
        time: FlightTimeFormat.time(best.instant, tz: tz),
        kind: best.kind,
        replacedTime: replaced,
        date: FlightTimeFormat.localDate(best.instant, tz: tz),
        dayOffset: dayOffset
    )
}

public struct NextFlight: Hashable, Sendable {
    public var card: FlightCard
    public var countdown: Countdown
    public init(card: FlightCard, countdown: Countdown) {
        self.card = card
        self.countdown = countdown
    }
}

public struct FlightSections: Hashable, Sendable {
    public var next: NextFlight?
    public var upcoming: [FlightCard]
    public var past: [FlightCard]
    public var isEmpty: Bool { upcoming.isEmpty && past.isEmpty }

    public init(next: NextFlight?, upcoming: [FlightCard], past: [FlightCard]) {
        self.next = next
        self.upcoming = upcoming
        self.past = past
    }
}

public let justLandedWindow: TimeInterval = 2 * 3600
public let staleAfterArrival: TimeInterval = 12 * 3600
public let staleAfterDeparture: TimeInterval = 24 * 3600

extension Flight {
    public func isPast(now: Date) -> Bool {
        let arrival = bestArrival
        let departure = bestDeparture
        if status == .cancelled { return now >= endOfFlightDate() }
        if hasLanded { return arrival == nil || now.timeIntervalSince(arrival!) >= justLandedWindow }
        if let arrival { return now.timeIntervalSince(arrival) >= staleAfterArrival }
        if let departure { return now.timeIntervalSince(departure) >= staleAfterDeparture }
        return now >= endOfFlightDate().addingTimeInterval(staleAfterArrival)
    }

    public func endOfFlightDate() -> Date {
        flightDate.adding(days: 1).startOfDay(in: timeZone(for: depTz))
    }
}

public func flightSections(
    _ tracked: [TrackedFlightDetails],
    now: Date,
    newFields: (Flight) -> Set<FlightField> = { _ in [] }
) -> FlightSections {
    let flights = tracked.map(\.flight)
    let past = flights.filter { $0.isPast(now: now) }
    let upcoming = flights.filter { !$0.isPast(now: now) }
    let upcomingCards = upcoming.sorted { lhs, rhs in
        switch (lhs.bestDeparture, rhs.bestDeparture) {
        case let (l?, r?): return l < r
        case (nil, _?): return false
        case (_?, nil): return true
        case (nil, nil): return false
        }
    }.map { $0.card(now: now, newFields: newFields($0)) }
    let pastCards = past.sorted { lhs, rhs in
        switch (lhs.bestDeparture, rhs.bestDeparture) {
        case let (l?, r?): return l > r
        case (nil, _?): return false
        case (_?, nil): return true
        case (nil, nil): return false
        }
    }.map { $0.card(now: now, newFields: newFields($0)) }
    return FlightSections(next: nextFlight(upcomingCards), upcoming: upcomingCards, past: pastCards)
}

private func nextFlight(_ upcoming: [FlightCard]) -> NextFlight? {
    let candidates = upcoming.filter { $0.countdown != nil }
    let pick = candidates.first { $0.phase == .inAir } ?? candidates.first
    guard let pick, let countdown = pick.countdown else { return nil }
    return NextFlight(card: pick, countdown: countdown)
}

public enum AlertGroup: String, CaseIterable, Sendable, Equatable {
    case delays, gate, boarding, takeoffAndLanding, baggage, disruption, inboundAircraft, checkin
    /// Fog and low visibility at either airport (FOG_ALERT, free). Same group as Android's WEATHER.
    case weather
    /// P3: the airline swapped the aircraft before departure (Pro). Android's AIRCRAFT_SWAP group.
    case aircraftSwap
    /// P3: the connection from this flight is at risk (Pro). Android's CONNECTIONS group.
    case connections

    public var types: [AlertType] {
        switch self {
        case .delays: return [.delay, .delayReduced, .scheduleChange]
        case .gate: return [.gateAssigned, .gateChange, .terminalChange]
        case .boarding: return [.boarding, .gateClosed]
        case .takeoffAndLanding: return [.departed, .takeoff, .landed, .atGate]
        case .baggage: return [.beltAssigned, .beltChange]
        case .disruption: return [.cancelled, .diverted, .reinstated, .divertedDest]
        case .inboundAircraft: return [.inboundLate]
        case .checkin: return [.checkinOpen, .checkinClosing]
        case .weather: return [.fogAlert]
        case .aircraftSwap: return [.aircraftSwap]
        case .connections: return [.connectionAtRisk]
        }
    }

    public var proOnly: Bool { types.allSatisfy(\.proOnly) }

    public func prefs(enabled: Bool) -> [AlertType: Bool] {
        Dictionary(uniqueKeysWithValues: types.map { ($0, enabled) })
    }

    public func isEnabled(_ tracking: TrackedFlight) -> Bool {
        types.contains { tracking.notifies($0) }
    }
}

public struct AlertToggle: Hashable, Sendable {
    public var group: AlertGroup
    public var enabled: Bool
    public var locked: Bool

    public init(group: AlertGroup, enabled: Bool, locked: Bool = false) {
        self.group = group
        self.enabled = enabled
        self.locked = locked
    }
}

public func alertToggles(muted: Set<AlertType>, isPro: Bool) -> [AlertToggle] {
    AlertGroup.allCases.map { group in
        let locked = group.proOnly && !isPro
        return AlertToggle(group: group, enabled: group.types.contains { !muted.contains($0) }, locked: locked)
    }
}

public final class FlightChangeTracker: @unchecked Sendable {
    public static let highlightWindow: TimeInterval = 30 * 60
    private let lock = NSLock()
    private var lastValues: [String: [FlightField: String?]] = [:]
    private var changedAt: [String: [FlightField: Date]] = [:]

    public init() {}

    public func record(_ flight: Flight, now: Date) -> Set<FlightField> {
        lock.lock()
        defer { lock.unlock() }
        var current: [FlightField: String?] = [:]
        for field in FlightField.allCases { current[field] = flight.value(of: field) }
        let previous = lastValues[flight.id]
        lastValues[flight.id] = current
        var changes = changedAt[flight.id] ?? [:]
        if let previous {
            for (field, value) in current {
                if value == nil {
                    changes[field] = nil
                } else if value != previous[field] {
                    changes[field] = now
                }
            }
        }
        changes = changes.filter { now.timeIntervalSince($0.value) < Self.highlightWindow }
        changedAt[flight.id] = changes
        return Set(changes.keys)
    }
}

public enum TrackingState: String, Sendable, Equatable {
    case tracked, archived, notTracked
}

public struct EndpointModel: Hashable, Sendable {
    public var code: String
    public var city: String
    public var airportName: String?
    public var time: FlightTimeModel?
    public var scheduled: String?
    public var estimated: String?
    public var actual: String?
    public var info: [FlightInfo]
}

public enum ProInsight: Hashable, Sendable {
    case none
    case teaser
    case details(
        inboundFlight: String?, inboundFrom: String?, inboundArrival: String?, inboundLanded: Bool,
        lateMinutes: Int, predictedDeparture: String?, reason: String?
    )

    public static let lateFromMinutes = 10
}

public struct BookingModel: Hashable, Sendable {
    public var pnr: String?
    public var seat: String?
    public var cabin: Cabin?
    public var note: String?
    public var isEmpty: Bool { pnr == nil && seat == nil && cabin == nil && note == nil }
}

public struct FlightDetailModel: Hashable, Sendable {
    public var card: FlightCard
    public var flightDate: LocalDate
    public var delayReason: String?
    public var departure: EndpointModel
    public var arrival: EndpointModel
    public var distanceKm: Int?
    public var aircraftModel: String?
    public var aircraftReg: String?
    public var proInsight: ProInsight
    public var trackingState: TrackingState
    public var booking: BookingModel?
    public var alerts: [AlertToggle]
}

extension Flight {
    public func detail(tracking: TrackedFlight?, isPro: Bool, now: Date, newFields: Set<FlightField> = []) -> FlightDetailModel {
        let card = card(now: now, newFields: newFields)
        let cancelled = status == .cancelled
        func info(stillExpected: Bool, fields: [FlightField]) -> [FlightInfo] {
            fields.map { FlightInfo(field: $0, value: value(of: $0), isNew: newFields.contains($0)) }
                .filter { $0.value != nil || stillExpected }
        }
        let state: TrackingState
        if let tracking {
            state = tracking.isArchived ? .archived : .tracked
        } else {
            state = .notTracked
        }
        let alerts: [AlertToggle]
        if let tracking {
            alerts = AlertGroup.allCases.map { group in
                let locked = group.proOnly && !isPro
                return AlertToggle(group: group, enabled: !locked && group.isEnabled(tracking), locked: locked)
            }
        } else {
            alerts = []
        }
        return FlightDetailModel(
            card: card,
            flightDate: flightDate,
            delayReason: delayReason?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            departure: EndpointModel(
                code: depIata,
                city: card.depCity,
                airportName: depAirport?.name,
                time: card.departure,
                scheduled: schedDep.map { FlightTimeFormat.time($0, tz: depTz) },
                estimated: estDep.map { FlightTimeFormat.time($0, tz: depTz) },
                actual: actDep.map { FlightTimeFormat.time($0, tz: depTz) },
                info: info(stillExpected: !cancelled && !hasDeparted, fields: [.depTerminal, .depGate, .checkInDesk])
            ),
            arrival: EndpointModel(
                code: arrIata,
                city: card.arrCity,
                airportName: arrAirport?.name,
                time: card.arrival,
                scheduled: schedArr.map { FlightTimeFormat.time($0, tz: arrTz) },
                estimated: estArr.map { FlightTimeFormat.time($0, tz: arrTz) },
                actual: actArr.map { FlightTimeFormat.time($0, tz: arrTz) },
                info: info(stillExpected: !cancelled, fields: [.arrTerminal, .arrGate, .baggageBelt])
            ),
            distanceKm: distanceKm,
            aircraftModel: aircraftModel?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            aircraftReg: aircraftReg?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty,
            proInsight: proInsight(isPro: isPro),
            trackingState: state,
            booking: tracking.map {
                BookingModel(pnr: $0.pnr, seat: $0.seat, cabin: $0.cabin, note: $0.note?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty)
            },
            alerts: alerts
        )
    }

    private func proInsight(isPro: Bool) -> ProInsight {
        if hasDeparted || status == .cancelled { return .none }
        if isPro {
            guard let prediction else { return .none }
            let arrival = prediction.inbound.flatMap { $0.actArr ?? $0.estArr ?? $0.schedArr }
            let landed = prediction.inbound.map {
                $0.actArr != nil || $0.status == .landed || $0.status == .arrived
            } ?? false
            return .details(
                inboundFlight: prediction.inbound?.flight.nonEmpty,
                inboundFrom: prediction.inbound?.fromIata.nonEmpty,
                inboundArrival: arrival.map { FlightTimeFormat.time($0, tz: depTz) },
                inboundLanded: landed,
                lateMinutes: max(0, prediction.lateMin),
                predictedDeparture: prediction.predictedDep.map { FlightTimeFormat.time($0, tz: depTz) },
                reason: prediction.reason?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
            )
        }
        return hasProInsight ? .teaser : .none
    }
}
