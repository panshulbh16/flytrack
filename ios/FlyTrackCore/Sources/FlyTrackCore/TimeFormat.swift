import Foundation

/// Used when an airport's zone is unknown. Same fallback as notify.ts.
public let fallbackTimeZone = "Asia/Kolkata"

public func timeZone(for identifier: String?) -> TimeZone {
    if let identifier, !identifier.isEmpty, let zone = TimeZone(identifier: identifier) {
        return zone
    }
    return TimeZone(identifier: fallbackTimeZone) ?? TimeZone(secondsFromGMT: 5 * 3600 + 1800) ?? TimeZone.current
}

public enum FlightTimeFormat {
    /// "18:50" in the airport's zone. ASCII digits, 24 hour.
    public static func time(_ instant: Date, tz: String?) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone(for: tz)
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: instant)
    }

    public static func dayMonth(_ instant: Date, tz: String?, locale: Locale = Locale(identifier: "en_US_POSIX")) -> String {
        format(instant, tz: tz, pattern: "d MMM", locale: locale)
    }

    public static func dayMonth(_ date: LocalDate, locale: Locale = Locale(identifier: "en_US_POSIX")) -> String {
        format(date, pattern: "d MMM", locale: locale)
    }

    public static func weekdayDayMonth(_ instant: Date, tz: String?, locale: Locale = Locale(identifier: "en_US_POSIX")) -> String {
        format(instant, tz: tz, pattern: "EEE, d MMM", locale: locale)
    }

    public static func weekdayDayMonth(_ date: LocalDate, locale: Locale = Locale(identifier: "en_US_POSIX")) -> String {
        format(date, pattern: "EEE, d MMM", locale: locale)
    }

    public static func fullDate(_ date: LocalDate, locale: Locale = Locale(identifier: "en_US_POSIX")) -> String {
        format(date, pattern: "EEE, d MMM yyyy", locale: locale)
    }

    public static func localDate(_ instant: Date, tz: String?) -> LocalDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone(for: tz)
        calendar.locale = Locale(identifier: "en_US_POSIX")
        let parts = calendar.dateComponents([.year, .month, .day], from: instant)
        return LocalDate(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    /// Calendar days the arrival is after the departure, each read in its own airport zone. 1 means "+1".
    public static func arrivalDayOffset(dep: Date, depTz: String?, arr: Date, arrTz: String?) -> Int {
        localDate(arr, tz: arrTz).epochDays - localDate(dep, tz: depTz).epochDays
    }

    private static func format(_ instant: Date, tz: String?, pattern: String, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone(for: tz)
        formatter.dateFormat = pattern
        return formatter.string(from: instant)
    }

    private static func format(_ date: LocalDate, pattern: String, locale: Locale) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = pattern
        return formatter.string(from: date.startOfDayUTC())
    }
}

public enum RelativeDay: Hashable, Sendable {
    case today
    case tomorrow
    case yesterday
    case inDays(Int)
    case daysAgo(Int)
}

public func relativeDay(target: LocalDate, today: LocalDate) -> RelativeDay {
    switch target.epochDays - today.epochDays {
    case 0: return .today
    case 1: return .tomorrow
    case -1: return .yesterday
    case let days where days > 0: return .inDays(days)
    case let days: return .daysAgo(-days)
    }
}

public func relativeDay(target: Date, now: Date, tz: String?) -> RelativeDay {
    relativeDay(target: FlightTimeFormat.localDate(target, tz: tz), today: FlightTimeFormat.localDate(now, tz: tz))
}

public enum RelativeUnit: String, Sendable, Equatable {
    case now, minutes, hours, days
}

public struct RelativeTime: Hashable, Sendable {
    public var amount: Int
    public var unit: RelativeUnit
    public var isPast: Bool

    public init(amount: Int, unit: RelativeUnit, isPast: Bool) {
        self.amount = amount
        self.unit = unit
        self.isPast = isPast
    }
}

/// Whole units, rounded down. 59 seconds is "now". 4 min 50 sec is 4 minutes.
public func relativeTime(target: Date, now: Date) -> RelativeTime {
    let seconds = target.timeIntervalSince(now)
    let minutes = Int(seconds / 60) // truncates toward zero, matching Kotlin inWholeMinutes
    let past = seconds < 0
    let absolute = abs(minutes)
    if absolute < 1 { return RelativeTime(amount: 0, unit: .now, isPast: false) }
    if absolute < 60 { return RelativeTime(amount: absolute, unit: .minutes, isPast: past) }
    if absolute < 24 * 60 { return RelativeTime(amount: absolute / 60, unit: .hours, isPast: past) }
    return RelativeTime(amount: absolute / (24 * 60), unit: .days, isPast: past)
}

public struct DurationParts: Hashable, Sendable {
    public var hours: Int
    public var minutes: Int

    public init(hours: Int, minutes: Int) {
        self.hours = hours
        self.minutes = minutes
    }

    public func format(hoursMinutes: String = "%dh %02dm", minutesOnly: String = "%dm") -> String {
        if hours > 0 {
            return String(format: hoursMinutes, hours, minutes)
        }
        return String(format: minutesOnly, minutes)
    }
}

public func durationParts(_ interval: TimeInterval) -> DurationParts {
    let clamped = max(0, interval)
    let total = Int((clamped + 30) / 60)
    return DurationParts(hours: total / 60, minutes: total % 60)
}

public extension FlyClock {
    func today(tz: String?) -> LocalDate {
        FlightTimeFormat.localDate(now(), tz: tz)
    }
}

/// Dates the backend's search accepts: 00:00 UTC of the date is between two days ago and 365 days ahead.
public enum SearchWindow {
    public static func contains(_ date: LocalDate, now: Date) -> Bool {
        let day = date.startOfDayUTC()
        return day >= now.addingTimeInterval(-2 * 86_400) && day <= now.addingTimeInterval(365 * 86_400)
    }

    public static func dates(now: Date) -> ClosedRange<LocalDate> {
        let firstCandidate = FlightTimeFormat.localDate(now.addingTimeInterval(-2 * 86_400), tz: "UTC")
        let first = contains(firstCandidate, now: now) ? firstCandidate : firstCandidate.adding(days: 1)
        let last = FlightTimeFormat.localDate(now.addingTimeInterval(365 * 86_400), tz: "UTC")
        return first...last
    }
}

public func selectableDates(today: LocalDate, now: Date) -> ClosedRange<LocalDate> {
    let server = SearchWindow.dates(now: now)
    let first = max(today.adding(days: -1), server.lowerBound)
    return first...max(first, server.upperBound)
}
