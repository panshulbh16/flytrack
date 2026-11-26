import Foundation
import XCTest
@testable import FlyTrackCore

// P2 contract (docs/P2_CONTRACTS.md): offline estimate and map (section 5), Airport Health (1), Passport (2),
// alert extras (3) and alert language (4). The estimate cases are the same fixture file as Android's.

private func fixtureData(_ name: String) throws -> Data {
    let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
    return try Data(contentsOf: url)
}

private func fixtureObject(_ name: String) throws -> [String: Any] {
    try XCTUnwrap(JSON.object(from: try fixtureData(name)))
}

private let del = GeoPoint(lat: 28.5665, lon: 77.1031)
private let bom = GeoPoint(lat: 19.0887, lon: 72.8679)
private let dxb = GeoPoint(lat: 25.2528, lon: 55.3644)
private let delAirport = Airport(iata: "DEL", name: "Indira Gandhi International", city: "Delhi", tz: "Asia/Kolkata", lat: 28.5665, lon: 77.1031)
private let bomAirport = Airport(iata: "BOM", name: "Chhatrapati Shivaji Maharaj International", city: "Mumbai", tz: "Asia/Kolkata", lat: 19.0887, lon: 72.8679)
// 6E 2341 DEL-BOM, scheduled 12:55-15:10 UTC (135 min), like Android's TestData.flight().
private let schedDep = parseInstant("2026-11-25T12:55:00Z")!
private let schedArr = parseInstant("2026-11-25T15:10:00Z")!
private let minute: TimeInterval = 60
private let hour: TimeInterval = 3600

private func testFlight(
    status: FlightStatus = .scheduled, schedArr arrival: Date? = schedArr, estDep: Date? = nil, actDep: Date? = nil,
    estArr: Date? = nil, actArr: Date? = nil, live: LivePosition? = nil, lastPolledAt: Date? = nil,
    updatedAt: Date? = nil, depAirport: Airport? = delAirport
) -> Flight {
    Flight(
        id: "11111111-2222-4333-8444-555555555555", carrierIata: "6E", flightNumber: "2341",
        flightDate: LocalDate(year: 2026, month: 11, day: 25), depIata: "DEL", arrIata: "BOM", status: status,
        schedDep: schedDep, estDep: estDep, actDep: actDep, schedArr: arrival, estArr: estArr, actArr: actArr,
        live: live, lastPolledAt: lastPolledAt, updatedAt: updatedAt, depAirport: depAirport, arrAirport: bomAirport
    )
}

private func liveFix(at: Date?, lat: Double = 23.0, lon: Double = 74.5, heading: Double? = 205) -> LivePosition {
    LivePosition(lat: lat, lon: lon, altFt: 36000, headingDeg: heading, speedKts: 450, at: at)
}

final class EstimateFixtureTests: XCTestCase {
    func testEveryFixtureCaseMatchesPositionProgressAndPhase() throws {
        let fixture = try fixtureObject("estimate_cases")
        let routes = try XCTUnwrap(fixture["routes"] as? [String: [String: Any]])
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        for item in cases {
            let name = try XCTUnwrap(JSON.string(item["route"]))
            let route = try XCTUnwrap(routes[name])
            let estimate = estimatePosition(
                dep: try point(route["dep"]), arr: try point(route["arr"]),
                depTime: try XCTUnwrap(parseInstant(JSON.string(route["depTime"]))),
                arrTime: try XCTUnwrap(parseInstant(JSON.string(route["arrTime"]))),
                now: try XCTUnwrap(parseInstant(JSON.string(item["now"])))
            )
            let label = "\(name) at \(JSON.string(item["now"]) ?? "?")"
            XCTAssertEqual(estimate.phase.wire, JSON.string(item["phase"]), label)
            XCTAssertEqual(estimate.progress, try XCTUnwrap(JSON.double(item["progress"])), accuracy: 1e-6, label)
            XCTAssertEqual(estimate.lat, try XCTUnwrap(JSON.double(item["lat"])), accuracy: 1e-3, label)
            XCTAssertEqual(estimate.lon, try XCTUnwrap(JSON.double(item["lon"])), accuracy: 1e-3, label)
        }
    }

    func testFixtureDistancesAndBearingsMatchTheGreatCircleHelpers() throws {
        let routes = try XCTUnwrap(try fixtureObject("estimate_cases")["routes"] as? [String: [String: Any]])
        XCTAssertEqual(routes.count, 2)
        for (name, route) in routes {
            let dep = try point(route["dep"])
            let arr = try point(route["arr"])
            XCTAssertEqual(GreatCircle.distanceKm(dep, arr), try XCTUnwrap(JSON.double(route["distanceKm"])), accuracy: 0.01, name)
            XCTAssertEqual(GreatCircle.bearing(dep, arr), try XCTUnwrap(JSON.double(route["initialBearing"])), accuracy: 0.01, name)
            XCTAssertEqual(GreatCircle.bearingAt(dep, arr, fraction: 0), try XCTUnwrap(JSON.double(route["initialBearing"])), accuracy: 0.01, name)
            XCTAssertEqual(GreatCircle.bearingAt(dep, arr, fraction: 0.5), try XCTUnwrap(JSON.double(route["midBearing"])), accuracy: 0.01, name)
            XCTAssertEqual(GreatCircle.bearingAt(dep, arr, fraction: 1), try XCTUnwrap(JSON.double(route["finalBearing"])), accuracy: 0.01, name)
        }
    }

    func testProgressIsClampedAndThePhaseFollowsTheClock() {
        let t0 = schedDep
        let t1 = t0.addingTimeInterval(2 * hour)
        let before = estimatePosition(dep: del, arr: bom, depTime: t0, arrTime: t1, now: t0.addingTimeInterval(-minute))
        XCTAssertEqual(before.phase, .preDeparture)
        XCTAssertEqual(before.progress, 0)
        XCTAssertEqual(before.point, del)
        let after = estimatePosition(dep: del, arr: bom, depTime: t0, arrTime: t1, now: t1.addingTimeInterval(5 * hour))
        XCTAssertEqual(after.phase, .arrived)
        XCTAssertEqual(after.progress, 1)
        XCTAssertEqual(after.point, bom)
    }

    func testAnArrivalNotAfterTheDepartureNeverReportsAirborne() {
        let t = schedDep
        XCTAssertEqual(estimatePosition(dep: del, arr: dxb, depTime: t, arrTime: t - hour, now: t - minute).phase, .preDeparture)
        XCTAssertEqual(estimatePosition(dep: del, arr: dxb, depTime: t, arrTime: t, now: t).phase, .arrived)
        XCTAssertEqual(estimatePosition(dep: del, arr: dxb, depTime: t, arrTime: t - hour, now: t + minute).phase, .arrived)
    }

    func testWireNamesMatchTheContract() {
        XCTAssertEqual(EstimatePhase.allCases.map(\.wire), ["pre_departure", "airborne", "arrived"])
    }

