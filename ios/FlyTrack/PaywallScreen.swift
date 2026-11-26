import FlyTrackCore
import SwiftUI

/// HeyGate Pro. Prices are the App Store's own strings from the RevenueCat `default` offering. Without a RevenueCat
/// key (or with no products yet) the screen says "Pro coming soon" and the rest of the app keeps working.
struct PaywallScreen: View {
    @ObservedObject var shell: AppShell
    @State private var store: PaywallStore?
    @Environment(\.openURL) private var openURL

    var body: some View {
        PaperScreen {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(String(localized: "HeyGate Pro"))
                        .font(FlyFont.serif(34, relativeTo: .largeTitle))
                        .accessibilityAddTraits(.isHeader)
                    Text(String(localized: "Know before the airline tells you."))
                        .font(FlyFont.serifItalic(20, relativeTo: .title3))
                    if let store {
                        if store.state.pro.isPro { ProStatusView(state: store.state.pro) }
                        benefits
                        content(store)
                    } else {
                        ProgressView(String(localized: "Loading…"))
                    }
                    footer
                }
                .padding(20)
            }
        }
        .navigationTitle(String(localized: "Pro"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if store == nil { store = PaywallStore(model: PaywallModel(pro: shell.pro.service)) }
            await store?.load()
        }
    }

    private var benefits: some View {
        VStack(alignment: .leading, spacing: 14) {
            benefit("airplane.arrival", String(localized: "Where's my plane"),
                    String(localized: "See the aircraft's previous leg and when it really reaches your gate."))
            benefit("bell.badge", String(localized: "Late-aircraft alerts"),
                    String(localized: "A push when the incoming plane will make your flight late, often before the airline says so."))
            benefit("lock.iphone", String(localized: "Live Activity"),
                    String(localized: "Your flight on the Lock Screen, from check-in to the baggage belt."))
            benefit("person.3", String(localized: "Family sharing"),
                    String(localized: "On a yearly plan, share Pro with up to five family members."))
        }
    }

    private func benefit(_ symbol: String, _ title: String, _ body: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .foregroundStyle(FlyColor.accentLine)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(FlyFont.sans(16, weight: .medium, relativeTo: .headline))
                Text(body).font(FlyFont.sans(14, relativeTo: .subheadline)).foregroundStyle(FlyColor.muted)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func content(_ store: PaywallStore) -> some View {
        switch store.state.phase {
        case .loading:
            ProgressView()
        case .comingSoon:
            notice(String(localized: "Pro coming soon"),
                   String(localized: "You can't buy Pro in this version yet. Everything else in HeyGate keeps working."))
        case .loadFailed, .offline:
            notice(String(localized: "Couldn't load the plans"),
                   store.state.phase == .offline
                       ? String(localized: "You're offline. Connect and try again.")
                       : String(localized: "The App Store didn't answer. Please try again in a moment."))
            Button(String(localized: "Try again")) { Task { await store.load() } }
                .buttonStyle(.bordered)
                .hitTarget()
        case .ready:
            plans(store)
        }
        if let message = store.state.message {
            Text(paywallMessage(message))
                .font(FlyFont.sans(15, relativeTo: .body))
                .foregroundStyle(isProblem(message) ? FlyColor.cancelled : FlyColor.onTime)
        }
    }

    private func plans(_ store: PaywallStore) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "Choose a plan"))
                .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                .textCase(.uppercase)
                .tracking(1.2)
            ForEach(store.state.plans, id: \.kind) { plan in
                PlanRow(plan: plan, selected: store.state.selected == plan.kind) { store.select(plan.kind) }
            }
            Button {
                Task {
                    await store.purchase()
                    await shell.proChanged()
                }
            } label: {
                HStack {
                    if store.state.busy { ProgressView().tint(FlyColor.paper) }
                    Text(String(localized: "Continue"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .foregroundStyle(FlyColor.paper)
            .disabled(store.state.busy || store.state.selected == nil)
            .hitTarget()
            Button(String(localized: "Restore purchases")) {
                Task {
                    await store.restore()
                    await shell.proChanged()
                }
            }
            .disabled(store.state.busy)
            .hitTarget()
            if store.state.pro.isPro, store.state.pro.source != .tripPass {
                Button(String(localized: "Manage subscription")) {
                    if let url = URL(string: "https://apps.apple.com/account/subscriptions") { openURL(url) }
                }
                .hitTarget()
            }
        }
    }

    private func notice(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(FlyFont.serifItalic(22, relativeTo: .title3))
            Text(body).font(FlyFont.sans(15, relativeTo: .body))
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FlyColor.paperRaised, in: .rect(cornerRadius: 12))
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(String(localized: "Subscriptions renew automatically until you cancel them in your Apple account settings. The trip pass is a one-time purchase and does not renew."))
                .font(FlyFont.sans(12, relativeTo: .caption))
                .foregroundStyle(FlyColor.muted)
            HStack(spacing: 16) {
                Link(String(localized: "Terms of use"), destination: URL(string: "https://heygate.app/terms")!)
                Link(String(localized: "Privacy policy"), destination: URL(string: "https://heygate.app/privacy")!)
            }
            .font(FlyFont.sans(13, relativeTo: .footnote))
        }
    }

    private func isProblem(_ message: PaywallMessage) -> Bool {
        switch message {
        case .purchased, .restored, .pending: return false
        default: return true
        }
    }
}

