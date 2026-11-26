import Foundation
import XCTest
@testable import FlyTrackCore

// P3 (docs/P3_CONTRACTS.md) on iOS, search / social / watch area: A1 (no count upsell, anti-abuse cap), A3 calendar
// import (the Swift port of HeyTurbulence's calendar parser on the SAME fixtures as Android:
// Fixtures/calendar_events.json is a byte-for-byte copy of android/app/src/test/resources/fixtures/calendar_events.json),
// B5 route search, B6 past flights, B10 friend Passport compare, B12 crew mode, B13 family, B9 watch payload.

private func fixture(_ name: String) throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

/// The app's bundled catalog (ios/FlyTrack/airports.json, a copy of Android's assets/airports.json).
private let bundledCatalog: AirportList = {
    let ios = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // FlyTrackCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // FlyTrackCore
        .deletingLastPathComponent() // ios
    guard let data = try? Data(contentsOf: ios.appendingPathComponent("FlyTrack/airports.json")),
          let list = AirportList.parse(data) else { return .empty }
    return list
}()

private func instant(_ text: String) -> Date { parseInstant(text)! }

private func str(_ o: [String: Any], _ key: String) -> String? {
    guard let value = o[key], !(value is NSNull) else { return nil }
    return value as? String
}

private func strings(_ o: [String: Any], _ key: String) -> [String] {
    (o[key] as? [Any] ?? []).compactMap { $0 as? String }
}

private func googleEvent(_ o: [String: Any]) -> CalendarEvent {
    func time(_ key: String) -> CalendarEventTime? {
        guard let t = o[key] as? [String: Any] else { return nil }
        return CalendarEventTime(dateTime: str(t, "dateTime"), timeZone: str(t, "timeZone"), date: str(t, "date"))
    }
    return CalendarEvent(
        id: str(o, "id") ?? "", summary: str(o, "summary"), description: str(o, "description"), location: str(o, "location"),
        start: time("start"), end: time("end"), status: str(o, "status"), eventType: str(o, "eventType")
    )
}

private let delAirport = Airport(iata: "DEL", name: "Indira Gandhi International Airport", city: "New Delhi", tz: "Asia/Kolkata", lat: 28.56, lon: 77.10)
private let bomAirport = Airport(iata: "BOM", name: "Chhatrapati Shivaji International Airport", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.09, lon: 72.87)

private func p3Flight(
    id: String = "f1", number: String = "2341", date: String = "2026-11-28", dep: String = "DEL", arr: String = "BOM",
    status: FlightStatus = .scheduled, schedDep: String? = "2026-11-28T13:20:00Z", schedArr: String? = "2026-11-28T15:30:00Z"
) -> Flight {
    Flight(
        id: id, carrierIata: "6E", flightNumber: number, flightDate: LocalDate.parse(date)!,
        depIata: dep, arrIata: arr, airlineName: "IndiGo", status: status,
        schedDep: parseInstant(schedDep), schedArr: parseInstant(schedArr),
        depAirport: dep == "DEL" ? delAirport : nil, arrAirport: arr == "BOM" ? bomAirport : nil
    )
}

private func details(_ flight: Flight) -> TrackedFlightDetails {
    TrackedFlightDetails(tracking: TrackedFlight(id: "t-\(flight.id)", userId: "user-1", flightId: flight.id), flight: flight)
}

// MARK: - A3 calendar parser (shared fixtures)

final class CalendarParserFixtureTests: XCTestCase {
    private let parser = CalendarFlightParser(catalog: bundledCatalog, sourcePrefix: "google:primary:")

    func testBundledCatalogLoads() {
        XCTAssertGreaterThan(bundledCatalog.count, 3000)
        XCTAssertEqual(bundledCatalog["DEL"]?.tz, "Asia/Kolkata")
        XCTAssertEqual(bundledCatalog["SIN"]?.country, "Singapore")
    }

    func testEveryFixtureMatchesTheJavaScriptParser() throws {
        let root = try XCTUnwrap(JSON.object(from: try fixture("calendar_events")))
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 60)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let event = googleEvent(try XCTUnwrap(c["event"] as? [String: Any], name))
            let actual = parser.parse(event)
            guard let e = c["expected"] as? [String: Any] else {
                XCTAssertNil(actual, "\(name): not a flight")
                continue
            }
            guard let actual else {
                XCTFail("\(name): expected a candidate")
                continue
            }
            XCTAssertEqual(actual.sourceId, str(e, "sourceId"), "\(name) sourceId")
            XCTAssertEqual(actual.from?.iata, str(e, "from"), "\(name) from")
            XCTAssertEqual(actual.to?.iata, str(e, "to"), "\(name) to")
            XCTAssertEqual(actual.number, str(e, "number"), "\(name) number")
            let legs = (e["legs"] as? [[String]] ?? []).map { CalendarLeg(from: $0[0], to: $0[1]) }
            XCTAssertEqual(actual.legs, legs, "\(name) legs")
            XCTAssertEqual(actual.legDetails.map(\.number), strings(e, "legNumbers"), "\(name) legNumbers")
            XCTAssertEqual(actual.legDetails.map(\.times), e["legTimes"] as? [[String]] ?? [], "\(name) legTimes")
            XCTAssertEqual(actual.startInstant.map(calendarInstantText), str(e, "startInstant"), "\(name) startInstant")
            XCTAssertEqual(actual.endInstant.map(calendarInstantText), str(e, "endInstant"), "\(name) endInstant")
            XCTAssertEqual(actual.departLocal, str(e, "departLocal"), "\(name) departLocal")
            XCTAssertEqual(actual.arriveLocal, str(e, "arriveLocal"), "\(name) arriveLocal")
            XCTAssertEqual(actual.needsReview, JSON.bool(e["needsReview"]), "\(name) needsReview")
            XCTAssertEqual(actual.requiresCorrection, JSON.bool(e["requiresCorrection"]), "\(name) requiresCorrection")
            XCTAssertEqual(actual.issues.map(\.rawValue), strings(e, "issues"), "\(name) issues")
            XCTAssertEqual(actual.sortDate, str(e, "sortDate"), "\(name) sortDate")

