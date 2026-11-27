import Foundation
import XCTest
@testable import FlyTrackCore

// P3 contract (docs/P3_CONTRACTS.md): new alert types (section C), full timeline (A2), aircraft (A5), delay and gate
// prediction (B1, B2), taxi times (B3), connections (B4), airport trends (B7). Answers follow migrations 22–25 and 27.

private func at(_ text: String) -> Date { parseInstant(text)! }
private func data(_ text: String) -> Data { Data(text.utf8) }

private let p3FlightId = "22222222-3333-4444-8555-666666666666"
private let delP3 = Airport(iata: "DEL", name: "Indira Gandhi International", city: "Delhi", tz: "Asia/Kolkata", lat: 28.5665, lon: 77.1031)
private let dxbP3 = Airport(iata: "DXB", name: "Dubai International", city: "Dubai", tz: "Asia/Dubai", lat: 25.2528, lon: 55.3644)

/// 6E 1401 DEL-DXB, scheduled 2026-11-26 10:00-13:30 UTC (15:30 IST, 17:30 GST).
private func p3Flight(
    status: FlightStatus = .scheduled, estDep: Date? = nil, actDep: Date? = nil, actArr: Date? = nil,
    depGate: String? = nil, belt: String? = nil, offblock: Date? = nil, takeoff: Date? = nil, touchdown: Date? = nil,
    onblock: Date? = nil, model: String? = "Airbus A321neo", icao: String? = "A21N", reg: String? = "VT-IUA", age: Double? = 3.46
) -> Flight {
    Flight(
        id: p3FlightId, carrierIata: "6E", flightNumber: "1401", flightDate: LocalDate(year: 2026, month: 11, day: 26),
        depIata: "DEL", arrIata: "DXB", status: status, schedDep: at("2026-11-26T10:00:00Z"), estDep: estDep, actDep: actDep,
        depTerminal: "3", depGate: depGate, schedArr: at("2026-11-26T13:30:00Z"), actArr: actArr, baggageBelt: belt,
        aircraftModel: model, aircraftReg: reg, depAirport: delP3, arrAirport: dxbP3,
        depOffblockAt: offblock, depRunwayAt: takeoff, arrRunwayAt: touchdown, arrOnblockAt: onblock,
        aircraftIcaoType: icao, aircraftAgeYears: age
    )
}

// MARK: - alert types

final class P3AlertTypeTests: XCTestCase {
    func testNewTypesAreAppendedInContractOrder() {
        let wires = AlertType.allCases.map(\.wire)
        XCTAssertEqual(Array(wires.suffix(5)), ["GATE_CLOSED", "TAKEOFF", "AT_GATE", "AIRCRAFT_SWAP", "CONNECTION_AT_RISK"])
        XCTAssertEqual(wires[wires.count - 6], "DIVERTED_DEST")
    }

    func testSeverityAndProFlagsMatchNotify() {
        for type in [AlertType.gateClosed, .takeoff, .atGate] {
            XCTAssertFalse(type.critical, type.wire)
            XCTAssertFalse(type.proOnly, type.wire)
            XCTAssertEqual(alertChannelId(type), PushContract.channelUpdates)
        }
        for type in [AlertType.aircraftSwap, .connectionAtRisk] {
            XCTAssertTrue(type.critical, type.wire)
            XCTAssertTrue(type.proOnly, type.wire)
            XCTAssertEqual(alertChannelId(type), PushContract.channelCritical)
        }
        XCTAssertTrue(AlertType.inboundLate.proOnly)
        XCTAssertFalse(AlertType.delay.proOnly)
    }

    func testTagGroupsMatchNotifyTs() {
        XCTAssertEqual(alertTagGroup("GATE_CLOSED"), "boarding")
        XCTAssertEqual(alertTagGroup("BOARDING"), "boarding")
        XCTAssertEqual(alertTagGroup("TAKEOFF"), "departed")
        XCTAssertEqual(alertTagGroup("DEPARTED"), "departed")
        XCTAssertEqual(alertTagGroup("AT_GATE"), "at_gate")
        XCTAssertEqual(alertTagGroup("AIRCRAFT_SWAP"), "aircraft_swap")
        XCTAssertEqual(alertTagGroup("CONNECTION_AT_RISK"), "connection")
        XCTAssertEqual(alertTagGroup("GATE_CHANGE"), "gate")
    }

    func testEveryTypeIsInExactlyOneGroup() {
        let grouped = AlertGroup.allCases.flatMap(\.types)
        XCTAssertEqual(Set(grouped), Set(AlertType.allCases))
        XCTAssertEqual(grouped.count, AlertType.allCases.count)
        XCTAssertEqual(AlertGroup.boarding.types, [.boarding, .gateClosed])
        XCTAssertEqual(AlertGroup.takeoffAndLanding.types, [.departed, .takeoff, .landed, .atGate])
        XCTAssertTrue(AlertGroup.aircraftSwap.proOnly)
        XCTAssertTrue(AlertGroup.connections.proOnly)
        XCTAssertFalse(AlertGroup.boarding.proOnly)
        let toggles = alertToggles(muted: [], isPro: false)
        XCTAssertEqual(toggles.first { $0.group == .connections }?.locked, true)
        XCTAssertEqual(toggles.first { $0.group == .takeoffAndLanding }?.locked, false)
    }