    private func point(_ value: Any?) throws -> GeoPoint {
        let object = try XCTUnwrap(JSON.object(value))
        return GeoPoint(lat: try XCTUnwrap(JSON.double(object["lat"])), lon: try XCTUnwrap(JSON.double(object["lon"])))
    }
}

final class GreatCircleTests: XCTestCase {
    func testInterpolationEndsAreTheAirportsThemselves() {
        XCTAssertEqual(GreatCircle.interpolate(del, bom, fraction: 0), del)
        XCTAssertEqual(GreatCircle.interpolate(del, bom, fraction: 1), bom)
        XCTAssertEqual(GreatCircle.interpolate(del, bom, fraction: -0.3), del)
        XCTAssertEqual(GreatCircle.interpolate(del, bom, fraction: 1.7), bom)
    }

    func testInterpolatedPointsSplitTheDistanceInProportion() {
        let total = GreatCircle.distanceKm(del, dxb)
        for f in [0.1, 0.33, 0.5, 0.9] {
            let p = GreatCircle.interpolate(del, dxb, fraction: f)
            XCTAssertEqual(GreatCircle.distanceKm(del, p), total * f, accuracy: 0.01)
            XCTAssertEqual(GreatCircle.distanceKm(p, dxb), total * (1 - f), accuracy: 0.01)
            XCTAssertEqual(GreatCircle.fractionAlong(del, dxb, p), f, accuracy: 1e-6)
        }
    }

    func testTheSameAirportTwiceIsHarmless() {
        XCTAssertEqual(GreatCircle.distanceKm(del, del), 0)
        XCTAssertEqual(GreatCircle.interpolate(del, del, fraction: 0.5), del)
        XCTAssertEqual(GreatCircle.bearingAt(del, del, fraction: 0.5), 0)
        XCTAssertEqual(GreatCircle.fractionAlong(del, del, del), 0)
    }

    func testCardinalBearings() {
        let origin = GeoPoint(lat: 0, lon: 0)
        XCTAssertEqual(GreatCircle.bearing(origin, GeoPoint(lat: 10, lon: 0)), 0, accuracy: 1e-9)
        XCTAssertEqual(GreatCircle.bearing(origin, GeoPoint(lat: 0, lon: 10)), 90, accuracy: 1e-9)
        XCTAssertEqual(GreatCircle.bearing(origin, GeoPoint(lat: -10, lon: 0)), 180, accuracy: 1e-9)
        XCTAssertEqual(GreatCircle.bearing(origin, GeoPoint(lat: 0, lon: -10)), 270, accuracy: 1e-9)
    }

    func testPathHasBothEndsAndTheRequestedSegments() {
        let path = GreatCircle.path(del, dxb, segments: 16)
        XCTAssertEqual(path.count, 17)
        XCTAssertEqual(path.first, del)
        XCTAssertEqual(path.last, dxb)
    }

    func testPathAcrossTheAntimeridianIsUnwrapped() {
        let hnd = GeoPoint(lat: 35.5494, lon: 139.7798)
        let sfo = GeoPoint(lat: 37.6190, lon: -122.3750)
        let path = GreatCircle.path(hnd, sfo, segments: 32)
        for (a, b) in zip(path, path.dropFirst()) {
            XCTAssertLessThan(abs(b.lon - a.lon), 180, "jump \(a.lon) -> \(b.lon)")
        }
        XCTAssertEqual(try XCTUnwrap(path.last).lon, -122.375 + 360, accuracy: 1e-6)
    }
}

final class FlightPositionTests: XCTestCase {
    func testEstimateTimesFollowTheContract() {
        let flight = testFlight(
            estDep: schedDep + 20 * minute, actDep: schedDep + 25 * minute,
            estArr: schedArr + 10 * minute, actArr: schedArr + 5 * minute // actual arrival is not part of arrTime
        )
        XCTAssertEqual(flight.estimateTimes, EstimateTimes(departure: schedDep + 25 * minute, arrival: schedArr + 10 * minute))
        XCTAssertEqual(testFlight().estimateTimes, EstimateTimes(departure: schedDep, arrival: schedArr))
        XCTAssertNil(testFlight(schedArr: nil).estimateTimes)
    }

    func testAFreshLiveFixWinsAndIsLabelledLive() throws {
        let now = schedDep + hour
        let flight = testFlight(status: .enRoute, actDep: schedDep, live: liveFix(at: now - 19 * minute))
        let position = try XCTUnwrap(flight.position(now: now))
        XCTAssertEqual(position.source, .live)
        XCTAssertEqual(position.point, GeoPoint(lat: 23, lon: 74.5))
        XCTAssertEqual(position.bearingDeg, 205)
        XCTAssertEqual(position.phase, .airborne)
        XCTAssertEqual(position.observedAt, now - 19 * minute)
        XCTAssertEqual(try XCTUnwrap(position.remaining), schedArr.timeIntervalSince(now), accuracy: 0.001)
        XCTAssertEqual(position.progress, GreatCircle.fractionAlong(del, bom, GeoPoint(lat: 23, lon: 74.5)), accuracy: 1e-9)
    }

    func testALiveFixOlderThan20MinutesFallsBackToTheEstimate() throws {
        let now = schedDep + hour
        let flight = testFlight(status: .enRoute, actDep: schedDep, live: liveFix(at: now - 21 * minute))
        XCTAssertNil(flight.freshLivePosition(now: now))
        let position = try XCTUnwrap(flight.position(now: now))
        XCTAssertEqual(position.source, .estimated)
        XCTAssertEqual(position.progress, 60.0 / 135.0, accuracy: 1e-9)
        XCTAssertNil(position.observedAt)
    }

    func testLiveFixesWithoutATimeAtNullIslandOrFarInTheFutureAreIgnored() {
        let now = schedDep + hour
        XCTAssertNil(testFlight(live: liveFix(at: nil)).freshLivePosition(now: now))
        XCTAssertNil(testFlight(live: liveFix(at: now, lat: 0, lon: 0)).freshLivePosition(now: now))
        XCTAssertNil(testFlight(live: liveFix(at: now + 10 * minute)).freshLivePosition(now: now))
        XCTAssertNil(testFlight(live: liveFix(at: now, lat: 95)).freshLivePosition(now: now))
        XCTAssertEqual(testFlight(live: liveFix(at: now + 2 * minute)).freshLivePosition(now: now), liveFix(at: now + 2 * minute))
    }

    func testWithoutAHeadingTheLivePlanePointsAtTheDestination() throws {
        let now = schedDep + hour
        let flight = testFlight(status: .enRoute, live: liveFix(at: now, heading: nil))
        XCTAssertEqual(try XCTUnwrap(flight.position(now: now)).bearingDeg, GreatCircle.bearing(GeoPoint(lat: 23, lon: 74.5), bom), accuracy: 1e-9)
        // A heading outside 0..<360 is normalised.
        let wrapped = testFlight(status: .enRoute, live: liveFix(at: now, heading: -90))
        XCTAssertEqual(try XCTUnwrap(wrapped.position(now: now)).bearingDeg, 270, accuracy: 1e-9)
    }

