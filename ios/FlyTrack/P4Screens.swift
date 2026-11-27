import FlyTrackCore
import MapKit
import SwiftUI
import UIKit

// P4 screens (docs/P4_CONTRACTS.md): radar (section 1), TripIt in Settings (section 2), the paywall's Gift Pro tab,
// "Redeem a gift code" and "My gifts" (section 3), and the terminal map (section 4). Views only hold the FlyTrackCore
// models; text is in Localizable.xcstrings (English keys).

// MARK: - stores

@MainActor
@Observable
final class RadarStore {
    var state: RadarState
    let model: RadarModel
    init(model: RadarModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }

    /// True while the screen should keep refreshing (reads `state` so SwiftUI re-evaluates it).
    var ticking: Bool {
        _ = state
        return model.nextRefreshDelay != nil
    }
}

@MainActor
@Observable
final class TripItStore {
    var state: TripItState
    let model: TripItModel
    init(model: TripItModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class GiftTabStore {
    var state: GiftTabState
    let model: GiftPurchaseModel
    init(model: GiftPurchaseModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class RedeemGiftStore {
    var state: RedeemGiftState
    let model: RedeemGiftModel
    init(model: RedeemGiftModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class MyGiftsStore {
    var state: MyGiftsState
    let model: MyGiftsModel
    init(model: MyGiftsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class TerminalMapStore {
    var state: TerminalMapState
    let model: TerminalMapModel
    init(model: TerminalMapModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

// MARK: - shared bits

/// A titled card for empty, coming-soon and upsell states.
private struct P4Notice<Actions: View>: View {
    let symbol: String
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 28))
                .foregroundStyle(FlyColor.accentLine)
                .accessibilityHidden(true)
            Text(title).font(FlyFont.serifItalic(24, relativeTo: .title2)).accessibilityAddTraits(.isHeader)
            Text(message).font(FlyFont.sans(15, relativeTo: .body)).foregroundStyle(FlyColor.ink)
            actions()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 12))
    }
}

private extension P4Notice where Actions == EmptyView {
    init(symbol: String, title: String, message: String) {
        self.init(symbol: symbol, title: title, message: message) { EmptyView() }
    }
}

private func coordinate(_ point: GeoPoint) -> CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon)
}

private struct P4ActivitySheet: UIViewControllerRepresentable {
    let text: String
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private struct P4ShareText: Identifiable {
    let text: String
    var id: String { text }
}

// MARK: - radar (section 1)

struct RadarScreen: View {
    @ObservedObject var shell: AppShell
    let target: RadarTarget
    @State private var store: RadarStore?
    @State private var camera: MapCameraPosition = .automatic
    @State private var cameraCenter: GeoPoint?
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL

    var body: some View {
        PaperScreen {
            if let store {
                content(store)
            } else {
                ProgressView(String(localized: "Loading…")).padding(.top, 40)
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if store == nil {
                let model = RadarModel(target: target, fetcher: shell.p4.radar, location: shell.p4.location, clock: shell.clock)
                let created = RadarStore(model: model)
                store = created
                await model.start()
            }
        }
        // P4 section 1: refresh every 10 s, only while the screen is visible and the app is in the foreground.
        .task(id: LoopKey(active: scenePhase == .active, ticking: store?.ticking ?? false)) {
            guard scenePhase == .active, let store else { return }
            while !Task.isCancelled, let delay = store.model.nextRefreshDelay {
                try? await Task.sleep(for: .seconds(delay))
                if Task.isCancelled { break }
                await store.model.refresh()
            }
        }
        .onChange(of: store?.state.center) { _, center in
            guard let center, cameraCenter != center else { return }
            cameraCenter = center
            let meters = (store?.state.radiusKm ?? RadarContract.proximityRadiusKm) * 2_300
            camera = .region(MKCoordinateRegion(center: coordinate(center), latitudinalMeters: meters, longitudinalMeters: meters))
        }
    }

    private struct LoopKey: Equatable {
        var active: Bool
        var ticking: Bool
    }

    private var title: String {
        switch target {
        case .airport(let iata): String(localized: "Ground radar · \(iata)")
        case .nearMe: String(localized: "Planes near me")
        case .point: String(localized: "Planes nearby")
        }
    }

    @ViewBuilder
    private func content(_ store: RadarStore) -> some View {
        let state = store.state
        switch state.phase {
        case .ready:
            VStack(spacing: 0) {
                radarMap(state)
                    .frame(maxWidth: .infinity)
                    .frame(height: 340)
                radarList(state)
            }
        case .needsLocation:
            ScrollView {
                P4Notice(
                    symbol: "location.circle",
                    title: String(localized: "Planes near you"),
                    message: String(localized: "See the planes within 75 km of you. HeyGate uses your approximate location, only while this screen is open, and never stores it.")
                ) {
                    Button(String(localized: "Show planes near me")) { Task { await store.model.useMyLocation() } }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(FlyColor.paper)
                        .hitTarget()
                }
                .padding(20)
            }
        case .locating:
            ProgressView(String(localized: "Finding where you are…")).padding(.top, 40)
        case .loading:
            ProgressView(String(localized: "Looking for planes…")).padding(.top, 40)
        case .comingSoon:
            notice(P4Notice(
                symbol: "dot.radiowaves.left.and.right",
                title: String(localized: "Radar is coming soon"),
                message: String(localized: "Live plane positions need a licensed data feed. We're setting it up, so check back soon.")
            ))
        case .proRequired:
            ScrollView {
                if shell.p3.crewMode {
                    P4Notice(symbol: "airplane", title: String(localized: "Radar is part of Pro"),
                             message: String(localized: "Live planes around airports and near you come with HeyGate Pro."))
                        .padding(20)
                } else {
                    P4Notice(symbol: "airplane", title: String(localized: "Radar is part of Pro"),
                             message: String(localized: "See the planes on the ground at your airport and in the sky near you, updated every 10 seconds.")) {
                        Button(String(localized: "See Pro plans")) { shell.path.append(Route.paywall) }
                            .buttonStyle(.borderedProminent)
                            .foregroundStyle(FlyColor.paper)
                            .hitTarget()
                    }
                    .padding(20)
                }
            }
        case .locationDenied:
            ScrollView {
                P4Notice(
                    symbol: "location.slash",
                    title: String(localized: "Location is off for HeyGate"),
                    message: String(localized: "To see planes near you, allow HeyGate to use your location while the app is open. Approximate location is enough.")
                ) {
                    Button(String(localized: "Open Settings")) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    .hitTarget()
                }
                .padding(20)
            }
        case .locationUnavailable:
            ScrollView {
                P4Notice(
                    symbol: "location.magnifyingglass",
                    title: String(localized: "Couldn't find where you are"),
                    message: String(localized: "Your phone didn't give a location. Check that Location Services are on and try again.")
                ) {
                    Button(String(localized: "Try again")) { Task { await store.model.useMyLocation() } }
                        .hitTarget()
                }
                .padding(20)
            }
        case .unknownAirport:
            notice(P4Notice(symbol: "mappin.slash", title: String(localized: "Airport not found"),
                            message: String(localized: "We don't have this airport's position yet.")))
        case .offline:
            notice(P4Notice(symbol: "wifi.slash", title: String(localized: "You're offline"),
                            message: String(localized: "Radar needs a connection. It comes back by itself when you're online.")))
        case .failed:
            notice(P4Notice(symbol: "exclamationmark.triangle", title: String(localized: "Radar didn't load"),
                            message: String(localized: "We'll try again in a moment.")))
        case .notConfigured:
            notice(P4Notice(symbol: "gearshape", title: String(localized: "Backend not configured."),
                            message: String(localized: "This build has no server keys.")))
        }
    }

    private func notice(_ view: some View) -> some View {
        ScrollView { view.padding(20) }
    }

    private func radarMap(_ state: RadarState) -> some View {
        Map(position: $camera, interactionModes: [.pan, .zoom]) {
            if let center = state.center {
                MapCircle(center: coordinate(center), radius: state.radiusKm * 1_000)
                    .foregroundStyle(FlyColor.primary.opacity(0.06))
                    .stroke(FlyColor.outline, lineWidth: 1)
            }
            ForEach(state.rows) { row in
                Annotation(row.label, coordinate: coordinate(row.position), anchor: .center) {
                    Image(systemName: "airplane")
                        .font(.system(size: row.onGround ? 14 : 18, weight: .semibold))
                        // SF "airplane" points east; tracks are degrees from north.
                        .rotationEffect(.degrees(row.rotationDeg - 90))
                        .foregroundStyle(row.flight != nil ? FlyColor.accentLine : FlyColor.mapPlane)
                        .padding(4)
                        .background(FlyColor.paper.opacity(0.85), in: .circle)
                        .accessibilityLabel(rowAccessibility(row))
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll, showsTraffic: false))
        .overlay(alignment: .top) {
            if let notice = state.notice {
                Text(radarNoticeText(notice))
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(FlyColor.paperRaised, in: .capsule)
                    .padding(.top, 8)
            }
        }
    }

    private func radarList(_ state: RadarState) -> some View {
        List {
            Section {
                if state.rows.isEmpty {
                    Text(state.target.mode == .ground
                         ? String(localized: "No planes on the ground here right now.")
                         : String(localized: "No planes in the sky nearby right now."))
                        .foregroundStyle(FlyColor.muted)
                }
                ForEach(state.rows) { row in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.label).font(FlyFont.sans(16, weight: .medium, relativeTo: .headline))
                            if let callsign = row.callsign {
                                Text(callsign).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                            }
                            Text(rowDetail(row)).font(FlyFont.sans(14, relativeTo: .subheadline))
                        }
                        Spacer()
                        if let flight = row.flight {
                            Button(String(localized: "Track")) { shell.path.append(Route.add(AddFlightPrefill(flight: flight))) }
                                .accessibilityLabel(String(localized: "Track \(flight)"))
                                .hitTarget()
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
            } footer: {
                Text(radarFooter(state)).font(FlyFont.sans(12, relativeTo: .caption)).foregroundStyle(FlyColor.muted)
            }
            .listRowBackground(FlyColor.paperRaised)
        }
        .scrollContentBackground(.hidden)
    }

    private func rowDetail(_ row: RadarRow) -> String {
        let distance = row.distanceKm < 10
            ? row.distanceKm.formatted(.number.precision(.fractionLength(1)))
            : row.distanceKm.formatted(.number.precision(.fractionLength(0)))
        if row.onGround {
            return String(localized: "On the ground · \(row.groundSpeedKts) kt · \(distance) km away")
        }
        return String(localized: "\(row.altitudeFt.formatted()) ft · \(row.groundSpeedKts) kt · \(distance) km away")
    }

    private func rowAccessibility(_ row: RadarRow) -> String {
        "\(row.label), \(rowDetail(row))"
    }

    private func radarFooter(_ state: RadarState) -> String {
        let time = (state.asOf ?? Date()).formatted(date: .omitted, time: .standard)
        return String(localized: "Updated \(time). Refreshes every 10 seconds while this screen is open.")
    }

    private func radarNoticeText(_ notice: RadarNotice) -> String {
        switch notice {
        case .offline: String(localized: "Offline. Showing the last planes seen.")
        case .rateLimited: String(localized: "Pausing updates for a minute.")
        case .feedError: String(localized: "Live data paused. Trying again…")
        }
    }
}

// MARK: - airport and flight entry points (sections 1 and 4)

/// Under the departure and arrival blocks of the detail screen: "Terminal map" and "Ground radar" for each airport,
/// and "Planes near this flight" while it is in the air. Hidden for crew without Pro (crew mode hides upsells).
struct P4AirportLinksView: View {
    @ObservedObject var shell: AppShell
    let flightId: String
    let revision: Date?
    @State private var flight: Flight?

    var body: some View {
        Group {
            if let flight, shell.pro.state.isPro || !shell.p3.crewMode {
                VStack(alignment: .leading, spacing: 4) {
                    links(iata: flight.depIata, request: .departure(flight))
                    if flight.arrIata != flight.depIata {
                        links(iata: flight.arrIata, request: .arrival(flight))
                    }
                    if let position = flight.position(now: shell.clock.now()), position.phase == .airborne {
                        Button(String(localized: "Planes near this flight"), systemImage: "dot.radiowaves.left.and.right") {
                            shell.path.append(Route.radar(.point(position.point)))
                        }
                        .hitTarget()
                    }
                }
                .font(FlyFont.sans(15, relativeTo: .body))
            }
        }
        .task(id: revision) { flight = await shell.flights.flight(id: flightId) }
    }

    @ViewBuilder
    private func links(iata: String, request: TerminalMapRequest) -> some View {
        if BundledTerminalMaps.hasMap(iata) {
            Button(String(localized: "Terminal map · \(iata)"), systemImage: "map") {
                shell.path.append(Route.terminalMap(request))
            }
            .hitTarget()
        }
        Button(String(localized: "Ground radar · \(iata)"), systemImage: "airplane.circle") {
            shell.path.append(Route.radar(.airport(iata)))
        }
        .hitTarget()
    }
}

// MARK: - Settings (sections 1–3)

/// Settings: "Planes near me", TripIt, "Redeem a gift code", "My gifts", "Gift Pro".
struct P4SettingsSection: View {
    @ObservedObject var shell: AppShell

    var body: some View {
        Section {
            Button(String(localized: "Planes near me"), systemImage: "dot.radiowaves.left.and.right") {
                shell.path.append(Route.radar(.nearMe))
            }
            .hitTarget()
            TripItRows(shell: shell)
        } header: {
            Text(String(localized: "Radar and TripIt"))
        }
        .listRowBackground(FlyColor.paperRaised)
        Section {
            Button(String(localized: "Redeem a gift code"), systemImage: "gift") { shell.path.append(Route.redeemGift(nil)) }
                .hitTarget()
            Button(String(localized: "My gifts"), systemImage: "list.bullet.rectangle") { shell.path.append(Route.myGifts) }
                .hitTarget()
            if !shell.p3.crewMode {
                Button(String(localized: "Gift Pro to someone"), systemImage: "heart") { shell.path.append(Route.giftPro) }
                    .hitTarget()
            }
        } header: {
            Text(String(localized: "Gifts"))
        }
        .listRowBackground(FlyColor.paperRaised)
    }
}

/// P4 section 2: Connect TripIt (ASWebAuthenticationSession, callback scheme `heygate`), status, Sync now, Disconnect.
private struct TripItRows: View {
    @ObservedObject var shell: AppShell
    @State private var store: TripItStore?
    @State private var confirmDisconnect = false

    var body: some View {
        Group {
            if let store { rows(store) } else { ProgressView() }
        }
        .task {
            if store == nil {
                let pro = shell.pro
                let model = TripItModel(
                    repository: shell.p4.tripIt, authorizer: shell.p4.tripItAuthorizer,
                    isPro: { await pro.reload(); return pro.state.isPro }
                )
                store = TripItStore(model: model)
                await model.load()
            }
            await consumePendingCallback()
        }
        .onChange(of: shell.p4.pendingTripItCallback) { _, _ in
            Task { await consumePendingCallback() }
        }
        .confirmationDialog(String(localized: "Disconnect TripIt?"), isPresented: $confirmDisconnect, titleVisibility: .visible) {
            Button(String(localized: "Disconnect"), role: .destructive) { Task { await store?.model.disconnect() } }
        } message: {
            Text(String(localized: "Flights already added stay in your list. New TripIt trips won't be added any more."))
        }
    }

    private func consumePendingCallback() async {
        guard let url = shell.p4.pendingTripItCallback, let store else { return }
        shell.p4.pendingTripItCallback = nil
        await store.model.handle(url)
    }

    @ViewBuilder
    private func rows(_ store: TripItStore) -> some View {
        let state = store.state
        switch state.phase {
        case .loading:
            ProgressView()
        case .unavailable:
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "Connect TripIt")).foregroundStyle(FlyColor.muted)
                Text(String(localized: "TripIt sync is coming soon."))
                    .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
        case .proRequired:
            if !shell.p3.crewMode {
                Button {
                    shell.path.append(Route.paywall)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "Connect TripIt (Pro)"))
                        Text(String(localized: "Flights in your TripIt trips are added to HeyGate by themselves."))
                            .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                    }
                }
                .hitTarget()
            }
        case .disconnected, .unknown:
            Button {
                Task { await store.model.connect() }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(String(localized: "Connect TripIt"))
                        if state.busy { ProgressView() }
                    }
                    Text(String(localized: "Flights in your TripIt trips are added to HeyGate by themselves, every hour."))
                        .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                }
            }
            .disabled(state.busy)
            .hitTarget()
        case .connected:
            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "Connected to TripIt"))
                Text(lastSyncText(state.status))
                    .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
            if state.needsReconnect {
                Text(String(localized: "TripIt access was removed. Connect again to keep your trips in sync."))
                    .font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.cancelled)
                Button(String(localized: "Connect again")) { Task { await store.model.connect() } }
                    .disabled(state.busy)
                    .hitTarget()
            } else {
                Button {
                    Task { await store.model.syncNow() }
                } label: {
                    HStack {
                        Text(state.syncing ? String(localized: "Syncing…") : String(localized: "Sync now"))
                        if state.syncing { ProgressView() }
                    }
                }
                .disabled(state.busy)
                .hitTarget()
            }
            Button(String(localized: "Disconnect TripIt"), role: .destructive) { confirmDisconnect = true }
                .disabled(state.busy)
                .hitTarget()
        }
        if let message = state.message {
            Text(tripItMessageText(message))
                .font(FlyFont.sans(14, relativeTo: .subheadline))
                .foregroundStyle(isTripItProblem(message) ? FlyColor.cancelled : FlyColor.onTime)
        }
    }

    private func lastSyncText(_ status: TripItStatus) -> String {
        guard let at = status.lastSyncAt else { return String(localized: "First sync running…") }
        return String(localized: "Last synced \(at.formatted(.relative(presentation: .named)))")
    }

    private func isTripItProblem(_ message: TripItMessage) -> Bool {
        switch message {
        case .connected, .synced, .disconnected: false
        default: true
        }
    }

    private func tripItMessageText(_ message: TripItMessage) -> String {
        switch message {
        case .connected: String(localized: "TripIt is connected. Your trips are being added.")
        case .synced(let summary):
            summary.added == 0
                ? String(localized: "Up to date. No new flights in TripIt.")
                : String(localized: "Added \(summary.added) flights from TripIt.")
        case .disconnected: String(localized: "TripIt is disconnected.")
        case .denied: String(localized: "TripIt wasn't connected because access was not allowed.")
        case .expired: String(localized: "That took too long. Please connect again.")
        case .revoked: String(localized: "TripIt access was removed. Connect again to keep your trips in sync.")
        case .tripitError: String(localized: "TripIt isn't answering right now. Please try again later.")
        case .proRequired: String(localized: "TripIt sync is part of Pro. You can still disconnect TripIt.")
        case .offline: String(localized: "You're offline. Connect and try again.")
        case .failed: String(localized: "That didn't work. Try again.")
        }
    }
}

