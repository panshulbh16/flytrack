import Foundation

/// Decodes one `flight_view` row. Unknown status values become `.unknown`. A missing airport name leaves the airport nil.
public func flight(from view: [String: Any]) -> Flight? {
    guard let id = JSON.string(view["id"]),
          let carrier = JSON.string(view["carrier_iata"]),
          let number = JSON.string(view["flight_number"]),
          let date = JSON.string(view["flight_date"]).flatMap(LocalDate.parse),
          let dep = JSON.string(view["dep_iata"]),
          let arr = JSON.string(view["arr_iata"]) else { return nil }
    return Flight(
        id: id,
        carrierIata: carrier.trimmingCharacters(in: .whitespacesAndNewlines),
        flightNumber: number.trimmingCharacters(in: .whitespacesAndNewlines),
        flightDate: date,
        depIata: dep.trimmingCharacters(in: .whitespacesAndNewlines),
        arrIata: arr.trimmingCharacters(in: .whitespacesAndNewlines),
        airlineName: JSON.string(view["airline_name"]),
        status: FlightStatus.fromWire(JSON.string(view["status"])),
        schedDep: parseInstant(JSON.string(view["sched_dep"])),
        estDep: parseInstant(JSON.string(view["est_dep"])),
        actDep: parseInstant(JSON.string(view["act_dep"])),
        depTerminal: JSON.string(view["dep_terminal"]),
        depGate: JSON.string(view["dep_gate"]),
        depCheckin: JSON.string(view["dep_checkin"]),
        schedArr: parseInstant(JSON.string(view["sched_arr"])),
        estArr: parseInstant(JSON.string(view["est_arr"])),
        actArr: parseInstant(JSON.string(view["act_arr"])),
        arrTerminal: JSON.string(view["arr_terminal"]),
        arrGate: JSON.string(view["arr_gate"]),
        baggageBelt: JSON.string(view["baggage_belt"]),
        aircraftModel: JSON.string(view["aircraft_model"]),
        aircraftReg: JSON.string(view["aircraft_reg"]),
        delayReason: JSON.string(view["delay_reason"]),
        live: livePosition(view),
        lastPolledAt: parseInstant(JSON.string(view["last_polled_at"])),
        updatedAt: parseInstant(JSON.string(view["updated_at"])),
        depAirport: airport(prefix: "dep", in: view),
        arrAirport: airport(prefix: "arr", in: view),
        inboundFlightId: JSON.string(view["inbound_flight_id"]),
        prediction: prediction(from: view["prediction"]),
        hasProInsight: JSON.bool(view["has_pro_insight"]) ?? false
    )
}

/// Encodes a flight back into the `flight_view` shape `flight(from:)` reads, so a local cache can round-trip.
public func flightViewJSON(_ flight: Flight) -> String {
    var object: [String: Any] = [
        "id": flight.id,
        "carrier_iata": flight.carrierIata,
        "flight_number": flight.flightNumber,
        "flight_date": flight.flightDate.iso,
        "dep_iata": flight.depIata,
        "arr_iata": flight.arrIata,
        "status": flight.status.wire,
        "has_pro_insight": flight.hasProInsight,
    ]
    func put(_ key: String, _ value: String?) { if let value { object[key] = value } }
    func putDate(_ key: String, _ value: Date?) {
        if let value { object[key] = isoInstant(value) }
    }
    put("airline_name", flight.airlineName)
    putDate("sched_dep", flight.schedDep)
    putDate("est_dep", flight.estDep)
    putDate("act_dep", flight.actDep)
    put("dep_terminal", flight.depTerminal)
    put("dep_gate", flight.depGate)
    put("dep_checkin", flight.depCheckin)
    putDate("sched_arr", flight.schedArr)
    putDate("est_arr", flight.estArr)
    putDate("act_arr", flight.actArr)
    put("arr_terminal", flight.arrTerminal)
    put("arr_gate", flight.arrGate)
    put("baggage_belt", flight.baggageBelt)
    put("aircraft_model", flight.aircraftModel)
    put("aircraft_reg", flight.aircraftReg)
    put("delay_reason", flight.delayReason)
    putDate("last_polled_at", flight.lastPolledAt)
    putDate("updated_at", flight.updatedAt)
    put("inbound_flight_id", flight.inboundFlightId)
    if let live = flight.live {
        object["live_lat"] = live.lat
        object["live_lon"] = live.lon
        if let alt = live.altFt { object["live_alt_ft"] = alt }
        if let heading = live.headingDeg { object["live_heading"] = heading }
        if let speed = live.speedKts { object["live_speed_kts"] = speed }
        putDate("live_updated_at", live.at)
    }
    if let airport = flight.depAirport { write(airport, prefix: "dep", into: &object) }
    if let airport = flight.arrAirport { write(airport, prefix: "arr", into: &object) }
    let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    return String(data: data, encoding: .utf8) ?? "{}"
}

