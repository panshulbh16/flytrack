import Foundation
import XCTest
@testable import FlyTrackCore

private let now = parseInstant("2026-11-26T10:00:00Z")!
private let day: TimeInterval = 24 * 3600

final class EntitlementRulesTests: XCTestCase {
    func testFreeProfileWithoutStoreIsFree() {
        let profile = UserProfile(id: "u1", displayName: nil, plan: .free, proUntil: nil, trialFlightId: "f9")
        let state = EntitlementRules.resolve(profile: profile, store: nil, currentUserId: "u1", now: now)
        XCTAssertFalse(state.isPro)
        XCTAssertEqual(state.source, .none)
        XCTAssertEqual(state.trialFlightId, "f9")
        XCTAssertEqual(state.access(for: "f9"), .trial)
        XCTAssertEqual(state.access(for: "f1"), .locked)
    }

    func testServerProWins() {
        let until = now.addingTimeInterval(30 * day)
        let profile = UserProfile(id: "u1", displayName: nil, plan: .pro, proUntil: until)
        let state = EntitlementRules.resolve(profile: profile, store: nil, currentUserId: "u1", now: now)
        XCTAssertTrue(state.isPro)
        XCTAssertEqual(state.source, .server)
        XCTAssertEqual(state.proUntil, until)
        XCTAssertTrue(state.confirmedByServer)
        XCTAssertEqual(state.access(for: "any"), .pro)
    }

    func testExpiredServerProIsFree() {
        let profile = UserProfile(id: "u1", displayName: nil, plan: .pro, proUntil: now.addingTimeInterval(-60))
        XCTAssertFalse(EntitlementRules.resolve(profile: profile, store: nil, currentUserId: "u1", now: now).isPro)
    }

    func testStoreSubscriptionIsOptimisticUntilServerConfirms() {
        let store = StoreEntitlement(appUserId: "u1", proEntitlementActive: true, proExpiresAt: now.addingTimeInterval(365 * day))
        let state = EntitlementRules.resolve(profile: nil, store: store, currentUserId: "u1", now: now)
        XCTAssertTrue(state.isPro)
        XCTAssertEqual(state.source, .subscription)
        XCTAssertFalse(state.confirmedByServer)
    }

    func testStoreOfAnotherUserIsIgnored() {
        let store = StoreEntitlement(appUserId: "old-user", proEntitlementActive: true, proExpiresAt: nil)
        XCTAssertFalse(EntitlementRules.resolve(profile: nil, store: store, currentUserId: "u1", now: now).isPro)
        XCTAssertFalse(EntitlementRules.resolve(profile: nil, store: store, currentUserId: nil, now: now).isPro)
    }

    func testTripPassLastsSevenDaysFromLatestPurchase() {
        let purchases = [
            StorePurchase(productId: "heygate_trip_pass", purchasedAt: now.addingTimeInterval(-10 * day)),
            StorePurchase(productId: "heygate_trip_pass", purchasedAt: now.addingTimeInterval(-2 * day)),
            StorePurchase(productId: "something_else", purchasedAt: now),
        ]
        XCTAssertEqual(EntitlementRules.tripPassUntil(purchases, now: now), now.addingTimeInterval(5 * day))
        let store = StoreEntitlement(appUserId: "u1", proEntitlementActive: false, proExpiresAt: nil, nonSubscriptionPurchases: purchases)
        let state = EntitlementRules.resolve(profile: nil, store: store, currentUserId: "u1", now: now)
        XCTAssertTrue(state.isPro)
        XCTAssertEqual(state.source, .tripPass)
        XCTAssertEqual(state.proUntil, now.addingTimeInterval(5 * day))
    }

    func testExpiredTripPassGrantsNothing() {
        let purchases = [StorePurchase(productId: "heygate_trip_pass", purchasedAt: now.addingTimeInterval(-8 * day))]
        XCTAssertNil(EntitlementRules.tripPassUntil(purchases, now: now))
        let store = StoreEntitlement(appUserId: "u1", proEntitlementActive: false, proExpiresAt: nil, nonSubscriptionPurchases: purchases)
        XCTAssertFalse(EntitlementRules.storeGrantsPro(store, now: now))
    }

