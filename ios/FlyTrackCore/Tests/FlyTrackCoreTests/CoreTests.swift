import Foundation
import XCTest
@testable import FlyTrackCore

final class FlightNumberTests: XCTestCase {
    private func parsed(_ input: String) -> (String, String)? {
        parseFlightNumber(input).map { ($0.carrier, $0.number) }
    }

    func testMirrorsTheServerCases() {
        XCTAssertEqual(parsed("6e-0234")?.0, "6E")
        XCTAssertEqual(parsed("6e-0234")?.1, "234")
        XCTAssertEqual(parsed("AI 101A")?.1, "101A")
        XCTAssertNil(parsed("12 345"))
        XCTAssertEqual(parsed("QP 1372")?.1, "1372")
        XCTAssertEqual(parsed("QP1372")?.1, "1372")
        XCTAssertEqual(parsed("6E2340")?.1, "2340")
        XCTAssertEqual(parsed("SG 8")?.1, "8")
        XCTAssertEqual(parsed("6E 1")?.1, "1")
    }

    func testSpacesDashAndCase() {
        XCTAssertEqual(parsed("6E 2341")?.1, "2341")
        XCTAssertEqual(parsed("6e2341")?.0, "6E")
        XCTAssertEqual(parsed("AI-101")?.1, "101")
        XCTAssertEqual(parsed("  ai - 101  ")?.1, "101")
        XCTAssertEqual(parsed("AI\t101\n")?.1, "101")
    }

    func testLeadingZerosAndSuffix() {
        XCTAssertEqual(parsed("6E 0234")?.1, "234")
        XCTAssertEqual(parsed("AI 007")?.1, "7")
        XCTAssertEqual(parsed("ai101a")?.1, "101A")
        XCTAssertEqual(parsed("6E 0000")?.1, "0")
    }

    func testCarrierMayContainOneDigit() {
        XCTAssertEqual(parsed("G8 123")?.0, "G8")
        XCTAssertEqual(parsed("9w1")?.0, "9W")
        XCTAssertEqual(parsed("I51234")?.0, "I5")
        XCTAssertEqual(parsed("I51234")?.1, "1234")
        XCTAssertNil(parsed("512345"))
        XCTAssertNil(parsed("99-1"))
    }

    func testRejectsWhatTheServerRejects() {
        for bad in ["", "   ", "6E", "2341", "6E 12345", "INDIGO 2341", "6E 23 41", "6E-", "6E 2341AB", "6E--2341", "6E_2341", "6E 2341 extra"] {
            XCTAssertNil(parsed(bad), bad)
        }
    }

    func testNoBreakSpace() {
        XCTAssertEqual(parsed("6E\u{00A0}2341")?.1, "2341")
    }

    func testDisplayForm() {
        XCTAssertEqual(parseFlightNumber("6e-2341")?.display, "6E 2341")
    }

    func testLiveValidation() {
        XCTAssertEqual(checkFlightInput(""), .empty)
        XCTAssertEqual(checkFlightInput("6E"), .incomplete)
        XCTAssertEqual(checkFlightInput("6E 2341"), .valid)
        XCTAssertEqual(checkFlightInput("AIR"), .invalid)
        XCTAssertEqual(checkFlightInput("12"), .invalid)
    }
}

