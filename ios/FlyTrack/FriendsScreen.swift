import FlyTrackCore
import SwiftUI
import UIKit

// Friends ("Mom's flight has landed"), P2 wave 2 contract section 1: invite, accept a code, the friends list with
// alert switches, and the flights friends share.

/// The friend's display name, or "Your friend" when they set none (contract: falls back to "Your friend").
func friendDisplayName(_ name: String?) -> String {
    name ?? String(localized: "Your friend")
}

/// The text sent with an invite: the code in its readable form and a link that opens the accept screen.
func inviteMessage(code: String) -> String {
    let readable = formatInviteCode(code)
    return String(localized: "Let's follow each other's flights on HeyGate. In the app, open Friends and enter my code \(readable)") + "\n" + inviteLink(code: code)
}

struct FriendsScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: FriendsStore?
    @State private var flights: FriendsFlightsStore?
    @State private var shareText: ShareText?
    @State private var removing: Friend?
    @State private var copied = false
    @Environment(\.openURL) private var openURL

    var body: some View {
        PaperScreen {
            if let store, let flights {
                content(store, flights)
            } else {
                ProgressView(String(localized: "Loading…"))
            }
        }
        .navigationTitle(String(localized: "Friends"))
        .sheet(item: $shareText) { item in TextShareSheet(text: item.text).ignoresSafeArea() }
        .confirmationDialog(
            String(localized: "Remove \(friendDisplayName(removing?.displayName))?"),
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button(String(localized: "Remove friend"), role: .destructive) {
                if let friend = removing { Task { await store?.remove(friend.id) } }
                removing = nil
            }
            Button(String(localized: "Cancel"), role: .cancel) { removing = nil }
        } message: {
            Text(String(localized: "You will stop seeing each other's flights."))
        }
        .onChange(of: store?.state.inviteShare) { _, request in
            guard let request else { return }
            store?.inviteHandled(request)
            handle(request)
        }
        .task {
            if store == nil { store = FriendsStore(model: FriendsModel(repository: shell.friends)) }
            if flights == nil { flights = FriendsFlightsStore(model: FriendsFlightsModel(repository: shell.friends, clock: shell.clock)) }
            await store?.load()
            await flights?.load()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                if Task.isCancelled { break }
                flights?.tick()
            }
        }
    }

    @ViewBuilder
    private func content(_ store: FriendsStore, _ flights: FriendsFlightsStore) -> some View {
        List {
            if let message = store.state.message {
                Section {
                    HStack(alignment: .top) {
                        Text(friendsMessageText(message)).font(FlyFont.sans(14, relativeTo: .footnote))
                        Spacer()
                        Button(String(localized: "Dismiss"), systemImage: "xmark") { store.messageShown(message) }
                            .labelStyle(.iconOnly)
                            .hitTarget()
                    }
                }
                .listRowBackground(FlyColor.paperRaised)
            }
            inviteSection(store)
            if !flights.state.rows.isEmpty {
                Section(String(localized: "Friends' flights")) {
                    ForEach(flights.state.rows, id: \.self) { row in
                        FriendFlightRowView(row: row) { shell.openFlight(row.card.flightId) }
                    }
                }
                .listRowBackground(FlyColor.paperRaised)
            }
            friendsSection(store)
        }
        .scrollContentBackground(.hidden)
        .refreshable {
            await store.load()
            await flights.load()
        }
    }

    @ViewBuilder
    private func inviteSection(_ store: FriendsStore) -> some View {
        Section {
            Text(String(localized: "Friends see the flights you share and get an alert when you take off and land. You see theirs."))
                .font(FlyFont.sans(14, relativeTo: .subheadline))
            if let code = store.state.inviteCode {
                HStack {
                    Text(String(localized: "Your code"))
                        .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                        .textCase(.uppercase)
                        .tracking(1.2)
                    Spacer()
                    Text(verbatim: formatInviteCode(code))
                        .font(FlyFont.numeral(22))
                        .textSelection(.enabled)
                }
                .accessibilityElement(children: .combine)
            }
            if WhatsApp.isInstalled {
                Button(String(localized: "Invite on WhatsApp")) { Task { await store.invite(.whatsApp) } }
                    .hitTarget()
            }
            Button(String(localized: "Share invite…")) { Task { await store.invite(.shareSheet) } }
                .hitTarget()
            Button(copied ? String(localized: "Code copied") : String(localized: "Copy my code")) { Task { await store.invite(.copy) } }
                .hitTarget()
            Button(String(localized: "Enter a friend's code")) { shell.path.append(Route.acceptInvite(nil)) }
                .hitTarget()
            if store.state.isCreatingInvite { ProgressView() }
        } header: {
            Text(String(localized: "Invite"))
        } footer: {
            Text(String(localized: "A code works for 7 days. Your PNR and seat are never shared."))
                .font(FlyFont.sans(12, relativeTo: .caption))
                .foregroundStyle(FlyColor.muted)
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    @ViewBuilder
    private func friendsSection(_ store: FriendsStore) -> some View {
        Section(String(localized: "Your friends")) {
            switch store.state.load {
            case .loading:
                ProgressView()
            case .offline:
                Text(String(localized: "You're offline. Friends will show when you're back online."))
            case .notConfigured:
                Text(String(localized: "This build has no server address."))
            case .failed:
                Button(String(localized: "Couldn't load your friends. Try again")) { Task { await store.load() } }
                    .hitTarget()
            case .loaded:
                if store.state.friends.isEmpty {
                    Text(String(localized: "No friends yet. Invite someone you fly with, or whose flights you watch."))
                        .foregroundStyle(FlyColor.muted)
                }
                ForEach(store.state.friends) { friend in
                    FriendRowView(
                        friend: friend,
                        busy: store.state.busy.contains(friend.id),
                        setAlerts: { enabled in Task { await store.setAlerts(friend.id, enabled: enabled) } },
                        remove: { removing = friend }
                    )
                }
            }
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    private func handle(_ request: InviteShareRequest) {
        let text = inviteMessage(code: request.code)
        switch request.target {
        case .whatsApp:
            if let url = whatsAppSendURL(text: text) { openURL(url) } else { shareText = ShareText(text: text) }
        case .shareSheet:
            shareText = ShareText(text: text)
        case .copy:
            UIPasteboard.general.string = formatInviteCode(request.code)
            copied = true
        }
    }
}

private struct FriendRowView: View {
    var friend: Friend
    var busy: Bool
    var setAlerts: (Bool) -> Void
    var remove: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(friendDisplayName(friend.displayName)).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                Spacer()
                Button(String(localized: "Remove"), systemImage: "person.crop.circle.badge.minus", role: .destructive, action: remove)
                    .labelStyle(.iconOnly)
                    .foregroundStyle(FlyColor.cancelled)
                    .disabled(busy)
                    .hitTarget()
                    .accessibilityLabel(String(localized: "Remove \(friendDisplayName(friend.displayName))"))
            }
            if let since = friend.since {
                Text(String(localized: "Friends since \(since.formatted(date: .abbreviated, time: .omitted))"))
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.muted)
            }
            Toggle(String(localized: "Alerts for their flights"), isOn: Binding(get: { friend.alertsEnabled }, set: setAlerts))
                .disabled(busy)
                .hitTarget()
        }
        .padding(.vertical, 4)
    }
}