    func testLandedCancelledAndDivertedFlights() throws {
        let now = schedArr + hour
        let arrived = try XCTUnwrap(testFlight(status: .landed, actDep: schedDep, actArr: schedArr).position(now: now))
        XCTAssertEqual(arrived.phase, .arrived)
        XCTAssertEqual(arrived.point, bom)
        XCTAssertNil(arrived.remaining)

        XCTAssertNil(testFlight(status: .cancelled).position(now: schedDep + hour))
        XCTAssertNil(testFlight(status: .diverted, actDep: schedDep).position(now: schedDep + hour))
        let divertedLive = testFlight(status: .diverted, live: liveFix(at: schedDep + hour))
        XCTAssertEqual(divertedLive.position(now: schedDep + hour)?.source, .live)
    }

    func testDepartedByStatusBeforeThePlannedTimeIsAirborneAtTheStart() throws {
        let position = try XCTUnwrap(testFlight(status: .departed).position(now: schedDep - 5 * minute))
        XCTAssertEqual(position.phase, .airborne)
        XCTAssertEqual(position.progress, 0)
    }

    func testFreshDataThatSaysNotDepartedKeepsThePlaneAtTheGate() throws {
        // A late departure not yet in the times: the clock says airborne, fresh data says not departed.
        let now = schedDep + 30 * minute
        let atGate = try XCTUnwrap(testFlight(lastPolledAt: now - 5 * minute).position(now: now))
        XCTAssertEqual(atGate.phase, .preDeparture)
        XCTAssertEqual(atGate.progress, 0)
        XCTAssertEqual(atGate.point, del)
        XCTAssertEqual(testFlight(lastPolledAt: now - 5 * minute).mapModel(now: now)?.state, .notDeparted)
        // Stale data (airplane mode): the estimate is trusted instead.
        let offline = try XCTUnwrap(testFlight(lastPolledAt: now - 40 * minute).position(now: now))
        XCTAssertEqual(offline.phase, .airborne)
        XCTAssertEqual(offline.source, .estimated)
    }

    func testFreshDataThatSaysInTheAirPastTheArrivalTimeIsStillAirborne() throws {
        let now = schedArr + 10 * minute
        let late = try XCTUnwrap(testFlight(status: .enRoute, actDep: schedDep, lastPolledAt: now - 2 * minute).position(now: now))
        XCTAssertEqual(late.phase, .airborne)
        XCTAssertEqual(late.progress, 1)
        XCTAssertEqual(late.point, bom)
        XCTAssertEqual(late.remaining, 0)
        let stale = try XCTUnwrap(testFlight(status: .enRoute, actDep: schedDep, lastPolledAt: now - 2 * hour).position(now: now))
        XCTAssertEqual(stale.phase, .arrived)
    }

    func testNoAirportsNoPosition() {
        XCTAssertNil(testFlight(depAirport: nil).position(now: schedDep + hour))
    }

    func testInTheAirProgressIsEstimatedFromTheTimesWithoutALiveFix() throws {
        let flying = testFlight(status: .enRoute, actDep: schedDep, estArr: schedDep + 2 * hour)
        let fix = try XCTUnwrap(flying.progressFix(now: schedDep + 30 * minute))
        XCTAssertEqual(fix.progress, 0.25, accuracy: 0.001)
        XCTAssertEqual(fix.source, .estimated)
        XCTAssertEqual(fix.arrival, schedDep + 2 * hour)
        XCTAssertEqual(try XCTUnwrap(fix.remaining(now: schedDep + 30 * minute)), 90 * minute, accuracy: 0.001)
    }

    func testInTheAirWithAFreshFixProgressIsTheDistanceFlown() throws {
        let now = schedDep + 30 * minute
        let midway = GreatCircle.interpolate(del, bom, fraction: 0.6)
        let flying = testFlight(status: .enRoute, actDep: schedDep, live: liveFix(at: now, lat: midway.lat, lon: midway.lon))
        let fix = try XCTUnwrap(flying.progressFix(now: now))
        XCTAssertEqual(fix.progress, 0.6, accuracy: 0.001)
        XCTAssertEqual(fix.source, .live)
    }

    func testStaleDataPastTheDepartureTimeShowsTheOfflineEstimate() throws {
        let lastSeen = schedDep - 2 * hour
        let offline = testFlight(lastPolledAt: lastSeen, updatedAt: lastSeen)
        let fix = try XCTUnwrap(offline.progressFix(now: schedDep + 45 * minute))
        XCTAssertEqual(fix.progress, 45.0 / 135.0, accuracy: 0.001)
        XCTAssertEqual(fix.source, .estimated)
        XCTAssertEqual(fix.arrival, schedArr)
        XCTAssertNil(offline.progressFix(now: schedDep - minute))
        XCTAssertNil(offline.progressFix(now: schedArr))
    }

    func testFreshDataThatSaysNotDepartedShowsNoProgress() {
        let recent = schedDep + 10 * minute
        XCTAssertNil(testFlight(lastPolledAt: recent, updatedAt: recent).progressFix(now: schedDep + 20 * minute))
    }

    func testNoProgressOnceLandedOrCancelled() {
        let lastSeen = schedDep - 2 * hour
        XCTAssertNil(testFlight(status: .landed, actDep: schedDep, actArr: schedArr).progressFix(now: schedArr))
        XCTAssertNil(testFlight(status: .cancelled, lastPolledAt: lastSeen, updatedAt: lastSeen).progressFix(now: schedDep + hour))
    }

    func testTheCardCarriesTheEstimateAndItsSource() {
        let lastSeen = schedDep - 2 * hour
        let card = testFlight(lastPolledAt: lastSeen, updatedAt: lastSeen).card(now: schedDep + 45 * minute)
        XCTAssertEqual(card.progress ?? -1, 45.0 / 135.0, accuracy: 0.001)
        XCTAssertEqual(card.progressSource, .estimated)
        XCTAssertEqual(card.progressArrival, schedArr)
        let fresh = testFlight(lastPolledAt: schedDep, updatedAt: schedDep).card(now: schedDep + 5 * minute)
        XCTAssertNil(fresh.progress)
        XCTAssertNil(fresh.progressSource)
    }
}

final class FlightMapModelTests: XCTestCase {
    func testAirborneSplitsTheRouteUnderThePlane() throws {
        let now = schedDep + hour
        let model = try XCTUnwrap(testFlight(status: .enRoute, actDep: schedDep).mapModel(now: now, segments: 16))
        XCTAssertEqual(model.state, .airborne)
        XCTAssertEqual(model.source, .estimated)
        let plane = try XCTUnwrap(model.plane)
        XCTAssertEqual(model.flownEnds, [del, plane.point])
        XCTAssertEqual(model.remainingEnds, [plane.point, bom])
        XCTAssertEqual(model.flownPath.first, del)
        XCTAssertEqual(model.flownPath.last, plane.point)
        XCTAssertEqual(model.remainingPath.first, plane.point)
        XCTAssertEqual(model.remainingPath.last, bom)
        XCTAssertEqual(try XCTUnwrap(model.progress), 60.0 / 135.0, accuracy: 1e-9)
        XCTAssertEqual(model.cameraPoints.count, 3)
    }