            let split = e["split"] as? [[String: Any]] ?? []
            let pieces = parser.splitLegs(actual)
            if split.isEmpty {
                XCTAssertEqual(pieces, [actual], "\(name): single leg passes through")
                continue
            }
            XCTAssertEqual(pieces.count, split.count, "\(name) split count")
            for (l, leg) in zip(split, pieces) {
                let tag = "\(name) leg \(JSON.int(l["leg"]) ?? 0)"
                XCTAssertEqual(leg.sourceId, str(l, "sourceId"), "\(tag) sourceId")
                XCTAssertEqual(leg.leg, JSON.int(l["leg"]), "\(tag) leg")
                XCTAssertEqual(leg.legCount, JSON.int(l["legCount"]), "\(tag) legCount")
                XCTAssertEqual(leg.from?.iata, str(l, "from"), "\(tag) from")
                XCTAssertEqual(leg.to?.iata, str(l, "to"), "\(tag) to")
                XCTAssertEqual(leg.number, str(l, "number"), "\(tag) number")
                XCTAssertEqual(leg.startInstant.map(calendarInstantText), str(l, "startInstant"), "\(tag) startInstant")
                XCTAssertEqual(leg.endInstant.map(calendarInstantText), str(l, "endInstant"), "\(tag) endInstant")
                XCTAssertEqual(leg.departLocal, str(l, "departLocal"), "\(tag) departLocal")
                XCTAssertEqual(leg.arriveLocal, str(l, "arriveLocal"), "\(tag) arriveLocal")
                XCTAssertEqual(leg.missing?.rawValue, str(l, "missing"), "\(tag) missing")
                XCTAssertEqual(leg.issues.map(\.rawValue), strings(l, "issues"), "\(tag) issues")
                XCTAssertTrue(leg.needsReview, "\(tag) needs review")
            }
        }
    }

    func testNullAndSingleCandidatesSplitTrivially() throws {
        XCTAssertEqual(parser.splitLegs(nil), [])
        let event = CalendarEvent(id: "x", summary: "Flight BLR → SIN")
        let single = try XCTUnwrap(parser.parse(event))
        XCTAssertEqual(parser.splitLegs(single), [single])
        XCTAssertEqual(parser.candidates(event), [single])
    }

    func testStatedLegTimesAreSurfacedNotFilledIn() {
        let legs = parser.candidates(CalendarEvent(
            id: "e", summary: "Emirates to London",
            description: "EK 569 BLR → DXB 04:30 - 07:05<br>EK 1 DXB → LHR 07:45 - 11:25",
            start: CalendarEventTime(dateTime: "2026-11-26T04:30:00+05:30"),
            end: CalendarEventTime(dateTime: "2026-11-26T11:25:00+00:00")
        ))
        XCTAssertEqual(legs.count, 2)
        XCTAssertEqual(legs.first?.statedTimes, ["04:30", "07:05"])
        XCTAssertEqual(legs.first?.issues, [.statedTimes])
        XCTAssertEqual(legs.first?.arriveLocal, "")
        XCTAssertEqual(legs.first?.missing, .landing)
        XCTAssertEqual(legs.last?.missing, .takeoff)
    }

    func testDeviceEventsUseTheDevicePrefix() {
        let candidate = CalendarFlightParser(catalog: bundledCatalog).parse(CalendarEvent(id: "42", summary: "Flight DEL → BOM"))
        XCTAssertEqual(candidate?.sourceId, "device:42")
    }

    func testHostileDescriptionsStayFast() {
        for description in [
            "flight\n" + String(repeating: "ab-cd\n", count: 5000),
            "flight " + String(repeating: "a ", count: 16000),
            "flight " + String(repeating: "a.", count: 20000),
        ] {
            let started = Date()
            _ = parser.parse(CalendarEvent(
                id: "x", summary: "Flight BLR to SIN", description: description,
                start: CalendarEventTime(dateTime: "2026-11-26T10:20:00+05:30"),
                end: CalendarEventTime(dateTime: "2026-11-26T17:40:00+08:00")
            ))
            XCTAssertLessThan(Date().timeIntervalSince(started), 4, "hostile description took too long")
        }
    }

    func testCleanDecodesHtmlAndEntities() {
        XCTAssertEqual(calendarClean("<script>x</script>a<br> b &rarr; c &x;").replacingOccurrences(of: "  ", with: " "), "a\n b → c &x;")
        XCTAssertTrue(calendarBoundary("new delhi", "delhi"))
        XCTAssertFalse(calendarBoundary("delhiwala", "delhi"))
        XCTAssertEqual(calendarNormalize("São Paulo"), "sao paulo")
        XCTAssertEqual(calendarPlain("Kempegowda Int'l — Airport"), "kempegowda int l airport")
    }

    func testEventTimesFollowTheJavaScriptRules() {
        XCTAssertEqual(calendarInstant(CalendarEventTime(dateTime: "2026-11-26T06:00:00+05:30")).map(calendarInstantText), "2026-11-26T00:30:00Z")
        XCTAssertEqual(calendarInstant(CalendarEventTime(dateTime: "2026-11-28T03:30:00Z")).map(calendarInstantText), "2026-11-28T03:30:00Z")
        XCTAssertEqual(calendarInstant(CalendarEventTime(dateTime: "2026-11-26T13:00:00", timeZone: "Asia/Singapore")).map(calendarInstantText), "2026-11-26T05:00:00Z")
        XCTAssertNil(calendarInstant(CalendarEventTime(dateTime: "2026-11-26T06:00:00")))
        XCTAssertNil(calendarInstant(CalendarEventTime(dateTime: "2026-11-26T06:00:00", timeZone: "Not/AZone")))
        XCTAssertNil(calendarInstant(CalendarEventTime(dateTime: "2026-02-30T06:00:00Z")))
        // 02:30 does not exist in New York on the spring-forward day.
        XCTAssertNil(calendarInstant(CalendarEventTime(dateTime: "2026-03-08T02:30:00", timeZone: "America/New_York")))
        XCTAssertNil(calendarInstant(CalendarEventTime(date: "2026-11-26")))
        XCTAssertEqual(calendarLocalText(instant("2026-11-26T00:30:00Z"), tz: "Asia/Kolkata"), "2026-11-26T06:00")
        XCTAssertEqual(calendarLocalText(instant("2026-11-26T00:30:00Z"), tz: "Nope/Zone"), "")
    }
}

// MARK: - airport catalog

final class AirportCatalogTests: XCTestCase {
    private let small = AirportList([
        CatalogAirport(iata: "DEL", name: "Indira Gandhi International Airport", city: "New Delhi", country: "India", lat: 28.56, lon: 77.1, tz: "Asia/Kolkata"),
        CatalogAirport(iata: "BOM", name: "Chhatrapati Shivaji Maharaj International Airport", city: "Mumbai", country: "India", lat: 19.09, lon: 72.87, tz: "Asia/Kolkata"),
        CatalogAirport(iata: "DXB", name: "Dubai International Airport", city: "Dubai", country: "United Arab Emirates", lat: 25.25, lon: 55.36, tz: "Asia/Dubai"),
        CatalogAirport(iata: "GRU", name: "Guarulhos International Airport", city: "São Paulo", country: "Brazil", lat: -23.43, lon: -46.47, tz: "America/Sao_Paulo"),
        CatalogAirport(iata: "DED", name: "Jolly Grant Airport", city: "Dehradun", country: "India", lat: 30.19, lon: 78.18, tz: "Asia/Kolkata"),
    ])

