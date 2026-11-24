import ActivityKit
import Foundation

/// A tracked flight on the lock screen and in the Dynamic Island. This file is compiled into the app,
/// which starts the activity, and into the FlightActivity extension, which draws it.
struct FlightActivityAttributes: ActivityAttributes {
    /// What changes during the flight. The backend sends it as `aps.content-state` (liveActivityState in
    /// supabase/functions/_shared/notify.ts), so keep the names in step. ActivityKit decodes it with
    /// JSONDecoder's defaults: dates arrive as seconds since 2001-01-01.
    struct ContentState: Codable, Hashable {
        var status: String // flights.status wire value, e.g. "boarding", "en_route"
        var schedDep: Date?
        var estDep: Date? // actual once the plane has left
        var schedArr: Date?
        var estArr: Date? // actual once it has landed
        var depTerminal: String?
        var depGate: String?
        var belt: String?
        var updatedAt: Date

        var isLanded: Bool { status == "landed" || status == "arrived" }
        var isAirborne: Bool { status == "departed" || status == "en_route" || status == "approaching" }
        /// Nothing left to wait for. liveActivityDone in notify.ts uses the same rule.
        var isDone: Bool { status == "cancelled" || (isLanded && belt != nil) }
    }

    var flightId: String
    var carrier: String
    var number: String
    var dep: String
    var arr: String
    /// Airport time zones, so times read as they do on the departures board.
    var depTz: String?
    var arrTz: String?
}