    func testCriticalPushOfANewTypeIsTimeSensitive() {
        let parsed = parseIncomingPush([
            "kind": "alert", "flight_id": p3FlightId, "event_type": "CONNECTION_AT_RISK",
            "title": "Connection at risk", "body": "6E 512 leaves DEL 10 min after you land",
        ])
        guard case .alert(let alert) = parsed else { return XCTFail("\(parsed)") }
        XCTAssertEqual(alert.eventType, .connectionAtRisk)
        XCTAssertEqual(alert.interruptionLevel, "time-sensitive")
        XCTAssertEqual(alert.tag, "\(p3FlightId):connection")
        let quiet = parseIncomingPush([
            "kind": "alert", "flight_id": p3FlightId, "event_type": "TAKEOFF", "title": "Airborne", "body": "x", "quiet": "1",
        ])
        guard case .alert(let info) = quiet else { return XCTFail("\(quiet)") }
        XCTAssertEqual(info.channelId, PushContract.channelQuiet)
        XCTAssertEqual(info.tag, "\(p3FlightId):departed")
    }
}

// MARK: - flight columns

final class P3FlightColumnsTests: XCTestCase {
    func testFlightViewReadsTheP3Columns() {
        let row: [String: Any] = [
            "id": p3FlightId, "carrier_iata": "6E", "flight_number": "1401", "flight_date": "2026-11-26",
            "dep_iata": "DEL", "arr_iata": "DXB", "status": "en_route",
            "dep_offblock_at": "2026-11-26T10:05:00+00:00", "dep_runway_at": "2026-11-26T10:21:00+00:00",
            "arr_runway_at": NSNull(), "arr_onblock_at": NSNull(),
            "aircraft_icao_type": "A21N", "aircraft_age_years": 8.4,
        ]
        let flight = FlyTrackCore.flight(from: row)
        XCTAssertEqual(flight?.depOffblockAt, at("2026-11-26T10:05:00Z"))
        XCTAssertEqual(flight?.depRunwayAt, at("2026-11-26T10:21:00Z"))
        XCTAssertNil(flight?.arrRunwayAt)
        XCTAssertEqual(flight?.aircraftIcaoType, "A21N")
        XCTAssertEqual(flight?.aircraftAgeYears, 8.4)
    }

    func testCacheRoundTripKeepsTheP3Columns() throws {
        let original = p3Flight(status: .arrived, actDep: at("2026-11-26T10:20:00Z"), actArr: at("2026-11-26T13:20:00Z"),
                                offblock: at("2026-11-26T10:05:00Z"), takeoff: at("2026-11-26T10:20:00Z"),
                                touchdown: at("2026-11-26T13:12:00Z"), onblock: at("2026-11-26T13:20:00Z"))
        let object = try XCTUnwrap(JSON.object(from: Data(flightViewJSON(original).utf8)))
        let decoded = try XCTUnwrap(FlyTrackCore.flight(from: object))
        XCTAssertEqual(decoded.depOffblockAt, original.depOffblockAt)
        XCTAssertEqual(decoded.depRunwayAt, original.depRunwayAt)
        XCTAssertEqual(decoded.arrRunwayAt, original.arrRunwayAt)
        XCTAssertEqual(decoded.arrOnblockAt, original.arrOnblockAt)
        XCTAssertEqual(decoded.aircraftIcaoType, "A21N")
        XCTAssertEqual(decoded.aircraftAgeYears, 3.46)
    }

    func testCompactSyncCarriesBlockTimesAndKeepsTheAircraftOfTheSameTail() throws {
        let cached = p3Flight(offblock: at("2026-11-26T10:05:00Z"))
        let json = """
        {"id":"\(p3FlightId)","c":"6E","n":"1401","d":"2026-11-26","dep":"DEL","arr":"DXB","st":"en_route",
         "sd":"2026-11-26T10:00:00Z","reg":"VT-IUA","rwd":"2026-11-26T10:21:00Z","u":"2026-11-26T10:30:00Z"}
        """
        let merged = try XCTUnwrap(mergeCompactFlight(json: json, into: cached))
        XCTAssertEqual(merged.depRunwayAt, at("2026-11-26T10:21:00Z"))
        XCTAssertEqual(merged.depOffblockAt, at("2026-11-26T10:05:00Z"), "an older payload without ofb keeps the cache")
        XCTAssertEqual(merged.aircraftIcaoType, "A21N")
        XCTAssertEqual(merged.aircraftAgeYears, 3.46)

        let swapped = json.replacingOccurrences(of: "VT-IUA", with: "VT-IUB").replacingOccurrences(of: "\"rwd\"", with: "\"ofb\":null,\"rwd\"")
        let after = try XCTUnwrap(mergeCompactFlight(json: swapped, into: cached))
        XCTAssertNil(after.aircraftIcaoType, "another tail: the cached type no longer applies")
        XCTAssertNil(after.aircraftAgeYears)
        XCTAssertNil(after.depOffblockAt, "an explicit null clears it")
    }
}

