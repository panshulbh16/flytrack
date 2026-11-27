import Foundation
import XCTest
@testable import FlyTrackCore

// P4 (docs/P4_CONTRACTS.md) on iOS: radar (section 1), TripIt (section 2), Gift Pro (section 3) and terminal maps
// (section 4). Answers are shaped as the backend's (supabase/functions/radar/handler.ts, tripit-*, migration 33).

private func p4Fixture(_ name: String, _ ext: String = "json") throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: ext, subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

private func body(_ text: String) -> Data { Data(text.utf8) }

// MARK: - fakes

@MainActor
private final class P4FakeRadar: RadarFetching {
    var results: [RadarFetchResult] = []
    var requests: [RadarRequest] = []

    func radar(_ request: RadarRequest) async -> RadarFetchResult {
        requests.append(request)
        return results.isEmpty ? .failure(.failed) : results.removeFirst()
    }
}

@MainActor
private final class P4FakeLocation: CoarseLocating {
    var auth: CoarseLocationAuthorization = .notDetermined
    var result: CoarseLocationResult = .located(GeoPoint(lat: 28.6139, lon: 77.2090))
    var asked = 0

    func authorization() -> CoarseLocationAuthorization { auth }
    func requestCoarseLocation() async -> CoarseLocationResult {
        asked += 1
        if case .located = result { auth = .authorized }
        if result == .denied { auth = .denied }
        return result
    }
}

/// 43 base64url characters, the shape of tripit-callback's one-time code.
private let tripItTestCode = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"

@MainActor
private final class P4FakeTripIt: TripItRepository {
    var statuses: [Result<TripItStatus, TripItFailure>] = []
    var lastStatus: Result<TripItStatus, TripItFailure> = .success(.disconnected)
    var authorize: Result<URL, TripItFailure> = .success(URL(string: "https://www.tripit.com/oauth/authorize?oauth_token=abc")!)
    var syncResult: Result<TripItSyncSummary, TripItFailure> = .success(TripItSyncSummary(segments: 3, added: 2, existing: 1, notFound: 0, skipped: 0, errors: 0))
    var disconnectResult: Result<Void, TripItFailure> = .success(())
    var completeResult: Result<Void, TripItFailure> = .success(())
    var completedCodes: [String] = []
    var statusCalls = 0
    var syncCalls = 0
    var disconnectCalls = 0

    func status() async -> Result<TripItStatus, TripItFailure> {
        statusCalls += 1
        if !statuses.isEmpty { lastStatus = statuses.removeFirst() }
        return lastStatus
    }
    func authorizeURL() async -> Result<URL, TripItFailure> { authorize }
    func complete(code: String) async -> Result<Void, TripItFailure> {
        completedCodes.append(code)
        return completeResult
    }
    func sync() async -> Result<TripItSyncSummary, TripItFailure> {
        syncCalls += 1
        return syncResult
    }
    func disconnect() async -> Result<Void, TripItFailure> {
        disconnectCalls += 1
        return disconnectResult
    }
}

@MainActor
private final class P4FakeAuthorizer: TripItAuthorizing {
    var result: TripItAuthorizationResult = .callback(URL(string: "heygate://tripit/connected")!)
    var opened: [(URL, String)] = []

    func authorize(url: URL, callbackScheme: String) async -> TripItAuthorizationResult {
        opened.append((url, callbackScheme))
        return result
    }
}

@MainActor
private final class P4FakeGifts: GiftsRepository {
    var lists: [RemoteResult<[Gift]>] = []
    var last: RemoteResult<[Gift]> = .ok([])
    var redeemResult: RedeemGiftResult = .redeemed(proUntil: nil, days: 365)
    var redeemed: [String] = []
    var listCalls = 0

    func myGifts() async -> RemoteResult<[Gift]> {
        listCalls += 1
        if !lists.isEmpty { last = lists.removeFirst() }
        return last
    }
    func redeem(code: String) async -> RedeemGiftResult {
        redeemed.append(code)
        return redeemResult
    }
}

@MainActor
private final class P4FakeGiftStore: GiftStore {
    var isAvailable = true
    var offer: GiftOfferResult = .available([
        GiftOption(product: .trip, price: StorePrice(formatted: "₹99.00", amount: 99, currencyCode: "INR")),
        GiftOption(product: .year, price: StorePrice(formatted: "₹4,999.00", amount: 4999, currencyCode: "INR")),
    ])
    var outcome: PurchaseOutcome = .purchased
    var bought: [GiftProduct] = []

    func loadGiftOffer() async -> GiftOfferResult { offer }
    func purchaseGift(_ product: GiftProduct) async -> PurchaseOutcome {
        bought.append(product)
        return outcome
    }
}

@MainActor
private final class P4FakeTerminalLoader: TerminalMapLoading {
    var data: Data?
    init(_ data: Data?) { self.data = data }
    func geojson(iata: String) async -> Data? { data }
}

private func gift(_ code: String, _ product: GiftProduct, _ status: GiftStatus = .unused) -> Gift {
    Gift(code: code, productId: product.productId, days: product.days, status: status, createdAt: nil, redeemedAt: nil)
}

private let noSleep: @Sendable (TimeInterval) async -> Void = { _ in }

@MainActor
private final class P4Counter {
    var value = 0
}

// MARK: - radar

final class RadarParsingTests: XCTestCase {
    func testCellRoundingMatchesTheServer() {
        XCTAssertEqual(roundToRadarCell(28.6139), 28.6)
        XCTAssertEqual(roundToRadarCell(77.2090), 77.2)
        XCTAssertEqual(roundToRadarCell(77.2249), 77.2)
        XCTAssertEqual(roundToRadarCell(77.2251), 77.25)
        XCTAssertEqual(roundToRadarCell(-0.02), 0)
        XCTAssertEqual(roundToRadarCell(-12.97), -12.95)
    }

    func testQueryItems() {
        XCTAssertEqual(RadarRequest.ground(iata: " del ").queryItems, [
            URLQueryItem(name: "mode", value: "ground"), URLQueryItem(name: "iata", value: "DEL"),
        ])
        XCTAssertNil(RadarRequest.ground(iata: "DE").queryItems)
        XCTAssertNil(RadarRequest.ground(iata: "D3L").queryItems)
        // The precise location never leaves the phone.
        XCTAssertEqual(RadarRequest.proximity(GeoPoint(lat: 28.61391, lon: 77.20902)).queryItems, [
            URLQueryItem(name: "mode", value: "proximity"), URLQueryItem(name: "lat", value: "28.6"),
            URLQueryItem(name: "lon", value: "77.2"),
        ])
        XCTAssertEqual(RadarRequest.proximity(GeoPoint(lat: 12.9716, lon: 77.5946)).queryItems?.map(\.value), ["proximity", "12.95", "77.6"])
        XCTAssertEqual(RadarRequest.proximity(GeoPoint(lat: -0.01, lon: 0.04)).queryItems?.map(\.value), ["proximity", "0", "0.05"])
        XCTAssertNil(RadarRequest.proximity(GeoPoint(lat: 91, lon: 0)).queryItems)
        XCTAssertNil(RadarRequest.proximity(GeoPoint(lat: .nan, lon: 0)).queryItems)
    }

