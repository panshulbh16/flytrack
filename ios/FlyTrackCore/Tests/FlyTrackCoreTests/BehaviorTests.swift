import Foundation
import XCTest
@testable import FlyTrackCore

final class PushTests: XCTestCase {
    func testCopiedFCMFixture() throws {
        let data = try Data(contentsOf: Bundle.module.url(forResource: "fcm_messages", withExtension: "json", subdirectory: "Fixtures")!)
        let object = try XCTUnwrap(JSON.object(from: data))
        let alert = object["alert"] as! [String: Any]
        let strings = alert.compactMapValues { JSON.string($0) }
        let parsed = parseIncomingPush(strings)
        guard case .alert(let message) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertEqual(message.flightId, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        XCTAssertEqual(message.eventType, .gateChange)
        XCTAssertEqual(message.channelId, "flight_critical")
        XCTAssertEqual(message.tag, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa:gate")
        XCTAssertEqual(message.interruptionLevel, "time-sensitive")
        XCTAssertEqual(message.title, "Gate change: 6E 2341 → Gate 31 (T1)")

        let sync = (object["sync"] as! [String: Any]).compactMapValues { JSON.string($0) }
        guard case .sync(let id, let json) = parseIncomingPush(sync) else { return XCTFail() }
        XCTAssertEqual(id, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        let merged = mergeCompactFlight(json: json!, into: nil)
        XCTAssertEqual(merged?.designator, "6E 2341")
        XCTAssertEqual(merged?.status, .boarding)
        XCTAssertEqual(merged?.depGate, "31")
        XCTAssertEqual(FlightTimeFormat.time(merged!.estDep!, tz: "Asia/Kolkata"), "19:05")
    }

    func testApnsEnvelopeOverridesTheFallback() throws {
        // Shape produced by buildAlertMessage / buildSyncMessage in notify.ts.
        let json = """
        {"data":{"kind":"alert","flight_id":"f1","event_id":"e1","event_type":"DEPARTED","title":"Gone","body":"In the air"},
         "apns":{"headers":{"apns-push-type":"alert","apns-priority":"10","apns-collapse-id":"f1:departed"},
                 "payload":{"aps":{"interruption-level":"active","thread-id":"f1:departed","category":"OPEN_FLIGHT"}}}}
        """.data(using: .utf8)!
        guard case .alert(let message) = parseIncomingPush(messageJSON: json) else { return XCTFail() }
        XCTAssertEqual(message.channelId, "flight_updates")
        XCTAssertEqual(message.interruptionLevel, "active")
        XCTAssertEqual(message.tag, "f1:departed")
        XCTAssertEqual(message.notificationId, notificationId(for: "f1:departed"))
    }

    func testSyncApnsIsBackgroundAndCollapses() throws {
        let json = """
        {"data":{"kind":"sync","flight_id":"f1","flight":"{\\"id\\":\\"f1\\",\\"c\\":\\"6E\\",\\"n\\":\\"1\\",\\"d\\":\\"2026-11-25\\",\\"dep\\":\\"DEL\\",\\"arr\\":\\"BOM\\",\\"u\\":\\"2026-11-25T12:00:00Z\\"}"},
         "apns":{"headers":{"apns-push-type":"background","apns-priority":"5","apns-collapse-id":"sync:f1"},
                 "payload":{"aps":{"content-available":1}}}}
        """.data(using: .utf8)!
        guard case .sync(let id, let flight) = parseIncomingPush(messageJSON: json) else { return XCTFail() }
        XCTAssertEqual(id, "f1")
        XCTAssertEqual(mergeCompactFlight(json: flight!, into: nil)?.flightNumber, "1")
    }

    func testStaleCompactPayloadIsIgnored() {
        let existing = Flight(
            id: "f1", carrierIata: "6E", flightNumber: "1", flightDate: LocalDate.parse("2026-11-25")!,
            depIata: "DEL", arrIata: "BOM", depGate: "12", updatedAt: parseInstant("2026-11-25T13:00:00Z")
        )
        let older = #"{"id":"f1","c":"6E","n":"1","d":"2026-11-25","dep":"DEL","arr":"BOM","dg":"99","u":"2026-11-25T12:00:00Z"}"#
        XCTAssertNil(mergeCompactFlight(json: older, into: existing))
        let newer = #"{"id":"f1","c":"6E","n":"1","d":"2026-11-25","dep":"DEL","arr":"BOM","dg":"31","u":"2026-11-25T14:00:00Z"}"#
        XCTAssertEqual(mergeCompactFlight(json: newer, into: existing)?.depGate, "31")
        XCTAssertEqual(mergeCompactFlight(json: newer, into: existing)?.depCheckin, nil)
    }

    func testUnknownAlertStillShowsOnTheCriticalChannel() {
        let parsed = parseIncomingPush(["kind": "alert", "flight_id": "f1", "event_type": "FUTURE", "title": "New"])
        guard case .alert(let message) = parsed else { return XCTFail() }
        XCTAssertNil(message.eventType)
        XCTAssertEqual(message.channelId, "flight_critical")
        XCTAssertEqual(message.interruptionLevel, "time-sensitive")
    }

    func testMissingFlightIsIgnored() {
        XCTAssertEqual(parseIncomingPush(["kind": "alert", "title": "Hi"]), .ignored)
    }

    func testLaunchOpensTheFlightFromAPushOrURL() {
        let id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        XCTAssertEqual(resolveLaunch(url: nil, flightId: id, shared: nil), .openFlight(id))
        XCTAssertEqual(resolveLaunch(url: URL(string: "heygate://flight/\(id)"), flightId: nil, shared: nil), .openFlight(id))
        XCTAssertNil(resolveLaunch(url: URL(string: "heygate://flight/not-a-uuid"), flightId: nil, shared: nil))
        let share = SharedContent(text: "6E 2341")
        XCTAssertEqual(resolveLaunch(url: nil, flightId: nil, shared: share), .importShared(share))
    }
}

final class PresentationTests: XCTestCase {
    func testDelayedCardUsesTheAirportClock() {
        let flight = sampleFlight(estDep: "2026-11-25T13:20:00Z", schedDep: "2026-11-25T12:55:00Z")
        let card = flight.card(now: parseInstant("2026-11-22T10:00:00Z")!)
        XCTAssertEqual(card.statusWord, .delayed)
        XCTAssertEqual(card.tone, .delayed)
        XCTAssertEqual(card.departure?.time, "18:50")
        XCTAssertEqual(card.departure?.replacedTime, "18:25")
        XCTAssertEqual(card.depCity, "New Delhi")
        if case .departsOn(.inDays(let days)) = card.countdown {
            XCTAssertEqual(days, 3)
        } else {
            XCTFail("\(String(describing: card.countdown))")
        }
    }

    func testPastAndUpcoming() {
        let soon = sampleFlight(estDep: "2026-11-25T13:20:00Z", schedDep: "2026-11-25T13:20:00Z")
        let landed = sampleFlight(id: "landed", actArr: "2026-11-20T12:00:00Z", status: .landed)
        let sections = flightSections([
            TrackedFlightDetails(tracking: sampleTracking(flightId: soon.id), flight: soon),
            TrackedFlightDetails(tracking: sampleTracking(id: "t2", flightId: landed.id), flight: landed),
        ], now: parseInstant("2026-11-22T10:00:00Z")!)
        XCTAssertEqual(sections.upcoming.map(\.flightId), [soon.id])
        XCTAssertEqual(sections.past.map(\.flightId), [landed.id])
        XCTAssertEqual(sections.next?.card.flightId, soon.id)
    }

    func testSearchResponseDecodesProInsight() throws {
        let data = try Data(contentsOf: Bundle.module.url(forResource: "search_response", withExtension: "json", subdirectory: "Fixtures")!)
        let flights = flights(fromSearch: data)
        XCTAssertEqual(flights.count, 2)
        XCTAssertEqual(flights[0].designator, "6E 2341")
        XCTAssertEqual(flights[0].depAirport?.tz, "Asia/Kolkata")
        XCTAssertTrue(flights[0].hasProInsight)
        XCTAssertEqual(flights[0].prediction?.lateMin, 45)
        XCTAssertEqual(flights[0].prediction?.inbound?.flight, "6E 2032")
        let detail = flights[0].detail(tracking: nil, isPro: false, now: parseInstant("2026-11-22T10:00:00Z")!)
        XCTAssertEqual(detail.proInsight, .teaser)
        let pro = flights[0].detail(tracking: nil, isPro: true, now: parseInstant("2026-11-22T10:00:00Z")!)
        if case .details(_, _, _, _, let late, _, _) = pro.proInsight {
            XCTAssertEqual(late, 45)
        } else {
            XCTFail("\(pro.proInsight)")
        }
    }
}

@MainActor
final class ViewModelTests: XCTestCase {
    func testAddFlightDoesNotSearchInvalidInput() async {
        let repo = FakeFlights()
        let model = makeAdd(repo)
        await model.search()
        XCTAssertEqual(model.state.flightError, .required)
        XCTAssertTrue(repo.searches.isEmpty)
    }

    func testAddFlightSearchAndTrack() async {
        let repo = FakeFlights()
        let flight = sampleFlight()
        repo.searchResult = .found([flight])
        repo.trackResult = .tracked(sampleTracking(flightId: flight.id))
        let model = makeAdd(repo)
        model.setFlightInput("6e-2341")
        await model.search()
        if case .results(let query, let legs) = model.state.search {
            XCTAssertEqual(query.flight, "6E 2341")
            XCTAssertEqual(legs.count, 1)
        } else {
            XCTFail("\(model.state.search)")
        }
        await model.track(flightId: flight.id)
        XCTAssertEqual(model.state.finishedFlightId, flight.id)
        XCTAssertEqual(repo.trackCalls.count, 1)
    }

    func testAddFlightRateLimitAndNotFound() async {
        let repo = FakeFlights()
        repo.searchResult = .rateLimited
        let model = makeAdd(repo)
        model.setFlightInput("6E 2341")
        await model.search()
        XCTAssertEqual(model.state.search, .rateLimited)
        repo.searchResult = .notFound
        model.setFlightInput("6E 9999")
        await model.search()
        if case .notFound = model.state.search {} else { XCTFail("\(model.state.search)") }
    }

    func testAddFlightLimit() async {
        let repo = FakeFlights()
        repo.trackResult = .trackingLimitReached("10")
        let model = makeAdd(repo)
        model.setFlightInput("6E 2341")
        await model.track(flightId: "f1")
        XCTAssertEqual(model.state.trackNotice?.problem, .limitReached(limit: 10, proLimit: 50))
    }

    func testPrefillSearches() async {
        let repo = FakeFlights()
        repo.searchResult = .found([sampleFlight()])
        let alerts = MemoryAlerts()
        let model = AddFlightModel(
            prefill: AddFlightPrefill(flight: "6E 2341", date: "2026-11-25", pnr: "ab-c123", source: .pdf),
            flights: repo, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!), homeTimeZone: "Asia/Kolkata",
            backendConfigured: true, googleConfigured: true, isAnonymous: true, defaultAlerts: alerts
        )
        XCTAssertEqual(model.state.prefillNotice, .checkDetails)
        XCTAssertEqual(model.state.pnrInput, "ABC123")
        await model.start()
        if case .results = model.state.search {} else { XCTFail("\(model.state.search)") }
        XCTAssertEqual(repo.searches.first?.0, "6E 2341")
    }

    func testFlightsListAndArchive() async {
        let repo = FakeFlights()
        let flight = sampleFlight()
        repo.tracked = [TrackedFlightDetails(tracking: sampleTracking(flightId: flight.id), flight: flight)]
        let model = FlightsModel(flights: repo, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!))
        await model.reload(session: .signedIn(userId: "u", isAnonymous: true, email: nil))
        XCTAssertEqual(model.state.upcoming.count, 1)
        XCTAssertEqual(model.state.next?.countdown, model.state.upcoming[0].countdown)
        await model.archive(flightId: flight.id, session: .signedIn(userId: "u", isAnonymous: true, email: nil))
        XCTAssertEqual(model.state.message, .archived(flight.id))
    }

    func testSettingsAnonymousAndPushNotConfigured() async {
        let auth = FakeAuth()
        let model = SettingsModel(
            auth: auth, profiles: FakeProfiles(), settings: MemorySettings(), alerts: MemoryAlerts(),
            health: FakeHealth(), push: FakePush(), clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!),
            googleConfigured: false, versionName: "0.1.0"
        )
        await model.load()
        XCTAssertEqual(model.state.account, .anonymous(userId: "user-1"))
        XCTAssertEqual(model.state.push, .notConfigured)
        XCTAssertFalse(model.state.cloudConsent)
        XCTAssertFalse(model.beginGoogleLink())
        XCTAssertEqual(model.state.message, .googleNotConfigured)
        await model.setCloudConsent(true)
        XCTAssertTrue(model.state.cloudConsent)
        let inbound = model.state.alerts.first { $0.group == .inboundAircraft }
        XCTAssertEqual(inbound?.locked, true)
    }