    func testBeforeTakeOffEverythingIsStillToFly() throws {
        let model = try XCTUnwrap(testFlight().mapModel(now: schedDep - hour))
        XCTAssertEqual(model.state, .notDeparted)
        XCTAssertNil(model.plane)
        XCTAssertNil(model.source)
        XCTAssertTrue(model.flownEnds.isEmpty)
        XCTAssertEqual(model.remainingEnds, [del, bom])
    }

    func testArrivedAndCancelled() throws {
        let arrived = try XCTUnwrap(testFlight(status: .landed, actDep: schedDep, actArr: schedArr).mapModel(now: schedArr + hour))
        XCTAssertEqual(arrived.state, .arrived)
        XCTAssertEqual(arrived.flownEnds, [del, bom])
        XCTAssertTrue(arrived.remainingEnds.isEmpty)
        let cancelled = try XCTUnwrap(testFlight(status: .cancelled).mapModel(now: schedDep))
        XCTAssertEqual(cancelled.state, .cancelled)
        XCTAssertNil(cancelled.plane)
        XCTAssertNil(testFlight(depAirport: nil).mapModel(now: schedDep))
    }

    func testSplitRouteStaysContinuousAcrossTheAntimeridian() {
        let hnd = GeoPoint(lat: 35.5494, lon: 139.7798)
        let sfo = GeoPoint(lat: 37.6190, lon: -122.3750)
        let at = GreatCircle.interpolate(hnd, sfo, fraction: 0.5)
        let split = splitRoute(dep: hnd, arr: sfo, fraction: 0.5, at: at, segments: 8)
        let joined = split.flown + split.remaining.dropFirst()
        for (a, b) in zip(joined, joined.dropFirst()) { XCTAssertLessThan(abs(b.lon - a.lon), 180) }
        XCTAssertEqual(split.flown.last, split.remaining.first)
    }

    func testBounds() throws {
        let bounds = try XCTUnwrap(mapBounds([del, bom, dxb]))
        XCTAssertEqual(bounds.south, 19.0887)
        XCTAssertEqual(bounds.north, 28.5665)
        XCTAssertEqual(bounds.west, 55.3644)
        XCTAssertEqual(bounds.east, 77.1031)
        let padded = bounds.padded(0.1)
        XCTAssertLessThan(padded.south, bounds.south)
        XCTAssertGreaterThan(padded.east, bounds.east)
        XCTAssertNil(mapBounds([]))
    }
}

final class AirportHealthTests: XCTestCase {
    func testProAnswerCarriesTheDetail() throws {
        let health = try XCTUnwrap(parseAirportHealth(try fixtureData("airport_health_pro"), requestedIata: "XXX"))
        XCTAssertEqual(health.iata, "DEL")
        XCTAssertEqual(health.level, .severe)
        XCTAssertEqual(health.level.tone, .cancelled)
        XCTAssertEqual(health.updatedAt, parseInstant("2026-11-22T09:30:00Z"))
        let detail = try XCTUnwrap(health.detail)
        XCTAssertEqual(detail.avgDepDelayMin, 34)
        XCTAssertEqual(detail.delayedPct, 41)
        XCTAssertEqual(detail.cancelledToday, 3)
        XCTAssertEqual(detail.sampleSize, 58)
        XCTAssertTrue(detail.hasDelaySample)
        let weather = try XCTUnwrap(detail.weather)
        XCTAssertEqual(weather.visibilityM, 300)
        XCTAssertEqual(weather.category, .lifr)
        XCTAssertTrue(weather.fog)
        XCTAssertEqual(weather.windKts, 4)
        XCTAssertEqual(weather.wx, "FG")
        XCTAssertEqual(health.typedHeadline, .fog(visibilityM: 300))
    }

    func testFreeAnswerHasNoDetail() throws {
        let health = try XCTUnwrap(parseAirportHealth(try fixtureData("airport_health_free"), requestedIata: "BOM"))
        XCTAssertEqual(health.level, .busy)
        XCTAssertNil(health.detail)
        XCTAssertEqual(health.typedHeadline, .runningLate(27))
        XCTAssertNotNil(health.updatedAt)
    }

