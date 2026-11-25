import FlyTrackCore
import SwiftUI

/// "Import a ticket". With Gmail import on it asks where from; otherwise it opens the file import as before.
struct ImportTicketButton: View {
    let shell: AppShell
    @State private var choosing = false

    var body: some View {
        Button(String(localized: "Import a ticket")) {
            if AppConfig.isGmailImportEnabled { choosing = true } else { shell.path.append(Route.importTicket) }
        }
        .confirmationDialog(String(localized: "Import a ticket"), isPresented: $choosing, titleVisibility: .visible) {
            Button(String(localized: "From a file or screenshot")) { shell.path.append(Route.importTicket) }
            Button(String(localized: "From Gmail")) { shell.path.append(Route.gmailImport) }
        }
    }
}

struct GmailImportScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: GmailImportStore?

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Google's required in-app disclosure for the restricted Gmail scope.
                    Text(String(localized: "HeyGate reads your booking emails (read-only Gmail access) to find tickets to import. It reads them on this phone only. Nothing from your mail is sent to HeyGate."))
                        .font(FlyFont.sans(15, relativeTo: .body))
                    if let store { content(store) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
        }
        .navigationTitle(String(localized: "From Gmail"))
        .task {
            // Coming back from Add flight only re-reads what is stored; the scan runs once per visit.
            if let store {
                await store.refresh()
                return
            }
            let created = GmailImportStore(model: shell.gmailModel())
            store = created
            await created.start()
        }
    }

    @ViewBuilder
    private func content(_ store: GmailImportStore) -> some View {
        let state = store.state
        if let email = state.email {
            Text(String(localized: "Connected as \(email)")).font(FlyFont.sans(14, relativeTo: .footnote))
        }
        switch state.scan {
        case .notConnected:
            connectButton(store, String(localized: "Connect Gmail"))
        case .needsReconnect:
            Text(String(localized: "HeyGate can no longer read this Gmail account."))
            connectButton(store, String(localized: "Reconnect Gmail"))
        case .scanning:
            ProgressView(String(localized: "Checking your booking emails…"))
        case .offline:
            Text(String(localized: "You're offline."))
            Button(String(localized: "Try again")) { Task { await store.scan() } }.hitTarget()
        case .noSuggestions:
            Text(String(localized: "No upcoming flights in your recent booking emails"))
                .font(FlyFont.serifItalic(20, relativeTo: .title3))
            Button(String(localized: "Import manually")) { shell.path.append(Route.importTicket) }.hitTarget()
            Button(String(localized: "Type it instead")) { shell.path.append(Route.add(AddFlightPrefill())) }.hitTarget()
        case .moreToRead:
            Text(String(localized: "Some booking emails are still unread. Scan again to keep looking."))
                .font(FlyFont.serifItalic(20, relativeTo: .title3))
            Button(String(localized: "Scan again")) { Task { await store.scan() } }.hitTarget()
        case .results:
            EmptyView()
        }
        if state.connectFailed {
            Text(String(localized: "Gmail could not be connected. Try again.")).foregroundStyle(FlyColor.cancelled)
        }
        ForEach(state.suggestions, id: \.id) { suggestion in
            Button { shell.path.append(Route.add(suggestion.addFlightPrefill)) } label: {
                GmailSuggestionLabel(suggestion: suggestion)
            }
            .hitTarget()
        }
    }

    private func connectButton(_ store: GmailImportStore, _ title: String) -> some View {
        Button(store.state.busy ? String(localized: "Connecting…") : title) { Task { await store.connect() } }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(FlyColor.paper)
            .disabled(store.state.busy)
            .hitTarget()
    }
}