    func testLongestGrantWinsAndNoEndBeatsAnyDate() {
        let profile = UserProfile(id: "u1", displayName: nil, plan: .pro, proUntil: now.addingTimeInterval(3 * day))
        let trip = StoreEntitlement(appUserId: "u1", proEntitlementActive: false, proExpiresAt: nil, nonSubscriptionPurchases: [
            StorePurchase(productId: "heygate_trip_pass", purchasedAt: now),
        ])
        let byTrip = EntitlementRules.resolve(profile: profile, store: trip, currentUserId: "u1", now: now)
        XCTAssertEqual(byTrip.source, .tripPass)
        XCTAssertTrue(byTrip.confirmedByServer)

        let lifetime = StoreEntitlement(appUserId: "u1", proEntitlementActive: true, proExpiresAt: nil)
        let byLifetime = EntitlementRules.resolve(profile: profile, store: lifetime, currentUserId: "u1", now: now)
        XCTAssertEqual(byLifetime.source, .subscription)
        XCTAssertNil(byLifetime.proUntil)
    }

    func testTieGoesToServer() {
        let until = now.addingTimeInterval(day)
        let profile = UserProfile(id: "u1", displayName: nil, plan: .pro, proUntil: until)
        let store = StoreEntitlement(appUserId: "u1", proEntitlementActive: true, proExpiresAt: until)
        XCTAssertEqual(EntitlementRules.resolve(profile: profile, store: store, currentUserId: "u1", now: now).source, .server)
    }

