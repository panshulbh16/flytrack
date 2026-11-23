import Foundation

public struct ParsedFlight: Hashable, Sendable {
    public var flight: FlightNumber
    public var date: LocalDate?
    public var fromIata: String?
    public var toIata: String?

    public init(flight: FlightNumber, date: LocalDate?, fromIata: String?, toIata: String?) {
        self.flight = flight
        self.date = date
        self.fromIata = fromIata
        self.toIata = toIata
    }
}

public struct ParsedTicket: Hashable, Sendable {
    public var flights: [ParsedFlight]
    public var pnr: String?

    public init(flights: [ParsedFlight], pnr: String?) {
        self.flights = flights
        self.pnr = pnr
    }
}

public let indianCarriers: Set<String> = ["6E", "AI", "IX", "QP", "SG", "UK", "G8", "I5", "9I"]

private let notACarrier: Set<String> = [
    "ON", "TO", "AT", "IN", "OF", "NO", "OR", "AN", "BY", "IS", "AM", "PM", "IF", "WE", "MY",
    "DO", "GO", "UP", "SO", "AS", "BE", "IT", "US",
]

private let months: [String: Int] = [
    "JAN": 1, "JANUARY": 1, "FEB": 2, "FEBRUARY": 2, "MAR": 3, "MARCH": 3, "APR": 4, "APRIL": 4,
    "MAY": 5, "JUN": 6, "JUNE": 6, "JUL": 7, "JULY": 7, "AUG": 8, "AUGUST": 8,
    "SEP": 9, "SEPT": 9, "SEPTEMBER": 9, "OCT": 10, "OCTOBER": 10, "NOV": 11, "NOVEMBER": 11,
    "DEC": 12, "DECEMBER": 12,
]

private let notIATA: Set<String> = Set(months.keys).union([
    "THE", "AND", "FOR", "NOT", "YOU", "ARE", "WAS", "HAS", "HAD", "HIS", "HER", "OUR", "OUT", "OFF",
    "ALL", "ANY", "NEW", "ONE", "TWO", "GST", "INR", "USD", "PDF", "SMS", "OTP", "PNR", "REF", "VIA",
    "NON", "YES", "DAY", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "TAX", "FEE", "BAG", "WEB",
    "APP", "ROW", "ETA", "ETD", "STD", "STA", "MR", "MRS", "MS",
])