    func testParseSkipsBadRowsAndSortsByCode() throws {
        let data = Data(#"[["DEL","Indira Gandhi","New Delhi","India",28.5,77.1,"Asia/Kolkata"],["XX","bad","x","y",1,2,"UTC"],["AAA","Anaa","Anaa","French Polynesia",-17.3,-145.5,"Pacific/Tahiti"],"junk"]"#.utf8)
        let list = try XCTUnwrap(AirportList.parse(data))
        XCTAssertEqual(list.airports.map(\.iata), ["AAA", "DEL"])
        XCTAssertNil(AirportList.parse(Data("{}".utf8)))
    }

    func testSearchRanksExactCodeCityAndHomeCountry() {
        XCTAssertEqual(small.search("del").first?.iata, "DEL", "exact code first")
        XCTAssertEqual(small.search("De").map(\.iata), ["DED", "DEL"], "city prefix, then contains")
        XCTAssertEqual(small.search("mum").map(\.iata), ["BOM"])
        XCTAssertEqual(small.search("sao").map(\.iata), ["GRU"], "accents folded")
        XCTAssertEqual(small.search("  ").map(\.iata), ["DED", "BOM", "DEL"], "blank lists Indian airports by city")
        XCTAssertEqual(small.search("international").first?.country, "India", "home country first within a rank")
        XCTAssertEqual(small.search("d", limit: 2).count, 2)
    }
}

// MARK: - calendar suggestions and the import model

final class CalendarSuggestionTests: XCTestCase {
    private let parser = CalendarFlightParser(catalog: bundledCatalog)
    private let today = LocalDate(year: 2026, month: 11, day: 26)

    private func event(_ id: String, _ summary: String, start: String? = "2026-11-28T06:00:00+05:30", end: String? = "2026-11-28T08:10:00+05:30", day: String? = nil) -> CalendarEvent {
        CalendarEvent(
            id: id, summary: summary,
            start: day.map { CalendarEventTime(date: $0) } ?? CalendarEventTime(dateTime: start),
            end: day.map { _ in CalendarEventTime(date: nil) } ?? CalendarEventTime(dateTime: end)
        )
    }

    func testNumberRouteAndDetailsActions() throws {
        let candidates = [
            event("a", "Flight 6E 2341 DEL → BOM"),
            event("b", "Flight from Bengaluru to Kochi", start: "2026-11-29T09:00:00+05:30", end: "2026-11-29T10:10:00+05:30"),
            event("c", "Flight to Singapore", start: nil, end: nil),
        ].flatMap(parser.candidates)
        let list = buildCalendarSuggestions(candidates, tracked: [], today: today)
        XCTAssertEqual(list.count, 3)
        XCTAssertEqual(list[0].action, .byNumber(flight: "6E 2341", date: LocalDate(year: 2026, month: 11, day: 28)))
        XCTAssertEqual(list[0].departTime, "06:00")
        XCTAssertEqual(list[0].prefill, AddFlightPrefill(flight: "6E 2341", date: "2026-11-28", source: .calendar))
        XCTAssertEqual(list[1].action, .byRoute(from: "BLR", to: "COK", date: LocalDate(year: 2026, month: 11, day: 29)))
        XCTAssertEqual(list[1].prefill?.routeFrom, "BLR")
        XCTAssertEqual(list[1].prefill?.isRoute, true)
        XCTAssertEqual(list[2].action, .needsDetails, "no date: nothing to search")
        XCTAssertNil(list[2].prefill)
    }

    func testPastDuplicatesAndTrackedFlights() {
        let candidates = [
            event("old", "Flight 6E 2341 DEL → BOM", start: "2026-11-20T06:00:00+05:30", end: "2026-11-20T08:10:00+05:30"),
            event("a", "Flight 6E 2341 DEL → BOM"),
            event("a2", "IndiGo 6E-2341 Delhi-Mumbai"),
            event("r", "Flight DEL → BOM", start: "2026-11-28T09:00:00+05:30", end: "2026-11-28T11:00:00+05:30"),
        ].flatMap(parser.candidates)
        let tracked = [p3Flight(id: "x", number: "2341", date: "2026-11-28")]
        let list = buildCalendarSuggestions(candidates, tracked: tracked, today: today)
        XCTAssertEqual(list.map(\.id), ["device:a", "device:r"], "past dropped, same number and date once")
        XCTAssertTrue(list[0].alreadyTracked)
        XCTAssertTrue(list[1].alreadyTracked, "route match: same departure airport, arrival and date")
    }

    func testCandidateDateUsesStatedTimeThenAllDayThenStart() throws {
        let allDay = try XCTUnwrap(parser.parse(event("d", "Flight BLR → SIN", day: "2026-12-01")))
        XCTAssertEqual(calendarCandidateDate(allDay), LocalDate(year: 2026, month: 12, day: 1))
        let timed = try XCTUnwrap(parser.parse(event("t", "Flight BLR → SIN", start: "2026-11-30T23:30:00+05:30", end: "2026-12-01T06:30:00+08:00")))
        XCTAssertEqual(calendarCandidateDate(timed), LocalDate(year: 2026, month: 11, day: 30), "local date at the origin")
    }
}

@MainActor
final class FakeDeviceCalendar: DeviceCalendarReading {
    var auth: CalendarAuthorization = .notDetermined
    var grant = true
    var read: DeviceCalendarRead = .events([], truncated: false)
    var requests = 0
    var ranges: [(Date, Date)] = []

    func authorization() -> CalendarAuthorization { auth }
    func requestFullAccess() async -> Bool {
        requests += 1
        auth = grant ? .fullAccess : .denied
        return grant
    }
    func events(from: Date, to: Date) async -> DeviceCalendarRead {
        ranges.append((from, to))
        return read
    }
}

@MainActor
final class FixedCatalog: AirportCatalogLoading {
    func load() async -> AirportList { bundledCatalog }
}

@MainActor
final class CalendarImportModelTests: XCTestCase {
    private let clock = ManualClock(parseInstant("2026-11-26T04:00:00Z")!)

    private func model(_ device: FakeDeviceCalendar, _ flights: FakeFlights = FakeFlights()) -> CalendarImportModel {
        CalendarImportModel(calendar: device, airports: FixedCatalog(), flights: flights, clock: clock, homeTimeZone: "Asia/Kolkata")
    }

    func testNoPromptBeforeTheTap() async {
        let device = FakeDeviceCalendar()
        let m = model(device)
        await m.start()
        XCTAssertEqual(m.state, .intro)
        XCTAssertEqual(device.requests, 0)
    }