    func testProfileJSONReadsTrialFlight() {
        let row: [String: Any] = ["id": "u1", "plan": "free", "trial_flight_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]
        let parsed = profile(from: row)
        XCTAssertEqual(parsed?.trialFlightId, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        XCTAssertEqual(parsed?.isProForFlight("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", now: now), true)
        XCTAssertEqual(parsed?.isProForFlight("other", now: now), false)
    }

    func testTripPassProductIdIgnoresSuffix() {
        XCTAssertTrue(EntitlementRules.isTripPass("heygate_trip_pass"))
        XCTAssertTrue(EntitlementRules.isTripPass("heygate_trip_pass:base"))
        XCTAssertFalse(EntitlementRules.isTripPass("heygate_pro_yearly"))
    }
}

final class PlanPricingTests: XCTestCase {
    private let annual = StorePrice(formatted: "₹4,999.00", amount: 4999, currencyCode: "INR")
    private let monthly = StorePrice(formatted: "₹499.00", amount: 499, currencyCode: "INR")

    func testSavingOfYearlyAgainstMonthly() {
        // 12 × 499 = 5,988; (5,988 − 4,999) / 5,988 = 16.5% → 16.
        XCTAssertEqual(PlanPricing.annualSavingPercent(annual: annual, monthly: monthly), 16)
    }

    func testNoSavingWhenCurrenciesDifferOrAnnualIsNotCheaper() {
        XCTAssertNil(PlanPricing.annualSavingPercent(annual: annual, monthly: StorePrice(formatted: "$4.99", amount: 4.99, currencyCode: "USD")))
        XCTAssertNil(PlanPricing.annualSavingPercent(annual: StorePrice(formatted: "", amount: 6000, currencyCode: "INR"), monthly: monthly))
        XCTAssertNil(PlanPricing.annualSavingPercent(annual: StorePrice(formatted: "", amount: 0, currencyCode: "INR"), monthly: monthly))
    }

    func testMonthlyEquivalentRoundsToWholeUnits() throws {
        // 4,999 / 12 = 416.58 → 417.
        let text = try XCTUnwrap(PlanPricing.monthlyEquivalent(annual: annual, locale: Locale(identifier: "en_IN")))
        XCTAssertTrue(text.contains("417"), text)
        XCTAssertFalse(text.contains("."), text)
        XCTAssertNil(PlanPricing.monthlyEquivalent(annual: StorePrice(formatted: "", amount: 0, currencyCode: "INR")))
    }
}

@MainActor
final class FakeBilling: BillingGateway {
    var isAvailable = true
    var store: StoreEntitlement?
    var offer: OfferResult = .notAvailable
    var purchaseOutcome: PurchaseOutcome = .purchased
    var restoreOutcome: RestoreOutcome = .restored(hasPro: true)
    var identified: [String] = []
    var purchased: [PlanKind] = []

    func storeEntitlement() async -> StoreEntitlement? { store }
    func identify(userId: String) async { identified.append(userId) }
    func loadOffer() async -> OfferResult { offer }
    func purchase(_ plan: PlanKind) async -> PurchaseOutcome {
        purchased.append(plan)
        return purchaseOutcome
    }
    func restore() async -> RestoreOutcome { restoreOutcome }
}

@MainActor
final class FakeSync: EntitlementSyncing {
    var calls = 0
    var onSync: (() -> Void)?
    func syncEntitlement() async -> ActionResult {
        calls += 1
        onSync?()
        return .success
    }
}

@MainActor
final class PaywallModelTests: XCTestCase {
    private func options() -> [PlanOption] {
        [
            PlanOption(kind: .tripPass, productId: "heygate_trip_pass", price: StorePrice(formatted: "₹99.00", amount: 99, currencyCode: "INR")),
            PlanOption(kind: .monthly, productId: "heygate_pro_monthly", price: StorePrice(formatted: "₹499.00", amount: 499, currencyCode: "INR")),
            PlanOption(kind: .annual, productId: "heygate_pro_yearly", price: StorePrice(formatted: "₹4,999.00", amount: 4999, currencyCode: "INR")),
        ]
    }

    private func make(_ billing: FakeBilling, _ sync: FakeSync = FakeSync(), profiles: FakeProfiles = FakeProfiles()) -> PaywallModel {
        let service = ProService(billing: billing, profiles: profiles, auth: FakeAuth(), sync: sync, clock: ManualClock(now))
        return PaywallModel(pro: service, locale: Locale(identifier: "en_IN"))
    }

    func testBlankKeyShowsComingSoon() async {
        let billing = FakeBilling()
        billing.isAvailable = false
        let model = make(billing)
        await model.load()
        XCTAssertEqual(model.state.phase, .comingSoon)
        XCTAssertTrue(billing.identified.isEmpty)
    }

    func testPlansComeFromTheOfferingInFixedOrder() async {
        let billing = FakeBilling()
        billing.offer = .available(options())
        let model = make(billing)
        await model.load()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.plans.map(\.kind), [.annual, .monthly, .tripPass])
        XCTAssertEqual(model.state.plans[0].price, "₹4,999.00")
        XCTAssertEqual(model.state.plans[0].savingPercent, 16)
        XCTAssertNotNil(model.state.plans[0].perMonth)
        XCTAssertEqual(model.state.plans[2].price, "₹99.00")
        XCTAssertEqual(model.state.selected, .annual)
        XCTAssertEqual(billing.identified, ["user-1"], "logIn with the Supabase user id")
    }

    func testEmptyOfferingIsComingSoon() async {
        let billing = FakeBilling()
        billing.offer = .available([])
        let model = make(billing)
        await model.load()
        XCTAssertEqual(model.state.phase, .comingSoon)
    }

    func testOfflineAndFailedOffer() async {
        let billing = FakeBilling()
        billing.offer = .offline
        let model = make(billing)
        await model.load()
        XCTAssertEqual(model.state.phase, .offline)
        billing.offer = .failed("x")
        await model.load()
        XCTAssertEqual(model.state.phase, .loadFailed)
    }

    func testPurchaseSyncsEntitlementAndRefreshesProfile() async {
        let billing = FakeBilling()
        billing.offer = .available(options())
        let sync = FakeSync()
        let profiles = FakeProfiles()
        sync.onSync = { profiles.current = UserProfile(id: "user-1", displayName: nil, plan: .pro, proUntil: now.addingTimeInterval(365 * day)) }
        let model = make(billing, sync, profiles: profiles)
        await model.load()
        model.select(.tripPass)
        await model.purchase()
        XCTAssertEqual(billing.purchased, [.tripPass])
        XCTAssertEqual(sync.calls, 1)
        XCTAssertEqual(model.state.message, .purchased)
        XCTAssertTrue(model.state.pro.isPro)
        XCTAssertFalse(model.state.busy)
    }

    func testCancelledPurchaseDoesNotSyncOrShowAMessage() async {
        let billing = FakeBilling()
        billing.offer = .available(options())
        billing.purchaseOutcome = .cancelled
        let sync = FakeSync()
        let model = make(billing, sync)
        await model.load()
        await model.purchase()
        XCTAssertEqual(sync.calls, 0)
        XCTAssertNil(model.state.message)
    }

    func testPendingPurchase() async {
        let billing = FakeBilling()
        billing.offer = .available(options())
        billing.purchaseOutcome = .pending
        let model = make(billing)
        await model.load()
        await model.purchase()
        XCTAssertEqual(model.state.message, .pending)
    }

    func testRestoreWithAndWithoutPro() async {
        let billing = FakeBilling()
        billing.offer = .available(options())
        let sync = FakeSync()
        let model = make(billing, sync)
        await model.load()
        await model.restore()
        XCTAssertEqual(model.state.message, .restored)
        XCTAssertEqual(sync.calls, 1)
        billing.restoreOutcome = .restored(hasPro: false)
        await model.restore()
        XCTAssertEqual(model.state.message, .nothingRestored)
        billing.restoreOutcome = .failed(nil)
        await model.restore()
        XCTAssertEqual(model.state.message, .restoreFailed)
    }

    func testUnavailableBillingNeverCrashes() async {
        let billing = UnavailableBilling()
        XCTAssertFalse(billing.isAvailable)
        let purchase = await billing.purchase(.annual)
        let restore = await billing.restore()
        let offer = await billing.loadOffer()
        XCTAssertEqual(purchase, .notAvailable)
        XCTAssertEqual(restore, .notAvailable)
        XCTAssertEqual(offer, .notAvailable)
    }
}

final class CheckinWindowTests: XCTestCase {
    private let indigo = CheckinRule(
        carrierIata: "6E", opensHoursBefore: 48, closesMinutesBefore: 60, closesMinutesBeforeIntl: 75,
        checkinUrl: "https://www.goindigo.in/web-check-in.html", verified: true
    )

