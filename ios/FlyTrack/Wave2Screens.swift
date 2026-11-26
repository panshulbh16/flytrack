import EventKit
import EventKitUI
import FlyTrackCore
import SwiftUI
import UIKit

// P2 wave 2 UI besides Friends: the detail screen's forecast and when-to-leave cards, "Add to calendar" and the
// share-with-friends switch; the email forwarding screen; the calendar subscription in Settings.

// MARK: - detail screen extras

/// Everything wave 2 adds to the flight detail screen, below the check-in card. It reads the cached flight itself,
/// so the detail model stays as it was.
struct FlightExtrasView: View {
    @ObservedObject var shell: AppShell
    let flightId: String
    /// Pro on this flight; nil while the detail screen has not worked it out.
    var proAccess: FlightProAccess?
    /// Changes whenever the detail screen re-renders (refresh, push, the minute tick): the card's `asOf`.
    var revision: Date?
    @State private var store: FlightExtrasStore?
    @State private var eventSheet: CalendarEventItem?
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            if let store {
                switch store.leave {
                case .hidden: EmptyView()
                case .locked: ProTeaserCard(caption: String(localized: "When to leave"), text: String(localized: "Pro tells you when to leave for the airport, with fog and rush hour counted, and reminds you 15 minutes before.")) { shell.path.append(Route.paywall) }
                case .ready(let card):
                    WhenToLeaveCardView(
                        card: card,
                        changeTravel: { minutes in Task { await store.setTravelMinutes(minutes) } },
                        open: { url in openURL(url) }
                    )
                }
                switch store.forecast {
                case .hidden: EmptyView()
                case .locked: ProTeaserCard(caption: String(localized: "Arrival forecast"), text: String(localized: "Pro shows when this flight usually lands and how often it runs on time.")) { shell.path.append(Route.paywall) }
                case .ready(let model): ForecastCardView(model: model)
                }
                if let flight = store.flight, flight.bestDeparture != nil {
                    Button(String(localized: "Add to calendar"), systemImage: "calendar.badge.plus") {
                        eventSheet = calendarEventSpec(flight).map(CalendarEventItem.init(spec:))
                    }
                    .hitTarget()
                }
                if let share = store.share.share {
                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(String(localized: "Friends can see this flight"), isOn: Binding(
                            get: { share },
                            set: { value in Task { await store.setShare(value) } }
                        ))
                        .disabled(store.share.isSaving)
                        .hitTarget()
                        Text(store.share.failed
                             ? String(localized: "That didn't save. Try again.")
                             : String(localized: "Friends get an alert when it takes off and lands."))
                            .font(FlyFont.sans(13, relativeTo: .footnote))
                            .foregroundStyle(store.share.failed ? FlyColor.cancelled : FlyColor.muted)
                    }
                }
            }
        }
        .sheet(item: $eventSheet) { item in
            EventEditSheet(spec: item.spec) { eventSheet = nil }
                .ignoresSafeArea()
        }
        .task(id: TaskKey(revision: revision, access: proAccess)) {
            if store == nil { store = FlightExtrasStore(shell: shell, flightId: flightId) }
            await store?.update(access: proAccess)
        }
        .onChange(of: shell.pro.state) { store?.proChanged() }
    }

    private struct TaskKey: Equatable {
        var revision: Date?
        var access: FlightProAccess?
    }

    private func calendarEventSpec(_ flight: Flight) -> CalendarEventSpec? {
        let notes = String(localized: "\(flight.designator), \(flight.depIata) to \(flight.arrIata). Gate, delay and belt updates are live in HeyGate.")
        return FlyTrackCore.calendarEvent(
            for: flight,
            terminalLocation: { name, terminal in String(localized: "\(name), Terminal \(terminal)") },
            notes: notes
        )
    }
}

/// One "Add to calendar" sheet.
private struct CalendarEventItem: Identifiable {
    let id = UUID()
    let spec: CalendarEventSpec
}

/// A Pro feature the user does not have on this flight: one line and the way to Pro.
private struct ProTeaserCard: View {
    var caption: String
    var text: String
    var openPaywall: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardCaption(text: caption)
            Text(text).font(FlyFont.sans(15, relativeTo: .body))
            Button(String(localized: "See Pro plans"), action: openPaywall)
                .font(FlyFont.sans(14, weight: .medium, relativeTo: .subheadline))
                .hitTarget()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
    }
}

private struct CardCaption: View {
    var text: String
    var body: some View {
        Text(text)
            .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
            .textCase(.uppercase)
            .tracking(1.2)
            .accessibilityAddTraits(.isHeader)
    }
}

