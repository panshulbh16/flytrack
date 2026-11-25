import FlyTrackCore
import Foundation
import Supabase
import SwiftData

/// supabase-swift 2.55.7. The client is nil when the xcconfig keys are blank, and every command
/// then returns `.notConfigured` instead of touching the network.
@MainActor
final class SupabaseStack {
    let client: SupabaseClient?
    let context: ModelContext

    init(context: ModelContext) {
        self.context = context
        if let url = AppConfig.supabaseURL, let key = AppConfig.anonKey {
            client = SupabaseClient(supabaseURL: url, supabaseKey: key)
        } else {
            client = nil
        }
    }
}

@MainActor
final class LiveAuthRepository: AuthRepository {
    private let stack: SupabaseStack
    /// Set by AppShell: Gmail revoke and state for the old HeyGate user. Runs before the cache wipe, so the
    /// Gmail account is forgotten before its rows go and a running scan writes nothing back.
    var onUserDataWiped: (@MainActor () async -> Void)?
    init(stack: SupabaseStack) { self.stack = stack }

    private func wipeUserData() async {
        await onUserDataWiped?()
        LocalCache.wipeUserData(in: stack.context)
    }

    func session() async -> SessionState {
        guard let client = stack.client else { return .notConfigured }
        guard let session = client.auth.currentSession else { return .signedOut }
        return .signedIn(
            userId: session.user.id.uuidString.lowercased(),
            isAnonymous: session.user.isAnonymous,
            email: session.user.email
        )
    }

