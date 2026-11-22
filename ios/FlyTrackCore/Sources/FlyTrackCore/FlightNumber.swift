import Foundation

/// A parsed flight designator. `carrier` is the 2-character IATA airline code. `number` has no leading zeros.
public struct FlightNumber: Hashable, Sendable {
    public var carrier: String
    public var number: String

    public init(carrier: String, number: String) {
        self.carrier = carrier
        self.number = number
    }

    /// "6E 2341", the form the backend accepts and the UI shows.
    public var display: String { "\(carrier) \(number)" }
}

/// JavaScript's `\s`, spelled out. A no-break space in an airline email must be accepted, as the server accepts it.
/// The class is built from the characters themselves: ICU does not accept Swift's `\u{…}` syntax.
private let flightWhitespace = "[\t\n\u{000B}\u{000C}\r \u{00A0}\u{1680}\u{2000}-\u{200A}\u{2028}\u{2029}\u{202F}\u{205F}\u{3000}\u{FEFF}]"

/// Port of `parseFlightNumber` in supabase/functions/_shared/engine.ts.
/// "6E 2341", "6e2341", "AI-101", "6E 0234" -> 234, "AI 101A" -> 101A. Two-digit carriers are rejected.
public func parseFlightNumber(_ input: String) -> FlightNumber? {
    let pattern = "^\(flightWhitespace)*([A-Za-z0-9]{2})\(flightWhitespace)*-?\(flightWhitespace)*([0-9]{1,4})([A-Za-z]?)\(flightWhitespace)*$"
    guard let (ns, match) = RE.first(pattern, in: input) else { return nil }
    let carrier = RE.group(ns, match, 1).uppercased()
    if carrier.range(of: "^[0-9]{2}$", options: .regularExpression) != nil { return nil }
    let digits = RE.group(ns, match, 2)
    guard let value = Int(digits) else { return nil }
    let suffix = RE.group(ns, match, 3).uppercased()
    return FlightNumber(carrier: carrier, number: "\(value)\(suffix)")
}

public enum FlightInputCheck: String, Sendable, Equatable {
    case empty, incomplete, valid, invalid
}

public let maxFlightInputLength = 24

/// Live validation of the flight-number field. Only text that can no longer become a flight number is invalid while typing.
public func checkFlightInput(_ input: String) -> FlightInputCheck {
    if input.trimmingCharacters(in: flightCharacterSet).isEmpty { return .empty }
    if parseFlightNumber(input) != nil { return .valid }
    let pattern = "^\(flightWhitespace)*(?:[A-Za-z0-9]|([A-Za-z0-9]{2})\(flightWhitespace)*(?:-\(flightWhitespace)*)?(?:[0-9]{1,4}[A-Za-z]?\(flightWhitespace)*)?)?$"
    guard let (ns, match) = RE.first(pattern, in: input) else { return .invalid }
    let carrier = RE.group(ns, match, 1)
    if carrier.range(of: "^[0-9]{2}$", options: .regularExpression) != nil { return .invalid }
    return .incomplete
}

private let flightCharacterSet = CharacterSet.whitespacesAndNewlines
