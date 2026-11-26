import Charts
import FlyTrackCore
import SwiftUI

// P3 UI (docs/P3_CONTRACTS.md A2, A5, B1–B4, B7): the detail screen's timeline, prediction, gate, taxi, aircraft and
// connection cards; the connection assistant; airport delay trends. Same content as Android's intel cards.

// MARK: - detail screen block

/// Everything P3 adds to the flight detail screen. It reads the cached flight itself, like `FlightExtrasView`.
struct FlightIntelView: View {
    @ObservedObject var shell: AppShell
    let flightId: String
    /// Pro on this flight; nil while the detail screen has not worked it out.
    var proAccess: FlightProAccess?
    /// Changes whenever the detail screen re-renders (refresh, push, the minute tick).
    var revision: Date?
    @State private var store: FlightIntelStore?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let store, let flight = store.flight {
                let state = store.state
                // P3 B12: crew mode hides Pro upsells (teasers, the age hint, links that only lead to a teaser).
                let crew = shell.p3.crewMode
                if !state.timeline.isEmpty {
                    TimelineCardView(rows: state.timeline, isLocal: state.timelineIsLocal)
                }
                switch state.gate {
                case .hidden: EmptyView()
                case .locked: EmptyView() // the prediction teaser below already offers Pro
                case .ready(let gate): GatePredictionView(prediction: gate)
                }
                switch state.delay {
                case .hidden: EmptyView()
                case .locked:
                    if !crew {
                        IntelTeaser(
                            caption: String(localized: "Prediction"),
                            text: String(localized: "Pro predicts how late this flight will be, from its history, your plane's inbound flight, the weather and the airport.")
                        ) { shell.path.append(Route.paywall) }
                    }
                case .ready(let card): DelayPredictionCardView(card: card)
                }
                if let taxi = state.taxi {
                    TaxiCardView(card: taxi)
                }
                if let aircraft = state.aircraft {
                    AircraftCardView(card: aircraft, depTz: flight.depTz, showProHint: !crew)
                }
                ForEach(state.connections, id: \.id) { connection in
                    ConnectionCardView(connection: connection, currentFlightId: flightId)
                }
                if store.tracked && (!crew || proAccess?.unlocked == true) {
                    intelLinks(flight)
                }
            }
        }
        .task(id: TaskKey(revision: revision, access: proAccess)) {
            if store == nil { store = FlightIntelStore(shell: shell, flightId: flightId) }
            await store?.update(access: proAccess)
        }
        .onChange(of: shell.pro.state) { store?.proChanged() }
    }

    @ViewBuilder
    private func intelLinks(_ flight: Flight) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Button(String(localized: "Connections"), systemImage: "arrow.triangle.branch") {
                shell.path.append(Route.connections)
            }
            .hitTarget()
            Button(String(localized: "Delay trends at \(flight.depIata)"), systemImage: "chart.bar") {
                shell.path.append(Route.airportTrends(flight.depIata))
            }
            .hitTarget()
            if flight.arrIata != flight.depIata {
                Button(String(localized: "Delay trends at \(flight.arrIata)"), systemImage: "chart.bar") {
                    shell.path.append(Route.airportTrends(flight.arrIata))
                }
                .hitTarget()
            }
        }
        .font(FlyFont.sans(15, relativeTo: .body))
    }

    private struct TaskKey: Equatable {
        var revision: Date?
        var access: FlightProAccess?
    }
}

@MainActor
@Observable
final class FlightIntelStore {
    var state = FlightIntelState()
    var flight: Flight?
    var tracked = false

    private let flightId: String
    private let flights: FlightRepository
    private let clock: FlyClock
    private let model: FlightIntelModel

    init(shell: AppShell, flightId: String) {
        self.flightId = flightId
        flights = shell.flights
        clock = shell.clock
        model = FlightIntelModel(
            flightId: flightId, source: shell.intel,
            language: predictionLanguage(preferredLanguages: Bundle.main.preferredLocalizations)
        )
        model.onChange = { [weak self] in self?.state = $0 }
    }