private func write(_ airport: Airport, prefix: String, into object: inout [String: Any]) {
    object["\(prefix)_name"] = airport.name
    object["\(prefix)_tz"] = airport.tz
    object["\(prefix)_lat"] = airport.lat
    object["\(prefix)_lon"] = airport.lon
    if let city = airport.city { object["\(prefix)_city"] = city }
}

private func isoInstant(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
}

public func flights(fromSearch data: Data) -> [Flight] {
    guard let object = JSON.object(from: data), let rows = object["flights"] as? [[String: Any]] else { return [] }
    return rows.compactMap(flight(from:))
}

public func trackedFlight(from row: [String: Any]) -> TrackedFlight? {
    guard let id = JSON.string(row["id"]),
          let userId = JSON.string(row["user_id"]),
          let flightId = JSON.string(row["flight_id"]) else { return nil }
    var prefs: [AlertType: Bool] = [:]
    if let raw = row["notify_prefs"] as? [String: Any] {
        for (key, value) in raw {
            if let type = AlertType.fromWire(key), let flag = JSON.bool(value) {
                prefs[type] = flag
            }
        }
    }
    return TrackedFlight(
        id: id,
        userId: userId,
        flightId: flightId,
        pnr: JSON.string(row["pnr"]),
        seat: JSON.string(row["seat"]),
        cabin: Cabin.fromWire(JSON.string(row["cabin"])),
        source: TrackSource.fromWire(JSON.string(row["source"])),
        notifyPrefs: prefs,
        note: JSON.string(row["note"]),
        archivedAt: parseInstant(JSON.string(row["archived_at"])),
        createdAt: parseInstant(JSON.string(row["created_at"]))
    )
}

public func profile(from row: [String: Any]) -> UserProfile? {
    guard let id = JSON.string(row["id"]) else { return nil }
    return UserProfile(
        id: id,
        displayName: JSON.string(row["display_name"]),
        plan: Plan.fromWire(JSON.string(row["plan"])),
        proUntil: parseInstant(JSON.string(row["pro_until"])),
        trialFlightId: JSON.string(row["trial_flight_id"])
    )
}

private func airport(prefix: String, in view: [String: Any]) -> Airport? {
    guard let iata = JSON.string(view["\(prefix)_iata"]),
          let name = JSON.string(view["\(prefix)_name"]),
          let tz = JSON.string(view["\(prefix)_tz"]),
          let lat = JSON.double(view["\(prefix)_lat"]),
          let lon = JSON.double(view["\(prefix)_lon"]) else { return nil }
    return Airport(iata: iata, name: name, city: JSON.string(view["\(prefix)_city"]), tz: tz, lat: lat, lon: lon)
}

private func livePosition(_ view: [String: Any]) -> LivePosition? {
    guard let lat = JSON.double(view["live_lat"]), let lon = JSON.double(view["live_lon"]) else { return nil }
    return LivePosition(
        lat: lat, lon: lon,
        altFt: JSON.int(view["live_alt_ft"]),
        headingDeg: JSON.double(view["live_heading"]),
        speedKts: JSON.double(view["live_speed_kts"]),
        at: parseInstant(JSON.string(view["live_updated_at"]))
    )
}

private func prediction(from value: Any?) -> InboundPrediction? {
    guard let object = value as? [String: Any] else { return nil }
    let inbound = (object["inbound"] as? [String: Any]).map { inbound in
        let live = (inbound["live"] as? [String: Any]).flatMap { live -> LivePosition? in
            guard let lat = JSON.double(live["lat"]), let lon = JSON.double(live["lon"]) else { return nil }
            return LivePosition(
                lat: lat, lon: lon,
                altFt: JSON.int(live["altFt"]),
                headingDeg: JSON.double(live["headingDeg"]),
                speedKts: JSON.double(live["speedKts"]),
                at: parseInstant(JSON.string(live["at"]))
            )
        }
        return InboundAircraft(
            flight: JSON.string(inbound["flight"]) ?? "",
            fromIata: JSON.string(inbound["from"]) ?? "",
            status: FlightStatus.fromWire(JSON.string(inbound["status"])),
            schedArr: parseInstant(JSON.string(inbound["schedArr"])),
            estArr: parseInstant(JSON.string(inbound["estArr"])),
            actArr: parseInstant(JSON.string(inbound["actArr"])),
            live: live
        )
    }
    return InboundPrediction(
        computedAt: parseInstant(JSON.string(object["computedAt"])),
        predictedDep: parseInstant(JSON.string(object["predictedDep"])),
        lateMin: JSON.int(object["lateMin"]) ?? 0,
        reason: JSON.string(object["reason"]),
        inbound: inbound
    )
}