private struct PlanRow: View {
    var plan: PaywallPlan
    var selected: Bool
    var choose: () -> Void

    var body: some View {
        Button(action: choose) {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(selected ? FlyColor.primary : FlyColor.muted)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(planTitle(plan.kind)).font(FlyFont.sans(17, weight: .medium, relativeTo: .headline))
                        if let saving = plan.savingPercent {
                            Text(String(localized: "Save \(saving)%"))
                                .font(FlyFont.sans(12, weight: .medium, relativeTo: .caption))
                                .foregroundStyle(FlyColor.accent)
                        }
                    }
                    Text(planPrice(plan)).font(FlyFont.sans(14, relativeTo: .subheadline))
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

/// "You're on Pro", until when and from what. Shared by the paywall and Settings.
struct ProStatusView: View {
    var state: ProState

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(localized: "You're on Pro")).font(FlyFont.serifItalic(22, relativeTo: .title3))
            Text(proUntilText(state)).font(FlyFont.sans(15, relativeTo: .body))
            if !state.confirmedByServer {
                Text(String(localized: "Confirming your purchase with HeyGate…"))
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.muted)
            }
        }
    }
}

func proUntilText(_ state: ProState) -> String {
    // P3 B13: a family's Pro runs as long as the owner's plan.
    if state.isViaFamily {
        guard let owner = state.familyOwnerName else { return String(localized: "Pro via your family") }
        return String(localized: "Pro via \(owner)'s family")
    }
    guard let until = state.proUntil else { return String(localized: "Pro, with no end date") }
    let date = until.formatted(date: .abbreviated, time: .shortened)
    return state.source == .tripPass ? String(localized: "Trip pass until \(date)") : String(localized: "Pro until \(date)")
}

private func planTitle(_ kind: PlanKind) -> String {
    switch kind {
    case .annual: String(localized: "Yearly")
    case .monthly: String(localized: "Monthly")
    case .tripPass: String(localized: "Trip pass")
    }
}

private func planPrice(_ plan: PaywallPlan) -> String {
    switch plan.kind {
    case .annual:
        if let perMonth = plan.perMonth { return String(localized: "\(plan.price) a year · about \(perMonth) a month") }
        return String(localized: "\(plan.price) a year")
    case .monthly:
        return String(localized: "\(plan.price) a month")
    case .tripPass:
        return String(localized: "\(plan.price) once · Pro for \(BillingContract.tripPassDays) days, does not renew")
    }
}

private func paywallMessage(_ message: PaywallMessage) -> String {
    switch message {
    case .purchased: String(localized: "Welcome to Pro!")
    case .pending: String(localized: "Payment pending. Pro starts as soon as the App Store confirms it.")
    case .alreadyOwned: String(localized: "This Apple account already has Pro. Tap Restore purchases.")
    case .failed: String(localized: "The purchase didn't go through. Please try again.")
    case .offline: String(localized: "You're offline. Connect and try again.")
    case .notAvailable: String(localized: "Purchases aren't available right now.")
    case .restored: String(localized: "Purchases restored. You're on Pro.")
    case .nothingRestored: String(localized: "No Pro purchases found on this Apple account.")
    case .restoreFailed: String(localized: "Couldn't restore purchases. Please try again.")
    }
}

@MainActor
@Observable
final class PaywallStore {
    var state: PaywallState
    let model: PaywallModel
    init(model: PaywallModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load(); state = model.state }
    func select(_ kind: PlanKind) { model.select(kind); state = model.state }
    func purchase() async { await model.purchase(); state = model.state }
    func restore() async { await model.restore(); state = model.state }
}
