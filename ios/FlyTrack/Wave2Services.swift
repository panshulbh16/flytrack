import FlyTrackCore
import Foundation
import Supabase
import UserNotifications

// Backend adapters and device services for the P2 wave 2 contract (docs/P2_WAVE2_CONTRACTS.md): friends (1), the
// email forwarding address (2), the calendar feed (3), the arrival forecast (4) and the "time to leave" reminder (5).
// Every adapter returns `.notConfigured` when the xcconfig keys are blank and never throws.

/// Runs one backend call after making sure the user is signed in (anonymous sign-in included) and turns every error
/// into a `RemoteResult`. Same role as Android's `Wave2Caller`.
@MainActor
final class Wave2Backend {
    private let stack: SupabaseStack
    private let auth: LiveAuthRepository

    init(stack: SupabaseStack, auth: LiveAuthRepository) {
        self.stack = stack
        self.auth = auth
    }

    /// `work` gets the client and the signed-in user's id (lower-case uuid).
    func call<T: Sendable>(_ work: (SupabaseClient, String) async throws -> T) async -> RemoteResult<T> {
        guard let client = stack.client else { return .notConfigured }
        switch await auth.ensureSignedIn() {
        case .success, .googleAccountInUse: break
        case .notConfigured: return .notConfigured
        case .offline: return .offline
        case .failed(let message): return .failed(message)
        }
        guard let userId = client.auth.currentSession?.user.id.uuidString.lowercased() else { return .failed("no user after sign-in") }
        do {
            return .ok(try await work(client, userId))
        } catch let error as PostgrestError {
            return .refused(code: error.code, message: error.message)
        } catch is URLError {
            return .offline
        } catch {
            return .failed(String(describing: type(of: error)))
        }
    }
}

/// The answer was not what the contract says (a malformed body): reported as `.failed`.
private struct UnexpectedAnswer: Error {}

private struct FriendCodeParams: Encodable { var p_code: String }
private struct FriendParams: Encodable { var p_friend: String }
private struct FriendAlertParams: Encodable { var p_friend: String; var p_enabled: Bool }
private struct ForecastParams: Encodable { var p_flight_id: String }
private struct SharePatch: Encodable { var share_with_friends: Bool }

/// Friends RPCs (contract section 1) and `user_flights.share_with_friends`.
@MainActor
final class LiveFriends: FriendsRepository {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func createInvite() async -> RemoteResult<String> {
        await backend.call { client, _ in
            let data = try await client.rpc("create_friend_invite").execute().data
            guard let code = rpcTextAnswer(data) else { throw UnexpectedAnswer() }
            return code
        }
    }

    func acceptInvite(code: String) async -> AcceptInviteResult {
        guard let normalized = normalizeInviteCode(code) else { return .invalidCode }
        let result: RemoteResult<AcceptedFriend> = await backend.call { client, _ in
            let data = try await client.rpc("accept_friend_invite", params: FriendCodeParams(p_code: normalized)).execute().data
            guard let friend = parseAcceptedFriend(data) else { throw UnexpectedAnswer() }
            return friend
        }
        return acceptInviteResult(result)
    }

    func friends() async -> RemoteResult<[Friend]> {
        await backend.call { client, _ in
            guard let list = parseFriends(try await client.rpc("my_friends").execute().data) else { throw UnexpectedAnswer() }
            return list
        }
    }

    func removeFriend(_ friendId: String) async -> RemoteResult<Bool> {
        await backend.call { client, _ in
            _ = try await client.rpc("remove_friend", params: FriendParams(p_friend: friendId)).execute()
            return true
        }
    }

    func setFriendAlerts(_ friendId: String, enabled: Bool) async -> RemoteResult<Bool> {
        await backend.call { client, _ in
            _ = try await client.rpc("set_friend_alerts", params: FriendAlertParams(p_friend: friendId, p_enabled: enabled)).execute()
            return true
        }
    }

    func friendsFlights() async -> RemoteResult<[FriendFlight]> {
        await backend.call { client, _ in
            guard let list = parseFriendsFlights(try await client.rpc("friends_flights").execute().data) else { throw UnexpectedAnswer() }
            return list
        }
    }

    func shareWithFriends(flightId: String) async -> RemoteResult<Bool?> {
        await backend.call { client, userId in
            let data = try await client.from("user_flights").select("share_with_friends")
                .eq("user_id", value: userId).eq("flight_id", value: flightId)
                .execute().data
            return Self.shareFlag(data)
        }
    }

    func setShareWithFriends(flightId: String, share: Bool) async -> RemoteResult<Bool?> {
        await backend.call { client, userId in
            let data = try await client.from("user_flights").update(SharePatch(share_with_friends: share))
                .eq("user_id", value: userId).eq("flight_id", value: flightId)
                .select("share_with_friends")
                .execute().data
            return Self.shareFlag(data)
        }
    }

    /// The first row's flag (default true, as the column); nil when no row matched.
    private static func shareFlag(_ data: Data) -> Bool? {
        guard let rows = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]], let row = rows.first else { return nil }
        return (row["share_with_friends"] as? Bool) ?? true
    }
}