    func update(access: FlightProAccess?) async {
        let details = await flights.trackedFlight(flightId: flightId)
        let shown: Flight?
        if let details { shown = details.flight } else { shown = await flights.flight(id: flightId) }
        flight = shown
        tracked = details.map { !$0.tracking.isArchived } ?? false
        await model.update(flight: shown, pro: access.map(\.unlocked), tracked: tracked, now: clock.now())
    }

    /// After a purchase everything Pro is asked again on the next update.
    func proChanged() { model.invalidate() }
}

// MARK: - shared pieces

private struct IntelCaption: View {
    var text: String
    var body: some View {
        Text(text)
            .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
            .textCase(.uppercase)
            .tracking(1.2)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A Pro feature the user does not have: one line and the way to Pro.
private struct IntelTeaser: View {
    var caption: String
    var text: String
    /// Nil in crew mode (P3 B12: no Pro upsells): the text stays, the way to the paywall goes.
    var openPaywall: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            IntelCaption(text: caption)
            Text(text).font(FlyFont.sans(15, relativeTo: .body))
            if let openPaywall {
                Button(String(localized: "See Pro plans"), action: openPaywall)
                    .font(FlyFont.sans(14, weight: .medium, relativeTo: .subheadline))
                    .hitTarget()
            }
        }
        .intelCard()
    }
}

private extension View {
    func intelCard() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
    }
}

private func footnote(_ text: String) -> some View {
    Text(text)
        .font(FlyFont.sans(13, relativeTo: .footnote))
        .foregroundStyle(FlyColor.muted)
}

// MARK: - A2 timeline

struct TimelineCardView: View {
    var rows: [TimelineRow]
    var isLocal: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            IntelCaption(text: String(localized: "Timeline"))
                .padding(.bottom, 10)
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                TimelineRowView(row: row, isFirst: index == 0, isLast: index == rows.count - 1)
            }
            if isLocal {
                footnote(String(localized: "From the latest flight data on this phone."))
                    .padding(.top, 8)
            }
        }
        .intelCard()
    }
}

private struct TimelineRowView: View {
    var row: TimelineRow
    var isFirst: Bool
    var isLast: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                Rectangle()
                    .fill(isFirst ? Color.clear : lineColor)
                    .frame(width: 2, height: 6)
                Circle()
                    .fill(row.state == .next ? Color.clear : dotColor)
                    .overlay(Circle().stroke(dotColor, lineWidth: 2))
                    .frame(width: row.state == .current ? 14 : 10, height: row.state == .current ? 14 : 10)
                Rectangle()
                    .fill(isLast ? Color.clear : lineColor)
                    .frame(width: 2)
                    .frame(maxHeight: .infinity)
            }
            .frame(width: 14)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline) {
                    Text(timelineTitle(row.key))
                        .font(FlyFont.sans(15, weight: row.state == .current ? .medium : .regular, relativeTo: .body))
                        .foregroundStyle(row.state == .next ? FlyColor.muted : FlyColor.ink)
                    Spacer(minLength: 8)
                    if let time = row.time {
                        Text(row.expected ? String(localized: "Expected \(time)") : time)
                            .font(FlyFont.sans(14, relativeTo: .subheadline))
                            .monospacedDigit()
                            .foregroundStyle(row.expected ? FlyColor.muted : FlyColor.ink)
                    }
                }
                ForEach(row.notes, id: \.self) { note in
                    Text(timelineNoteText(note))
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(FlyColor.muted)
                }
            }
            .padding(.bottom, isLast ? 0 : 10)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
        .accessibilityValue(stateWord(row.state))
    }

    private var dotColor: Color {
        switch row.state {
        case .current: row.key == .cancelled || row.key == .diverted ? FlyColor.cancelled : FlyColor.accentLine
        case .done: FlyColor.primary
        case .next: FlyColor.outline
        }
    }

    private var lineColor: Color { row.state == .next ? FlyColor.outlineVariant : FlyColor.outline }
}

