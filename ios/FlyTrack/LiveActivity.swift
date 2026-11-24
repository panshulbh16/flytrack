import ActivityKit
import FlyTrackCore
import Foundation
import Supabase

/// The lock-screen and Dynamic Island activity for a tracked flight. The app starts it, hands its push tokens to
/// the backend (register_live_activity), and ends it once the flight is done. The backend updates it in between
/// (fanOut → buildLiveActivityMessage in supabase/functions/_shared/notify.ts).
@MainActor
enum FlightLiveActivities {
    typealias FlightActivity = Activity<FlightActivityAttributes>

    /// nil = not offered (Live Activities are off, or the flight is done); otherwise whether one is running.
    static func offer(_ flightId: String, flights: FlightRepository) async -> Bool? {
        guard ActivityAuthorizationInfo().areActivitiesEnabled,
              let flight = await flights.flight(id: flightId),
              !FlightActivityAttributes.ContentState(flight).isDone else { return nil }
        return activity(flightId) != nil
    }

    /// Returns whether an activity is now running for the flight.
    static func start(_ flight: Flight, client: SupabaseClient?, push: LivePush) -> Bool {
        if activity(flight.id) != nil { return true }
        let attributes = FlightActivityAttributes(
            flightId: flight.id, carrier: flight.carrierIata, number: flight.flightNumber,
            dep: flight.depIata, arr: flight.arrIata, depTz: flight.depAirport?.tz, arrTz: flight.arrAirport?.tz
        )
        let content = ActivityContent(state: FlightActivityAttributes.ContentState(flight), staleDate: nil)
        guard let activity = try? FlightActivity.request(attributes: attributes, content: content, pushType: .token) else {
            return false
        }
        follow(activity, client: client, push: push)
        return true
    }

    static func stop(_ flightId: String) async {
        await activity(flightId)?.end(nil, dismissalPolicy: .immediate)
    }

    /// Sign-out deletes this phone's devices row and, by cascade, its live_activities rows, so the backend could no
    /// longer update or end these. The wiped cache also stops endFinished from finding their flights.
    static func stopAll() async {
        for activity in FlightActivity.activities { await activity.end(nil, dismissalPolicy: .immediate) }
    }

    /// Activities outlive the app process, so every launch picks the running ones up again.
    static func resume(client: SupabaseClient?, push: LivePush, flights: FlightRepository) async {
        for activity in FlightActivity.activities { follow(activity, client: client, push: push) }
        await endFinished(flights: flights)
    }

    /// Ends activities whose flight is done (landed with a belt, or cancelled), in case the backend's "end" did not
    /// arrive. Uses the cached flight, so it is only as fresh as the last sync.
    static func endFinished(flights: FlightRepository) async {
        for activity in FlightActivity.activities where activity.activityState == .active {
            guard let flight = await flights.flight(id: activity.attributes.flightId) else { continue }
            let state = FlightActivityAttributes.ContentState(flight)
            guard state.isDone else { continue }
            await activity.end(ActivityContent(state: state, staleDate: nil), dismissalPolicy: .after(.now + 3600))
        }
    }

    /// nonisolated: Activity is not Sendable, and one fetched on the main actor could not be handed to end().
    private nonisolated static func activity(_ flightId: String) -> FlightActivity? {
        FlightActivity.activities.first { $0.attributes.flightId == flightId && $0.activityState == .active }
    }

    private static func follow(_ activity: FlightActivity, client: SupabaseClient?, push: LivePush) {
        guard let client else { return }
        let flightId = activity.attributes.flightId
        Task {
            // The current token too: the backend drops the row when this phone's FCM token is replaced.
            if let token = activity.pushToken { await register(hex(token), flightId: flightId, client: client, push: push) }
            for await token in activity.pushTokenUpdates {
                await register(hex(token), flightId: flightId, client: client, push: push)
            }
        }
        Task {
            for await state in activity.activityStateUpdates where state == .ended || state == .dismissed {
                // Ended on the phone (swiped away, or by the app): stop the backend pushing to it.
                if let token = activity.pushToken {
                    _ = try? await client.from("live_activities").delete().eq("activity_token", value: hex(token)).execute()
                }
                break
            }
        }
    }

    private static func register(_ token: String, flightId: String, client: SupabaseClient, push: LivePush) async {
        guard let fcmToken = await push.currentToken() else { return }
        let params = RegisterLiveActivityParams(p_fcm_token: fcmToken, p_flight_id: flightId, p_activity_token: token)
        _ = try? await client.rpc("register_live_activity", params: params).execute()
    }

    private static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
}

private struct RegisterLiveActivityParams: Encodable {
    var p_fcm_token: String
    var p_flight_id: String
    var p_activity_token: String
}

extension FlightActivityAttributes.ContentState {
    init(_ flight: Flight) {
        self.init(
            status: flight.status.wire,
            schedDep: flight.schedDep,
            estDep: flight.actDep ?? flight.estDep,
            schedArr: flight.schedArr,
            estArr: flight.actArr ?? flight.estArr,
            depTerminal: flight.depTerminal,
            depGate: flight.depGate,
            belt: flight.baggageBelt,
            updatedAt: flight.updatedAt ?? .now
        )
    }
}
