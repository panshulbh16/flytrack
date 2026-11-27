import FlyTrackCore
import SwiftUI
import UIKit

// P3 screens of the search / social area: calendar import (A3), friend Passport compare (B10), crew mode and Passport
// sharing in Settings (B12, B10), family sharing (B13).

// MARK: - stores

@MainActor
@Observable
final class CalendarImportStore {
    var state: CalendarImportState
    let model: CalendarImportModel
    init(model: CalendarImportModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class FriendCompareStore {
    var state: FriendCompareState
    let model: FriendCompareModel
    init(model: FriendCompareModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class ProfileFlagsStore {
    var state: ProfileFlagsState
    let model: ProfileFlagsModel
    init(model: ProfileFlagsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

@MainActor
@Observable
final class FamilyStore {
    var state: FamilyState
    let model: FamilyModel
    init(model: FamilyModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
}

// MARK: - A3 calendar import

struct CalendarImportScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: CalendarImportStore?
    @Environment(\.openURL) private var openURL

    private var isPro: Bool { shell.pro.state.isPro }

    var body: some View {
        PaperScreen {
            List {
                if !isPro {
                    proSection
                } else if let store {
                    content(store)
                }
            }
            .scrollContentBackground(.hidden)
        }
        .navigationTitle(String(localized: "From your calendar"))
        .task {
            guard isPro else { return }
            if store == nil {
                let model = CalendarImportModel(
                    calendar: shell.p3.calendar, airports: shell.p3.airports, flights: shell.flights,
                    clock: shell.clock, homeTimeZone: TimeZone.current.identifier
                )
                store = CalendarImportStore(model: model)
                await model.start()
            } else {
                // Back from Add flight: mark what was just added.
                await store?.model.refreshTracked()
            }
        }
    }

    private var proSection: some View {
        Section {
            Text(String(localized: "Find flights in your calendar"))
                .font(FlyFont.serif(26, relativeTo: .title))
            Text(String(localized: "Calendar import is part of HeyGate Pro. It looks through the next year of your calendar for flights and lists them so you can add each with one tap."))
                .font(FlyFont.sans(15, relativeTo: .body))
            if !shell.p3.crewMode {
                Button(String(localized: "See plans")) { shell.path.append(Route.paywall) }
                    .hitTarget()
            }
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    @ViewBuilder
    private func content(_ store: CalendarImportStore) -> some View {
        switch store.state {
        case .intro:
            Section {
                Text(String(localized: "Find flights in your calendar"))
                    .font(FlyFont.serif(26, relativeTo: .title))
                Text(String(localized: "HeyGate reads the next 365 days of your calendar on this iPhone and suggests the flights it finds. Nothing is uploaded and nothing in your calendar is changed."))
                    .font(FlyFont.sans(15, relativeTo: .body))
                Button(String(localized: "Scan my calendar")) { Task { await store.model.scanTapped() } }
                    .buttonStyle(.borderedProminent)
                    .foregroundStyle(FlyColor.paper)
                    .hitTarget()
            }
            .listRowBackground(FlyColor.paperRaised)
        case .permissionDenied(let restricted):
            Section {
                Text(restricted
                     ? String(localized: "Calendar access is restricted on this iPhone.")
                     : String(localized: "HeyGate needs full access to your calendar to find flights. You can allow it in Settings."))
                if !restricted {
                    Button(String(localized: "Open Settings")) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    .hitTarget()
                }
            }
            .listRowBackground(FlyColor.paperRaised)
        case .scanning:
            Section { ProgressView(String(localized: "Looking for flights…")) }
                .listRowBackground(FlyColor.paperRaised)
        case .unavailable:
            Section { Text(String(localized: "The calendar could not be read. Try again later.")) }
                .listRowBackground(FlyColor.paperRaised)
        case .results(let suggestions, let read, let truncated):
            Section {
                if suggestions.isEmpty {
                    Text(String(localized: "No flights found in your calendar."))
                } else {
                    ForEach(suggestions) { suggestion in
                        CalendarSuggestionRow(suggestion: suggestion) {
                            shell.path.append(Route.add(suggestion.prefill ?? AddFlightPrefill(source: .calendar)))
                        }
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Checked \(read) calendar events."))
                    if truncated { Text(String(localized: "Your calendar is very full: only the first events were checked.")) }
                }
                .font(FlyFont.sans(13, relativeTo: .footnote))
                .foregroundStyle(FlyColor.muted)
            }
            .listRowBackground(FlyColor.paperRaised)
            Section {
                Button(String(localized: "Scan again")) { Task { await store.model.scan() } }.hitTarget()
            }
            .listRowBackground(FlyColor.paperRaised)
        }
    }
}

private struct CalendarSuggestionRow: View {
    let suggestion: CalendarSuggestion
    let add: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let leg = suggestion.leg, let count = suggestion.legCount {
                Text(String(localized: "Leg \(leg) of \(count)").uppercased())
                    .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2))
                    .tracking(1.2)
                    .foregroundStyle(FlyColor.muted)
            }
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: suggestion.flight ?? String(localized: "Flight"))
                    .font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                Spacer()
                if let date = suggestion.date {
                    Text(verbatim: dayLabel(date) + (suggestion.departTime.map { " · \($0)" } ?? ""))
                        .font(FlyFont.sans(14, relativeTo: .subheadline))
                }
            }
            Text(verbatim: "\(suggestion.from?.iata ?? "?") → \(suggestion.to?.iata ?? "?")").font(FlyFont.numeral(20))
            if let from = suggestion.from, let to = suggestion.to {
                Text(verbatim: "\(from.city) – \(to.city)").font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
            ForEach(suggestion.issues, id: \.self) { issue in
                Text(issueText(issue)).font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.delayed)
            }
            if suggestion.alreadyTracked {
                Label(String(localized: "Already tracked"), systemImage: "checkmark.circle")
                    .foregroundStyle(FlyColor.onTime)
            } else {
                Button(actionTitle, action: add)
                    .hitTarget()
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var actionTitle: String {
        switch suggestion.action {
        case .byNumber: String(localized: "Add")
        case .byRoute: String(localized: "Find this flight")
        case .needsDetails: String(localized: "Add details")
        }
    }

    private func dayLabel(_ date: LocalDate) -> String {
        date.startOfDayUTC().formatted(Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: TimeZone(secondsFromGMT: 0)!))
    }

    private func issueText(_ issue: CalendarReviewIssue) -> String {
        switch issue {
        case .noFrom: String(localized: "Departure airport not found: check it before adding.")
        case .noTo: String(localized: "Arrival airport not found: check it before adding.")
        case .sameAirport: String(localized: "The event names the same airport twice.")
        case .untimed: String(localized: "No departure time in the event.")
        case .conflict: String(localized: "The event mentions different routes.")
        case .multiLeg: String(localized: "This event covers several flights.")
        case .statedTimes: String(localized: "Times in the event are shown for checking only.")
        }
    }
}

// MARK: - B10 friend Passport compare

struct FriendCompareScreen: View {
    @ObservedObject var shell: AppShell
    let friendId: String
    let friendName: String?
    @State private var store: FriendCompareStore?

    var body: some View {
        PaperScreen {
            ScrollView {
                if let store {
                    VStack(alignment: .leading, spacing: 20) {
                        Picker(String(localized: "Period"), selection: Binding(
                            get: { store.state.tab },
                            set: { tab in Task { await store.model.select(tab) } }
                        )) {
                            Text(String(localized: "Overview")).tag(PassportTab.overview)
                            Text(String(localized: "This year")).tag(PassportTab.year)
                        }
                        .pickerStyle(.segmented)
                        content(store.state)
                    }
                    .padding(16)
                }
            }
            .refreshable { await store?.model.load() }
        }
        .navigationTitle(String(localized: "You vs \(name(store?.state.displayName ?? friendName))"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if store == nil {
                let model = FriendCompareModel(
                    friendId: friendId, friendName: friendName, mine: shell.passport, friends: shell.p3.friendPassports, clock: shell.clock
                )
                store = FriendCompareStore(model: model)
                await model.load()
            }
        }
    }

    private func name(_ value: String?) -> String {
        value ?? String(localized: "Your friend")
    }

    @ViewBuilder
    private func content(_ state: FriendCompareState) -> some View {
        if state.isLoading && state.rows.isEmpty {
            ProgressView(String(localized: "Loading…")).frame(maxWidth: .infinity)
        } else if let problem = state.problem, state.rows.isEmpty {
            Text(problemText(problem, friend: name(state.displayName)))
                .font(FlyFont.sans(15, relativeTo: .body))
        } else {
            HStack {
                Text(String(localized: "You")).font(FlyFont.serif(24, relativeTo: .title2))
                Spacer()
                Text(verbatim: name(state.displayName)).font(FlyFont.serif(24, relativeTo: .title2)).foregroundStyle(FlyColor.accent)
            }
            ForEach(state.rows, id: \.kind) { row in
                CompareRowView(row: row, label: rowLabel(row.kind), unit: row.kind == .distanceKm || row.kind == .longestKm ? "km" : nil)
            }
            if !state.sharedCountries.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Countries you have both flown to").uppercased())
                        .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2)).tracking(1.2).foregroundStyle(FlyColor.muted)
                    Text(verbatim: state.sharedCountries.map(countryName).joined(separator: ", "))
                        .font(FlyFont.sans(15, relativeTo: .body))
                }
            }
            if let mine = state.mine, let friend = state.friend {
                HStack(alignment: .top) {
                    airlines(String(localized: "Your airlines"), mine.side.airlines)
                    Spacer()
                    airlines(String(localized: "Their airlines"), friend.side.airlines)
                }
            }
            if state.limited {
                Text(String(localized: "Showing the last 12 months. HeyGate Pro compares all time."))
                    .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
            }
        }
    }

    private func airlines(_ title: String, _ list: [CountedAirline]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title.uppercased()).font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2)).tracking(1.2).foregroundStyle(FlyColor.muted)
            if list.isEmpty { Text(verbatim: "–") }
            ForEach(list, id: \.carrier) { airline in
                Text(verbatim: "\(airline.name ?? airline.carrier) · \(airline.count)").font(FlyFont.sans(14, relativeTo: .subheadline))
            }
        }
    }

    private func countryName(_ code: String) -> String {
        Locale.current.localizedString(forRegionCode: code) ?? code
    }

    private func rowLabel(_ kind: CompareKind) -> String {
        switch kind {
        case .flights: String(localized: "Flights")
        case .distanceKm: String(localized: "Distance")
        case .airports: String(localized: "Airports")
        case .countries: String(localized: "Countries")
        case .longestKm: String(localized: "Longest flight")
        }
    }

    private func problemText(_ problem: FriendCompareProblem, friend: String) -> String {
        switch problem {
        case .notShared: String(localized: "\(friend) doesn't share their Passport.")
        case .offline: String(localized: "You're offline.")
        case .notConfigured: String(localized: "Backend not configured.")
        case .failed: String(localized: "That didn't work. Try again.")
        }
    }
}