    func testGoogleAlreadyInUseKeepsTheToken() async {
        let auth = FakeAuth()
        auth.linkResult = .googleAccountInUse
        let model = SettingsModel(
            auth: auth, profiles: FakeProfiles(), settings: MemorySettings(), alerts: MemoryAlerts(),
            health: FakeHealth(), push: FakePush(), clock: SystemClock(), googleConfigured: true, versionName: "0.1.0"
        )
        XCTAssertTrue(model.beginGoogleLink())
        await model.linkGoogle(idToken: "token")
        XCTAssertEqual(model.state.message, .googleInUse)
        XCTAssertEqual(model.state.pendingGoogleToken, "token")
    }

    func testImportChoosesAmongFlightsAndSkipsCloudWhenLocalSucceeds() async {
        let cloud = FakeCloud()
        let model = ImportTicketModel(
            shared: SharedContentHolder(), extractor: FakeExtractor(text: "6E 2341\nAI 101\n25 Nov 2026"),
            cloud: cloud, settings: MemorySettings(), today: LocalDate(year: 2026, month: 11, day: 22)
        )
        await model.start()
        await model.picked(uris: ["file://ticket.pdf"], mimeType: "application/pdf")
        XCTAssertEqual(model.state.phase, .choose)
        XCTAssertEqual(model.state.flights.count, 2)
        XCTAssertEqual(cloud.calls, 0)
        model.select(1)
        model.continueWithSelection()
        XCTAssertEqual(model.state.parsed?.source, "pdf")
    }