/// "Leave by 05:40" (contract section 5).
struct WhenToLeaveCardView: View {
    var card: WhenToLeaveCard
    var changeTravel: (Int) -> Void
    var open: (URL) -> Void

    var body: some View {
        let plan = card.plan
        VStack(alignment: .leading, spacing: 8) {
            CardCaption(text: String(localized: "When to leave"))
            Text(String(localized: "Leave by \(plan.leaveByTime)"))
                .font(FlyFont.serifItalic(30, relativeTo: .title))
                .foregroundStyle(FlyColor.accent)
            Text(String(localized: "\(plan.travelMinutes) min to reach \(card.depCity) airport, then \(plan.bufferMinutes) min there before the \(plan.departureTime) departure."))
                .font(FlyFont.sans(15, relativeTo: .body))
            if plan.fog {
                reason(String(localized: "Includes 30 min for fog or disruption at the airport."))
            }
            if plan.peak {
                reason(String(localized: "Includes 20 min for rush-hour traffic."))
            }
            if plan.international {
                reason(String(localized: "International: 3 hours at the airport."))
            }
            Stepper(
                String(localized: "Travel time: \(plan.travelMinutes) min"),
                value: Binding(get: { plan.travelMinutes }, set: changeTravel),
                in: LeaveRules.minTravelMin...LeaveRules.maxTravelMin,
                step: 5
            )
            .font(FlyFont.sans(15, relativeTo: .body))
            .hitTarget()
            Text(card.reminderAhead
                 ? String(localized: "We'll remind you 15 minutes before.")
                 : String(localized: "Times are at the departure airport."))
                .font(FlyFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(FlyColor.muted)
            Menu {
                if let apple = card.appleMapsURL {
                    Button(String(localized: "Apple Maps")) { open(apple) }
                }
                if let google = card.googleMapsURL {
                    Button(String(localized: "Google Maps")) { open(google) }
                }
            } label: {
                Label(String(localized: "Get directions"), systemImage: "arrow.triangle.turn.up.right.diamond")
            }
            .hitTarget()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
    }

    private func reason(_ text: String) -> some View {
        Text(text).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
    }
}

/// Arrival forecast and on-time history (contract section 4).
struct ForecastCardView: View {
    var model: ForecastCardModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CardCaption(text: String(localized: "Arrival forecast"))
            if let p50 = model.p50Time {
                Text(String(localized: "Likely to land by \(p50)"))
                    .font(FlyFont.serifItalic(26, relativeTo: .title2))
                if let p80 = model.p80Time {
                    Text(String(localized: "Almost certainly by \(p80)"))
                        .font(FlyFont.sans(15, relativeTo: .body))
                }
                if let delta = model.p50VsScheduleMin {
                    Text(scheduleText(delta))
                        .font(FlyFont.sans(14, relativeTo: .subheadline))
                        .foregroundStyle(delta >= delayThresholdMinutes ? FlyColor.delayed : FlyColor.onTime)
                }
                Text(basisText)
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.muted)
            }
            if let history = model.history {
                if model.p50Time != nil { Divider().overlay(FlyColor.outlineVariant) }
                Text(String(localized: "Last \(history.windowDays) days, \(history.samples) flights"))
                    .font(FlyFont.sans(13, weight: .medium, relativeTo: .footnote))
                if let pct = history.onTimePct {
                    Text(String(localized: "On time \(pct)% of the time"))
                        .font(FlyFont.serifItalic(20, relativeTo: .title3))
                }
                if let dep = history.avgDepDelayMin, dep > 0 {
                    line(String(localized: "Leaves \(dep) min late on average"))
                }
                if let arr = history.medianArrDelayMin {
                    line(arr > 0 ? String(localized: "Usually lands \(arr) min late") : String(localized: "Usually lands on time"))
                }
                if history.cancelled > 0 {
                    line(String(localized: "\(history.cancelled) cancelled"))
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
        .accessibilityElement(children: .combine)
    }

    private var basisText: String {
        let confidence: String = switch model.confidence ?? .low {
        case .high: String(localized: "high confidence")
        case .medium: String(localized: "medium confidence")
        case .low: String(localized: "low confidence")
        }
        if model.method == .history {
            return String(localized: "From past flights, \(confidence). Arrival airport time.")
        }
        return String(localized: "From the timetable only. Arrival airport time.")
    }

    private func scheduleText(_ delta: Int) -> String {
        if delta > 0 { return String(localized: "\(delta) min after schedule") }
        if delta < 0 { return String(localized: "\(-delta) min before schedule") }
        return String(localized: "On schedule")
    }

    private func line(_ text: String) -> some View {
        Text(text).font(FlyFont.sans(14, relativeTo: .subheadline))
    }
}

/// The system's new-event sheet with the flight filled in. EventKitUI runs it out of process, so adding one event
/// needs no calendar permission (iOS 17 and later); the user picks the calendar and saves.
struct EventEditSheet: UIViewControllerRepresentable {
    var spec: CalendarEventSpec
    var done: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(done: done) }

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let event = EKEvent(eventStore: store)
        event.title = spec.title
        event.startDate = spec.start
        event.endDate = spec.end
        event.location = spec.location
        event.notes = spec.notes
        event.timeZone = spec.timeZone.flatMap(TimeZone.init(identifier:))
        let controller = EKEventEditViewController()
        controller.eventStore = store
        controller.event = event
        controller.editViewDelegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: EKEventEditViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject {
        let done: () -> Void
        init(done: @escaping () -> Void) { self.done = done }
    }
}

extension EventEditSheet.Coordinator: @preconcurrency EKEventEditViewDelegate {
    func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
        done()
    }
}