private struct CompareRowView: View {
    let row: CompareRow
    let label: String
    let unit: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label.uppercased()).font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2)).tracking(1.2).foregroundStyle(FlyColor.muted)
            HStack(spacing: 12) {
                bar(row.mineFraction, color: FlyColor.primary, value: row.mine, leading: true)
                bar(row.friendFraction, color: FlyColor.accentLine, value: row.friend, leading: false)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(label): \(text(row.mine)) / \(text(row.friend))"))
    }

    private func text(_ value: Int) -> String {
        unit.map { "\(value.formatted()) \($0)" } ?? value.formatted()
    }

    private func bar(_ fraction: Double, color: Color, value: Int, leading: Bool) -> some View {
        VStack(alignment: leading ? .leading : .trailing, spacing: 2) {
            Text(verbatim: text(value)).font(FlyFont.numeral(20)).lineLimit(1).minimumScaleFactor(0.6)
            GeometryReader { proxy in
                ZStack(alignment: leading ? .leading : .trailing) {
                    Capsule().fill(FlyColor.outlineVariant)
                    Capsule().fill(color).frame(width: max(fraction > 0 ? 4 : 0, proxy.size.width * fraction))
                }
            }
            .frame(height: 8)
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Settings: crew mode, Passport sharing, family, calendar

struct P3SettingsSection: View {
    @ObservedObject var shell: AppShell
    @State private var store: ProfileFlagsStore?

    var body: some View {
        Section {
            if let store {
                Toggle(isOn: Binding(get: { store.state.flags.crewMode }, set: { on in Task { await store.model.setCrewMode(on) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "Crew mode"))
                        Text(String(localized: "For pilots and cabin crew: no check-in, boarding or time-to-leave alerts, no Pro offers, and block times instead of flight times."))
                            .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                    }
                }
                .disabled(store.state.saving)
                Toggle(isOn: Binding(get: { store.state.flags.sharePassportWithFriends }, set: { on in Task { await store.model.setSharePassport(on) } })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "Friends can compare Passports with me"))
                        Text(String(localized: "Your totals only: flights, distance, airports, countries and top airlines."))
                            .font(FlyFont.sans(13, relativeTo: .footnote)).foregroundStyle(FlyColor.muted)
                    }
                }
                .disabled(store.state.saving)
                if let problem = store.state.problem {
                    Text(flagsProblemText(problem)).foregroundStyle(FlyColor.cancelled)
                }
            }
            Button(String(localized: "Family sharing"), systemImage: "person.3") { shell.path.append(Route.family(nil)) }
                .hitTarget()
            Button(String(localized: "Find flights in my calendar"), systemImage: "calendar") { shell.path.append(Route.calendarImport) }
                .hitTarget()
        } header: {
            Text(String(localized: "Travel and sharing"))
        }
        .task {
            if store == nil {
                let model = ProfileFlagsModel(repository: shell.p3.profileFlags)
                store = ProfileFlagsStore(model: model)
                await model.load()
            }
        }
    }

    private func flagsProblemText(_ problem: ProfileFlagsProblem) -> String {
        switch problem {
        case .offline: String(localized: "You're offline. The setting was not changed.")
        case .notConfigured: String(localized: "Backend not configured.")
        case .failed: String(localized: "The setting could not be saved. Try again.")
        }
    }
}

