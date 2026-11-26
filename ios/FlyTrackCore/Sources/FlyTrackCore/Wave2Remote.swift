import Foundation

/// Outcome of one P2 wave 2 backend call (friends, forwarding address, calendar feed, forecast). Same cases as
/// Android's `RemoteResult`. Callers turn `refused` codes into their own typed errors.
public enum RemoteResult<Value: Sendable>: Sendable {
    case ok(Value)
    /// No connection: the request may never have reached the server.
    case offline
    /// The build has no backend keys (the app already shows a banner).
    case notConfigured
    /// The server answered with an error. `code` is the Postgres error code ("P0002", "23505", ...), `message` the
    /// raised message ("invalid_code", ...): technical, never shown.
    case refused(code: String?, message: String?)
    /// Unreadable answer or sign-in failure. Technical English: log it, do not show it.
    case failed(String?)

    /// The value, or nil for every error.
    public var value: Value? {
        if case .ok(let value) = self { return value }
        return nil
    }

    public var isOk: Bool { value != nil }

    /// The same error with another value type (for passing an error on).
    public func mapValue<Other: Sendable>(_ transform: (Value) -> Other) -> RemoteResult<Other> {
        switch self {
        case .ok(let value): return .ok(transform(value))
        case .offline: return .offline
        case .notConfigured: return .notConfigured
        case .refused(let code, let message): return .refused(code: code, message: message)
        case .failed(let message): return .failed(message)
        }
    }
}

extension RemoteResult: Equatable where Value: Equatable {}

/// A `text` RPC answer (`create_friend_invite`, `my_forwarding_address`, `my_calendar_url`): PostgREST sends a JSON
/// string. Nil when the body is not a JSON string or the string is blank.
public func rpcTextAnswer(_ data: Data) -> String? {
    guard let value = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? String else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
}

/// True when an RPC body is SQL null (or empty, as some proxies send for `void`).
public func rpcIsNull(_ data: Data) -> Bool {
    let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return text.isEmpty || text == "null"
}
