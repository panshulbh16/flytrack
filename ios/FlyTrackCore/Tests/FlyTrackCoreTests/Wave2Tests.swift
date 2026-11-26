import Foundation
import XCTest
@testable import FlyTrackCore

// P2 wave 2 contract (docs/P2_WAVE2_CONTRACTS.md): friends (section 1), forwarding address (2), calendar (3),
// forecast (4) and when to leave (5). `leave_by_cases.json` is a byte-for-byte copy of Android's fixture (contract:
// "Tests use the same fixtures on Android and iOS"); `invite_code_cases.json` follows Android's invite-code rules.

private func fixture(_ name: String) throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

private func instant(_ text: String) -> Date { parseInstant(text)! }

private let flightUUID = "11111111-2222-4333-8444-555555555555"
private let ashaId = "aaaaaaaa-1111-4111-8111-111111111111"
private let delAirport = Airport(iata: "DEL", name: "Indira Gandhi International", city: "Delhi", tz: "Asia/Kolkata", lat: 28.5665, lon: 77.1031)
private let bomAirport = Airport(iata: "BOM", name: "Chhatrapati Shivaji Maharaj International", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.0887, lon: 72.8679)
private let dxbAirport = Airport(iata: "DXB", name: "Dubai International", city: "Dubai", tz: "Asia/Dubai", lat: 25.2528, lon: 55.3644)

/// 6E 2341 DEL-BOM, scheduled 12:55-15:10 UTC (18:25 IST: evening peak).
private func wave2Flight(
    status: FlightStatus = .scheduled, schedDep: Date? = instant("2026-11-25T12:55:00Z"), estDep: Date? = nil,
    actDep: Date? = nil, schedArr: Date? = instant("2026-11-25T15:10:00Z"), estArr: Date? = nil, actArr: Date? = nil,
    depTerminal: String? = nil, arrAirport: Airport? = bomAirport
) -> Flight {
    Flight(
        id: flightUUID, carrierIata: "6E", flightNumber: "2341", flightDate: LocalDate(year: 2026, month: 11, day: 25),
        depIata: "DEL", arrIata: arrAirport?.iata ?? "BOM", status: status, schedDep: schedDep, estDep: estDep, actDep: actDep,
        depTerminal: depTerminal, schedArr: schedArr, estArr: estArr, actArr: actArr, depAirport: delAirport, arrAirport: arrAirport
    )
}

private func health(level: HealthLevel = .normal, headline: String = "Operating normally", fog: Bool? = nil) -> AirportHealth {
    let detail = fog.map {
        HealthDetail(avgDepDelayMin: nil, delayedPct: nil, cancelledToday: 0, sampleSize: 0,
                     weather: AirportWeather(visibilityM: 800, category: .ifr, fog: $0, windKts: nil, wx: nil, observedAt: nil))
    }
    return AirportHealth(iata: "DEL", level: level, headline: headline, updatedAt: nil, detail: detail)
}

// MARK: - when to leave

final class LeaveByFixtureTests: XCTestCase {
    private func root() throws -> [String: Any] {
        try XCTUnwrap(JSON.object(from: try fixture("leave_by_cases")))
    }