    private func flight(dep: String = "2026-11-27T10:00:00Z", est: String? = nil, arrTz: String = "Asia/Kolkata", status: FlightStatus = .scheduled) -> Flight {
        Flight(
            id: "f1", carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate.parse("2026-11-27")!,
            depIata: "DEL", arrIata: "BOM", status: status, schedDep: parseInstant(dep), estDep: parseInstant(est),
            depAirport: Airport(iata: "DEL", name: "Delhi", city: "New Delhi", tz: "Asia/Kolkata", lat: 28.56, lon: 77.10),
            arrAirport: Airport(iata: "BOM", name: "Mumbai", city: "Mumbai", tz: arrTz, lat: 19.09, lon: 72.87)
        )
    }

    func testNotOpenYet() {
        let window = checkinWindow(rule: indigo, flight: flight(dep: "2026-11-28T16:00:00Z"), now: now)
        XCTAssertEqual(window, .notOpenYet(opensAt: parseInstant("2026-11-26T16:00:00Z")!, closesAt: parseInstant("2026-11-28T15:00:00Z")!))
        let card = checkinCard(rule: indigo, flight: flight(dep: "2026-11-28T16:00:00Z"), now: now)
        XCTAssertEqual(card?.countdown, RelativeTime(amount: 6, unit: .hours, isPast: false))
        XCTAssertEqual(card?.opensTime, "21:30")
        XCTAssertEqual(card?.canCheckInNow, false)
    }

    func testOpenAndClosingSoon() {
        let open = checkinWindow(rule: indigo, flight: flight(), now: now)
        XCTAssertEqual(open, .open(opensAt: parseInstant("2026-11-25T10:00:00Z")!, closesAt: parseInstant("2026-11-27T09:00:00Z")!, closingSoon: false))
        XCTAssertEqual(checkinCard(rule: indigo, flight: flight(), now: now)?.canCheckInNow, true)
        let soon = checkinWindow(rule: indigo, flight: flight(dep: "2026-11-26T11:20:00Z"), now: now)
        if case .open(_, _, let closingSoon) = soon { XCTAssertTrue(closingSoon) } else { XCTFail("\(String(describing: soon))") }
    }