private func timelineTitle(_ key: TimelineKey) -> String {
    switch key {
    case .scheduled: String(localized: "Scheduled")
    case .checkinOpen: String(localized: "Check-in opens")
    case .gateAssigned: String(localized: "Gate assigned")
    case .boarding: String(localized: "Boarding")
    case .gateClosed: String(localized: "Gate closed")
    case .pushback: String(localized: "Pushback")
    case .takeoff: String(localized: "Takeoff")
    case .diverted: String(localized: "Diverted")
    case .landing: String(localized: "Landing")
    case .atGate: String(localized: "At the gate")
    case .belt: String(localized: "Baggage belt")
    case .cancelled: String(localized: "Cancelled")
    }
}

private func timelineNoteText(_ note: TimelineNote) -> String {
    switch note {
    case .gate(let gate, let terminal?): String(localized: "Gate \(gate), Terminal \(terminal)")
    case .gate(let gate, nil): String(localized: "Gate \(gate)")
    case .delay(let minutes) where minutes > 0: String(localized: "\(minutes) min late")
    case .delay(let minutes): String(localized: "\(-minutes) min early")
    case .taxiOut(let minutes): String(localized: "Taxi out \(minutes) min")
    case .taxiIn(let minutes): String(localized: "Taxi in \(minutes) min")
    case .belt(let belt): String(localized: "Belt \(belt)")
    case .checkinCloses(let time): String(localized: "Closes \(time)")
    case .divertedTo(_, let to?): String(localized: "Diverted to \(to)")
    case .divertedTo(_, nil): String(localized: "New destination not announced yet")
    }
}

private func stateWord(_ state: TimelineState) -> String {
    switch state {
    case .done: String(localized: "Done")
    case .current: String(localized: "Now")
    case .next: String(localized: "Coming up")
    }
}

// MARK: - B1 delay prediction

struct DelayPredictionCardView: View {
    var card: DelayPredictionCard

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            IntelCaption(text: String(localized: "Prediction"))
            Text(card.expectedDelayMin >= 5
                 ? String(localized: "About \(card.expectedDelayMin) min late")
                 : String(localized: "Likely on time"))
                .font(FlyFont.serifItalic(28, relativeTo: .title))
                .foregroundStyle(card.expectedDelayMin >= delayThresholdMinutes ? FlyColor.delayed : FlyColor.onTime)
            Text(String(localized: "\(card.percentLate15)% chance of a delay of 15 min or more"))
                .font(FlyFont.sans(15, relativeTo: .body))
                .monospacedDigit()
            Text(String(localized: "\(card.percentLate60)% chance of an hour or more"))
                .font(FlyFont.sans(15, relativeTo: .body))
                .monospacedDigit()
            if !card.factors.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(card.factors, id: \.key) { factor in
                        HStack(alignment: .firstTextBaseline) {
                            Text(factor.label).font(FlyFont.sans(14, relativeTo: .subheadline))
                            Spacer(minLength: 8)
                            Text(factor.effectMin > 0
                                 ? String(localized: "+\(factor.effectMin) min")
                                 : String(localized: "\(factor.effectMin) min"))
                                .font(FlyFont.sans(14, weight: .medium, relativeTo: .subheadline))
                                .monospacedDigit()
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.top, 4)
            }
            footnote(String(localized: "Confidence: \(confidenceWord(card.confidence)). A prediction from past flights, not airline information."))
        }
        .intelCard()
    }
}

private func confidenceWord(_ confidence: PredictionConfidence) -> String {
    switch confidence {
    case .low: String(localized: "low")
    case .medium: String(localized: "medium")
    case .high: String(localized: "high")
    }
}

// MARK: - B2 gate prediction