private struct FriendFlightRowView: View {
    var row: FriendFlightRow
    var open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                Image(systemName: "airplane")
                    .frame(width: 44, height: 44)
                    .overlay(Circle().stroke(FlyColor.accentLine, lineWidth: 1))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(friendDisplayName(row.friendName).uppercased())
                        .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(FlyColor.accent)
                    Text("\(row.card.depCode) → \(row.card.arrCode)").font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                    Text(verbatim: row.card.designator).font(FlyFont.sans(14, relativeTo: .subheadline))
                    Text(statusWord(row.card.statusWord))
                        .font(FlyFont.serifItalic(18, relativeTo: .headline))
                        .foregroundStyle(FlyColor.tone(row.card.tone))
                    if let progress = row.card.progress {
                        FlightProgressLine(
                            progress: progress, source: row.card.progressSource,
                            remaining: row.card.progressArrival.map { max(0, $0.timeIntervalSince(row.card.asOf)) }
                        )
                        .padding(.top, 4)
                    }
                }
                Spacer()
                if let time = row.card.departure?.time {
                    Text(time).font(FlyFont.numeral(22))
                }
            }
        }
        .hitTarget()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "\(friendDisplayName(row.friendName))'s flight \(row.card.designator), \(row.card.depCity) to \(row.card.arrCity), \(statusWord(row.card.statusWord))"))
    }
}