    func testCoordinateText() {
        XCTAssertEqual(radarCoordinateText(28.6), "28.6")
        XCTAssertEqual(radarCoordinateText(28.05), "28.05")
        XCTAssertEqual(radarCoordinateText(77), "77")
        XCTAssertEqual(radarCoordinateText(-12.95), "-12.95")
        XCTAssertEqual(radarCoordinateText(-0.0), "0")
    }

    func testParsesTheGroundAnswer() throws {
        let snapshot = try XCTUnwrap(parseRadarResponse(try p4Fixture("radar_ground_del")))
        XCTAssertEqual(snapshot.center, GeoPoint(lat: 28.5665, lon: 77.1031))
        XCTAssertEqual(snapshot.radiusKm, 5)
        XCTAssertEqual(snapshot.feed, "opensky")
        XCTAssertEqual(snapshot.asOf, parseInstant("2026-11-27T10:15:05.120Z"))
        // No address and no position are skipped.
        XCTAssertEqual(snapshot.aircraft.map(\.icao24), ["800c1a", "800b44", "8015c3", "80a1b2"])
        let indigo = snapshot.aircraft[0]
        XCTAssertEqual(indigo.flight, "6E2341")
        XCTAssertEqual(indigo.flightDisplay, "6E 2341")
        XCTAssertEqual(indigo.label, "6E 2341")
        XCTAssertEqual(indigo.reg, "VT-IZA")
        XCTAssertEqual(indigo.groundSpeedKts, 12)
        XCTAssertEqual(indigo.trackDeg, 271.6, accuracy: 0.0001)
        XCTAssertTrue(indigo.onGround)
        XCTAssertEqual(indigo.seenAt, parseInstant("2026-11-27T10:15:02Z"))
        // Tracks are normalised; labels fall back to callsign, registration.
        XCTAssertEqual(snapshot.aircraft[1].trackDeg, 270, accuracy: 0.0001)
        XCTAssertEqual(snapshot.aircraft[1].label, "AIC101")
        XCTAssertNil(snapshot.aircraft[1].flight)
        XCTAssertEqual(snapshot.aircraft[2].trackDeg, 5, accuracy: 0.0001)
        XCTAssertEqual(snapshot.aircraft[2].label, "VT-ANB")
        XCTAssertEqual(snapshot.aircraft[3].verticalRateFpm, 1800)
        XCTAssertEqual(snapshot.aircraft[3].altitudeFt, 2300)
    }

    func testLabelFallsBackToTheAddress() {
        let a = RadarAircraft(icao24: "800abc", callsign: "", position: GeoPoint(lat: 0, lon: 0), altitudeFt: 0, groundSpeedKts: 0, trackDeg: 0, onGround: true)
        XCTAssertEqual(a.label, "800ABC")
    }

