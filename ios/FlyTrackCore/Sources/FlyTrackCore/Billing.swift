import Foundation

/// RevenueCat and backend names from docs/P1_CONTRACTS.md section 1. They must match the RevenueCat dashboard,
/// App Store Connect and the backend exactly.
///
/// Prices are deliberately absent: they live in App Store Connect and reach the app through RevenueCat
/// offerings, already formatted for the user's storefront.
public enum BillingContract {
    /// Granted by both subscriptions, never by the trip pass.
    public static let entitlementPro = "pro"
    public static let offeringDefault = "default"
    public static let packageAnnual = "$rc_annual"
    public static let packageMonthly = "$rc_monthly"
    public static let packageTripPass = "trip_pass"
    public static let productAnnual = "heygate_pro_yearly"
    public static let productMonthly = "heygate_pro_monthly"
    /// Consumable. Each purchase grants Pro for `tripPassDays` from its purchase date.
    public static let productTripPass = "heygate_trip_pass"
    public static let tripPassDays = 7
    public static let tripPassDuration: TimeInterval = TimeInterval(tripPassDays) * 24 * 3600
    /// Edge Function the app calls after a purchase or restore (user JWT, POST).
    public static let functionSyncEntitlement = "sync-entitlement"
}

/// One `CustomerInfo.nonSubscriptions` entry.
public struct StorePurchase: Hashable, Sendable {
    public var productId: String
    public var purchasedAt: Date

    public init(productId: String, purchasedAt: Date) {
        self.productId = productId
        self.purchasedAt = purchasedAt
    }
}

/// What RevenueCat's CustomerInfo says about one app user. Built from the SDK types in the app target.
public struct StoreEntitlement: Hashable, Sendable {
    /// The RevenueCat app user id (= the Supabase user id) this snapshot belongs to.
    public var appUserId: String
    public var proEntitlementActive: Bool
    /// Expiry of the `pro` entitlement; nil while active means lifetime.
    public var proExpiresAt: Date?
    public var nonSubscriptionPurchases: [StorePurchase]

    public init(appUserId: String, proEntitlementActive: Bool, proExpiresAt: Date?, nonSubscriptionPurchases: [StorePurchase] = []) {
        self.appUserId = appUserId
        self.proEntitlementActive = proEntitlementActive
        self.proExpiresAt = proExpiresAt
        self.nonSubscriptionPurchases = nonSubscriptionPurchases
    }
}

/// Where the user's Pro comes from.
public enum ProSource: String, Sendable, Equatable {
    case none
    /// `profiles.plan` / `pro_until`: the server's truth, also manual grants.
    case server
    /// An active `pro` entitlement the server may not have seen yet.
    case subscription
    /// A trip pass bought less than seven days ago.
    case tripPass
    /// P3 B13: a member of a family whose owner has Pro of their own ("Pro via {owner}'s family"). Never passed on.
    case family
}

/// P3 B13: the signed-in user is Pro through a family (`my_family().pro_via_family`, or sync-entitlement's
/// `via_family`). The server decides family Pro itself (`is_pro`); this only decides what the app shows.
public struct FamilyGrant: Hashable, Sendable {
    /// The user the grant was read for; a grant for another user is ignored.
    public var userId: String
    /// The owner's display name, nil when they set none.
    public var ownerName: String?

    public init(userId: String, ownerName: String?) {
        self.userId = userId
        self.ownerName = ownerName
    }
}

/// The cached family grant of the signed-in user (the app keeps it per user).
@MainActor
public protocol FamilyGrantProviding: AnyObject {
    func familyGrant() -> FamilyGrant?
}

/// Pro on one flight. `trial` shows the "Pro free for this flight" badge.
public enum FlightProAccess: String, Sendable, Equatable {
    case pro, trial, locked

    public var unlocked: Bool { self != .locked }
}

/// The Pro state the UI shows.
public struct ProState: Hashable, Sendable {
    public var isPro: Bool
    /// When Pro ends; nil while `isPro` means no end (lifetime or a manual grant).
    public var proUntil: Date?
    public var source: ProSource
    /// False while Pro is only optimistic (the store says Pro, the profile not yet). Server features start once true.
    public var confirmedByServer: Bool
    /// `profiles.trial_flight_id`.
    public var trialFlightId: String?
    /// P3 B13: the family owner's name while `source` is `.family` (nil when they set none).
    public var familyOwnerName: String?