// MARK: - B13 family sharing

struct FamilyScreen: View {
    @ObservedObject var shell: AppShell
    let prefillCode: String?
    @State private var store: FamilyStore?
    @State private var removing: FamilyMember?
    @State private var confirmLeave = false
    @State private var shareText: FamilyShareText?

    var body: some View {
        PaperScreen {
            List {
                if let store { content(store) }
            }
            .scrollContentBackground(.hidden)
            .refreshable { await store?.model.load() }
        }
        .navigationTitle(String(localized: "Family sharing"))
        .task {
            if store == nil {
                let pro = shell.pro
                let p3 = shell.p3
                let model = FamilyModel(repository: shell.p3.family, prefillCode: prefillCode) {
                    // The family grant first: Pro is resolved from it.
                    await p3.refresh()
                    await pro.reload()
                }
                store = FamilyStore(model: model)
                await model.load()
            }
        }
        .onChange(of: store?.state.newInvite) { _, code in
            if let code {
                shareText = FamilyShareText(text: familyInviteMessage(code))
                store?.model.inviteShared()
            }
        }
        .sheet(item: $shareText) { item in FamilyActivitySheet(text: item.text) }
        .confirmationDialog(String(localized: "Remove from your family?"), isPresented: Binding(
            get: { removing != nil }, set: { if !$0 { removing = nil } }
        ), titleVisibility: .visible) {
            Button(String(localized: "Remove"), role: .destructive) {
                if let member = removing { Task { await store?.model.remove(member.userId) } }
                removing = nil
            }
        } message: {
            Text(String(localized: "They lose the Pro they get through your family."))
        }
        .confirmationDialog(String(localized: "Leave the family?"), isPresented: $confirmLeave, titleVisibility: .visible) {
            Button(String(localized: "Leave"), role: .destructive) { Task { await store?.model.leave() } }
        } message: {
            if store?.state.family?.isOwner == true {
                Text(String(localized: "This ends the family for everyone in it."))
            } else {
                Text(String(localized: "You lose the Pro you get through this family."))
            }
        }
    }

