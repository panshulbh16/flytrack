import Foundation

/// `profiles.alert_language` (P2 contract section 4). Hinglish exists only for alerts, not for the app UI.
public enum AlertLanguage: String, CaseIterable, Sendable, Equatable {
    case en, hi, hinglish

    public var wire: String { rawValue }

    /// Unknown or missing values read as English, like the server's renderer.
    public static func fromWire(_ value: String?) -> AlertLanguage {
        guard let value, let language = AlertLanguage(rawValue: value) else { return .en }
        return language
    }
}

/// The contract's default: Hindi when the phone's language is Hindi, else English.
public func defaultAlertLanguage(deviceLanguage: String?) -> AlertLanguage {
    guard let raw = deviceLanguage?.lowercased() else { return .en }
    let base = raw.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map(String.init) ?? raw
    return base == "hi" ? .hi : .en
}

/// A wall-clock time with minute precision (a Postgres `time` column).
public struct ClockTime: Hashable, Comparable, Sendable {
    public var hour: Int
    public var minute: Int

    public init(hour: Int, minute: Int) {
        self.hour = min(23, max(0, hour))
        self.minute = min(59, max(0, minute))
    }

    public var minutesOfDay: Int { hour * 60 + minute }

    /// "HH:mm", which Postgres reads as a `time`.
    public var pg: String { String(format: "%02d:%02d", hour, minute) }

    public static func < (lhs: ClockTime, rhs: ClockTime) -> Bool { lhs.minutesOfDay < rhs.minutesOfDay }
}

/// Postgres `time` ("23:00:00", "07:30", "06:15:00.5") to a minute-precision time; nil when unreadable.
public func parsePgTime(_ value: String?) -> ClockTime? {
    guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines),
          text.range(of: #"^\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?$"#, options: .regularExpression) != nil else { return nil }
    let parts = text.split(separator: ":")
    guard parts.count >= 2, let hour = Int(parts[0]), let minute = Int(parts[1]), hour <= 23, minute <= 59 else { return nil }
    return ClockTime(hour: hour, minute: minute)
}

/// The user's alert settings, kept on the server in `profiles`.
public struct AlertPrefs: Hashable, Sendable {
    public static let defaultQuietStart = ClockTime(hour: 23, minute: 0)
    public static let defaultQuietEnd = ClockTime(hour: 7, minute: 0)
    public static let defaultQuietTz = "Asia/Kolkata"

    public var quietHoursEnabled: Bool
    public var quietStart: ClockTime
    public var quietEnd: ClockTime
    /// IANA zone the quiet hours are read in.
    public var quietTz: String
    public var language: AlertLanguage

    public init(
        quietHoursEnabled: Bool = false, quietStart: ClockTime = AlertPrefs.defaultQuietStart,
        quietEnd: ClockTime = AlertPrefs.defaultQuietEnd, quietTz: String = AlertPrefs.defaultQuietTz,
        language: AlertLanguage = .en
    ) {
        self.quietHoursEnabled = quietHoursEnabled
        self.quietStart = quietStart
        self.quietEnd = quietEnd
        self.quietTz = quietTz
        self.language = language
    }

    /// Reads a `profiles` row (only the P2 columns are looked at). Missing or unreadable values take the defaults.
    public init(profileRow row: [String: Any]) {
        self.init(
            quietHoursEnabled: JSON.bool(row["quiet_hours_enabled"]) ?? false,
            quietStart: parsePgTime(JSON.string(row["quiet_start"])) ?? Self.defaultQuietStart,
            quietEnd: parsePgTime(JSON.string(row["quiet_end"])) ?? Self.defaultQuietEnd,
            quietTz: JSON.string(row["quiet_tz"])?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty ?? Self.defaultQuietTz,
            language: AlertLanguage.fromWire(JSON.string(row["alert_language"]))
        )
    }

    /// Whether `now` falls in the quiet window (start inclusive, end exclusive, may wrap past midnight). The
    /// server decides for real; the app uses this only to say "Quiet hours are on now".
    public func isQuiet(at now: Date) -> Bool {
        guard quietHoursEnabled, quietStart != quietEnd else { return false }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: quietTz) ?? TimeZone(identifier: Self.defaultQuietTz) ?? .current
        let parts = calendar.dateComponents([.hour, .minute], from: now)
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        let start = quietStart.minutesOfDay
        let end = quietEnd.minutesOfDay
        return start < end ? (minute >= start && minute < end) : (minute >= start || minute < end)
    }
}

/// The first row of a PostgREST `profiles` select (a JSON array); nil when there is no row or the JSON is not an array.
public func alertPrefs(fromProfilesJSON data: Data) -> AlertPrefs? {
    JSON.array(from: data)?.first.map { AlertPrefs(profileRow: $0) }
}

/// One change the user made in Settings.
public enum AlertPrefsChange: Hashable, Sendable {
    case quietHoursEnabled(Bool)
    case quietStart(ClockTime)
    case quietEnd(ClockTime)
    case language(AlertLanguage)
}