    public init(isPro: Bool, proUntil: Date?, source: ProSource, confirmedByServer: Bool, trialFlightId: String?, familyOwnerName: String? = nil) {
        self.isPro = isPro
        self.proUntil = proUntil
        self.source = source
        self.confirmedByServer = confirmedByServer
        self.trialFlightId = trialFlightId
        self.familyOwnerName = familyOwnerName
    }

    /// "Pro via {owner}'s family" applies.
    public var isViaFamily: Bool { isPro && source == .family }

    public static let free = ProState(isPro: false, proUntil: nil, source: .none, confirmedByServer: true, trialFlightId: nil)

    public func access(for flightId: String) -> FlightProAccess {
        if isPro { return .pro }
        if let trialFlightId, trialFlightId == flightId { return .trial }
        return .locked
    }
}

/// The entitlement rules shared by the paywall, settings and the flight screens. Same as the Android app.
public enum EntitlementRules {
    /// Combines the server profile with the store's optimistic view. A store snapshot for another RevenueCat user
    /// (between a sign-in switch and `logIn` finishing) is ignored, so one account's purchase never shows on another.
    /// P3 B13: `family` (the user's cached family grant) only fills the gap when the user has no Pro of their own.
    public static func resolve(profile: UserProfile?, store: StoreEntitlement?, currentUserId: String?, now: Date, family: FamilyGrant? = nil) -> ProState {
        let ownStore = store.flatMap { snapshot in currentUserId != nil && snapshot.appUserId == currentUserId ? snapshot : nil }
        var grants: [(source: ProSource, until: Date?)] = []
        if let profile, profile.isPro(now: now) { grants.append((source: .server, until: profile.proUntil)) }
        if let ownStore {
            if let until = subscriptionUntil(ownStore, now: now) { grants.append((source: .subscription, until: until)) }
            if let until = tripPassUntil(ownStore.nonSubscriptionPurchases, now: now) { grants.append((source: .tripPass, until: until)) }
        }
        let trial = profile?.trialFlightId
        guard var best = grants.first else {
            if let family, let currentUserId, family.userId == currentUserId {
                return ProState(
                    isPro: true, proUntil: nil, source: .family, confirmedByServer: true, trialFlightId: trial,
                    familyOwnerName: family.ownerName
                )
            }
            var state = ProState.free
            state.trialFlightId = trial
            return state
        }
        // The grant that lasts longest wins; no end beats any date. Ties prefer the server.
        for grant in grants.dropFirst() where outlasts(grant, best) { best = grant }
        return ProState(
            isPro: true,
            proUntil: best.until,
            source: best.source,
            confirmedByServer: grants.contains { $0.source == .server },
            trialFlightId: trial
        )
    }

    /// End of the latest trip pass, or nil when none is running. Same rule as the backend: the latest
    /// `heygate_trip_pass` purchase + 7 days.
    public static func tripPassUntil(_ purchases: [StorePurchase], now: Date) -> Date? {
        guard let latest = purchases.filter({ isTripPass($0.productId) }).map(\.purchasedAt).max() else { return nil }
        let until = latest.addingTimeInterval(BillingContract.tripPassDuration)
        return until > now ? until : nil
    }

    public static func isTripPass(_ productId: String) -> Bool {
        productId.split(separator: ":", maxSplits: 1).first.map(String.init) == BillingContract.productTripPass
    }

    /// True when the store alone grants Pro right now (used after a restore).
    public static func storeGrantsPro(_ store: StoreEntitlement, now: Date) -> Bool {
        subscriptionUntil(store, now: now) != nil || tripPassUntil(store.nonSubscriptionPurchases, now: now) != nil
    }

    /// Wrapped so "active with no end" (`.some(nil)`) differs from "not active" (`nil`).
    private static func subscriptionUntil(_ store: StoreEntitlement, now: Date) -> Date?? {
        guard store.proEntitlementActive else { return nil }
        if let expires = store.proExpiresAt {
            if expires > now { return .some(expires) }
            return nil
        }
        return .some(nil)
    }

