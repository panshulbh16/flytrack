import FlyTrackCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers
import UserNotifications

struct FlightsScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: FlightsStore?

    var body: some View {
        PaperScreen {
            Group {
                if let store {
                    flights(store)
                } else {
                    ProgressView(String(localized: "Loading…"))
                }
            }
        }
        .navigationTitle(String(localized: "My flights"))
        .toolbar {
            // Toolbar items already get the system 44 pt target, so no hitTarget() here.
            // Settings is an icon (its title stays the VoiceOver label); the text label clipped in its glass button.
            ToolbarItem(placement: .topBarLeading) {
                Button(String(localized: "Settings"), systemImage: "gearshape") { shell.path.append(Route.settings) }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(String(localized: "Add flight")) { shell.path.append(Route.add(AddFlightPrefill())) }
                    .buttonStyle(.glassProminent)
                    .foregroundStyle(FlyColor.paper)
            }
        }
        .task {
            if store == nil {
                let model = FlightsModel(flights: shell.flights, clock: shell.clock)
                store = FlightsStore(model: model)
            }
            await store?.reload(session: shell.session)
            await askForNotifications()
        }
    }

    @ViewBuilder
    private func flights(_ store: FlightsStore) -> some View {
        if shell.session == .notConfigured {
            banner(String(localized: "This build has no server address. Add SUPABASE_URL and SUPABASE_ANON_KEY to ios/Config/Secrets.xcconfig."))
        }
        if store.state.isEmpty {
            empty
        } else {
            List {
                if let next = store.state.next {
                    Section {
                        NextCard(next: next) { shell.openFlight(next.card.flightId) }
                    }
                    .listRowBackground(FlyColor.paperRaised)
                }
                if !store.state.upcoming.isEmpty {
                    Section(String(localized: "Upcoming")) {
                        ForEach(store.state.upcoming, id: \.flightId) { card in
                            FlightRow(card: card) { shell.openFlight(card.flightId) }
                        }
                    }
                    .listRowBackground(FlyColor.paperRaised)
                }
                if !store.state.past.isEmpty {
                    Section(String(localized: "Past")) {
                        ForEach(store.state.past, id: \.flightId) { card in
                            FlightRow(card: card) { shell.openFlight(card.flightId) }
                        }
                    }
                    .listRowBackground(FlyColor.paperRaised)
                }
            }
            .scrollContentBackground(.hidden)
            .refreshable { await store.refresh(session: shell.session) }
        }
    }

    private var empty: some View {
        VStack(spacing: 16) {
            Text(String(localized: "No flights yet"))
                .font(FlyFont.serif(28, relativeTo: .title))
            Text(String(localized: "Add a flight number, or import a ticket, and HeyGate will watch the gate, delays and baggage belt."))
                .font(FlyFont.sans(16, relativeTo: .body))
                .multilineTextAlignment(.center)
            Button(String(localized: "Import a ticket")) { shell.path.append(Route.importTicket) }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(FlyColor.paper)
                .hitTarget()
            Button(String(localized: "Scan boarding pass")) { shell.path.append(Route.scanBoardingPass) }
                .buttonStyle(.bordered)
                .hitTarget()
        }
        .padding(24)
    }

    private func askForNotifications() async {
        if await shell.settings.notificationPermissionAsked() { return }
        await shell.settings.setNotificationPermissionAsked(true)
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge, .timeSensitive])
    }
}

struct AddFlightScreen: View {
    @ObservedObject var shell: AppShell
    let prefill: AddFlightPrefill
    @State private var store: AddStore?

