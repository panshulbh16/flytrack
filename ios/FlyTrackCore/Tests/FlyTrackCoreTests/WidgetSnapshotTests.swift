import Foundation
import XCTest
@testable import FlyTrackCore

// iOS widgets (docs/P3_CONTRACTS.md A4): what the app writes into the App Group and what the widget shows when.

private func t(_ text: String) -> Date { parseInstant(text)! }

private let delW = Airport(iata: "DEL", name: "Indira Gandhi International", city: "Delhi", tz: "Asia/Kolkata", lat: 28.5665, lon: 77.1031)
private let bomW = Airport(iata: "BOM", name: "Chhatrapati Shivaji Maharaj International", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.0887, lon: 72.8679)

/// DEL-BOM, scheduled `dep` to `dep + 2h10m`.
private func wFlight(
    id: String, dep: String, status: FlightStatus = .scheduled, estDep: Date? = nil, actDep: Date? = nil,
    actArr: Date? = nil, gate: String? = nil, belt: String? = nil, takeoff: Date? = nil, onblock: Date? = nil
) -> Flight {
    let schedDep = t(dep)
    return Flight(
        id: id, carrierIata: "6E", flightNumber: "2134", flightDate: LocalDate(year: 2026, month: 11, day: 26),
        depIata: "DEL", arrIata: "BOM", status: status, schedDep: schedDep, estDep: estDep, actDep: actDep,
        depTerminal: "1", depGate: gate, schedArr: schedDep.addingTimeInterval(130 * 60), actArr: actArr,
        arrTerminal: "2", arrGate: "C5", baggageBelt: belt, depAirport: delW, arrAirport: bomW,
        depRunwayAt: takeoff, arrOnblockAt: onblock
    )
}

final class WidgetSnapshotTests: XCTestCase {
    private let now = t("2026-11-26T06:00:00Z")

    func testContractNames() {
        XCTAssertEqual(WidgetContract.appGroup, "group.app.flytrack")
        XCTAssertEqual(WidgetContract.kind, "FlyTrackWidget")
    }

    func testSnapshotKeepsUpcomingAndCurrentFlightsSoonestFirst() {
        let landedLongAgo = wFlight(id: "old", dep: "2026-11-25T20:00:00Z", status: .arrived,
                                    actDep: t("2026-11-25T20:05:00Z"), actArr: t("2026-11-25T22:10:00Z"))
        let later = wFlight(id: "later", dep: "2026-11-28T04:00:00Z")
        let next = wFlight(id: "next", dep: "2026-11-26T09:00:00Z")
        let cancelledPast = wFlight(id: "cx", dep: "2026-11-26T02:00:00Z", status: .cancelled)
        let snapshot = widgetSnapshot(flights: [later, landedLongAgo, next, cancelledPast], now: now)
        XCTAssertEqual(snapshot.flights.map(\.id), ["next", "later"])
        XCTAssertEqual(snapshot.writtenAt, now)
        XCTAssertEqual(snapshot.flights.first?.designator, "6E 2134")
        XCTAssertEqual(snapshot.flights.first?.depCity, "Delhi")
    }

    func testSnapshotIsCapped() {
        let flights = (1...9).map { wFlight(id: "f\($0)", dep: "2026-12-0\($0)T23:00:00Z") }
        XCTAssertEqual(widgetSnapshot(flights: flights, now: now).flights.count, WidgetContract.maxFlights)
    }

    func testAirborneFlightWinsAndLandedRollsOver() {
        let airborne = wFlight(id: "air", dep: "2026-11-26T05:00:00Z", status: .enRoute, actDep: t("2026-11-26T05:10:00Z"),
                               takeoff: t("2026-11-26T05:20:00Z"))
        let next = wFlight(id: "next", dep: "2026-11-26T12:00:00Z")
        let snapshot = WidgetSnapshot(writtenAt: now, flights: [WidgetFlight(airborne), WidgetFlight(next)])
        XCTAssertEqual(widgetFlight(in: snapshot, at: now)?.id, "air")

        let landed = wFlight(id: "air", dep: "2026-11-26T05:00:00Z", status: .arrived, actDep: t("2026-11-26T05:10:00Z"),
                             actArr: t("2026-11-26T07:15:00Z"), belt: "4", onblock: t("2026-11-26T07:20:00Z"))
        let after = WidgetSnapshot(writtenAt: now, flights: [WidgetFlight(landed), WidgetFlight(next)])
        XCTAssertEqual(widgetFlight(in: after, at: t("2026-11-26T08:19:00Z"))?.id, "air", "an hour at the belt")
        XCTAssertEqual(widgetFlight(in: after, at: t("2026-11-26T08:20:00Z"))?.id, "next")
        XCTAssertNil(widgetFlight(in: .empty, at: now))
    }