final class TimeFormatTests: XCTestCase {
    func testAirportZoneNotThePhoneZone() {
        let dep = parseInstant("2026-11-25T13:20:00Z")!
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: "Asia/Kolkata"), "18:50")
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: "Asia/Dubai"), "17:20")
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: "Europe/London"), "13:20")
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: "America/New_York"), "08:20")
        XCTAssertEqual(FlightTimeFormat.time(parseInstant("2026-11-25T18:35:00Z")!, tz: "Asia/Kolkata"), "00:05")
    }

    func testUnknownZoneFallsBackToIndia() {
        let dep = parseInstant("2026-11-25T13:20:00Z")!
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: nil), "18:50")
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: "Not/AZone"), "18:50")
        XCTAssertEqual(FlightTimeFormat.time(dep, tz: ""), "18:50")
    }

    func testDaylightSaving() {
        XCTAssertEqual(FlightTimeFormat.time(parseInstant("2026-07-01T13:00:00Z")!, tz: "Europe/London"), "14:00")
        XCTAssertEqual(FlightTimeFormat.time(parseInstant("2026-12-01T13:00:00Z")!, tz: "Europe/London"), "13:00")
    }

    func testDatesFollowTheAirport() {
        let late = parseInstant("2026-11-25T19:30:00Z")!
        XCTAssertEqual(FlightTimeFormat.dayMonth(late, tz: "Asia/Kolkata"), "26 Nov")
        XCTAssertEqual(FlightTimeFormat.dayMonth(late, tz: "Europe/London"), "25 Nov")
        XCTAssertEqual(FlightTimeFormat.weekdayDayMonth(late, tz: "Asia/Kolkata"), "Thu, 26 Nov")
        XCTAssertEqual(FlightTimeFormat.localDate(late, tz: "Asia/Kolkata").iso, "2026-11-26")
    }

    func testPlainDates() {
        let date = LocalDate.parse("2026-11-05")!
        XCTAssertEqual(FlightTimeFormat.dayMonth(date), "5 Nov")
        XCTAssertEqual(FlightTimeFormat.weekdayDayMonth(date), "Thu, 5 Nov")
        XCTAssertEqual(FlightTimeFormat.fullDate(date), "Thu, 5 Nov 2026")
    }

    func testArrivalDayOffset() {
        let dep = parseInstant("2026-11-25T17:00:00Z")!
        let arr = parseInstant("2026-11-25T19:15:00Z")!
        XCTAssertEqual(FlightTimeFormat.arrivalDayOffset(dep: dep, depTz: "Asia/Kolkata", arr: arr, arrTz: "Asia/Kolkata"), 1)
        XCTAssertEqual(
            FlightTimeFormat.arrivalDayOffset(
                dep: parseInstant("2026-11-25T15:30:00Z")!, depTz: "Asia/Tokyo",
                arr: parseInstant("2026-11-25T22:30:00Z")!, arrTz: "Pacific/Honolulu"
            ),
            -1
        )
    }

    func testRelativeTimeRoundsDown() {
        let now = parseInstant("2026-11-25T12:00:00Z")!
        XCTAssertEqual(relativeTime(target: now.addingTimeInterval(59), now: now).unit, .now)
        XCTAssertEqual(relativeTime(target: now.addingTimeInterval(4 * 60 + 50), now: now).amount, 4)
        XCTAssertEqual(relativeTime(target: now.addingTimeInterval(-(4 * 60)), now: now).isPast, true)
    }

    func testSearchWindow() {
        // The date's 00:00 UTC must sit inside [now - 2 days, now + 365 days].
        let now = parseInstant("2026-11-22T00:00:00Z")!
        XCTAssertTrue(SearchWindow.contains(LocalDate.parse("2026-11-20")!, now: now))
        XCTAssertFalse(SearchWindow.contains(LocalDate.parse("2026-11-19")!, now: now))
        XCTAssertFalse(SearchWindow.contains(LocalDate.parse("2026-11-20")!, now: parseInstant("2026-11-22T12:00:00Z")!))
    }
}

final class TicketParserTests: XCTestCase {
    private let today = LocalDate(year: 2026, month: 11, day: 22)
    private func parse(_ text: String) -> ParsedTicket { parseTicketText(text, today: today) }

    func testIndiGo() {
        let ticket = parse("""
        IndiGo
        Booking Reference: Q7K2LM
        Flight 6E 2341
        Delhi (DEL) to Mumbai (BOM)
        Tue, 25 Nov 2026
        Departs 18:50
        """)
        let flight = ticket.flights[0]
        XCTAssertEqual(flight.flight.display, "6E 2341")
        XCTAssertEqual(flight.date?.iso, "2026-11-25")
        XCTAssertEqual(flight.fromIata, "DEL")
        XCTAssertEqual(flight.toIata, "BOM")
        XCTAssertEqual(ticket.pnr, "Q7K2LM")
    }

    func testAirIndia() {
        let ticket = parse("""
        Air India
        PNR: A1B2C3
        AI-101
        25 Nov 2026
        DEL → BOM
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "AI 101")
        XCTAssertEqual(ticket.flights[0].fromIata, "DEL")
        XCTAssertEqual(ticket.flights[0].toIata, "BOM")
        XCTAssertEqual(ticket.pnr, "A1B2C3")
    }

    func testAirIndiaExpress() {
        let ticket = parse("""
        Air India Express
        IX 1345
        25NOV26
        BLR-CCU
        Booking ID: ZX9PL4
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "IX 1345")
        XCTAssertEqual(ticket.flights[0].date?.iso, "2026-11-25")
        XCTAssertEqual(ticket.flights[0].fromIata, "BLR")
        XCTAssertEqual(ticket.flights[0].toIata, "CCU")
        XCTAssertEqual(ticket.pnr, "ZX9PL4")
    }

    func testAkasa() {
        let ticket = parse("""
        Akasa Air
        QP 1372
        25/11/2026
        BOM to DEL
        Confirmation code: K8M2NP
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "QP 1372")
        XCTAssertEqual(ticket.flights[0].date?.iso, "2026-11-25")
        XCTAssertEqual(ticket.pnr, "K8M2NP")
    }

    func testSpiceJet() {
        let ticket = parse("""
        SpiceJet SG 815
        Departure date: 25 Nov 2026
        HYD-MAA
        Record locator M2N4P6
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "SG 815")
        XCTAssertEqual(ticket.flights[0].fromIata, "HYD")
        XCTAssertEqual(ticket.flights[0].toIata, "MAA")
        XCTAssertEqual(ticket.pnr, "M2N4P6")
    }