/// `my_forwarding_address()` (contract section 2).
@MainActor
final class LiveForwarding: ForwardingAddressSource {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func address() async -> RemoteResult<String> {
        await backend.call { client, _ in
            guard let address = rpcTextAnswer(try await client.rpc("my_forwarding_address").execute().data),
                  isForwardingAddress(address) else { throw UnexpectedAnswer() }
            return address
        }
    }
}

/// `my_calendar_url()` / `rotate_calendar_token()` (contract section 3).
@MainActor
final class LiveCalendarFeed: CalendarFeedSource {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func feedURL() async -> RemoteResult<String> { await url("my_calendar_url") }
    func rotate() async -> RemoteResult<String> { await url("rotate_calendar_token") }

    private func url(_ rpc: String) async -> RemoteResult<String> {
        await backend.call { client, _ in
            guard let url = rpcTextAnswer(try await client.rpc(rpc).execute().data), isFeedURL(url) else { throw UnexpectedAnswer() }
            return url
        }
    }
}

/// `flight_forecast(p_flight_id)` (contract section 4).
@MainActor
final class LiveForecast: ForecastSource {
    private let backend: Wave2Backend
    init(backend: Wave2Backend) { self.backend = backend }

    func forecast(flightId: String) async -> RemoteResult<FlightForecast?> {
        await backend.call { client, _ in
            try parseFlightForecast(try await client.rpc("flight_forecast", params: ForecastParams(p_flight_id: flightId)).execute().data)
        }
    }
}

/// Travel minutes per departure airport, on this phone only (contract section 5: "the apps remember it").
@MainActor
final class DeviceTravelTimes: TravelTimeStore {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    private func key(_ iata: String) -> String {
        "leave_travel_minutes_" + iata.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    func travelMinutes(iata: String) -> Int {
        guard let stored = defaults.object(forKey: key(iata)) as? NSNumber else { return LeaveRules.defaultTravelMin }
        return clampTravelMinutes(stored.intValue)
    }

    func setTravelMinutes(iata: String, minutes: Int) {
        defaults.set(clampTravelMinutes(minutes), forKey: key(iata))
    }
}

/// The "time to leave" notification at `leaveBy − 15 min`, scheduled on the device. One pending request per flight
/// (identifier `<flightId>:leave`), replaced when the plan moves. The tap opens the flight (`flight_id`).
@MainActor
final class LocalLeaveReminders: LeaveReminderScheduling {
    private let center = UNUserNotificationCenter.current()

    func schedule(flightId: String, plan: LeavePlan, designator: String, depCity: String) async {
        let delay = plan.reminderAt.timeIntervalSinceNow
        guard delay > 0 else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Leave by \(plan.leaveByTime) for \(designator)")
        content.body = String(localized: "\(designator) departs at \(plan.departureTime). Allow \(plan.travelMinutes) min to reach \(depCity) airport.")
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        let identifier = LeaveReminderContract.identifier(flightId: flightId)
        content.threadIdentifier = identifier
        content.userInfo = [
            PushContract.keyFlightId: flightId,
            PushContract.keyKind: LeaveReminderContract.kindLeave,
            LeaveReminderContract.keyFog: plan.fog ? "1" : "0",
        ]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, delay), repeats: false)
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
    }

    func cancel(flightId: String) async {
        center.removePendingNotificationRequests(withIdentifiers: [LeaveReminderContract.identifier(flightId: flightId)])
    }

    /// Re-plans every pending reminder from the cached flight (a delay moves it, a cancellation removes it). Runs after
    /// a push about a flight and when the app comes to the foreground: iOS cannot re-check a plan when it fires. The
    /// fog flag comes from the pending request (Airport Health is not asked from here). Without Pro (or while the
    /// Pro state is not loaded) a pending reminder is left alone; the detail screen cancels it once it knows.
    func resync(flights: FlightRepository, travel: TravelTimeStore, pro: ProState) async {
        let pending = await center.pendingNotificationRequests().compactMap { request -> (flightId: String, fog: Bool)? in
            guard let id = LeaveReminderContract.flightId(fromIdentifier: request.identifier) else { return nil }
            return (id, request.content.userInfo[LeaveReminderContract.keyFog] as? String == "1")
        }
        for item in pending {
            guard pro.access(for: item.flightId).unlocked else { continue }
            let tracked = await flights.trackedFlight(flightId: item.flightId)
            let flight = tracked.flatMap { $0.tracking.isArchived ? nil : $0.flight }
            let minutes = travel.travelMinutes(iata: flight?.depIata ?? "")
            let action = replanReminder(flight: flight, travelMinutes: minutes, fog: item.fog, pro: true, now: Date())
            switch action {
            case .schedule:
                if let flight, let plan = leavePlan(flight: flight, travelMinutes: minutes, fogAtDep: item.fog, now: Date()) {
                    await schedule(flightId: item.flightId, plan: plan, designator: flight.designator,
                                   depCity: flight.depAirport?.displayCity ?? flight.depIata)
                }
            case .cancel:
                await cancel(flightId: item.flightId)
            case .keep:
                break
            }
        }
    }
}