// MARK: - Gift Pro tab (section 3)

enum PaywallTab: Hashable {
    case forMe, gift
}

/// The paywall's "Gift Pro" tab: buy `heygate_gift_year` or `heygate_gift_trip`, then show the code with Share /
/// WhatsApp. Buying a gift never changes the buyer's own plan.
struct GiftProTab: View {
    @ObservedObject var shell: AppShell
    @State private var store: GiftTabStore?
    @State private var shareText: P4ShareText?
    @State private var copied = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(String(localized: "Give someone HeyGate Pro"))
                .font(FlyFont.serifItalic(22, relativeTo: .title3))
                .accessibilityAddTraits(.isHeader)
            Text(String(localized: "Pay once and send them a code. A gift never renews and doesn't change your own plan."))
                .font(FlyFont.sans(15, relativeTo: .body))
            if let store { content(store) } else { ProgressView() }
            HStack(spacing: 16) {
                Button(String(localized: "My gifts")) { shell.path.append(Route.myGifts) }
                Button(String(localized: "Redeem a code")) { shell.path.append(Route.redeemGift(nil)) }
            }
            .font(FlyFont.sans(15, relativeTo: .body))
            .hitTarget()
        }
        .task {
            if store == nil {
                let pro = shell.pro.service
                store = GiftTabStore(model: GiftPurchaseModel(
                    store: shell.p4.giftStore, gifts: shell.p4.gifts, identify: { await pro.identifyCurrentUser() }
                ))
            }
            await store?.model.load()
        }
        .sheet(item: $shareText) { item in P4ActivitySheet(text: item.text) }
    }

    @ViewBuilder
    private func content(_ store: GiftTabStore) -> some View {
        let state = store.state
        if let gift = state.newGift {
            GiftCodeCard(gift: gift, copied: copied) { action in
                handle(action, gift: gift)
            }
        }
        switch state.phase {
        case .loading:
            ProgressView()
        case .comingSoon:
            P4Notice(symbol: "gift", title: String(localized: "Gifts coming soon"),
                     message: String(localized: "You can't buy a gift in this version yet."))
        case .offline, .loadFailed:
            P4Notice(
                symbol: "wifi.slash", title: String(localized: "Couldn't load the gifts"),
                message: state.phase == .offline
                    ? String(localized: "You're offline. Connect and try again.")
                    : String(localized: "The App Store didn't answer. Please try again in a moment.")
            ) {
                Button(String(localized: "Try again")) { Task { await store.model.load() } }.hitTarget()
            }
        case .ready:
            ForEach(state.options, id: \.product) { option in
                GiftOptionRowView(option: option, selected: state.selected == option.product) { store.model.select(option.product) }
            }
            Button {
                Task { await store.model.purchase() }
            } label: {
                HStack {
                    if state.busy { ProgressView().tint(FlyColor.paper) }
                    Text(state.busy ? String(localized: "Getting your code…") : String(localized: "Buy gift"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(FlyColor.paper)
            .disabled(state.busy || state.selected == nil)
            .hitTarget()
        }
        if let message = state.message {
            Text(giftPurchaseMessageText(message))
                .font(FlyFont.sans(15, relativeTo: .body))
                .foregroundStyle(message == .codeDelayed || message == .pending ? FlyColor.ink : FlyColor.cancelled)
        }
    }

    private func handle(_ action: GiftCodeAction, gift: Gift) {
        let text = giftShareMessage(gift)
        switch action {
        case .whatsApp:
            if let url = whatsAppSendURL(text: text), UIApplication.shared.canOpenURL(url) { openURL(url) } else { shareText = P4ShareText(text: text) }
        case .share:
            shareText = P4ShareText(text: text)
        case .copy:
            UIPasteboard.general.string = gift.code
            copied = true
        }
    }
}

enum GiftCodeAction {
    case whatsApp, share, copy
}

/// The code just bought: big, monospaced, with WhatsApp, Share and Copy.
struct GiftCodeCard: View {
    let gift: Gift
    var copied: Bool
    let act: (GiftCodeAction) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(String(localized: "Your gift code")).font(FlyFont.sans(12, weight: .medium, relativeTo: .caption)).textCase(.uppercase).tracking(1.2)
            Text(gift.code)
                .font(.system(size: 28, weight: .semibold, design: .monospaced))
                .textSelection(.enabled)
                .accessibilityLabel(gift.code.map(String.init).joined(separator: " "))
            Text(giftDaysText(gift.days)).font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.muted)
            HStack(spacing: 12) {
                Button(String(localized: "WhatsApp")) { act(.whatsApp) }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(FlyColor.paper)
                Button(String(localized: "Share…")) { act(.share) }
                    .buttonStyle(.bordered)
                Button(copied ? String(localized: "Copied") : String(localized: "Copy")) { act(.copy) }
                    .buttonStyle(.bordered)
            }
            .hitTarget()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(FlyColor.accentLine, lineWidth: 1.5))
    }
}