    func testClosed() {
        let window = checkinWindow(rule: indigo, flight: flight(dep: "2026-11-26T10:30:00Z"), now: now)
        if case .closed = window {} else { XCTFail("\(String(describing: window))") }
    }

    func testInternationalUsesLaterClose() {
        let window = checkinWindow(rule: indigo, flight: flight(arrTz: "Asia/Dubai"), now: now)
        XCTAssertEqual(window?.closesAt, parseInstant("2026-11-27T08:45:00Z"))
    }

    func testEstimatedDepartureMovesTheWindow() {
        let window = checkinWindow(rule: indigo, flight: flight(est: "2026-11-27T12:00:00Z"), now: now)
        XCTAssertEqual(window?.closesAt, parseInstant("2026-11-27T11:00:00Z"))
    }

    func testHiddenWithoutRuleOrForGoneFlights() {
        XCTAssertNil(checkinWindow(rule: nil, flight: flight(), now: now))
        XCTAssertNil(checkinWindow(rule: indigo, flight: flight(status: .cancelled), now: now))
        XCTAssertNil(checkinWindow(rule: indigo, flight: flight(dep: "2026-11-26T09:00:00Z"), now: now))
    }

    func testSafeURL() {
        XCTAssertEqual(safeCheckinURL(" https://www.goindigo.in/web-check-in.html ")?.absoluteString, "https://www.goindigo.in/web-check-in.html")
        XCTAssertNil(safeCheckinURL("http://www.goindigo.in"))
        XCTAssertNil(safeCheckinURL("javascript:alert(1)"))
        XCTAssertNil(safeCheckinURL("https://"))
        XCTAssertNil(safeCheckinURL("https://a b.com"))
        XCTAssertNil(safeCheckinURL(nil))
        let card = checkinCard(rule: CheckinRule(carrierIata: "6E", opensHoursBefore: 48, closesMinutesBefore: 60, closesMinutesBeforeIntl: 75, checkinUrl: "ftp://x", verified: false), flight: flight(), now: now)
        XCTAssertNil(card?.url)
        XCTAssertEqual(card?.canCheckInNow, false)
        XCTAssertEqual(card?.verified, false)
    }

    func testRulesFromJSON() {
        let json = """
        [{"carrier_iata":"6e","opens_hours_before":48,"closes_minutes_before":60,"closes_minutes_before_intl":75,
          "checkin_url":"https://www.goindigo.in/web-check-in.html","verified":true,"notes":null,"updated_at":"2026-11-26T00:00:00Z"},
         {"carrier_iata":"AI","opens_hours_before":48,"closes_minutes_before":60,"closes_minutes_before_intl":75,"checkin_url":null,"verified":false},
         {"carrier_iata":"XX"}]
        """
        let rules = checkinRules(fromJSON: Data(json.utf8))
        XCTAssertEqual(rules.map(\.carrierIata), ["6E", "AI"])
        XCTAssertEqual(rules[0], indigo)
        XCTAssertNil(rules[1].checkinUrl)
    }
}

final class CheckinPushTests: XCTestCase {
    private let flightId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    func testCheckinAlertCarriesSafeURL() {
        let parsed = parseIncomingPush([
            "kind": "alert", "flight_id": flightId, "event_type": "CHECKIN_OPEN",
            "title": "Web check-in is open", "body": "6E 2341", "checkin_url": "https://www.goindigo.in/web-check-in.html",
        ])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertEqual(alert.eventType, .checkinOpen)
        XCTAssertEqual(alert.checkinUrl, "https://www.goindigo.in/web-check-in.html")
        XCTAssertEqual(alert.channelId, PushContract.channelUpdates)
        XCTAssertEqual(AlertType.checkinClosing.critical, true)
        XCTAssertFalse(AlertType.checkinOpen.proOnly)
    }