extension AlertPrefs {
    /// `change` applied locally (optimistic UI). A quiet-hours change also moves the zone to `deviceTz`.
    public func applying(_ change: AlertPrefsChange, deviceTz: String) -> AlertPrefs {
        var next = self
        switch change {
        case .quietHoursEnabled(let enabled):
            next.quietHoursEnabled = enabled
            next.quietTz = deviceTz
        case .quietStart(let time):
            next.quietStart = time
            next.quietTz = deviceTz
        case .quietEnd(let time):
            next.quietEnd = time
            next.quietTz = deviceTz
        case .language(let language):
            next.language = language
        }
        return next
    }
}

/// A `profiles` PATCH value: only the user-editable columns of migration 15.
public enum AlertPrefsValue: Hashable, Sendable, Encodable {
    case bool(Bool)
    case text(String)

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .bool(let value): try container.encode(value)
        case .text(let value): try container.encode(value)
        }
    }
}

/// The `profiles` PATCH for `change`. Quiet hours are read in the zone of the phone that set them, so every
/// quiet-hours change also writes `quiet_tz`. Same columns as Android's `alertPrefsPatch`.
public func alertPrefsPatch(_ change: AlertPrefsChange, deviceTz: String) -> [String: AlertPrefsValue] {
    var patch: [String: AlertPrefsValue] = [:]
    switch change {
    case .quietHoursEnabled(let enabled): patch["quiet_hours_enabled"] = .bool(enabled)
    case .quietStart(let time): patch["quiet_start"] = .text(time.pg)
    case .quietEnd(let time): patch["quiet_end"] = .text(time.pg)
    case .language(let language): patch["alert_language"] = .text(language.wire)
    }
    if case .language = change {} else { patch["quiet_tz"] = .text(deviceTz) }
    return patch
}

/// `profiles` quiet hours and alert language on the server.
@MainActor
public protocol AlertPrefsRepository: AnyObject {
    /// Nil when there is no signed-in profile or the backend is not configured.
    func load() async -> Result<AlertPrefs?, AlertPrefsError>
    func save(_ change: AlertPrefsChange, deviceTz: String) async -> Result<Void, AlertPrefsError>
}

public enum AlertPrefsError: Error, Sendable, Equatable {
    case offline, notConfigured, failed
}

public enum AlertPrefsMessage: String, Sendable, Equatable {
    case offline, saveFailed, loadFailed
}

public struct AlertPrefsState: Equatable, Sendable {
    public var isLoading: Bool
    /// False when the backend is not configured or there is no profile: the section is hidden.
    public var available: Bool
    public var prefs: AlertPrefs
    public var isSaving: Bool
    public var message: AlertPrefsMessage?

    public init(isLoading: Bool = true, available: Bool = false, prefs: AlertPrefs = AlertPrefs(), isSaving: Bool = false, message: AlertPrefsMessage? = nil) {
        self.isLoading = isLoading
        self.available = available
        self.prefs = prefs
        self.isSaving = isSaving
        self.message = message
    }
}

/// Settings → quiet hours and "Language for alerts". Optimistic: the change shows at once and is rolled back
/// when the server refuses it. Saves may overlap (two quick taps): a refused change rolls back only itself, the
/// screen always shows what the server confirmed plus the changes still on their way.
@MainActor
public final class AlertPrefsModel {
    public private(set) var state = AlertPrefsState()
    public var onChange: ((AlertPrefsState) -> Void)?

    private let repository: AlertPrefsRepository
    private let deviceTz: () -> String
    /// What the server has: the last load plus every save it accepted since.
    private var confirmed = AlertPrefs()
    private var pending: [(id: Int, change: AlertPrefsChange, tz: String)] = []
    private var nextSaveId = 0

    public init(repository: AlertPrefsRepository, deviceTz: @escaping () -> String) {
        self.repository = repository
        self.deviceTz = deviceTz
    }

    public func load() async {
        state.isLoading = true
        publish()
        switch await repository.load() {
        case .success(let prefs?):
            confirmed = prefs
            state.prefs = shown()
            state.available = true
            state.message = nil
        case .success(.none), .failure(.notConfigured):
            state.available = false
        case .failure(.offline):
            state.message = state.available ? .offline : .loadFailed
        case .failure(.failed):
            state.message = .loadFailed
        }
        state.isLoading = false
        publish()
    }

    public func apply(_ change: AlertPrefsChange) async {
        guard state.available else { return }
        let tz = deviceTz()
        let next = state.prefs.applying(change, deviceTz: tz)
        if next == state.prefs { return }
        nextSaveId += 1
        let id = nextSaveId
        pending.append((id: id, change: change, tz: tz))
        state.prefs = next
        state.isSaving = true
        state.message = nil
        publish()
        let result = await repository.save(change, deviceTz: tz)
        pending.removeAll { $0.id == id }
        switch result {
        case .success:
            confirmed = confirmed.applying(change, deviceTz: tz)
        case .failure(let error):
            state.message = error == .offline ? .offline : .saveFailed
        }
        state.prefs = shown()
        state.isSaving = !pending.isEmpty
        publish()
    }

    /// The server's values with the saves still in flight on top.
    private func shown() -> AlertPrefs {
        pending.reduce(confirmed) { $0.applying($1.change, deviceTz: $1.tz) }
    }

    public func clearMessage() {
        state.message = nil
        publish()
    }

    private func publish() { onChange?(state) }
}
