import Foundation
import XCTest
@testable import FlyTrackCore

final class GmailNoticeTests: XCTestCase {
    private func suggestion(from: String?, to: String?) -> GmailSuggestion {
        GmailSuggestion(
            id: "6E2341_2026-12-03", messageId: "m1", flight: "6E 2341", date: LocalDate(year: 2026, month: 12, day: 3),
            fromIata: from, toIata: to, pnr: "ABC123", subject: "Your IndiGo booking", status: .pending, foundAt: 0
        )
    }

    func testBodyWithRoute() {
        XCTAssertEqual(GmailNotice.body(suggestion(from: "DEL", to: "BOM")), "Track 6E 2341 on 3 Dec, DEL→BOM? Found in your Gmail.")
    }

    func testBodyWithoutRoute() {
        XCTAssertEqual(GmailNotice.body(suggestion(from: nil, to: nil)), "Track 6E 2341 on 3 Dec? Found in your Gmail.")
        XCTAssertEqual(GmailNotice.body(suggestion(from: "DEL", to: nil)), "Track 6E 2341 on 3 Dec? Found in your Gmail.")
    }

    func testSuggestionIdValidation() {
        XCTAssertTrue(GmailNotice.isValidSuggestionId("6E2341_2026-12-03"))
        XCTAssertTrue(GmailNotice.isValidSuggestionId("AI101_2026-01-31"))
        XCTAssertFalse(GmailNotice.isValidSuggestionId("6e2341_2026-12-03"))
        XCTAssertFalse(GmailNotice.isValidSuggestionId("6E 2341_2026-12-03"))
        XCTAssertFalse(GmailNotice.isValidSuggestionId("6E2341_2026-12-03\n"))
        XCTAssertFalse(GmailNotice.isValidSuggestionId("6E2341_2026-12-3"))
        XCTAssertFalse(GmailNotice.isValidSuggestionId(""))
    }

    func testAppOpenCheckRunsAtMostOnceAnHour() {
        XCTAssertTrue(GmailNotice.appOpenCheckDue(lastCheck: nil, now: 10_000))
        XCTAssertFalse(GmailNotice.appOpenCheckDue(lastCheck: 10_000, now: 10_000))
        XCTAssertFalse(GmailNotice.appOpenCheckDue(lastCheck: 10_000, now: 13_599))
        XCTAssertTrue(GmailNotice.appOpenCheckDue(lastCheck: 10_000, now: 13_600))
        XCTAssertTrue(GmailNotice.appOpenCheckDue(lastCheck: 10_000, now: 9_000))
    }

    /// Track on a notification whose suggestion was dismissed, added or deleted since it was posted.
    func testTrackOpensAddFlightOnlyForAPendingSuggestion() {
        var found = suggestion(from: "DEL", to: "BOM")
        let expected = AddFlightPrefill(flight: "6E 2341", date: "2026-12-03", pnr: "ABC123", source: .gmail, gmailSuggestionId: "6E2341_2026-12-03")
        XCTAssertEqual(found.addFlightPrefill, expected)
        XCTAssertEqual(GmailNotice.trackPrefill(found), expected)
        found.status = .dismissed
        XCTAssertNil(GmailNotice.trackPrefill(found))
        found.status = .added
        XCTAssertNil(GmailNotice.trackPrefill(found))
        XCTAssertNil(GmailNotice.trackPrefill(nil))
    }
}