struct GatePredictionView: View {
    var prediction: GatePrediction

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            IntelCaption(text: String(localized: "Gate prediction"))
            Text(String(localized: "Usually gate \(prediction.gate) (\(prediction.count) of \(prediction.samples))"))
                .font(FlyFont.serifItalic(22, relativeTo: .title3))
            if let terminal = prediction.terminal {
                Text(String(localized: "Terminal \(terminal)")).font(FlyFont.sans(14, relativeTo: .subheadline))
            }
            if !prediction.alternatives.isEmpty {
                Text(prediction.alternatives
                    .map { String(localized: "\($0.gate) (\(Int(($0.share * 100).rounded()))%)") }
                    .joined(separator: ", "))
                    .font(FlyFont.sans(14, relativeTo: .subheadline))
                    .accessibilityLabel(String(localized: "Other gates"))
            }
            footnote(String(localized: "From the last 60 days. The airline has not assigned a gate yet."))
        }
        .intelCard()
    }
}

// MARK: - B3 taxi times

struct TaxiCardView: View {
    var card: TaxiCard

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            IntelCaption(text: String(localized: "Taxi times"))
            if let out = card.taxiOutMin {
                Text(String(localized: "Typical taxi out at \(card.depIata): \(out) min"))
                    .font(FlyFont.sans(15, relativeTo: .body))
            }
            if let taxiIn = card.taxiInMin {
                Text(String(localized: "Typical taxi in at \(card.arrIata): \(taxiIn) min"))
                    .font(FlyFont.sans(15, relativeTo: .body))
            }
            footnote(String(localized: "Median of the last 30 days."))
        }
        .intelCard()
    }
}

// MARK: - A5 aircraft

struct AircraftCardView: View {
    var card: AircraftCard
    var depTz: String?
    /// False in crew mode: no "Pro shows the age" hint.
    var showProHint = true

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            IntelCaption(text: String(localized: "Aircraft"))
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(card.type).font(FlyFont.sans(16, weight: .medium, relativeTo: .headline))
                if let icao = card.icaoType {
                    Text(verbatim: icao).font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.muted)
                }
            }
            if let reg = card.reg {
                Text(String(localized: "Registration \(reg)")).font(FlyFont.sans(14, relativeTo: .subheadline))
            }
            if let age = card.ageYears {
                Text(String(localized: "\(age) years old")).font(FlyFont.sans(14, relativeTo: .subheadline))
            } else if card.ageLocked && showProHint {
                footnote(String(localized: "Pro shows the aircraft's age and alerts you if the airline swaps it."))
            }
            ForEach(Array(card.swaps.enumerated()), id: \.offset) { _, swap in
                Text(swapText(swap))
                    .font(FlyFont.sans(14, relativeTo: .subheadline))
                    .foregroundStyle(FlyColor.delayed)
            }
        }
        .intelCard()
    }

    private func swapText(_ swap: AircraftSwap) -> String {
        let old = [swap.oldReg, swap.oldModel].compactMap { $0 }.joined(separator: " ")
        let new = [swap.newReg, swap.newModel].compactMap { $0 }.joined(separator: " ")
        let change = String(localized: "Aircraft changed: \(old.isEmpty ? "?" : old) → \(new.isEmpty ? "?" : new)")
        guard let at = swap.at else { return change }
        return "\(change) · \(FlightTimeFormat.time(at, tz: depTz))"
    }
}

// MARK: - B4 connections

struct ConnectionCardView: View {
    var connection: FlightConnection
    /// The detail screen's flight; nil on the connections screen.
    var currentFlightId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                IntelCaption(text: String(localized: "Connection at \(connection.airport)"))
                Spacer(minLength: 8)
                Text(riskWord(connection.risk))
                    .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                    .foregroundStyle(FlyColor.tone(connection.risk.tone))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .overlay(Capsule().stroke(FlyColor.tone(connection.risk.tone), lineWidth: 1))
            }
            Text(verbatim: "\(connection.inFlight) → \(connection.outFlight)")
                .font(FlyFont.sans(16, weight: .medium, relativeTo: .headline))
            Text(String(localized: "Layover \(connection.layoverMin) min, minimum connection time \(connection.mctMin) min"))
                .font(FlyFont.sans(14, relativeTo: .subheadline))
                .monospacedDigit()
            Text(bufferText)
                .font(FlyFont.sans(14, weight: .medium, relativeTo: .subheadline))
                .foregroundStyle(FlyColor.tone(connection.risk.tone))
            if let change = connection.terminalChange {
                Text(String(localized: "Terminal change \(change)")).font(FlyFont.sans(14, relativeTo: .subheadline))
            }
            if let note = connection.transferNote {
                footnote(note)
            }
        }
        .intelCard()
        .accessibilityElement(children: .combine)
    }

    private var bufferText: String {
        switch connection.risk {
        case .missed: String(localized: "The next flight leaves before this one lands.")
        case .atRisk: String(localized: "\(-connection.bufferMin) min short of the minimum")
        case .ok, .tight: String(localized: "\(connection.bufferMin) min to spare")
        }
    }
}