/// "Enter a friend code". A code from an invite link arrives filled in.
struct AcceptInviteScreen: View {
    @ObservedObject var shell: AppShell
    var prefill: String?
    @State private var store: AcceptInviteStore?
    @FocusState private var focused: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PaperScreen {
            if let store {
                VStack(alignment: .leading, spacing: 16) {
                    if let friend = store.state.accepted {
                        Text(String(localized: "You and \(friendDisplayName(friend.displayName)) are now friends."))
                            .font(FlyFont.serifItalic(28, relativeTo: .title))
                        Text(String(localized: "You'll see the flights they share, and they'll see yours."))
                            .font(FlyFont.sans(16, relativeTo: .body))
                        Button(String(localized: "Done")) { dismiss() }
                            .buttonStyle(.borderedProminent)
                            .foregroundStyle(FlyColor.paper)
                            .hitTarget()
                    } else {
                        Text(String(localized: "Type or paste the code your friend sent you."))
                            .font(FlyFont.sans(16, relativeTo: .body))
                        TextField(String(localized: "ABCD-EFGH"), text: Binding(get: { store.state.input }, set: { store.setInput($0) }))
                            .font(FlyFont.numeral(28))
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                            .focused($focused)
                            .submitLabel(.done)
                            .onSubmit { Task { await store.submit() } }
                            .padding(12)
                            .background(FlyColor.paperRaised, in: .rect(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6).stroke(FlyColor.outline, lineWidth: 1))
                            .accessibilityLabel(String(localized: "Friend code"))
                        if let error = store.state.error {
                            Text(acceptErrorText(error))
                                .font(FlyFont.sans(14, relativeTo: .footnote))
                                .foregroundStyle(FlyColor.cancelled)
                        }
                        Button {
                            Task { await store.submit() }
                        } label: {
                            HStack {
                                if store.state.isSubmitting { ProgressView() }
                                Text(String(localized: "Add friend"))
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .foregroundStyle(FlyColor.paper)
                        .disabled(!store.state.canSubmit)
                        .hitTarget()
                    }
                    Spacer()
                }
                .padding(20)
            }
        }
        .navigationTitle(String(localized: "Add a friend"))
        .task {
            if store == nil {
                let model = AcceptInviteModel(repository: shell.friends)
                model.prefill(prefill)
                store = AcceptInviteStore(model: model)
                focused = prefill == nil
            }
        }
    }
}

func friendsMessageText(_ message: FriendsMessage) -> String {
    switch message {
    case .offline: String(localized: "You're offline. Try again when you're connected.")
    case .failed: String(localized: "That didn't work. Try again.")
    case .removed: String(localized: "Friend removed.")
    }
}

func acceptErrorText(_ error: AcceptError) -> String {
    switch error {
    case .invalidCode: String(localized: "That code doesn't work. Check it, or ask for a new one: codes last 7 days.")
    case .ownCode: String(localized: "That's your own code. Send it to a friend instead.")
    case .alreadyFriends: String(localized: "You're already friends.")
    case .friendLimit: String(localized: "One of you already has 50 friends, the most HeyGate allows.")
    case .offline: String(localized: "You're offline. Try again when you're connected.")
    case .failed: String(localized: "That didn't work. Try again.")
    }
}

struct ShareText: Identifiable {
    let id = UUID()
    let text: String
}

struct TextShareSheet: UIViewControllerRepresentable {
    var text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - stores

@MainActor
@Observable
final class FriendsStore {
    var state: FriendsState
    let model: FriendsModel
    init(model: FriendsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load() }
    func invite(_ target: InviteTarget) async { await model.invite(target) }
    func inviteHandled(_ request: InviteShareRequest) { model.inviteHandled(request) }
    func setAlerts(_ id: String, enabled: Bool) async { await model.setAlerts(id, enabled: enabled) }
    func remove(_ id: String) async { await model.remove(id) }
    func messageShown(_ message: FriendsMessage) { model.messageShown(message) }
}

@MainActor
@Observable
final class FriendsFlightsStore {
    var state: FriendsFlightsState
    let model: FriendsFlightsModel
    init(model: FriendsFlightsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load() }
    func tick() { model.tick() }
}

@MainActor
@Observable
final class AcceptInviteStore {
    var state: AcceptInviteState
    let model: AcceptInviteModel
    init(model: AcceptInviteModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func setInput(_ value: String) { model.setInput(value) }
    func submit() async { await model.submit() }
}
