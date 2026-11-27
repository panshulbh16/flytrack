import Foundation

// P4 section 3 (docs/P4_CONTRACTS.md, notes in section 7): Gift Pro. Two consumables bought through RevenueCat
// (neither attached to the `pro` entitlement); the webhook turns each transaction into one code `XXXX-XXXX-XXXX`
// from the invite alphabet. The buyer shares the code; anyone else redeems it with `redeem_gift`. Same rules as
// Android's `feature/gift`.

public enum GiftContract {
    public static let productYear = "heygate_gift_year"
    public static let productTrip = "heygate_gift_trip"
    public static let yearDays = 365
    public static let tripDays = 7
    public static let rpcMyGifts = "my_gifts"
    public static let rpcRedeem = "redeem_gift"
    /// `redeem_gift` allows this many failed attempts per hour.
    public static let maxFailuresPerHour = 10
    /// After a purchase the code shows up in `my_gifts()` once RevenueCat's webhook arrives (usually seconds).
    public static let pollDelays: [TimeInterval] = [1, 2, 3, 4, 5, 5, 10]
    /// When the codes before a purchase could not be read, a code counts as the new one only if the server created it
    /// at most this long before the purchase started (allows for the phone's clock being a little ahead).
    public static let unknownBeforeWindow: TimeInterval = 5 * 60
}

public enum GiftProduct: String, CaseIterable, Sendable, Equatable {
    case year, trip

    public var productId: String {
        switch self {
        case .year: return GiftContract.productYear
        case .trip: return GiftContract.productTrip
        }
    }

    public var days: Int {
        switch self {
        case .year: return GiftContract.yearDays
        case .trip: return GiftContract.tripDays
        }
    }

    /// The product for a store id; App Store ids may carry a ":base-plan" suffix on other stores, so only the part
    /// before ":" counts.
    public init?(productId: String) {
        let base = productId.split(separator: ":", maxSplits: 1).first.map(String.init) ?? productId
        guard let match = GiftProduct.allCases.first(where: { $0.productId == base }) else { return nil }
        self = match
    }
}

// MARK: - codes

/// The code's characters: the invite alphabet (no 0, O, 1, I, L).
public let giftCodeAlphabet = InviteCode.alphabet
private let giftCodeCharacters = Set(giftCodeAlphabet)

/// "abcd efgh-jkmn" → "ABCD-EFGH-JKMN". Spaces, dashes and case are ignored (the server does the same); nil unless
/// exactly 12 characters of the alphabet remain.
public func normalizeGiftCode(_ input: String) -> String? {
    let raw = input.uppercased().filter { !$0.isWhitespace && $0 != "-" && $0 != "\u{2010}" && $0 != "\u{2011}" && $0 != "\u{2013}" }
    guard raw.count == 12, raw.allSatisfy({ giftCodeCharacters.contains($0) }) else { return nil }
    return formatGiftCode(raw)
}

/// Dashes after every four characters of what is there ("ABCDEF" → "ABCD-EF").
public func formatGiftCode(_ raw: String) -> String {
    var out = ""
    for (index, character) in raw.enumerated() {
        if index > 0, index % 4 == 0 { out.append("-") }
        out.append(character)
    }
    return out
}

/// The text field while typing: upper-case alphabet characters only, at most 12, dashed.
public func giftCodeInput(_ value: String) -> String {
    let raw = String(value.uppercased().filter { giftCodeCharacters.contains($0) }.prefix(12))
    return formatGiftCode(raw)
}

/// The code inside a pasted message ("I've gifted you HeyGate Pro! Code: ABCD-EFGH-JKMN"). Only the dashed form is
/// taken from free text, so ordinary words are never read as a code; a bare 12-character paste also works.
public func extractGiftCode(_ text: String) -> String? {
    if let whole = normalizeGiftCode(text) { return whole }
    let a = "[\(giftCodeAlphabet)]"
    let pattern = "(?<![A-Z0-9-])\(a){4}-\(a){4}-\(a){4}(?![A-Z0-9-])"
    let upper = text.uppercased()
    guard let found = RE.matches(pattern, in: upper).first else { return nil }
    return normalizeGiftCode(found.0.substring(with: found.1.range))
}

