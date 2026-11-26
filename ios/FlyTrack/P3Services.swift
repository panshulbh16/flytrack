import EventKit
import FlyTrackCore
import Foundation
import Supabase
import UserNotifications

// Backend adapters and device services for the P3 search / social area (docs/P3_CONTRACTS.md): A3 calendar import
// (EventKit), B5 route search, B6 past flights, B10 friend Passports, B12 crew mode, B13 family sharing. Every adapter
// returns `.notConfigured` when the xcconfig keys are blank and never throws.

/// The P3 search / social services and the crew-mode flag screens read. Owned by `AppShell`.
@MainActor
final class P3Center: ObservableObject {
    let airports: BundledAirports
    let search: LiveP3Search
    let profileFlags: LiveProfileFlags
    let friendPassports: LiveFriendPassports
    let family: LiveFamily
    let calendar: DeviceCalendar
    let watch: WatchSender
    /// B13: the family Pro grant `ProService` reads (created with the shell, before this center).
    let familyGrant: FamilyGrantCache

    /// B12: crew mode for the signed-in user (device cache first, then the server). Hides Pro upsells, mutes the
    /// device's when-to-leave reminders, shows block times.
    @Published private(set) var crewMode: Bool
    /// B13: "Pro via {owner}'s family" (nil when the user's Pro is their own or they have none).
    @Published private(set) var proViaFamilyOwner: String? = nil

    init(stack: SupabaseStack, flights: LiveFlightRepository, backend: Wave2Backend, familyGrant: FamilyGrantCache) {
        self.familyGrant = familyGrant
        airports = BundledAirports()
        calendar = DeviceCalendar()
        watch = WatchSender()
        search = LiveP3Search(flights: flights)
        let flags = LiveProfileFlags(backend: backend, stack: stack)
        profileFlags = flags
        friendPassports = LiveFriendPassports(backend: backend)
        family = LiveFamily(backend: backend)
        crewMode = flags.cachedCrewMode()
        proViaFamilyOwner = familyGrant.familyGrant().map { $0.ownerName ?? "" }
        flags.onCrewModeChanged = { [weak self] on in self?.crewMode = on }
    }

    /// At launch and after a sign-in: the profile switches and the family's Pro source. Returns true when the family
    /// grant changed, so the caller reloads the Pro state (family members have no Pro of their own in `profiles`).
    @discardableResult
    func refresh() async -> Bool {
        _ = await profileFlags.refresh()
        crewMode = profileFlags.cachedCrewMode()
        guard case .ok(let answer) = await family.myFamily() else { return false }
        var via: FamilyInfo?
        if case .family(let info) = answer, info.proViaFamily { via = info }
        let changed = familyGrant.update(proViaFamily: via != nil, ownerName: via?.ownerName)
        proViaFamilyOwner = familyGrant.familyGrant().map { $0.ownerName ?? "" }
        return changed
    }

    /// Sign-out or a user switch: forget the previous user's switches.
    func clear() {
        profileFlags.forget()
        familyGrant.forget()
        crewMode = false
        proViaFamilyOwner = nil
        watch.clear()
    }
}

// MARK: - family Pro grant (B13)

/// The signed-in user's "Pro via a family" grant, cached per user in UserDefaults so `ProService` knows it at launch and
/// offline. Written from `my_family()` (`pro_via_family`); the server enforces family Pro on its own (`is_pro`).
@MainActor
final class FamilyGrantCache: FamilyGrantProviding {
    private let stack: SupabaseStack
    private let defaults = UserDefaults.standard
    private static let userKey = "p3.family_grant.user"
    private static let ownerKey = "p3.family_grant.owner"

    init(stack: SupabaseStack) { self.stack = stack }

    private var currentUserId: String? { stack.client?.auth.currentSession?.user.id.uuidString.lowercased() }

    func familyGrant() -> FamilyGrant? {
        guard let user = currentUserId, defaults.string(forKey: Self.userKey) == user else { return nil }
        let owner = defaults.string(forKey: Self.ownerKey)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return FamilyGrant(userId: user, ownerName: (owner?.isEmpty ?? true) ? nil : owner)
    }

    /// Stores the answer for the signed-in user. True when what `familyGrant()` returns changed.
    @discardableResult
    func update(proViaFamily: Bool, ownerName: String?) -> Bool {
        let before = familyGrant()
        if proViaFamily, let user = currentUserId {
            defaults.set(user, forKey: Self.userKey)
            defaults.set(ownerName ?? "", forKey: Self.ownerKey)
        } else {
            forget()
        }
        return familyGrant() != before
    }