    func testCloudRunsOnlyWithConsentWhenNothingWasRead() async {
        let settings = MemorySettings()
        await settings.setCloudTicketParsingConsent(true)
        let cloud = FakeCloud()
        cloud.result = .parsed(ParsedTicket(flights: [ParsedFlight(flight: parseFlightNumber("6E 1")!, date: nil, fromIata: nil, toIata: nil)], pnr: nil))
        let model = ImportTicketModel(
            shared: SharedContentHolder(), extractor: FakeExtractor(text: "no flight here"),
            cloud: cloud, settings: settings, today: LocalDate(year: 2026, month: 11, day: 22)
        )
        await model.start()
        await model.picked(uris: ["file://shot.png"], mimeType: "image/png")
        XCTAssertEqual(cloud.calls, 1)
        XCTAssertEqual(model.state.flights.first?.flight.display, "6E 1")
    }
}

// MARK: - fakes

@MainActor
final class FakeFlights: FlightRepository {
    var tracked: [TrackedFlightDetails] = []
    var cached: [String: Flight] = [:]
    var searchResult: SearchResult = .notFound
    var trackResult: TrackResult = .failed(nil)
    var actionResult: ActionResult = .success
    var searches: [(String, LocalDate)] = []
    var trackCalls: [String] = []

    func trackedFlights() async -> [TrackedFlightDetails] { tracked.filter { !$0.tracking.isArchived } }
    func trackedFlight(flightId: String) async -> TrackedFlightDetails? { tracked.first { $0.flight.id == flightId } }
    func flight(id: String) async -> Flight? { cached[id] }
    func search(flightText: String, date: LocalDate) async -> SearchResult {
        searches.append((flightText, date))
        return searchResult
    }
    func track(flightId: String, extras: TrackExtras) async -> TrackResult {
        trackCalls.append(flightId)
        return trackResult
    }
    func untrack(flightId: String) async -> ActionResult { actionResult }
    func archive(flightId: String, archived: Bool) async -> ActionResult { actionResult }
    func updateNotifyPrefs(flightId: String, prefs: [AlertType: Bool]) async -> ActionResult { actionResult }
    func updateTrackingDetails(flightId: String, extras: TrackExtras) async -> ActionResult { actionResult }
    func refresh() async -> ActionResult { actionResult }
    func applyServerSync(flightId: String, compactFlightJSON: String?) async -> ActionResult { actionResult }
}

