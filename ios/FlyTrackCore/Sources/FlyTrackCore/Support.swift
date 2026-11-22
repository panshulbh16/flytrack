import Foundation

/// A calendar date with no time and no zone. Flight dates are the departure airport's local date.
public struct LocalDate: Hashable, Comparable, Sendable {
    public var year: Int
    public var month: Int
    public var day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// "2026-11-25". Nil when the text is not that shape or the day does not exist.
    public static func parse(_ text: String) -> LocalDate? {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]) else {
            return nil
        }
        return valid(year: year, month: month, day: day)
    }

    public static func valid(year: Int, month: Int, day: Int) -> LocalDate? {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
        let parts = DateComponents(year: year, month: month, day: day)
        guard let date = calendar.date(from: parts) else { return nil }
        let back = calendar.dateComponents([.year, .month, .day], from: date)
        guard back.year == year, back.month == month, back.day == day else { return nil }
        return LocalDate(year: year, month: month, day: day)
    }

    public var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }

    public func adding(days: Int) -> LocalDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
        let start = startOfDayUTC()
        guard let next = calendar.date(byAdding: .day, value: days, to: start) else { return self }
        let parts = calendar.dateComponents([.year, .month, .day], from: next)
        return LocalDate(year: parts.year ?? year, month: parts.month ?? month, day: parts.day ?? day)
    }

    public func startOfDayUTC() -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
        return calendar.date(from: DateComponents(year: year, month: month, day: day)) ?? Date(timeIntervalSince1970: 0)
    }

    public func startOfDay(in timeZone: TimeZone) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        return calendar.date(from: DateComponents(year: year, month: month, day: day)) ?? startOfDayUTC()
    }

    public var epochDays: Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
        let epoch = calendar.date(from: DateComponents(year: 1970, month: 1, day: 1)) ?? Date(timeIntervalSince1970: 0)
        return calendar.dateComponents([.day], from: epoch, to: startOfDayUTC()).day ?? 0
    }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }
}

public protocol FlyClock: Sendable {
    func now() -> Date
}

public struct SystemClock: FlyClock {
    public init() {}
    public func now() -> Date { Date() }
}

public final class ManualClock: FlyClock, @unchecked Sendable {
    private let lock = NSLock()
    private var instant: Date

    public init(_ instant: Date) {
        self.instant = instant
    }

    public func now() -> Date {
        lock.lock()
        defer { lock.unlock() }
        return instant
    }

    public func set(_ instant: Date) {
        lock.lock()
        defer { lock.unlock() }
        self.instant = instant
    }

    public func advance(seconds: TimeInterval) {
        lock.lock()
        defer { lock.unlock() }
        instant = instant.addingTimeInterval(seconds)
    }
}

/// PostgREST timestamps ("2026-11-25T13:20:00+00:00", with or without fractional seconds) and
/// the functions' "...Z" form. Anything else is nil, the same rule as the Android mapper.
public func parseInstant(_ text: String?) -> Date? {
    guard var raw = text?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
    if raw.contains(" ") && !raw.contains("T") {
        raw = raw.replacingOccurrences(of: " ", with: "T")
    }
    let withFraction = ISO8601DateFormatter()
    withFraction.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = withFraction.date(from: raw) { return date }
    let plain = ISO8601DateFormatter()
    plain.formatOptions = [.withInternetDateTime]
    return plain.date(from: raw)
}

enum JSON {
    static func object(from data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func array(from data: Data) -> [[String: Any]]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]]
    }

    static func string(_ value: Any?) -> String? {
        if value == nil || value is NSNull { return nil }
        if let text = value as? String { return text }
        return nil
    }

    static func bool(_ value: Any?) -> Bool? {
        if let flag = value as? Bool { return flag }
        if let number = value as? NSNumber, isJSONBoolean(number) { return number.boolValue }
        return nil
    }

    static func int(_ value: Any?) -> Int? {
        if let int = value as? Int { return int }
        if let double = value as? Double, double.rounded() == double { return Int(double) }
        if let number = value as? NSNumber, !isJSONBoolean(number) { return number.intValue }
        if let text = value as? String { return Int(text) }
        return nil
    }

    static func double(_ value: Any?) -> Double? {
        if let double = value as? Double { return double }
        if let int = value as? Int { return Double(int) }
        if let number = value as? NSNumber, !isJSONBoolean(number) { return number.doubleValue }
        if let text = value as? String { return Double(text) }
        return nil
    }

    /// JSON booleans arrive as NSNumber with a boolean type code. Real numbers use an integer or double code.
    private static func isJSONBoolean(_ number: NSNumber) -> Bool {
        let type = String(cString: number.objCType)
        return type == "c" || type == "B"
    }

    static func object(_ value: Any?) -> [String: Any]? {
        value as? [String: Any]
    }
}

enum RE {
    static func regex(_ pattern: String) -> NSRegularExpression {
        do {
            return try NSRegularExpression(pattern: pattern, options: [])
        } catch {
            preconditionFailure("bad regular expression: \(pattern) \(error)")
        }
    }

    static func matches(_ pattern: String, in text: String) -> [(NSString, NSTextCheckingResult)] {
        let expression = regex(pattern)
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        return expression.matches(in: text, options: [], range: range).map { (ns, $0) }
    }

    static func first(_ pattern: String, in text: String) -> (NSString, NSTextCheckingResult)? {
        let expression = regex(pattern)
        let ns = text as NSString
        let range = NSRange(location: 0, length: ns.length)
        guard let match = expression.firstMatch(in: text, options: [], range: range) else { return nil }
        return (ns, match)
    }

    static func contains(_ pattern: String, in text: String) -> Bool {
        first(pattern, in: text) != nil
    }

    static func group(_ ns: NSString, _ match: NSTextCheckingResult, _ index: Int) -> String {
        let range = match.range(at: index)
        if range.location == NSNotFound { return "" }
        return ns.substring(with: range)
    }
}