    var body: some View {
        PaperScreen {
            if let store {
                Form {
                    Section {
                        if let notice = store.state.prefillNotice {
                            Text(prefillText(notice)).font(FlyFont.serifItalic(18, relativeTo: .headline))
                        }
                        TextField(String(localized: "Flight number"), text: Binding(
                            get: { store.state.flightInput },
                            set: { store.setFlight($0) }
                        ))
                        .textInputAutocapitalization(.characters)
                        .accessibilityLabel(String(localized: "Flight number"))
                        if let error = store.state.flightError {
                            Text(error == .required ? String(localized: "Enter a flight number such as 6E 2341.") : String(localized: "That is not a flight number."))
                                .foregroundStyle(FlyColor.cancelled)
                        }
                        DatePicker(String(localized: "Date"), selection: Binding(
                            get: { store.state.date.startOfDayUTC() },
                            set: { store.setDate(FlightTimeFormat.localDate($0, tz: "UTC")) }
                        ), in: store.state.firstSelectableDate.startOfDayUTC()...store.state.lastSelectableDate.startOfDayUTC(), displayedComponents: .date)
                        TextField(String(localized: "Booking reference"), text: Binding(get: { store.state.pnrInput }, set: { store.setPnr($0) }))
                        TextField(String(localized: "Seat"), text: Binding(get: { store.state.seatInput }, set: { store.setSeat($0) }))
                        Button(String(localized: "Search")) { Task { await store.search() } }
                            .hitTarget()
                            .disabled(store.state.isSearching)
                        searchBody(store)
                    }
                    .listRowBackground(FlyColor.paperRaised)
                }
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle(String(localized: "Add flight"))
        .toolbar {
            if !prefill.isEmpty == false {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Import a ticket")) { shell.path.append(Route.importTicket) }.hitTarget()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "Scan boarding pass"), systemImage: "barcode.viewfinder") { shell.path.append(Route.scanBoardingPass) }
                        .hitTarget()
                }
            }
        }
        .task {
            if store == nil {
                let session = await shell.auth.session()
                let anonymous = if case .signedIn(_, true, _) = session { true } else { false }
                let model = AddFlightModel(
                    prefill: prefill, flights: shell.flights, clock: shell.clock, homeTimeZone: TimeZone.current.identifier,
                    backendConfigured: shell.session != .notConfigured, googleConfigured: AppConfig.isGoogleConfigured,
                    isAnonymous: anonymous, defaultAlerts: shell.settings
                )
                let wrapped = AddStore(model: model)
                store = wrapped
                await model.start()
                wrapped.pull()
            }
        }
        .onChange(of: store?.state.finishedFlightId) { _, id in
            if let id { shell.openFlight(id) }
        }
    }

    @ViewBuilder
    private func searchBody(_ store: AddStore) -> some View {
        switch store.state.search {
        case .idle: EmptyView()
        case .searching: ProgressView(String(localized: "Searching…"))
        case .results(_, let legs):
            ForEach(legs, id: \.id) { leg in
                VStack(alignment: .leading, spacing: 6) {
                    Text(leg.designator).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                    Text("\(leg.depIata) → \(leg.arrIata)").font(FlyFont.numeral(22))
                    Button(store.state.trackedFlightIds.contains(leg.id) ? String(localized: "Open") : String(localized: "Track")) {
                        Task {
                            if store.state.trackedFlightIds.contains(leg.id) { await store.open(leg.id) }
                            else { await store.track(leg.id) }
                        }
                    }
                    .hitTarget()
                    if store.state.trackNotice?.flightId == leg.id {
                        Text(trackText(store.state.trackNotice?.problem)).foregroundStyle(FlyColor.cancelled)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        case .notFound: Text(String(localized: "No flight was found on that date."))
        case .rateLimited: Text(String(localized: "You've used today's searches. Linking Google raises the limit."))
        case .providerError: Text(String(localized: "The flight service did not answer. Try again in a moment."))
        case .offline: Text(String(localized: "You're offline."))
        case .notConfigured: Text(String(localized: "Backend not configured."))
        case .rejected: Text(String(localized: "The server rejected that search."))
        }
    }
}

struct DetailScreen: View {
    @ObservedObject var shell: AppShell
    let flightId: String
    @State private var store: DetailStore?
    @State private var live: Bool? // nil = Live Activity not offered for this flight
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PaperScreen {
            if let detail = store?.state.detail {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(statusWord(detail.card.statusWord))
                            .font(FlyFont.serifItalic(32, relativeTo: .largeTitle))
                            .foregroundStyle(FlyColor.tone(detail.card.tone))
                            .accessibilityAddTraits(.isHeader)
                        Text(statusSentence(detail.card.statusLine))
                            .font(FlyFont.sans(16, relativeTo: .body))
                        endpoint(String(localized: "Departure"), detail.departure)
                        endpoint(String(localized: "Arrival"), detail.arrival)
                        if let reason = detail.delayReason {
                            Text(reason).font(FlyFont.sans(14, relativeTo: .footnote))
                        }
                        if case .teaser = detail.proInsight {
                            Text(String(localized: "Pro can see whether the inbound aircraft is running late."))
                                .font(FlyFont.serifItalic(18, relativeTo: .headline))
                        }
                        if case .details(let flight, let from, _, let landed, let late, let predicted, let reason) = detail.proInsight {
                            Text(late >= 10 ? String(localized: "Your plane looks about \(late) min late.") : String(localized: "Your inbound plane is on time."))
                                .font(FlyFont.serifItalic(20, relativeTo: .title3))
                            if let flight, let from { Text("\(flight) from \(from)") }
                            if let predicted { Text(String(localized: "Expected departure \(predicted)")) }
                            if landed { Text(String(localized: "The inbound aircraft has landed.")) }
                            if let reason { Text(reason) }
                        }
                        if let booking = detail.booking, !booking.isEmpty {
                            Text([booking.pnr, booking.seat].compactMap { $0 }.joined(separator: " · "))
                                .font(FlyFont.sans(14, relativeTo: .footnote))
                        }
                        ForEach(detail.alerts, id: \.group) { toggle in
                            Toggle(alertTitle(toggle.group), isOn: Binding(
                                get: { toggle.enabled },
                                set: { value in Task { await store?.setAlert(toggle.group, value) } }
                            ))
                            .disabled(toggle.locked)
                            .hitTarget()
                        }
                        if detail.trackingState == .tracked, let live {
                            Button(live ? String(localized: "Remove from Lock Screen") : String(localized: "Show on Lock Screen")) {
                                Task { await toggleLive(live) }
                            }
                            .hitTarget()
                        }
                        if detail.trackingState == .tracked {
                            Button(String(localized: "Stop tracking"), role: .destructive) {
                                Task {
                                    await FlightLiveActivities.stop(flightId)
                                    await store?.untrack()
                                }
                            }
                            // PaperScreen's ink foreground would otherwise hide the destructive red.
                            .foregroundStyle(FlyColor.cancelled)
                            .hitTarget()
                        }
                    }
                    .padding(20)
                }
            } else if store?.state.isLoading == false {
                Text(String(localized: "This flight is not on this phone."))
                    .padding()
            } else {
                ProgressView(String(localized: "Loading…"))
            }
        }
        .navigationTitle(store?.state.detail?.card.designator ?? String(localized: "Flight"))
        .task {
            if store == nil { store = DetailStore(model: FlightDetailScreenModel(flightId: flightId, flights: shell.flights, profiles: shell.profiles, clock: shell.clock)) }
            await store?.load()
            live = await FlightLiveActivities.offer(flightId, flights: shell.flights)
        }
        .onChange(of: store?.state.isClosed) { _, closed in if closed == true { dismiss() } }
    }

    private func toggleLive(_ running: Bool) async {
        if running {
            await FlightLiveActivities.stop(flightId)
            live = false
        } else if let flight = await shell.flights.flight(id: flightId) {
            live = FlightLiveActivities.start(flight, client: shell.stack.client, push: shell.push)
        }
    }

    private func endpoint(_ title: String, _ end: EndpointModel) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(FlyFont.sans(12, weight: .medium, relativeTo: .caption)).textCase(.uppercase).tracking(1.2)
            Text(end.code).font(FlyFont.numeral(36)).accessibilityLabel("\(title) \(end.city)")
            Text(end.city).font(FlyFont.sans(16, relativeTo: .body))
            if let time = end.time?.time { Text(time).font(FlyFont.numeral(28)).monospacedDigit() }
            if let scheduled = end.scheduled, scheduled != end.time?.time {
                Text(scheduled).strikethrough().font(FlyFont.sans(14, relativeTo: .footnote))
            }
            ForEach(end.info.indices, id: \.self) { index in
                let row = end.info[index]
                Text("\(fieldName(row.field)): \(row.value ?? String(localized: "Not announced"))")
                    .font(FlyFont.sans(15, relativeTo: .body))
                    .accessibilityLabel("\(fieldName(row.field)) \(row.value ?? String(localized: "not announced"))")
            }
        }
    }
}