    private static func outlasts(_ a: (source: ProSource, until: Date?), _ b: (source: ProSource, until: Date?)) -> Bool {
        let serverTie = a.source == .server && b.source != .server
        guard let x = a.until else { return b.until == nil ? serverTie : true }
        guard let y = b.until else { return false }
        return x != y ? x > y : serverTie
    }
}

// MARK: - store

/// The three things the paywall sells.
public enum PlanKind: String, CaseIterable, Sendable, Equatable {
    case annual, monthly, tripPass

    public var packageId: String {
        switch self {
        case .annual: return BillingContract.packageAnnual
        case .monthly: return BillingContract.packageMonthly
        case .tripPass: return BillingContract.packageTripPass
        }
    }
}

/// A price from the store. `formatted` is the store's own text ("₹4,999.00") and is what the UI shows; `amount` is
/// only for derived figures (per-month price, saving).
public struct StorePrice: Hashable, Sendable {
    public var formatted: String
    public var amount: Decimal
    public var currencyCode: String

    public init(formatted: String, amount: Decimal, currencyCode: String) {
        self.formatted = formatted
        self.amount = amount
        self.currencyCode = currencyCode
    }
}

/// One purchasable package of the `default` offering.
public struct PlanOption: Hashable, Sendable {
    public var kind: PlanKind
    public var productId: String
    public var price: StorePrice

    public init(kind: PlanKind, productId: String, price: StorePrice) {
        self.kind = kind
        self.productId = productId
        self.price = price
    }
}

public enum OfferResult: Equatable, Sendable {
    /// At least one of the three packages exists, sorted annual, monthly, trip pass.
    case available([PlanOption])
    /// No RevenueCat key in this build, or the store has no products yet: "Pro coming soon".
    case notAvailable
    case offline
    case failed(String?)
}

public enum PurchaseOutcome: Equatable, Sendable {
    case purchased
    case cancelled
    /// Ask to Buy or a slow payment: the store finishes it later and the webhook updates the profile.
    case pending
    case alreadyOwned
    case notAvailable
    case offline
    case failed(String?)
}

public enum RestoreOutcome: Equatable, Sendable {
    /// `hasPro`: the restored purchases grant Pro right now.
    case restored(hasPro: Bool)
    case notAvailable
    case offline
    case failed(String?)
}

/// The store, behind a protocol: RevenueCat in a build with `REVENUECAT_IOS_KEY`, `UnavailableBilling` without one.
/// Commands never throw.
@MainActor
public protocol BillingGateway: AnyObject {
    /// False without a RevenueCat key: every command returns its notAvailable result.
    var isAvailable: Bool { get }
    /// The latest CustomerInfo of the logged-in RevenueCat user; nil before the first one.
    func storeEntitlement() async -> StoreEntitlement?
    /// Configures the SDK on first use and makes its app user id equal to the Supabase user id.
    func identify(userId: String) async
    func loadOffer() async -> OfferResult
    func purchase(_ plan: PlanKind) async -> PurchaseOutcome
    func restore() async -> RestoreOutcome
}

/// A build without a RevenueCat key. Nothing crashes; the paywall says "Pro coming soon".
@MainActor
public final class UnavailableBilling: BillingGateway {
    public init() {}
    public var isAvailable: Bool { false }
    public func storeEntitlement() async -> StoreEntitlement? { nil }
    public func identify(userId: String) async {}
    public func loadOffer() async -> OfferResult { .notAvailable }
    public func purchase(_ plan: PlanKind) async -> PurchaseOutcome { .notAvailable }
    public func restore() async -> RestoreOutcome { .notAvailable }
}

/// `POST sync-entitlement`: the server re-reads RevenueCat for the caller and updates the profile.
@MainActor
public protocol EntitlementSyncing: AnyObject {
    func syncEntitlement() async -> ActionResult
}

@MainActor
public protocol ProStateProviding: AnyObject {
    func proState() async -> ProState
}