    func testTapAsksThenScansTheNextYear() async throws {
        let device = FakeDeviceCalendar()
        device.read = .events([CalendarEvent(
            id: "e1", summary: "Flight 6E 2341 DEL → BOM",
            start: CalendarEventTime(dateTime: "2026-11-28T00:30:00Z"), end: CalendarEventTime(dateTime: "2026-11-28T02:40:00Z")
        ), CalendarEvent(id: "e2", summary: "Dentist")], truncated: false)
        let m = model(device)
        await m.scanTapped()
        XCTAssertEqual(device.requests, 1)
        guard case .results(let suggestions, let read, let truncated) = m.state else { return XCTFail("\(m.state)") }
        XCTAssertEqual(read, 2)
        XCTAssertFalse(truncated)
        XCTAssertEqual(suggestions.map(\.flight), ["6E 2341"])
        let range = try XCTUnwrap(device.ranges.first)
        XCTAssertEqual(range.1.timeIntervalSince(range.0), 366 * 86_400, accuracy: 1)
    }

    func testRefusalAndRestriction() async {
        let device = FakeDeviceCalendar()
        device.grant = false
        let m = model(device)
        await m.scanTapped()
        XCTAssertEqual(m.state, .permissionDenied(restricted: false))
        device.auth = .restricted
        await m.start()
        XCTAssertEqual(m.state, .permissionDenied(restricted: true))
        device.auth = .writeOnly
        await m.start()
        XCTAssertEqual(m.state, .permissionDenied(restricted: false))
    }

    func testRefreshMarksNewlyTrackedFlights() async {
        let device = FakeDeviceCalendar()
        device.auth = .fullAccess
        device.read = .events([CalendarEvent(
            id: "e1", summary: "Flight 6E 2341 DEL → BOM",
            start: CalendarEventTime(dateTime: "2026-11-28T00:30:00Z"), end: CalendarEventTime(dateTime: "2026-11-28T02:40:00Z")
        )], truncated: true)
        let flights = FakeFlights()
        let m = model(device, flights)
        await m.start()
        guard case .results(let before, _, true) = m.state else { return XCTFail("\(m.state)") }
        XCTAssertEqual(before.first?.alreadyTracked, false)
        flights.tracked = [details(p3Flight(id: "x"))]
        await m.refreshTracked()
        guard case .results(let after, _, _) = m.state else { return XCTFail("\(m.state)") }
        XCTAssertEqual(after.first?.alreadyTracked, true)
    }

    func testUnavailableCalendar() async {
        let device = FakeDeviceCalendar()
        device.auth = .fullAccess
        device.read = .unavailable
        let m = model(device)
        await m.start()
        XCTAssertEqual(m.state, .unavailable)
    }
}

// MARK: - A1, B5 route search, B6 past flights

final class P3LimitsAndWindowsTests: XCTestCase {
    func testNoProductLimitOnlyTheAntiAbuseCap() {
        XCTAssertEqual(UserProfile.freeTrackingLimit, 100)
        XCTAssertEqual(UserProfile.proTrackingLimit, 300)
        XCTAssertEqual(TrackSource.calendar.wire, "calendar")
        XCTAssertEqual(TrackSource.fromWire("calendar"), .calendar)
    }

    func testRouteQueryRules() {
        let q = RouteSearchQuery(dep: " del ", arr: "", date: LocalDate(year: 2026, month: 11, day: 28), airline: "6e").normalized()
        XCTAssertEqual(q, RouteSearchQuery(dep: "DEL", arr: nil, date: LocalDate(year: 2026, month: 11, day: 28), airline: "6E"))
        XCTAssertTrue(q.isValid)
        XCTAssertFalse(RouteSearchQuery(dep: "DEL", arr: "DEL", date: q.date).isValid)
        XCTAssertFalse(RouteSearchQuery(dep: "DELHI", date: q.date).isValid)
        XCTAssertFalse(RouteSearchQuery(dep: "DEL", date: q.date, airline: "6EX").isValid)
        XCTAssertEqual(RouteSearchQuery.sanitizeAirlineInput(" 6e-x"), "6E")
    }

    func testSearchWindows() {
        let now = parseInstant("2026-11-26T10:00:00Z")!
        XCTAssertEqual(RouteSearchWindow.dates(now: now), LocalDate(year: 2026, month: 11, day: 25)...LocalDate(year: 2026, month: 12, day: 26))
        XCTAssertFalse(RouteSearchWindow.contains(LocalDate(year: 2026, month: 11, day: 24), now: now))
        XCTAssertEqual(PastSearchWindow.dates(now: now), LocalDate(year: 2025, month: 11, day: 26)...LocalDate(year: 2026, month: 11, day: 27))
        XCTAssertTrue(PastSearchWindow.contains(LocalDate(year: 2025, month: 12, day: 1), now: now))
        XCTAssertFalse(PastSearchWindow.contains(LocalDate(year: 2025, month: 11, day: 25), now: now))
    }
}

@MainActor
final class FakeP3Search: RouteFlightSearching, PastFlightSearching {
    var routeResult: SearchResult = .notFound
    var pastResult: SearchResult = .notFound
    var trackResult: TrackResult = .failed(nil)
    var routeQueries: [RouteSearchQuery] = []
    var pastSearches: [(String, LocalDate)] = []
    var pastTracks: [(String, TrackSource)] = []

    func searchRoute(_ query: RouteSearchQuery) async -> SearchResult {
        routeQueries.append(query)
        return routeResult
    }
    func searchPast(flightText: String, date: LocalDate) async -> SearchResult {
        pastSearches.append((flightText, date))
        return pastResult
    }
    func trackPast(flightId: String, extras: TrackExtras) async -> TrackResult {
        pastTracks.append((flightId, extras.source))
        return trackResult
    }
}

@MainActor
final class RecordingSourceFlights: FlightRepository {
    var tracked: [TrackedFlightDetails] = []
    var trackResult: TrackResult = .failed(nil)
    var sources: [TrackSource] = []
    var prefs: [[AlertType: Bool]] = []

    func trackedFlights() async -> [TrackedFlightDetails] { tracked }
    func trackedFlight(flightId: String) async -> TrackedFlightDetails? { tracked.first { $0.flight.id == flightId } }
    func flight(id: String) async -> Flight? { nil }
    func search(flightText: String, date: LocalDate) async -> SearchResult { .notFound }
    func track(flightId: String, extras: TrackExtras) async -> TrackResult {
        sources.append(extras.source)
        return trackResult
    }
    func untrack(flightId: String) async -> ActionResult { .success }
    func archive(flightId: String, archived: Bool) async -> ActionResult { .success }
    func updateNotifyPrefs(flightId: String, prefs: [AlertType: Bool]) async -> ActionResult {
        self.prefs.append(prefs)
        return .success
    }
    func updateTrackingDetails(flightId: String, extras: TrackExtras) async -> ActionResult { .success }
    func refresh() async -> ActionResult { .success }
    func applyServerSync(flightId: String, compactFlightJSON: String?) async -> ActionResult { .success }
}

@MainActor
final class RouteSearchModelTests: XCTestCase {
    private let clock = ManualClock(parseInstant("2026-11-26T04:00:00Z")!)