/// `heygate://gift?code=ABCD-EFGH-JKMN`: opens "Redeem a gift code" with the code filled in.
public func giftRedeemLink(code: String) -> String { "heygate://gift?code=\(code)" }

/// The code of a `heygate://gift?code=...` link, nil for other links or a malformed code.
public func giftCode(fromURL url: URL) -> String? {
    guard url.scheme?.lowercased() == TripItContract.callbackScheme, url.host?.lowercased() == "gift" else { return nil }
    let value = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value
    return value.flatMap(normalizeGiftCode)
}

// MARK: - my gifts

public enum GiftStatus: String, Sendable, Equatable {
    case unused, redeemed, voided
}

/// One `my_gifts()` item.
public struct Gift: Hashable, Sendable, Identifiable {
    public var code: String
    public var productId: String
    public var days: Int
    public var status: GiftStatus
    public var createdAt: Date?
    public var redeemedAt: Date?

    public var id: String { code }
    public var product: GiftProduct? { GiftProduct(productId: productId) }

    public init(code: String, productId: String, days: Int, status: GiftStatus, createdAt: Date?, redeemedAt: Date?) {
        self.code = code
        self.productId = productId
        self.days = days
        self.status = status
        self.createdAt = createdAt
        self.redeemedAt = redeemedAt
    }
}

/// `my_gifts()`, newest first as the server sends it. Items without a code are skipped; an unknown status reads as
/// unused only when nothing says otherwise. Nil when the body is not a list.
public func parseMyGifts(_ data: Data) -> [Gift]? {
    if rpcIsNull(data) { return [] }
    guard let list = (try? JSONSerialization.jsonObject(with: data)) as? [Any] else { return nil }
    return list.compactMap { $0 as? [String: Any] }.compactMap { o -> Gift? in
        guard let code = JSON.string(o["code"]).flatMap(normalizeGiftCode) else { return nil }
        let productId = JSON.string(o["product_id"]) ?? ""
        let redeemedAt = parseInstant(JSON.string(o["redeemed_at"]))
        let status = JSON.string(o["status"]).flatMap(GiftStatus.init(rawValue:)) ?? (redeemedAt != nil ? .redeemed : .unused)
        return Gift(
            code: code,
            productId: productId,
            days: JSON.int(o["days"]) ?? GiftProduct(productId: productId)?.days ?? 0,
            status: status,
            createdAt: parseInstant(JSON.string(o["created_at"])),
            redeemedAt: redeemedAt
        )
    }
}

// MARK: - redeem

public enum RedeemGiftResult: Equatable, Sendable {
    case redeemed(proUntil: Date?, days: Int)
    case invalidCode
    case alreadyRedeemed
    case voided
    /// "You can't redeem your own gift": share it instead.
    case ownGift
    /// 10 failures in the last hour.
    case rateLimited
    /// The caller already has Pro without an end; the code is not used up.
    case lifetimePro
    case offline
    case notConfigured
    case failed
}

/// `redeem_gift` answers `{pro_until, days}` or `{error}`; it never raises for a bad code.
public func parseRedeemGift(_ data: Data) -> RedeemGiftResult {
    guard let o = JSON.object(from: data) else { return .failed }
    if let error = JSON.string(o["error"]) { return redeemGiftError(error) }
    guard let days = JSON.int(o["days"]) else { return .failed }
    return .redeemed(proUntil: parseInstant(JSON.string(o["pro_until"])), days: days)
}

/// The contract's error names, also the SQLSTATEs the contract lists in case a server version raises instead.
public func redeemGiftError(_ code: String) -> RedeemGiftResult {
    switch code {
    case "invalid_code", "P0002": return .invalidCode
    case "already_redeemed", "23505": return .alreadyRedeemed
    case "voided": return .voided
    case "own_gift": return .ownGift
    case "rate_limited": return .rateLimited
    case "lifetime_pro": return .lifetimePro
    default: return .failed
    }
}

@MainActor
public protocol GiftsRepository: AnyObject {
    func myGifts() async -> RemoteResult<[Gift]>
    /// `code` is already normalised.
    func redeem(code: String) async -> RedeemGiftResult
}

// MARK: - store

public struct GiftOption: Hashable, Sendable {
    public var product: GiftProduct
    public var price: StorePrice