/// The Found in Gmail card on Flights: each pending suggestion with Add and Dismiss.
struct FoundInGmailCard: View {
    let shell: AppShell
    let store: GmailImportStore

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Found in Gmail").uppercased())
                .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2))
                .tracking(1.2)
                .foregroundStyle(FlyColor.accent)
                .accessibilityAddTraits(.isHeader)
            ForEach(store.state.suggestions, id: \.id) { suggestion in
                VStack(alignment: .leading, spacing: 8) {
                    GmailSuggestionLabel(suggestion: suggestion)
                    HStack(spacing: 16) {
                        // Borderless, or a tap anywhere in the List row fires every button in it.
                        Button(String(localized: "Add")) { shell.path.append(Route.add(suggestion.addFlightPrefill)) }
                            .buttonStyle(.borderless)
                            .hitTarget()
                        Button(String(localized: "Dismiss")) { Task { await store.dismiss(suggestion.id) } }
                            .buttonStyle(.borderless)
                            .hitTarget()
                    }
                }
            }
        }
        .padding(.vertical, 8)
    }
}

/// Settings → Gmail, shown once an account is connected.
struct GmailSettingsSection: View {
    let store: GmailImportStore

    var body: some View {
        if let email = store.state.email {
            Section(String(localized: "Gmail")) {
                Text(email)
                Toggle(String(localized: "Auto-check for new bookings"), isOn: Binding(
                    get: { store.state.autoCheck },
                    set: { value in Task { await store.setAutoCheck(value) } }
                ))
                if store.state.scan == .needsReconnect {
                    Text(String(localized: "HeyGate can no longer read this Gmail account."))
                    Button(String(localized: "Reconnect Gmail")) { Task { await store.connect() } }
                        .disabled(store.state.busy)
                        .hitTarget()
                }
                Button(String(localized: "Disconnect Gmail"), role: .destructive) { Task { await store.disconnect() } }
                    // PaperScreen's ink foreground would otherwise hide the destructive red.
                    .foregroundStyle(FlyColor.cancelled)
                    .disabled(store.state.busy)
                    .hitTarget()
            }
        }
    }
}

private struct GmailSuggestionLabel: View {
    let suggestion: GmailSuggestion

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(suggestion.flight).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
            Text(FlightTimeFormat.fullDate(suggestion.date)).font(FlyFont.sans(15, relativeTo: .body))
            if let from = suggestion.fromIata, let to = suggestion.toIata {
                Text("\(from) → \(to)").font(FlyFont.numeral(22))
            }
            Text(suggestion.subject)
                .font(FlyFont.sans(14, relativeTo: .footnote))
                .foregroundStyle(FlyColor.muted)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

@MainActor
@Observable
final class GmailImportStore {
    var state: GmailImportState
    let model: GmailImportModel
    init(model: GmailImportModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func start() async { await model.start() }
    func refresh() async { await model.refresh() }
    func scan() async { await model.scan() }
    func connect() async { await model.connect() }
    func dismiss(_ id: String) async { await model.dismiss(id) }
    func setAutoCheck(_ on: Bool) async { await model.setAutoCheck(on) }
    func disconnect() async { await model.disconnect() }
}

extension AppShell {
    func gmailModel() -> GmailImportModel {
        let clock = clock
        return GmailImportModel(
            scanner: gmailScanner(), store: gmailStore, settings: settings, tracked: flights,
            system: LiveGmailSystem(auth: gmailAuth), today: { clock.today(tz: TimeZone.current.identifier) }
        )
    }
}

/// Task 11's GmailAuth plus Task 12's notification and BGTask helpers, behind the core `GmailSystem`.
@MainActor
final class LiveGmailSystem: GmailSystem {
    private let auth: GmailAuth
    init(auth: GmailAuth) { self.auth = auth }

    func connect() async -> GmailTokenResult { await auth.connect() }
    func revoke() async { await auth.disconnect() }
    func cancelNotifications(_ ids: [String]) async { GmailNotifications.cancel(ids) }
    func cancelAllNotifications() async { await GmailNotifications.cancelAll() }
    func setBackgroundChecks(_ on: Bool) { on ? GmailBackground.submit() : GmailBackground.cancel() }
}
