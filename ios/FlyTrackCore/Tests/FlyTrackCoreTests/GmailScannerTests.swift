import Foundation
import XCTest
@testable import FlyTrackCore

private let scanDay = LocalDate(year: 2026, month: 11, day: 24)
private let startMs: Int64 = 1_795_000_000_000
private let indigo = "PNR: ABC123\nIndiGo flight 6E 2341 DEL - BOM\nDeparture 03 Dec 2026 06:15"
private let airIndia = "Booking reference: XYZ789\nAI 101 BOM - DEL on 10 Dec 2026"

/// Gmail sends bodies as unpadded base64url.
private func b64url(_ text: String) -> String {
    Data(text.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

/// A synthetic `messages.get?format=full` payload: a plain body and, optionally, a PDF attachment and the email's date.
private func mail(
    _ id: String, subject: String = "Your booking is confirmed", body: String, pdf: (id: String, size: Int64)? = nil, internalDate: String? = nil
) -> GmailMessage {
    var parts: [[String: Any]] = [
        ["mimeType": "text/plain", "filename": "", "body": ["size": body.utf8.count, "data": b64url(body)]],
    ]
    if let pdf {
        parts.append(["mimeType": "application/pdf", "filename": "ticket.pdf", "body": ["size": Int(pdf.size), "attachmentId": pdf.id]])
    }
    var json: [String: Any] = [
        "id": id,
        "payload": [
            "mimeType": "multipart/mixed", "filename": "", "headers": [["name": "Subject", "value": subject]],
            "body": ["size": 0], "parts": parts,
        ],
    ]
    if let internalDate { json["internalDate"] = internalDate }
    let data = try! JSONSerialization.data(withJSONObject: json)
    return try! JSONDecoder().decode(GmailMessage.self, from: data)
}

// ponytail: the scanner awaits one call at a time, so the fakes need no locks.
final class FakeGmailAPI: GmailAPI, @unchecked Sendable {
    var pages: [String?: (ids: [String], next: String?)] = [:]
    var messages: [String: GmailMessage] = [:]
    var attachments: [String: Data] = [:]
    var validTokens: Set<String> = ["t1"]
    var listFailure: GmailFailure?
    var messageFailures: [String: GmailFailure] = [:]
    var cancelAfterFetching: String?
    /// Runs while a message is being fetched, e.g. to disconnect mid-scan.
    var onFetch: (@MainActor @Sendable (String) async -> Void)?
    var queries: [String] = []
    var fetched: [String] = []
    var fetchedAttachments: [String] = []
    var tokensUsed: [String] = []

    func profile(token: String) async throws -> GmailProfile { throw GmailFailure.other }

    func list(token: String, query: String, pageToken: String?) async throws -> GmailListResponse {
        tokensUsed.append(token)
        guard validTokens.contains(token) else { throw GmailFailure.unauthorized }
        if let listFailure { throw listFailure }
        queries.append(query)
        let page = pages[pageToken] ?? (ids: [], next: nil)
        return GmailListResponse(messages: page.ids.map { GmailMessageRef(id: $0) }, nextPageToken: page.next)
    }

    func message(token: String, id: String) async throws -> GmailMessage {
        tokensUsed.append(token)
        guard validTokens.contains(token) else { throw GmailFailure.unauthorized }
        if let failure = messageFailures[id] { throw failure }
        fetched.append(id)
        if let onFetch { await onFetch(id) }
        if id == cancelAfterFetching { withUnsafeCurrentTask { $0?.cancel() } }
        guard let message = messages[id] else { throw GmailFailure.other }
        return message
    }

    func attachment(token: String, messageId: String, attachmentId: String) async throws -> Data {
        guard validTokens.contains(token) else { throw GmailFailure.unauthorized }
        fetchedAttachments.append(attachmentId)
        guard let data = attachments[attachmentId] else { throw GmailFailure.other }
        return data
    }
}

@MainActor
final class FakeGmailTokens: GmailTokenProviding {
    var silent: GmailTokenResult = .granted("t1")
    var refreshed: GmailTokenResult = .granted("t2")
    var refreshCount = 0
    /// Runs before either call returns, e.g. to disconnect while the token is being fetched.
    var beforeReturning: (() -> Void)?

    func silentToken() async -> GmailTokenResult {
        beforeReturning?()
        return silent
    }
    func refresh() async -> GmailTokenResult {
        refreshCount += 1
        beforeReturning?()
        return refreshed
    }
}

@MainActor
final class FakeGmailStore: GmailSuggestionStoring {
    var rows: [String: GmailSuggestion] = [:]
    var seenIds: Set<String> = []

    func all() async -> [GmailSuggestion] { rows.values.sorted { $0.id < $1.id } }
    func get(_ id: String) async -> GmailSuggestion? { rows[id] }
    func insert(_ suggestions: [GmailSuggestion]) async { for suggestion in suggestions { rows[suggestion.id] = suggestion } }
    func setStatus(_ ids: [String], _ status: GmailSuggestionStatus) async { for id in ids { rows[id]?.status = status } }
    func delete(_ ids: [String]) async { for id in ids { rows[id] = nil } }
    func seen(_ messageIds: [String]) async -> Set<String> { seenIds.intersection(messageIds) }
    func markSeen(_ messageIds: [String]) async { seenIds.formUnion(messageIds) }
    func clear() async {
        rows = [:]
        seenIds = []
    }
}

@MainActor
final class FakeGmailSettings: GmailSettingsStoring {
    var email: String? = "traveller@example.com"
    var autoCheck = true
    var lastCheck: Int64?
    var needsReconnect = false
    var lastAppOpenCheck: Int64?

    func gmailAccountEmail() async -> String? { email }
    func setGmailAccountEmail(_ email: String?) async { self.email = email }
    func gmailAutoCheck() async -> Bool { autoCheck }
    func setGmailAutoCheck(_ enabled: Bool) async { autoCheck = enabled }
    func gmailLastCheck() async -> Int64? { lastCheck }
    func setGmailLastCheck(_ value: Int64?) async { lastCheck = value }
    func gmailNeedsReconnect() async -> Bool { needsReconnect }
    func setGmailNeedsReconnect(_ value: Bool) async { needsReconnect = value }
    func gmailLastAppOpenCheck() async -> Int64? { lastAppOpenCheck }
    func setGmailLastAppOpenCheck(_ value: Int64?) async { lastAppOpenCheck = value }
}

@MainActor
final class FakeTrackedKeys: TrackedKeysProviding {
    var keys: Set<TrackedKey> = []
    func trackedKeys() async -> Set<TrackedKey> { keys }
}

@MainActor
final class FakePDFReader: PDFTextReading {
    var texts: [Data: String] = [:]
    func read(_ data: Data) async -> String { texts[data] ?? "" }
}

@MainActor
final class GmailScannerTests: XCTestCase {
    private var api: FakeGmailAPI!
    private var tokens: FakeGmailTokens!
    private var store: FakeGmailStore!
    private var settings: FakeGmailSettings!
    private var tracked: FakeTrackedKeys!
    private var pdf: FakePDFReader!
    private var nowMs = startMs
    private var tick: Int64 = 0
    private var day = scanDay

    override func setUp() async throws {
        api = FakeGmailAPI()
        tokens = FakeGmailTokens()
        store = FakeGmailStore()
        settings = FakeGmailSettings()
        tracked = FakeTrackedKeys()
        pdf = FakePDFReader()
        nowMs = startMs
        tick = 0
        day = scanDay
    }

    private func scanner() -> GmailScanner {
        GmailScanner(
            api: api, tokens: tokens, store: store, settings: settings, tracked: tracked, pdf: pdf,
            now: { [unowned self] in
                let value = self.nowMs
                self.nowMs += self.tick
                return value
            },
            today: { [unowned self] in self.day }
        )
    }

    /// One listing page holding these messages.
    private func inbox(_ messages: [GmailMessage], next: String? = nil) {
        api.pages[nil] = (ids: messages.map(\.id), next: next)
        for message in messages { api.messages[message.id] = message }
    }

    func testNotConnectedWithoutAnAccount() async {
        settings.email = nil
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome, GmailScanOutcome(result: .notConnected, newSuggestions: []))
        XCTAssertTrue(api.queries.isEmpty)
    }

    func testFirstScanReadsEveryPageAndSavesSuggestions() async {
        inbox([mail("m1", body: indigo)], next: "p2")
        api.pages["p2"] = (ids: ["m2"], next: nil)
        api.messages["m2"] = mail("m2", subject: "Air India e-ticket", body: airIndia)

        let outcome = await scanner().scan()

        XCTAssertEqual(outcome.result, .completed)
        XCTAssertEqual(api.queries, [GmailQuery.build(lastCheckEpochSeconds: nil), GmailQuery.build(lastCheckEpochSeconds: nil)])
        XCTAssertTrue(api.queries[0].hasSuffix(" newer_than:180d"))
        XCTAssertEqual(outcome.newSuggestions.map(\.id).sorted(), ["6E2341_2026-12-03", "AI101_2026-12-10"])
        let indigoRow = await store.get("6E2341_2026-12-03")
        XCTAssertEqual(indigoRow?.status, .pending)
        XCTAssertEqual(indigoRow?.pnr, "ABC123")
        XCTAssertEqual(indigoRow?.messageId, "m1")
        XCTAssertEqual(indigoRow?.subject, "Your booking is confirmed")
        XCTAssertEqual(indigoRow?.foundAt, startMs)
        XCTAssertEqual(store.seenIds, ["m1", "m2"])
        XCTAssertEqual(settings.lastCheck, startMs / 1000)
    }

    func testLaterScanAsksForMailAfterTheLastCheckWithAnHourOfOverlap() async {
        settings.lastCheck = 1_794_000_000
        inbox([])
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .completed)
        XCTAssertEqual(api.queries, [GmailQuery.terms + " after:1793996400"])
        XCTAssertEqual(settings.lastCheck, startMs / 1000)
    }

    func testLastCheckIsTheScanStartNotItsEnd() async {
        tick = 60_000
        inbox([mail("m1", body: indigo)])
        _ = await scanner().scan()
        XCTAssertEqual(settings.lastCheck, startMs / 1000)
        let row = await store.get("6E2341_2026-12-03")
        XCTAssertGreaterThan(row?.foundAt ?? 0, startMs)
    }

    func testTheNewestEmailWinsWhenSeveralMentionOneFlight() async {
        // Gmail lists newest first, so the older email is read last.
        inbox([
            mail("m-new", subject: "Changed", body: indigo.replacingOccurrences(of: "ABC123", with: "NEW222"), internalDate: "1764000000000"),
            mail("m-old", body: indigo, internalDate: "1763000000000"),
        ])
        tick = 60_000
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.newSuggestions.count, 1)
        XCTAssertEqual(outcome.newSuggestions.first?.pnr, "NEW222")
        XCTAssertEqual(outcome.newSuggestions.first?.subject, "Changed")
        XCTAssertEqual(outcome.newSuggestions.first?.messageId, "m-new")
        XCTAssertEqual(outcome.newSuggestions.first?.foundAt, 1_764_000_000_000)
    }

    func testSeenMessagesAreNeitherFetchedNorCounted() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        store.seenIds = ["m1"]
        let outcome = await scanner().scan()
        XCTAssertEqual(api.fetched, ["m2"])
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["AI101_2026-12-10"])
        XCTAssertEqual(outcome.result, .completed)
    }

    func testReadsAtMostFiftyUnseenMessagesAndLeavesLastCheck() async {
        let messages = (1...51).map { mail("m\($0)", body: "nothing to see") }
        inbox(Array(messages[..<30]), next: "p2")
        api.pages["p2"] = (ids: messages[30...].map(\.id), next: nil)
        for message in messages { api.messages[message.id] = message }
        store.seenIds = ["seen-before"]

        let outcome = await scanner().scan()

        XCTAssertEqual(outcome.result, .partial)
        XCTAssertEqual(api.fetched.count, 50)
        XCTAssertEqual(store.seenIds.count, 51) // 50 read + the one seen before
        XCTAssertFalse(store.seenIds.contains("m51"))
        XCTAssertNil(settings.lastCheck)
    }

    func testAMailboxOverTheCapIsFinishedOverSeveralScansAndOnlyTheLastSavesTheCheck() async {
        let messages = (1...119).map { mail("m\($0)", body: "nothing to see") } + [mail("m120", body: indigo)]
        api.pages[nil] = (ids: messages[..<100].map(\.id), next: "p2")
        api.pages["p2"] = (ids: messages[100...].map(\.id), next: nil)
        for message in messages { api.messages[message.id] = message }

        let first = await scanner().scan() // m1...m50
        let second = await scanner().scan() // m51...m100
        XCTAssertEqual([first.result, second.result], [.partial, .partial])
        XCTAssertNil(settings.lastCheck)

        nowMs = startMs + 60_000
        let last = await scanner().scan() // m101...m120
        XCTAssertEqual(last.result, .completed)
        XCTAssertEqual(last.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(api.fetched, messages.map(\.id)) // every email read exactly once
        XCTAssertEqual(Set(api.queries), [GmailQuery.build(lastCheckEpochSeconds: nil)])
        XCTAssertEqual(settings.lastCheck, (startMs + 60_000) / 1000)

        _ = await scanner().scan()
        XCTAssertEqual(api.queries.last, GmailQuery.build(lastCheckEpochSeconds: (startMs + 60_000) / 1000))
    }

    func testDisconnectWhileScanningWritesNothing() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        let store = self.store!, settings = self.settings!
        // Disconnect Gmail (or HeyGate sign-out) lands while m2 is being fetched.
        // Clearing Gmail state forgets the account first, then wipes the store.
        api.onFetch = { id in
            guard id == "m2" else { return }
            await settings.setGmailAccountEmail(nil)
            await store.clear()
        }

        let outcome = await scanner().scan()

        XCTAssertEqual(outcome, GmailScanOutcome(result: .notConnected, newSuggestions: []))
        let rows = await store.all()
        XCTAssertTrue(rows.isEmpty)
        XCTAssertTrue(store.seenIds.isEmpty)
        XCTAssertNil(settings.lastCheck)
        XCTAssertFalse(settings.needsReconnect)
    }

    func testADateWithoutAYearIsReadAgainstTheEmailsDate() async {
        inbox([
            // Received 20 Nov 2026, for a trip on 3 Dec.
            mail("m-new", body: "IndiGo flight 6E 5012 BOM - DEL on 3 Dec", internalDate: "1795176000000"),
            // Received 10 Jun 2026, for a trip on 15 Jul. Read against today (24 Nov 2026), that would be 15 Jul 2027.
            mail("m-old", body: "IndiGo flight 6E 2341 DEL - BOM on 15 Jul", internalDate: "1781092800000"),
        ])

        let outcome = await scanner().scan()

        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E5012_2026-12-03"])
        XCTAssertEqual(store.seenIds, ["m-new", "m-old"])
    }

    func testExactlyFiftyUnseenMessagesCompletes() async {
        inbox((1...50).map { mail("m\($0)", body: "nothing to see") })
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .completed)
        XCTAssertEqual(api.fetched.count, 50)
        XCTAssertEqual(settings.lastCheck, startMs / 1000)
    }

    func testCancellationSubjectIsSkippedButMarkedSeen() async {
        inbox([mail("m1", subject: "Booking CANCELLED: refund initiated", body: indigo)])
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome, GmailScanOutcome(result: .completed, newSuggestions: []))
        XCTAssertEqual(store.seenIds, ["m1"])
    }

    func testPdfIsReadWhenTheBodyHasNoFlight() async {
        inbox([mail("m1", body: "Your e-ticket is attached.", pdf: (id: "a1", size: 48_213))])
        let bytes = Data("%PDF-1.4".utf8)
        api.attachments["a1"] = bytes
        pdf.texts[bytes] = indigo

        let outcome = await scanner().scan()

        XCTAssertEqual(api.fetchedAttachments, ["a1"])
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
    }

    func testPdfIsNotFetchedWhenTheBodyHasAFlightOrTheFileIsOverFiveMegabytes() async {
        inbox([
            mail("m1", body: indigo, pdf: (id: "a1", size: 1_000)),
            mail("m2", body: "Your e-ticket is attached.", pdf: (id: "a2", size: 5_000_001)),
        ])
        let outcome = await scanner().scan()
        XCTAssertTrue(api.fetchedAttachments.isEmpty)
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(store.seenIds, ["m1", "m2"])
    }

    func testPdfAtExactlyFiveMegabytesIsRead() async {
        inbox([mail("m1", body: "Attached.", pdf: (id: "a1", size: 5_000_000))])
        api.attachments["a1"] = Data([1])
        _ = await scanner().scan()
        XCTAssertEqual(api.fetchedAttachments, ["a1"])
    }

    func testUnauthorizedRefreshesTheTokenOnceAndRetries() async {
        api.validTokens = ["t2"]
        inbox([mail("m1", body: indigo)])
        let outcome = await scanner().scan()
        XCTAssertEqual(tokens.refreshCount, 1)
        XCTAssertEqual(api.tokensUsed, ["t1", "t2", "t2"])
        XCTAssertEqual(outcome.result, .completed)
        XCTAssertFalse(settings.needsReconnect)
    }

    func testSecondUnauthorizedMeansReconnect() async {
        api.validTokens = []
        inbox([mail("m1", body: indigo)])
        let outcome = await scanner().scan()
        XCTAssertEqual(tokens.refreshCount, 1)
        XCTAssertEqual(outcome.result, .needsReconnect)
        XCTAssertTrue(settings.needsReconnect)
        XCTAssertNil(settings.lastCheck)
    }

    func testRefreshNeedingTheUserMeansReconnect() async {
        api.validTokens = []
        tokens.refreshed = .needsUser
        inbox([mail("m1", body: indigo)])
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .needsReconnect)
        XCTAssertTrue(settings.needsReconnect)
    }

    func testAFailedRefreshStopsAsPartialAndLeavesTheEmailUnread() async {
        for (refresh, expected) in [(GmailTokenResult.failed, GmailScanResult.partial), (.notConfigured, .partial), (.offline, .offline)] {
            resetFakes(refresh: refresh)
            inbox([mail("m1", body: indigo)])
            api.messageFailures["m1"] = .unauthorized
            let outcome = await scanner().scan()
            XCTAssertEqual(outcome, GmailScanOutcome(result: expected, newSuggestions: []), "\(refresh)")
            XCTAssertTrue(store.seenIds.isEmpty, "\(refresh)")
            XCTAssertFalse(settings.needsReconnect, "\(refresh)")
            XCTAssertNil(settings.lastCheck, "\(refresh)")
        }
    }

    func testAFailedRefreshOnAnAttachmentStopsAsPartial() async {
        inbox([mail("m1", body: "Your e-ticket is attached.", pdf: (id: "a1", size: 1_000))])
        api.attachments["a1"] = Data([1])
        api.validTokens = ["t1"]
        tokens.refreshed = .failed
        // Only the attachment call 401s: the fake checks tokens, so swap the token set once the message is read.
        let api = self.api!
        api.onFetch = { _ in api.validTokens = [] }
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .partial)
        XCTAssertEqual(tokens.refreshCount, 1)
        XCTAssertTrue(store.seenIds.isEmpty)
        XCTAssertFalse(settings.needsReconnect)
        XCTAssertNil(settings.lastCheck)
    }

    func testOneRefreshPerScan() async {
        api.validTokens = ["t2"]
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        api.messageFailures["m2"] = .unauthorized
        let outcome = await scanner().scan()
        XCTAssertEqual(tokens.refreshCount, 1)
        XCTAssertEqual(outcome.result, .needsReconnect)
        XCTAssertEqual(store.seenIds, ["m1"])
    }

    func testDisconnectWhileGettingTheTokenDoesNotFlagReconnect() async {
        let settings = self.settings!
        tokens.beforeReturning = { settings.email = nil }
        tokens.silent = .needsUser
        let silent = await scanner().scan()
        XCTAssertEqual(silent, GmailScanOutcome(result: .notConnected, newSuggestions: []))
        XCTAssertFalse(settings.needsReconnect)

        // The same during the refresh after a 401.
        settings.email = "traveller@example.com"
        tokens.silent = .granted("t1")
        tokens.refreshed = .needsUser
        api.validTokens = []
        inbox([mail("m1", body: indigo)])
        let refreshed = await scanner().scan()
        XCTAssertEqual(refreshed, GmailScanOutcome(result: .notConnected, newSuggestions: []))
        XCTAssertFalse(settings.needsReconnect)
        XCTAssertTrue(store.seenIds.isEmpty)
        XCTAssertNil(settings.lastCheck)
    }

    private func resetFakes(refresh: GmailTokenResult) {
        api = FakeGmailAPI()
        tokens = FakeGmailTokens()
        tokens.refreshed = refresh
        store = FakeGmailStore()
        settings = FakeGmailSettings()
    }

    func testSilentTokenNeedingTheUserMeansReconnect() async {
        tokens.silent = .needsUser
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome, GmailScanOutcome(result: .needsReconnect, newSuggestions: []))
        XCTAssertTrue(settings.needsReconnect)
        XCTAssertTrue(api.queries.isEmpty)
    }

    func testSilentTokenOutcomes() async {
        tokens.silent = .offline
        let offline = await scanner().scan()
        XCTAssertEqual(offline.result, .offline)
        tokens.silent = .notConfigured
        let notConfigured = await scanner().scan()
        XCTAssertEqual(notConfigured.result, .notConnected)
        tokens.silent = .failed
        let failed = await scanner().scan()
        XCTAssertEqual(failed.result, .partial)
        XCTAssertFalse(settings.needsReconnect)
        XCTAssertNil(settings.lastCheck)
    }

    func testAGrantedTokenClearsNeedsReconnect() async {
        settings.needsReconnect = true
        inbox([])
        _ = await scanner().scan()
        XCTAssertFalse(settings.needsReconnect)
    }

    func testAccessLostMidScanKeepsWhatWasFound() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        api.messageFailures["m2"] = .accessLost
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .needsReconnect)
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertTrue(settings.needsReconnect)
        XCTAssertEqual(store.seenIds, ["m1"])
    }

    func testRateLimitStopsAsPartialKeepsFindsAndLeavesLastCheck() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        api.messageFailures["m2"] = .rateLimited
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .partial)
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(store.seenIds, ["m1"])
        XCTAssertNil(settings.lastCheck)
        XCTAssertFalse(settings.needsReconnect)
    }

    func testOfflineStopsAndLeavesLastCheck() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        api.messageFailures["m2"] = .offline
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .offline)
        XCTAssertEqual(store.seenIds, ["m1"])
        XCTAssertNil(settings.lastCheck)
    }

    func testListingFailureStopsBeforeReadingAnything() async {
        settings.lastCheck = 1_794_000_000
        api.listFailure = .rateLimited
        let rateLimited = await scanner().scan()
        XCTAssertEqual(rateLimited.result, .partial)
        api.listFailure = .other
        let other = await scanner().scan()
        XCTAssertEqual(other.result, .partial)
        XCTAssertEqual(settings.lastCheck, 1_794_000_000)
    }

    func testUndecodableMessageIsSkippedAndMarkedSeen() async {
        inbox([mail("m1", body: indigo)])
        api.pages[nil] = (ids: ["gone", "m1"], next: nil)
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome.result, .completed)
        XCTAssertEqual(store.seenIds, ["gone", "m1"])
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
    }

    func testCancelledScanStopsAsPartial() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        api.cancelAfterFetching = "m1"
        let scan = scanner()
        let outcome = await Task { await scan.scan() }.value
        XCTAssertEqual(outcome.result, .partial)
        XCTAssertEqual(api.fetched, ["m1"])
        XCTAssertEqual(outcome.newSuggestions.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(store.seenIds, ["m1"])
        XCTAssertNil(settings.lastCheck)
    }

    func testTrackedFlightsAndKnownSuggestionsAreNotSuggestedAgain() async {
        inbox([mail("m1", body: indigo)])
        _ = await scanner().scan()
        await store.setStatus(["6E2341_2026-12-03"], .dismissed)

        // A second email about the same flight, plus one for a flight tracked another way.
        api.pages[nil] = (ids: ["m2", "m3"], next: nil)
        api.messages["m2"] = mail("m2", subject: "Booking update", body: indigo)
        api.messages["m3"] = mail("m3", body: airIndia)
        tracked.keys = [TrackedKey(flightCompact: "AI101", date: LocalDate(year: 2026, month: 12, day: 10))]

        let outcome = await scanner().scan()

        XCTAssertEqual(outcome, GmailScanOutcome(result: .completed, newSuggestions: []))
        let indigoRow = await store.get("6E2341_2026-12-03")
        XCTAssertEqual(indigoRow?.status, .dismissed)
        XCTAssertEqual(indigoRow?.messageId, "m1")
        let airIndiaRow = await store.get("AI101_2026-12-10")
        XCTAssertNil(airIndiaRow)
    }

    func testPastLegsAreNotSuggested() async {
        inbox([mail("m1", body: "IndiGo flight 6E 2341 DEL - BOM on 20 Nov 2026")])
        let outcome = await scanner().scan()
        XCTAssertEqual(outcome, GmailScanOutcome(result: .completed, newSuggestions: []))
        XCTAssertEqual(store.seenIds, ["m1"])
    }

    func testCleanupDeletesPastAndMarksTrackedAsAdded() async {
        inbox([mail("m1", body: indigo), mail("m2", body: airIndia)])
        _ = await scanner().scan()
        day = LocalDate(year: 2026, month: 12, day: 5)
        tracked.keys = [TrackedKey(flightCompact: "AI101", date: LocalDate(year: 2026, month: 12, day: 10))]

        let cleanup = await scanner().cleanup()

        XCTAssertEqual(cleanup, GmailCleanup(deleteIds: ["6E2341_2026-12-03"], markAddedIds: ["AI101_2026-12-10"]))
        let past = await store.get("6E2341_2026-12-03")
        XCTAssertNil(past)
        let added = await store.get("AI101_2026-12-10")
        XCTAssertEqual(added?.status, .added)
    }
}