private struct GiftOptionRowView: View {
    let option: GiftOptionRow
    let selected: Bool
    let choose: () -> Void

    var body: some View {
        Button(action: choose) {
            HStack(spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? FlyColor.primary : FlyColor.muted)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(giftProductTitle(option.product)).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                    Text(String(localized: "\(option.price) once · does not renew")).font(FlyFont.sans(14, relativeTo: .subheadline))
                }
                Spacer()
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(FlyColor.paperRaised, in: .rect(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? FlyColor.accentLine : .clear, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
        .hitTarget()
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

func giftProductTitle(_ product: GiftProduct?) -> String {
    switch product {
    case .year?: String(localized: "1 year of Pro")
    case .trip?: String(localized: "Pro for a trip (7 days)")
    case nil: String(localized: "HeyGate Pro gift")
    }
}

func giftDaysText(_ days: Int) -> String {
    String(localized: "Pro for \(days) days. Share it with the person it's for.")
}

/// Hinglish line, then the English line, then how to redeem (bilingual like the flight share text).
func giftShareMessage(_ gift: Gift) -> String {
    shareMessage(lines: [
        String(localized: "Maine aapko HeyGate Pro gift kiya hai! Code: \(gift.code)"),
        String(localized: "I've gifted you HeyGate Pro! Code: \(gift.code)"),
        String(localized: "Redeem it in HeyGate: Settings → Redeem a gift code. \(giftRedeemLink(code: gift.code))"),
    ])
}

private func giftPurchaseMessageText(_ message: GiftPurchaseMessage) -> String {
    switch message {
    case .cancelled: String(localized: "Purchase cancelled.")
    case .pending: String(localized: "Payment pending. Your code appears in My gifts once the App Store confirms it.")
    case .codeDelayed: String(localized: "Thanks! Your code is on its way and will appear in My gifts in a minute.")
    case .offline: String(localized: "You're offline. Connect and try again.")
    case .failed: String(localized: "The purchase didn't go through. Please try again.")
    case .notAvailable: String(localized: "Purchases aren't available right now.")
    }
}

// MARK: - redeem (section 3)

struct RedeemGiftScreen: View {
    @ObservedObject var shell: AppShell
    let prefill: String?
    @State private var store: RedeemGiftStore?
    @FocusState private var focused: Bool

    var body: some View {
        PaperScreen {
            Form {
                if let store {
                    Section {
                        TextField(String(localized: "XXXX-XXXX-XXXX"), text: Binding(
                            get: { store.state.input }, set: { store.model.setInput($0) }
                        ))
                        .font(.system(size: 22, weight: .medium, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .focused($focused)
                        .submitLabel(.go)
                        .onSubmit { Task { await store.model.submit() } }
                        .accessibilityLabel(String(localized: "Gift code"))
                        PasteButton(payloadType: String.self) { strings in
                            let text = strings.first
                            Task { @MainActor in if let text { store.model.setInput(text) } }
                        }
                        Button {
                            Task { await store.model.submit() }
                        } label: {
                            HStack {
                                Text(String(localized: "Redeem"))
                                if store.state.busy { ProgressView() }
                            }
                        }
                        .disabled(!store.state.canSubmit)
                        .hitTarget()
                    } footer: {
                        Text(String(localized: "Enter the 12-character code from your gift message. Pro starts at once and adds to any Pro you already have."))
                    }
                    .listRowBackground(FlyColor.paperRaised)
                    if let result = store.state.result {
                        Section {
                            Text(redeemResultText(result))
                                .foregroundStyle(isRedeemed(result) ? FlyColor.onTime : FlyColor.cancelled)
                        }
                        .listRowBackground(FlyColor.paperRaised)
                    }
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle(String(localized: "Redeem a gift code"))
        .task {
            if store == nil {
                let profiles = shell.profiles
                let pro = shell.pro
                store = RedeemGiftStore(model: RedeemGiftModel(gifts: shell.p4.gifts, prefill: prefill) {
                    _ = await profiles.refresh()
                    await pro.reload()
                })
                focused = prefill == nil
            }
        }
    }

    private func isRedeemed(_ result: RedeemGiftResult) -> Bool {
        if case .redeemed = result { return true }
        return false
    }

    private func redeemResultText(_ result: RedeemGiftResult) -> String {
        switch result {
        case .redeemed(let until, let days):
            if let until {
                return String(localized: "Pro is on! It now runs until \(until.formatted(date: .abbreviated, time: .omitted)).")
            }
            return String(localized: "Pro is on for \(days) more days.")
        case .invalidCode: return String(localized: "That code isn't right. Check it and try again.")
        case .alreadyRedeemed: return String(localized: "This code has already been used.")
        case .voided: return String(localized: "This gift was refunded, so the code no longer works.")
        case .ownGift: return String(localized: "That's your own gift. Send it to someone instead.")
        case .rateLimited: return String(localized: "Too many tries. Please wait an hour and try again.")
        case .lifetimePro: return String(localized: "You already have Pro with no end date. Keep the code for someone else.")
        case .offline: return String(localized: "You're offline. Connect and try again.")
        case .notConfigured: return String(localized: "Backend not configured.")
        case .failed: return String(localized: "That didn't work. Try again.")
        }
    }
}

// MARK: - my gifts (section 3)

struct MyGiftsScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: MyGiftsStore?
    @State private var shareText: P4ShareText?
    @Environment(\.openURL) private var openURL

    var body: some View {
        PaperScreen {
            List {
                if let store { content(store) }
            }
            .scrollContentBackground(.hidden)
            .refreshable { await store?.model.load() }
        }
        .navigationTitle(String(localized: "My gifts"))
        .task {
            if store == nil { store = MyGiftsStore(model: MyGiftsModel(gifts: shell.p4.gifts)) }
            await store?.model.load()
        }
        .sheet(item: $shareText) { item in P4ActivitySheet(text: item.text) }
    }

    @ViewBuilder
    private func content(_ store: MyGiftsStore) -> some View {
        let state = store.state
        switch state.phase {
        case .loading:
            Section { ProgressView(String(localized: "Loading…")) }.listRowBackground(FlyColor.paperRaised)
        case .offline:
            Section { Text(String(localized: "You're offline. Connect and try again.")) }.listRowBackground(FlyColor.paperRaised)
        case .failed:
            Section { Text(String(localized: "Your gifts didn't load. Pull to try again.")) }.listRowBackground(FlyColor.paperRaised)
        case .notConfigured:
            Section { Text(String(localized: "Backend not configured.")) }.listRowBackground(FlyColor.paperRaised)
        case .ready:
            if state.gifts.isEmpty {
                Section {
                    Text(String(localized: "No gifts yet. Gift Pro to a friend or family member from the Pro screen."))
                    if !shell.p3.crewMode {
                        Button(String(localized: "Gift Pro to someone")) { shell.path.append(Route.giftPro) }.hitTarget()
                    }
                }
                .listRowBackground(FlyColor.paperRaised)
            }
            if !state.unused.isEmpty {
                Section(String(localized: "Not used yet")) {
                    ForEach(state.unused) { gift in unusedRow(gift) }
                }
                .listRowBackground(FlyColor.paperRaised)
            }
            if !state.used.isEmpty {
                Section(String(localized: "Used or refunded")) {
                    ForEach(state.used) { gift in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(gift.code).font(.system(size: 17, weight: .medium, design: .monospaced))
                            Text(giftProductTitle(gift.product)).font(FlyFont.sans(14, relativeTo: .subheadline))
                            Text(statusText(gift)).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .listRowBackground(FlyColor.paperRaised)
            }
        }
    }

    private func unusedRow(_ gift: Gift) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(gift.code).font(.system(size: 19, weight: .semibold, design: .monospaced)).textSelection(.enabled)
            Text(giftProductTitle(gift.product)).font(FlyFont.sans(14, relativeTo: .subheadline))
            if let created = gift.createdAt {
                Text(String(localized: "Bought \(created.formatted(date: .abbreviated, time: .omitted))"))
                    .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
            HStack(spacing: 12) {
                Button(String(localized: "WhatsApp")) {
                    let text = giftShareMessage(gift)
                    if let url = whatsAppSendURL(text: text), UIApplication.shared.canOpenURL(url) { openURL(url) } else { shareText = P4ShareText(text: text) }
                }
                .buttonStyle(.bordered)
                Button(String(localized: "Share…")) { shareText = P4ShareText(text: giftShareMessage(gift)) }
                    .buttonStyle(.bordered)
                Button(String(localized: "Copy")) { UIPasteboard.general.string = gift.code }
                    .buttonStyle(.bordered)
            }
            .hitTarget()
        }
    }

    private func statusText(_ gift: Gift) -> String {
        switch gift.status {
        case .redeemed:
            if let at = gift.redeemedAt { return String(localized: "Redeemed on \(at.formatted(date: .abbreviated, time: .omitted))") }
            return String(localized: "Redeemed")
        case .voided: return String(localized: "Refunded. The code no longer works.")
        case .unused: return String(localized: "Not used yet")
        }
    }
}

// MARK: - terminal map (section 4)

struct TerminalMapScreen: View {
    @ObservedObject var shell: AppShell
    let request: TerminalMapRequest
    @State private var store: TerminalMapStore?

    var body: some View {
        PaperScreen {
            if !shell.pro.state.isPro {
                ScrollView { teaser.padding(20) }
            } else if let store {
                content(store)
            } else {
                ProgressView(String(localized: "Loading…")).padding(.top, 40)
            }
        }
        .navigationTitle(String(localized: "Terminal map · \(request.iata)"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if store == nil {
                let model = TerminalMapModel(request: request, loader: shell.p4.terminalMaps)
                store = TerminalMapStore(model: model)
                await model.load()
            }
        }
    }

    @ViewBuilder
    private var teaser: some View {
        if shell.p3.crewMode {
            P4Notice(symbol: "map", title: String(localized: "Terminal maps are part of Pro"),
                     message: String(localized: "Gates, food, toilets and lounges at the airport, even offline."))
        } else {
            P4Notice(symbol: "map", title: String(localized: "Terminal maps are part of Pro"),
                     message: String(localized: "Find your gate, food, toilets, lounges and ATMs at the airport, even offline.")) {
                Button(String(localized: "See Pro plans")) { shell.path.append(Route.paywall) }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(FlyColor.paper)
                    .hitTarget()
            }
        }
    }

    @ViewBuilder
    private func content(_ store: TerminalMapStore) -> some View {
        let state = store.state
        switch state.phase {
        case .loading:
            ProgressView(String(localized: "Loading…")).padding(.top, 40)
        case .notAvailable:
            ScrollView {
                P4Notice(
                    symbol: "map",
                    title: String(localized: "Terminal map not available yet"),
                    message: String(localized: "We don't have a map of \(request.iata) yet. Maps come from OpenStreetMap and more airports are on the way.")
                )
                .padding(20)
            }
        case .ready:
            VStack(spacing: 0) {
                if let data = state.geojson {
                    TerminalMKMapView(
                        geojson: data, highlighted: state.highlightedIds, focus: state.focus,
                        selected: state.selectedPOI.flatMap { id in state.pois.first { $0.id == id } }
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 360)
                    .overlay(alignment: .bottomTrailing) { attribution }
                    .accessibilityLabel(String(localized: "Map of \(request.iata) terminals"))
                }
                poiList(store)
            }
        }
    }

    /// ODbL attribution, on every map.
    private var attribution: some View {
        Link(TerminalMapContract.attribution, destination: URL(string: TerminalMapContract.copyrightURL)!)
            .font(FlyFont.sans(11, relativeTo: .caption2))
            .foregroundStyle(FlyColor.ink)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(FlyColor.paper.opacity(0.9), in: .capsule)
            .padding(8)
    }

    private func poiList(_ store: TerminalMapStore) -> some View {
        let state = store.state
        let visible = store.model.visiblePOIs(label: poiCategoryLabel)
        return List {
            Section {
                highlightRows(state)
            }
            .listRowBackground(FlyColor.paperRaised)
            Section {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(FlyColor.muted).accessibilityHidden(true)
                    TextField(String(localized: "Search food, toilets, lounges…"), text: Binding(
                        get: { store.state.query }, set: { store.model.setQuery($0) }
                    ))
                    .autocorrectionDisabled()
                }
                if !state.categories.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(state.categories, id: \.self) { category in
                                Button {
                                    store.model.setCategory(category)
                                } label: {
                                    Label(poiCategoryLabel(category), systemImage: poiSymbol(category))
                                        .font(FlyFont.sans(14, relativeTo: .subheadline))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 6)
                                        .background(state.category == category ? FlyColor.primary : FlyColor.paper, in: .capsule)
                                        .foregroundStyle(state.category == category ? FlyColor.paper : FlyColor.ink)
                                        .overlay(Capsule().stroke(FlyColor.outline, lineWidth: 1))
                                }
                                .buttonStyle(.plain)
                                .hitTarget()
                                .accessibilityAddTraits(state.category == category ? [.isSelected] : [])
                            }
                        }
                    }
                }
                if visible.isEmpty {
                    Text(state.pois.isEmpty
                         ? String(localized: "No places are mapped inside this terminal yet.")
                         : String(localized: "Nothing matches your search."))
                        .foregroundStyle(FlyColor.muted)
                }
                ForEach(visible) { poi in
                    Button {
                        store.model.select(poi.id)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: poiSymbol(poi.category))
                                .foregroundStyle(FlyColor.accentLine)
                                .frame(width: 24)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(poi.name ?? poiCategoryLabel(poi.category))
                                    .font(FlyFont.sans(16, relativeTo: .body))
                                HStack(spacing: 6) {
                                    if poi.name != nil { Text(poiCategoryLabel(poi.category)) }
                                    if let level = poi.level { Text(String(localized: "Level \(level)")) }
                                }
                                .font(FlyFont.sans(13, relativeTo: .footnote))
                                .foregroundStyle(FlyColor.muted)
                            }
                            Spacer()
                            if state.selectedPOI == poi.id {
                                Image(systemName: "mappin.circle.fill").foregroundStyle(FlyColor.accentLine).accessibilityHidden(true)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(state.selectedPOI == poi.id ? [.isSelected] : [])
                }
            } header: {
                Text(String(localized: "Places in the terminal"))
            } footer: {
                Text(String(localized: "Map data © OpenStreetMap contributors, available under the Open Database License (ODbL). Works offline."))
                    .font(FlyFont.sans(12, relativeTo: .caption))
            }
            .listRowBackground(FlyColor.paperRaised)
        }
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private func highlightRows(_ state: TerminalMapState) -> some View {
        if let gate = state.request.gate, !gate.isEmpty {
            if state.gateNotMapped {
                Label(String(localized: "Gate \(gate) isn't on this map yet."), systemImage: "questionmark.circle")
                    .foregroundStyle(FlyColor.muted)
            } else {
                Label(String(localized: "Your gate \(gate) is marked on the map."), systemImage: "door.left.hand.open")
                    .foregroundStyle(FlyColor.accent)
            }
        }
        if let belt = state.request.belt, !belt.isEmpty {
            if state.beltFeatures.isEmpty {
                Label(String(localized: "Belt \(belt). The baggage area isn't on this map yet."), systemImage: "suitcase")
                    .foregroundStyle(FlyColor.muted)
            } else {
                Label(String(localized: "Belt \(belt): the baggage area is marked on the map."), systemImage: "suitcase")
                    .foregroundStyle(FlyColor.accent)
            }
        }
        if (state.request.gate ?? "").isEmpty && (state.request.belt ?? "").isEmpty {
            Text(String(localized: "Your gate shows here once it's announced."))
                .foregroundStyle(FlyColor.muted)
        }
    }
}

func poiCategoryLabel(_ category: POICategory) -> String {
    switch category {
    case .food: String(localized: "Food and drink")
    case .toilets: String(localized: "Toilets")
    case .lounge: String(localized: "Lounges")
    case .atm: String(localized: "ATM and money")
    case .prayerRoom: String(localized: "Prayer room")
    case .pharmacy: String(localized: "Pharmacy")
    case .charging: String(localized: "Phone charging")
    case .shop: String(localized: "Shops")
    }
}

func poiSymbol(_ category: POICategory) -> String {
    switch category {
    case .food: "fork.knife"
    case .toilets: "toilet"
    case .lounge: "sofa"
    case .atm: "indianrupeesign.circle"
    case .prayerRoom: "hands.and.sparkles"
    case .pharmacy: "cross.case"
    case .charging: "bolt.batteryblock"
    case .shop: "bag"
    }
}

// MARK: - MapKit rendering of the bundled GeoJSON

/// One point of the file on the map (gates, baggage claim points, POIs).
final class TerminalPin: NSObject, MKAnnotation {
    let featureId: String
    let kind: TerminalFeatureKind
    let coordinate: CLLocationCoordinate2D
    let title: String?
    let glyph: String?

    init(featureId: String, kind: TerminalFeatureKind, coordinate: CLLocationCoordinate2D, title: String?, glyph: String?) {
        self.featureId = featureId
        self.kind = kind
        self.coordinate = coordinate
        self.title = title
        self.glyph = glyph
    }
}

/// MKMapView with the file decoded by MKGeoJSONDecoder: terminals, aprons and baggage areas as MKPolygon /
/// MKMultiPolygon, runways, taxiways and jet bridges as MKPolyline, gates and POIs as annotations. The user's gate,
/// belt area and terminal are drawn in the accent colour. Apple's own points of interest are hidden so only OSM
/// data shows; without a connection the base map is blank but every overlay still draws.
struct TerminalMKMapView: UIViewRepresentable {
    let geojson: Data
    let highlighted: Set<String>
    let focus: GeoBounds?
    let selected: TerminalPOI?

    func makeCoordinator() -> TerminalMapCoordinator { TerminalMapCoordinator() }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        let configuration = MKStandardMapConfiguration(elevationStyle: .flat, emphasisStyle: .muted)
        configuration.pointOfInterestFilter = .excludingAll
        configuration.showsTraffic = false
        map.preferredConfiguration = configuration
        map.showsCompass = true
        map.showsScale = true
        map.isPitchEnabled = false
        map.delegate = context.coordinator
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: TerminalMapCoordinator.reuseId)
        context.coordinator.highlighted = highlighted
        context.coordinator.load(geojson, into: map)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.update(map, highlighted: highlighted, focus: focus, selected: selected)
    }
}

@MainActor
final class TerminalMapCoordinator: NSObject, @preconcurrency MKMapViewDelegate {
    static let reuseId = "terminal-pin"

    var highlighted: Set<String> = []
    private var kinds: [ObjectIdentifier: TerminalFeatureKind] = [:]
    private var featureIds: [ObjectIdentifier: String] = [:]
    private var pins: [String: TerminalPin] = [:]
    private var focused = false
    private var lastSelected: String?

    func load(_ data: Data, into map: MKMapView) {
        guard let objects = try? MKGeoJSONDecoder().decode(data) else { return }
        var overlays: [MKOverlay] = []
        var annotations: [TerminalPin] = []
        for (index, object) in objects.enumerated() {
            guard let feature = object as? MKGeoJSONFeature else { continue }
            let props = feature.properties.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
            guard let kind = (props["kind"] as? String).flatMap(TerminalFeatureKind.init(rawValue:)) else { continue }
            // Stands are too many to read; the gates carry the numbers people look for.
            if kind == .parkingPosition { continue }
            // The same fallback id as `parseTerminalMap`, so highlights and POI taps find features without an id.
            let id = feature.identifier ?? "feature/\(index)"
            let ref = (props["ref"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let name = (props["name"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            for shape in feature.geometry {
                switch shape {
                case let polygon as MKPolygon: overlays.append(register(polygon, kind: kind, id: id))
                case let multi as MKMultiPolygon: overlays.append(register(multi, kind: kind, id: id))
                case let line as MKPolyline: overlays.append(register(line, kind: kind, id: id))
                case let multi as MKMultiPolyline: overlays.append(register(multi, kind: kind, id: id))
                case let point as MKPointAnnotation:
                    let title: String?
                    switch kind {
                    case .gate: title = ref.map { String(localized: "Gate \($0)") } ?? name
                    case .baggageClaim: title = ref.map { String(localized: "Belt \($0)") } ?? name ?? String(localized: "Baggage claim")
                    default: title = name ?? kind.poiCategory.map(poiCategoryLabel)
                    }
                    let glyph = kind == .gate ? ref.map { String($0.prefix(4)) } : nil
                    let pin = TerminalPin(featureId: id, kind: kind, coordinate: point.coordinate, title: title, glyph: glyph)
                    pins[id] = pin
                    annotations.append(pin)
                default:
                    break
                }
            }
        }
        // The file is already in drawing order: aprons and runways under terminals, gates on top.
        map.addOverlays(overlays, level: .aboveRoads)
        map.addAnnotations(annotations)
    }

    private func register(_ overlay: MKOverlay, kind: TerminalFeatureKind, id: String) -> MKOverlay {
        kinds[ObjectIdentifier(overlay)] = kind
        featureIds[ObjectIdentifier(overlay)] = id
        return overlay
    }

    func update(_ map: MKMapView, highlighted next: Set<String>, focus: GeoBounds?, selected: TerminalPOI?) {
        if next != highlighted {
            let changed = highlighted.symmetricDifference(next)
            highlighted = next
            for overlay in map.overlays where changed.contains(featureIds[ObjectIdentifier(overlay)] ?? "") {
                if let renderer = map.renderer(for: overlay) as? MKOverlayPathRenderer {
                    let id = featureIds[ObjectIdentifier(overlay)] ?? ""
                    style(renderer, kind: kinds[ObjectIdentifier(overlay)], highlighted: next.contains(id))
                    renderer.setNeedsDisplay()
                }
            }
            for id in changed {
                if let pin = pins[id], let view = map.view(for: pin) as? MKMarkerAnnotationView { configure(view, pin: pin) }
            }
        }
        if !focused, let focus {
            focused = true
            show(focus, on: map, animated: false)
        }
        if selected?.id != lastSelected {
            lastSelected = selected?.id
            if let selected {
                let region = MKCoordinateRegion(
                    center: CLLocationCoordinate2D(latitude: selected.position.lat, longitude: selected.position.lon),
                    latitudinalMeters: 250, longitudinalMeters: 250
                )
                map.setRegion(region, animated: true)
                if let pin = pins[selected.id] { map.selectAnnotation(pin, animated: true) }
            }
        }
    }

    private func show(_ bounds: GeoBounds, on map: MKMapView, animated: Bool) {
        let a = MKMapPoint(CLLocationCoordinate2D(latitude: bounds.minLat, longitude: bounds.minLon))
        let b = MKMapPoint(CLLocationCoordinate2D(latitude: bounds.maxLat, longitude: bounds.maxLon))
        let rect = MKMapRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
        // A single gate is a point: show about 300 m around it.
        let minimum = 300 * MKMapPointsPerMeterAtLatitude(bounds.center.lat)
        let padded = rect.insetBy(dx: -max(0, (minimum - rect.width) / 2), dy: -max(0, (minimum - rect.height) / 2))
        map.setVisibleMapRect(padded, edgePadding: UIEdgeInsets(top: 32, left: 24, bottom: 40, right: 24), animated: animated)
    }

    func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
        let renderer: MKOverlayPathRenderer
        switch overlay {
        case let polygon as MKPolygon: renderer = MKPolygonRenderer(polygon: polygon)
        case let multi as MKMultiPolygon: renderer = MKMultiPolygonRenderer(multiPolygon: multi)
        case let line as MKPolyline: renderer = MKPolylineRenderer(polyline: line)
        case let multi as MKMultiPolyline: renderer = MKMultiPolylineRenderer(multiPolyline: multi)
        default: return MKOverlayRenderer(overlay: overlay)
        }
        let id = featureIds[ObjectIdentifier(overlay)] ?? ""
        style(renderer, kind: kinds[ObjectIdentifier(overlay)], highlighted: highlighted.contains(id))
        return renderer
    }

    func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
        guard let pin = annotation as? TerminalPin else { return nil }
        let view = (mapView.dequeueReusableAnnotationView(withIdentifier: Self.reuseId, for: pin) as? MKMarkerAnnotationView)
            ?? MKMarkerAnnotationView(annotation: pin, reuseIdentifier: Self.reuseId)
        view.annotation = pin
        configure(view, pin: pin)
        return view
    }

    private func configure(_ view: MKMarkerAnnotationView, pin: TerminalPin) {
        let isHighlighted = highlighted.contains(pin.featureId)
        view.titleVisibility = isHighlighted ? .visible : .adaptive
        view.subtitleVisibility = .hidden
        view.animatesWhenAdded = false
        switch pin.kind {
        case .gate:
            view.glyphText = pin.glyph
            view.glyphImage = pin.glyph == nil ? UIImage(systemName: "door.left.hand.open") : nil
            view.markerTintColor = UIColor(isHighlighted ? FlyColor.accentLine : FlyColor.primary)
            view.displayPriority = isHighlighted ? .required : .defaultLow
            view.zPriority = isHighlighted ? .max : .defaultUnselected
        case .baggageClaim:
            view.glyphText = nil
            view.glyphImage = UIImage(systemName: "suitcase")
            view.markerTintColor = UIColor(isHighlighted ? FlyColor.accentLine : FlyColor.muted)
            view.displayPriority = isHighlighted ? .required : .defaultLow
        default:
            view.glyphText = nil
            view.glyphImage = pin.kind.poiCategory.map { UIImage(systemName: poiSymbol($0)) } ?? nil
            view.markerTintColor = UIColor(FlyColor.muted)
            view.displayPriority = .defaultLow
        }
    }

    private func style(_ renderer: MKOverlayPathRenderer, kind: TerminalFeatureKind?, highlighted: Bool) {
        let accent = UIColor(FlyColor.accentLine)
        switch kind {
        case .terminal?:
            renderer.fillColor = UIColor(FlyColor.paperRaised).withAlphaComponent(0.92)
            renderer.strokeColor = highlighted ? accent : UIColor(FlyColor.outline)
            renderer.lineWidth = highlighted ? 2.5 : 1
        case .apron?:
            renderer.fillColor = UIColor(FlyColor.outlineVariant).withAlphaComponent(0.55)
            renderer.strokeColor = nil
            renderer.lineWidth = 0
        case .baggageClaim?:
            renderer.fillColor = accent.withAlphaComponent(highlighted ? 0.45 : 0.12)
            renderer.strokeColor = highlighted ? accent : nil
            renderer.lineWidth = highlighted ? 2 : 0
        case .runway?:
            renderer.fillColor = UIColor(FlyColor.muted).withAlphaComponent(0.6)
            renderer.strokeColor = UIColor(FlyColor.muted)
            renderer.lineWidth = 6
        case .taxiway?:
            renderer.fillColor = UIColor(FlyColor.outline).withAlphaComponent(0.3)
            renderer.strokeColor = UIColor(FlyColor.outline)
            renderer.lineWidth = 2
        case .jetBridge?:
            renderer.fillColor = UIColor(FlyColor.outline)
            renderer.strokeColor = UIColor(FlyColor.ink)
            renderer.lineWidth = 2
        default:
            renderer.fillColor = UIColor(FlyColor.outlineVariant).withAlphaComponent(0.4)
            renderer.strokeColor = UIColor(FlyColor.outline)
            renderer.lineWidth = 1
        }
        if renderer is MKPolylineRenderer || renderer is MKMultiPolylineRenderer {
            renderer.lineCap = .round
            renderer.lineJoin = .round
        }
    }
}
