import FlyTrackCore
import Foundation
import Observation
#if canImport(RevenueCat)
import RevenueCat
#endif

/// RevenueCat when REVENUECAT_IOS_KEY is set, otherwise a gateway that says "Pro coming soon" and never crashes.
@MainActor
func makeBillingGateway() -> BillingGateway {
    #if canImport(RevenueCat)
    if let key = AppConfig.revenueCatKey { return RevenueCatBilling(apiKey: key) }
    #endif
    return UnavailableBilling()
}

#if canImport(RevenueCat)
/// purchases-ios 5.x. The app user id is the Supabase user id (P1 contract section 1). Prices come from the
/// `default` offering, never from code.
@MainActor
final class RevenueCatBilling: BillingGateway {
    private let apiKey: String
    private var latest: StoreEntitlement?
    private var packages: [PlanKind: Package] = [:]
    /// P4 section 3: the gift consumables, fetched by product id (they are in no offering and no entitlement).
    private var giftProducts: [GiftProduct: StoreProduct] = [:]
    private var listener: Task<Void, Never>?
    /// Called whenever CustomerInfo changes, so the Pro state on screen follows a renewal or an expiry.
    var onChange: (@MainActor () -> Void)?

    init(apiKey: String) { self.apiKey = apiKey }

    var isAvailable: Bool { true }

    func storeEntitlement() async -> StoreEntitlement? { latest }

    func identify(userId: String) async {
        if !Purchases.isConfigured {
            // Configuring with the id avoids creating an anonymous RevenueCat user first.
            Purchases.configure(withAPIKey: apiKey, appUserID: userId)
            startListening()
            return
        }
        startListening()
        guard Purchases.shared.appUserID != userId else { return }
        latest = nil
        packages = [:]
        do {
            let result = try await Purchases.shared.logIn(userId)
            apply(result.customerInfo)
        } catch {
            // Retried on the next identify (launch, sign-in, paywall).
        }
    }

    func loadOffer() async -> OfferResult {
        configureIfNeeded()
        do {
            let offerings = try await Purchases.shared.offerings()
            guard let offering = offerings.offering(identifier: BillingContract.offeringDefault) ?? offerings.current else {
                return .notAvailable
            }
            var found: [PlanKind: Package] = [:]
            var options: [PlanOption] = []
            for kind in PlanKind.allCases {
                guard let package = offering.package(identifier: kind.packageId) else { continue }
                let product = package.storeProduct
                found[kind] = package
                options.append(PlanOption(
                    kind: kind,
                    productId: product.productIdentifier,
                    price: StorePrice(formatted: product.localizedPriceString, amount: product.price, currencyCode: product.currencyCode ?? "")
                ))
            }
            packages = found
            return options.isEmpty ? .notAvailable : .available(options)
        } catch {
            switch Self.code(error) {
            case .networkError?, .offlineConnectionError?: return .offline
            case .configurationError?, .invalidCredentialsError?: return .notAvailable
            default: return .failed(error.localizedDescription)
            }
        }
    }

    func purchase(_ plan: PlanKind) async -> PurchaseOutcome {
        configureIfNeeded()
        if packages[plan] == nil { _ = await loadOffer() }
        guard let package = packages[plan] else { return .notAvailable }
        do {
            let result = try await Purchases.shared.purchase(package: package)
            apply(result.customerInfo)
            return result.userCancelled ? .cancelled : .purchased
        } catch {
            switch Self.code(error) {
            case .purchaseCancelledError?: return .cancelled
            case .paymentPendingError?: return .pending
            case .productAlreadyPurchasedError?: return .alreadyOwned
            case .networkError?, .offlineConnectionError?: return .offline
            case .purchaseNotAllowedError?, .configurationError?, .productNotAvailableForPurchaseError?: return .notAvailable
            default: return .failed(error.localizedDescription)
            }
        }
    }