    func ensureSignedIn() async -> AuthResult {
        guard let client = stack.client else { return .notConfigured }
        if client.auth.currentSession != nil {
            do {
                _ = try await client.auth.session
                return .success
            } catch is URLError {
                return .offline
            } catch is AuthError {
                // A stored session that the server no longer accepts falls through to a new anonymous user.
            } catch {
                return .failed(error.localizedDescription)
            }
        }
        do {
            _ = try await client.auth.signInAnonymously()
            return .success
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func linkGoogle(idToken: String, nonce: String?) async -> AuthResult {
        await google(idToken: idToken, nonce: nonce, link: true)
    }

    func signInWithGoogle(idToken: String, nonce: String?) async -> AuthResult {
        let result = await google(idToken: idToken, nonce: nonce, link: false)
        if result.isSuccess { await wipeUserData() }
        return result
    }

    func signOut() async -> AuthResult {
        guard let client = stack.client else { return .notConfigured }
        do {
            try await client.auth.signOut()
            await wipeUserData()
            return await ensureSignedIn()
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func google(idToken: String, nonce: String?, link: Bool) async -> AuthResult {
        guard let client = stack.client else { return .notConfigured }
        let credentials = OpenIDConnectCredentials(provider: .google, idToken: idToken, nonce: nonce)
        do {
            if link {
                _ = try await client.auth.linkIdentityWithIdToken(credentials: credentials)
            } else {
                _ = try await client.auth.signInWithIdToken(credentials: credentials)
            }
            return .success
        } catch let error as AuthError {
            if case .api(_, let code, _, _) = error, code == .identityAlreadyExists {
                return .googleAccountInUse
            }
            return .failed(error.localizedDescription)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

@MainActor
final class LiveFlightRepository: FlightRepository, TrackedKeysProviding {
    private let stack: SupabaseStack
    init(stack: SupabaseStack) { self.stack = stack }

    func trackedFlights() async -> [TrackedFlightDetails] {
        LocalCache.trackingRows(in: stack.context).compactMap { tracking in
            guard !tracking.isArchived, let flight = LocalCache.flight(id: tracking.flightId, in: stack.context) else { return nil }
            return TrackedFlightDetails(tracking: tracking, flight: flight)
        }.sorted { ($0.flight.bestDeparture ?? .distantFuture) < ($1.flight.bestDeparture ?? .distantFuture) }
    }

    func trackedFlight(flightId: String) async -> TrackedFlightDetails? {
        guard let tracking = LocalCache.trackingRows(in: stack.context).first(where: { $0.flightId == flightId }),
              let flight = LocalCache.flight(id: flightId, in: stack.context) else { return nil }
        return TrackedFlightDetails(tracking: tracking, flight: flight)
    }

    func flight(id: String) async -> Flight? { LocalCache.flight(id: id, in: stack.context) }

    func trackedKeys() async -> Set<TrackedKey> { LocalCache.trackedKeys(in: stack.context) }

    func search(flightText: String, date: LocalDate) async -> SearchResult {
        guard let number = parseFlightNumber(flightText) else { return .badInput(.flightNumber) }
        guard SearchWindow.contains(date, now: Date()) else { return .badInput(.date) }
        guard let client = stack.client else { return .notConfigured }
        do {
            let body = SearchRequest(flight: number.display, date: date.iso)
            let response: SearchResponse = try await client.functions.invoke(
                "search-flight",
                options: FunctionInvokeOptions(body: body)
            )
            let legs = response.flights.compactMap { $0.flight() }
            for leg in legs { LocalCache.save(flight: leg, in: stack.context) }
            return legs.isEmpty ? .notFound : .found(legs.sorted { ($0.bestDeparture ?? .distantFuture) < ($1.bestDeparture ?? .distantFuture) })
        } catch let error as FunctionsError {
            return searchFailure(error)
        } catch is URLError {
            return .offline
        } catch {
            return .providerError
        }
    }

    func track(flightId: String, extras: TrackExtras) async -> TrackResult {
        guard stack.client != nil else { return .notConfigured }
        guard let userId = stack.client?.auth.currentSession?.user.id.uuidString.lowercased() else { return .notConfigured }
        switch validate(extras) {
        case .invalid(let field): return .invalidInput(field)
        case .valid(let pnr, let seat, let note):
            let insert = UserFlightWrite(
                flight_id: flightId, pnr: pnr, seat: seat, cabin: extras.cabin?.wire,
                source: extras.source.wire, note: note, user_id: userId
            )
            do {
                let rows: [WireTracking] = try await stack.client!.from("user_flights")
                    .insert(insert, returning: .representation)
                    .execute()
                    .value
                guard let row = rows.first, let tracking = row.tracking() else { return .failed("empty insert") }
                LocalCache.save(tracking: tracking, in: stack.context)
                return .tracked(tracking)
            } catch let error as PostgrestError {
                if error.code == "23505" { return await alreadyTracked(flightId: flightId, pnr: pnr, seat: seat) }
                if error.code == "23503" { return .flightNotFound }
                if error.message.contains("tracking_limit") { return .trackingLimitReached(error.hint) }
                return .failed(error.message)
            } catch is URLError {
                return .offline
            } catch {
                return .failed(error.localizedDescription)
            }
        }
    }

    func untrack(flightId: String) async -> ActionResult {
        guard let tracking = await trackedFlight(flightId: flightId)?.tracking else { return .notFound }
        return await command {
            try await self.stack.client?.from("user_flights").delete().eq("id", value: tracking.id).execute()
            LocalCache.deleteTracking(id: tracking.id, in: self.stack.context)
        }
    }

    func archive(flightId: String, archived: Bool) async -> ActionResult {
        guard let tracking = LocalCache.trackingRows(in: stack.context).first(where: { $0.flightId == flightId }) else { return .notFound }
        let stamp = archived ? ISO8601DateFormatter().string(from: Date()) : nil
        return await command {
            let rows: [WireTracking] = try await self.stack.client!.from("user_flights")
                .update(ArchivePatch(archived_at: stamp))
                .eq("id", value: tracking.id)
                .execute()
                .value
            if let updated = rows.first?.tracking() { LocalCache.save(tracking: updated, in: self.stack.context) }
        }
    }

    func updateNotifyPrefs(flightId: String, prefs: [AlertType: Bool]) async -> ActionResult {
        guard let tracking = LocalCache.trackingRows(in: stack.context).first(where: { $0.flightId == flightId }) else { return .notFound }
        var raw: [String: Bool] = [:]
        for (type, enabled) in tracking.notifyPrefs { raw[type.wire] = enabled }
        let merged = mergeNotifyPrefs(raw, changes: prefs)
        return await command {
            let rows: [WireTracking] = try await self.stack.client!.from("user_flights")
                .update(PrefsPatch(notify_prefs: merged))
                .eq("id", value: tracking.id)
                .execute()
                .value
            if let updated = rows.first?.tracking() { LocalCache.save(tracking: updated, in: self.stack.context) }
        }
    }

    func updateTrackingDetails(flightId: String, extras: TrackExtras) async -> ActionResult {
        switch validate(extras) {
        case .invalid(let field): return .invalidInput(field)
        case .valid(let pnr, let seat, let note):
            guard let tracking = LocalCache.trackingRows(in: stack.context).first(where: { $0.flightId == flightId }) else { return .notFound }
            return await command {
                let rows: [WireTracking] = try await self.stack.client!.from("user_flights")
                    .update(DetailsPatch(pnr: pnr, seat: seat, cabin: extras.cabin?.wire, note: note))
                    .eq("id", value: tracking.id)
                    .execute()
                    .value
                if let updated = rows.first?.tracking() { LocalCache.save(tracking: updated, in: self.stack.context) }
            }
        }
    }

    func refresh() async -> ActionResult {
        guard let client = stack.client else { return .notConfigured }
        do {
            let rows: [WireTracking] = try await client.from("user_flights").select().execute().value
            let ids = rows.map(\.flight_id)
            var byId: [String: Flight] = [:]
            if !ids.isEmpty {
                let flights: [WireFlight] = try await client.from("flight_view").select().in("id", values: ids.map { $0 as any PostgrestFilterValue }).execute().value
                for wire in flights {
                    if let flight = wire.flight() {
                        byId[flight.id] = flight
                        LocalCache.save(flight: flight, in: stack.context)
                    }
                }
            }
            for row in rows {
                if let tracking = row.tracking() { LocalCache.save(tracking: tracking, in: stack.context) }
            }
            return .success
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func applyServerSync(flightId: String, compactFlightJSON: String?) async -> ActionResult {
        var changed = false
        if let compactFlightJSON, let merged = mergeCompactFlight(json: compactFlightJSON, into: LocalCache.flight(id: flightId, in: stack.context)) {
            LocalCache.save(flight: merged, in: stack.context)
            changed = true
        }
        let refreshed = await refreshOne(flightId)
        if refreshed.isSuccess || changed { return .success }
        return refreshed
    }

    private func refreshOne(_ flightId: String) async -> ActionResult {
        guard let client = stack.client else { return .notConfigured }
        do {
            let rows: [WireFlight] = try await client.from("flight_view").select().eq("id", value: flightId).execute().value
            if let flight = rows.first?.flight() { LocalCache.save(flight: flight, in: stack.context) }
            return .success
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func alreadyTracked(flightId: String, pnr: String?, seat: String?) async -> TrackResult {
        guard let client = stack.client else { return .notConfigured }
        do {
            let rows: [WireTracking] = try await client.from("user_flights").select().eq("flight_id", value: flightId).execute().value
            guard var tracking = rows.first?.tracking() else { return .failed("missing row") }
            if tracking.isArchived {
                let revived: [WireTracking] = try await client.from("user_flights")
                    .update(ArchivePatch(archived_at: nil))
                    .eq("id", value: tracking.id)
                    .execute()
                    .value
                tracking = revived.first?.tracking() ?? tracking
                LocalCache.save(tracking: tracking, in: stack.context)
                return .tracked(tracking)
            }
            LocalCache.save(tracking: tracking, in: stack.context)
            return .alreadyTracked(tracking)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func command(_ work: () async throws -> Void) async -> ActionResult {
        guard stack.client != nil else { return .notConfigured }
        do {
            try await work()
            return .success
        } catch let error as PostgrestError {
            if error.message.contains("tracking_limit") { return .trackingLimitReached }
            return .failed(error.message)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func searchFailure(_ error: FunctionsError) -> SearchResult {
        guard case .httpError(let status, let data) = error else { return .providerError }
        let body = (try? JSONDecoder().decode(FunctionErrorBody.self, from: data))
        if body?.error == "not_found" { return .notFound }
        if body?.error == "rate_limited" || status == 429 { return .rateLimited }
        if body?.error == "bad_input" {
            let message = body?.message ?? ""
            if message.localizedCaseInsensitiveContains("date") { return .badInput(.date) }
            if message.localizedCaseInsensitiveContains("flight") { return .badInput(.flightNumber) }
            return .badInput(.other)
        }
        return .providerError
    }
}

@MainActor
final class LiveProfileRepository: ProfileRepository {
    private let stack: SupabaseStack
    init(stack: SupabaseStack) { self.stack = stack }

    func profile() async -> UserProfile? { LocalCache.profile(in: stack.context) }

    func refresh() async -> ActionResult {
        guard let client = stack.client, let id = client.auth.currentSession?.user.id.uuidString.lowercased() else {
            return stack.client == nil ? .notConfigured : .success
        }
        do {
            let rows: [WireProfile] = try await client.from("profiles").select().eq("id", value: id).execute().value
            if let profile = rows.first?.profile() { LocalCache.save(profile: profile, in: stack.context) }
            return .success
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

@MainActor
final class LiveCloudParser: CloudTicketParsing {
    private let stack: SupabaseStack
    private let auth: AuthRepository
    init(stack: SupabaseStack, auth: AuthRepository) {
        self.stack = stack
        self.auth = auth
    }

    func parse(text: String) async -> CloudParseResult {
        let clipped = String(text.prefix(maxTicketCharacters))
        if clipped.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .failed }
        switch await auth.ensureSignedIn() {
        case .notConfigured: return .notConfigured
        case .offline: return .offline
        case .success: break
        default: return .failed
        }
        guard let client = stack.client else { return .notConfigured }
        do {
            let response: CloudWire = try await client.functions.invoke(
                "parse-ticket",
                options: FunctionInvokeOptions(body: ParseRequest(text: clipped))
            )
            return .parsed(CloudTicketResponse(pnr: response.pnr, flights: response.flights.map {
                CloudTicketFlight(flight: $0.flight, date: $0.date, from: $0.from, to: $0.to)
            }).validated())
        } catch let error as FunctionsError {
            guard case .httpError(let status, let data) = error else { return .failed }
            let body = try? JSONDecoder().decode(FunctionErrorBody.self, from: data)
            if body?.error == "rate_limited" || status == 429 { return .rateLimited }
            if body?.error == "not_configured" { return .notConfigured }
            return .failed
        } catch is URLError {
            return .offline
        } catch {
            return .failed
        }
    }
}

private enum Checked {
    case valid(String?, String?, String?)
    case invalid(TrackField)
}

private func validate(_ extras: TrackExtras) -> Checked {
    let pnr = extras.pnr.flatMap { $0.isEmpty ? nil : $0 }
    if let pnr, normalizePnr(pnr) == nil { return .invalid(.pnr) }
    if let seat = extras.seat, seat.count > TrackExtras.maxSeatLength { return .invalid(.seat) }
    if let note = extras.note, note.count > TrackExtras.maxNoteLength { return .invalid(.note) }
    return .valid(pnr.flatMap(normalizePnr), extras.seat.flatMap { $0.isEmpty ? nil : $0 }, extras.note.flatMap { $0.isEmpty ? nil : $0 })
}

private struct SearchRequest: Encodable { var flight: String; var date: String }
private struct ParseRequest: Encodable { var text: String }
struct FunctionErrorBody: Decodable { var error: String?; var message: String? }

private struct SearchResponse: Decodable {
    var flights: [WireFlight]
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        flights = try container.decodeIfPresent([WireFlight].self, forKey: .flights) ?? []
    }
    private enum CodingKeys: String, CodingKey { case flights }
}

private struct CloudWire: Decodable {
    var pnr: String?
    var flights: [CloudFlightWire]
    struct CloudFlightWire: Decodable { var flight: String?; var date: String?; var from: String?; var to: String? }
}

private struct UserFlightWrite: Encodable {
    var flight_id: String
    var pnr: String?
    var seat: String?
    var cabin: String?
    var source: String
    var note: String?
    var user_id: String
}

private struct ArchivePatch: Encodable { var archived_at: String? }
private struct PrefsPatch: Encodable { var notify_prefs: [String: Bool] }
private struct DetailsPatch: Encodable { var pnr: String?; var seat: String?; var cabin: String?; var note: String? }

private struct WireProfile: Decodable {
    var id: String
    var display_name: String?
    var plan: String?
    var pro_until: String?
    func profile() -> UserProfile {
        UserProfile(id: id, displayName: display_name, plan: Plan.fromWire(plan), proUntil: parseInstant(pro_until))
    }
}

private struct WireTracking: Decodable {
    var id: String
    var user_id: String
    var flight_id: String
    var pnr: String?
    var seat: String?
    var cabin: String?
    var source: String?
    var note: String?
    var notify_prefs: [String: Bool]?
    var archived_at: String?
    var created_at: String?
    func tracking() -> TrackedFlight? {
        var prefs: [AlertType: Bool] = [:]
        for (key, value) in notify_prefs ?? [:] {
            if let type = AlertType.fromWire(key) { prefs[type] = value }
        }
        return TrackedFlight(
            id: id, userId: user_id, flightId: flight_id, pnr: pnr, seat: seat,
            cabin: Cabin.fromWire(cabin), source: TrackSource.fromWire(source), notifyPrefs: prefs,
            note: note, archivedAt: parseInstant(archived_at), createdAt: parseInstant(created_at)
        )
    }
}

private struct WireFlight: Decodable {
    var id: String
    var carrier_iata: String
    var flight_number: String
    var flight_date: String
    var dep_iata: String
    var arr_iata: String
    var airline_name: String?
    var status: String?
    var sched_dep: String?
    var est_dep: String?
    var act_dep: String?
    var dep_terminal: String?
    var dep_gate: String?
    var dep_checkin: String?
    var sched_arr: String?
    var est_arr: String?
    var act_arr: String?
    var arr_terminal: String?
    var arr_gate: String?
    var baggage_belt: String?
    var aircraft_model: String?
    var aircraft_reg: String?
    var delay_reason: String?
    var live_lat: Double?
    var live_lon: Double?
    var live_alt_ft: Int?
    var live_heading: Double?
    var live_speed_kts: Double?
    var live_updated_at: String?
    var last_polled_at: String?
    var updated_at: String?
    var dep_name: String?
    var dep_city: String?
    var dep_tz: String?
    var dep_lat: Double?
    var dep_lon: Double?
    var arr_name: String?
    var arr_city: String?
    var arr_tz: String?
    var arr_lat: Double?
    var arr_lon: Double?
    var inbound_flight_id: String?
    var has_pro_insight: Bool?
    var prediction: WirePrediction?

    func flight() -> Flight? {
        guard let date = LocalDate.parse(flight_date) else { return nil }
        let live: LivePosition? = if let live_lat, let live_lon {
            LivePosition(lat: live_lat, lon: live_lon, altFt: live_alt_ft, headingDeg: live_heading, speedKts: live_speed_kts, at: parseInstant(live_updated_at))
        } else { nil }
        return Flight(
            id: id, carrierIata: carrier_iata, flightNumber: flight_number, flightDate: date,
            depIata: dep_iata, arrIata: arr_iata, airlineName: airline_name,
            status: FlightStatus.fromWire(status),
            schedDep: parseInstant(sched_dep), estDep: parseInstant(est_dep), actDep: parseInstant(act_dep),
            depTerminal: dep_terminal, depGate: dep_gate, depCheckin: dep_checkin,
            schedArr: parseInstant(sched_arr), estArr: parseInstant(est_arr), actArr: parseInstant(act_arr),
            arrTerminal: arr_terminal, arrGate: arr_gate, baggageBelt: baggage_belt,
            aircraftModel: aircraft_model, aircraftReg: aircraft_reg, delayReason: delay_reason,
            live: live, lastPolledAt: parseInstant(last_polled_at), updatedAt: parseInstant(updated_at),
            depAirport: airport(dep_iata, dep_name, dep_city, dep_tz, dep_lat, dep_lon),
            arrAirport: airport(arr_iata, arr_name, arr_city, arr_tz, arr_lat, arr_lon),
            inboundFlightId: inbound_flight_id, prediction: prediction?.prediction(),
            hasProInsight: has_pro_insight ?? false
        )
    }
}

private struct WirePrediction: Decodable {
    var computedAt: String?
    var predictedDep: String?
    var lateMin: Int?
    var reason: String?
    var inbound: WireInbound?
    func prediction() -> InboundPrediction {
        InboundPrediction(
            computedAt: parseInstant(computedAt), predictedDep: parseInstant(predictedDep),
            lateMin: lateMin ?? 0, reason: reason, inbound: inbound?.aircraft()
        )
    }
}

private struct WireInbound: Decodable {
    var flight: String?
    var from: String?
    var status: String?
    var schedArr: String?
    var estArr: String?
    var actArr: String?
    func aircraft() -> InboundAircraft {
        InboundAircraft(
            flight: flight ?? "", fromIata: from ?? "", status: FlightStatus.fromWire(status),
            schedArr: parseInstant(schedArr), estArr: parseInstant(estArr), actArr: parseInstant(actArr), live: nil
        )
    }
}

private func airport(_ iata: String, _ name: String?, _ city: String?, _ tz: String?, _ lat: Double?, _ lon: Double?) -> Airport? {
    guard let name, let tz, let lat, let lon else { return nil }
    return Airport(iata: iata, name: name, city: city, tz: tz, lat: lat, lon: lon)
}