    func testUnsafeURLIsDropped() {
        let parsed = parseIncomingPush([
            "kind": "alert", "flight_id": flightId, "event_type": "CHECKIN_CLOSING",
            "title": "Check-in closes soon", "body": "", "checkin_url": "http://evil.example",
        ])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertNil(alert.checkinUrl)
    }

    func testNotificationTapRouting() {
        XCTAssertEqual(
            notificationTap(userInfo: ["flight_id": flightId, "checkin_url": "https://www.goindigo.in/web-check-in.html"]),
            .checkin(url: URL(string: "https://www.goindigo.in/web-check-in.html")!, flightId: flightId)
        )
        XCTAssertEqual(notificationTap(userInfo: ["flight_id": flightId, "checkin_url": "http://x"]), .openFlight(flightId))
        XCTAssertEqual(notificationTap(userInfo: ["flight_id": "nope"]), .ignore)
    }

    func testCheckinAlertGroupMutesBothEvents() {
        XCTAssertEqual(AlertGroup.checkin.types, [.checkinOpen, .checkinClosing])
        XCTAssertFalse(AlertGroup.checkin.proOnly)
        XCTAssertEqual(AlertGroup.checkin.prefs(enabled: false), [.checkinOpen: false, .checkinClosing: false])
    }
}

final class ShareTests: XCTestCase {
    func testShareURL() {
        XCTAssertEqual(shareURL(base: nil, token: "abc"), "https://heygate.app/f/#abc")
        XCTAssertEqual(shareURL(base: "  ", token: "abc"), "https://heygate.app/f/#abc")
        XCTAssertEqual(shareURL(base: "https://example.com/s/#old", token: " abc "), "https://example.com/s/#abc")
        XCTAssertEqual(shareURL(base: "https:", token: "abc"), "https://heygate.app/f/#abc", "a base cut by an xcconfig // comment")
    }

    func testShareMessageJoinsLines() {
        XCTAssertEqual(shareMessage(lines: ["  Meri flight live track karo: 6E 2341 DEL→BOM ", "", "Track my flight 6E 2341 DEL→BOM live: https://heygate.app/f/#t"]),
                       "Meri flight live track karo: 6E 2341 DEL→BOM\nTrack my flight 6E 2341 DEL→BOM live: https://heygate.app/f/#t")
    }

    func testWhatsAppURLEncodesEverything() throws {
        let url = try XCTUnwrap(whatsAppSendURL(text: "A&B+C #t\nDEL→BOM"))
        XCTAssertEqual(url.absoluteString, "whatsapp://send?text=A%26B%2BC%20%23t%0ADEL%E2%86%92BOM")
    }

    func testShareFlightFromFlight() {
        let flight = Flight(id: "f1", carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate.parse("2026-11-25")!, depIata: "DEL", arrIata: "BOM")
        XCTAssertEqual(ShareFlight(flight), ShareFlight(carrierIata: "6E", flightNumber: "2341", depIata: "DEL", arrIata: "BOM"))
    }

    func testScanSource() {
        XCTAssertEqual(TrackSource.fromWire("scan"), .scan)
        XCTAssertEqual(TrackSource.scan.wire, "scan")
    }
}

@MainActor
final class FakeSharing: FlightSharing {
    var result: ShareTokenResult = .token("tok_abcdefghijklmnopqrstuv")
    var calls: [String] = []
    func createShare(flightId: String) async -> ShareTokenResult {
        calls.append(flightId)
        return result
    }
}

@MainActor
final class FakeRules: CheckinRuleSource {
    var rules: [String: CheckinRule] = [:]
    func rule(carrier: String) async -> CheckinRule? { rules[carrier] }
}

@MainActor
final class FakeProState: ProStateProviding {
    var state = ProState.free
    func proState() async -> ProState { state }
}

/// The server trigger set `trial_flight_id` after the cached profile was read.
@MainActor
final class TrialGrantingProfiles: ProfileRepository {
    var current: UserProfile? = UserProfile(id: "user-1", displayName: nil, plan: .free, proUntil: nil)
    var refreshes = 0
    private let trialFlightId: String
    init(trialFlightId: String) { self.trialFlightId = trialFlightId }
    func profile() async -> UserProfile? { current }
    func refresh() async -> ActionResult {
        refreshes += 1
        current?.trialFlightId = trialFlightId
        return .success
    }
}

@MainActor
final class RecordingFlights: FlightRepository {
    var tracked: [TrackedFlightDetails] = []
    var detailWrites: [TrackExtras] = []
    var detailsResult: ActionResult = .success