    func restore() async -> RestoreOutcome {
        configureIfNeeded()
        do {
            let info = try await Purchases.shared.restorePurchases()
            apply(info)
            guard let latest else { return .restored(hasPro: false) }
            return .restored(hasPro: EntitlementRules.storeGrantsPro(latest, now: Date()))
        } catch {
            switch Self.code(error) {
            case .networkError?, .offlineConnectionError?: return .offline
            case .purchaseNotAllowedError?, .configurationError?: return .notAvailable
            default: return .failed(error.localizedDescription)
            }
        }
    }

    /// Only reached without a signed-in user (no backend): an anonymous RevenueCat user, replaced by logIn later.
    private func configureIfNeeded() {
        guard !Purchases.isConfigured else { return }
        Purchases.configure(withAPIKey: apiKey)
        startListening()
    }

    private func startListening() {
        guard listener == nil else { return }
        listener = Task { [weak self] in
            for await info in Purchases.shared.customerInfoStream {
                self?.apply(info)
            }
        }
    }

    private func apply(_ info: CustomerInfo) {
        let pro = info.entitlements[BillingContract.entitlementPro]
        latest = StoreEntitlement(
            appUserId: Purchases.shared.appUserID,
            proEntitlementActive: pro?.isActive == true,
            proExpiresAt: pro?.expirationDate,
            nonSubscriptionPurchases: info.nonSubscriptions.map {
                StorePurchase(productId: $0.productIdentifier, purchasedAt: $0.purchaseDate)
            }
        )
        onChange?()
    }

    private static func code(_ error: Error) -> RevenueCat.ErrorCode? { (error as NSError).asErrorCode }
}

/// P4 section 3: Gift Pro. The purchase never grants Pro to the buyer (no entitlement); RevenueCat's webhook turns the
/// transaction into a code, which the gift tab then reads from `my_gifts()`.
extension RevenueCatBilling: GiftStore {
    func loadGiftOffer() async -> GiftOfferResult {
        configureIfNeeded()
        let products = await Purchases.shared.products(GiftProduct.allCases.map(\.productId))
        var found: [GiftProduct: StoreProduct] = [:]
        var options: [GiftOption] = []
        for product in products {
            guard let gift = GiftProduct(productId: product.productIdentifier), found[gift] == nil else { continue }
            found[gift] = product
            options.append(GiftOption(
                product: gift,
                price: StorePrice(formatted: product.localizedPriceString, amount: product.price, currencyCode: product.currencyCode ?? "")
            ))
        }
        giftProducts = found
        return options.isEmpty ? .notAvailable : .available(options)
    }

    func purchaseGift(_ product: GiftProduct) async -> PurchaseOutcome {
        configureIfNeeded()
        if giftProducts[product] == nil { _ = await loadGiftOffer() }
        guard let item = giftProducts[product] else { return .notAvailable }
        do {
            let result = try await Purchases.shared.purchase(product: item)
            apply(result.customerInfo)
            return result.userCancelled ? .cancelled : .purchased
        } catch {
            switch Self.code(error) {
            case .purchaseCancelledError?: return .cancelled
            case .paymentPendingError?: return .pending
            case .networkError?, .offlineConnectionError?: return .offline
            case .purchaseNotAllowedError?, .configurationError?, .productNotAvailableForPurchaseError?: return .notAvailable
            default: return .failed(error.localizedDescription)
            }
        }
    }
}
#endif

/// The store for the paywall's Gift Pro tab: RevenueCat when configured, otherwise "Gifts coming soon".
@MainActor
func makeGiftStore(_ billing: BillingGateway) -> GiftStore {
    (billing as? GiftStore) ?? UnavailableBilling()
}

/// The Pro state every screen reads. Reloaded at launch, after sign-in changes, after a purchase or restore, and when
/// RevenueCat reports new CustomerInfo.
@MainActor
@Observable
final class ProCenter {
    private(set) var state: ProState = .free
    let service: ProService

    init(service: ProService) { self.service = service }

    func reload() async {
        let next = await service.proState()
        if next != state { state = next }
    }

    /// Makes the RevenueCat user the Supabase user, then reloads.
    func identifyAndReload() async {
        await service.identifyCurrentUser()
        await reload()
    }
}