@MainActor
@Observable
final class FlightExtrasStore {
    var forecast: ForecastCardState = .hidden
    var leave: WhenToLeaveState = .hidden
    var share = ShareWithFriendsState()
    /// The cached flight (tracked or not), for "Add to calendar".
    var flight: Flight?

    private let flightId: String
    private let flights: FlightRepository
    private let forecastModel: FlightForecastModel
    private let leaveModel: WhenToLeaveModel
    private let shareModel: ShareWithFriendsModel
    private var shareLoaded = false

    init(shell: AppShell, flightId: String) {
        self.flightId = flightId
        flights = shell.flights
        forecastModel = FlightForecastModel(flightId: flightId, source: shell.forecast)
        leaveModel = WhenToLeaveModel(
            flightId: flightId, travel: shell.travelTimes, scheduler: shell.leaveReminders,
            health: shell.airportHealth, clock: shell.clock
        )
        shareModel = ShareWithFriendsModel(flightId: flightId, repository: shell.friends)
        forecastModel.onChange = { [weak self] in self?.forecast = $0 }
        leaveModel.onChange = { [weak self] in self?.leave = $0 }
        shareModel.onChange = { [weak self] in self?.share = $0 }
    }

    func update(access: FlightProAccess?) async {
        let details = await flights.trackedFlight(flightId: flightId)
        let tracked = details.flatMap { $0.tracking.isArchived ? nil : $0.flight }
        let shown: Flight?
        if let details { shown = details.flight } else { shown = await flights.flight(id: flightId) }
        flight = shown
        await leaveModel.update(flight: tracked, pro: access.map(\.unlocked))
        await forecastModel.update(flight: shown)
        if tracked != nil, !shareLoaded {
            shareLoaded = true
            await shareModel.load()
        }
    }

    func setTravelMinutes(_ minutes: Int) async { await leaveModel.setTravelMinutes(minutes) }
    func setShare(_ value: Bool) async { await shareModel.set(value) }

    /// After a purchase the forecast is asked again on the next update.
    func proChanged() { forecastModel.invalidate() }
}

// MARK: - email forwarding (contract section 2)