@MainActor
final class FakeAuth: AuthRepository {
    var current: SessionState = .signedIn(userId: "user-1", isAnonymous: true, email: nil)
    var linkResult: AuthResult = .success
    func session() async -> SessionState { current }
    func ensureSignedIn() async -> AuthResult { .success }
    func linkGoogle(idToken: String, nonce: String?) async -> AuthResult { linkResult }
    func signInWithGoogle(idToken: String, nonce: String?) async -> AuthResult { .success }
    func signOut() async -> AuthResult { .success }
}

@MainActor
final class FakeProfiles: ProfileRepository {
    var current: UserProfile? = UserProfile(id: "user-1", displayName: nil, plan: .free, proUntil: nil)
    func profile() async -> UserProfile? { current }
    func refresh() async -> ActionResult { .success }
}

@MainActor
final class MemorySettings: SettingsStore {
    var consent = false
    var asked = false
    func cloudTicketParsingConsent() async -> Bool { consent }
    func setCloudTicketParsingConsent(_ granted: Bool) async { consent = granted }
    func notificationPermissionAsked() async -> Bool { asked }
    func setNotificationPermissionAsked(_ asked: Bool) async { self.asked = asked }
}

@MainActor
final class MemoryAlerts: DefaultAlertPreferences {
    var mutedTypes: Set<AlertType> = []
    func muted() async -> Set<AlertType> { mutedTypes }
    func setMuted(_ types: Set<AlertType>, muted: Bool) async {
        if muted { mutedTypes.formUnion(types) } else { mutedTypes.subtract(types) }
    }
}