    func testRejectsBodiesWithoutCentreOrList() {
        XCTAssertNil(parseRadarResponse(body(#"{"aircraft":[]}"#)))
        XCTAssertNil(parseRadarResponse(body(#"{"center":{"lat":1,"lon":2}}"#)))
        XCTAssertNil(parseRadarResponse(body("not json")))
        XCTAssertEqual(parseRadarResponse(body(#"{"center":{"lat":1,"lon":2},"aircraft":[],"radius_km":75}"#))?.aircraft, [])
    }

    func testGroundRowsKeepAircraftOnTheGroundNearestFirst() throws {
        let snapshot = try XCTUnwrap(parseRadarResponse(try p4Fixture("radar_ground_del")))
        let ground = radarRows(snapshot, mode: .ground)
        XCTAssertEqual(ground.map(\.id), ["800b44", "800c1a", "8015c3"])
        XCTAssertTrue(ground.allSatisfy(\.onGround))
        XCTAssertEqual(ground[1].flight, "6E 2341")
        XCTAssertEqual(ground[1].callsign, "IGO2341")
        XCTAssertNil(ground[0].callsign)
        let proximity = radarRows(snapshot, mode: .proximity)
        XCTAssertEqual(proximity.count, 4)
        XCTAssertTrue(proximity.map(\.distanceKm) == proximity.map(\.distanceKm).sorted())
    }

    func testFailureMapping() {
        XCTAssertEqual(radarFailure(status: 402, body: body(#"{"error":"pro_required"}"#)), .proRequired)
        XCTAssertEqual(radarFailure(status: 503, body: body(#"{"error":"feed_unavailable"}"#)), .comingSoon)
        XCTAssertEqual(radarFailure(status: 503, body: body(#"{"error":"feed_unlicensed"}"#)), .comingSoon)
        XCTAssertEqual(radarFailure(status: 502, body: body(#"{"error":"feed_error"}"#)), .feedError)
        XCTAssertEqual(radarFailure(status: 429, body: body(#"{"error":"rate_limited"}"#)), .rateLimited)
        XCTAssertEqual(radarFailure(status: 404, body: body(#"{"error":"not_found"}"#)), .unknownAirport)
        XCTAssertEqual(radarFailure(status: 400, body: body(#"{"error":"bad_input"}"#)), .failed)
        XCTAssertEqual(radarFailure(status: 401, body: body(#"{"error":"unauthorized"}"#)), .failed)
        // Without a readable body the status decides.
        XCTAssertEqual(radarFailure(status: 402, body: nil), .proRequired)
        XCTAssertEqual(radarFailure(status: 429, body: body("<html>")), .rateLimited)
        XCTAssertEqual(radarFailure(status: 503, body: nil), .comingSoon)
        XCTAssertEqual(radarFailure(status: 500, body: nil), .failed)
    }
}

@MainActor
final class RadarModelTests: XCTestCase {
    private func snapshot(_ ids: [String], onGround: Bool = true) -> RadarSnapshot {
        RadarSnapshot(
            center: GeoPoint(lat: 28.5665, lon: 77.1031), radiusKm: 5,
            aircraft: ids.enumerated().map { i, id in
                RadarAircraft(icao24: id, callsign: "C\(i)", position: GeoPoint(lat: 28.5665 + Double(i) * 0.001, lon: 77.1031),
                              altitudeFt: 0, groundSpeedKts: 0, trackDeg: 0, onGround: onGround)
            },
            feed: "opensky", asOf: nil
        )
    }

    func testAirportLoadsAtOnceAndRefreshesEveryTenSeconds() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot(["a", "b"])), .ok(snapshot(["a"]))]
        let location = P4FakeLocation()
        let model = RadarModel(target: .airport("DEL"), fetcher: radar, location: location, clock: ManualClock(Date()))
        XCTAssertEqual(model.state.phase, .loading)
        XCTAssertEqual(model.state.radiusKm, 5)
        await model.start()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.rows.map(\.id), ["a", "b"])
        XCTAssertEqual(model.nextRefreshDelay, 10)
        await model.refresh()
        XCTAssertEqual(model.state.rows.map(\.id), ["a"])
        XCTAssertEqual(radar.requests, [.ground(iata: "DEL"), .ground(iata: "DEL")])
        XCTAssertEqual(location.asked, 0, "ground radar never asks for location")
    }

    func testNearMeWaitsForTheTap() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot(["a"], onGround: false))]
        let location = P4FakeLocation()
        let model = RadarModel(target: .nearMe, fetcher: radar, location: location, clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .needsLocation)
        XCTAssertEqual(location.asked, 0)
        XCTAssertNil(model.nextRefreshDelay)
        await model.refresh()
        XCTAssertTrue(radar.requests.isEmpty, "no request without a fix")
        await model.useMyLocation()
        XCTAssertEqual(location.asked, 1)
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.radiusKm, 5, "radius comes from the answer")
        XCTAssertEqual(radar.requests, [.proximity(GeoPoint(lat: 28.6139, lon: 77.2090))])
        XCTAssertEqual(radar.requests.first?.queryItems?.map(\.value), ["proximity", "28.6", "77.2"])
    }

    func testNearMeStartsByItselfOnceAllowed() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot([]))]
        let location = P4FakeLocation()
        location.auth = .authorized
        let model = RadarModel(target: .nearMe, fetcher: radar, location: location, clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertTrue(model.state.rows.isEmpty)
    }

    func testLocationDeniedAndUnavailable() async {
        let location = P4FakeLocation()
        location.auth = .denied
        let model = RadarModel(target: .nearMe, fetcher: P4FakeRadar(), location: location, clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .locationDenied)
        XCTAssertEqual(location.asked, 0)

        let none = P4FakeLocation()
        none.result = .unavailable
        let other = RadarModel(target: .nearMe, fetcher: P4FakeRadar(), location: none, clock: ManualClock(Date()))
        await other.useMyLocation()
        XCTAssertEqual(other.state.phase, .locationUnavailable)
        XCTAssertNil(other.nextRefreshDelay)

        let refused = P4FakeLocation()
        refused.result = .denied
        let third = RadarModel(target: .nearMe, fetcher: P4FakeRadar(), location: refused, clock: ManualClock(Date()))
        await third.useMyLocation()
        XCTAssertEqual(third.state.phase, .locationDenied)
    }

    func testStaleFixIsRenewed() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot(["a"])), .ok(snapshot(["a"])), .ok(snapshot(["a"]))]
        let location = P4FakeLocation()
        let clock = ManualClock(Date())
        let model = RadarModel(target: .nearMe, fetcher: radar, location: location, clock: clock)
        await model.useMyLocation()
        clock.advance(seconds: 60)
        await model.refresh()
        XCTAssertEqual(location.asked, 1)
        clock.advance(seconds: 5 * 60)
        location.result = .located(GeoPoint(lat: 19.0896, lon: 72.8656))
        await model.refresh()
        XCTAssertEqual(location.asked, 2)
        XCTAssertEqual(radar.requests.last, .proximity(GeoPoint(lat: 19.0896, lon: 72.8656)))
    }

    func testFeedUnavailableIsComingSoonAndStops() async {
        let radar = P4FakeRadar()
        radar.results = [.failure(.comingSoon)]
        let model = RadarModel(target: .airport("BOM"), fetcher: radar, location: P4FakeLocation(), clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .comingSoon)
        XCTAssertNil(model.nextRefreshDelay)
    }

    func testFeedErrorKeepsThePlanesAndRetries() async {
        let radar = P4FakeRadar()
        radar.results = [.failure(.feedError), .ok(snapshot(["a"])), .failure(.feedError), .ok(snapshot(["b"]))]
        let model = RadarModel(target: .airport("DEL"), fetcher: radar, location: P4FakeLocation(), clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .comingSoon, "the same friendly state, never an error")
        XCTAssertEqual(model.nextRefreshDelay, 10, "retried on the next tick")
        await model.refresh()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertNil(model.state.notice)
        await model.refresh()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.notice, .feedError)
        XCTAssertEqual(model.state.rows.map(\.id), ["a"])
        await model.refresh()
        XCTAssertNil(model.state.notice)
        XCTAssertEqual(model.state.rows.map(\.id), ["b"])
    }

    func testRetryFromComingSoonKeepsTheCard() async {
        let radar = P4FakeRadar()
        radar.results = [.failure(.feedError), .failure(.feedError)]
        let model = RadarModel(target: .airport("DEL"), fetcher: radar, location: P4FakeLocation(), clock: ManualClock(Date()))
        await model.start()
        var phases: [RadarPhase] = []
        model.onChange = { phases.append($0.phase) }
        await model.refresh()
        XCTAssertEqual(phases, [.comingSoon], "no spinner flashes between retries")
    }

    func testRateLimitWaitsLonger() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot(["a"])), .failure(.rateLimited)]
        let model = RadarModel(target: .airport("DEL"), fetcher: radar, location: P4FakeLocation(), clock: ManualClock(Date()))
        await model.start()
        await model.refresh()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.notice, .rateLimited)
        XCTAssertEqual(model.nextRefreshDelay, 60)
    }

    func testProRequiredOfflineAndUnknownAirport() async {
        let radar = P4FakeRadar()
        radar.results = [.failure(.proRequired)]
        let model = RadarModel(target: .airport("DEL"), fetcher: radar, location: P4FakeLocation(), clock: ManualClock(Date()))
        await model.start()
        XCTAssertEqual(model.state.phase, .proRequired)
        XCTAssertNil(model.nextRefreshDelay)

        let offline = P4FakeRadar()
        offline.results = [.failure(.offline), .ok(snapshot(["a"])), .failure(.offline)]
        let second = RadarModel(target: .airport("DEL"), fetcher: offline, location: P4FakeLocation(), clock: ManualClock(Date()))
        await second.start()
        XCTAssertEqual(second.state.phase, .offline)
        XCTAssertEqual(second.nextRefreshDelay, 10)
        await second.refresh()
        await second.refresh()
        XCTAssertEqual(second.state.phase, .ready)
        XCTAssertEqual(second.state.notice, .offline)

        let unknown = P4FakeRadar()
        unknown.results = [.failure(.unknownAirport)]
        let third = RadarModel(target: .airport("XXX"), fetcher: unknown, location: P4FakeLocation(), clock: ManualClock(Date()))
        await third.start()
        XCTAssertEqual(third.state.phase, .unknownAirport)
        XCTAssertNil(third.nextRefreshDelay)
    }

    func testPointTargetUsesNoLocation() async {
        let radar = P4FakeRadar()
        radar.results = [.ok(snapshot(["a"], onGround: false))]
        let location = P4FakeLocation()
        let point = GeoPoint(lat: 21.1, lon: 79.05)
        let model = RadarModel(target: .point(point), fetcher: radar, location: location, clock: ManualClock(Date()))
        XCTAssertEqual(model.state.center, point)
        XCTAssertEqual(model.state.radiusKm, 75)
        await model.start()
        XCTAssertEqual(model.state.rows.count, 1, "proximity keeps airborne aircraft")
        XCTAssertEqual(location.asked, 0)
    }
}

// MARK: - TripIt

final class TripItParsingTests: XCTestCase {
    func testStatus() {
        XCTAssertEqual(parseTripItStatus(body(#"{"connected":false,"last_sync_at":null,"last_error":null}"#)), .disconnected)
        XCTAssertEqual(parseTripItStatus(body("null")), .disconnected)
        XCTAssertEqual(
            parseTripItStatus(body(#"{"connected":true,"last_sync_at":"2026-11-27T09:00:00+00:00","last_error":"tripit_revoked"}"#)),
            TripItStatus(connected: true, lastSyncAt: parseInstant("2026-11-27T09:00:00+00:00"), lastError: .revoked)
        )
        XCTAssertEqual(parseTripItStatus(body(#"{"connected":true,"last_sync_at":null,"last_error":"weird"}"#))?.lastError, nil)
        XCTAssertNil(parseTripItStatus(body("[]")))
    }

    func testSyncSummary() {
        XCTAssertEqual(
            parseTripItSyncSummary(body(#"{"segments":5,"added":2,"existing":1,"not_found":1,"skipped":1,"errors":0}"#)),
            TripItSyncSummary(segments: 5, added: 2, existing: 1, notFound: 1, skipped: 1, errors: 0)
        )
        XCTAssertNil(parseTripItSyncSummary(body(#"{"added":2}"#)))
    }

    func testAuthorizeURLMustBeTripIt() {
        let good = #"{"authorize_url":"https://www.tripit.com/oauth/authorize?oauth_token=abc&oauth_callback=https%3A%2F%2Fx.supabase.co%2Ffunctions%2Fv1%2Ftripit-callback"}"#
        XCTAssertEqual(parseTripItAuthorizeURL(body(good))?.host, "www.tripit.com")
        XCTAssertNil(parseTripItAuthorizeURL(body(#"{"authorize_url":"http://www.tripit.com/oauth/authorize"}"#)))
        XCTAssertNil(parseTripItAuthorizeURL(body(#"{"authorize_url":"https://evil.example/oauth/authorize"}"#)))
        XCTAssertNil(parseTripItAuthorizeURL(body(#"{}"#)))
    }

    func testFailures() {
        XCTAssertEqual(tripItFailure(status: 402, body: body(#"{"error":"pro_required"}"#)), .proRequired)
        XCTAssertEqual(tripItFailure(status: 409, body: body(#"{"error":"not_connected"}"#)), .notConnected)
        XCTAssertEqual(tripItFailure(status: 409, body: body(#"{"error":"tripit_revoked"}"#)), .revoked)
        XCTAssertEqual(tripItFailure(status: 502, body: body(#"{"error":"tripit_error"}"#)), .tripitError)
        XCTAssertEqual(tripItFailure(status: 503, body: body(#"{"error":"not_configured"}"#)), .notConfigured)
        XCTAssertEqual(tripItFailure(status: 500, body: body(#"{"error":"internal"}"#)), .failed)
        XCTAssertEqual(tripItFailure(status: 503, body: nil), .notConfigured)
        XCTAssertEqual(tripItFailure(status: 402, body: nil), .proRequired)
    }

    func testCallbackURLs() {
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/connected")!), .connected)
        XCTAssertEqual(parseTripItCallback(URL(string: "HEYGATE://TripIt/connected/")!), .connected)
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed?error=denied")!), .failed(.denied))
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed?error=expired")!), .failed(.expired))
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed?error=tripit_error")!), .failed(.tripitError))
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed?error=not_configured")!), .failed(.notConfigured))
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed?error=internal")!), .failed(.internalError))
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/failed")!), .failed(.internalError))
        XCTAssertNil(parseTripItCallback(URL(string: "heygate://friend?code=ABCD2345")!))
        XCTAssertNil(parseTripItCallback(URL(string: "https://tripit/connected")!))
        XCTAssertNil(parseTripItCallback(URL(string: "heygate://tripit/other")!))
    }

    func testCallbackCodeURL() {
        let code = tripItTestCode
        XCTAssertEqual(code.count, 43)
        XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/callback?code=\(code)")!), .code(code))
        XCTAssertEqual(parseTripItCallback(URL(string: "HEYGATE://TripIt/callback/?x=1&code=\(code)")!), .code(code))
        // missing, short, long or non-base64url codes are a failed connect, never sent to the server
        for bad in ["", "?code=", "?code=abc", "?code=\(code)x", "?code=\(code.dropLast())%2B", "?other=\(code)"] {
            XCTAssertEqual(parseTripItCallback(URL(string: "heygate://tripit/callback\(bad)")!), .failed(.internalError), bad)
        }
        XCTAssertNil(parseTripItCallback(URL(string: "https://evil.example/tripit/callback?code=\(code)")!))
        XCTAssertNil(parseTripItCallback(URL(string: "heygate://family/callback?code=\(code)")!))
        XCTAssertFalse(String(describing: TripItCallback.code(code)).contains(code), "the one-time code is never printed")
        XCTAssertTrue(isTripItCallbackCode(code))
        XCTAssertFalse(isTripItCallbackCode(String(code.dropLast()) + "="))
        let body = JSON.object(from: tripItCompleteBody(code: code))
        XCTAssertEqual(body.flatMap { JSON.string($0["code"]) }, code)
    }

    func testCompleteAndCooldownFailures() {
        XCTAssertEqual(tripItFailure(status: 403, body: body(#"{"error":"not_your_link"}"#)), .notYourLink)
        XCTAssertEqual(tripItFailure(status: 410, body: body(#"{"error":"expired"}"#)), .expired)
        XCTAssertEqual(tripItFailure(status: 409, body: body(#"{"error":"denied"}"#)), .denied)
        XCTAssertEqual(
            tripItFailure(status: 429, body: body(#"{"error":"cooldown","retry_after_s":42,"message":"retry_after_s=42"}"#)),
            .cooldown(retryAfter: 42)
        )
        XCTAssertEqual(tripItFailure(status: 429, body: body(#"{"error":"cooldown"}"#)), .cooldown(retryAfter: 60))
        XCTAssertEqual(tripItFailure(status: 429, body: body(#"{"error":"cooldown","retry_after_s":0}"#)), .cooldown(retryAfter: 1))
        XCTAssertEqual(tripItFailure(status: 429, body: nil), .cooldown(retryAfter: 60))
        XCTAssertEqual(tripItFailure(status: 410, body: nil), .expired)
        XCTAssertEqual(tripItFailure(status: 403, body: nil), .notYourLink)
    }
}

@MainActor
final class TripItModelTests: XCTestCase {
    func testFreeUserSeesTheUpsell() async {
        let repo = P4FakeTripIt()
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { false }, sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .proRequired)
    }

    func testNotConfiguredDisablesTheRow() async {
        let repo = P4FakeTripIt()
        repo.authorize = .failure(.notConfigured)
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .disconnected)
        await model.connect()
        XCTAssertEqual(model.state.phase, .unavailable)
        XCTAssertNil(model.state.message)
        XCTAssertFalse(model.state.busy)
    }

    func testConnectOpensTripItAndPicksUpTheFirstSync() async {
        let repo = P4FakeTripIt()
        let synced = TripItStatus(connected: true, lastSyncAt: parseInstant("2026-11-27T10:00:00Z"))
        repo.statuses = [.success(.disconnected), .success(TripItStatus(connected: true)), .success(synced)]
        let authorizer = P4FakeAuthorizer()
        let model = TripItModel(repository: repo, authorizer: authorizer, isPro: { true }, followUpDelays: [3, 10], sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .disconnected)
        await model.connect()
        XCTAssertEqual(authorizer.opened.first?.0.host, "www.tripit.com")
        XCTAssertEqual(authorizer.opened.first?.1, "heygate")
        XCTAssertEqual(model.state.phase, .connected)
        XCTAssertEqual(model.state.message, .connected)
        XCTAssertEqual(model.state.status, synced, "the background first sync shows up without a manual refresh")
        XCTAssertEqual(repo.statusCalls, 3, "stops polling once a sync time is known")
        XCTAssertFalse(model.state.busy)
    }

    func testCancelledAndDeniedSignIn() async {
        let repo = P4FakeTripIt()
        let authorizer = P4FakeAuthorizer()
        authorizer.result = .cancelled
        let model = TripItModel(repository: repo, authorizer: authorizer, isPro: { true }, sleep: noSleep)
        await model.load()
        await model.connect()
        XCTAssertNil(model.state.message)
        XCTAssertEqual(model.state.phase, .disconnected)
        authorizer.result = .callback(URL(string: "heygate://tripit/failed?error=denied")!)
        await model.connect()
        XCTAssertEqual(model.state.message, .denied)
        authorizer.result = .callback(URL(string: "heygate://tripit/failed?error=expired")!)
        await model.connect()
        XCTAssertEqual(model.state.message, .expired)
    }

    func testDeepLinkFromTheSystemRefreshesTheStatus() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(.disconnected), .success(TripItStatus(connected: true, lastSyncAt: Date()))]
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        let handled = await model.handle(URL(string: "heygate://tripit/connected")!)
        XCTAssertTrue(handled)
        XCTAssertEqual(model.state.phase, .connected)
        let other = await model.handle(URL(string: "heygate://friend?code=X")!)
        XCTAssertFalse(other)
    }

    func testSyncNowAndRevoked() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(TripItStatus(connected: true, lastSyncAt: Date()))]
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        await model.syncNow()
        XCTAssertEqual(model.state.message, .synced(TripItSyncSummary(segments: 3, added: 2, existing: 1, notFound: 0, skipped: 0, errors: 0)))
        XCTAssertFalse(model.state.syncing)
        XCTAssertFalse(model.state.needsReconnect)
        repo.syncResult = .failure(.revoked)
        await model.syncNow()
        XCTAssertEqual(model.state.message, .revoked)
        XCTAssertTrue(model.state.needsReconnect)
        repo.syncResult = .failure(.notConnected)
        await model.syncNow()
        XCTAssertEqual(model.state.phase, .disconnected)
    }

    func testLapsedProKeepsDisconnect() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(TripItStatus(connected: true, lastSyncAt: Date()))]
        repo.syncResult = .failure(.proRequired)
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { false }, sleep: noSleep)
        await model.load()
        await model.syncNow()
        XCTAssertEqual(model.state.phase, .connected, "the Disconnect button stays")
        XCTAssertEqual(model.state.message, .proRequired)
    }

    func testDisconnect() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(TripItStatus(connected: true))]
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { false }, sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .connected, "a connected free user can still disconnect")
        await model.disconnect()
        XCTAssertEqual(repo.disconnectCalls, 1)
        XCTAssertEqual(model.state.message, .disconnected)
        XCTAssertEqual(model.state.phase, .proRequired)
        repo.disconnectResult = .failure(.offline)
        await model.disconnect()
        XCTAssertEqual(model.state.message, .offline)
    }

    func testSignInSheetCodeIsCompletedAsTheSignedInUser() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(.disconnected), .success(TripItStatus(connected: true, lastSyncAt: Date()))]
        let authorizer = P4FakeAuthorizer()
        authorizer.result = .callback(URL(string: "heygate://tripit/callback?code=\(tripItTestCode)")!)
        let model = TripItModel(repository: repo, authorizer: authorizer, isPro: { true }, sleep: noSleep)
        await model.load()
        await model.connect()
        XCTAssertEqual(repo.completedCodes, [tripItTestCode])
        XCTAssertEqual(model.state.phase, .connected)
        XCTAssertEqual(model.state.message, .connected)
        XCTAssertFalse(model.state.busy)
    }

    func testCodeFromAnotherAccountIsNotYourLink() async {
        let repo = P4FakeTripIt()
        repo.completeResult = .failure(.notYourLink)
        let authorizer = P4FakeAuthorizer()
        authorizer.result = .callback(URL(string: "heygate://tripit/callback?code=\(tripItTestCode)")!)
        let model = TripItModel(repository: repo, authorizer: authorizer, isPro: { true }, sleep: noSleep)
        await model.load()
        await model.connect()
        XCTAssertEqual(model.state.message, .notYourLink)
        XCTAssertEqual(model.state.phase, .disconnected)
        XCTAssertFalse(model.state.busy)
        repo.completeResult = .failure(.expired)
        await model.connect()
        XCTAssertEqual(model.state.message, .expired)
    }

    func testCodeDeepLinkFromOnOpenURL() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(.disconnected), .success(TripItStatus(connected: true, lastSyncAt: Date()))]
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        let handled = await model.handle(URL(string: "heygate://tripit/callback?code=\(tripItTestCode)")!)
        XCTAssertTrue(handled)
        XCTAssertEqual(repo.completedCodes, [tripItTestCode])
        XCTAssertEqual(model.state.phase, .connected)
        XCTAssertEqual(model.state.message, .connected)
        // a malformed code is a failed connect without a server call
        let bad = await model.handle(URL(string: "heygate://tripit/callback?code=nope")!)
        XCTAssertTrue(bad)
        XCTAssertEqual(repo.completedCodes.count, 1)
        XCTAssertEqual(model.state.message, .failed)
    }

    func testSameCodeIsSentOnlyOnce() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(.disconnected), .success(TripItStatus(connected: true, lastSyncAt: Date()))]
        let url = URL(string: "heygate://tripit/callback?code=\(tripItTestCode)")!
        let authorizer = P4FakeAuthorizer()
        authorizer.result = .callback(url)
        let model = TripItModel(repository: repo, authorizer: authorizer, isPro: { true }, sleep: noSleep)
        await model.load()
        await model.connect()
        // the system delivers the same link again via onOpenURL: ignored, no second (expired) call
        let handled = await model.handle(url)
        XCTAssertTrue(handled)
        XCTAssertEqual(repo.completedCodes, [tripItTestCode])
        XCTAssertEqual(model.state.message, .connected)
        XCTAssertFalse(model.state.busy)
    }

    func testSyncCooldown() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.success(TripItStatus(connected: true, lastSyncAt: Date()))]
        repo.syncResult = .failure(.cooldown(retryAfter: 42))
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        await model.syncNow()
        XCTAssertEqual(model.state.message, .cooldown(42))
        XCTAssertEqual(model.state.phase, .connected)
        XCTAssertFalse(model.state.syncing)
        XCTAssertFalse(model.state.busy)
    }

    func testOfflineStatusIsUnknown() async {
        let repo = P4FakeTripIt()
        repo.statuses = [.failure(.offline)]
        let model = TripItModel(repository: repo, authorizer: P4FakeAuthorizer(), isPro: { true }, sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .unknown)
    }
}

// MARK: - gifts

final class GiftParsingTests: XCTestCase {
    func testProducts() {
        XCTAssertEqual(GiftProduct(productId: "heygate_gift_year"), .year)
        XCTAssertEqual(GiftProduct(productId: "heygate_gift_trip:base"), .trip)
        XCTAssertNil(GiftProduct(productId: "heygate_trip_pass"))
        XCTAssertEqual(GiftProduct.year.days, 365)
        XCTAssertEqual(GiftProduct.trip.days, 7)
        // Gift purchases never count as a trip pass.
        XCTAssertFalse(EntitlementRules.isTripPass(GiftContract.productTrip))
    }

    func testCodes() {
        XCTAssertEqual(normalizeGiftCode("abcd efgh-jkmn"), "ABCD-EFGH-JKMN")
        XCTAssertEqual(normalizeGiftCode(" ABCDEFGHJKMN "), "ABCD-EFGH-JKMN")
        XCTAssertNil(normalizeGiftCode("ABCD-EFGH-JKM"))
        XCTAssertNil(normalizeGiftCode("ABCD-EFGH-JKMO"), "O is not in the alphabet")
        XCTAssertNil(normalizeGiftCode("ABCD-EFGH-JKMN-P"))
        XCTAssertEqual(giftCodeInput("abcdefg"), "ABCD-EFG")
        XCTAssertEqual(giftCodeInput("abcd-efgh-jkmn-pqrs"), "ABCD-EFGH-JKMN")
        XCTAssertEqual(giftCodeInput("ab0i1l"), "AB")
        XCTAssertEqual(formatGiftCode("ABCD"), "ABCD")
        XCTAssertEqual(formatGiftCode("ABCDE"), "ABCD-E")
    }

    func testExtractsTheCodeFromAMessage() {
        XCTAssertEqual(extractGiftCode("I've gifted you HeyGate Pro! Code: ABCD-EFGH-JKMN. Redeem in Settings."), "ABCD-EFGH-JKMN")
        XCTAssertEqual(extractGiftCode("maine gift kiya code: abcd-efgh-jkmn"), "ABCD-EFGH-JKMN")
        XCTAssertEqual(extractGiftCode("ABCDEFGHJKMN"), "ABCD-EFGH-JKMN")
        XCTAssertNil(extractGiftCode("Nothing to see"))
        XCTAssertNil(extractGiftCode("XABCD-EFGH-JKMN"))
    }

    func testRedeemLinks() {
        XCTAssertEqual(giftRedeemLink(code: "ABCD-EFGH-JKMN"), "heygate://gift?code=ABCD-EFGH-JKMN")
        XCTAssertEqual(giftCode(fromURL: URL(string: "heygate://gift?code=abcd-efgh-jkmn")!), "ABCD-EFGH-JKMN")
        XCTAssertNil(giftCode(fromURL: URL(string: "heygate://gift?code=nope")!))
        XCTAssertNil(giftCode(fromURL: URL(string: "heygate://friend?code=ABCD-EFGH-JKMN")!))
    }

    func testMyGifts() throws {
        let list = try XCTUnwrap(parseMyGifts(try p4Fixture("my_gifts")))
        XCTAssertEqual(list.map(\.code), ["ABCD-EFGH-JKMN", "PQRS-TUVW-XYZ2", "3456-789A-BCDE"])
        XCTAssertEqual(list.map(\.status), [.unused, .redeemed, .voided])
        XCTAssertEqual(list[0].product, .year)
        XCTAssertEqual(list[0].days, 365)
        XCTAssertEqual(list[0].createdAt, parseInstant("2026-11-27T09:00:00.123+00:00"))
        XCTAssertEqual(list[1].redeemedAt, parseInstant("2026-11-21T10:30:00+00:00"))
        XCTAssertEqual(parseMyGifts(body("[]")), [])
        XCTAssertEqual(parseMyGifts(body("null")), [])
        XCTAssertNil(parseMyGifts(body("{}")))
    }

    func testRedeemAnswers() {
        XCTAssertEqual(parseRedeemGift(body(#"{"pro_until":"2027-11-27T10:00:00+00:00","days":365}"#)),
                       .redeemed(proUntil: parseInstant("2027-11-27T10:00:00+00:00"), days: 365))
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"invalid_code"}"#)), .invalidCode)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"already_redeemed"}"#)), .alreadyRedeemed)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"voided"}"#)), .voided)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"own_gift"}"#)), .ownGift)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"rate_limited"}"#)), .rateLimited)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"lifetime_pro"}"#)), .lifetimePro)
        XCTAssertEqual(parseRedeemGift(body(#"{"error":"something_new"}"#)), .failed)
        XCTAssertEqual(parseRedeemGift(body("null")), .failed)
        XCTAssertEqual(redeemGiftError("P0002"), .invalidCode)
        XCTAssertEqual(redeemGiftError("23505"), .alreadyRedeemed)
    }
}

@MainActor
final class GiftModelTests: XCTestCase {
    func testOfferIsSortedYearThenTrip() async {
        let model = GiftPurchaseModel(store: P4FakeGiftStore(), gifts: P4FakeGifts(), sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.options.map(\.product), [.year, .trip])
        XCTAssertEqual(model.state.options.map(\.price), ["₹4,999.00", "₹99.00"])
        XCTAssertEqual(model.state.selected, .year)
        model.select(.trip)
        XCTAssertEqual(model.state.selected, .trip)
    }

    func testNoStoreIsComingSoon() async {
        let store = P4FakeGiftStore()
        store.isAvailable = false
        let model = GiftPurchaseModel(store: store, gifts: P4FakeGifts(), sleep: noSleep)
        await model.load()
        XCTAssertEqual(model.state.phase, .comingSoon)
        let empty = P4FakeGiftStore()
        empty.offer = .available([])
        let other = GiftPurchaseModel(store: empty, gifts: P4FakeGifts(), sleep: noSleep)
        await other.load()
        XCTAssertEqual(other.state.phase, .comingSoon)
    }

    func testPurchasePollsForTheNewCode() async {
        let store = P4FakeGiftStore()
        let gifts = P4FakeGifts()
        let old = gift("ABCD-EFGH-JKMN", .year)
        let fresh = gift("PQRS-TUVW-XYZ2", .year)
        gifts.lists = [.ok([old]), .ok([old]), .ok([old]), .ok([fresh, old])]
        let identified = P4Counter()
        let model = GiftPurchaseModel(store: store, gifts: gifts, identify: { identified.value += 1 }, pollDelays: [1, 1, 1], sleep: noSleep)
        await model.load()
        await model.purchase()
        XCTAssertEqual(store.bought, [.year])
        XCTAssertEqual(model.state.newGift, fresh, "the old unused code is not taken for the new one")
        XCTAssertNil(model.state.message)
        XCTAssertFalse(model.state.busy)
        XCTAssertEqual(gifts.listCalls, 4)
        XCTAssertEqual(identified.value, 2)
    }

    func testUnreadableListBeforeThePurchaseOnlyTakesAFreshCode() async {
        let gifts = P4FakeGifts()
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var oldGift = gift("ABCD-EFGH-JKMN", .year)
        oldGift.createdAt = start.addingTimeInterval(-3 * 86_400)
        var fresh = gift("PQRS-TUVW-XYZ2", .year)
        fresh.createdAt = start.addingTimeInterval(4)
        gifts.lists = [.offline, .ok([oldGift]), .ok([fresh, oldGift])]
        let model = GiftPurchaseModel(store: P4FakeGiftStore(), gifts: gifts, pollDelays: [1], sleep: noSleep, now: { start })
        await model.load()
        await model.purchase()
        XCTAssertEqual(model.state.newGift, fresh, "an older unused code is never shown as the one just bought")
    }

    func testCodeLateFromTheWebhook() async {
        let gifts = P4FakeGifts()
        gifts.last = .ok([])
        let model = GiftPurchaseModel(store: P4FakeGiftStore(), gifts: gifts, pollDelays: [1, 1], sleep: noSleep)
        await model.load()
        await model.purchase()
        XCTAssertNil(model.state.newGift)
        XCTAssertEqual(model.state.message, .codeDelayed)
    }

    func testPurchaseOutcomes() async {
        let store = P4FakeGiftStore()
        let model = GiftPurchaseModel(store: store, gifts: P4FakeGifts(), pollDelays: [], sleep: noSleep)
        await model.load()
        store.outcome = .cancelled
        await model.purchase()
        XCTAssertEqual(model.state.message, .cancelled)
        store.outcome = .pending
        await model.purchase()
        XCTAssertEqual(model.state.message, .pending)
        store.outcome = .offline
        await model.purchase()
        XCTAssertEqual(model.state.message, .offline)
        store.outcome = .failed("x")
        await model.purchase()
        XCTAssertEqual(model.state.message, .failed)
    }

    func testRedeem() async {
        let gifts = P4FakeGifts()
        let refreshed = P4Counter()
        let model = RedeemGiftModel(gifts: gifts, prefill: "Code: abcd-efgh-jkmn") { refreshed.value += 1 }
        XCTAssertEqual(model.state.input, "ABCD-EFGH-JKMN")
        XCTAssertTrue(model.state.canSubmit)
        await model.submit()
        XCTAssertEqual(gifts.redeemed, ["ABCD-EFGH-JKMN"])
        XCTAssertEqual(model.state.result, .redeemed(proUntil: nil, days: 365))
        XCTAssertEqual(refreshed.value, 1)

        gifts.redeemResult = .ownGift
        model.setInput("pqrs tuvw xyz2")
        XCTAssertNil(model.state.result)
        XCTAssertEqual(model.state.input, "PQRS-TUVW-XYZ2")
        await model.submit()
        XCTAssertEqual(model.state.result, .ownGift)
        XCTAssertEqual(refreshed.value, 1, "no refresh after a refusal")
    }

    func testRedeemChecksTheCodeBeforeAsking() async {
        let gifts = P4FakeGifts()
        let model = RedeemGiftModel(gifts: gifts) {}
        model.setInput("ABCD")
        XCTAssertFalse(model.state.canSubmit)
        await model.submit()
        XCTAssertEqual(model.state.result, .invalidCode)
        XCTAssertTrue(gifts.redeemed.isEmpty, "a short code never spends one of the 10 attempts")
    }

    func testMyGiftsKeepsTheListOnErrors() async throws {
        let gifts = P4FakeGifts()
        let list = try XCTUnwrap(parseMyGifts(try p4Fixture("my_gifts")))
        gifts.lists = [.ok(list), .offline]
        let model = MyGiftsModel(gifts: gifts)
        await model.load()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.unused.count, 1)
        XCTAssertEqual(model.state.used.count, 2)
        await model.load()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertEqual(model.state.gifts.count, 3)

        let empty = P4FakeGifts()
        empty.last = .offline
        let other = MyGiftsModel(gifts: empty)
        await other.load()
        XCTAssertEqual(other.state.phase, .offline)
    }
}

// MARK: - terminal maps

final class TerminalMapTests: XCTestCase {
    private func sample() throws -> TerminalMap {
        try XCTUnwrap(parseTerminalMap(try p4Fixture("terminal_map_sample", "geojson"), iata: "del"))
    }

    func testParsesTheBundledFormat() throws {
        let map = try sample()
        XCTAssertEqual(map.airport, "DEL")
        XCTAssertEqual(map.attribution, "© OpenStreetMap contributors (ODbL)")
        // Unknown kinds and positions off the globe are skipped.
        XCTAssertEqual(map.features.count, 15)
        XCTAssertFalse(map.features.contains { $0.id == "node/16" || $0.id == "node/17" })
        XCTAssertEqual(map.terminals.map(\.id), ["way/3", "relation/4"])
        let t3 = map.terminals[0]
        XCTAssertEqual(t3.anchor.lat, 28.555, accuracy: 1e-9)
        XCTAssertEqual(t3.anchor.lon, 77.085, accuracy: 1e-9)
        let t1 = map.terminals[1]
        XCTAssertEqual(t1.geometry, .multiPolygon)
        XCTAssertEqual(t1.anchor.lon, 77.112, accuracy: 1e-9, "label on the largest part")
        XCTAssertEqual(t1.bounds.maxLon, 77.121, accuracy: 1e-9)
        let taxiway = try XCTUnwrap(map.features.first { $0.kind == .taxiway })
        XCTAssertEqual(taxiway.anchor, GeoPoint(lat: 28.545, lon: 77.075))
        // The first camera covers the terminals only.
        let bounds = try XCTUnwrap(map.bounds)
        XCTAssertEqual(bounds.minLon, 77.083, accuracy: 1e-9)
        XCTAssertEqual(bounds.maxLon, 77.121, accuracy: 1e-9)
        XCTAssertEqual(map.features.first { $0.id == "node/10" }?.level, "1")
    }

    func testRejectsOtherBodies() {
        XCTAssertNil(parseTerminalMap(body(#"{"type":"Feature"}"#), iata: "DEL"))
        XCTAssertNil(parseTerminalMap(body("nope"), iata: "DEL"))
        XCTAssertEqual(parseTerminalMap(body(#"{"type":"FeatureCollection","features":[]}"#), iata: "bom")?.airport, "BOM")
    }

    func testGateKeys() {
        XCTAssertEqual(gateKeys("42B"), ["42B"])
        XCTAssertEqual(gateKeys("Gate 042b"), ["42B"])
        XCTAssertEqual(gateKeys("T3-42B"), ["T342B", "42B"])
        XCTAssertEqual(gateKeys("A 07"), ["A7"])
        XCTAssertEqual(gateKeys("10"), ["10"])
        XCTAssertEqual(gateKeys("0"), ["0"])
        XCTAssertEqual(gateKeys("  "), [])
        XCTAssertEqual(gateKeys(nil), [])
        XCTAssertEqual(gateKeys("Belt 4"), ["4"])
    }

    func testMatchesTheUsersGate() throws {
        let map = try sample()
        XCTAssertEqual(matchGate("42B", in: map).map(\.id), ["node/6"])
        XCTAssertEqual(matchGate("T3-42b", in: map).map(\.id), ["node/6"])
        XCTAssertEqual(matchGate("7", in: map).map(\.id), ["node/9"], "a gate mapped only by name")
        XCTAssertEqual(matchGate("99", in: map), [])
        XCTAssertEqual(matchGate(nil, in: map), [])
        // Two terminals reuse gate 12: the flight's terminal decides.
        XCTAssertEqual(Set(matchGate("12", in: map).map(\.id)), ["node/7", "node/8"])
        XCTAssertEqual(matchGate("12", in: map, within: matchTerminal("T3", in: map)).map(\.id), ["node/7"])
        XCTAssertEqual(matchGate("12", in: map, within: matchTerminal("1", in: map)).map(\.id), ["node/8"])
    }

    func testTerminalAndBelt() throws {
        let map = try sample()
        XCTAssertEqual(terminalKey("Terminal 3"), "3")
        XCTAssertEqual(terminalKey("T3"), "3")
        XCTAssertEqual(terminalKey("t-1d"), "1D")
        XCTAssertNil(terminalKey(" "))
        XCTAssertEqual(matchTerminal("3", in: map).map(\.id), ["way/3"])
        XCTAssertEqual(matchTerminal("T1", in: map).map(\.id), ["relation/4"])
        XCTAssertEqual(matchTerminal("2", in: map), [])
        XCTAssertEqual(matchBeltArea("4", in: map).map(\.id), ["way/5"])
        XCTAssertEqual(matchBeltArea("9", in: map).map(\.id), ["way/5"], "a known belt not mapped by number shows the claim area")
        XCTAssertEqual(matchBeltArea(nil, in: map), [])
    }

    func testPOIsAndSearch() throws {
        let pois = terminalPOIs(try sample())
        XCTAssertEqual(pois.map(\.id), ["node/13", "node/10", "node/11", "node/12", "node/15", "node/14"])
        XCTAssertEqual(pois.map(\.category), [.food, .food, .toilets, .lounge, .atm, .prayerRoom])
        let label: (POICategory) -> String = { category in
            switch category {
            case .food: return "Food and drink"
            case .toilets: return "Toilets"
            case .lounge: return "Lounges"
            case .atm: return "ATM and money"
            case .prayerRoom: return "Prayer room"
            case .pharmacy: return "Pharmacy"
            case .charging: return "Charging"
            case .shop: return "Shops"
            }
        }
        XCTAssertEqual(filterPOIs(pois, query: "cafe", label: label).map(\.id), ["node/10"], "diacritics ignored")
        XCTAssertEqual(filterPOIs(pois, query: "TOILET", label: label).map(\.id), ["node/11"])
        XCTAssertEqual(filterPOIs(pois, query: "food", label: label).count, 2)
        XCTAssertEqual(filterPOIs(pois, query: "level 1", label: label).map(\.id), ["node/10"])
        XCTAssertEqual(filterPOIs(pois, query: "", category: .lounge, label: label).map(\.id), ["node/12"])
        XCTAssertEqual(filterPOIs(pois, query: "  ", label: label).count, 6)
        XCTAssertEqual(filterPOIs(pois, query: "sbi atm", label: label).map(\.id), ["node/15"])
        XCTAssertEqual(filterPOIs(pois, query: "pizza", label: label), [])
    }

    func testRequestsFromAFlight() {
        var flight = Flight(
            id: "f1", carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate.parse("2026-11-28")!,
            depIata: "DEL", arrIata: "BOM", airlineName: "IndiGo", status: .scheduled
        )
        flight.depGate = "42B"
        flight.depTerminal = "3"
        flight.baggageBelt = "7"
        flight.arrTerminal = "2"
        XCTAssertEqual(TerminalMapRequest.departure(flight), TerminalMapRequest(iata: "DEL", gate: "42B", terminal: "3"))
        XCTAssertEqual(TerminalMapRequest.arrival(flight), TerminalMapRequest(iata: "BOM", belt: "7", terminal: "2"))
        XCTAssertTrue(terminalMapCovers("del"))
        XCTAssertFalse(terminalMapCovers("GOI"))
    }
}

@MainActor
final class TerminalMapModelTests: XCTestCase {
    func testLoadsAndHighlights() async throws {
        let loader = P4FakeTerminalLoader(try p4Fixture("terminal_map_sample", "geojson"))
        let model = TerminalMapModel(request: TerminalMapRequest(iata: "DEL", gate: "12", terminal: "T3"), loader: loader)
        await model.load()
        XCTAssertEqual(model.state.phase, .ready)
        XCTAssertNotNil(model.state.geojson)
        XCTAssertEqual(model.state.gateFeatures.map(\.id), ["node/7"])
        XCTAssertEqual(model.state.terminalFeatures.map(\.id), ["way/3"])
        XCTAssertFalse(model.state.gateNotMapped)
        XCTAssertEqual(model.state.highlightedIds, ["node/7", "way/3"])
        XCTAssertEqual(model.state.focus?.center.lon ?? 0, 77.0845, accuracy: 1e-9, "the camera starts on the gate")
        XCTAssertEqual(model.state.categories, [.food, .toilets, .lounge, .atm, .prayerRoom])

        model.update(gate: "99", belt: nil)
        XCTAssertTrue(model.state.gateNotMapped)
        XCTAssertEqual(model.state.focus?.center.lon ?? 0, 77.085, accuracy: 1e-9, "then the terminal")

        model.setCategory(.food)
        XCTAssertEqual(model.visiblePOIs(label: { $0.rawValue }).count, 2)
        model.setCategory(.food)
        XCTAssertNil(model.state.category, "tapping the chip again clears it")
        model.setQuery("encalm")
        XCTAssertEqual(model.visiblePOIs(label: { $0.rawValue }).map(\.id), ["node/12"])
        model.select("node/12")
        XCTAssertEqual(model.state.selectedPOI, "node/12")
    }

    func testMissingFileIsNotAvailableYet() async {
        let model = TerminalMapModel(request: TerminalMapRequest(iata: "GOI"), loader: P4FakeTerminalLoader(nil))
        await model.load()
        XCTAssertEqual(model.state.phase, .notAvailable)
        let empty = TerminalMapModel(
            request: TerminalMapRequest(iata: "DEL"),
            loader: P4FakeTerminalLoader(Data(#"{"type":"FeatureCollection","features":[]}"#.utf8))
        )
        await empty.load()
        XCTAssertEqual(empty.state.phase, .notAvailable)
    }
}