struct SettingsScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: SettingsStoreBox?
    @State private var testAlertSending = false
    @State private var testAlertText: String?
    @AppStorage(Appearance.storageKey) private var appearance = Appearance.system

    var body: some View {
        PaperScreen {
            Form {
                if let store {
                    Section(String(localized: "Account")) {
                        Text(accountText(store.state.account))
                        if case .anonymous = store.state.account, store.state.googleConfigured {
                            Button(store.state.linking ? String(localized: "Linking…") : String(localized: "Link Google account")) {
                                Task { await link(store) }
                            }
                            .hitTarget()
                        }
                        if store.state.message == .googleInUse {
                            Button(String(localized: "Sign in to that Google account")) { Task { await store.signInPending() } }
                                .hitTarget()
                        }
                        if let message = store.state.message { Text(settingsMessage(message)) }
                        Button(String(localized: "Sign out")) { Task { await store.signOut() } }.hitTarget()
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    Section(String(localized: "Appearance")) {
                        Picker(String(localized: "Appearance"), selection: $appearance) {
                            Text(String(localized: "System")).tag(Appearance.system)
                            Text(String(localized: "Light")).tag(Appearance.light)
                            Text(String(localized: "Dark")).tag(Appearance.dark)
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    Section(String(localized: "Alerts for flights you add next")) {
                        ForEach(store.state.alerts, id: \.group) { toggle in
                            Toggle(alertTitle(toggle.group), isOn: Binding(
                                get: { toggle.enabled },
                                set: { value in Task { await store.setAlert(toggle.group, value) } }
                            ))
                            .disabled(toggle.locked)
                        }
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    Section(String(localized: "Ticket reading")) {
                        Toggle(String(localized: "Allow cloud reading when the phone cannot read the ticket"), isOn: Binding(
                            get: { store.state.cloudConsent },
                            set: { value in Task { await store.setConsent(value) } }
                        ))
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    Section(String(localized: "Notifications")) {
                        Text(pushText(store.state.push))
                        Text(healthText(store.state.health))
                        Button(String(localized: "Open notification settings")) {
                            if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        }
                        .hitTarget()
                        Button(testAlertSending ? String(localized: "Sending…") : String(localized: "Send a test alert")) {
                            Task {
                                testAlertSending = true
                                testAlertText = await TestAlert.send(client: shell.stack.client, push: shell.push)
                                testAlertSending = false
                            }
                        }
                        .disabled(testAlertSending)
                        .hitTarget()
                        if let testAlertText { Text(testAlertText) }
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    Section { Text(String(localized: "HeyGate \(store.state.versionName)")) }
                    .listRowBackground(FlyColor.paperRaised)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle(String(localized: "Settings"))
        .task {
            if store == nil {
                let model = SettingsModel(
                    auth: shell.auth, profiles: shell.profiles, settings: shell.settings, alerts: shell.settings,
                    health: LiveHealth(), push: shell.push, clock: shell.clock,
                    googleConfigured: AppConfig.isGoogleConfigured, versionName: AppConfig.versionName
                )
                store = SettingsStoreBox(model: model)
            }
            await store?.load()
        }
    }

    private func link(_ store: SettingsStoreBox) async {
        guard store.beginLink() else { return }
        switch await GoogleLinker.token() {
        case .success(let token):
            await store.link(token.idToken)
        case .failure(.cancelled):
            store.cancel()
        case .failure(.missingCredential):
            store.fail(missing: true)
        case .failure(.notConfigured):
            store.fail(missing: false)
        case .failure(.failed):
            store.fail(missing: false)
        }
    }
}

struct ImportScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: ImportStore?
    @State private var showFiles = false

    var body: some View {
        PaperScreen {
            VStack(alignment: .leading, spacing: 16) {
                Text(String(localized: "The ticket stays on this phone. Cloud reading sends only the extracted text, and only if you allow it."))
                    .font(FlyFont.sans(15, relativeTo: .body))
                Button(String(localized: "Choose a PDF or screenshot")) { showFiles = true }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(FlyColor.paper)
                    .hitTarget()
                if let store {
                    if store.state.busy { ProgressView(String(localized: "Reading the ticket…")) }
                    if store.state.flights.count > 1 {
                        Text(String(localized: "Several flights were found. Choose one."))
                            .font(FlyFont.serifItalic(20, relativeTo: .title3))
                    }
                    ForEach(store.state.flights.indices, id: \.self) { index in
                        let flight = store.state.flights[index]
                        Button {
                            store.select(index)
                        } label: {
                            HStack {
                                Text(flight.flight.display).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                                if let date = flight.date { Text(FlightTimeFormat.fullDate(date)) }
                                if store.state.selected == index { Image(systemName: "checkmark") }
                            }
                        }
                        .hitTarget()
                        .accessibilityAddTraits(store.state.selected == index ? .isSelected : [])
                    }
                    if store.state.phase == .choose {
                        Button(String(localized: "Use this flight")) { store.continueSelection() }
                            .hitTarget()
                    }
                    if store.state.phase == .empty {
                        Text(String(localized: "No flight number was found."))
                        Toggle(String(localized: "Allow cloud reading"), isOn: Binding(
                            get: { store.state.consent },
                            set: { value in Task { await store.setConsent(value) } }
                        ))
                        Button(String(localized: "Try cloud reading")) { Task { await store.tryCloud() } }
                            .disabled(!store.state.consent)
                            .hitTarget()
                    }
                    if let notice = store.state.notice { Text(importNotice(notice)) }
                }
                Spacer()
            }
            .padding(20)
        }
        .navigationTitle(String(localized: "Import a ticket"))
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.pdf, .image, .plainText], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result {
                Task { await store?.picked(urls.map(\.absoluteString), mime: nil) }
            }
        }
        .task {
            if store == nil {
                let model = ImportTicketModel(shared: shell.shared, extractor: shell.extractor, cloud: shell.cloud, settings: shell.settings, today: shell.clock.today(tz: TimeZone.current.identifier))
                store = ImportStore(model: model)
            }
            await store?.start()
        }
        .onChange(of: store?.state.parsed?.flight) { _, flight in
            guard let parsed = store?.state.parsed, flight != nil || parsed.date != nil else { return }
            shell.path.append(Route.add(AddFlightPrefill(flight: parsed.flight, date: parsed.date, pnr: parsed.pnr, source: TrackSource.fromWire(parsed.source))))
        }
    }
}

// MARK: - rows

private struct NextCard: View {
    var next: NextFlight
    var open: () -> Void
    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 6) {
                Text(String(localized: "Next takeoff").uppercased())
                    .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2))
                    .tracking(1.2)
                    .foregroundStyle(FlyColor.accent)
                Text(next.card.designator).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                Text("\(next.card.depCode) → \(next.card.arrCode)").font(FlyFont.numeral(28))
                Text(countdownText(next.countdown)).font(FlyFont.serifItalic(20, relativeTo: .title3))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 8)
        }
        .hitTarget()
        .accessibilityLabel("\(next.card.designator), \(next.card.depCity) to \(next.card.arrCity), \(countdownText(next.countdown))")
    }
}

