import Foundation

/// Names from docs/P1_CONTRACTS.md section 2.
public enum ShareContract {
    /// Default of `SHARE_BASE_URL`. The token goes after `#`, so it never reaches the web server's logs.
    public static let defaultBaseURL = "https://heygate.app/f/"
    public static let rpcCreate = "create_flight_share"
    /// iOS URL scheme; `LSApplicationQueriesSchemes` must list it for `canOpenURL`.
    public static let whatsAppScheme = "whatsapp"
}

/// What the share message names: "6E 2341 DEL→BOM".
public struct ShareFlight: Hashable, Sendable {
    public var carrierIata: String
    public var flightNumber: String
    public var depIata: String
    public var arrIata: String

    public init(carrierIata: String, flightNumber: String, depIata: String, arrIata: String) {
        self.carrierIata = carrierIata
        self.flightNumber = flightNumber
        self.depIata = depIata
        self.arrIata = arrIata
    }

    public init(_ flight: Flight) {
        self.init(carrierIata: flight.carrierIata, flightNumber: flight.flightNumber, depIata: flight.depIata, arrIata: flight.arrIata)
    }

    public var designator: String { "\(carrierIata) \(flightNumber)" }
}

/// `{SHARE_BASE_URL}#<token>`. A blank or non-http(s) base falls back to the default; a fragment in the base is replaced.
public func shareURL(base: String?, token: String) -> String {
    var root = base?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if let hash = root.firstIndex(of: "#") { root = String(root[..<hash]) }
    let lower = root.lowercased()
    if root.isEmpty || !(lower.hasPrefix("https://") || lower.hasPrefix("http://")) || URL(string: root)?.host == nil {
        root = ShareContract.defaultBaseURL
    }
    return "\(root)#\(token.trimmingCharacters(in: .whitespacesAndNewlines))"
}

/// The message for WhatsApp or the share sheet: the Hinglish line, then the English line with the link (the app
/// localises both; see `shareMessage` callers). Blank lines are dropped.
public func shareMessage(lines: [String]) -> String {
    lines.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.joined(separator: "\n")
}

/// `whatsapp://send?text=<message>`, percent-encoded so `&`, `+`, `#` and newlines survive.
public func whatsAppSendURL(text: String) -> URL? {
    // RFC 3986 unreserved characters only. CharacterSet.alphanumerics would also let non-ASCII letters through.
    let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    guard let encoded = text.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
    return URL(string: "\(ShareContract.whatsAppScheme)://send?text=\(encoded)")
}

public enum ShareTokenResult: Equatable, Sendable {
    case token(String)
    /// The caller does not track that flight (the RPC refuses).
    case notTracked
    case offline
    case notConfigured
    case failed(String?)
}

/// `create_flight_share(p_flight_id)`: the caller's active token for that flight, created when missing.
@MainActor
public protocol FlightSharing: AnyObject {
    func createShare(flightId: String) async -> ShareTokenResult
}

/// A link is ready: the screen builds the localised text and opens WhatsApp or the share sheet.
public struct FlightShareReady: Hashable, Sendable, Identifiable {
    public var flight: ShareFlight
    public var url: String
    public var id: String { url }

    public init(flight: ShareFlight, url: String) {
        self.flight = flight
        self.url = url
    }
}