    func testUnknownLevelOrShapeIsLeftOut() {
        XCTAssertNil(parseAirportHealth(Data(#"{"iata":"DEL","level":"apocalyptic","headline":"?"}"#.utf8), requestedIata: "DEL"))
        XCTAssertNil(parseAirportHealth(Data("[]".utf8), requestedIata: "DEL"))
        let bare = parseAirportHealth(Data(#"{"level":"normal"}"#.utf8), requestedIata: "BLR")
        XCTAssertEqual(bare?.iata, "BLR")
        XCTAssertEqual(bare?.typedHeadline, .normal)
    }

    func testEveryServerHeadlineIsTyped() {
        func typed(_ text: String, detail: HealthDetail? = nil) -> HealthHeadline {
            AirportHealth(iata: "DEL", level: .disrupted, headline: text, updatedAt: nil, detail: detail).typedHeadline
        }
        XCTAssertEqual(typed("Operating normally"), .normal)
        XCTAssertEqual(typed("Fog"), .fog(visibilityM: nil))
        XCTAssertEqual(typed("Thunderstorms at the airport"), .thunderstorms)
        XCTAssertEqual(typed("Low visibility: 1200 m"), .lowVisibility(1200))
        XCTAssertEqual(typed("Low cloud: 600 ft"), .lowCloud(600))
        XCTAssertEqual(typed("Poor flying weather"), .poorWeather)
        XCTAssertEqual(typed("12 flights cancelled today"), .cancellations(12))
        XCTAssertEqual(typed("Departures running 45 min late"), .runningLate(45))
        XCTAssertEqual(typed("33% of departures delayed"), .delayedShare(33))
        XCTAssertEqual(typed("Something new"), .other("Something new"))
        let storm = HealthDetail(avgDepDelayMin: nil, delayedPct: nil, cancelledToday: 0, sampleSize: 0,
                                 weather: AirportWeather(visibilityM: 4000, category: .ifr, fog: false, windKts: 20, wx: "+TSRA", observedAt: nil))
        XCTAssertEqual(typed("Something new", detail: storm), .thunderstorms)
        XCTAssertFalse(storm.hasDelaySample)
    }

    func testPresentWeatherWords() {
        XCTAssertEqual(presentWeather("FG"), .fog)
        XCTAssertEqual(presentWeather("VCFG"), .fog)
        XCTAssertEqual(presentWeather("-RA BR"), .mist)
        XCTAssertEqual(presentWeather("TSRA"), .thunderstorm)
        XCTAssertEqual(presentWeather("HZ"), .haze)
        XCTAssertEqual(presentWeather("FU"), .smoke)
        XCTAssertEqual(presentWeather("BLDU"), .dust)
        XCTAssertEqual(presentWeather("-DZ"), .rain)
        XCTAssertNil(presentWeather(""))
        XCTAssertNil(presentWeather(nil))
        XCTAssertNil(presentWeather("SKC"))
    }

    func testWhichAirportsMatter() {
        XCTAssertEqual(healthSides(.upcoming), [.departure, .arrival])
        XCTAssertEqual(healthSides(.boarding), [.departure, .arrival])
        XCTAssertEqual(healthSides(.inAir), [.arrival])
        XCTAssertEqual(healthSides(.landed), [])
        XCTAssertEqual(healthSides(.cancelled), [])
    }

    func testLevelsAreOrdered() {
        XCTAssertLessThan(HealthLevel.normal, HealthLevel.busy)
        XCTAssertLessThan(HealthLevel.disrupted, HealthLevel.severe)
    }

    @MainActor
    func testLoadingAsksEachAirportOnceAndSkipsUnavailable() async throws {
        let source = FakeHealthSource()
        source.answers["DEL"] = .health(try XCTUnwrap(parseAirportHealth(try fixtureData("airport_health_pro"), requestedIata: "DEL")))
        source.answers["BOM"] = .offline
        let rows = await loadAirportHealth(for: testFlight(), source: source)
        XCTAssertEqual(rows.map(\.iata), ["DEL"])
        XCTAssertEqual(rows.first?.side, .departure)
        XCTAssertEqual(rows.first?.city, "Delhi")
        XCTAssertEqual(source.asked, ["DEL", "BOM"])
        let inAir = await loadAirportHealth(for: testFlight(status: .enRoute, actDep: schedDep), source: source)
        XCTAssertTrue(inAir.isEmpty)
        XCTAssertEqual(source.asked.last, "BOM")
    }
}

@MainActor
final class FakeHealthSource: AirportHealthSource {
    var answers: [String: AirportHealthResult] = [:]
    var asked: [String] = []
    func health(iata: String) async -> AirportHealthResult {
        asked.append(iata)
        return answers[iata] ?? .unavailable
    }
}

final class PassportTests: XCTestCase {
    private let today = LocalDate(year: 2026, month: 11, day: 22)

    private func pro() throws -> Passport {
        try XCTUnwrap(parsePassport(try fixtureData("passport_pro_all"), fallbackYear: 2026))
    }

    func testReadsEveryFieldOfTheContractExample() throws {
        let p = try pro()
        XCTAssertEqual(p.scope, .all)
        XCTAssertEqual(p.year, 2026)
        XCTAssertFalse(p.proLimited)
        XCTAssertEqual(p.flights, 14)
        XCTAssertEqual(p.distanceKm, 18234)
        XCTAssertEqual(p.airborneMinutes, 1810)
        XCTAssertEqual(p.countries, ["AE", "IN", "US"])
        XCTAssertEqual(p.airlines.first, CountedAirline(carrier: "6E", name: "IndiGo", count: 8))
        XCTAssertEqual(p.airlines[1], CountedAirline(carrier: "AI", name: nil, count: 4))
        XCTAssertEqual(p.aircraft.first, CountedAircraft(model: "A320neo", count: 7))
        XCTAssertEqual(p.longest, LongestFlight(flightId: "c0ffee00-0000-4000-8000-000000000001", carrier: "AI", number: "101", dep: "DEL", arr: "JFK", distanceKm: 11752))
        XCTAssertEqual(p.topRoute, TopRoute(dep: "DEL", arr: "BOM", count: 5))
        XCTAssertEqual(p.delay, DelayStats(totalMin: 412, onTimePct: 64, worst: WorstDelay(carrier: "SG", number: "8", depDelayMin: 185)))
        XCTAssertEqual(p.co2Kg, 1730)
        XCTAssertEqual(p.co2Method, co2MethodV1)
        XCTAssertEqual(p.firstFlightDate, LocalDate(year: 2025, month: 12, day: 3))
        XCTAssertEqual(p.airports.map(\.iata), ["DEL", "BOM", "JFK"]) // the entry without a code is dropped
    }

    func testAnEmptyFreeAnswerHasNoHighlightsAndAsksForPro() throws {
        let empty = try XCTUnwrap(parsePassport(try fixtureData("passport_free_empty"), fallbackYear: 2026))
        XCTAssertTrue(empty.isEmpty)
        XCTAssertTrue(empty.proLimited)
        XCTAssertEqual(empty.scope, .last12)
        XCTAssertNil(empty.longest)
        XCTAssertNil(empty.topRoute)
        XCTAssertNil(empty.delay.onTimePct)
        XCTAssertNil(empty.firstFlightDate)
        XCTAssertNil(empty.yearInReviewCard(today: today))
        XCTAssertNil(empty.earthLaps)
    }

    func testMissingFieldsFallBackToSafeDefaults() throws {
        let minimal = try XCTUnwrap(parsePassport(Data(#"{"flights": 2}"#.utf8), fallbackYear: 2025))
        XCTAssertEqual(minimal.flights, 2)
        XCTAssertEqual(minimal.year, 2025)
        XCTAssertEqual(minimal.scope, .last12)
        XCTAssertEqual(minimal.co2Method, co2MethodV1)
        XCTAssertTrue(minimal.airports.isEmpty)
        XCTAssertNil(parsePassport(Data("null".utf8), fallbackYear: 2025))
    }

    func testOverviewBarsAreTheTwelveMonthsEndingThisMonth() throws {
        let bars = try pro().monthBars(today: today)
        XCTAssertEqual(bars.count, 12)
        XCTAssertEqual(bars.first?.month, YearMonth(year: 2025, month: 12))
        XCTAssertEqual(bars.last?.month, YearMonth(year: 2026, month: 11))
        XCTAssertEqual(bars.first?.flights, 1)
        XCTAssertEqual(bars.last?.flights, 7)
        XCTAssertEqual(bars.last?.fraction, 1)
        XCTAssertEqual(bars.first { $0.month == YearMonth(year: 2026, month: 3) }?.flights, 4)
        XCTAssertEqual(bars.first { $0.month == YearMonth(year: 2026, month: 2) }?.fraction, 0)
    }

    func testYearBarsRunJanuaryToDecember() throws {
        var year = try pro()
        year.scope = .year
        let bars = year.monthBars(today: today)
        XCTAssertEqual(bars.map(\.month), (1...12).map { YearMonth(year: 2026, month: $0) })
        XCTAssertEqual(bars.map(\.flights).reduce(0, +), 13, "December 2025 is not part of 2026")
    }

    func testLittleNumbers() throws {
        let p = try pro()
        XCTAssertEqual(p.hoursInAir, 30)
        XCTAssertEqual(try XCTUnwrap(p.earthLaps), 0.5, accuracy: 0.0001)
        XCTAssertEqual(p.statTiles.map(\.value), [14, 18234, 30, 3, 3, 3])
        XCTAssertEqual(p.statTiles.map(\.kind), StatKind.allCases)
    }

    func testYearInReviewCardIsBuiltOnlyForANonEmptyYear() throws {
        var p = try pro()
        XCTAssertNil(p.yearInReviewCard(today: today))
        p.scope = .year
        let card = try XCTUnwrap(p.yearInReviewCard(today: today))
        XCTAssertEqual(card.year, 2026)
        XCTAssertEqual(card.flights, 14)
        XCTAssertEqual(card.hours, 30)
        XCTAssertEqual(card.topAirline, "IndiGo")
        XCTAssertEqual(card.topRoute, TopRoute(dep: "DEL", arr: "BOM", count: 5))
        XCTAssertEqual(card.bars.count, 12)
        p.topRoute = TopRoute(dep: "DEL", arr: "BOM", count: 1)
        p.airlines = [CountedAirline(carrier: "QP", name: nil, count: 1)]
        let once = try XCTUnwrap(p.yearInReviewCard(today: today))
        XCTAssertNil(once.topRoute)
        XCTAssertEqual(once.topAirline, "QP")
    }

    func testTabsAndIndianCalendar() {
        XCTAssertNil(PassportTab.overview.yearArgument(currentYear: 2026))
        XCTAssertEqual(PassportTab.year.yearArgument(currentYear: 2026), 2026)
        XCTAssertEqual(passportToday(parseInstant("2026-12-31T19:00:00Z")!), LocalDate(year: 2027, month: 1, day: 1))
    }

    func testYearMonthHelpers() {
        XCTAssertNil(YearMonth.parse("2026"))
        XCTAssertNil(YearMonth.parse("2026-13"))
        XCTAssertEqual(YearMonth.parse("2026-03"), YearMonth(year: 2026, month: 3))
        XCTAssertEqual(YearMonth(year: 2026, month: 1).adding(months: -1), YearMonth(year: 2025, month: 12))
        XCTAssertEqual(YearMonth(year: 2025, month: 12).adding(months: 13), YearMonth(year: 2027, month: 1))
    }

    @MainActor
    func testModelLoadsTabsAndKeepsAnswers() async throws {
        let source = FakePassportSource()
        source.result = .passport(try pro())
        let model = PassportModel(source: source, clock: ManualClock(parseInstant("2026-11-22T10:00:00Z")!))
        await model.load()
        XCTAssertEqual(source.years, [nil])
        XCTAssertEqual(model.state.passport?.flights, 14)
        XCTAssertFalse(model.state.isLoading)
        source.result = .offline
        await model.select(.year)
        XCTAssertEqual(source.years, [nil, 2026])
        XCTAssertEqual(model.state.problem, .offline)
        XCTAssertNil(model.state.passport)
        await model.select(.overview)
        XCTAssertEqual(source.years.count, 2, "the overview answer is kept")
        XCTAssertEqual(model.state.passport?.flights, 14)
        XCTAssertNil(model.state.problem)
        await model.refresh()
        XCTAssertEqual(source.years, [nil, 2026, nil])
        XCTAssertEqual(model.state.problem, .offline)
        XCTAssertEqual(model.state.passport?.flights, 14, "a failed refresh keeps the last answer")
    }
}

@MainActor
final class FakePassportSource: PassportSource {
    var result: PassportResult = .failed
    var years: [Int?] = []
    func passport(year: Int?) async -> PassportResult {
        years.append(year)
        return result
    }
}

final class AlertExtrasTests: XCTestCase {
    private let flightId = "11111111-2222-4333-8444-555555555555"

    func testNewAlertTypesAreCriticalFreeAndGrouped() {
        XCTAssertEqual(AlertType.fromWire("FOG_ALERT"), .fogAlert)
        XCTAssertEqual(AlertType.fromWire("DIVERTED_DEST"), .divertedDest)
        XCTAssertTrue(AlertType.fogAlert.critical)
        XCTAssertTrue(AlertType.divertedDest.critical)
        XCTAssertFalse(AlertType.fogAlert.proOnly)
        XCTAssertEqual(AlertGroup.weather.types, [.fogAlert])
        XCTAssertFalse(AlertGroup.weather.proOnly)
        XCTAssertTrue(AlertGroup.disruption.types.contains(.divertedDest))
        // Every alert type belongs to exactly one group, so every type can be muted.
        let grouped = AlertGroup.allCases.flatMap(\.types)
        XCTAssertEqual(Set(grouped), Set(AlertType.allCases))
        XCTAssertEqual(grouped.count, AlertType.allCases.count)
    }

    func testDivertedDestReplacesTheDivertedNotification() {
        XCTAssertEqual(alertTagGroup("DIVERTED_DEST"), "diverted")
        XCTAssertEqual(alertTagGroup("DIVERTED"), "diverted")
        XCTAssertEqual(alertTagGroup("FOG_ALERT"), "fog_alert")
        let parsed = parseIncomingPush(["kind": "alert", "flight_id": flightId, "event_type": "FOG_ALERT", "title": "Fog at Delhi", "body": "Visibility 300 m"])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertEqual(alert.channelId, PushContract.channelCritical)
        XCTAssertEqual(alert.interruptionLevel, "time-sensitive")
    }

    func testQuietInfoAlertIsPassive() {
        let parsed = parseIncomingPush([
            "kind": "alert", "flight_id": flightId, "event_id": "77", "event_type": "LANDED",
            "title": "Landed", "body": "6E 2341 landed", "quiet": "1",
        ])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertTrue(alert.quiet)
        XCTAssertEqual(alert.channelId, PushContract.channelQuiet)
        XCTAssertEqual(alert.interruptionLevel, "passive")
    }

    func testCriticalAlertsStayLoudEvenWhenFlaggedQuiet() {
        let parsed = parseIncomingPush([
            "kind": "alert", "flight_id": flightId, "event_type": "GATE_CHANGE", "title": "Gate change", "body": "Gate 29", "quiet": "1",
        ])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertFalse(alert.quiet)
        XCTAssertEqual(alert.interruptionLevel, "time-sensitive")
    }

    func testApnsPassiveLevelMeansQuiet() throws {
        let message = """
        {"data": {"kind": "alert", "flight_id": "\(flightId)", "event_type": "DEPARTED", "title": "Departed", "body": "6E 2341"},
         "apns": {"headers": {"apns-collapse-id": "\(flightId):departed"},
                  "payload": {"aps": {"interruption-level": "passive", "thread-id": "x"}}}}
        """
        let parsed = parseIncomingPush(messageJSON: Data(message.utf8))
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertTrue(alert.quiet)
        XCTAssertEqual(alert.interruptionLevel, "passive")
        XCTAssertEqual(interruptionLevel(for: PushContract.channelQuiet), "passive")
        XCTAssertEqual(alertChannelId(.departed, quiet: false), PushContract.channelUpdates)
        XCTAssertEqual(alertChannelId(nil, quiet: true), PushContract.channelQuiet)
    }

    func testUnflaggedInfoAlertIsActive() {
        let parsed = parseIncomingPush(["kind": "alert", "flight_id": flightId, "event_type": "LANDED", "title": "Landed", "body": "x"])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertFalse(alert.quiet)
        XCTAssertEqual(alert.interruptionLevel, "active")
    }
}

final class PushAckTests: XCTestCase {
    func testEventIds() {
        XCTAssertEqual(parsePushEventId("123"), 123)
        XCTAssertEqual(parsePushEventId(" 42 "), 42)
        XCTAssertNil(parsePushEventId("0"))
        XCTAssertNil(parsePushEventId("-5"))
        XCTAssertNil(parsePushEventId("12a"))
        XCTAssertNil(parsePushEventId("1234567890123456"))
        XCTAssertNil(parsePushEventId("١٢٣")) // Arabic-Indic digits are not an id
        XCTAssertNil(parsePushEventId(nil))
        XCTAssertEqual(parsePushEventId("999999999999999"), 999_999_999_999_999)
    }

    func testRequestBodyMatchesTheContract() throws {
        let request = PushAckRequest(eventId: 123, receivedAt: parseInstant("2026-11-26T05:30:00.250Z")!)
        let object = try XCTUnwrap(JSON.object(from: try JSONEncoder().encode(request)))
        XCTAssertEqual(JSON.int(object["event_id"]), 123)
        XCTAssertEqual(JSON.string(object["received_at"]), "2026-11-26T05:30:00.250Z")
        XCTAssertEqual(object.count, 2)
    }

    func testEachEventIsAcknowledgedOnceWithItsEarliestTime() {
        let acked = AckedEvents(capacity: 2)
        let t0 = parseInstant("2026-11-26T05:30:00Z")!
        let first = pushAcks(for: [("5", t0 + 10), ("5", t0), ("bad", t0), (nil, t0), ("6", t0 + 1)], acked: acked)
        XCTAssertEqual(first, [PushAckRequest(eventId: 5, receivedAt: t0), PushAckRequest(eventId: 6, receivedAt: t0 + 1)])
        XCTAssertEqual(pushAcks(for: [("5", t0 + 99)], acked: acked), [], "opened after it arrived: already acknowledged")
        XCTAssertTrue(acked.firstTime(7))
        XCTAssertTrue(acked.firstTime(5), "the oldest id was forgotten (capacity 2)")
    }
}

final class AlertPrefsTests: XCTestCase {
    func testLanguagesAndDefaults() {
        XCTAssertEqual(AlertLanguage.allCases.map(\.wire), ["en", "hi", "hinglish"])
        XCTAssertEqual(AlertLanguage.fromWire("tamil"), .en)
        XCTAssertEqual(AlertLanguage.fromWire(nil), .en)
        XCTAssertEqual(defaultAlertLanguage(deviceLanguage: "hi-IN"), .hi)
        XCTAssertEqual(defaultAlertLanguage(deviceLanguage: "hi_IN"), .hi)
        XCTAssertEqual(defaultAlertLanguage(deviceLanguage: "HI"), .hi)
        XCTAssertEqual(defaultAlertLanguage(deviceLanguage: "en-IN"), .en)
        XCTAssertEqual(defaultAlertLanguage(deviceLanguage: nil), .en)
    }

    func testPostgresTimes() {
        XCTAssertEqual(parsePgTime("23:00:00"), ClockTime(hour: 23, minute: 0))
        XCTAssertEqual(parsePgTime("07:30"), ClockTime(hour: 7, minute: 30))
        XCTAssertEqual(parsePgTime("6:15:00.5"), ClockTime(hour: 6, minute: 15))
        XCTAssertNil(parsePgTime("24:00"))
        XCTAssertNil(parsePgTime("7pm"))
        XCTAssertNil(parsePgTime(nil))
        XCTAssertEqual(ClockTime(hour: 7, minute: 5).pg, "07:05")
    }

    func testProfileRowAndDefaults() {
        let prefs = AlertPrefs(profileRow: [
            "quiet_hours_enabled": true, "quiet_start": "22:30:00", "quiet_end": "06:00:00",
            "quiet_tz": "Asia/Dubai", "alert_language": "hinglish",
        ])
        XCTAssertEqual(prefs, AlertPrefs(quietHoursEnabled: true, quietStart: ClockTime(hour: 22, minute: 30),
                                         quietEnd: ClockTime(hour: 6, minute: 0), quietTz: "Asia/Dubai", language: .hinglish))
        XCTAssertEqual(AlertPrefs(profileRow: [:]), AlertPrefs())
    }

    func testPatchWritesTheZoneWithEveryQuietHoursChange() {
        XCTAssertEqual(alertPrefsPatch(.quietHoursEnabled(true), deviceTz: "Asia/Kolkata"),
                       ["quiet_hours_enabled": .bool(true), "quiet_tz": .text("Asia/Kolkata")])
        XCTAssertEqual(alertPrefsPatch(.quietStart(ClockTime(hour: 22, minute: 0)), deviceTz: "Europe/London"),
                       ["quiet_start": .text("22:00"), "quiet_tz": .text("Europe/London")])
        XCTAssertEqual(alertPrefsPatch(.quietEnd(ClockTime(hour: 6, minute: 45)), deviceTz: "Asia/Kolkata"),
                       ["quiet_end": .text("06:45"), "quiet_tz": .text("Asia/Kolkata")])
        XCTAssertEqual(alertPrefsPatch(.language(.hi), deviceTz: "Asia/Kolkata"), ["alert_language": .text("hi")])
    }

    func testQuietWindowWrapsMidnight() {
        let prefs = AlertPrefs(quietHoursEnabled: true) // 23:00-07:00 India
        XCTAssertTrue(prefs.isQuiet(at: parseInstant("2026-11-26T18:00:00Z")!)) // 23:30 IST
        XCTAssertTrue(prefs.isQuiet(at: parseInstant("2026-11-26T01:00:00Z")!)) // 06:30 IST
        XCTAssertFalse(prefs.isQuiet(at: parseInstant("2026-11-26T01:30:00Z")!)) // 07:00 IST, end is exclusive
        XCTAssertFalse(prefs.isQuiet(at: parseInstant("2026-11-26T10:00:00Z")!))
        var off = prefs
        off.quietHoursEnabled = false
        XCTAssertFalse(off.isQuiet(at: parseInstant("2026-11-26T18:00:00Z")!))
        let day = AlertPrefs(quietHoursEnabled: true, quietStart: ClockTime(hour: 13, minute: 0), quietEnd: ClockTime(hour: 15, minute: 0))
        XCTAssertTrue(day.isQuiet(at: parseInstant("2026-11-26T08:00:00Z")!)) // 13:30 IST
        XCTAssertFalse(day.isQuiet(at: parseInstant("2026-11-26T18:00:00Z")!))
    }

    @MainActor
    func testModelIsOptimisticAndRollsBack() async {
        let repo = FakeAlertPrefs()
        repo.stored = AlertPrefs()
        let model = AlertPrefsModel(repository: repo, deviceTz: { "Asia/Dubai" })
        await model.load()
        XCTAssertTrue(model.state.available)
        await model.apply(.quietHoursEnabled(true))
        XCTAssertTrue(model.state.prefs.quietHoursEnabled)
        XCTAssertEqual(model.state.prefs.quietTz, "Asia/Dubai")
        XCTAssertEqual(repo.saved, [.quietHoursEnabled(true)])
        repo.saveResult = .failure(.offline)
        await model.apply(.language(.hinglish))
        XCTAssertEqual(model.state.prefs.language, .en, "rolled back")
        XCTAssertEqual(model.state.message, .offline)
        await model.apply(.quietHoursEnabled(true))
        XCTAssertEqual(repo.saved.count, 2, "no change, no request")
    }

    @MainActor
    func testOverlappingSavesRollBackOnlyTheRefusedChange() async {
        let repo = GatedAlertPrefs()
        let model = AlertPrefsModel(repository: repo, deviceTz: { "Asia/Dubai" })
        await model.load()
        let first = Task { await model.apply(.quietHoursEnabled(true)) }
        await repo.waitFor(1)
        let second = Task { await model.apply(.language(.hi)) }
        await repo.waitFor(2)
        XCTAssertTrue(model.state.isSaving)
        XCTAssertTrue(model.state.prefs.quietHoursEnabled)
        XCTAssertEqual(model.state.prefs.language, .hi)

        repo.results[.quietHoursEnabled(true)] = .failure(.failed)
        repo.release(.quietHoursEnabled(true))
        await first.value
        XCTAssertFalse(model.state.prefs.quietHoursEnabled, "the refused change is rolled back")
        XCTAssertEqual(model.state.prefs.quietTz, "Asia/Kolkata", "with the zone it wrote")
        XCTAssertEqual(model.state.prefs.language, .hi, "the change still on its way stays")
        XCTAssertTrue(model.state.isSaving, "one save still running")
        XCTAssertEqual(model.state.message, .saveFailed)

        repo.release(.language(.hi))
        await second.value
        XCTAssertEqual(model.state.prefs.language, .hi)
        XCTAssertFalse(model.state.prefs.quietHoursEnabled)
        XCTAssertFalse(model.state.isSaving)
    }

    @MainActor
    func testWithoutAProfileTheSectionIsHidden() async {
        let repo = FakeAlertPrefs()
        let model = AlertPrefsModel(repository: repo, deviceTz: { "Asia/Kolkata" })
        await model.load()
        XCTAssertFalse(model.state.available)
        await model.apply(.language(.hi))
        XCTAssertTrue(repo.saved.isEmpty)
    }
}

@MainActor
final class FakeAlertPrefs: AlertPrefsRepository {
    var stored: AlertPrefs?
    var saveResult: Result<Void, AlertPrefsError> = .success(())
    var saved: [AlertPrefsChange] = []
    func load() async -> Result<AlertPrefs?, AlertPrefsError> { .success(stored) }
    func save(_ change: AlertPrefsChange, deviceTz: String) async -> Result<Void, AlertPrefsError> {
        saved.append(change)
        return saveResult
    }
}

/// Holds every save until the test releases it, so two saves can overlap.
@MainActor
final class GatedAlertPrefs: AlertPrefsRepository {
    var results: [AlertPrefsChange: Result<Void, AlertPrefsError>] = [:]
    private var waiting: [(change: AlertPrefsChange, resume: CheckedContinuation<Void, Never>)] = []

    func load() async -> Result<AlertPrefs?, AlertPrefsError> { .success(AlertPrefs()) }

    func save(_ change: AlertPrefsChange, deviceTz: String) async -> Result<Void, AlertPrefsError> {
        await withCheckedContinuation { continuation in waiting.append((change: change, resume: continuation)) }
        return results[change] ?? .success(())
    }

    func waitFor(_ count: Int) async {
        var tries = 0
        while waiting.count < count && tries < 1000 {
            tries += 1
            await Task.yield()
        }
    }

    func release(_ change: AlertPrefsChange) {
        guard let index = waiting.firstIndex(where: { $0.change == change }) else { return }
        waiting.remove(at: index).resume.resume()
    }
}

@MainActor
final class FlightDetailP2Tests: XCTestCase {
    func testDetailShowsTheMapAndAirportHealthAndRefreshesHealthOnlyWhenOld() async throws {
        let flight = testFlight()
        let repo = RecordingFlights()
        repo.tracked = [TrackedFlightDetails(tracking: TrackedFlight(id: "t1", userId: "user-1", flightId: flight.id), flight: flight)]
        let source = FakeHealthSource()
        source.answers["DEL"] = .health(try XCTUnwrap(parseAirportHealth(try fixtureData("airport_health_pro"), requestedIata: "DEL")))
        source.answers["BOM"] = .health(try XCTUnwrap(parseAirportHealth(try fixtureData("airport_health_free"), requestedIata: "BOM")))
        let clock = ManualClock(schedDep - 3 * hour)
        let model = FlightDetailScreenModel(flightId: flight.id, flights: repo, profiles: FakeProfiles(), clock: clock, airportHealth: source)
        await model.load()
        XCTAssertEqual(model.state.map?.state, .notDeparted)
        XCTAssertEqual(model.state.map?.remainingEnds, [del, bom])
        XCTAssertEqual(model.state.airportHealth.map(\.iata), ["DEL", "BOM"])
        XCTAssertEqual(model.state.airportHealth.map(\.side), [.departure, .arrival])
        XCTAssertEqual(source.asked.count, 2)

        clock.set(schedDep - 3 * hour + 5 * minute)
        await model.reload()
        XCTAssertEqual(source.asked.count, 2, "fresh enough: no new request")
        clock.set(schedDep - 3 * hour + 16 * minute)
        source.answers = [:]
        await model.reload()
        XCTAssertEqual(source.asked.count, 4)
        XCTAssertEqual(model.state.airportHealth.count, 2, "a failed refresh keeps the rows")
    }
}

final class AlertPrefsJSONTests: XCTestCase {
    func testProfilesSelectAnswer() {
        let data = Data(#"[{"quiet_hours_enabled":true,"quiet_start":"23:00:00","quiet_end":"07:00:00","quiet_tz":"Asia/Kolkata","alert_language":"hi"}]"#.utf8)
        XCTAssertEqual(alertPrefs(fromProfilesJSON: data), AlertPrefs(quietHoursEnabled: true, language: .hi))
        XCTAssertNil(alertPrefs(fromProfilesJSON: Data("[]".utf8)))
        XCTAssertNil(alertPrefs(fromProfilesJSON: Data("{}".utf8)))
    }
}

final class PassportShareCardTests: XCTestCase {
    func testEveryNonEmptyPassportHasAShareCardNamedByItsScope() throws {
        let pro = try XCTUnwrap(parsePassport(try fixtureData("passport_pro_all"), fallbackYear: 2026))
        let today = LocalDate(year: 2026, month: 11, day: 22)
        let card = try XCTUnwrap(pro.shareCard(today: today))
        XCTAssertEqual(card.scope, .all)
        XCTAssertEqual(card.distanceKm, 18234)
        XCTAssertNil(pro.yearInReviewCard(today: today), "Year in Review is the year scope only")
        let empty = try XCTUnwrap(parsePassport(try fixtureData("passport_free_empty"), fallbackYear: 2026))
        XCTAssertNil(empty.shareCard(today: today))
    }
}