    public init(product: GiftProduct, price: StorePrice) {
        self.product = product
        self.price = price
    }
}

public enum GiftOfferResult: Equatable, Sendable {
    /// Sorted year, trip.
    case available([GiftOption])
    case notAvailable
    case offline
    case failed(String?)
}

/// The two gift consumables (app: `RevenueCatBilling` fetches them by product id, so they need no offering).
@MainActor
public protocol GiftStore: AnyObject {
    var isAvailable: Bool { get }
    func loadGiftOffer() async -> GiftOfferResult
    func purchaseGift(_ product: GiftProduct) async -> PurchaseOutcome
}

extension UnavailableBilling: GiftStore {
    public func loadGiftOffer() async -> GiftOfferResult { .notAvailable }
    public func purchaseGift(_ product: GiftProduct) async -> PurchaseOutcome { .notAvailable }
}

// MARK: - gift tab

public enum GiftTabPhase: Equatable, Sendable {
    case loading
    /// No RevenueCat key or no gift products yet: "Gifts coming soon".
    case comingSoon
    case offline
    case loadFailed
    case ready
}

public enum GiftPurchaseMessage: Equatable, Sendable {
    case cancelled
    case pending
    /// Bought, but the code has not reached `my_gifts()` yet: "It will appear in My gifts in a minute."
    case codeDelayed
    case offline
    case failed
    case notAvailable
}

public struct GiftOptionRow: Hashable, Sendable {
    public var product: GiftProduct
    public var price: String
    public var days: Int
}

public struct GiftTabState: Equatable, Sendable {
    public var phase: GiftTabPhase = .loading
    public var options: [GiftOptionRow] = []
    public var selected: GiftProduct?
    public var busy = false
    /// The code just bought, shown big with Share / WhatsApp.
    public var newGift: Gift?
    public var message: GiftPurchaseMessage?

    public init() {}
}

/// The paywall's "Gift Pro" tab: buy a gift, then wait for its code.
@MainActor
public final class GiftPurchaseModel {
    public private(set) var state = GiftTabState()
    public var onChange: ((GiftTabState) -> Void)?

    private let store: GiftStore
    private let gifts: GiftsRepository
    private let identify: @MainActor () async -> Void
    private let pollDelays: [TimeInterval]
    private let sleep: @Sendable (TimeInterval) async -> Void
    private let now: @Sendable () -> Date

    public init(
        store: GiftStore, gifts: GiftsRepository, identify: @escaping @MainActor () async -> Void = {},
        pollDelays: [TimeInterval] = GiftContract.pollDelays,
        sleep: @escaping @Sendable (TimeInterval) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.store = store
        self.gifts = gifts
        self.identify = identify
        self.pollDelays = pollDelays
        self.sleep = sleep
        self.now = now
    }

    public func load() async {
        guard store.isAvailable else {
            state.phase = .comingSoon
            publish()
            return
        }
        state.phase = .loading
        publish()
        await identify()
        switch await store.loadGiftOffer() {
        case .available(let options):
            state.options = GiftProduct.allCases.compactMap { product in
                options.first(where: { $0.product == product }).map { GiftOptionRow(product: product, price: $0.price.formatted, days: product.days) }
            }
            state.phase = state.options.isEmpty ? .comingSoon : .ready
            if state.selected == nil || !state.options.contains(where: { $0.product == state.selected }) {
                state.selected = state.options.first?.product
            }
        case .notAvailable: state.phase = .comingSoon
        case .offline: state.phase = .offline
        case .failed: state.phase = .loadFailed
        }
        publish()
    }

    public func select(_ product: GiftProduct) {
        guard state.options.contains(where: { $0.product == product }) else { return }
        state.selected = product
        publish()
    }