    func trackedFlights() async -> [TrackedFlightDetails] { tracked }
    func trackedFlight(flightId: String) async -> TrackedFlightDetails? { tracked.first { $0.flight.id == flightId } }
    func flight(id: String) async -> Flight? { tracked.first { $0.flight.id == id }?.flight }
    func search(flightText: String, date: LocalDate) async -> SearchResult { .notFound }
    func track(flightId: String, extras: TrackExtras) async -> TrackResult { .failed(nil) }
    func untrack(flightId: String) async -> ActionResult { .success }
    func archive(flightId: String, archived: Bool) async -> ActionResult { .success }
    func updateNotifyPrefs(flightId: String, prefs: [AlertType: Bool]) async -> ActionResult { .success }
    func updateTrackingDetails(flightId: String, extras: TrackExtras) async -> ActionResult {
        detailWrites.append(extras)
        if detailsResult.isSuccess, let index = tracked.firstIndex(where: { $0.flight.id == flightId }) {
            tracked[index].tracking.pnr = extras.pnr
            tracked[index].tracking.seat = extras.seat
            tracked[index].tracking.cabin = extras.cabin
        }
        return detailsResult
    }
    func refresh() async -> ActionResult { .success }
    func applyServerSync(flightId: String, compactFlightJSON: String?) async -> ActionResult { .success }
}

@MainActor
final class FlightDetailP1Tests: XCTestCase {
    private let flightId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    private func makeRepo(pnr: String? = nil, cabin: Cabin? = nil) -> RecordingFlights {
        let flight = Flight(
            id: flightId, carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate.parse("2026-11-27")!,
            depIata: "DEL", arrIata: "BOM", schedDep: parseInstant("2026-11-27T10:00:00Z"),
            depAirport: Airport(iata: "DEL", name: "Delhi", city: "New Delhi", tz: "Asia/Kolkata", lat: 28.56, lon: 77.10),
            arrAirport: Airport(iata: "BOM", name: "Mumbai", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.09, lon: 72.87)
        )
        let repo = RecordingFlights()
        repo.tracked = [TrackedFlightDetails(tracking: TrackedFlight(id: "t1", userId: "user-1", flightId: flightId, pnr: pnr, cabin: cabin), flight: flight)]
        return repo
    }

    private func makeModel(_ repo: RecordingFlights, pro: FakeProState? = nil, rules: FakeRules? = nil, sharing: FakeSharing? = nil) -> FlightDetailScreenModel {
        FlightDetailScreenModel(
            flightId: flightId, flights: repo, profiles: FakeProfiles(), clock: ManualClock(now),
            pro: pro, checkinRules: rules, sharing: sharing, shareBaseURL: "https://heygate.app/f/"
        )
    }

    func testEditorWritesPnrSeatAndCabin() async {
        let repo = makeRepo(pnr: "ABC123", cabin: .economy)
        let model = makeModel(repo)
        await model.load()
        await model.beginEditing()
        XCTAssertEqual(model.state.editor?.pnr, "ABC123")
        XCTAssertEqual(model.state.editor?.cabin, .economy)
        model.setEditorSeat("14c")
        model.setEditorCabin(.business)
        await model.saveEditor()
        XCTAssertNil(model.state.editor)
        XCTAssertEqual(repo.detailWrites.last?.pnr, "ABC123")
        XCTAssertEqual(repo.detailWrites.last?.cabin, .business)
        XCTAssertNil(repo.detailWrites.last?.note, "the note is not touched")
        XCTAssertEqual(model.state.detail?.booking?.cabin, .business)
    }