    func forget() {
        defaults.removeObject(forKey: Self.userKey)
        defaults.removeObject(forKey: Self.ownerKey)
    }
}

// MARK: - airports (A3, B5)

/// `airports.json` from the app bundle (a copy of Android's assets/airports.json), read once off the main actor.
@MainActor
final class BundledAirports: AirportCatalogLoading {
    private var cached: AirportList?

    func load() async -> AirportList {
        if let cached { return cached }
        let list = await Task.detached(priority: .userInitiated) { () -> AirportList in
            guard let url = Bundle.main.url(forResource: "airports", withExtension: "json"),
                  let data = try? Data(contentsOf: url) else { return .empty }
            return AirportList.parse(data) ?? .empty
        }.value
        if !list.isEmpty { cached = list }
        return list
    }
}

// MARK: - route search (B5) and past flights (B6)

private struct RouteSearchRequest: Encodable {
    var dep: String
    var arr: String?
    var date: String
    var airline: String?
}

private struct PastSearchRequest: Encodable {
    var flight: String
    var date: String
    var mode = "past"
}

/// `user_flights` insert of a past flight: `source = 'manual'`, `archived_at = now()` (contract B6).
private struct PastFlightWrite: Encodable {
    var flight_id: String
    var user_id: String
    var source = TrackSource.manual.wire
    var archived_at: String
    var pnr: String?
    var seat: String?
}

/// `search-route` and `search-flight` in past mode. Results are cached like add-flight's, so tapping one opens the
/// normal detail screen.
@MainActor
final class LiveP3Search: RouteFlightSearching, PastFlightSearching {
    private let flights: LiveFlightRepository
    init(flights: LiveFlightRepository) { self.flights = flights }

    private var stack: SupabaseStack { flights.stack }

    func searchRoute(_ query: RouteSearchQuery) async -> SearchResult {
        let q = query.normalized()
        guard q.isValid else { return .badInput(.other) }
        guard RouteSearchWindow.contains(q.date, now: Date()) else { return .badInput(.date) }
        return await invoke("search-route", body: RouteSearchRequest(dep: q.dep, arr: q.arr, date: q.date.iso, airline: q.airline))
    }

    func searchPast(flightText: String, date: LocalDate) async -> SearchResult {
        guard let number = parseFlightNumber(flightText) else { return .badInput(.flightNumber) }
        guard PastSearchWindow.contains(date, now: Date()) else { return .badInput(.date) }
        return await invoke("search-flight", body: PastSearchRequest(flight: number.display, date: date.iso))
    }