@MainActor
final class FakeHealth: NotificationHealthSource {
    func read() async -> NotificationHealth { .unknown }
}

@MainActor
final class FakePush: PushControlling {
    func availability() async -> PushAvailability { .notConfigured }
    func beforeSignOut() async {}
}

@MainActor
final class FakeExtractor: TicketTextExtracting {
    var text: String
    init(text: String) { self.text = text }
    func extract(_ request: ExtractRequest) async throws -> ExtractedTicket {
        ExtractedTicket(text: text, source: trackSource(for: [mediaKind(mime: request.mimeType, name: request.uris.first)]), truncated: false)
    }
}

@MainActor
final class FakeCloud: CloudTicketParsing {
    var calls = 0
    var result: CloudParseResult = .failed
    func parse(text: String) async -> CloudParseResult {
        calls += 1
        return result
    }
}

@MainActor
private func makeAdd(_ repo: FakeFlights) -> AddFlightModel {
    AddFlightModel(
        flights: repo, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!), homeTimeZone: "Asia/Kolkata",
        backendConfigured: true, googleConfigured: false, isAnonymous: true, defaultAlerts: MemoryAlerts()
    )
}

private func sampleFlight(
    id: String = "f1",
    estDep: String? = nil,
    schedDep: String? = "2026-11-25T13:20:00Z",
    actArr: String? = nil,
    status: FlightStatus = .scheduled
) -> Flight {
    Flight(
        id: id, carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate.parse("2026-11-25")!,
        depIata: "DEL", arrIata: "BOM", airlineName: "IndiGo", status: status,
        schedDep: parseInstant(schedDep), estDep: parseInstant(estDep), actArr: parseInstant(actArr),
        depAirport: Airport(iata: "DEL", name: "Indira Gandhi International Airport", city: "New Delhi", tz: "Asia/Kolkata", lat: 28.56, lon: 77.10),
        arrAirport: Airport(iata: "BOM", name: "Chhatrapati Shivaji International Airport", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.09, lon: 72.87)
    )
}

private func sampleTracking(id: String = "t1", flightId: String) -> TrackedFlight {
    TrackedFlight(id: id, userId: "user-1", flightId: flightId, source: .manual)
}
