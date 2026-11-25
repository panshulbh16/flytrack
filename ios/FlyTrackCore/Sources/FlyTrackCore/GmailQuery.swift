import Foundation

/// The Gmail search for booking emails. Gmail matches whole words, so each variant is listed.
public enum GmailQuery {
    public static let terms = #"subject:(ticket OR e-ticket OR itinerary OR booking OR PNR OR confirmation OR "boarding pass") -subject:(cancel OR cancelled OR canceled OR cancellation OR refund OR refunded)"#

    /// Until a scan completes: the last 180 days. After that: since the last completed scan, with one hour of overlap.
    public static func build(lastCheckEpochSeconds: Int64?) -> String {
        guard let last = lastCheckEpochSeconds else { return terms + " newer_than:180d" }
        return terms + " after:\(last - 3600)"
    }
}