private let flightToken = #"(?<![A-Z0-9])([A-Z0-9]{2})[ \t]*-?[ \t]*([0-9]{1,4})([A-Z])?(?![A-Z0-9])"#
private let ocrFlight = #"(?<![A-Z0-9])([A-Z0-9]{2})([ \t]*)-?([ \t]*)([0-9ILO]*[0-9][0-9ILO]*)([A-Z])?(?![A-Z0-9])"#
private let notADesignator = #"(?!\s+(?!\d\d)[A-Z0-9]{2}\s*-?\s*\d)"#
private let airlineNumbers: [(String, String)] = [
    (#"\bAIR\s+INDIA\s+EXPRESS(?:\s+FLIGHT)?"# + notADesignator + #"\s+(\d{1,4})([A-Z])?\b"#, "IX"),
    (#"\bAIR\s+INDIA(?:\s+FLIGHT)?"# + notADesignator + #"\s+(\d{1,4})([A-Z])?\b"#, "AI"),
    (#"\bINDIGO(?:\s+FLIGHT)?"# + notADesignator + #"\s+(\d{1,4})([A-Z])?\b"#, "6E"),
    (#"\bAKASA(?:\s+AIR)?(?:\s+FLIGHT)?"# + notADesignator + #"\s+(\d{1,4})([A-Z])?\b"#, "QP"),
    (#"\bSPICE\s*JET(?:\s+FLIGHT)?"# + notADesignator + #"\s+(\d{1,4})([A-Z])?\b"#, "SG"),
]
private let dateWithYear = #"\b(\d{1,2})\s*([A-Z]{3,9})\s*(\d{2,4})\b"#
private let dateWeekday = #"\b(?:MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*,?\s+(\d{1,2})\s+([A-Z]{3,9})\b"#
private let dateDayMonth = #"\b(\d{1,2})\s+([A-Z]{3,9})\b"#
private let dateSlash = #"\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b"#
private let pnrLabel = #"\b(?:PNR|BOOKING\s+(?:REFERENCE|REF|ID|CODE)|CONFIRMATION(?:\s+(?:CODE|NUMBER))?|RECORD\s+LOCATOR)\b(?:\s*(?:NO\.?|NUMBER|CODE))?\s*[:#\-]*\s*([A-Z0-9]{6})\b"#
private let iataPair = #"(?<![A-Z])([A-Z]{3})\s*(?:→|>|-|/|TO)\s*([A-Z]{3})(?![A-Z])"#
private let iataParen = #"\(([A-Z]{3})\)"#

/// Reads flight numbers, a booking reference and dates out of ticket text.
/// `today` is only used when a date has no year.
public func parseTicketText(_ text: String, today: LocalDate) -> ParsedTicket {
    let normalized = normalizeTicketText(text)
    let flights = findFlights(normalized)
    let dates = findDates(normalized, today: today)
    let paired = pairFlights(normalized, flights: flights, dates: dates)
        .sorted { lhs, rhs in
            let lhsIndian = indianCarriers.contains(lhs.flight.carrier)
            let rhsIndian = indianCarriers.contains(rhs.flight.carrier)
            if lhsIndian != rhsIndian { return lhsIndian && !rhsIndian }
            return lhs.order < rhs.order
        }
        .map { ParsedFlight(flight: $0.flight, date: $0.date, fromIata: $0.from, toIata: $0.to) }
    var seen = Set<String>()
    let unique = paired.filter { item in
        let key = "\(item.flight.display)|\(item.date?.iso ?? "")|\(item.fromIata ?? "")"
        return seen.insert(key).inserted
    }
    return ParsedTicket(flights: unique, pnr: findPnr(normalized))
}

public func normalizeTicketText(_ raw: String) -> String {
    var text = raw
        .replacingOccurrences(of: "\u{00A0}", with: " ")
        .replacingOccurrences(of: "\u{200B}", with: " ")
        .replacingOccurrences(of: "–", with: "-")
        .replacingOccurrences(of: "—", with: "-")
        .replacingOccurrences(of: "→", with: ">")
    text = text.replacingOccurrences(of: "lndigo", with: "INDIGO", options: .caseInsensitive)
    text = text.replacingOccurrences(of: "1ndigo", with: "INDIGO", options: .caseInsensitive)
    text = text.uppercased(with: Locale(identifier: "en_US_POSIX"))
    text = text.replacingOccurrences(of: #"\bN0V\b"#, with: "NOV", options: .regularExpression)
    text = text.replacingOccurrences(of: #"\b0CT\b"#, with: "OCT", options: .regularExpression)
    text = text.replacingOccurrences(of: #"\bDE0\b"#, with: "DEC", options: .regularExpression)
    return replaceOCRDigits(text)
}

private func replaceOCRDigits(_ text: String) -> String {
    let expression = RE.regex(ocrFlight)
    let ns = NSMutableString(string: text)
    let matches = expression.matches(in: ns as String, options: [], range: NSRange(location: 0, length: ns.length))
    for match in matches.reversed() {
        let digits = RE.group(ns, match, 4).map { character -> Character in
            switch character {
            case "I", "L": return "1"
            case "O": return "0"
            default: return character
            }
        }
        let replacement = RE.group(ns, match, 1) + RE.group(ns, match, 2) + RE.group(ns, match, 3) + String(digits) + RE.group(ns, match, 5)
        ns.replaceCharacters(in: match.range, with: replacement)
    }
    return ns as String
}

private struct FlightHit {
    var start: Int
    var end: Int
    var flight: FlightNumber
}

private func findFlights(_ text: String) -> [FlightHit] {
    var found: [FlightHit] = []
    for (ns, match) in RE.matches(flightToken, in: text) {
        let digits = RE.group(ns, match, 2)
        let trimmed = String(digits.drop(while: { $0 == "0" }))
        let number = trimmed.isEmpty ? "0" : trimmed
        let candidate = RE.group(ns, match, 1) + " " + number + RE.group(ns, match, 3)
        guard let parsed = parseFlightNumber(candidate), !notACarrier.contains(parsed.carrier) else { continue }
        found.append(FlightHit(start: match.range.location, end: match.range.location + match.range.length, flight: parsed))
    }
    for (pattern, carrier) in airlineNumbers {
        for (ns, match) in RE.matches(pattern, in: text) {
            let digits = RE.group(ns, match, 1)
            let trimmed = String(digits.drop(while: { $0 == "0" }))
            let number = (trimmed.isEmpty ? "0" : trimmed) + RE.group(ns, match, 2)
            guard let parsed = parseFlightNumber("\(carrier) \(number)") else { continue }
            let near = found.contains { $0.flight == parsed && abs($0.start - match.range.location) < 40 }
            if near { continue }
            found.append(FlightHit(start: match.range.location, end: match.range.location + match.range.length, flight: parsed))
        }
    }
    var seen = Set<String>()
    let unique = found.filter { hit in
        seen.insert("\(hit.start)|\(hit.flight.display)").inserted
    }
    return unique.sorted { $0.start < $1.start }.prefix(8).map { $0 }
}

private struct Hit<T> {
    var start: Int
    var end: Int
    var value: T
}

private func findDates(_ text: String, today: LocalDate) -> [Hit<LocalDate>] {
    var hits: [Hit<LocalDate>] = []
    func add(_ match: NSTextCheckingResult, _ date: LocalDate?) {
        if let date {
            hits.append(Hit(start: match.range.location, end: match.range.location + match.range.length, value: date))
        }
    }
    for (ns, match) in RE.matches(dateWithYear, in: text) {
        add(match, dateFromParts(RE.group(ns, match, 1), RE.group(ns, match, 2), RE.group(ns, match, 3), today: today))
    }
    for (ns, match) in RE.matches(dateSlash, in: text) {
        if let day = Int(RE.group(ns, match, 1)), let month = Int(RE.group(ns, match, 2)), let year = expandYear(RE.group(ns, match, 3)) {
            add(match, LocalDate.valid(year: year, month: month, day: day))
        }
    }
    for (ns, match) in RE.matches(dateWeekday, in: text) {
        add(match, dateFromParts(RE.group(ns, match, 1), RE.group(ns, match, 2), nil, today: today))
    }
    for (ns, match) in RE.matches(dateDayMonth, in: text) {
        add(match, dateFromParts(RE.group(ns, match, 1), RE.group(ns, match, 2), nil, today: today))
    }
    return dropOverlaps(hits)
}

private func dropOverlaps<T>(_ hits: [Hit<T>]) -> [Hit<T>] {
    let sorted = hits.sorted { lhs, rhs in
        if lhs.start != rhs.start { return lhs.start < rhs.start }
        return (lhs.end - lhs.start) > (rhs.end - rhs.start)
    }
    var kept: [Hit<T>] = []
    for hit in sorted {
        if kept.contains(where: { hit.start < $0.end && $0.start < hit.end }) { continue }
        kept.append(hit)
    }
    return kept.sorted { $0.start < $1.start }
}

private func dateFromParts(_ dayRaw: String, _ monthRaw: String, _ yearRaw: String?, today: LocalDate) -> LocalDate? {
    guard let day = Int(dayRaw), let month = months[monthRaw] else { return nil }
    if let yearRaw, let year = expandYear(yearRaw) {
        return LocalDate.valid(year: year, month: month, day: day)
    }
    return inferYear(today: today, month: month, day: day)
}

/// Two-digit years are 20xx. A missing year stays in this year when it is bookable, otherwise next year.
public func inferYear(today: LocalDate, month: Int, day: Int) -> LocalDate? {
    let earliest = today.adding(days: -2)
    let latest = today.adding(days: 365)
    let thisYear = LocalDate.valid(year: today.year, month: month, day: day)
    if let thisYear, thisYear >= earliest, thisYear <= latest { return thisYear }
    let next = LocalDate.valid(year: today.year + 1, month: month, day: day)
    if let next, next >= earliest, next <= latest { return next }
    return thisYear
}

private func expandYear(_ raw: String) -> Int? {
    guard let year = Int(raw) else { return nil }
    switch raw.count {
    case 4: return (2000...2099).contains(year) ? year : nil
    case 2: return 2000 + year
    default: return nil
    }
}

private struct Paired {
    var order: Int
    var flight: FlightNumber
    var date: LocalDate?
    var from: String?
    var to: String?
}

private func ownerIndex(position: Int, flights: [FlightHit]) -> Int {
    if let preceding = flights.lastIndex(where: { $0.end <= position }) { return preceding }
    return 0
}

private func pairFlights(_ text: String, flights: [FlightHit], dates: [Hit<LocalDate>]) -> [Paired] {
    let routes = routeHits(text)
    return flights.enumerated().map { index, flight in
        let date = dates.filter { ownerIndex(position: $0.start, flights: flights) == index }
            .min { abs($0.start - flight.start) < abs($1.start - flight.start) }?.value
        let route = routes.filter { ownerIndex(position: $0.start, flights: flights) == index }
            .min { abs($0.start - flight.start) < abs($1.start - flight.start) }?.value
        return Paired(order: flight.start, flight: flight.flight, date: date, from: route?.0, to: route?.1)
    }
}

private func routeHits(_ text: String) -> [Hit<(String, String)>] {
    var hits: [Hit<(String, String)>] = []
    for (ns, match) in RE.matches(iataPair, in: text) {
        let from = iataOrNil(RE.group(ns, match, 1))
        let to = iataOrNil(RE.group(ns, match, 2))
        if let from, let to, from != to {
            hits.append(Hit(start: match.range.location, end: match.range.location + match.range.length, value: (from, to)))
        }
    }
    var cursor = text.startIndex
    while cursor < text.endIndex {
        let lineEnd = text[cursor...].firstIndex(of: "\n") ?? text.endIndex
        let line = String(text[cursor..<lineEnd])
        if !RE.contains(iataPair, in: line) {
            let codes = RE.matches(iataParen, in: line).compactMap { iataOrNil(RE.group($0.0, $0.1, 1)) }
            var distinct: [String] = []
            for code in codes where !distinct.contains(code) { distinct.append(code) }
            if distinct.count >= 2 {
                let start = text.distance(from: text.startIndex, to: cursor)
                let end = text.distance(from: text.startIndex, to: lineEnd)
                hits.append(Hit(start: start, end: end, value: (distinct[0], distinct[1])))
            }
        }
        cursor = lineEnd == text.endIndex ? text.endIndex : text.index(after: lineEnd)
    }
    return hits
}

private func iataOrNil(_ code: String) -> String? {
    code.count == 3 && !notIATA.contains(code) ? code : nil
}

private func findPnr(_ text: String) -> String? {
    for (ns, match) in RE.matches(pnrLabel, in: text) {
        let token = RE.group(ns, match, 1)
        if parseFlightNumber(token) != nil { continue }
        if let pnr = normalizePnr(token), pnr.count == 6 { return pnr }
    }
    return nil
}

public enum TicketMedia: String, Sendable, Equatable {
    case pdf, image, text, unknown
}

public func mediaKind(mime: String?, name: String?) -> TicketMedia {
    let type = mime?.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
    let file = name?.lowercased() ?? ""
    if type == "application/pdf" || file.hasSuffix(".pdf") { return .pdf }
    if type.hasPrefix("image/") || file.hasSuffix(".png") || file.hasSuffix(".jpg") || file.hasSuffix(".jpeg") || file.hasSuffix(".webp") {
        return .image
    }
    if type.hasPrefix("text/") || file.hasSuffix(".txt") { return .text }
    return .unknown
}

public func trackSource(for kinds: [TicketMedia]) -> TrackSource {
    if !kinds.isEmpty && kinds.allSatisfy({ $0 == .pdf }) { return .pdf }
    if !kinds.isEmpty && kinds.allSatisfy({ $0 == .image }) { return .screenshot }
    return .share
}

public let maxBitmapEdge = 1_600
public let maxPDFPages = 6
public let maxTicketCharacters = 20_000

public func scaledSize(width: Int, height: Int, maxEdge: Int = maxBitmapEdge) -> (Int, Int) {
    if width <= 0 || height <= 0 { return (1, 1) }
    let longest = max(width, height)
    if longest <= maxEdge { return (width, height) }
    let scale = Double(maxEdge) / Double(longest)
    return (max(1, Int(Double(width) * scale)), max(1, Int(Double(height) * scale)))
}

public struct CloudTicketFlight: Equatable, Sendable {
    public var flight: String?
    public var date: String?
    public var from: String?
    public var to: String?

    public init(flight: String?, date: String?, from: String?, to: String?) {
        self.flight = flight
        self.date = date
        self.from = from
        self.to = to
    }
}

public struct CloudTicketResponse: Equatable, Sendable {
    public var pnr: String?
    public var flights: [CloudTicketFlight]

    public init(pnr: String?, flights: [CloudTicketFlight]) {
        self.pnr = pnr
        self.flights = flights
    }

    /// Drops anything the model returned that is not a real flight number, date, airport or PNR.
    public func validated() -> ParsedTicket {
        let parsed = flights.compactMap { dto -> ParsedFlight? in
            guard let flight = dto.flight.flatMap(parseFlightNumber) else { return nil }
            let date = dto.date.flatMap(LocalDate.parse)
            return ParsedFlight(
                flight: flight,
                date: date,
                fromIata: airportCode(dto.from),
                toIata: airportCode(dto.to)
            )
        }
        return ParsedTicket(flights: parsed, pnr: normalizePnr(pnr).flatMap { $0.count == 6 ? $0 : nil })
    }
}

private func airportCode(_ raw: String?) -> String? {
    guard let text = raw?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased(), text.count == 3 else { return nil }
    return text.allSatisfy(\.isLetter) ? text : nil
}

public enum CloudParseResult: Equatable, Sendable {
    case parsed(ParsedTicket)
    case rateLimited
    case notConfigured
    case offline
    case failed
}
