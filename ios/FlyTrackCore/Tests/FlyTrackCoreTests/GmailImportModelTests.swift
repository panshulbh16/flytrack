import Foundation
import XCTest
@testable import FlyTrackCore

private let today = LocalDate(year: 2026, month: 11, day: 24)

@MainActor
final class GmailImportModelTests: XCTestCase {
    func testStartScansAndShowsOnlyUpcomingPendingUntrackedSuggestions() async {
        let rig = Rig(email: "me@gmail.com")
        rig.store.items = [
            suggestion("6E2341_2026-12-03", "6E 2341", 3),
            suggestion("AI101_2026-11-30", "AI 101", 30, status: .dismissed),
            suggestion("QP1105_2026-11-20", "QP 1105", 20, month: 11),
            suggestion("6E7_2026-12-05", "6E 7", 5),
        ]
        rig.tracked.keys = [TrackedKey(flightCompact: "6E7", date: LocalDate(year: 2026, month: 12, day: 5))]
        rig.scanner.cleanupResult = GmailCleanup(deleteIds: ["QP1105_2026-11-20"], markAddedIds: ["6E7_2026-12-05"])

        await rig.model.start()

        XCTAssertEqual(rig.scanner.scans, 1)
        XCTAssertEqual(rig.model.state.scan, .results)
        XCTAssertEqual(rig.model.state.email, "me@gmail.com")
        XCTAssertEqual(rig.model.state.suggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(rig.system.cancelled, [["QP1105_2026-11-20", "6E7_2026-12-05"]])
    }

    func testNotConnectedShowsConnectAndDoesNotScan() async {
        let rig = Rig(email: nil)
        await rig.model.start()
        XCTAssertEqual(rig.model.state.scan, .notConnected)
        XCTAssertEqual(rig.scanner.scans, 0)
    }

    func testEmptyOfflineAndLostAccessScans() async {
        let rig = Rig(email: "me@gmail.com")
        await rig.model.start()
        XCTAssertEqual(rig.model.state.scan, .noSuggestions)

        rig.scanner.result = .offline
        await rig.model.scan()
        XCTAssertEqual(rig.model.state.scan, .offline)

        rig.scanner.result = .needsReconnect
        await rig.model.scan()
        XCTAssertEqual(rig.model.state.scan, .needsReconnect)
    }

    func testScanStoppedEarlyWithNothingPendingOffersScanAgain() async {
        let rig = Rig(email: "me@gmail.com")
        rig.scanner.result = .partial // what the 50-email read cap gives
        await rig.model.start()
        XCTAssertEqual(rig.model.state.scan, .moreToRead)

        rig.store.items = [suggestion("6E2341_2026-12-03", "6E 2341", 3)]
        await rig.model.scan()
        XCTAssertEqual(rig.model.state.scan, .results) // a stopped scan still lists what it has
        await rig.model.dismiss("6E2341_2026-12-03")
        XCTAssertEqual(rig.model.state.scan, .moreToRead)

        rig.scanner.result = .completed
        await rig.model.scan() // "Scan again"
        XCTAssertEqual(rig.model.state.scan, .noSuggestions)
        XCTAssertEqual(rig.scanner.scans, 3)
    }

    func testNeedsReconnectWaitsForTheUser() async {
        let rig = Rig(email: "me@gmail.com")
        rig.settings.needsReconnect = true
        await rig.model.start()
        XCTAssertEqual(rig.model.state.scan, .needsReconnect)
        XCTAssertEqual(rig.scanner.scans, 0)
    }

    func testConnectSavesStateSchedulesChecksAndScans() async {
        let rig = Rig(email: nil)
        rig.settings.needsReconnect = true
        rig.system.connectResult = .granted("token")
        rig.system.onConnect = { rig.settings.email = "me@gmail.com" }
        await rig.model.start()

        await rig.model.connect()

        XCTAssertFalse(rig.settings.needsReconnect)
        XCTAssertEqual(rig.system.background, [true])
        XCTAssertEqual(rig.scanner.scans, 1)
        XCTAssertEqual(rig.model.state.scan, .noSuggestions)
        XCTAssertFalse(rig.model.state.busy)
    }

    func testCancelledOrFailedConnect() async {
        let rig = Rig(email: nil)
        rig.system.connectResult = .needsUser
        await rig.model.connect()
        XCTAssertEqual(rig.model.state.scan, .notConnected)
        XCTAssertFalse(rig.model.state.connectFailed)

        rig.system.connectResult = .offline
        await rig.model.connect()
        XCTAssertTrue(rig.model.state.connectFailed)
        XCTAssertEqual(rig.scanner.scans, 0)
        XCTAssertEqual(rig.system.background, [])
    }

    func testDismissMarksDismissedAndCancelsTheNotification() async {
        let rig = Rig(email: "me@gmail.com")
        rig.store.items = [suggestion("6E2341_2026-12-03", "6E 2341", 3)]
        await rig.model.refresh()

        await rig.model.dismiss("6E2341_2026-12-03")

        XCTAssertEqual(rig.store.items.first?.status, .dismissed)
        XCTAssertEqual(rig.system.cancelled, [["6E2341_2026-12-03"]])
        XCTAssertTrue(rig.model.state.suggestions.isEmpty)
        XCTAssertEqual(rig.model.state.scan, .noSuggestions)
    }

    func testRefreshReadsStoredSuggestionsWithoutScanning() async {
        let rig = Rig(email: "me@gmail.com")
        rig.store.items = [suggestion("6E2341_2026-12-03", "6E 2341", 3)]
        await rig.model.refresh()
        XCTAssertEqual(rig.model.state.suggestions.count, 1)
        XCTAssertEqual(rig.scanner.scans, 0)
    }

    func testAutoCheckSwitch() async {
        let rig = Rig(email: "me@gmail.com")
        await rig.model.refresh()
        XCTAssertTrue(rig.model.state.autoCheck)
        await rig.model.setAutoCheck(false)
        XCTAssertFalse(rig.settings.autoCheck)
        XCTAssertFalse(rig.model.state.autoCheck)
        await rig.model.setAutoCheck(true)
        XCTAssertEqual(rig.system.background, [false, true])
    }

    func testDisconnectRevokesAndClearsEverything() async {
        let rig = Rig(email: "me@gmail.com")
        rig.settings.autoCheck = false
        rig.settings.lastCheck = 1_700_000_000
        rig.settings.lastAppOpenCheck = 1_700_000_000
        rig.store.items = [suggestion("6E2341_2026-12-03", "6E 2341", 3)]
        await rig.model.refresh()
        var emailAtClear: String? = "unset"
        rig.store.onClear = { emailAtClear = rig.settings.email }

        await rig.model.disconnect()

        XCTAssertNil(emailAtClear) // forgotten first, so a scan still running writes nothing back
        XCTAssertEqual(rig.system.revokes, 1)
        XCTAssertEqual(rig.system.background, [false])
        XCTAssertEqual(rig.system.cancelAllCalls, 1)
        XCTAssertTrue(rig.store.items.isEmpty)
        XCTAssertNil(rig.settings.email)
        XCTAssertNil(rig.settings.lastCheck)
        XCTAssertNil(rig.settings.lastAppOpenCheck)
        XCTAssertTrue(rig.settings.autoCheck)
        XCTAssertEqual(rig.model.state.scan, .notConnected)
        XCTAssertTrue(rig.model.state.suggestions.isEmpty)
    }

    func testSuggestionOpensAddFlightPrefilled() {
        let prefill = suggestion("6E2341_2026-12-03", "6E 2341", 3).addFlightPrefill
        XCTAssertEqual(prefill.flight, "6E 2341")
        XCTAssertEqual(prefill.date, "2026-12-03")
        XCTAssertEqual(prefill.pnr, "ABC123")
        XCTAssertEqual(prefill.source, .gmail)
        XCTAssertEqual(prefill.gmailSuggestionId, "6E2341_2026-12-03")
    }

    func testAddFlightMarksTheSuggestionAddedOnlyAfterTracking() async {
        let repo = FakeFlights()
        let store = GmailMemoryStore()
        let system = GmailFakeSystem()
        store.items = [suggestion("6E2341_2026-11-25", "6E 2341", 25, month: 11)]
        let model = AddFlightModel(
            prefill: suggestion("6E2341_2026-11-25", "6E 2341", 25, month: 11).addFlightPrefill,
            flights: repo, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!), homeTimeZone: "Asia/Kolkata",
            backendConfigured: true, googleConfigured: false, isAnonymous: true, defaultAlerts: MemoryAlerts(),
            gmailStore: store, gmailSystem: system
        )

        repo.trackResult = .offline
        await model.track(flightId: "f1")
        XCTAssertEqual(store.items.first?.status, .pending)
        XCTAssertTrue(system.cancelled.isEmpty)

        repo.trackResult = .tracked(TrackedFlight(id: "t1", userId: "user-1", flightId: "f1", source: .gmail))
        await model.track(flightId: "f1")
        XCTAssertEqual(model.state.finishedFlightId, "f1")
        XCTAssertEqual(store.items.first?.status, .added)
        XCTAssertEqual(system.cancelled, [["6E2341_2026-11-25"]])
    }
}