    func testMakeMyTripOmitsTheYear() {
        let ticket = parse("""
        MakeMyTrip
        Booking ID: NF7K2Q
        IndiGo 6E-2341
        Delhi (DEL) → Mumbai (BOM)
        Tue, 25 Nov
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "6E 2341")
        XCTAssertEqual(ticket.flights[0].date?.iso, "2026-11-25")
        XCTAssertEqual(ticket.pnr, "NF7K2Q")
    }

    func testEaseMyTripReturn() {
        let ticket = parse("""
        EaseMyTrip
        Confirmation Number: H4P9LM
        Onward
        SpiceJet SG-815
        25/11/2026
        BLR to DEL
        Return
        Air India AI 102
        02/12/2026
        DEL to BLR
        """)
        XCTAssertEqual(ticket.flights.count, 2)
        let onward = ticket.flights.first { $0.flight.display == "SG 815" }!
        let back = ticket.flights.first { $0.flight.display == "AI 102" }!
        XCTAssertEqual(onward.date?.iso, "2026-11-25")
        XCTAssertEqual(onward.fromIata, "BLR")
        XCTAssertEqual(back.date?.iso, "2026-12-02")
        XCTAssertEqual(back.fromIata, "DEL")
        XCTAssertEqual(ticket.pnr, "H4P9LM")
    }

    func testOCRNoise() {
        let ticket = parse("""
        lndiGo
        PNR Q7K2LM
        6E 234l
        25 N0V 2026
        Delhi (DEL) to Mumbai (BOM)
        """)
        XCTAssertEqual(ticket.flights[0].flight.display, "6E 2341")
        XCTAssertEqual(ticket.flights[0].date?.iso, "2026-11-25")
        XCTAssertEqual(ticket.pnr, "Q7K2LM")
    }

    func testIndianCarrierListedFirst() {
        let ticket = parse("LH 760\n6E 2341\n25 Nov 2026")
        XCTAssertEqual(ticket.flights[0].flight.display, "6E 2341")
        XCTAssertEqual(ticket.flights[1].flight.display, "LH 760")
    }

    func testYearRollsForward() {
        XCTAssertEqual(inferYear(today: today, month: 1, day: 2)?.iso, "2027-01-02")
        XCTAssertEqual(inferYear(today: today, month: 11, day: 20)?.iso, "2026-11-20")
    }

    func testSlashDatesAreDayFirst() {
        XCTAssertEqual(parse("6E 2341\n25/11/2026").flights[0].date?.iso, "2026-11-25")
        XCTAssertNil(parse("6E 2341\n11/25/2026").flights[0].date)
    }

    func testPnrIsNotAFlightNumber() {
        XCTAssertEqual(parse("PNR 6E2341\nBooking Reference: ABC123\n6E 2341\n25 Nov 2026").pnr, "ABC123")
    }

    func testSeatIsNotAFlight() {
        XCTAssertEqual(parse("Seat 12A\nGate 31\n6E 2341").flights.map(\.flight.display), ["6E 2341"])
    }

    func testAirlineNamePlusBareNumber() {
        XCTAssertEqual(parse("IndiGo flight 2341 on 25 Nov 2026").flights[0].flight.display, "6E 2341")
    }

    func testMediaAndScale() {
        XCTAssertEqual(mediaKind(mime: "application/pdf", name: nil), .pdf)
        XCTAssertEqual(mediaKind(mime: "image/jpeg", name: "shot.png"), .image)
        XCTAssertEqual(trackSource(for: [.pdf, .pdf]), .pdf)
        XCTAssertEqual(trackSource(for: [.image]), .screenshot)
        XCTAssertEqual(trackSource(for: [.pdf, .image]), .share)
        XCTAssertEqual(scaledSize(width: 3200, height: 1800).0, 1600)
        XCTAssertEqual(scaledSize(width: 3200, height: 1800).1, 900)
        XCTAssertEqual(scaledSize(width: 0, height: 40).0, 1)
    }

    func testCloudJSONIsValidated() {
        let ticket = CloudTicketResponse(pnr: "ab-c123", flights: [
            CloudTicketFlight(flight: "6e-0234", date: "2026-11-25", from: "del", to: "bom"),
            CloudTicketFlight(flight: "not a flight", date: "2026-11-25", from: "DEL", to: "BOM"),
            CloudTicketFlight(flight: "AI 101", date: "tomorrow", from: "DEL", to: "BOM"),
        ]).validated()
        XCTAssertEqual(ticket.pnr, "ABC123")
        XCTAssertEqual(ticket.flights.count, 2)
        XCTAssertEqual(ticket.flights[0].flight.display, "6E 234")
        XCTAssertEqual(ticket.flights[0].date?.iso, "2026-11-25")
        XCTAssertNil(ticket.flights[1].date)
    }
}