    func testPhases() {
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z")), at: now), .upcoming)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z", status: .checkIn)), at: now), .checkIn)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z", status: .gateClosed)), at: now), .boarding)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T05:00:00Z", status: .departed, actDep: t("2026-11-26T05:05:00Z"))), at: now), .departed)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T05:00:00Z", status: .departed, takeoff: t("2026-11-26T05:15:00Z"))), at: now), .inAir)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T03:00:00Z", status: .landed, actArr: t("2026-11-26T05:10:00Z"))), at: now), .landed)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z", status: .cancelled)), at: now), .cancelled)
        XCTAssertEqual(widgetPhase(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T05:00:00Z", status: .diverted)), at: now), .diverted)
    }

    func testDisplayBeforeDeparture() {
        let flight = WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z", estDep: t("2026-11-26T09:40:00Z"), gate: "42B"))
        let display = widgetDisplay(flight, at: now)
        XCTAssertEqual(display.phase, .upcoming)
        XCTAssertEqual(display.depTime, "15:10", "best departure in IST")
        XCTAssertEqual(display.schedDepTime, "14:30")
        XCTAssertEqual(display.lateMin, 40)
        XCTAssertEqual(display.countdownTarget, t("2026-11-26T09:40:00Z"))
        XCTAssertEqual(display.gate, "42B")
        XCTAssertEqual(display.terminal, "1")
        XCTAssertNil(display.belt)
        XCTAssertNil(display.progress)
    }

    func testDisplayInTheAirAndAtTheBelt() {
        let air = widgetDisplay(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T05:00:00Z", status: .enRoute,
                                                     actDep: t("2026-11-26T05:00:00Z"), takeoff: t("2026-11-26T05:00:00Z"))), at: now)
        XCTAssertEqual(air.phase, .inAir)
        XCTAssertEqual(air.countdownTarget, t("2026-11-26T07:10:00Z"))
        XCTAssertNil(air.gate)
        XCTAssertNil(air.schedDepTime)
        XCTAssertEqual(air.progress ?? -1, 60.0 / 130.0, accuracy: 0.001)

        let landed = widgetDisplay(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T03:00:00Z", status: .arrived,
                                                        actDep: t("2026-11-26T03:00:00Z"), actArr: t("2026-11-26T05:40:00Z"), belt: "4")), at: now)
        XCTAssertEqual(landed.phase, .landed)
        XCTAssertEqual(landed.belt, "4")
        XCTAssertEqual(landed.gate, "C5", "arrival gate after landing")
        XCTAssertEqual(landed.lateMin, 30)
        XCTAssertNil(landed.countdownTarget)

        let cancelled = widgetDisplay(WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z", status: .cancelled, estDep: t("2026-11-26T10:00:00Z"))), at: now)
        XCTAssertNil(cancelled.lateMin, "a cancelled flight is not late")
    }

    func testMilestonesAndEntries() {
        let flight = WidgetFlight(wFlight(id: "a", dep: "2026-11-26T09:00:00Z"))
        XCTAssertEqual(widgetMilestones(flight, after: now), [
            t("2026-11-26T08:20:00Z"), t("2026-11-26T09:00:00Z"), t("2026-11-26T11:10:00Z"), t("2026-11-26T17:10:00Z"),
        ], "boarding, departure, arrival, and the latest roll-over (6 h after the expected arrival without data)")
        let far = WidgetFlight(wFlight(id: "b", dep: "2026-12-10T09:00:00Z"))
        XCTAssertEqual(widgetMilestones(far, after: now), [])

        let snapshot = WidgetSnapshot(writtenAt: now, flights: [flight, far])
        let entries = widgetEntryDates(snapshot, now: now)
        XCTAssertEqual(entries.first, now)
        XCTAssertEqual(entries.count, 5)
        XCTAssertEqual(widgetReloadDate(entries: entries, now: now), now.addingTimeInterval(WidgetContract.idleRefresh),
                       "never wait longer than the idle refresh")
        XCTAssertEqual(widgetReloadDate(entries: [now, now.addingTimeInterval(600)], now: now), now.addingTimeInterval(600))
        XCTAssertEqual(widgetReloadDate(entries: [now], now: now), now.addingTimeInterval(WidgetContract.idleRefresh))
        XCTAssertEqual(widgetEntryDates(.empty, now: now), [now])
    }

    func testCodecRoundTripAndRejectsNewerVersions() throws {
        let snapshot = widgetSnapshot(flights: [wFlight(id: "a", dep: "2026-11-26T09:00:00Z", gate: "42B")], now: now)
        let data = try XCTUnwrap(WidgetSnapshotCodec.encode(snapshot))
        XCTAssertEqual(WidgetSnapshotCodec.decode(data), snapshot)
        XCTAssertNil(WidgetSnapshotCodec.decode(nil))
        XCTAssertNil(WidgetSnapshotCodec.decode(Data("garbage".utf8)))
        var newer = snapshot
        newer.version = WidgetSnapshot.currentVersion + 1
        XCTAssertNil(WidgetSnapshotCodec.decode(WidgetSnapshotCodec.encode(newer)))
    }
}