private func suggestion(_ id: String, _ flight: String, _ day: Int, month: Int = 12, status: GmailSuggestionStatus = .pending) -> GmailSuggestion {
    GmailSuggestion(
        id: id, messageId: "m-\(id)", flight: flight, date: LocalDate(year: 2026, month: month, day: day),
        fromIata: "DEL", toIata: "BOM", pnr: "ABC123", subject: "Your IndiGo booking", status: status, foundAt: 1
    )
}

@MainActor
private final class Rig {
    let scanner = GmailFakeScanner()
    let store = GmailMemoryStore()
    let settings = FakeGmailSettings()
    let tracked = FakeTrackedKeys()
    let system = GmailFakeSystem()
    lazy var model = GmailImportModel(scanner: scanner, store: store, settings: settings, tracked: tracked, system: system, today: { today })

    init(email: String?) { settings.email = email }
}

@MainActor
private final class GmailFakeScanner: GmailScanning {
    var result: GmailScanResult = .completed
    var cleanupResult = GmailCleanup(deleteIds: [], markAddedIds: [])
    var scans = 0
    func scan() async -> GmailScanOutcome {
        scans += 1
        return GmailScanOutcome(result: result, newSuggestions: [])
    }
    func cleanup() async -> GmailCleanup { cleanupResult }
}