    func trackPast(flightId: String, extras: TrackExtras) async -> TrackResult {
        guard let client = stack.client else { return .notConfigured }
        guard let userId = client.auth.currentSession?.user.id.uuidString.lowercased() else { return .notConfigured }
        let pnr = extras.pnr.flatMap { $0.isEmpty ? nil : normalizePnr($0) }
        if let typed = extras.pnr, !typed.isEmpty, pnr == nil { return .invalidInput(.pnr) }
        if let seat = extras.seat, seat.count > TrackExtras.maxSeatLength { return .invalidInput(.seat) }
        let write = PastFlightWrite(
            flight_id: flightId, user_id: userId, archived_at: ISO8601DateFormatter().string(from: Date()),
            pnr: pnr, seat: extras.seat.flatMap { $0.isEmpty ? nil : $0 }
        )
        do {
            let rows: [WireTracking] = try await client.from("user_flights").insert(write, returning: .representation).execute().value
            guard let tracking = rows.first?.tracking() else { return .failed("empty insert") }
            LocalCache.save(tracking: tracking, in: stack.context)
            return .tracked(tracking)
        } catch let error as PostgrestError {
            // Already in the list: open that row as it is. The normal track path would un-archive an archived row,
            // which would put a past flight back among the upcoming ones.
            if error.code == "23505" { return await existingTracking(flightId: flightId, client: client) }
            if error.code == "23503" { return .flightNotFound }
            if error.message.contains("tracking_limit") { return .trackingLimitReached(error.hint) }
            return .failed(error.message)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// The caller's existing `user_flights` row for a flight, unchanged (archived stays archived).
    private func existingTracking(flightId: String, client: SupabaseClient) async -> TrackResult {
        do {
            let rows: [WireTracking] = try await client.from("user_flights").select().eq("flight_id", value: flightId).execute().value
            guard let tracking = rows.first?.tracking() else { return .failed("missing row") }
            LocalCache.save(tracking: tracking, in: stack.context)
            return .alreadyTracked(tracking)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    private func invoke(_ function: String, body: some Encodable & Sendable) async -> SearchResult {
        guard let client = stack.client else { return .notConfigured }
        do {
            let response: SearchResponse = try await client.functions.invoke(function, options: FunctionInvokeOptions(body: body))
            let legs = response.flights.compactMap { $0.flight() }
            for leg in legs { LocalCache.save(flight: leg, in: stack.context) }
            return legs.isEmpty ? .notFound : .found(legs)
        } catch let error as FunctionsError {
            return flights.searchFailure(error)
        } catch is URLError {
            return .offline
        } catch {
            return .providerError
        }
    }
}

// MARK: - profile switches (B10, B12)

private struct CrewModePatch: Encodable { var crew_mode: Bool }
private struct SharePassportPatch: Encodable { var share_passport_with_friends: Bool }

/// `profiles.crew_mode` and `profiles.share_passport_with_friends`. Crew mode is cached per user in UserDefaults so
/// screens and the leave-reminder resync know it offline and at once.
@MainActor
final class LiveProfileFlags: ProfileFlagsRepository {
    private let backend: Wave2Backend
    private let stack: SupabaseStack
    private let defaults = UserDefaults.standard
    private static let key = "p3.crew_mode.user"
    var onCrewModeChanged: ((Bool) -> Void)?

    init(backend: Wave2Backend, stack: SupabaseStack) {
        self.backend = backend
        self.stack = stack
    }

    private var currentUserId: String? { stack.client?.auth.currentSession?.user.id.uuidString.lowercased() }

    func cachedCrewMode() -> Bool {
        guard let user = currentUserId else { return false }
        return defaults.string(forKey: Self.key) == "\(user):1"
    }

    func forget() { defaults.removeObject(forKey: Self.key) }

    func refresh() async -> RemoteResult<ProfileFlags> {
        await run { client, userId in
            try await client.from("profiles").select(ProfileFlags.columns).eq("id", value: userId).execute().data
        }
    }

    func setCrewMode(_ enabled: Bool) async -> RemoteResult<ProfileFlags> {
        await run { client, userId in
            try await client.from("profiles").update(CrewModePatch(crew_mode: enabled)).eq("id", value: userId)
                .select(ProfileFlags.columns).execute().data
        }
    }

    func setSharePassport(_ enabled: Bool) async -> RemoteResult<ProfileFlags> {
        await run { client, userId in
            try await client.from("profiles").update(SharePassportPatch(share_passport_with_friends: enabled)).eq("id", value: userId)
                .select(ProfileFlags.columns).execute().data
        }
    }

    private func run(_ work: (SupabaseClient, String) async throws -> Data) async -> RemoteResult<ProfileFlags> {
        let result: RemoteResult<ProfileFlags> = await backend.call { client, userId in
            guard let flags = parseProfileFlags(try await work(client, userId)) else { throw P3UnexpectedAnswer() }
            return flags
        }
        if case .ok(let flags) = result, let user = currentUserId {
            defaults.set("\(user):\(flags.crewMode ? 1 : 0)", forKey: Self.key)
            onCrewModeChanged?(flags.crewMode)
        }
        return result
    }
}

// MARK: - friend Passport (B10)

private struct FriendPassportParams: Encodable {
    var p_friend: String
    var p_year: Int?

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(p_friend, forKey: .p_friend)
        try container.encode(p_year, forKey: .p_year) // explicit null: the RPC's default (overview rules)
    }

    private enum CodingKeys: String, CodingKey { case p_friend, p_year }
}

@MainActor
final class LiveFriendPassports: FriendPassportSource {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func friendPassport(friendId: String, year: Int?) async -> FriendPassportResult {
        let fallback = year ?? passportToday(Date()).year
        let result: RemoteResult<FriendPassport> = await backend.call { client, _ in
            let data = try await client.rpc("friend_passport", params: FriendPassportParams(p_friend: friendId, p_year: year)).execute().data
            guard let passport = parseFriendPassport(data, fallbackYear: fallback) else { throw P3UnexpectedAnswer() }
            return passport
        }
        return friendPassportResult(result)
    }
}

// MARK: - family (B13)

private struct FamilyCodeParams: Encodable { var p_code: String }
private struct FamilyUserParams: Encodable { var p_user: String }

@MainActor
final class LiveFamily: FamilyRepository {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func myFamily() async -> RemoteResult<FamilyAnswer> {
        await backend.call { client, _ in
            guard let answer = parseFamily(try await client.rpc("my_family").execute().data) else { throw P3UnexpectedAnswer() }
            return answer
        }
    }

    func createInvite() async -> RemoteResult<String> {
        await backend.call { client, _ in
            guard let code = rpcTextAnswer(try await client.rpc("create_family_invite").execute().data) else { throw P3UnexpectedAnswer() }
            return code
        }
    }

    func acceptInvite(code: String) async -> RemoteResult<JoinedFamily> {
        await backend.call { client, _ in
            let data = try await client.rpc("accept_family_invite", params: FamilyCodeParams(p_code: code)).execute().data
            guard let joined = parseJoinedFamily(data) else { throw P3UnexpectedAnswer() }
            return joined
        }
    }

    func leave() async -> RemoteResult<Bool> {
        await backend.call { client, _ in
            _ = try await client.rpc("leave_family").execute()
            return true
        }
    }

    func removeMember(_ userId: String) async -> RemoteResult<Bool> {
        await backend.call { client, _ in
            _ = try await client.rpc("remove_family_member", params: FamilyUserParams(p_user: userId)).execute()
            return true
        }
    }
}

/// The answer was not what the contract says: reported as `.failed`.
struct P3UnexpectedAnswer: Error {}

// MARK: - device calendar (A3)

/// Read-only EventKit access. Full access is asked only when the user taps "Scan my calendar" (contract A3); the
/// Info.plist reason is `NSCalendarsFullAccessUsageDescription`.
@MainActor
final class DeviceCalendar: DeviceCalendarReading {
    /// A busy calendar has a few thousand events a year; more than this is cut (the screen says so).
    static let maxEvents = 5_000
    private var store = EKEventStore()

    func authorization() -> CalendarAuthorization {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .notDetermined: return .notDetermined
        case .fullAccess: return .fullAccess
        case .writeOnly: return .writeOnly
        case .restricted: return .restricted
        case .denied: return .denied
        default: return .denied
        }
    }

    func requestFullAccess() async -> Bool {
        do {
            let granted = try await store.requestFullAccessToEvents()
            // A store created before access was granted does not see events; start from a fresh one.
            if granted { store = EKEventStore() }
            return granted
        } catch {
            return false
        }
    }

    func events(from: Date, to: Date) async -> DeviceCalendarRead {
        guard authorization() == .fullAccess else { return .noPermission }
        let calendars = store.calendars(for: .event)
        if calendars.isEmpty { return .events([], truncated: false) }
        let predicate = store.predicateForEvents(withStart: from, end: to, calendars: calendars)
        let found = store.events(matching: predicate)
        let truncated = found.count > Self.maxEvents
        let events = found.prefix(Self.maxEvents).map(Self.calendarEvent)
        return .events(Array(events), truncated: truncated)
    }

    /// EventKit event → the parser's Google-shaped event. Times are UTC instants; all-day events carry their date.
    /// The id adds the occurrence start, since every occurrence of a recurring event shares one identifier.
    nonisolated private static func calendarEvent(_ event: EKEvent) -> CalendarEvent {
        let start = event.startDate ?? Date()
        let identifier = event.calendarItemIdentifier
        let id = "\(identifier):\(Int64(start.timeIntervalSince1970))"
        let time: (Date?) -> CalendarEventTime? = { date in
            guard let date else { return nil }
            if event.isAllDay {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = event.timeZone ?? TimeZone.current
                let c = calendar.dateComponents([.year, .month, .day], from: date)
                return CalendarEventTime(date: String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0))
            }
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime]
            formatter.timeZone = TimeZone(secondsFromGMT: 0)
            return CalendarEventTime(dateTime: formatter.string(from: date))
        }
        return CalendarEvent(
            id: id,
            summary: event.title,
            description: event.hasNotes ? event.notes : nil,
            location: event.location ?? event.structuredLocation?.title,
            start: time(event.startDate),
            end: time(event.endDate),
            status: event.status == .canceled ? "cancelled" : nil,
            eventType: event.birthdayContactIdentifier != nil ? "birthday" : nil
        )
    }
}

// MARK: - crew mode and leave reminders (B12)

extension AppShell {
    /// Crew mode mutes when-to-leave: every pending local reminder goes.
    func cancelAllLeaveReminders() async {
        let pending = await UNUserNotificationCenter.current().pendingNotificationRequests()
        for id in pending.compactMap({ LeaveReminderContract.flightId(fromIdentifier: $0.identifier) }) {
            await leaveReminders.cancel(flightId: id)
        }
    }
}