    func testEditorClearsWithEmptyFields() async {
        let repo = makeRepo(pnr: "ABC123", cabin: .first)
        let model = makeModel(repo)
        await model.load()
        await model.beginEditing()
        model.setEditorPnr("")
        model.setEditorCabin(nil)
        await model.saveEditor()
        XCTAssertNil(repo.detailWrites.last?.pnr)
        XCTAssertNil(repo.detailWrites.last?.cabin)
    }

    func testEditorRejectsBadPnrWithoutWriting() async {
        let repo = makeRepo()
        let model = makeModel(repo)
        await model.load()
        await model.beginEditing()
        model.setEditorPnr("AB")
        await model.saveEditor()
        XCTAssertEqual(model.state.editor?.error, .pnr)
        XCTAssertTrue(repo.detailWrites.isEmpty)
        model.cancelEditing()
        XCTAssertNil(model.state.editor)
    }

    func testTrialFlightUnlocksProAndShowsBadge() async {
        let pro = FakeProState()
        pro.state = ProState(isPro: false, proUntil: nil, source: .none, confirmedByServer: true, trialFlightId: flightId)
        let model = makeModel(makeRepo(), pro: pro)
        await model.load()
        XCTAssertEqual(model.state.proAccess, .trial)
        XCTAssertEqual(model.state.detail?.alerts.contains { $0.locked }, false)
        pro.state = .free
        await model.reload()
        XCTAssertEqual(model.state.proAccess, .locked)
        XCTAssertEqual(model.state.detail?.alerts.contains { $0.locked }, true)
    }

    func testTrialFromProfileWithoutProService() async {
        let profiles = FakeProfiles()
        profiles.current = UserProfile(id: "user-1", displayName: nil, plan: .free, proUntil: nil, trialFlightId: flightId)
        let model = FlightDetailScreenModel(flightId: flightId, flights: makeRepo(), profiles: profiles, clock: ManualClock(now))
        await model.load()
        XCTAssertEqual(model.state.proAccess, .trial)
    }

    func testFirstOpenRefreshesProfileToSeeNewTrial() async {
        let profiles = TrialGrantingProfiles(trialFlightId: flightId)
        let model = FlightDetailScreenModel(flightId: flightId, flights: makeRepo(), profiles: profiles, clock: ManualClock(now))
        await model.load()
        XCTAssertEqual(profiles.refreshes, 1)
        XCTAssertEqual(model.state.proAccess, .trial)
        await model.load()
        XCTAssertEqual(profiles.refreshes, 1, "a known trial flight needs no refresh")
    }

    func testCheckinCardFromRules() async {
        let rules = FakeRules()
        rules.rules["6E"] = CheckinRule(carrierIata: "6E", opensHoursBefore: 48, closesMinutesBefore: 60, closesMinutesBeforeIntl: 75,
                                         checkinUrl: "https://www.goindigo.in/web-check-in.html", verified: true)
        let model = makeModel(makeRepo(), rules: rules)
        await model.load()
        XCTAssertEqual(model.state.checkin?.canCheckInNow, true)
        rules.rules = [:]
        await model.reload()
        XCTAssertNil(model.state.checkin)
    }

    func testShareBuildsLinkFromToken() async {
        let sharing = FakeSharing()
        let model = makeModel(makeRepo(), sharing: sharing)
        await model.load()
        await model.share()
        XCTAssertEqual(sharing.calls, [flightId])
        XCTAssertEqual(model.state.shareReady?.url, "https://heygate.app/f/#tok_abcdefghijklmnopqrstuv")
        XCTAssertEqual(model.state.shareReady?.flight, ShareFlight(carrierIata: "6E", flightNumber: "2341", depIata: "DEL", arrIata: "BOM"))
        model.shareHandled()
        XCTAssertNil(model.state.shareReady)
    }

    func testShareFailuresBecomeMessages() async {
        let sharing = FakeSharing()
        sharing.result = .failed("boom")
        let model = makeModel(makeRepo(), sharing: sharing)
        await model.load()
        await model.share()
        XCTAssertEqual(model.state.message, .shareFailed)
        XCTAssertNil(model.state.shareReady)

        let untracked = RecordingFlights()
        let other = makeModel(untracked, sharing: sharing)
        await other.share()
        XCTAssertEqual(other.state.message, .shareNotTracked)
    }
}