// MARK: - A2 timeline

private let timelineAnswer = """
[{"key":"scheduled","at":"2026-11-26T10:00:00Z","state":"done","detail":{"sched_dep":"2026-11-26T10:00:00Z","sched_arr":"2026-11-26T13:30:00Z"}},
 {"key":"checkin_open","at":"2026-11-25T10:00:00Z","state":"done","detail":{"closes_at":"2026-11-26T09:00:00Z"}},
 {"key":"gate_assigned","at":"2026-11-26T08:40:00Z","state":"done","detail":{"gate":"42B","terminal":"3"}},
 {"key":"boarding","at":"2026-11-26T09:20:00Z","state":"done","detail":{"gate":"42B","terminal":"3"}},
 {"key":"gate_closed","at":null,"state":"done","detail":{"gate":"42B"}},
 {"key":"pushback","at":"2026-11-26T10:12:00Z","state":"done","detail":{"delay_min":12}},
 {"key":"takeoff","at":"2026-11-26T10:30:00Z","state":"current","detail":{"taxi_out_min":18}},
 {"key":"landing","at":"2026-11-26T13:35:00Z","state":"next","detail":{"delay_min":5}},
 {"key":"at_gate","at":null,"state":"next","detail":{"gate":null,"terminal":null,"taxi_in_min":null}},
 {"key":"teleport","at":null,"state":"next","detail":null}]
"""

final class TimelineTests: XCTestCase {
    func testParsesItemsAndSkipsUnknownKeys() throws {
        let items = try parseFlightTimeline(data(timelineAnswer))
        XCTAssertEqual(items.map(\.key), [.scheduled, .checkinOpen, .gateAssigned, .boarding, .gateClosed, .pushback, .takeoff, .landing, .atGate])
        XCTAssertEqual(items[6].state, .current)
        XCTAssertEqual(items[6].detail?.taxiOutMin, 18)
        XCTAssertEqual(items[2].detail?.gate, "42B")
        XCTAssertNil(items[4].at)
        XCTAssertNil(items[8].detail?.gate)
        XCTAssertEqual(try parseFlightTimeline(data("null")), [])
        XCTAssertThrowsError(try parseFlightTimeline(data("{\"key\":1}")))
    }

    func testRowsUseTheAirportOfEachMilestoneAndWordTheDetail() throws {
        let rows = timelineRows(try parseFlightTimeline(data(timelineAnswer)), flight: p3Flight())
        XCTAssertEqual(rows[0].time, "15:30", "departure side in IST")
        XCTAssertEqual(rows[7].time, "17:35", "landing in Dubai time")
        XCTAssertTrue(rows[7].expected)
        XCTAssertFalse(rows[6].expected)
        XCTAssertEqual(rows[1].notes, [.checkinCloses(time: "14:30")])
        XCTAssertEqual(rows[2].notes, [.gate(gate: "42B", terminal: "3")])
        XCTAssertEqual(rows[5].notes, [.delay(minutes: 12)])
        XCTAssertEqual(rows[6].notes, [.taxiOut(minutes: 18)])
        XCTAssertEqual(rows[8].notes, [])
        XCTAssertNil(rows[8].time)
    }