private func riskWord(_ risk: ConnectionRisk) -> String {
    switch risk {
    case .ok: String(localized: "Comfortable")
    case .tight: String(localized: "Tight")
    case .atRisk: String(localized: "At risk")
    case .missed: String(localized: "Missed")
    }
}

struct ConnectionsScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: ConnectionsStore?

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch store?.state ?? .loading {
                    case .loading:
                        ProgressView(String(localized: "Loading…"))
                    case .locked:
                        IntelTeaser(
                            caption: String(localized: "Connections"),
                            text: String(localized: "Pro checks the time between your flights against the airport's minimum connection time and alerts you when a connection is at risk."),
                            openPaywall: shell.p3.crewMode ? nil : { shell.path.append(Route.paywall) }
                        )
                    case .offline:
                        Text(String(localized: "You're offline."))
                        retry
                    case .failed:
                        Text(String(localized: "That didn't work. Try again."))
                        retry
                    case .notConfigured:
                        Text(String(localized: "Backend not configured."))
                    case .ready(let list) where list.isEmpty:
                        Text(String(localized: "No connections. Track every flight of a trip and HeyGate checks the time between them."))
                            .font(FlyFont.sans(15, relativeTo: .body))
                    case .ready(let list):
                        ForEach(list, id: \.id) { connection in
                            Button {
                                shell.path.append(Route.detail(connection.outFlightId))
                            } label: {
                                ConnectionCardView(connection: connection, currentFlightId: nil)
                            }
                            .buttonStyle(.plain)
                        }
                        footnote(String(localized: "Minimum connection times are estimates. Check with your airline."))
                    }
                }
                .padding(20)
            }
            .refreshable { await store?.load() }
        }
        .navigationTitle(String(localized: "Connections"))
        .task {
            if store == nil { store = ConnectionsStore(shell: shell) }
            await store?.load()
        }
    }

    private var retry: some View {
        Button(String(localized: "Try again")) { Task { await store?.load() } }
            .hitTarget()
    }
}

@MainActor
@Observable
final class ConnectionsStore {
    var state: ConnectionsState = .loading
    private let model: ConnectionsModel
    private let pro: ProCenter

    init(shell: AppShell) {
        model = ConnectionsModel(source: shell.intel)
        pro = shell.pro
        model.onChange = { [weak self] in self?.state = $0 }
    }

    func load() async {
        let state = pro.state
        // The server also allows the trial flight's connections, so a trial user asks.
        await model.load(pro: state.isPro || state.trialFlightId != nil)
    }
}

// MARK: - B7 airport trends

struct AirportTrendsScreen: View {
    @ObservedObject var shell: AppShell
    let iata: String
    @State private var store: AirportTrendsStore?
    @State private var days = AirportTrendsModel.defaultDays

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Picker(String(localized: "Period"), selection: $days) {
                        Text(String(localized: "7 days")).tag(7)
                        Text(String(localized: "14 days")).tag(14)
                        Text(String(localized: "30 days")).tag(30)
                    }
                    .pickerStyle(.segmented)
                    switch store?.state ?? .loading {
                    case .loading:
                        ProgressView(String(localized: "Loading…"))
                    case .locked:
                        IntelTeaser(
                            caption: String(localized: "Delay trends"),
                            text: String(localized: "Pro shows which hours run late at this airport and how the last weeks went."),
                            openPaywall: shell.p3.crewMode ? nil : { shell.path.append(Route.paywall) }
                        )
                    case .offline:
                        Text(String(localized: "You're offline."))
                    case .failed:
                        Text(String(localized: "That didn't work. Try again."))
                    case .notConfigured:
                        Text(String(localized: "Backend not configured."))
                    case .empty:
                        Text(String(localized: "Not enough flights at this airport yet."))
                    case .ready(let trends):
                        TrendsContent(trends: trends)
                    }
                }
                .padding(20)
            }
        }
        .navigationTitle(String(localized: "Delay trends at \(iata)"))
        .task(id: days) {
            if store == nil { store = AirportTrendsStore(shell: shell, iata: iata) }
            await store?.load(days: days)
        }
    }
}