struct ForwardingScreen: View {
    @ObservedObject var shell: AppShell
    @State private var load: LinkLoad = .loading
    @State private var copied = false
    @State private var shareText: ShareText?

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(String(localized: "Forward your booking emails"))
                        .font(FlyFont.serif(30, relativeTo: .title))
                    Text(String(localized: "Send airline and travel-site confirmations to your own HeyGate address. We find the flights and start tracking them, and tell you when they're added."))
                        .font(FlyFont.sans(16, relativeTo: .body))
                    switch load {
                    case .loading:
                        ProgressView()
                    case .ready(let address):
                        Text(verbatim: address)
                            .font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                            .textSelection(.enabled)
                            .padding(14)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
                            .accessibilityLabel(String(localized: "Your forwarding address, \(address)"))
                        HStack(spacing: 12) {
                            Button(copied ? String(localized: "Copied") : String(localized: "Copy address")) {
                                UIPasteboard.general.string = address
                                copied = true
                            }
                            .buttonStyle(.borderedProminent)
                            .foregroundStyle(FlyColor.paper)
                            .hitTarget()
                            Button(String(localized: "Share…")) { shareText = ShareText(text: address) }
                                .buttonStyle(.bordered)
                                .hitTarget()
                        }
                        Text(String(localized: "Tip: in Gmail or Mail, add this address as a contact, then forward each booking as it arrives."))
                            .font(FlyFont.sans(14, relativeTo: .subheadline))
                            .foregroundStyle(FlyColor.muted)
                    case .offline:
                        retry(String(localized: "You're offline. Your address will show when you're back online."))
                    case .notConfigured:
                        Text(String(localized: "This build has no server address."))
                    case .failed:
                        retry(String(localized: "Couldn't get your address."))
                    }
                    if !shell.pro.state.isPro {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(String(localized: "Email import is part of HeyGate Pro. Emails forwarded without Pro are not read."))
                                .font(FlyFont.sans(15, relativeTo: .body))
                            Button(String(localized: "See Pro plans")) { shell.path.append(Route.paywall) }
                                .hitTarget()
                        }
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(FlyColor.accentLine, lineWidth: 1))
                    }
                    Text(String(localized: "We read each email only to find flights, and don't keep it. Emails over 5 MB are skipped."))
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(FlyColor.muted)
                }
                .padding(20)
            }
        }
        .navigationTitle(String(localized: "Import by email"))
        .sheet(item: $shareText) { item in TextShareSheet(text: item.text).ignoresSafeArea() }
        .task { await reload() }
    }

    private func retry(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(text).font(FlyFont.sans(15, relativeTo: .body))
            Button(String(localized: "Try again")) { Task { await reload() } }
                .hitTarget()
        }
    }

    private func reload() async {
        load = .loading
        load = linkLoad(await shell.forwarding.address())
    }
}

// MARK: - calendar subscription in Settings (contract section 3)

struct CalendarSettingsSection: View {
    @ObservedObject var shell: AppShell
    @State private var load: LinkLoad?
    @State private var confirmReset = false
    @State private var copied = false
    @State private var busy = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        Section {
            Button(String(localized: "Subscribe in Apple Calendar")) {
                Task { if let url = await feed().flatMap({ webcalURL(feedURL: $0) }) { openURL(url) } }
            }
            .hitTarget()
            Button(String(localized: "Subscribe in Google Calendar")) {
                Task { if let url = await feed().flatMap({ googleCalendarSubscribeURL(feedURL: $0) }) { openURL(url) } }
            }
            .hitTarget()
            Button(copied ? String(localized: "Link copied") : String(localized: "Copy calendar link")) {
                Task {
                    if let url = await feed() {
                        UIPasteboard.general.string = url
                        copied = true
                    }
                }
            }
            .hitTarget()
            Button(String(localized: "Reset calendar link"), role: .destructive) { confirmReset = true }
                .foregroundStyle(FlyColor.cancelled)
                .hitTarget()
            if busy { ProgressView() }
            if let load, let problem = problemText(load) {
                Text(problem).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.cancelled)
            }
        } header: {
            Text(String(localized: "Calendar"))
        } footer: {
            Text(String(localized: "Your tracked flights appear in your calendar and update every hour. Anyone with the link can see them, so reset it if you shared it by mistake."))
        }
        .disabled(busy)
        .confirmationDialog(String(localized: "Reset calendar link?"), isPresented: $confirmReset, titleVisibility: .visible) {
            Button(String(localized: "Reset link"), role: .destructive) { Task { await rotate() } }
            Button(String(localized: "Cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "Calendars subscribed to the old link stop updating. Subscribe again with the new one."))
        }
    }

    /// The feed URL, asked once per Settings visit.
    private func feed() async -> String? {
        if case .ready(let url) = load { return url }
        busy = true
        defer { busy = false }
        load = linkLoad(await shell.calendarFeed.feedURL())
        if case .ready(let url) = load { return url }
        return nil
    }

    private func rotate() async {
        busy = true
        defer { busy = false }
        let result = linkLoad(await shell.calendarFeed.rotate())
        if case .ready = result { copied = false }
        load = result
    }

    private func problemText(_ load: LinkLoad) -> String? {
        switch load {
        case .offline: String(localized: "You're offline. Try again when you're connected.")
        case .failed: String(localized: "Couldn't get your calendar link. Try again.")
        case .notConfigured: String(localized: "This build has no server address.")
        case .loading, .ready: nil
        }
    }
}

/// Friends and email import entries in Settings.
struct Wave2SettingsSection: View {
    @ObservedObject var shell: AppShell

    var body: some View {
        Section(String(localized: "Friends and import")) {
            Button(String(localized: "Friends")) { shell.path.append(Route.friends) }
                .hitTarget()
            Button(String(localized: "Import by email")) { shell.path.append(Route.forwarding) }
                .hitTarget()
        }
    }
}