/// Pro for the whole app: the server profile combined with the store's CustomerInfo for an optimistic "you're Pro"
/// right after a purchase. Purchase and restore always end with `sync-entitlement` and a profile refresh.
@MainActor
public final class ProService: ProStateProviding {
    private let billing: BillingGateway
    private let profiles: ProfileRepository
    private let auth: AuthRepository
    private let sync: EntitlementSyncing
    private let clock: FlyClock
    private let family: FamilyGrantProviding?

    public init(
        billing: BillingGateway, profiles: ProfileRepository, auth: AuthRepository, sync: EntitlementSyncing, clock: FlyClock,
        family: FamilyGrantProviding? = nil
    ) {
        self.billing = billing
        self.profiles = profiles
        self.auth = auth
        self.sync = sync
        self.clock = clock
        self.family = family
    }

    public var isBillingAvailable: Bool { billing.isAvailable }

    public func proState() async -> ProState {
        let userId: String?
        if case .signedIn(let id, _, _) = await auth.session() { userId = id } else { userId = nil }
        let profile = await profiles.profile()
        let store = await billing.storeEntitlement()
        return EntitlementRules.resolve(profile: profile, store: store, currentUserId: userId, now: clock.now(), family: family?.familyGrant())
    }

    /// Keeps the RevenueCat app user equal to the signed-in Supabase user (anonymous users included).
    public func identifyCurrentUser() async {
        guard billing.isAvailable, case .signedIn(let id, _, _) = await auth.session() else { return }
        await billing.identify(userId: id)
    }

    public func loadOffer() async -> OfferResult { await billing.loadOffer() }

    /// A failed sync is not an error for the user: the webhook catches up.
    public func purchase(_ plan: PlanKind) async -> PurchaseOutcome {
        await identifyCurrentUser()
        let outcome = await billing.purchase(plan)
        if outcome == .purchased { _ = await syncEntitlement() }
        return outcome
    }

    public func restore() async -> RestoreOutcome {
        await identifyCurrentUser()
        let outcome = await billing.restore()
        if case .restored = outcome { _ = await syncEntitlement() }
        return outcome
    }

    /// `sync-entitlement`, then a profile refresh so the cache (and every screen) sees the new plan. Without the
    /// backend the profile is still refreshed.
    public func syncEntitlement() async -> ActionResult {
        switch await auth.ensureSignedIn() {
        case .success: break
        case .offline: return .offline
        case .notConfigured: return .notConfigured
        case .googleAccountInUse, .failed: return .failed("sign-in")
        }
        let synced = await sync.syncEntitlement()
        let refreshed = await profiles.refresh()
        return synced.isSuccess ? refreshed : synced
    }
}

// MARK: - pricing

/// Figures derived from the store prices. Nothing here knows a price.
public enum PlanPricing {
    /// The annual price over twelve months in whole currency units ("₹417"); nil when unknown.
    public static func monthlyEquivalent(annual: StorePrice, locale: Locale = .current) -> String? {
        guard annual.amount > 0, !annual.currencyCode.isEmpty else { return nil }
        var perMonth = annual.amount / 12
        var rounded = Decimal()
        NSDecimalRound(&rounded, &perMonth, 0, .plain)
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.locale = locale
        formatter.currencyCode = annual.currencyCode
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 0
        return formatter.string(from: NSDecimalNumber(decimal: rounded))
    }

    /// The saving of the annual plan against twelve monthly payments, rounded down to a whole percent. Nil when the
    /// currencies differ, a price is unknown, or the saving is under 1%.
    public static func annualSavingPercent(annual: StorePrice, monthly: StorePrice) -> Int? {
        guard annual.currencyCode == monthly.currencyCode, annual.amount > 0, monthly.amount > 0 else { return nil }
        let year = monthly.amount * 12
        guard annual.amount < year else { return nil }
        var ratio = (year - annual.amount) * 100 / year
        var floored = Decimal()
        NSDecimalRound(&floored, &ratio, 0, .down)
        let percent = NSDecimalNumber(decimal: floored).intValue
        return percent >= 1 ? percent : nil
    }
}

// MARK: - paywall

public enum PaywallPhase: String, Sendable, Equatable {
    case loading
    /// No RevenueCat key or no products: "Pro coming soon".
    case comingSoon
    case loadFailed
    case offline
    case ready
}