    func testCalendarPrefillSearchesAndTracksWithCalendarSource() async {
        let search = FakeP3Search()
        let later = p3Flight(id: "b", schedDep: "2026-11-28T15:00:00Z")
        let earlier = p3Flight(id: "a", schedDep: "2026-11-28T03:00:00Z")
        search.routeResult = .found([later, earlier])
        let flights = RecordingSourceFlights()
        flights.trackResult = .tracked(TrackedFlight(id: "t", userId: "u", flightId: "a", source: .calendar))
        let alerts = MemoryAlerts()
        alerts.mutedTypes = [.delayReduced]
        let model = RouteSearchModel(
            prefill: AddFlightPrefill(date: "2026-11-28", source: .calendar, routeFrom: "del", routeTo: "BOM"),
            routes: search, flights: flights, clock: clock, homeTimeZone: "Asia/Kolkata", backendConfigured: true, defaultAlerts: alerts
        )
        await model.start()
        XCTAssertEqual(search.routeQueries, [RouteSearchQuery(dep: "DEL", arr: "BOM", date: LocalDate(year: 2026, month: 11, day: 28))])
        guard case .results(_, let legs) = model.state.search else { return XCTFail("\(model.state.search)") }
        XCTAssertEqual(legs.map(\.id), ["a", "b"], "soonest first")
        await model.track(flightId: "a")
        XCTAssertEqual(flights.sources, [.calendar])
        XCTAssertEqual(flights.prefs, [[.delayReduced: false]])
        XCTAssertEqual(model.state.finishedFlightId, "a")
    }

    func testFormRulesAndSwap() async {
        let search = FakeP3Search()
        let model = RouteSearchModel(routes: search, flights: RecordingSourceFlights(), clock: clock, homeTimeZone: "Asia/Kolkata", backendConfigured: true)
        await model.search()
        XCTAssertEqual(model.state.formError, .missingFrom)
        XCTAssertTrue(search.routeQueries.isEmpty)
        model.setFrom("DEL")
        model.setTo("DEL")
        XCTAssertNil(model.state.from, "picking the same airport clears the other side")
        model.setFrom("BOM")
        model.swap()
        XCTAssertEqual(model.state.from, "DEL")
        XCTAssertEqual(model.state.to, "BOM")
        model.setAirline("6e1")
        XCTAssertEqual(model.state.airlineInput, "6E")
        model.setDate(LocalDate(year: 2027, month: 6, day: 1))
        XCTAssertEqual(model.state.date, LocalDate(year: 2026, month: 11, day: 26), "outside the 30-day window")
        search.routeResult = .rateLimited
        await model.search()
        XCTAssertEqual(model.state.search, .rateLimited)
        XCTAssertEqual(search.routeQueries.last?.airline, "6E")
    }

    func testAlreadyTrackedResultOpensWithoutInsert() async {
        let flights = RecordingSourceFlights()
        flights.tracked = [details(p3Flight(id: "a"))]
        let model = RouteSearchModel(routes: FakeP3Search(), flights: flights, clock: clock, homeTimeZone: "Asia/Kolkata", backendConfigured: true)
        await model.start()
        XCTAssertEqual(model.state.trackedFlightIds, ["a"])
        await model.track(flightId: "a")
        XCTAssertEqual(model.state.finishedFlightId, "a")
        XCTAssertTrue(flights.sources.isEmpty)
    }
}

@MainActor
final class AddFlightPastModeTests: XCTestCase {
    private func model(_ repo: FakeFlights, past: FakeP3Search?) -> AddFlightModel {
        AddFlightModel(
            flights: repo, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!), homeTimeZone: "Asia/Kolkata",
            backendConfigured: true, googleConfigured: false, isAnonymous: true, defaultAlerts: MemoryAlerts(), past: past
        )
    }

    func testPastModeSearchesAndTracksArchived() async {
        let repo = FakeFlights()
        let past = FakeP3Search()
        past.pastResult = .found([p3Flight(id: "old", date: "2026-11-21")])
        past.trackResult = .tracked(TrackedFlight(id: "t", userId: "u", flightId: "old", archivedAt: Date()))
        let m = model(repo, past: past)
        XCTAssertTrue(m.state.canAddPastFlights)
        m.setPastMode(true)
        XCTAssertTrue(m.state.pastMode)
        XCTAssertEqual(m.state.firstSelectableDate, LocalDate(year: 2025, month: 11, day: 22))
        XCTAssertEqual(m.state.lastSelectableDate, LocalDate(year: 2026, month: 11, day: 23))
        XCTAssertEqual(m.state.date, LocalDate(year: 2026, month: 11, day: 21), "yesterday")
        m.setDate(LocalDate(year: 2026, month: 1, day: 5))
        XCTAssertEqual(m.state.date, LocalDate(year: 2026, month: 1, day: 5))
        m.setFlightInput("6e2341")
        await m.search()
        XCTAssertEqual(past.pastSearches.first?.0, "6E 2341")
        XCTAssertTrue(repo.searches.isEmpty)
        await m.track(flightId: "old")
        XCTAssertEqual(past.pastTracks.first?.0, "old")
        XCTAssertTrue(repo.trackCalls.isEmpty)
        XCTAssertEqual(m.state.finishedFlightId, "old")
    }

    func testTurningPastModeOffRestoresTheUpcomingRange() {
        let m = model(FakeFlights(), past: FakeP3Search())
        let upcoming = m.state.firstSelectableDate...m.state.lastSelectableDate
        m.setPastMode(true)
        m.setDate(LocalDate(year: 2026, month: 2, day: 1))
        m.setPastMode(false)
        XCTAssertEqual(m.state.firstSelectableDate...m.state.lastSelectableDate, upcoming)
        XCTAssertEqual(m.state.date, upcoming.lowerBound, "clamped into the upcoming range")
    }

    func testNotOfferedWithoutThePort() {
        let m = model(FakeFlights(), past: nil)
        XCTAssertFalse(m.state.canAddPastFlights)
        m.setPastMode(true)
        XCTAssertFalse(m.state.pastMode)
    }

    func testRoutePrefillLeavesTheNumberTabEmptyButKeepsTheSource() {
        let prefill = AddFlightPrefill(date: "2026-11-28", source: .calendar, routeFrom: "DEL")
        XCTAssertTrue(prefill.isRoute)
        XCTAssertEqual(prefill.forNumberTab, AddFlightPrefill(source: .calendar))
        XCTAssertTrue(prefill.forNumberTab.isEmpty)
        let numbered = AddFlightPrefill(flight: "6E 1", date: "2026-11-28", source: .calendar)
        XCTAssertEqual(numbered.forNumberTab, numbered)
    }
}

// MARK: - B10 friend Passport