    /// Buys the selected gift, then polls `my_gifts()` for its code: the newest unused code of that product that was
    /// not there before the purchase.
    public func purchase() async {
        guard !state.busy, let product = state.selected else { return }
        state.busy = true
        state.message = nil
        state.newGift = nil
        publish()
        await identify()
        // The codes that already exist. When that list cannot be read, only a code created around this purchase counts,
        // so an older unused gift is never shown as the one just bought.
        let before = await gifts.myGifts().value.map { Set($0.map(\.code)) }
        let startedAt = now()
        let outcome = await store.purchaseGift(product)
        switch outcome {
        case .purchased:
            if let gift = await waitForCode(product: product, before: before, startedAt: startedAt) {
                state.newGift = gift
            } else {
                state.message = .codeDelayed
            }
        case .cancelled: state.message = .cancelled
        case .pending: state.message = .pending
        case .offline: state.message = .offline
        case .notAvailable: state.message = .notAvailable
        case .alreadyOwned, .failed: state.message = .failed
        }
        state.busy = false
        publish()
    }

    public func messageShown() {
        state.message = nil
        publish()
    }

    public func dismissNewGift() {
        state.newGift = nil
        publish()
    }

    private func waitForCode(product: GiftProduct, before: Set<String>?, startedAt: Date) async -> Gift? {
        func isNew(_ gift: Gift) -> Bool {
            guard gift.status == .unused, gift.product == product else { return false }
            if let before { return !before.contains(gift.code) }
            guard let created = gift.createdAt else { return false }
            return created >= startedAt.addingTimeInterval(-GiftContract.unknownBeforeWindow)
        }
        for delay in [0] + pollDelays {
            if delay > 0 { await sleep(delay) }
            if let list = await gifts.myGifts().value, let gift = list.first(where: isNew) {
                return gift
            }
        }
        return nil
    }

    private func publish() { onChange?(state) }
}

// MARK: - redeem screen

public struct RedeemGiftState: Equatable, Sendable {
    public var input = ""
    public var busy = false
    public var result: RedeemGiftResult?

    public init() {}

    /// The button is enabled once the field holds a whole code.
    public var canSubmit: Bool { !busy && normalizeGiftCode(input) != nil }
}

/// Settings → "Redeem a gift code".
@MainActor
public final class RedeemGiftModel {
    public private(set) var state = RedeemGiftState()
    public var onChange: ((RedeemGiftState) -> Void)?

    private let gifts: GiftsRepository
    /// Runs after a redemption: the profile refresh that makes every screen see Pro.
    private let onRedeemed: @MainActor () async -> Void

    public init(gifts: GiftsRepository, prefill: String? = nil, onRedeemed: @escaping @MainActor () async -> Void) {
        self.gifts = gifts
        self.onRedeemed = onRedeemed
        if let prefill, let code = extractGiftCode(prefill) { state.input = code }
    }

    public func setInput(_ value: String) {
        // A pasted message keeps only its code.
        let next = extractGiftCode(value) ?? giftCodeInput(value)
        guard next != state.input else { return }
        state.input = next
        state.result = nil
        publish()
    }

    public func submit() async {
        guard !state.busy else { return }
        guard let code = normalizeGiftCode(state.input) else {
            state.result = .invalidCode
            publish()
            return
        }
        state.busy = true
        state.result = nil
        publish()
        let result = await gifts.redeem(code: code)
        if case .redeemed = result { await onRedeemed() }
        state.busy = false
        state.result = result
        publish()
    }

    private func publish() { onChange?(state) }
}

// MARK: - my gifts screen

public enum MyGiftsPhase: Equatable, Sendable {
    case loading
    case ready
    case offline
    case failed
    case notConfigured
}

public struct MyGiftsState: Equatable, Sendable {
    public var phase: MyGiftsPhase = .loading
    public var gifts: [Gift] = []

    public init() {}

    public var unused: [Gift] { gifts.filter { $0.status == .unused } }
    public var used: [Gift] { gifts.filter { $0.status != .unused } }
}

/// Settings → "My gifts". Errors keep the list already shown.
@MainActor
public final class MyGiftsModel {
    public private(set) var state = MyGiftsState()
    public var onChange: ((MyGiftsState) -> Void)?

    private let gifts: GiftsRepository

    public init(gifts: GiftsRepository) { self.gifts = gifts }

    public func load() async {
        switch await gifts.myGifts() {
        case .ok(let list):
            state.gifts = list
            state.phase = .ready
        case .offline: if state.gifts.isEmpty { state.phase = .offline }
        case .notConfigured: state.phase = .notConfigured
        case .failed, .refused: if state.gifts.isEmpty { state.phase = .failed }
        }
        publish()
    }

    private func publish() { onChange?(state) }
}