public enum PaywallMessage: String, Sendable, Equatable {
    case purchased, pending, alreadyOwned, failed, offline, notAvailable, restored, nothingRestored, restoreFailed
}

/// One plan row, prices already in the store's words.
public struct PaywallPlan: Hashable, Sendable {
    public var kind: PlanKind
    public var price: String
    /// Annual only: "₹417" a month.
    public var perMonth: String?
    /// Annual only: "Save 16%".
    public var savingPercent: Int?
}

public struct PaywallState: Equatable, Sendable {
    public var phase: PaywallPhase
    public var plans: [PaywallPlan]
    public var selected: PlanKind?
    public var busy: Bool
    public var message: PaywallMessage?
    public var pro: ProState

    public init(
        phase: PaywallPhase = .loading, plans: [PaywallPlan] = [], selected: PlanKind? = nil, busy: Bool = false,
        message: PaywallMessage? = nil, pro: ProState = .free
    ) {
        self.phase = phase
        self.plans = plans
        self.selected = selected
        self.busy = busy
        self.message = message
        self.pro = pro
    }
}

@MainActor
public final class PaywallModel {
    public private(set) var state = PaywallState()
    public var onChange: ((PaywallState) -> Void)?

    private let pro: ProService
    private let locale: Locale

    public init(pro: ProService, locale: Locale = .current) {
        self.pro = pro
        self.locale = locale
    }

    public func load() async {
        state.pro = await pro.proState()
        guard pro.isBillingAvailable else {
            state.phase = .comingSoon
            publish()
            return
        }
        state.phase = .loading
        publish()
        await pro.identifyCurrentUser()
        switch await pro.loadOffer() {
        case .available(let options):
            state.plans = plans(options)
            state.phase = state.plans.isEmpty ? .comingSoon : .ready
            if state.selected == nil || !state.plans.contains(where: { $0.kind == state.selected }) {
                state.selected = state.plans.first?.kind
            }
        case .notAvailable:
            state.phase = .comingSoon
        case .offline:
            state.phase = .offline
        case .failed:
            state.phase = .loadFailed
        }
        publish()
    }

    public func select(_ kind: PlanKind) {
        guard state.plans.contains(where: { $0.kind == kind }) else { return }
        state.selected = kind
        publish()
    }

    public func purchase() async {
        guard !state.busy, state.phase == .ready, let kind = state.selected else { return }
        state.busy = true
        state.message = nil
        publish()
        let outcome = await pro.purchase(kind)
        state.busy = false
        switch outcome {
        case .purchased: state.message = .purchased
        case .cancelled: state.message = nil
        case .pending: state.message = .pending
        case .alreadyOwned: state.message = .alreadyOwned
        case .notAvailable: state.message = .notAvailable
        case .offline: state.message = .offline
        case .failed: state.message = .failed
        }
        state.pro = await pro.proState()
        publish()
    }

    public func restore() async {
        guard !state.busy else { return }
        state.busy = true
        state.message = nil
        publish()
        let outcome = await pro.restore()
        state.busy = false
        state.pro = await pro.proState()
        switch outcome {
        case .restored(let hasPro): state.message = hasPro || state.pro.isPro ? .restored : .nothingRestored
        case .notAvailable: state.message = .notAvailable
        case .offline: state.message = .offline
        case .failed: state.message = .restoreFailed
        }
        publish()
    }

    public func clearMessage() {
        state.message = nil
        publish()
    }

    private func plans(_ options: [PlanOption]) -> [PaywallPlan] {
        let monthly = options.first { $0.kind == .monthly }
        return PlanKind.allCases.compactMap { kind in
            guard let option = options.first(where: { $0.kind == kind }) else { return nil }
            if kind == .annual {
                return PaywallPlan(
                    kind: kind,
                    price: option.price.formatted,
                    perMonth: PlanPricing.monthlyEquivalent(annual: option.price, locale: locale),
                    savingPercent: monthly.flatMap { PlanPricing.annualSavingPercent(annual: option.price, monthly: $0.price) }
                )
            }
            return PaywallPlan(kind: kind, price: option.price.formatted, perMonth: nil, savingPercent: nil)
        }
    }

    private func publish() { onChange?(state) }
}