    func testLeaveByMatchesSharedFixtures() throws {
        let cases = try XCTUnwrap(try root()["leaveBy"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let dep = try XCTUnwrap(parseInstant(item["depTime"] as? String), name)
            let international = try XCTUnwrap(JSON.bool(item["international"]), name)
            let travel = try XCTUnwrap(JSON.int(item["travelMinutes"]), name)
            let fog = try XCTUnwrap(JSON.bool(item["fogAtDep"]), name)
            let peak = try XCTUnwrap(JSON.bool(item["peak"]), name)
            let leave = leaveBy(depTime: dep, international: international, travelMinutes: travel, fogAtDep: fog, peak: peak)
            XCTAssertEqual(leave, parseInstant(item["leaveBy"] as? String), name)
            XCTAssertEqual(leaveBufferMinutes(international: international, fogAtDep: fog, peak: peak), JSON.int(item["bufferMinutes"]), name)
            let plan = LeavePlan(leaveBy: leave, departure: dep, depTz: nil, travelMinutes: travel, bufferMinutes: 0, international: international, fog: fog, peak: peak)
            XCTAssertEqual(plan.reminderAt, parseInstant(item["reminderAt"] as? String), name)
        }
    }

    func testPeakHoursMatchSharedFixtures() throws {
        let cases = try XCTUnwrap(try root()["peak"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for item in cases {
            let name = item["name"] as? String ?? "?"
            let dep = try XCTUnwrap(parseInstant(item["depTime"] as? String), name)
            XCTAssertEqual(isPeakHour(dep, depTz: JSON.string(item["tz"])), JSON.bool(item["peak"]), name)
        }
    }

    func testDefaultsMatchSharedFixtures() throws {
        let defaults = try XCTUnwrap(JSON.object(try root()["defaults"]))
        XCTAssertEqual(JSON.int(defaults["travelMinutes"]), LeaveRules.defaultTravelMin)
        XCTAssertEqual(JSON.int(defaults["reminderBeforeMinutes"]), LeaveRules.reminderBeforeMin)
    }
}

final class LeavePlanTests: XCTestCase {
    private let now = instant("2026-11-25T06:00:00Z")

    func testPlanUsesEstimatedDepartureAndPeakAtTheAirport() throws {
        // est 13:30 UTC = 19:00 IST: peak. Domestic (both Asia/Kolkata): 120 + 20 + 45.
        let plan = try XCTUnwrap(leavePlan(flight: wave2Flight(estDep: instant("2026-11-25T13:30:00Z")), travelMinutes: 45, fogAtDep: false, now: now))
        XCTAssertEqual(plan.departure, instant("2026-11-25T13:30:00Z"))
        XCTAssertTrue(plan.peak)
        XCTAssertFalse(plan.international)
        XCTAssertEqual(plan.bufferMinutes, 140)
        XCTAssertEqual(plan.leaveBy, instant("2026-11-25T10:25:00Z"))
        XCTAssertEqual(plan.leaveByTime, "15:55")
        XCTAssertEqual(plan.departureTime, "19:00")
        XCTAssertEqual(plan.reminderAt, instant("2026-11-25T10:10:00Z"))
    }

    func testInternationalWhenZonesDifferAndTravelIsClamped() throws {
        let plan = try XCTUnwrap(leavePlan(flight: wave2Flight(arrAirport: dxbAirport), travelMinutes: 9_999, fogAtDep: true, now: now))
        XCTAssertTrue(plan.international)
        XCTAssertEqual(plan.travelMinutes, LeaveRules.maxTravelMin)
        XCTAssertEqual(plan.bufferMinutes, 180 + 30 + 20)
        let unknownZone = try XCTUnwrap(leavePlan(flight: wave2Flight(arrAirport: nil), travelMinutes: -5, fogAtDep: false, now: now))
        XCTAssertTrue(unknownZone.international, "an unknown zone counts as international, like check-in")
        XCTAssertEqual(unknownZone.travelMinutes, 0)
    }

    func testNoPlanForGoneCancelledDivertedOrPastFlights() {
        XCTAssertNil(leavePlan(flight: wave2Flight(status: .cancelled), travelMinutes: 60, fogAtDep: false, now: now))
        XCTAssertNil(leavePlan(flight: wave2Flight(status: .diverted), travelMinutes: 60, fogAtDep: false, now: now))
        XCTAssertNil(leavePlan(flight: wave2Flight(actDep: instant("2026-11-25T05:00:00Z")), travelMinutes: 60, fogAtDep: false, now: now))
        XCTAssertNil(leavePlan(flight: wave2Flight(status: .departed), travelMinutes: 60, fogAtDep: false, now: now))
        XCTAssertNil(leavePlan(flight: wave2Flight(schedDep: nil), travelMinutes: 60, fogAtDep: false, now: now))
        XCTAssertNil(leavePlan(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, now: instant("2026-11-25T12:55:00Z")))
    }

    func testAirportHealthSlowsDeparture() {
        XCTAssertFalse(health().slowsDeparture)
        XCTAssertFalse(health(level: .busy, headline: "Departures running 20 min late").slowsDeparture)
        XCTAssertTrue(health(level: .disrupted, headline: "Departures running 50 min late").slowsDeparture)
        XCTAssertTrue(health(level: .severe, headline: "12 flights cancelled today").slowsDeparture)
        XCTAssertTrue(health(level: .busy, headline: "Fog: visibility 400 m").slowsDeparture)
        XCTAssertTrue(health(level: .busy, headline: "something new", fog: true).slowsDeparture)
    }

    func testHealthIsAskedOnlyWithinADayOfDeparture() {
        let flight = wave2Flight()
        XCTAssertFalse(leaveNeedsHealth(flight: flight, now: instant("2026-11-24T12:00:00Z")))
        XCTAssertTrue(leaveNeedsHealth(flight: flight, now: instant("2026-11-24T13:00:00Z")))
        XCTAssertFalse(leaveNeedsHealth(flight: flight, now: instant("2026-11-25T13:00:00Z")))
    }

    func testDirections() {
        XCTAssertEqual(directionsDestination(airport: delAirport, iata: "DEL", terminal: " 3 "), "Indira Gandhi International Terminal 3, Delhi")
        XCTAssertEqual(directionsDestination(airport: delAirport, iata: "DEL", terminal: nil), "Indira Gandhi International, Delhi")
        XCTAssertEqual(directionsDestination(airport: nil, iata: "DEL", terminal: "3"), "DEL airport")
        let blank = Airport(iata: "XYZ", name: " ", city: nil, tz: "Asia/Kolkata", lat: 12.5, lon: 77.25)
        XCTAssertEqual(directionsDestination(airport: blank, iata: "XYZ", terminal: nil), "12.5,77.25")
        XCTAssertEqual(
            googleMapsDirectionsURL(airport: delAirport, iata: "DEL", terminal: "3")?.absoluteString,
            "https://www.google.com/maps/dir/?api=1&destination=Indira%20Gandhi%20International%20Terminal%203%2C%20Delhi"
        )
        XCTAssertEqual(
            appleMapsDirectionsURL(airport: delAirport, iata: "DEL", terminal: nil)?.absoluteString,
            "https://maps.apple.com/?daddr=28.5665,77.1031&dirflg=d&q=Indira%20Gandhi%20International%2C%20Delhi"
        )
        XCTAssertEqual(appleMapsDirectionsURL(airport: nil, iata: "DEL", terminal: nil)?.absoluteString, "https://maps.apple.com/?daddr=DEL%20airport&dirflg=d")
    }
}

final class WhenToLeaveStateTests: XCTestCase {
    private let now = instant("2026-11-25T06:00:00Z")

    func testCardStates() throws {
        XCTAssertEqual(whenToLeaveState(flight: nil, travelMinutes: 60, fogAtDep: false, proAccess: true, now: now), .hidden)
        XCTAssertEqual(whenToLeaveState(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, proAccess: nil, now: now), .hidden)
        XCTAssertEqual(whenToLeaveState(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, proAccess: false, now: now), .locked)
        XCTAssertEqual(whenToLeaveState(flight: wave2Flight(status: .cancelled), travelMinutes: 60, fogAtDep: false, proAccess: false, now: now), .hidden)
        guard case .ready(let card) = whenToLeaveState(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, proAccess: true, now: now) else {
            return XCTFail("expected a card")
        }
        // 12:55 UTC = 18:25 IST (peak): 120 + 20 + 60 = 200 min before.
        XCTAssertEqual(card.plan.leaveBy, instant("2026-11-25T09:35:00Z"))
        XCTAssertEqual(card.depCity, "Delhi")
        XCTAssertTrue(card.reminderAhead)
        XCTAssertNotNil(card.googleMapsURL)
        XCTAssertNotNil(card.appleMapsURL)
    }

    func testReminderActions() throws {
        let ready = whenToLeaveState(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, proAccess: true, now: now)
        XCTAssertEqual(reminderAction(ready, proKnown: true), .schedule(at: instant("2026-11-25T09:20:00Z")))
        // Between the reminder and departure: the reminder fired or is firing; leave it alone.
        let late = whenToLeaveState(flight: wave2Flight(), travelMinutes: 60, fogAtDep: false, proAccess: true, now: instant("2026-11-25T09:30:00Z"))
        XCTAssertEqual(reminderAction(late, proKnown: true), .keep)
        XCTAssertEqual(reminderAction(.locked, proKnown: true), .cancel)
        XCTAssertEqual(reminderAction(.hidden, proKnown: true), .cancel)
        XCTAssertEqual(reminderAction(.hidden, proKnown: false), .keep)
    }

    func testReplanAfterADelayMovesTheReminder() {
        let delayed = wave2Flight(estDep: instant("2026-11-25T14:55:00Z")) // +2 h, 20:25 IST still peak
        XCTAssertEqual(replanReminder(flight: delayed, travelMinutes: 60, fog: false, pro: true, now: now), .schedule(at: instant("2026-11-25T11:20:00Z")))
        XCTAssertEqual(replanReminder(flight: nil, travelMinutes: 60, fog: false, pro: true, now: now), .cancel)
        XCTAssertEqual(replanReminder(flight: wave2Flight(status: .cancelled), travelMinutes: 60, fog: false, pro: true, now: now), .cancel)
        XCTAssertEqual(replanReminder(flight: wave2Flight(), travelMinutes: 60, fog: false, pro: false, now: now), .cancel)
    }

    func testReminderIdentifiers() {
        let id = LeaveReminderContract.identifier(flightId: flightUUID)
        XCTAssertEqual(id, "\(flightUUID):leave")
        XCTAssertEqual(LeaveReminderContract.flightId(fromIdentifier: id), flightUUID)
        XCTAssertNil(LeaveReminderContract.flightId(fromIdentifier: "\(flightUUID):gate"))
        XCTAssertNil(LeaveReminderContract.flightId(fromIdentifier: "not-a-uuid:leave"))
    }
}

@MainActor
final class FakeTravelTimes: TravelTimeStore {
    var minutes: [String: Int] = [:]
    func travelMinutes(iata: String) -> Int { minutes[iata] ?? LeaveRules.defaultTravelMin }
    func setTravelMinutes(iata: String, minutes value: Int) { minutes[iata] = clampTravelMinutes(value) }
}

@MainActor
final class FakeLeaveScheduler: LeaveReminderScheduling {
    var scheduled: [(flightId: String, at: Date)] = []
    var cancelled: [String] = []
    func schedule(flightId: String, plan: LeavePlan, designator: String, depCity: String) async {
        scheduled.append((flightId, plan.reminderAt))
    }
    func cancel(flightId: String) async { cancelled.append(flightId) }
}

@MainActor
final class CountingHealthSource: AirportHealthSource {
    var answer: AirportHealthResult
    var calls = 0
    init(_ answer: AirportHealthResult) { self.answer = answer }
    func health(iata: String) async -> AirportHealthResult {
        calls += 1
        return answer
    }
}

@MainActor
final class WhenToLeaveModelTests: XCTestCase {
    func testSchedulesOnceAndFollowsTravelAndFog() async throws {
        let clock = ManualClock(instant("2026-11-25T06:00:00Z"))
        let travel = FakeTravelTimes()
        let scheduler = FakeLeaveScheduler()
        let source = CountingHealthSource(.health(health(level: .busy, headline: "Fog: visibility 300 m")))
        let model = WhenToLeaveModel(flightId: flightUUID, travel: travel, scheduler: scheduler, health: source, clock: clock)
        await model.update(flight: wave2Flight(), pro: true)
        guard case .ready(let card) = model.state else { return XCTFail("expected a card") }
        XCTAssertTrue(card.plan.fog)
        // 120 + 30 fog + 20 peak + 60 = 230 min before 12:55.
        XCTAssertEqual(card.plan.leaveBy, instant("2026-11-25T09:05:00Z"))
        XCTAssertEqual(scheduler.scheduled.map(\.at), [instant("2026-11-25T08:50:00Z")])
        await model.update(flight: wave2Flight(), pro: true)
        XCTAssertEqual(scheduler.scheduled.count, 1, "the same plan is not scheduled twice")
        XCTAssertEqual(source.calls, 1, "Airport Health is asked once per departure hour")

        await model.setTravelMinutes(30)
        XCTAssertEqual(travel.minutes["DEL"], 30)
        XCTAssertEqual(scheduler.scheduled.last?.at, instant("2026-11-25T09:20:00Z"))

        await model.update(flight: wave2Flight(), pro: false)
        XCTAssertEqual(model.state, .locked)
        XCTAssertEqual(scheduler.cancelled, [flightUUID])
    }

    func testNoHealthCallFarFromDepartureAndUnknownProKeepsTheReminder() async {
        let clock = ManualClock(instant("2026-11-23T06:00:00Z"))
        let scheduler = FakeLeaveScheduler()
        let source = CountingHealthSource(.offline)
        let model = WhenToLeaveModel(flightId: flightUUID, travel: FakeTravelTimes(), scheduler: scheduler, health: source, clock: clock)
        await model.update(flight: wave2Flight(), pro: nil)
        XCTAssertEqual(model.state, .hidden)
        XCTAssertEqual(source.calls, 0)
        XCTAssertTrue(scheduler.scheduled.isEmpty)
        XCTAssertTrue(scheduler.cancelled.isEmpty)
        await model.update(flight: nil, pro: true)
        XCTAssertEqual(scheduler.cancelled, [flightUUID], "untracked with a known Pro state: the reminder goes")
    }
}

@MainActor
final class WhenToLeaveHealthRetryTests: XCTestCase {
    func testAnOfflineHealthAnswerIsAskedAgain() async {
        let clock = ManualClock(instant("2026-11-25T06:00:00Z"))
        let scheduler = FakeLeaveScheduler()
        let source = CountingHealthSource(.offline)
        let model = WhenToLeaveModel(flightId: flightUUID, travel: FakeTravelTimes(), scheduler: scheduler, health: source, clock: clock)
        await model.update(flight: wave2Flight(), pro: true)
        guard case .ready(let first) = model.state else { return XCTFail("expected a card") }
        XCTAssertFalse(first.plan.fog)
        source.answer = .health(health(level: .busy, headline: "Fog: visibility 300 m"))
        await model.update(flight: wave2Flight(), pro: true)
        XCTAssertEqual(source.calls, 2, "offline is not an answer: ask again")
        guard case .ready(let second) = model.state else { return XCTFail("expected a card") }
        XCTAssertTrue(second.plan.fog)
        await model.update(flight: wave2Flight(), pro: true)
        XCTAssertEqual(source.calls, 2, "a real answer is kept for the departure hour")
    }

    func testAnUnavailableAirportIsNotAskedAgain() async {
        let clock = ManualClock(instant("2026-11-25T06:00:00Z"))
        let source = CountingHealthSource(.unavailable)
        let model = WhenToLeaveModel(flightId: flightUUID, travel: FakeTravelTimes(), scheduler: FakeLeaveScheduler(), health: source, clock: clock)
        await model.update(flight: wave2Flight(), pro: true)
        await model.update(flight: wave2Flight(), pro: true)
        XCTAssertEqual(source.calls, 1)
    }
}

// MARK: - friends

final class InviteCodeFixtureTests: XCTestCase {
    func testSharedCases() throws {
        let root = try XCTUnwrap(JSON.object(from: try fixture("invite_code_cases")))
        for item in try XCTUnwrap(root["normalize"] as? [[String: Any]]) {
            let input = try XCTUnwrap(item["input"] as? String)
            XCTAssertEqual(normalizeInviteCode(input), JSON.string(item["expected"]), "normalize \(input)")
        }
        for item in try XCTUnwrap(root["extract"] as? [[String: Any]]) {
            let input = try XCTUnwrap(item["input"] as? String)
            XCTAssertEqual(extractInviteCode(input), JSON.string(item["expected"]), "extract \(input)")
        }
        for item in try XCTUnwrap(root["format"] as? [[String: Any]]) {
            let input = try XCTUnwrap(item["input"] as? String)
            XCTAssertEqual(formatInviteCode(input), JSON.string(item["expected"]), "format \(input)")
        }
    }

    func testInputAndLinks() throws {
        XCTAssertEqual(inviteInput("Follow my flights on HeyGate: ABCD-EFGH"), "ABCD-EFGH")
        XCTAssertEqual(inviteInput("abcd-ef"), "ABCD-EF")
        XCTAssertEqual(inviteInput("abcdefghjkmnpq"), "ABCDEFGHJKMN")
        XCTAssertEqual(inviteCode(fromURL: try XCTUnwrap(URL(string: "heygate://friend?code=abcd-efgh"))), "ABCDEFGH")
        XCTAssertEqual(inviteCode(fromURL: try XCTUnwrap(URL(string: "heygate://friend/ABCDEFGH"))), "ABCDEFGH")
        XCTAssertNil(inviteCode(fromURL: try XCTUnwrap(URL(string: "heygate://import"))))
        XCTAssertNil(inviteCode(fromURL: try XCTUnwrap(URL(string: "https://friend/ABCDEFGH"))))
        XCTAssertEqual(inviteLink(code: "ABCDEFGH"), "heygate://friend?code=ABCDEFGH")
    }
}

final class FriendsParsingTests: XCTestCase {
    func testMyFriends() throws {
        let friends = try XCTUnwrap(parseFriends(try fixture("my_friends")))
        XCTAssertEqual(friends.count, 2)
        XCTAssertEqual(friends[0], Friend(id: ashaId, displayName: "Asha", alertsEnabled: true, since: instant("2026-11-20T10:00:00Z")))
        XCTAssertNil(friends[1].displayName, "a blank name reads as no name")
        XCTAssertFalse(friends[1].alertsEnabled)
        XCTAssertEqual(parseFriends(Data("null".utf8)), [])
        XCTAssertNil(parseFriends(Data("{\"a\":1}".utf8)))
    }

    func testFriendsFlights() throws {
        let rows = try XCTUnwrap(parseFriendsFlights(try fixture("friends_flights")))
        XCTAssertEqual(rows.count, 2, "the row without a flight is skipped")
        let asha = rows[0]
        XCTAssertEqual(asha.friendName, "Asha")
        XCTAssertEqual(asha.flight.id, flightUUID)
        XCTAssertEqual(asha.flight.designator, "6E 2341")
        XCTAssertEqual(asha.flight.estDep, instant("2026-11-25T13:20:00Z"))
        XCTAssertEqual(asha.flight.depGate, "12")
        XCTAssertEqual(asha.flight.baggageBelt, "5")
        XCTAssertEqual(asha.flight.aircraftModel, "A320neo")
        XCTAssertEqual(asha.flight.depAirport?.city, "Delhi")
        XCTAssertEqual(asha.flight.arrAirport?.tz, "Asia/Kolkata")
        let other = rows[1]
        XCTAssertNil(other.friendName)
        XCTAssertEqual(other.flight.status, .enRoute)
        XCTAssertNil(other.flight.arrAirport, "an airport without zone and coordinates is unknown")
        XCTAssertEqual(other.flight.depAirport?.name, "DEL")
        XCTAssertEqual(other.flight.live?.altFt, 36000)
        XCTAssertEqual(other.flight.live?.headingDeg, 310)
    }

    func testAcceptedAndText() {
        XCTAssertEqual(parseAcceptedFriend(Data("{\"friend_id\":\"\(ashaId)\",\"display_name\":\"Asha \"}".utf8)), AcceptedFriend(friendId: ashaId, displayName: "Asha"))
        XCTAssertNil(parseAcceptedFriend(Data("{}".utf8)))
        XCTAssertEqual(rpcTextAnswer(Data("\"K7MNP2QR\"".utf8)), "K7MNP2QR")
        XCTAssertNil(rpcTextAnswer(Data("\"  \"".utf8)))
        XCTAssertNil(rpcTextAnswer(Data("null".utf8)))
        XCTAssertNil(rpcTextAnswer(Data("{}".utf8)))
    }

    func testAcceptErrors() {
        XCTAssertEqual(acceptInviteError(code: "P0002", message: "invalid_code"), .invalidCode)
        XCTAssertEqual(acceptInviteError(code: nil, message: "invalid_code"), .invalidCode)
        XCTAssertEqual(acceptInviteError(code: "22023", message: nil), .ownCode)
        XCTAssertEqual(acceptInviteError(code: "23505", message: "already_friends"), .alreadyFriends)
        XCTAssertEqual(acceptInviteError(code: "P0001", message: "friend_limit"), .friendLimit)
        XCTAssertEqual(acceptInviteError(code: "42501", message: "permission denied"), .failed("permission denied"))
        XCTAssertEqual(acceptInviteResult(.offline), .offline)
        XCTAssertEqual(acceptInviteResult(.ok(AcceptedFriend(friendId: ashaId, displayName: nil))), .accepted(AcceptedFriend(friendId: ashaId, displayName: nil)))
    }
}

@MainActor
final class FakeFriends: FriendsRepository {
    var friendsResult: RemoteResult<[Friend]> = .ok([])
    var inviteResult: RemoteResult<String> = .ok("K7MNP2QR")
    var acceptResult: AcceptInviteResult = .invalidCode
    var commandResult: RemoteResult<Bool> = .ok(true)
    var flightsResult: RemoteResult<[FriendFlight]> = .ok([])
    var shareResult: RemoteResult<Bool?> = .ok(true)
    var setShareResult: RemoteResult<Bool?> = .ok(false)
    var inviteCalls = 0
    var accepted: [String] = []
    var alertCalls: [(String, Bool)] = []

    func createInvite() async -> RemoteResult<String> {
        inviteCalls += 1
        return inviteResult
    }
    func acceptInvite(code: String) async -> AcceptInviteResult {
        accepted.append(code)
        return acceptResult
    }
    func friends() async -> RemoteResult<[Friend]> { friendsResult }
    func removeFriend(_ friendId: String) async -> RemoteResult<Bool> { commandResult }
    func setFriendAlerts(_ friendId: String, enabled: Bool) async -> RemoteResult<Bool> {
        alertCalls.append((friendId, enabled))
        return commandResult
    }
    func friendsFlights() async -> RemoteResult<[FriendFlight]> { flightsResult }
    func shareWithFriends(flightId: String) async -> RemoteResult<Bool?> { shareResult }
    func setShareWithFriends(flightId: String, share: Bool) async -> RemoteResult<Bool?> { setShareResult }
}

@MainActor
final class FriendsModelTests: XCTestCase {
    private let asha = Friend(id: ashaId, displayName: "Asha", alertsEnabled: true, since: nil)

    func testLoadKeepsTheListWhenARefreshFails() async {
        let repo = FakeFriends()
        repo.friendsResult = .offline
        let model = FriendsModel(repository: repo)
        await model.load()
        XCTAssertEqual(model.state.load, .offline)
        repo.friendsResult = .ok([asha])
        await model.load()
        XCTAssertEqual(model.state.load, .loaded)
        XCTAssertEqual(model.state.friends, [asha])
        repo.friendsResult = .failed("boom")
        await model.load()
        XCTAssertEqual(model.state.load, .loaded)
        XCTAssertEqual(model.state.friends, [asha])
        XCTAssertEqual(model.state.message, .failed)
        model.messageShown(.failed)
        XCTAssertNil(model.state.message)
    }

    func testInviteCreatesTheCodeOnceThenReusesIt() async throws {
        let repo = FakeFriends()
        let model = FriendsModel(repository: repo)
        await model.invite(.whatsApp)
        XCTAssertEqual(model.state.inviteShare, InviteShareRequest(code: "K7MNP2QR", target: .whatsApp))
        model.inviteHandled(try XCTUnwrap(model.state.inviteShare))
        XCTAssertNil(model.state.inviteShare)
        await model.invite(.copy)
        XCTAssertEqual(model.state.inviteShare, InviteShareRequest(code: "K7MNP2QR", target: .copy))
        XCTAssertEqual(repo.inviteCalls, 1)

        let offline = FakeFriends()
        offline.inviteResult = .offline
        let other = FriendsModel(repository: offline)
        await other.invite(.shareSheet)
        XCTAssertNil(other.state.inviteShare)
        XCTAssertEqual(other.state.message, .offline)
    }

    func testAlertSwitchRollsBackWhenRefused() async {
        let repo = FakeFriends()
        repo.friendsResult = .ok([asha])
        let model = FriendsModel(repository: repo)
        await model.load()
        repo.commandResult = .refused(code: "42501", message: nil)
        await model.setAlerts(ashaId, enabled: false)
        XCTAssertEqual(model.state.friends.first?.alertsEnabled, true)
        XCTAssertEqual(model.state.message, .failed)
        XCTAssertTrue(model.state.busy.isEmpty)
        repo.commandResult = .ok(true)
        await model.setAlerts(ashaId, enabled: false)
        XCTAssertEqual(model.state.friends.first?.alertsEnabled, false)
        await model.setAlerts(ashaId, enabled: false)
        XCTAssertEqual(repo.alertCalls.count, 2, "no call when the switch already shows the value")
    }

    func testRemove() async {
        let repo = FakeFriends()
        repo.friendsResult = .ok([asha])
        let model = FriendsModel(repository: repo)
        await model.load()
        repo.commandResult = .offline
        await model.remove(ashaId)
        XCTAssertEqual(model.state.friends.count, 1)
        XCTAssertEqual(model.state.message, .offline)
        repo.commandResult = .ok(true)
        await model.remove(ashaId)
        XCTAssertTrue(model.state.friends.isEmpty)
        XCTAssertEqual(model.state.message, .removed)
    }
}

@MainActor
final class AcceptInviteModelTests: XCTestCase {
    func testSubmitMapsErrorsAndSuccess() async {
        let repo = FakeFriends()
        let model = AcceptInviteModel(repository: repo)
        model.setInput("abc")
        XCTAssertFalse(model.state.canSubmit)
        await model.submit()
        XCTAssertEqual(model.state.error, .invalidCode)
        XCTAssertTrue(repo.accepted.isEmpty, "text that cannot be a code is never sent")

        model.setInput("My HeyGate code is k7mn-p2qr")
        XCTAssertEqual(model.state.input, "K7MN-P2QR")
        XCTAssertNil(model.state.error)
        repo.acceptResult = .ownCode
        await model.submit()
        XCTAssertEqual(repo.accepted, ["K7MNP2QR"])
        XCTAssertEqual(model.state.error, .ownCode)

        let friend = AcceptedFriend(friendId: ashaId, displayName: "Asha")
        repo.acceptResult = .accepted(friend)
        await model.submit()
        XCTAssertEqual(model.state.accepted, friend)
        XCTAssertFalse(model.state.canSubmit)
    }

    func testPrefillOnlyIntoAnEmptyField() {
        let model = AcceptInviteModel(repository: FakeFriends())
        model.prefill("ABCDEFGH")
        XCTAssertEqual(model.state.input, "ABCDEFGH")
        model.prefill("K7MNP2QR")
        XCTAssertEqual(model.state.input, "ABCDEFGH")
    }
}

@MainActor
final class FriendsFlightsAndShareTests: XCTestCase {
    func testFriendsFlightsCards() async throws {
        let repo = FakeFriends()
        repo.flightsResult = .ok(try XCTUnwrap(parseFriendsFlights(try fixture("friends_flights"))))
        let model = FriendsFlightsModel(repository: repo, clock: ManualClock(instant("2026-11-25T10:00:00Z")))
        await model.load()
        XCTAssertEqual(model.state.load, .loaded)
        XCTAssertEqual(model.state.rows.map(\.card.designator), ["6E 2341", "AI 101"])
        XCTAssertEqual(model.state.rows.first?.friendName, "Asha")
        repo.flightsResult = .offline
        await model.load()
        XCTAssertEqual(model.state.load, .loaded, "an earlier list stays")
        XCTAssertEqual(model.state.rows.count, 2)
    }

    func testShareSwitchRollsBack() async {
        let repo = FakeFriends()
        let model = ShareWithFriendsModel(flightId: flightUUID, repository: repo)
        await model.load()
        XCTAssertEqual(model.state.share, true)
        repo.setShareResult = .offline
        await model.set(false)
        XCTAssertEqual(model.state.share, true)
        XCTAssertTrue(model.state.failed)
        repo.setShareResult = .ok(false)
        await model.set(false)
        XCTAssertEqual(model.state.share, false)
        XCTAssertFalse(model.state.failed)

        let untracked = FakeFriends()
        untracked.shareResult = .ok(nil)
        let none = ShareWithFriendsModel(flightId: flightUUID, repository: untracked)
        await none.load()
        XCTAssertNil(none.state.share)
        await none.set(true)
        XCTAssertNil(none.state.share, "no switch for a flight the user does not track")
    }
}

final class FriendPushTests: XCTestCase {
    private func data(_ type: String, quiet: Bool = false, friend: Bool = true) -> [String: String] {
        var map = [
            "kind": "alert", "flight_id": flightUUID, "event_id": "e1", "event_type": type,
            "title": "6E 2341", "body": "Asha's flight 6E 2341 has landed in Mumbai",
        ]
        if friend {
            map["role"] = "friend"
            map["friend_name"] = " Asha "
        }
        if quiet { map["quiet"] = "1" }
        return map
    }

    private func alert(_ push: IncomingPush) -> PushAlert? {
        if case .alert(let alert) = push { return alert }
        return nil
    }

    func testFriendAlertChannels() throws {
        let landed = try XCTUnwrap(alert(parseIncomingPush(data("LANDED"))))
        XCTAssertTrue(landed.isFriend)
        XCTAssertEqual(landed.friendName, "Asha")
        XCTAssertEqual(landed.channelId, PushContract.channelUpdates)
        XCTAssertEqual(try XCTUnwrap(alert(parseIncomingPush(data("DELAY")))).interruptionLevel, "active", "a friend's delay is not time-sensitive")
        XCTAssertEqual(try XCTUnwrap(alert(parseIncomingPush(data("CANCELLED", quiet: true)))).channelId, PushContract.channelCritical)
        XCTAssertEqual(try XCTUnwrap(alert(parseIncomingPush(data("DIVERTED_DEST")))).interruptionLevel, "time-sensitive")
        XCTAssertEqual(try XCTUnwrap(alert(parseIncomingPush(data("DEPARTED", quiet: true)))).channelId, PushContract.channelQuiet)
        // The server's APNs level wins.
        let apns = ApnsEnvelope(interruptionLevel: "passive")
        XCTAssertEqual(try XCTUnwrap(alert(parseIncomingPush(data("DELAY"), apns: apns))).interruptionLevel, "passive")
        // A tracker's own delay stays critical.
        let own = try XCTUnwrap(alert(parseIncomingPush(data("DELAY", friend: false))))
        XCTAssertFalse(own.isFriend)
        XCTAssertNil(own.friendName)
        XCTAssertEqual(own.channelId, PushContract.channelCritical)
    }

    func testImportPush() {
        XCTAssertEqual(parseIncomingPush(["kind": "import", "count": "2", "flight_id": flightUUID, "title": "t", "body": "b"]), .imported(count: 2, flightId: flightUUID))
        XCTAssertEqual(parseIncomingPush(["kind": "import", "count": "x", "flight_id": "nope"]), .imported(count: 0, flightId: nil))
        XCTAssertEqual(friendAlertChannelId(eventType: "DIVERTED", quiet: true), PushContract.channelCritical)
        XCTAssertTrue(isFriendAlert(["role": " friend "]))
        XCTAssertFalse(isFriendAlert(["role": "tracker"]))
    }
}

// MARK: - forecast

final class ForecastTests: XCTestCase {
    func testParseFixtures() throws {
        let pro = try XCTUnwrap(try parseFlightForecast(try fixture("flight_forecast_pro")))
        XCTAssertFalse(pro.detailLocked)
        XCTAssertEqual(pro.history, ForecastHistory(samples: 23, windowDays: 60, onTimePct: 61, avgDepDelayMin: 14, medianArrDelayMin: 6, cancelled: 1))
        XCTAssertEqual(pro.forecast, ArrivalForecast(p50: instant("2026-11-25T15:16:00Z"), p80: instant("2026-11-25T15:41:00Z"), method: .history, confidence: .high))
        let locked = try XCTUnwrap(try parseFlightForecast(try fixture("flight_forecast_locked")))
        XCTAssertEqual(locked, FlightForecast(history: nil, forecast: nil, detailLocked: true))
        XCTAssertNil(try parseFlightForecast(Data("null".utf8)))
        XCTAssertThrowsError(try parseFlightForecast(Data("[1]".utf8)))
    }

    func testOddValues() throws {
        let body = """
        {"history":{"samples":0},"forecast":{"arr_p50":"2026-11-25T15:16:00Z","arr_p80":"2026-11-25T15:00:00Z","method":"magic","confidence":"sure"}}
        """
        let answer = try XCTUnwrap(try parseFlightForecast(Data(body.utf8)))
        XCTAssertEqual(answer.history?.windowDays, 60)
        XCTAssertNil(answer.forecast?.p80, "a p80 before p50 is dropped")
        XCTAssertEqual(answer.forecast?.method, .schedule)
        XCTAssertEqual(answer.forecast?.confidence, .low)
        XCTAssertNil(try parseFlightForecast(Data("{\"forecast\":{\"arr_p50\":\"later\"}}".utf8))?.forecast)
        XCTAssertTrue(FlightForecast(history: ForecastHistory(samples: 0, windowDays: 60, onTimePct: nil, avgDepDelayMin: nil, medianArrDelayMin: nil, cancelled: 0), forecast: nil, detailLocked: false).isEmpty)
    }

    func testCardStates() throws {
        let pro = try XCTUnwrap(try parseFlightForecast(try fixture("flight_forecast_pro")))
        XCTAssertEqual(forecastCardState(flight: wave2Flight(), answer: nil), .hidden)
        XCTAssertEqual(forecastCardState(flight: wave2Flight(), answer: FlightForecast(history: nil, forecast: nil, detailLocked: true)), .locked)
        XCTAssertEqual(forecastCardState(flight: wave2Flight(status: .landed), answer: pro), .hidden)
        XCTAssertEqual(forecastCardState(flight: wave2Flight(status: .cancelled), answer: pro), .hidden)
        guard case .ready(let card) = forecastCardState(flight: wave2Flight(), answer: pro) else { return XCTFail("expected a card") }
        XCTAssertEqual(card.p50Time, "20:46")
        XCTAssertEqual(card.p80Time, "21:11")
        XCTAssertEqual(card.p50VsScheduleMin, 6)
        XCTAssertEqual(card.confidence, .high)
        XCTAssertEqual(card.history?.onTimePct, 61)
    }
}

@MainActor
final class FakeForecastSource: ForecastSource {
    var result: RemoteResult<FlightForecast?> = .ok(nil)
    var calls = 0
    func forecast(flightId: String) async -> RemoteResult<FlightForecast?> {
        calls += 1
        return result
    }
}

@MainActor
final class FlightForecastModelTests: XCTestCase {
    func testAsksAgainOnlyWhenTheTimesChange() async throws {
        let source = FakeForecastSource()
        source.result = .ok(try parseFlightForecast(try fixture("flight_forecast_pro")))
        let model = FlightForecastModel(flightId: flightUUID, source: source)
        await model.update(flight: wave2Flight())
        guard case .ready = model.state else { return XCTFail("expected a card") }
        await model.update(flight: wave2Flight())
        XCTAssertEqual(source.calls, 1)
        source.result = .offline
        await model.update(flight: wave2Flight(estDep: instant("2026-11-25T13:40:00Z")))
        XCTAssertEqual(source.calls, 2)
        guard case .ready = model.state else { return XCTFail("an error keeps the earlier answer") }
        await model.update(flight: wave2Flight(status: .arrived, actArr: instant("2026-11-25T15:20:00Z")))
        XCTAssertEqual(source.calls, 2, "no call once the flight landed")
        XCTAssertEqual(model.state, .hidden)
    }

    func testAnOfflineAskIsRetriedButARefusalIsNot() async throws {
        let source = FakeForecastSource()
        source.result = .offline
        let model = FlightForecastModel(flightId: flightUUID, source: source)
        await model.update(flight: wave2Flight())
        XCTAssertEqual(model.state, .hidden)
        source.result = .ok(try parseFlightForecast(try fixture("flight_forecast_pro")))
        await model.update(flight: wave2Flight())
        XCTAssertEqual(source.calls, 2, "same times, but the first ask never got an answer")
        guard case .ready = model.state else { return XCTFail("expected a card") }
        await model.update(flight: wave2Flight())
        XCTAssertEqual(source.calls, 2)

        let refused = FakeForecastSource()
        refused.result = .refused(code: "42501", message: "not_allowed")
        let other = FlightForecastModel(flightId: flightUUID, source: refused)
        await other.update(flight: wave2Flight())
        await other.update(flight: wave2Flight())
        XCTAssertEqual(refused.calls, 1)
    }
}

// MARK: - calendar and forwarding

final class CalendarLinkTests: XCTestCase {
    private let feed = "https://abc.supabase.co/functions/v1/calendar-feed?t=0123456789abcdef0123456789abcdef"

    func testFeedLinks() {
        XCTAssertTrue(isFeedURL(feed))
        XCTAssertFalse(isFeedURL("http://abc.supabase.co/functions/v1/calendar-feed?t=x"))
        XCTAssertFalse(isFeedURL("https://abc.supabase.co/calendar feed"))
        XCTAssertFalse(isFeedURL("https:///nohost"))
        XCTAssertEqual(webcalURL(feedURL: feed)?.absoluteString, "webcal://abc.supabase.co/functions/v1/calendar-feed?t=0123456789abcdef0123456789abcdef")
        XCTAssertNil(webcalURL(feedURL: "javascript:alert(1)"))
        XCTAssertEqual(
            googleCalendarSubscribeURL(feedURL: feed)?.absoluteString,
            "https://calendar.google.com/calendar/r?cid=webcal%3A%2F%2Fabc.supabase.co%2Ffunctions%2Fv1%2Fcalendar-feed%3Ft%3D0123456789abcdef0123456789abcdef"
        )
    }

    func testEventSpec() throws {
        let terminal: (String, String) -> String = { "\($0), Terminal \($1)" }
        let event = try XCTUnwrap(calendarEvent(for: wave2Flight(estDep: instant("2026-11-25T13:20:00Z"), depTerminal: "1"), terminalLocation: terminal, notes: "n"))
        XCTAssertEqual(event.title, "✈ 6E 2341 DEL→BOM")
        XCTAssertEqual(event.start, instant("2026-11-25T13:20:00Z"))
        XCTAssertEqual(event.end, instant("2026-11-25T15:10:00Z"))
        XCTAssertEqual(event.location, "Indira Gandhi International, Terminal 1")
        XCTAssertEqual(event.timeZone, "Asia/Kolkata")
        XCTAssertEqual(event.notes, "n")
        let noArrival = try XCTUnwrap(calendarEvent(for: wave2Flight(schedArr: nil), terminalLocation: terminal, notes: ""))
        XCTAssertEqual(noArrival.end, instant("2026-11-25T14:55:00Z"))
        XCTAssertEqual(noArrival.location, "Indira Gandhi International")
        let impossible = try XCTUnwrap(calendarEvent(for: wave2Flight(schedArr: instant("2026-11-25T12:00:00Z")), terminalLocation: terminal, notes: ""))
        XCTAssertEqual(impossible.end, instant("2026-11-25T14:55:00Z"))
        XCTAssertNil(calendarEvent(for: wave2Flight(schedDep: nil), terminalLocation: terminal, notes: ""))
    }

    func testForwardingAddress() {
        XCTAssertTrue(isForwardingAddress("trips+ab12cd34ef@in.heygate.app"))
        XCTAssertFalse(isForwardingAddress("trips+ab12 cd@in.heygate.app"))
        XCTAssertFalse(isForwardingAddress("trips@localhost"))
        XCTAssertFalse(isForwardingAddress("<script>@x.y"))
        XCTAssertEqual(linkLoad(.ok("a@b.c")), .ready("a@b.c"))
        XCTAssertEqual(linkLoad(.refused(code: "42501", message: nil)), .failed)
        XCTAssertEqual(linkLoad(.offline), .offline)
    }

    func testEmailSource() {
        XCTAssertEqual(TrackSource.fromWire("email"), .email)
        XCTAssertEqual(TrackSource.email.wire, "email")
    }
}