    func testFreeAnswerHasNoNotes() throws {
        let free = try parseFlightTimeline(data(#"[{"key":"pushback","at":"2026-11-26T10:12:00Z","state":"current","detail":null}]"#))
        XCTAssertEqual(timelineRows(free, flight: p3Flight()).first?.notes, [])
    }

    func testLocalTimelineBeforeDeparture() {
        let items = localTimeline(p3Flight(estDep: at("2026-11-26T10:20:00Z")))
        XCTAssertEqual(items.map(\.key), [.scheduled, .checkinOpen, .gateAssigned, .boarding, .gateClosed, .pushback, .takeoff, .landing, .atGate, .belt])
        XCTAssertEqual(items.first?.state, .current)
        XCTAssertEqual(items.first { $0.key == .pushback }?.at, at("2026-11-26T10:20:00Z"), "expected departure")
        XCTAssertEqual(items.first { $0.key == .landing }?.at, at("2026-11-26T13:30:00Z"))
        XCTAssertTrue(items.dropFirst().allSatisfy { $0.state == .next })
    }

    func testLocalTimelineInTheAirImpliesEarlierSteps() {
        let items = localTimeline(p3Flight(status: .enRoute, actDep: at("2026-11-26T10:20:00Z"),
                                           offblock: at("2026-11-26T10:05:00Z"), takeoff: at("2026-11-26T10:20:00Z")))
        XCTAssertEqual(items.map(\.key), [.scheduled, .boarding, .gateClosed, .pushback, .takeoff, .landing, .atGate, .belt],
                       "check-in and gate assignment are left out without proof")
        XCTAssertEqual(items.first { $0.key == .boarding }?.state, .done)
        XCTAssertNil(items.first { $0.key == .boarding }?.at)
        XCTAssertEqual(items.first { $0.key == .pushback }?.at, at("2026-11-26T10:05:00Z"))
        XCTAssertEqual(items.first { $0.key == .takeoff }?.state, .current)
        XCTAssertEqual(items.first { $0.key == .takeoff }?.at, at("2026-11-26T10:20:00Z"))
    }

    func testLocalTimelineAtTheBelt() {
        let items = localTimeline(p3Flight(status: .arrived, actDep: at("2026-11-26T10:20:00Z"), actArr: at("2026-11-26T13:20:00Z"),
                                           depGate: "42B", belt: "7", touchdown: at("2026-11-26T13:12:00Z"), onblock: at("2026-11-26T13:20:00Z")))
        XCTAssertEqual(items.last?.key, .belt)
        XCTAssertEqual(items.last?.state, .current)
        XCTAssertEqual(items.first { $0.key == .landing }?.at, at("2026-11-26T13:12:00Z"))
        XCTAssertTrue(items.contains { $0.key == .gateAssigned }, "a known gate proves the assignment")
    }

    func testLocalTimelineCancelled() {
        let items = localTimeline(p3Flight(status: .cancelled, depGate: "42B"))
        XCTAssertEqual(items.map(\.key), [.scheduled, .gateAssigned, .cancelled])
        XCTAssertEqual(items.last?.state, .current)
        XCTAssertTrue(items.dropLast().allSatisfy { $0.state == .done })
    }

    func testLocalTimelineDiverted() {
        let items = localTimeline(p3Flight(status: .diverted, actDep: at("2026-11-26T10:20:00Z")))
        XCTAssertTrue(items.contains { $0.key == .diverted && $0.state == .current })
    }
}

// MARK: - B1 delay prediction

private let predictionAnswer = """
{"p_delay_15":0.42,"p_delay_60":0.08,"expected_delay_min":17,
 "factors":[{"key":"history","effect_min":9,"label":"This flight is late 45% of the time"},
            {"key":"inbound","effect_min":20,"label":"Your plane is running 20 min late"},
            {"key":"time_of_day","effect_min":-4,"label":"Morning departures run earlier"},
            {"key":"airport","effect_min":0,"label":"DEL departures are on time"}],
 "confidence":"medium","samples":14,"model":"rules-v1","computed_at":"2026-11-26T08:00:00Z"}
"""

final class DelayPredictionTests: XCTestCase {
    func testParsesTheContractShape() throws {
        let prediction = try XCTUnwrap(try parseDelayPrediction(data(predictionAnswer)))
        XCTAssertEqual(prediction.pDelay15, 0.42)
        XCTAssertEqual(prediction.pDelay60, 0.08)
        XCTAssertEqual(prediction.expectedDelayMin, 17)
        XCTAssertEqual(prediction.confidence, .medium)
        XCTAssertEqual(prediction.samples, 14)
        XCTAssertEqual(prediction.model, "rules-v1")
        XCTAssertEqual(prediction.factors.count, 4)
        XCTAssertNil(try parseDelayPrediction(data("null")), "outside the 72 h window")
        XCTAssertThrowsError(try parseDelayPrediction(data("[]")))
        XCTAssertEqual(PredictionConfidence.fromWire("bogus"), .low)
    }

    func testCardSortsFactorsByEffectAndDropsZero() throws {
        let card = delayPredictionCard(try XCTUnwrap(try parseDelayPrediction(data(predictionAnswer))))
        XCTAssertEqual(card.percentLate15, 42)
        XCTAssertEqual(card.percentLate60, 8)
        XCTAssertEqual(card.factors.map(\.key), ["inbound", "history", "time_of_day"])
    }

    func testP60NeverExceedsP15() throws {
        let prediction = try XCTUnwrap(try parseDelayPrediction(data(#"{"p_delay_15":0.2,"p_delay_60":0.5,"expected_delay_min":-3}"#)))
        XCTAssertEqual(prediction.pDelay60, 0.2)
        XCTAssertEqual(prediction.expectedDelayMin, 0)
    }

    func testWindowIs72HoursBeforeDeparture() {
        let flight = p3Flight()
        XCTAssertFalse(flight.wantsDelayPrediction(now: at("2026-11-23T09:59:00Z")))
        XCTAssertTrue(flight.wantsDelayPrediction(now: at("2026-11-23T10:00:00Z")))
        XCTAssertTrue(flight.wantsDelayPrediction(now: at("2026-11-26T09:59:00Z")))
        XCTAssertFalse(flight.wantsDelayPrediction(now: at("2026-11-26T10:00:00Z")))
        XCTAssertTrue(p3Flight(estDep: at("2026-11-26T11:00:00Z")).wantsDelayPrediction(now: at("2026-11-26T10:30:00Z")))
        XCTAssertFalse(p3Flight(status: .cancelled).wantsDelayPrediction(now: at("2026-11-26T08:00:00Z")))
        XCTAssertFalse(p3Flight(offblock: at("2026-11-26T09:58:00Z")).wantsDelayPrediction(now: at("2026-11-26T09:59:00Z")))
    }

    func testLabelLanguage() {
        XCTAssertEqual(predictionLanguage(preferredLanguages: ["hi"]), "hi")
        XCTAssertEqual(predictionLanguage(preferredLanguages: ["hi-IN", "en"]), "hi")
        XCTAssertEqual(predictionLanguage(preferredLanguages: ["en-IN"]), "en")
        XCTAssertEqual(predictionLanguage(preferredLanguages: []), "en")
    }
}

// MARK: - B2 gate prediction, B3 taxi, A5 aircraft

final class GateTaxiAircraftTests: XCTestCase {
    func testGatePrediction() throws {
        let gate = try XCTUnwrap(try parseGatePrediction(data(
            #"{"gate":"42B","terminal":"1","share":0.78,"count":7,"samples":9,"alternatives":[{"gate":"40","share":0.11},{"gate":"41","share":0.11}]}"#
        )))
        XCTAssertEqual(gate.gate, "42B")
        XCTAssertEqual(gate.terminal, "1")
        XCTAssertEqual(gate.count, 7)
        XCTAssertEqual(gate.samples, 9)
        XCTAssertEqual(gate.alternatives.map(\.gate), ["40", "41"])
        XCTAssertNil(try parseGatePrediction(data("null")), "fewer than 4 samples")
        let withoutCount = try XCTUnwrap(try parseGatePrediction(data(#"{"gate":"42B","share":0.78,"samples":9}"#)))
        XCTAssertEqual(withoutCount.count, 7, "rebuilt from the share")
        XCTAssertTrue(p3Flight().wantsGatePrediction)
        XCTAssertFalse(p3Flight(depGate: "42B").wantsGatePrediction, "only while no real gate is assigned")
        XCTAssertFalse(p3Flight(status: .departed).wantsGatePrediction)
    }

    func testTaxiStatsAndCard() throws {
        let dep = try XCTUnwrap(try parseTaxiStats(data(
            #"{"iata":"DEL","window_days":30,"taxi_out":{"p50":18,"p80":27,"samples":312},"taxi_in":{"p50":9,"p80":14,"samples":290}}"#
        )))
        XCTAssertEqual(dep.taxiOut, TaxiTimes(p50: 18, p80: 27, samples: 312))
        let arr = try XCTUnwrap(try parseTaxiStats(data(
            #"{"iata":"DXB","window_days":30,"taxi_out":{"p50":null,"p80":null,"samples":0},"taxi_in":{"p50":12,"p80":20,"samples":3}}"#
        )))
        XCTAssertNil(arr.taxiOut.p50)
        let card = try XCTUnwrap(taxiCard(flight: p3Flight(), dep: dep, arr: arr))
        XCTAssertEqual(card.taxiOutMin, 18)
        XCTAssertNil(card.taxiInMin, "3 samples are too few")
        XCTAssertNil(taxiCard(flight: p3Flight(), dep: nil, arr: arr))
    }

    func testAircraftSwapsAndCard() throws {
        let swaps = try parseAircraftSwaps(data("""
        [{"created_at":"2026-11-26T07:10:00+00:00","payload":{"old_reg":"VT-IUA","new_reg":"VT-IUB","old_model":"A321neo","new_model":"A320neo","flight":"6E 1401"}},
         {"created_at":"2026-11-26T06:00:00+00:00","payload":{"old_reg":"VT-IUC","new_reg":"VT-IUA"}},
         {"created_at":"2026-11-26T08:00:00+00:00","payload":{}}]
        """))
        XCTAssertEqual(swaps.map(\.newReg), ["VT-IUA", "VT-IUB"], "oldest first, empty payloads dropped")

        let pro = try XCTUnwrap(aircraftCard(flight: p3Flight(), pro: true, swaps: swaps))
        XCTAssertEqual(pro.type, "Airbus A321neo")
        XCTAssertEqual(pro.icaoType, "A21N")
        XCTAssertEqual(pro.reg, "VT-IUA")
        XCTAssertEqual(pro.ageYears, "3.5")
        XCTAssertEqual(pro.swaps.count, 2)
        XCTAssertFalse(pro.ageLocked)

        let free = try XCTUnwrap(aircraftCard(flight: p3Flight(), pro: false, swaps: swaps))
        XCTAssertNil(free.ageYears, "the age is Pro")
        XCTAssertEqual(free.swaps, [])
        XCTAssertTrue(free.ageLocked)
        XCTAssertEqual(aircraftCard(flight: p3Flight(model: nil), pro: nil, swaps: [])?.type, "A21N", "the type is for everyone")
        XCTAssertNil(aircraftCard(flight: p3Flight(model: nil, icao: nil), pro: true, swaps: []))
        XCTAssertEqual(formatAgeYears(8.44), "8.4")
    }
}

// MARK: - B4 connections, B7 trends

private let connectionsAnswer = """
[{"in_flight_id":"a1","out_flight_id":"b1","airport":"DEL","in_flight":"6E 2134","out_flight":"6E 512",
  "in_arr":"2026-11-26T08:00:00Z","out_dep":"2026-11-26T09:25:00Z","layover_min":85,"mct_min":75,"buffer_min":10,
  "risk":"tight","terminal_change":"T1→T3","transfer_note":"Shuttle between T1 and T3, about 20–30 min"},
 {"in_flight_id":"a2","out_flight_id":"b2","airport":"BOM","in_flight":"AI 101","out_flight":"AI 191",
  "layover_min":50,"mct_min":60,"risk":"at_risk"},
 {"in_flight_id":"a3","airport":"BLR"}]
"""

final class ConnectionsTrendsTests: XCTestCase {
    func testParsesConnections() throws {
        let list = try parseConnections(data(connectionsAnswer))
        XCTAssertEqual(list.count, 2, "rows without both flights are skipped")
        XCTAssertEqual(list[0].risk, .tight)
        XCTAssertEqual(list[0].terminalChange, "T1→T3")
        XCTAssertEqual(list[0].outDep, at("2026-11-26T09:25:00Z"))
        XCTAssertEqual(list[1].bufferMin, -10, "missing buffer = layover − MCT")
        XCTAssertEqual(list[1].risk, .atRisk)
        XCTAssertTrue(list[0].involves("b1"))
        XCTAssertFalse(list[0].involves("a2"))
        XCTAssertEqual(try parseConnections(data("[]")), [])
        XCTAssertEqual(ConnectionRisk.fromWire("new_level"), .tight)
    }

    func testRiskRules() {
        XCTAssertEqual(ConnectionRisk.fromBuffer(30), .ok)
        XCTAssertEqual(ConnectionRisk.fromBuffer(29), .tight)
        XCTAssertEqual(ConnectionRisk.fromBuffer(0), .tight)
        XCTAssertEqual(ConnectionRisk.fromBuffer(-1), .atRisk)
        XCTAssertEqual(connectionRisk(inArr: at("2026-11-26T08:00:00Z"), outDep: at("2026-11-26T07:59:00Z"), mctMin: 75), .missed)
        XCTAssertEqual(connectionRisk(inArr: at("2026-11-26T08:00:00Z"), outDep: at("2026-11-26T09:25:00Z"), mctMin: 45), .ok)
        XCTAssertNil(connectionRisk(inArr: nil, outDep: at("2026-11-26T09:25:00Z"), mctMin: 45))
    }

    func testAirportTrends() throws {
        let trends = try XCTUnwrap(try parseAirportTrends(data("""
        {"iata":"DEL","days":14,
         "by_hour":[{"hour":18,"avg_dep_delay":31,"delayed_pct":55,"samples":40},{"hour":6,"avg_dep_delay":12,"delayed_pct":28,"samples":40},
                    {"hour":23,"avg_dep_delay":60,"delayed_pct":90,"samples":2},{"hour":24,"avg_dep_delay":1,"samples":9}],
         "by_day":[{"date":"2026-11-21","avg_dep_delay":15,"cancelled":0,"samples":80},{"date":"2026-11-20","avg_dep_delay":19,"cancelled":2,"samples":75}]}
        """)))
        XCTAssertEqual(trends.byHour.map(\.hour), [6, 18, 23], "sorted, out-of-range hours dropped")
        XCTAssertEqual(trends.byDay.map(\.date.iso), ["2026-11-20", "2026-11-21"])
        XCTAssertEqual(trends.worstHour()?.hour, 18, "hours with too few legs are ignored")
        XCTAssertNil(try parseAirportTrends(data("null")))
    }
}

// MARK: - models

@MainActor
final class FakeIntelSource: FlightIntelSource {
    var timelineResult: RemoteResult<[TimelineItem]> = .ok([])
    var predictionResult: RemoteResult<DelayPrediction?> = .ok(nil)
    var gateResult: RemoteResult<GatePrediction?> = .ok(nil)
    var taxiResult: [String: RemoteResult<TaxiStats?>] = [:]
    var swapsResult: RemoteResult<[AircraftSwap]> = .ok([])
    var connectionsResult: RemoteResult<[FlightConnection]> = .ok([])
    var trendsResult: RemoteResult<AirportTrends?> = .ok(nil)
    var calls: [String] = []

    func timeline(flightId: String) async -> RemoteResult<[TimelineItem]> { calls.append("timeline"); return timelineResult }
    func delayPrediction(flightId: String, language: String) async -> RemoteResult<DelayPrediction?> {
        calls.append("prediction:\(language)")
        return predictionResult
    }
    func gatePrediction(flightId: String) async -> RemoteResult<GatePrediction?> { calls.append("gate"); return gateResult }
    func taxiStats(iata: String) async -> RemoteResult<TaxiStats?> { calls.append("taxi:\(iata)"); return taxiResult[iata] ?? .ok(nil) }
    func aircraftSwaps(flightId: String) async -> RemoteResult<[AircraftSwap]> { calls.append("swaps"); return swapsResult }
    func connections() async -> RemoteResult<[FlightConnection]> { calls.append("connections"); return connectionsResult }
    func airportTrends(iata: String, days: Int) async -> RemoteResult<AirportTrends?> { calls.append("trends:\(iata):\(days)"); return trendsResult }
}

private let proRequired: RemoteResult<DelayPrediction?> = .refused(code: "42501", message: "pro_required")

@MainActor
final class FlightIntelModelTests: XCTestCase {
    private let beforeDeparture = at("2026-11-26T06:00:00Z")

    func testFreeUserGetsTimelineTypeAndTeaserWithoutProCalls() async {
        let source = FakeIntelSource()
        source.timelineResult = .offline
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: beforeDeparture)
        XCTAssertEqual(source.calls, ["timeline"])
        XCTAssertTrue(model.state.timelineIsLocal, "offline: the cached row's timeline")
        XCTAssertFalse(model.state.timeline.isEmpty)
        XCTAssertEqual(model.state.delay, .locked)
        XCTAssertEqual(model.state.gate, .hidden)
        XCTAssertNil(model.state.taxi)
        XCTAssertEqual(model.state.aircraft?.type, "Airbus A321neo")
        XCTAssertNil(model.state.aircraft?.ageYears)
        XCTAssertEqual(model.state.connections, [])
    }

    func testProUserGetsEveryCardAndCachesAnswers() async throws {
        let source = FakeIntelSource()
        source.timelineResult = .ok(try parseFlightTimeline(data(timelineAnswer)))
        source.predictionResult = .ok(try parseDelayPrediction(data(predictionAnswer)))
        source.gateResult = .ok(GatePrediction(gate: "42B", terminal: "3", share: 0.78, count: 7, samples: 9, alternatives: []))
        source.taxiResult["DEL"] = .ok(TaxiStats(iata: "DEL", windowDays: 30, taxiOut: TaxiTimes(p50: 18, p80: 27, samples: 300), taxiIn: TaxiTimes(p50: 9, p80: 12, samples: 300)))
        source.connectionsResult = .ok(try parseConnections(data(connectionsAnswer)).map { c in
            var copy = c
            if copy.inFlightId == "a1" { copy.inFlightId = p3FlightId }
            return copy
        })
        let model = FlightIntelModel(flightId: p3FlightId, source: source, language: "hi")
        await model.update(flight: p3Flight(), pro: true, tracked: true, now: beforeDeparture)
        XCTAssertFalse(model.state.timelineIsLocal)
        XCTAssertEqual(model.state.delay.value?.percentLate15, 42)
        XCTAssertEqual(model.state.gate.value?.gate, "42B")
        XCTAssertEqual(model.state.taxi?.taxiOutMin, 18)
        XCTAssertEqual(model.state.aircraft?.ageYears, "3.5")
        XCTAssertEqual(model.state.connections.map(\.outFlightId), ["b1"])
        XCTAssertTrue(source.calls.contains("prediction:hi"))
        XCTAssertTrue(source.calls.contains("taxi:DEL") && source.calls.contains("taxi:DXB"))

        let callsAfterFirst = source.calls.count
        await model.update(flight: p3Flight(), pro: true, tracked: true, now: beforeDeparture.addingTimeInterval(60))
        XCTAssertEqual(source.calls.count, callsAfterFirst, "nothing changed: no new calls")

        await model.update(flight: p3Flight(), pro: true, tracked: true, now: beforeDeparture.addingTimeInterval(16 * 60))
        XCTAssertEqual(source.calls.suffix(2), ["prediction:hi", "connections"], "prediction and connections refresh on their own clocks")

        await model.update(flight: p3Flight(depGate: "42B"), pro: true, tracked: true, now: beforeDeparture.addingTimeInterval(17 * 60))
        XCTAssertEqual(model.state.gate, .hidden, "a real gate hides the prediction")
        XCTAssertEqual(source.calls.last, "timeline", "a gate change asks for the timeline again")
    }

    func testProRequiredShowsTheTeaser() async {
        let source = FakeIntelSource()
        source.predictionResult = proRequired
        source.gateResult = .refused(code: "42501", message: "pro_required")
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: true, tracked: false, now: beforeDeparture)
        XCTAssertEqual(model.state.delay, .locked)
        XCTAssertEqual(model.state.gate, .locked)
        XCTAssertFalse(source.calls.contains("connections"), "untracked flights have no connections")
    }

    func testUnknownProShowsNoProCards() async {
        let source = FakeIntelSource()
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: nil, tracked: true, now: beforeDeparture)
        XCTAssertEqual(model.state.delay, .hidden)
        XCTAssertNil(model.state.aircraft?.ageYears)
        XCTAssertEqual(model.state.aircraft?.ageLocked, false)
        XCTAssertEqual(source.calls, [], "the timeline waits for the Pro answer instead of asking twice")
        XCTAssertTrue(model.state.timelineIsLocal)
        XCTAssertFalse(model.state.timeline.isEmpty)
    }

    func testBuyingProAsksForTheTimelineAgain() async throws {
        let source = FakeIntelSource()
        source.timelineResult = .ok(try parseFlightTimeline(data(timelineAnswer)))
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: beforeDeparture)
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: beforeDeparture.addingTimeInterval(60))
        XCTAssertEqual(source.calls.filter { $0 == "timeline" }.count, 1)
        // The access changes before (or without) the screen's invalidate(): the Pro answer has `detail`.
        await model.update(flight: p3Flight(), pro: true, tracked: true, now: beforeDeparture.addingTimeInterval(120))
        XCTAssertEqual(source.calls.filter { $0 == "timeline" }.count, 2)
    }

    func testOutsideTheWindowNoPrediction() async {
        let source = FakeIntelSource()
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: at("2026-11-20T00:00:00Z"))
        XCTAssertEqual(model.state.delay, .hidden)
        await model.update(flight: p3Flight(), pro: true, tracked: true, now: at("2026-11-20T00:00:00Z"))
        XCTAssertFalse(source.calls.contains { $0.hasPrefix("prediction") })
    }

    func testFailedTimelineIsAskedAgain() async throws {
        let source = FakeIntelSource()
        source.timelineResult = .offline
        let model = FlightIntelModel(flightId: p3FlightId, source: source)
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: beforeDeparture)
        source.timelineResult = .ok(try parseFlightTimeline(data(timelineAnswer)))
        await model.update(flight: p3Flight(), pro: false, tracked: true, now: beforeDeparture)
        XCTAssertEqual(source.calls, ["timeline", "timeline"])
        XCTAssertFalse(model.state.timelineIsLocal)
    }

    func testNoFlightClearsTheState() async {
        let model = FlightIntelModel(flightId: p3FlightId, source: FakeIntelSource())
        await model.update(flight: nil, pro: true, tracked: false, now: beforeDeparture)
        XCTAssertEqual(model.state, FlightIntelState())
    }
}

@MainActor
final class ConnectionsAndTrendsModelTests: XCTestCase {
    func testConnectionsModel() async throws {
        let source = FakeIntelSource()
        let model = ConnectionsModel(source: source)
        await model.load(pro: false)
        XCTAssertEqual(model.state, .locked)
        XCTAssertEqual(source.calls, [], "the teaser needs no call")

        source.connectionsResult = .ok(try parseConnections(data(connectionsAnswer)))
        await model.load(pro: true)
        guard case .ready(let list) = model.state else { return XCTFail("\(model.state)") }
        XCTAssertEqual(list.count, 2)

        source.connectionsResult = .offline
        await model.load(pro: true)
        guard case .ready = model.state else { return XCTFail("offline keeps the list") }

        let fresh = ConnectionsModel(source: source)
        await fresh.load(pro: true)
        XCTAssertEqual(fresh.state, .offline)
        source.connectionsResult = .refused(code: "42501", message: "pro_required")
        await fresh.load(pro: true)
        XCTAssertEqual(fresh.state, .locked)
    }

    func testTrendsModel() async throws {
        let source = FakeIntelSource()
        let model = AirportTrendsModel(iata: " del ", source: source)
        XCTAssertEqual(model.iata, "DEL")
        await model.load(pro: true)
        XCTAssertEqual(model.state, .empty)
        XCTAssertEqual(source.calls, ["trends:DEL:14"])
        source.trendsResult = .ok(AirportTrends(iata: "DEL", days: 30, byHour: [HourTrend(hour: 6, avgDepDelay: 12, delayedPct: 28, samples: 40)], byDay: []))
        await model.load(pro: true, days: 30)
        guard case .ready(let trends) = model.state else { return XCTFail("\(model.state)") }
        XCTAssertEqual(trends.byHour.count, 1)
        XCTAssertEqual(source.calls.last, "trends:DEL:30")
        await model.load(pro: true, days: 500)
        XCTAssertEqual(model.days, 90, "the server allows 1...90")
        await model.load(pro: false)
        XCTAssertEqual(model.state, .locked)
    }
}