private struct TrendsContent: View {
    var trends: AirportTrends

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let worst = trends.worstHour(), let delay = worst.avgDepDelay {
                Text(String(localized: "Departures around \(hourLabel(worst.hour)) run \(delay) min late on average"))
                    .font(FlyFont.serifItalic(22, relativeTo: .title3))
            }
            if !trends.byHour.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    IntelCaption(text: String(localized: "Average departure delay by hour"))
                    Chart(trends.byHour, id: \.hour) { hour in
                        BarMark(
                            x: .value(String(localized: "Hour"), hour.hour),
                            y: .value(String(localized: "Minutes late"), max(0, hour.avgDepDelay ?? 0))
                        )
                        .foregroundStyle((hour.avgDepDelay ?? 0) >= delayThresholdMinutes ? FlyColor.accentLine : FlyColor.primary)
                        .accessibilityLabel(hourLabel(hour.hour))
                        .accessibilityValue(String(localized: "\(hour.avgDepDelay ?? 0) min late, \(hour.delayedPct ?? 0)% delayed"))
                    }
                    .chartXScale(domain: 0...23)
                    .chartXAxis {
                        AxisMarks(values: [0, 6, 12, 18, 23]) { value in
                            AxisGridLine()
                            AxisValueLabel { if let hour = value.as(Int.self) { Text(hourLabel(hour)) } }
                        }
                    }
                    .frame(height: 180)
                    footnote(String(localized: "Local time at the airport. Bars in orange average 15 min or more."))
                }
                .intelCard()
            }
            if !trends.byDay.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    IntelCaption(text: String(localized: "Average departure delay by day"))
                    Chart(trends.byDay, id: \.date) { day in
                        BarMark(
                            x: .value(String(localized: "Day"), FlightTimeFormat.dayMonth(day.date)),
                            y: .value(String(localized: "Minutes late"), max(0, day.avgDepDelay ?? 0))
                        )
                        .foregroundStyle(day.cancelled > 0 ? FlyColor.cancelled : FlyColor.primary)
                        .accessibilityLabel(FlightTimeFormat.dayMonth(day.date))
                        .accessibilityValue(String(localized: "\(day.avgDepDelay ?? 0) min late, \(day.cancelled) cancelled"))
                    }
                    .chartXAxis {
                        AxisMarks { _ in AxisValueLabel(orientation: .verticalReversed) }
                    }
                    .frame(height: 200)
                    let cancelled = trends.byDay.reduce(0) { $0 + $1.cancelled }
                    if cancelled > 0 {
                        footnote(String(localized: "\(cancelled) cancelled in this period. Days with cancellations are red."))
                    }
                }
                .intelCard()
            }
        }
    }
}

/// "06:00"
private func hourLabel(_ hour: Int) -> String { String(format: "%02d:00", hour) }

@MainActor
@Observable
final class AirportTrendsStore {
    var state: AirportTrendsState = .loading
    private let model: AirportTrendsModel
    private let pro: ProCenter

    init(shell: AppShell, iata: String) {
        model = AirportTrendsModel(iata: iata, source: shell.intel)
        pro = shell.pro
        model.onChange = { [weak self] in self?.state = $0 }
    }

    func load(days: Int) async {
        await model.load(pro: pro.state.isPro, days: days)
    }
}
