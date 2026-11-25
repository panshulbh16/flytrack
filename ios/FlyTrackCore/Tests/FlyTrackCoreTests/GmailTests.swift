import Foundation
import XCTest
@testable import FlyTrackCore

private let today = LocalDate(year: 2026, month: 11, day: 24)

/// Gmail sends bodies as unpadded base64url.
private func b64url(_ text: String) -> String {
    Data(text.utf8).base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

private func decodeMessage(_ json: String) throws -> GmailMessage {
    try JSONDecoder().decode(GmailMessage.self, from: Data(json.utf8))
}

// Synthetic emails in the shape `users.messages.get?format=full` returns. Not real mail.

private let indigoPlain = """
PNR: ABC123
IndiGo flight 6E 2341 DEL - BOM
Departure 03 Dec 2026 06:15
"""

private func indigoTextOnly() -> String {
    """
    {"id":"m-indigo","threadId":"t1","payload":{"mimeType":"text/plain","filename":"",
     "headers":[{"name":"From","value":"IndiGo <reservations@example.com>"},{"name":"subject","value":"Your IndiGo booking is confirmed"}],
     "body":{"size":\(indigoPlain.utf8.count),"data":"\(b64url(indigoPlain))"}}}
    """
}

private let airIndiaHTML = """
<html><head><style>.x { color: red }</style><script>var a = "AI 999";</script></head>
<body><table><tr><td>Booking reference</td><td>XYZ789</td></tr>
<tr><td>AI&nbsp;101</td><td>BOM &rarr; DEL</td><td>10 Dec 2026</td></tr></table>
<p>Thanks &amp; safe travels</p></body></html>
"""

private func airIndiaHTMLOnly() -> String {
    """
    {"id":"m-ai","payload":{"mimeType":"text/html","headers":[{"name":"Subject","value":"Air India e-ticket"}],
     "body":{"size":\(airIndiaHTML.utf8.count),"data":"\(b64url(airIndiaHTML))"}}}
    """
}

private let akasaPlain = """
Booking reference: QPZ456
Akasa Air flight QP 1372 BLR - DEL on 15 Dec 2026
"""
private let akasaHTML = "<p>Akasa Air flight <b>QP 1372</b> BLR - DEL on 15 Dec 2026</p>"

private func akasaMultipart() -> String {
    """
    {"id":"m-akasa","payload":{"mimeType":"multipart/mixed","filename":"",
     "headers":[{"name":"Subject","value":"Akasa Air itinerary"}],"body":{"size":0},
     "parts":[
       {"mimeType":"multipart/alternative","filename":"","headers":[],"body":{"size":0},"parts":[
         {"partId":"0.0","mimeType":"text/plain","filename":"","body":{"size":\(akasaPlain.utf8.count),"data":"\(b64url(akasaPlain))"}},
         {"partId":"0.1","mimeType":"text/html","filename":"","body":{"size":\(akasaHTML.utf8.count),"data":"\(b64url(akasaHTML))"}}
       ]},
       {"partId":"1","mimeType":"text/plain","filename":"notes.txt","body":{"size":20,"attachmentId":"att-notes"}},
       {"partId":"2","mimeType":"application/pdf","filename":"Itinerary.pdf","body":{"size":81234,"attachmentId":"att-akasa"}}
     ]}}
    """
}

private let stubPlain = "View this email in your browser"
private let indigoTicketHTML = """
<p><a href="https://example.com/view">View this email in your browser</a></p>
<table><tr><td>PNR</td><td>Q7K2LM</td></tr>
<tr><td>Flight</td><td>6E 5123</td></tr>
<tr><td>Date</td><td>Mon, 14 Dec 2026</td></tr>
<tr><td>Route</td><td>DEL&nbsp;&rarr;&nbsp;GOI</td></tr></table>
"""

/// The plain part is only a "View in browser" stub; the ticket is in the HTML part.
private func indigoPlainStub() -> String {
    """
    {"id":"m-stub","payload":{"mimeType":"multipart/alternative","headers":[{"name":"Subject","value":"Your IndiGo itinerary"}],"body":{"size":0},
     "parts":[
       {"mimeType":"text/plain","filename":"","body":{"size":\(stubPlain.utf8.count),"data":"\(b64url(stubPlain))"}},
       {"mimeType":"text/html","filename":"","body":{"size":\(indigoTicketHTML.utf8.count),"data":"\(b64url(indigoTicketHTML))"}}
     ]}}
    """
}

private func pdfOnly() -> String {
    let note = "Please find your e-ticket attached."
    return """
    {"id":"m-pdf","payload":{"mimeType":"multipart/mixed","headers":[{"name":"Subject","value":"IndiGo e-ticket"}],"body":{"size":0},
     "parts":[
       {"mimeType":"text/plain","filename":"","body":{"size":\(note.utf8.count),"data":"\(b64url(note))"}},
       {"mimeType":"application/octet-stream","filename":"ETICKET.PDF","body":{"size":48213,"attachmentId":"att-pdf"}}
     ]}}
    """
}

final class GmailQueryTests: XCTestCase {
    private let terms = #"subject:(ticket OR e-ticket OR itinerary OR booking OR PNR OR confirmation OR "boarding pass") -subject:(cancel OR cancelled OR canceled OR cancellation OR refund OR refunded)"#

    func testTermsAreExact() {
        XCTAssertEqual(GmailQuery.terms, terms)
    }

    func testFirstScanLooksBack180Days() {
        XCTAssertEqual(GmailQuery.build(lastCheckEpochSeconds: nil), terms + " newer_than:180d")
    }

    func testLaterScanOverlapsOneHour() {
        XCTAssertEqual(GmailQuery.build(lastCheckEpochSeconds: 1_764_000_000), terms + " after:1763996400")
    }
}

final class GmailMessageTextTests: XCTestCase {
    func testTextOnlyIndiGo() throws {
        let content = try decodeMessage(indigoTextOnly()).content()
        XCTAssertEqual(content, GmailMessageContent(subject: "Your IndiGo booking is confirmed", text: indigoPlain, pdfAttachmentId: nil, pdfSize: 0))
    }

    func testHTMLOnlyAirIndiaIsStripped() throws {
        let content = try decodeMessage(airIndiaHTMLOnly()).content()
        XCTAssertEqual(content.subject, "Air India e-ticket")
        XCTAssertFalse(content.text.contains("<"), content.text)
        XCTAssertFalse(content.text.contains("AI 999"), "script text must be dropped")
        XCTAssertFalse(content.text.contains("color"), "style text must be dropped")
        XCTAssertTrue(content.text.contains("Booking reference XYZ789"), content.text)
        XCTAssertTrue(content.text.contains("AI 101 BOM → DEL 10 Dec 2026"), content.text)
        XCTAssertTrue(content.text.contains("Thanks & safe travels"), content.text)
        XCTAssertNil(content.pdfAttachmentId)
    }

    func testMultipartReadsPlainThenHTMLAndFindsThePDF() throws {
        let content = try decodeMessage(akasaMultipart()).content()
        XCTAssertEqual(content.subject, "Akasa Air itinerary")
        XCTAssertEqual(content.text, akasaPlain + "\nAkasa Air flight QP 1372 BLR - DEL on 15 Dec 2026")
        XCTAssertEqual(content.pdfAttachmentId, "att-akasa")
        XCTAssertEqual(content.pdfSize, 81_234)
    }

    func testPlainStubDoesNotHideTheHTMLTicket() throws {
        let content = try decodeMessage(indigoPlainStub()).content()
        XCTAssertEqual(content.text, "View this email in your browser\nView this email in your browser\nPNR Q7K2LM\nFlight 6E 5123\nDate Mon, 14 Dec 2026\nRoute DEL → GOI")
        let ticket = parseTicketText(content.text, today: today)
        XCTAssertEqual(ticket.flights.map(\.flight.display), ["6E 5123"])
        XCTAssertEqual(ticket.flights.first?.date, LocalDate(year: 2026, month: 12, day: 14))
        XCTAssertEqual(ticket.flights.first?.fromIata, "DEL")
        XCTAssertEqual(ticket.flights.first?.toIata, "GOI")
        XCTAssertEqual(ticket.pnr, "Q7K2LM")
    }

    func testPDFOnlyByFilename() throws {
        let content = try decodeMessage(pdfOnly()).content()
        XCTAssertEqual(content.text, "Please find your e-ticket attached.")
        XCTAssertEqual(content.pdfAttachmentId, "att-pdf")
        XCTAssertEqual(content.pdfSize, 48_213)
    }

    func testMissingPayloadIsEmpty() throws {
        XCTAssertEqual(try decodeMessage(#"{"id":"m-empty"}"#).content(), GmailMessageContent(subject: "", text: "", pdfAttachmentId: nil, pdfSize: 0))
    }

    func testUndecodableBodyIsEmptyText() throws {
        let json = #"{"id":"m-bad","payload":{"mimeType":"text/plain","headers":[],"body":{"size":1,"data":"a"}}}"#
        XCTAssertEqual(try decodeMessage(json).content().text, "")
    }

    func testListResponseDefaults() throws {
        let empty = try JSONDecoder().decode(GmailListResponse.self, from: Data(#"{"resultSizeEstimate":0}"#.utf8))
        XCTAssertTrue(empty.messages.isEmpty)
        XCTAssertNil(empty.nextPageToken)
        let page = try JSONDecoder().decode(GmailListResponse.self, from: Data(#"{"messages":[{"id":"a","threadId":"t"},{"id":"b","threadId":"t"}],"nextPageToken":"p2"}"#.utf8))
        XCTAssertEqual(page.messages.map(\.id), ["a", "b"])
        XCTAssertEqual(page.nextPageToken, "p2")
    }

    func testProfileAndAttachmentLessBody() throws {
        XCTAssertEqual(try JSONDecoder().decode(GmailProfile.self, from: Data(#"{"emailAddress":"traveller@example.com","messagesTotal":10}"#.utf8)).emailAddress, "traveller@example.com")
        let body = try JSONDecoder().decode(GmailBody.self, from: Data("{}".utf8))
        XCTAssertEqual(body.size, 0)
        XCTAssertNil(body.data)
        XCTAssertNil(body.attachmentId)
    }

    func testBase64URL() {
        XCTAssertEqual(decodeBase64URL("-_8"), Data([0xFB, 0xFF]))
        XCTAssertEqual(decodeBase64URL("-_8="), Data([0xFB, 0xFF]))
        XCTAssertEqual(decodeBase64URL(b64url("6E 2341")).map { String(decoding: $0, as: UTF8.self) }, "6E 2341")
        XCTAssertEqual(decodeBase64URL(""), Data())
        XCTAssertNil(decodeBase64URL("a"))
        XCTAssertNil(decodeBase64URL("@@@@"))
    }

    func testHardWrappedHTMLIsOneLine() {
        let html = "<html><head><title>Booking confirmation PNR ABC123</title></head><body><p>Akasa Air flight\nQP\n1372\nBLR - DEL\non 15 Dec 2026</p><table><tr><td>PNR\nQPZ456</td></tr></table></body></html>"
        let text = htmlToText(html)
        XCTAssertEqual(text, "Akasa Air flight QP 1372 BLR - DEL on 15 Dec 2026\nPNR QPZ456")
        XCTAssertFalse(text.contains("Booking confirmation"), text)
        let ticket = parseTicketText(text, today: today)
        XCTAssertEqual(ticket.flights.map(\.flight.display), ["QP 1372"])
        XCTAssertEqual(ticket.flights.first?.date, LocalDate(year: 2026, month: 12, day: 15))
        XCTAssertEqual(ticket.flights.first?.fromIata, "BLR")
        XCTAssertEqual(ticket.flights.first?.toIata, "DEL")
    }

    func testHTMLToText() {
        XCTAssertEqual(htmlToText("<div>6E&nbsp;2341</div><div>DEL&#8594;BOM &#x2192; GOI</div>"), "6E 2341\nDEL→BOM → GOI")
        XCTAssertEqual(htmlToText("Line one<br>Line two<BR/>three"), "Line one\nLine two\nthree")
        XCTAssertEqual(htmlToText("&amp;lt; is literal &unknown; &lt;b&gt;"), "&lt; is literal &unknown; <b>")
        XCTAssertEqual(htmlToText("<!-- PNR: HIDDEN --><STYLE type=\"text/css\">p{}</STYLE>PNR<span>ABC123</span>"), "PNR ABC123")
    }
}

final class GmailSuggestionsTests: XCTestCase {
    private func date(_ iso: String) -> LocalDate { LocalDate.parse(iso)! }

    private func suggestion(
        _ flight: String, _ iso: String, status: GmailSuggestionStatus = .pending, foundAt: Int64 = 1,
        pnr: String? = nil, subject: String = "Booking"
    ) -> GmailSuggestion {
        GmailSuggestion(
            id: GmailSuggestions.suggestionId(flightCompact: GmailSuggestions.compact(flight), date: date(iso)),
            messageId: "m\(foundAt)", flight: flight, date: date(iso), fromIata: nil, toIata: nil,
            pnr: pnr, subject: subject, status: status, foundAt: foundAt
        )
    }

    private func leg(_ carrier: String, _ number: String, _ iso: String?, _ from: String? = nil, _ to: String? = nil) -> ParsedFlight {
        ParsedFlight(flight: FlightNumber(carrier: carrier, number: number), date: iso.map(date), fromIata: from, toIata: to)
    }

    func testIdAndCompact() {
        XCTAssertEqual(GmailSuggestions.compact("6E 2341"), "6E2341")
        XCTAssertEqual(GmailSuggestions.compact("ai-101a"), "AI101A")
        XCTAssertEqual(GmailSuggestions.compact("6E.2341"), "6E2341")
        XCTAssertEqual(GmailSuggestions.compact("6E.2341"), GmailSuggestions.compact("6E 2341"))
        XCTAssertEqual(GmailSuggestions.suggestionId(flightCompact: "6E2341", date: date("2026-12-03")), "6E2341_2026-12-03")
        let id = GmailSuggestions.suggestionId(flightCompact: GmailSuggestions.compact("QP 1372"), date: date("2026-12-15"))
        XCTAssertNotNil(id.range(of: #"^[A-Z0-9]{3,8}_\d{4}-\d{2}-\d{2}$"#, options: .regularExpression))
    }

    func testCancellationBackstop() {
        XCTAssertTrue(GmailSuggestions.isCancellation("Booking CANCELLED: 6E 2341"))
        XCTAssertTrue(GmailSuggestions.isCancellation("Your refund is processed"))
        XCTAssertTrue(GmailSuggestions.isCancellation("Cancellation confirmation"))
        XCTAssertFalse(GmailSuggestions.isCancellation("Your e-ticket for DEL-BOM"))
    }

    func testFromTicketDropsUndatedAndPastKeepsTodayAndBlankRoute() {
        let ticket = ParsedTicket(flights: [
            leg("6E", "2341", "2026-12-03", "DEL", "BOM"),
            leg("6E", "2341", "2026-12-03"), // the same leg read again from the HTML part, without its route
            leg("6E", "2342", "2026-11-24"),
            leg("AI", "101", "2026-11-23"),
            leg("QP", "1372", nil),
        ], pnr: "ABC123")
        let found = GmailSuggestions.fromTicket(ticket, messageId: "m1", subject: "Your booking", today: today, foundAt: 42)
        XCTAssertEqual(found, [
            GmailSuggestion(id: "6E2341_2026-12-03", messageId: "m1", flight: "6E 2341", date: date("2026-12-03"), fromIata: "DEL", toIata: "BOM", pnr: "ABC123", subject: "Your booking", status: .pending, foundAt: 42),
            GmailSuggestion(id: "6E2342_2026-11-24", messageId: "m1", flight: "6E 2342", date: date("2026-11-24"), fromIata: nil, toIata: nil, pnr: "ABC123", subject: "Your booking", status: .pending, foundAt: 42),
        ])
    }

    func testFromTicketSkipsCancelledSubjects() {
        let ticket = ParsedTicket(flights: [leg("6E", "2341", "2026-12-03")], pnr: nil)
        XCTAssertTrue(GmailSuggestions.fromTicket(ticket, messageId: "m1", subject: "Booking cancelled", today: today, foundAt: 1).isEmpty)
    }

    func testNewOnlyMergesDuplicatesKeepingTheNewest() {
        let older = suggestion("6E 2341", "2026-12-03", foundAt: 1, pnr: "OLD111", subject: "Old")
        let newer = suggestion("6E 2341", "2026-12-03", foundAt: 2, pnr: "NEW222", subject: "New")
        let other = suggestion("AI 101", "2026-12-10", foundAt: 1)
        XCTAssertEqual(GmailSuggestions.newOnly([newer, other, older], existingIds: [], tracked: []), [newer, other])
        XCTAssertEqual(GmailSuggestions.newOnly([older, newer], existingIds: [], tracked: []), [newer])
        let tieA = suggestion("6E 2341", "2026-12-03", foundAt: 5, subject: "A")
        let tieB = suggestion("6E 2341", "2026-12-03", foundAt: 5, subject: "B")
        XCTAssertEqual(GmailSuggestions.newOnly([tieA, tieB], existingIds: [], tracked: []).map(\.subject), ["B"])
    }

    func testNewOnlyDropsTrackedAndExistingOfAnyStatus() {
        let tracked = suggestion("6E 2341", "2026-12-03")
        let dismissedBefore = suggestion("AI 101", "2026-12-10")
        let fresh = suggestion("QP 1372", "2026-12-15")
        let result = GmailSuggestions.newOnly(
            [tracked, dismissedBefore, fresh],
            existingIds: [dismissedBefore.id],
            tracked: [TrackedKey(flightCompact: "6E2341", date: date("2026-12-03"))]
        )
        XCTAssertEqual(result, [fresh])
    }

    func testVisibleIsPendingUpcomingUntrackedSortedByDate() {
        let later = suggestion("QP 1372", "2026-12-15")
        let todays = suggestion("6E 2342", "2026-11-24")
        let past = suggestion("AI 101", "2026-11-23")
        let dismissed = suggestion("SG 8", "2026-12-01", status: .dismissed)
        let added = suggestion("IX 12", "2026-12-02", status: .added)
        let trackedElsewhere = suggestion("6E 2341", "2026-12-03")
        let tracked: Set<TrackedKey> = [TrackedKey(flightCompact: "6E2341", date: date("2026-12-03"))]
        XCTAssertEqual(
            GmailSuggestions.visible([later, past, dismissed, trackedElsewhere, added, todays], today: today, tracked: tracked),
            [todays, later]
        )
    }

    func testCleanupDeletesPastAndMarksTrackedPendingAdded() {
        let pastDismissed = suggestion("AI 101", "2026-11-20", status: .dismissed)
        let pastPending = suggestion("6E 1", "2026-11-23")
        let pendingTracked = suggestion("6E 2341", "2026-12-03")
        let dismissedTracked = suggestion("QP 1372", "2026-12-15", status: .dismissed)
        let pendingOpen = suggestion("SG 8", "2026-12-01")
        let tracked: Set<TrackedKey> = [
            TrackedKey(flightCompact: "6E2341", date: date("2026-12-03")),
            TrackedKey(flightCompact: "QP1372", date: date("2026-12-15")),
            TrackedKey(flightCompact: "6E1", date: date("2026-11-23")),
        ]
        XCTAssertEqual(
            GmailSuggestions.cleanup([pastDismissed, pastPending, pendingTracked, dismissedTracked, pendingOpen], today: today, tracked: tracked),
            GmailCleanup(deleteIds: [pastDismissed.id, pastPending.id], markAddedIds: [pendingTracked.id])
        )
    }

    /// Fixture email -> body text -> on-device parser -> suggestions, for each synthetic airline email.
    func testFixturesBecomeSuggestions() throws {
        func found(_ json: String) throws -> [GmailSuggestion] {
            let content = try decodeMessage(json).content()
            return GmailSuggestions.fromTicket(parseTicketText(content.text, today: today), messageId: "m", subject: content.subject, today: today, foundAt: 1)
        }
        let indigo = try found(indigoTextOnly())
        XCTAssertEqual(indigo.map(\.id), ["6E2341_2026-12-03"])
        XCTAssertEqual(indigo.first?.pnr, "ABC123")
        XCTAssertEqual(indigo.first?.fromIata, "DEL")
        XCTAssertEqual(indigo.first?.toIata, "BOM")

        let airIndia = try found(airIndiaHTMLOnly())
        XCTAssertEqual(airIndia.map(\.id), ["AI101_2026-12-10"])
        XCTAssertEqual(airIndia.first?.pnr, "XYZ789")

        let akasa = try found(akasaMultipart())
        XCTAssertEqual(akasa.map(\.id), ["QP1372_2026-12-15"])
        XCTAssertEqual(akasa.first?.pnr, "QPZ456")

        XCTAssertTrue(try found(pdfOnly()).isEmpty)
    }

    func testPrefillCarriesSuggestionId() {
        XCTAssertNil(AddFlightPrefill(flight: "6E 2341").gmailSuggestionId)
        let prefill = AddFlightPrefill(flight: "6E 2341", date: "2026-12-03", pnr: "ABC123", source: .pdf, gmailSuggestionId: "6E2341_2026-12-03")
        XCTAssertEqual(prefill.gmailSuggestionId, "6E2341_2026-12-03")
        XCTAssertFalse(prefill.isEmpty)
    }
}