    @ViewBuilder
    private func content(_ store: FamilyStore) -> some View {
        let state = store.state
        if let joined = state.joined {
            Section {
                Text(String(localized: "You joined \(joined.ownerName ?? String(localized: "your family"))'s family. Pro is on for you now."))
            }
            .listRowBackground(FlyColor.paperRaised)
        }
        switch state.phase {
        case .loading:
            Section { ProgressView(String(localized: "Loading…")) }.listRowBackground(FlyColor.paperRaised)
        case .noFamily:
            createSection(store)
            joinSection(store)
        case .family(let info):
            familySection(info, store: store)
        }
        if let problem = state.problem {
            Section {
                Text(familyProblemText(problem)).foregroundStyle(FlyColor.cancelled)
                if case .refused(.requiresYearly) = problem, !shell.p3.crewMode {
                    Button(String(localized: "See plans")) { shell.path.append(Route.paywall) }.hitTarget()
                }
            }
            .listRowBackground(FlyColor.paperRaised)
        }
    }

    private func createSection(_ store: FamilyStore) -> some View {
        Section {
            Text(String(localized: "Share HeyGate Pro with your family"))
                .font(FlyFont.serif(24, relativeTo: .title2))
            Text(String(localized: "On a yearly Pro plan you can add up to five people. They get Pro for as long as your plan runs."))
                .font(FlyFont.sans(15, relativeTo: .body))
            Button(String(localized: "Invite family")) { Task { await store.model.createInvite() } }
                .disabled(store.state.busy)
                .hitTarget()
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    private func joinSection(_ store: FamilyStore) -> some View {
        Section(String(localized: "Join a family")) {
            TextField(String(localized: "Family code"), text: Binding(get: { store.state.codeInput }, set: { store.model.setCode($0) }))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(FlyFont.numeral(20))
            Button(store.state.busy ? String(localized: "Joining…") : String(localized: "Join")) { Task { await store.model.join() } }
                .disabled(store.state.busy || store.state.normalizedCode == nil)
                .hitTarget()
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    @ViewBuilder
    private func familySection(_ info: FamilyInfo, store: FamilyStore) -> some View {
        Section {
            if info.isOwner {
                Text(String(localized: "Your family")).font(FlyFont.serif(24, relativeTo: .title2))
                Text(info.ownerPro
                     ? String(localized: "Everyone here has Pro through your plan.")
                     : String(localized: "Your Pro has ended, so your family's Pro is paused."))
                    .font(FlyFont.sans(14, relativeTo: .subheadline))
            } else {
                Text(String(localized: "\(info.ownerName ?? String(localized: "Your family owner"))'s family"))
                    .font(FlyFont.serif(24, relativeTo: .title2))
                if info.proViaFamily {
                    Text(String(localized: "Pro via \(info.ownerName ?? String(localized: "your family owner"))'s family"))
                        .font(FlyFont.sans(14, relativeTo: .subheadline))
                } else if !info.ownerPro {
                    Text(String(localized: "The owner's Pro has ended, so the family's Pro is paused."))
                        .font(FlyFont.sans(14, relativeTo: .subheadline))
                }
            }
        }
        .listRowBackground(FlyColor.paperRaised)
        Section {
            ForEach(info.members) { member in
                HStack {
                    Text(member.displayName ?? String(localized: "Family member"))
                    Spacer()
                    if info.isOwner {
                        Button(String(localized: "Remove"), systemImage: "person.crop.circle.badge.minus", role: .destructive) { removing = member }
                            .labelStyle(.iconOnly)
                            .foregroundStyle(FlyColor.cancelled)
                            .disabled(store.state.busy)
                            .hitTarget()
                    }
                }
            }
            if info.members.isEmpty {
                Text(String(localized: "Nobody has joined yet.")).foregroundStyle(FlyColor.muted)
            }
        } header: {
            Text(String(localized: "Members (\(info.members.count) of \(info.maxMembers))"))
        }
        .listRowBackground(FlyColor.paperRaised)
        if info.isOwner {
            Section {
                if let invite = info.invite {
                    Text(verbatim: formatInviteCode(invite.code)).font(FlyFont.numeral(28))
                }
                if info.canInvite {
                    Button(String(localized: "Share invite")) { Task { await store.model.createInvite() } }
                        .disabled(store.state.busy)
                        .hitTarget()
                } else if info.seatsLeft == 0 {
                    Text(String(localized: "Your family is full."))
                }
            } header: {
                Text(String(localized: "Invite"))
            }
            .listRowBackground(FlyColor.paperRaised)
        }
        Section {
            Button(info.isOwner ? String(localized: "End family sharing") : String(localized: "Leave family"), role: .destructive) {
                confirmLeave = true
            }
            .disabled(store.state.busy)
            .hitTarget()
        }
        .listRowBackground(FlyColor.paperRaised)
    }

    private func familyInviteMessage(_ code: String) -> String {
        String(localized: "Join my HeyGate family to get Pro: open \(familyInviteLink(code: code)) or enter the code \(formatInviteCode(code)) in Settings → Family sharing.")
    }

    private func familyProblemText(_ problem: FamilyProblem) -> String {
        switch problem {
        case .offline: String(localized: "You're offline.")
        case .notConfigured: String(localized: "Backend not configured.")
        case .failed: String(localized: "That didn't work. Try again.")
        case .refused(let error):
            switch error {
            case .requiresYearly: String(localized: "Family sharing needs a yearly or lifetime Pro plan.")
            case .alreadyInFamily: String(localized: "You're already in a family. Leave it first.")
            case .invalidCode: String(localized: "That code is not valid or has expired.")
            case .ownCode: String(localized: "That's your own code. Send it to your family instead.")
            case .full: String(localized: "That family is full.")
            case .inactive: String(localized: "That family's Pro has ended.")
            case .notMember: String(localized: "That person is no longer in your family.")
            case .notOwner: String(localized: "Only the family owner can do that.")
            }
        }
    }
}

private struct FamilyShareText: Identifiable {
    let id = UUID()
    let text: String
}

private struct FamilyActivitySheet: UIViewControllerRepresentable {
    let text: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [text], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

// MARK: - B12 crew block time on the detail screen

/// "Block 2 h 16 min · off-block 18:55 · on-block 21:11" for crew, from the cached flight.
struct CrewBlockTimeView: View {
    @ObservedObject var shell: AppShell
    let flightId: String
    let revision: Date
    @State private var flight: Flight?

    var body: some View {
        Group {
            if let flight {
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Block time").uppercased())
                        .font(FlyFont.sans(11, weight: .medium, relativeTo: .caption2)).tracking(1.2).foregroundStyle(FlyColor.muted)
                    if let minutes = blockMinutes(flight) {
                        let parts = durationParts(TimeInterval(minutes * 60))
                        Text(String(localized: "\(parts.hours) h \(parts.minutes) min")).font(FlyFont.numeral(24))
                    } else {
                        Text(String(localized: "Shown once off-block and on-block times are known."))
                            .font(FlyFont.sans(14, relativeTo: .subheadline))
                    }
                    HStack(spacing: 16) {
                        if let off = flight.depOffblockAt ?? flight.actDep {
                            Text(String(localized: "Off-block \(FlightTimeFormat.time(off, tz: flight.depTz))"))
                        }
                        if let on = flight.arrOnblockAt ?? flight.actArr {
                            Text(String(localized: "On-block \(FlightTimeFormat.time(on, tz: flight.arrTz))"))
                        }
                    }
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(FlyColor.paperRaised, in: .rect(cornerRadius: 10))
                .accessibilityElement(children: .combine)
            }
        }
        .task(id: revision) { flight = await shell.flights.flight(id: flightId) }
    }
}