@MainActor
private final class GmailMemoryStore: GmailSuggestionStoring {
    var items: [GmailSuggestion] = []
    func all() async -> [GmailSuggestion] { items }
    func get(_ id: String) async -> GmailSuggestion? { items.first { $0.id == id } }
    var onClear: () -> Void = {}
    func insert(_ suggestions: [GmailSuggestion]) async {
        for suggestion in suggestions where !items.contains(where: { $0.id == suggestion.id }) { items.append(suggestion) }
    }
    func setStatus(_ ids: [String], _ status: GmailSuggestionStatus) async {
        for index in items.indices where ids.contains(items[index].id) { items[index].status = status }
    }
    func delete(_ ids: [String]) async { items.removeAll { ids.contains($0.id) } }
    func seen(_ messageIds: [String]) async -> Set<String> { [] }
    func markSeen(_ messageIds: [String]) async {}
    func clear() async {
        onClear()
        items = []
    }
}

@MainActor
private final class GmailFakeSystem: GmailSystem {
    var connectResult: GmailTokenResult = .needsUser
    var onConnect: () -> Void = {}
    var revokes = 0
    var cancelled: [[String]] = []
    var cancelAllCalls = 0
    var background: [Bool] = []
    func connect() async -> GmailTokenResult {
        onConnect()
        return connectResult
    }
    func revoke() async { revokes += 1 }
    func cancelNotifications(_ ids: [String]) async { cancelled.append(ids) }
    func cancelAllNotifications() async { cancelAllCalls += 1 }
    func setBackgroundChecks(_ on: Bool) { background.append(on) }
}