private struct FlightRow: View {
    var card: FlightCard
    var open: () -> Void
    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                Image(systemName: "airplane")
                    .frame(width: 44, height: 44)
                    .overlay(Circle().stroke(FlyColor.accentLine, lineWidth: 1))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(card.depCode) → \(card.arrCode)").font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                    Text(String(localized: "\(card.depCity) to \(card.arrCity)")).font(FlyFont.sans(14, relativeTo: .subheadline))
                    Text(statusWord(card.statusWord)).font(FlyFont.serifItalic(18, relativeTo: .headline)).foregroundStyle(FlyColor.tone(card.tone))
                }
                Spacer()
                if let time = card.departure?.time {
                    Text(time).font(FlyFont.numeral(22))
                }
            }
        }
        .hitTarget()
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(card.designator), \(card.depCity) to \(card.arrCity), \(statusWord(card.statusWord)), \(statusSentence(card.statusLine))")
    }
}

private func banner(_ text: String) -> some View {
    Text(text).font(FlyFont.sans(14, relativeTo: .footnote)).padding(.horizontal, 16).padding(.top, 8)
}

// MARK: - stores

@MainActor
@Observable
final class FlightsStore {
    var state: FlightsState
    let model: FlightsModel
    init(model: FlightsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func reload(session: SessionState) async { await model.reload(session: session); state = model.state }
    func refresh(session: SessionState) async { await model.refresh(session: session); state = model.state }
}

@MainActor
@Observable
final class AddStore {
    var state: AddFlightState
    let model: AddFlightModel
    init(model: AddFlightModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func pull() { state = model.state }
    func setFlight(_ text: String) { model.setFlightInput(text); state = model.state }
    func setDate(_ date: LocalDate) { model.setDate(date); state = model.state }
    func setPnr(_ text: String) { model.setPnr(text); state = model.state }
    func setSeat(_ text: String) { model.setSeat(text); state = model.state }
    func search() async { await model.search(); state = model.state }
    func track(_ id: String) async { await model.track(flightId: id); state = model.state }
    func open(_ id: String) async { await model.openTracked(flightId: id); state = model.state }
}

@MainActor
@Observable
final class DetailStore {
    var state: FlightDetailState
    let model: FlightDetailScreenModel
    init(model: FlightDetailScreenModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load(); state = model.state }
    func untrack() async { await model.untrack(); state = model.state }
    func setAlert(_ group: AlertGroup, _ enabled: Bool) async { await model.setAlert(group, enabled: enabled); state = model.state }
}

@MainActor
@Observable
final class SettingsStoreBox {
    var state: SettingsState
    let model: SettingsModel
    init(model: SettingsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load(); state = model.state }
    func beginLink() -> Bool { let ok = model.beginGoogleLink(); state = model.state; return ok }
    func cancel() { model.cancelGoogleLink(); state = model.state }
    func fail(missing: Bool) { model.googleLinkFailed(missingCredential: missing); state = model.state }
    func link(_ token: String) async { await model.linkGoogle(idToken: token); state = model.state }
    func signInPending() async { await model.signInWithPendingGoogle(); state = model.state }
    func signOut() async { await model.signOut(); state = model.state }
    func setAlert(_ group: AlertGroup, _ enabled: Bool) async { await model.setAlert(group, enabled: enabled); state = model.state }
    func setConsent(_ granted: Bool) async { await model.setCloudConsent(granted); state = model.state }
}

@MainActor
@Observable
final class ImportStore {
    var state: ImportState
    let model: ImportTicketModel
    init(model: ImportTicketModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func start() async { await model.start(); state = model.state }
    func picked(_ uris: [String], mime: String?) async { await model.picked(uris: uris, mimeType: mime); state = model.state }
    func select(_ index: Int) { model.select(index); state = model.state }
    func setConsent(_ granted: Bool) async { await model.setConsent(granted); state = model.state }
    func tryCloud() async { await model.tryCloud(); state = model.state }
    func continueSelection() { model.continueWithSelection(); state = model.state }
}

// MARK: - copy

private func statusWord(_ word: FlightStatusWord) -> String {
    switch word {
    case .scheduleOnly: String(localized: "Timetable only")
    case .scheduled: String(localized: "Scheduled")
    case .onTime: String(localized: "On time")
    case .delayed: String(localized: "Delayed")
    case .early: String(localized: "Early")
    case .checkInOpen: String(localized: "Check-in open")
    case .boarding: String(localized: "Boarding")
    case .gateClosed: String(localized: "Gate closed")
    case .departed: String(localized: "Departed")
    case .inTheAir: String(localized: "In the air")
    case .landingSoon: String(localized: "Landing soon")
    case .landed: String(localized: "Landed")
    case .arrived: String(localized: "Arrived")
    case .cancelled: String(localized: "Cancelled")
    case .diverted: String(localized: "Diverted")
    }
}

private func statusSentence(_ line: StatusLine) -> String {
    switch line {
    case .scheduleOnly: String(localized: "No live updates yet. These are the timetable times.")
    case .beforeDeparture(.late(let minutes)): String(localized: "Running \(minutes) min late")
    case .beforeDeparture(.early(let minutes)): String(localized: "Leaving \(minutes) min early")
    case .beforeDeparture: String(localized: "Leaving on time")
    case .checkInOpen: String(localized: "Check-in is open")
    case .boarding(let gate?, _): String(localized: "Boarding now at gate \(gate)")
    case .boarding: String(localized: "Boarding now")
    case .gateClosed: String(localized: "The gate has closed.")
    case .inAir(.late(let minutes), _): String(localized: "Landing about \(minutes) min late")
    case .inAir: String(localized: "In the air")
    case .landed(_, true): String(localized: "At the gate")
    case .landed: String(localized: "Landed")
    case .cancelled: String(localized: "This flight was cancelled")
    case .diverted: String(localized: "This flight was diverted")
    }
}

private func countdownText(_ countdown: Countdown) -> String {
    switch countdown {
    case .departs(let time): String(localized: "Takes off \(relative(time))")
    case .departsOn(let day): String(localized: "Takes off \(dayText(day))")
    case .lands(let time): String(localized: "Lands \(relative(time))")
    }
}

private func relative(_ time: RelativeTime) -> String {
    switch time.unit {
    case .now: String(localized: "now")
    case .minutes: time.isPast ? String(localized: "\(time.amount) min ago") : String(localized: "in \(time.amount) min")
    case .hours: time.isPast ? String(localized: "\(time.amount) hr ago") : String(localized: "in \(time.amount) hr")
    case .days: time.isPast ? String(localized: "\(time.amount) days ago") : String(localized: "in \(time.amount) days")
    }
}

private func dayText(_ day: RelativeDay) -> String {
    switch day {
    case .today: String(localized: "today")
    case .tomorrow: String(localized: "tomorrow")
    case .yesterday: String(localized: "yesterday")
    case .inDays(let days): String(localized: "in \(days) days")
    case .daysAgo(let days): String(localized: "\(days) days ago")
    }
}

private func fieldName(_ field: FlightField) -> String {
    switch field {
    case .depTerminal, .arrTerminal: String(localized: "Terminal")
    case .depGate, .arrGate: String(localized: "Gate")
    case .checkInDesk: String(localized: "Check-in")
    case .baggageBelt: String(localized: "Belt")
    }
}

private func alertTitle(_ group: AlertGroup) -> String {
    switch group {
    case .delays: String(localized: "Delays and schedule changes")
    case .gate: String(localized: "Gate and terminal")
    case .boarding: String(localized: "Boarding")
    case .takeoffAndLanding: String(localized: "Take-off and landing")
    case .baggage: String(localized: "Baggage belt")
    case .disruption: String(localized: "Cancellation and diversion")
    case .inboundAircraft: String(localized: "Inbound aircraft (Pro)")
    }
}

private func prefillText(_ notice: PrefillNotice) -> String {
    switch notice {
    case .checkDetails: String(localized: "Check these details from the ticket.")
    case .flightUnreadable: String(localized: "The flight number could not be read.")
    case .dateUnreadable: String(localized: "The date could not be read.")
    case .dateOutOfRange: String(localized: "That ticket date is outside the dates we can search.")
    }
}

private func trackText(_ problem: TrackProblem?) -> String {
    switch problem {
    case .limitReached: String(localized: "You've reached the number of flights your plan can track.")
    case .offline: String(localized: "You're offline.")
    case .flightGone: String(localized: "That flight is no longer available. Search again.")
    case .notConfigured: String(localized: "Backend not configured.")
    case .invalidDetails, .detailsNotSaved: String(localized: "The booking reference or seat could not be saved.")
    case .failed: String(localized: "That didn't work. Try again.")
    case nil: ""
    }
}

private func accountText(_ account: AccountKind) -> String {
    switch account {
    case .loading: String(localized: "Loading…")
    case .notConfigured: String(localized: "Backend not configured.")
    case .signedOut: String(localized: "Signed out")
    case .anonymous: String(localized: "Anonymous account on this phone")
    case .google(let email): email ?? String(localized: "Google account")
    }
}

private func settingsMessage(_ message: SettingsMessage) -> String {
    switch message {
    case .noGoogleCredential: String(localized: "No Google account was chosen.")
    case .googleNotConfigured: String(localized: "Google sign-in is not configured in this build.")
    case .googleInUse: String(localized: "That Google account already belongs to another HeyGate user.")
    case .linked: String(localized: "Google account linked.")
    case .offline: String(localized: "You're offline.")
    case .failed: String(localized: "That didn't work. Try again.")
    case .signedOut: String(localized: "Signed out. A new anonymous account is ready.")
    }
}

private func pushText(_ push: PushAvailability) -> String {
    switch push {
    case .checking: String(localized: "Checking push…")
    case .notConfigured: String(localized: "Push is not configured. Add GoogleService-Info.plist from the Firebase project. The app still runs.")
    case .ready: String(localized: "Push is configured.")
    }
}

private func healthText(_ health: NotificationHealth) -> String {
    switch health.authorization {
    case .notDetermined: return String(localized: "Notifications have not been requested yet.")
    case .denied: return String(localized: "Notifications are off.")
    case .provisional: return String(localized: "Notifications are provisional.")
    case .authorized:
        if health.timeSensitiveAllowed {
            return String(localized: "Alerts are allowed, including time-sensitive gate and delay alerts.")
        }
        return String(localized: "Alerts are allowed. Turn on Time-Sensitive Notifications so a gate change can break through Focus.")
    }
}

private func importNotice(_ notice: ImportNotice) -> String {
    switch notice {
    case .truncated: String(localized: "Only the first part of the ticket was read.")
    case .cloudFailed: String(localized: "Cloud reading failed.")
    case .rateLimited: String(localized: "You've used today's cloud readings.")
    case .offline: String(localized: "You're offline.")
    case .notConfigured: String(localized: "Cloud reading is not configured.")
    case .nothingFound: String(localized: "No flight number was found.")
    }
}