private let friendPassportJSON = """
{"friend_id":"aaaaaaaa-1111-4111-8111-111111111111","display_name":" Asha ","scope":"last12","year":2026,"pro_limited":true,
 "flights":12,"distance_km":15400,"airports":7,"countries":3,"country_codes":["AE","IN","SG"],
 "airlines":[{"carrier":"6E","name":"IndiGo","count":8},{"carrier":"AI","name":"Air India","count":3},{"carrier":"EK","name":null,"count":1},{"carrier":"SQ","count":1}],
 "longest":{"carrier":"EK","number":"569","dep":"BLR","arr":"DXB","distance_km":2700}}
"""

final class FriendPassportParseTests: XCTestCase {
    func testParsesTheReducedPassport() throws {
        let p = try XCTUnwrap(parseFriendPassport(Data(friendPassportJSON.utf8), fallbackYear: 2026))
        XCTAssertEqual(p.friendId, "aaaaaaaa-1111-4111-8111-111111111111")
        XCTAssertEqual(p.displayName, "Asha")
        XCTAssertEqual(p.scope, .last12)
        XCTAssertTrue(p.proLimited)
        XCTAssertEqual(p.side.flights, 12)
        XCTAssertEqual(p.side.airlines.map(\.carrier), ["6E", "AI", "EK"], "top three")
        XCTAssertNil(p.side.airlines[2].name)
        XCTAssertEqual(p.side.longest?.distanceKm, 2700)
        XCTAssertEqual(p.countryCodes, ["AE", "IN", "SG"])
        XCTAssertNil(parseFriendPassport(Data("null".utf8), fallbackYear: 2026))
        XCTAssertNil(parseFriendPassport(Data(#"{"flights":1}"#.utf8), fallbackYear: 2026))
        let bare = try XCTUnwrap(parseFriendPassport(Data(#"{"friend_id":"x","flights":-3,"longest":{"carrier":"6E"}}"#.utf8), fallbackYear: 2025))
        XCTAssertEqual(bare.year, 2025)
        XCTAssertEqual(bare.side.flights, 0)
        XCTAssertNil(bare.side.longest)
    }

    func testCompareRowsLeadersAndFractions() {
        let mine = PassportSide(flights: 20, distanceKm: 15400, airports: 5, countries: 0, airlines: [], longest: nil)
        let theirs = PassportSide(flights: 10, distanceKm: 15400, airports: 7, countries: 0, airlines: [], longest: LongestFlight(flightId: nil, carrier: "EK", number: "569", dep: "BLR", arr: "DXB", distanceKm: 2700))
        let rows = comparePassports(mine: mine, friend: theirs)
        XCTAssertEqual(rows.map(\.kind), CompareKind.allCases)
        XCTAssertEqual(rows[0].leader, .me)
        XCTAssertEqual(rows[0].friendFraction, 0.5)
        XCTAssertNil(rows[1].leader, "tie")
        XCTAssertEqual(rows[2].leader, .friend)
        XCTAssertEqual(rows[3].mineFraction, 0, "nobody has any")
        XCTAssertEqual(rows[4].friend, 2700)
        XCTAssertEqual(sharedCountries(mine: ["IN", "SG", "IN", "TH"], friend: ["SG", "IN"]), ["IN", "SG"])
        let six = CountedAirline(carrier: "6E", name: nil, count: 1)
        let ai = CountedAirline(carrier: "AI", name: nil, count: 1)
        XCTAssertEqual(sharedAirlines(mine: [ai, six], friend: [six]), ["6E"])
    }

    func testRefusalsMapToNotShared() {
        XCTAssertEqual(friendPassportResult(.refused(code: "42501", message: "not_shared")), .notShared)
        XCTAssertEqual(friendPassportResult(.refused(code: nil, message: "not_shared")), .notShared)
        XCTAssertEqual(friendPassportResult(.refused(code: "28000", message: "not_authenticated")), .failed("not_authenticated"))
        XCTAssertEqual(friendPassportResult(.offline), .offline)
    }
}

@MainActor
final class FakeFriendPassports: FriendPassportSource {
    var result: FriendPassportResult = .notShared
    var years: [Int?] = []
    func friendPassport(friendId: String, year: Int?) async -> FriendPassportResult {
        years.append(year)
        return result
    }
}

@MainActor
final class FriendCompareModelTests: XCTestCase {
    func testLoadsBothSidesAndSwitchesToTheYear() async throws {
        let mine = FakePassportSource()
        mine.result = .passport(try XCTUnwrap(parsePassport(Data(#"{"scope":"all","year":2026,"flights":3,"distance_km":2000,"airports":[{"iata":"DEL","count":2}],"countries":["IN","SG"],"airlines":[]}"#.utf8), fallbackYear: 2026)))
        let friends = FakeFriendPassports()
        friends.result = .loaded(try XCTUnwrap(parseFriendPassport(Data(friendPassportJSON.utf8), fallbackYear: 2026)))
        let model = FriendCompareModel(friendId: "f", friendName: "A", mine: mine, friends: friends, clock: ManualClock(parseInstant("2026-11-26T04:00:00Z")!))
        await model.load()
        XCTAssertFalse(model.state.isLoading)
        XCTAssertEqual(model.state.rows.first?.mine, 3)
        XCTAssertEqual(model.state.rows.first?.friend, 12)
        XCTAssertEqual(model.state.sharedCountries, ["IN", "SG"])
        XCTAssertTrue(model.state.limited)
        XCTAssertEqual(model.state.displayName, "Asha")
        await model.select(.year)
        XCTAssertEqual(friends.years, [nil, 2026])
        XCTAssertEqual(mine.years, [nil, 2026])
    }

    func testNotSharedShowsTheProblem() async {
        let model = FriendCompareModel(friendId: "f", friendName: "A", mine: FakePassportSource(), friends: FakeFriendPassports(), clock: ManualClock(Date()))
        await model.load()
        XCTAssertEqual(model.state.problem, .notShared)
        XCTAssertTrue(model.state.rows.isEmpty)
        XCTAssertEqual(model.state.displayName, "A")
    }
}

// MARK: - B12 crew mode

@MainActor
final class FakeProfileFlags: ProfileFlagsRepository {
    var cached = false
    var answer: RemoteResult<ProfileFlags> = .ok(ProfileFlags())
    var writes: [String] = []
    func cachedCrewMode() -> Bool { cached }
    func refresh() async -> RemoteResult<ProfileFlags> { answer }
    func setCrewMode(_ enabled: Bool) async -> RemoteResult<ProfileFlags> {
        writes.append("crew=\(enabled)")
        return answer
    }
    func setSharePassport(_ enabled: Bool) async -> RemoteResult<ProfileFlags> {
        writes.append("share=\(enabled)")
        return answer
    }
}

@MainActor
final class CrewModeTests: XCTestCase {
    func testParseFlagsAndBlockTimes() throws {
        XCTAssertEqual(parseProfileFlags(Data(#"[{"crew_mode":true,"share_passport_with_friends":false}]"#.utf8)), ProfileFlags(crewMode: true, sharePassportWithFriends: false))
        XCTAssertEqual(parseProfileFlags(Data(#"[{}]"#.utf8)), ProfileFlags(crewMode: false, sharePassportWithFriends: true))
        XCTAssertNil(parseProfileFlags(Data("[]".utf8)))
        var flight = p3Flight()
        XCTAssertNil(blockMinutes(flight))
        flight.depOffblockAt = instant("2026-11-28T13:25:00Z")
        flight.actDep = instant("2026-11-28T13:40:00Z")
        flight.arrOnblockAt = instant("2026-11-28T15:41:00Z")
        XCTAssertEqual(blockMinutes(flight), 136, "off-block to on-block, not takeoff")
        XCTAssertEqual(blockHours(minutes: 150), 3)
        let passport = try XCTUnwrap(parsePassport(Data(#"{"flights":2,"block_minutes":245}"#.utf8), fallbackYear: 2026))
        XCTAssertEqual(passport.blockMinutes, 245)
    }

    func testOptimisticSwitchRollsBackOnRefusal() async {
        let repo = FakeProfileFlags()
        repo.cached = true
        let model = ProfileFlagsModel(repository: repo)
        XCTAssertTrue(model.state.flags.crewMode, "cached value before the server answers")
        repo.answer = .ok(ProfileFlags(crewMode: true, sharePassportWithFriends: true))
        await model.load()
        XCTAssertTrue(model.state.loaded)
        repo.answer = .refused(code: "42501", message: "permission denied")
        await model.setSharePassport(false)
        XCTAssertTrue(model.state.flags.sharePassportWithFriends, "rolled back")
        XCTAssertEqual(model.state.problem, .failed)
        repo.answer = .ok(ProfileFlags(crewMode: false, sharePassportWithFriends: true))
        await model.setCrewMode(false)
        XCTAssertFalse(model.state.flags.crewMode)
        XCTAssertNil(model.state.problem)
        XCTAssertEqual(repo.writes, ["share=false", "crew=false"])
    }
}

// MARK: - B13 family

private let familyJSON = """
{"family_id":"fam-1","role":"owner","owner":{"user_id":"u-owner","display_name":"Ravi"},"owner_pro":true,"pro_via_family":false,
 "members":[{"user_id":"u-a","display_name":"Asha","joined_at":"2026-11-20T10:00:00+00:00"},{"user_id":"u-b","display_name":"  ","joined_at":null}],
 "max_members":5,"can_invite":true,"invite":{"code":"ABCD2345","expires_at":"2026-12-01T00:00:00Z"}}
"""

@MainActor
final class FakeFamily: FamilyRepository {
    var family: RemoteResult<FamilyAnswer> = .ok(.noFamily)
    var invite: RemoteResult<String> = .ok("ABCD2345")
    var accept: RemoteResult<JoinedFamily> = .ok(JoinedFamily(familyId: "fam-1", ownerId: "u-owner", ownerName: "Ravi"))
    var leaveResult: RemoteResult<Bool> = .ok(true)
    var removeResult: RemoteResult<Bool> = .ok(true)
    var codes: [String] = []
    var removed: [String] = []

    func myFamily() async -> RemoteResult<FamilyAnswer> { family }
    func createInvite() async -> RemoteResult<String> { invite }
    func acceptInvite(code: String) async -> RemoteResult<JoinedFamily> {
        codes.append(code)
        return accept
    }
    func leave() async -> RemoteResult<Bool> { leaveResult }
    func removeMember(_ userId: String) async -> RemoteResult<Bool> {
        removed.append(userId)
        return removeResult
    }
}

@MainActor
final class Counter {
    var value = 0
}

@MainActor
final class FamilyTests: XCTestCase {
    func testParseFamilyAndNull() throws {
        guard case .family(let f) = try XCTUnwrap(parseFamily(Data(familyJSON.utf8))) else { return XCTFail("no family") }
        XCTAssertTrue(f.isOwner)
        XCTAssertEqual(f.ownerName, "Ravi")
        XCTAssertEqual(f.members.map(\.userId), ["u-a", "u-b"])
        XCTAssertNil(f.members[1].displayName, "blank name")
        XCTAssertEqual(f.members[0].joinedAt, instant("2026-11-20T10:00:00Z"))
        XCTAssertEqual(f.seatsLeft, 3)
        XCTAssertEqual(f.invite?.code, "ABCD2345")
        XCTAssertEqual(parseFamily(Data("null".utf8)), .noFamily)
        XCTAssertNil(parseFamily(Data(#"{"family_id":"x"}"#.utf8)), "no owner")
        XCTAssertEqual(parseJoinedFamily(Data(#"{"family_id":"f","owner_id":"o","owner_name":"Ravi"}"#.utf8)), JoinedFamily(familyId: "f", ownerId: "o", ownerName: "Ravi"))
    }

    func testErrorMapping() {
        XCTAssertEqual(familyError(code: "42501", message: "family_requires_yearly"), .requiresYearly)
        XCTAssertEqual(familyError(code: "P0001", message: "family_full"), .full)
        XCTAssertEqual(familyError(code: "P0001", message: "family_inactive"), .inactive)
        XCTAssertEqual(familyError(code: "P0002", message: "invalid_code"), .invalidCode)
        XCTAssertEqual(familyError(code: "P0002", message: "not_member"), .notMember)
        XCTAssertEqual(familyError(code: "42501", message: "not_owner"), .notOwner)
        XCTAssertEqual(familyError(code: "23505", message: "duplicate"), .alreadyInFamily)
        XCTAssertEqual(familyError(code: "22023", message: nil), .ownCode)
        XCTAssertNil(familyError(code: "42501", message: "permission denied"), "42501 alone is ambiguous")
        XCTAssertEqual(familyInviteCode(fromURL: URL(string: "heygate://family?code=abcd-2345")!), "ABCD2345")
        XCTAssertNil(familyInviteCode(fromURL: URL(string: "heygate://friend?code=ABCD2345")!))
    }

    func testJoinReloadsEntitlementsAndFamily() async throws {
        let repo = FakeFamily()
        let reloads = Counter()
        let model = FamilyModel(repository: repo, prefillCode: "abcd 2345") { reloads.value += 1 }
        await model.load()
        XCTAssertEqual(model.state.phase, .noFamily)
        XCTAssertEqual(model.state.codeInput, "ABCD2345")
        guard case .family(let info) = try XCTUnwrap(parseFamily(Data(familyJSON.utf8))) else { return XCTFail() }
        repo.family = .ok(.family(info))
        await model.join()
        XCTAssertEqual(repo.codes, ["ABCD2345"])
        XCTAssertEqual(reloads.value, 1)
        XCTAssertEqual(model.state.joined?.ownerName, "Ravi")
        XCTAssertEqual(model.state.family?.familyId, "fam-1")
        XCTAssertFalse(model.state.busy)
    }

    func testRefusalsAreTyped() async {
        let repo = FakeFamily()
        repo.accept = .refused(code: "P0001", message: "family_full")
        let model = FamilyModel(repository: repo)
        model.setCode("abc")
        await model.join()
        XCTAssertEqual(model.state.problem, .refused(.invalidCode), "too short: no call")
        XCTAssertTrue(repo.codes.isEmpty)
        model.setCode("ABCD2345")
        await model.join()
        XCTAssertEqual(model.state.problem, .refused(.full))
        repo.invite = .refused(code: "42501", message: "family_requires_yearly")
        await model.createInvite()
        XCTAssertEqual(model.state.problem, .refused(.requiresYearly))
        repo.invite = .ok("WXYZ6789")
        await model.createInvite()
        XCTAssertEqual(model.state.newInvite, "WXYZ6789")
        model.inviteShared()
        XCTAssertNil(model.state.newInvite)
        repo.removeResult = .offline
        await model.remove("u-a")
        XCTAssertEqual(model.state.problem, .offline)
        XCTAssertEqual(repo.removed, ["u-a"])
    }
}

// MARK: - B9 watch payload

final class WatchPayloadTests: XCTestCase {
    func testRoundTripAndWidgetConversion() throws {
        var flight = p3Flight(id: "w1")
        flight.depGate = "42B"
        flight.depTerminal = "1"
        flight.depOffblockAt = instant("2026-11-28T13:25:00Z")
        let now = instant("2026-11-28T10:00:00Z")
        let payload = watchPayload(flights: [flight], now: now)
        XCTAssertEqual(payload.flights.count, 1)
        let text = try XCTUnwrap(WatchPayloadCodec.encode(payload))
        XCTAssertTrue(text.contains(#""c":"6E""#))
        XCTAssertTrue(text.contains(#""dg":"42B""#))
        XCTAssertTrue(text.contains(#""sd":"2026-11-28T13:20:00Z""#))
        let back = try XCTUnwrap(WatchPayloadCodec.decode(text))
        XCTAssertEqual(back, payload)
        XCTAssertFalse(WatchPayloadCodec.differs(payload, back))
        let widget = back.flights[0].widgetFlight
        XCTAssertEqual(widget, WidgetFlight(flight))
        XCTAssertEqual(widgetFlight(in: back.widgetSnapshot, at: now)?.id, "w1")
    }

    func testRejectsNewerVersionsAndFollowsTheWidgetChoice() throws {
        XCTAssertNil(WatchPayloadCodec.decode(#"{"v":2,"at":"2026-11-28T10:00:00Z","flights":[]}"#))
        XCTAssertNil(WatchPayloadCodec.decode("nope"))
        let now = instant("2026-11-28T20:00:00Z")
        let landedLongAgo = p3Flight(id: "old", status: .arrived, schedDep: "2026-11-27T03:00:00Z", schedArr: "2026-11-27T05:00:00Z")
        let next = p3Flight(id: "next", date: "2026-11-29", schedDep: "2026-11-29T03:00:00Z", schedArr: "2026-11-29T05:00:00Z")
        let later = p3Flight(id: "later", date: "2026-11-30", schedDep: "2026-11-30T03:00:00Z", schedArr: "2026-11-30T05:00:00Z")
        let payload = watchPayload(flights: [later, landedLongAgo, next], now: now)
        XCTAssertEqual(payload.flights.map(\.id), ["next", "later"])
        XCTAssertEqual(payload.flights.map(\.id), widgetSnapshot(flights: [later, landedLongAgo, next], now: now).flights.map(\.id))
    }
}

// MARK: - B13 family Pro in the app's entitlement

final class FamilyEntitlementTests: XCTestCase {
    private let now = parseInstant("2026-11-26T04:00:00Z")!

    func testFamilyGrantMakesAFreeMemberPro() {
        let profile = UserProfile(id: "u1", displayName: nil, plan: .free, proUntil: nil, trialFlightId: "f9")
        let state = EntitlementRules.resolve(
            profile: profile, store: nil, currentUserId: "u1", now: now, family: FamilyGrant(userId: "u1", ownerName: "Asha")
        )
        XCTAssertTrue(state.isPro)
        XCTAssertEqual(state.source, .family)
        XCTAssertTrue(state.isViaFamily)
        XCTAssertEqual(state.familyOwnerName, "Asha")
        XCTAssertNil(state.proUntil)
        XCTAssertTrue(state.confirmedByServer)
        XCTAssertEqual(state.trialFlightId, "f9")
        XCTAssertEqual(state.access(for: "any"), .pro)
    }

    func testOwnProWinsAndAnotherUsersGrantIsIgnored() {
        let until = now.addingTimeInterval(86_400 * 30)
        let own = EntitlementRules.resolve(
            profile: UserProfile(id: "u1", displayName: nil, plan: .pro, proUntil: until), store: nil, currentUserId: "u1",
            now: now, family: FamilyGrant(userId: "u1", ownerName: "Asha")
        )
        XCTAssertEqual(own.source, .server)
        XCTAssertFalse(own.isViaFamily)
        XCTAssertNil(own.familyOwnerName)

        let stale = EntitlementRules.resolve(
            profile: UserProfile(id: "u2", displayName: nil, plan: .free, proUntil: nil), store: nil, currentUserId: "u2",
            now: now, family: FamilyGrant(userId: "u1", ownerName: "Asha")
        )
        XCTAssertFalse(stale.isPro)
        XCTAssertEqual(
            EntitlementRules.resolve(profile: nil, store: nil, currentUserId: nil, now: now, family: FamilyGrant(userId: "u1", ownerName: nil)),
            .free
        )
    }
}

@MainActor
final class FixedFamilyGrant: FamilyGrantProviding {
    var grant: FamilyGrant?
    func familyGrant() -> FamilyGrant? { grant }
}

@MainActor
final class ProServiceFamilyTests: XCTestCase {
    func testProServiceReadsTheCachedFamilyGrant() async {
        let family = FixedFamilyGrant()
        let service = ProService(
            billing: FakeBilling(), profiles: FakeProfiles(), auth: FakeAuth(), sync: FakeSync(),
            clock: ManualClock(parseInstant("2026-11-26T04:00:00Z")!), family: family
        )
        let before = await service.proState()
        XCTAssertFalse(before.isPro)
        family.grant = FamilyGrant(userId: "user-1", ownerName: nil)
        let after = await service.proState()
        XCTAssertTrue(after.isViaFamily)
        XCTAssertNil(after.familyOwnerName)
    }
}
